package ai.datris.audit

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisEnvironment
import ai.datris.util.{LogRedactUtil, MongoDBUtil, NoSQLDbUtil}
import com.google.gson.JsonObject
import com.mongodb.MongoCommandException
import com.mongodb.client.model.{Filters, IndexOptions, Updates}
import io.micrometer.core.instrument.Metrics
import jakarta.servlet.http.HttpServletRequest
import net.logstash.logback.argument.StructuredArguments
import org.bson.Document
import org.bson.types.ObjectId
import org.slf4j.{Logger, LoggerFactory}

import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import scala.collection.mutable

/** Durable, admin-readable record of who did what on the platform.
  *
  * Write path: request threads (via [[ai.datris.config.AuditInterceptor]] and
  * the direct `system` / `record` / `denied` calls) hand entries to a bounded
  * queue; one daemon writer thread drains it into Mongo (`{env}-audit-log`).
  * Every entry is also emitted as a structured INFO line on logger
  * `ai.datris.audit` (one JSON object per event under the `production`
  * profile) so a SIEM already scraping container logs gets the trail with no
  * integration work.
  *
  * Default (best effort): the request is never blocked and never sees a write
  * failure. Queue overflow evicts the oldest entry (`datris_audit_dropped_total`)
  * and a failed Mongo write is logged and skipped.
  *
  * Strict (AUDIT_LOG_STRICT, see [[strict]]): nothing is evicted and a failed
  * write is retried with backoff until it lands, in order. While the queue is
  * at its high-water mark ([[acceptingWrites]] false) the
  * [[ai.datris.config.AuditInterceptor]] refuses audited requests with 503, so
  * the handler never runs. An entry that still cannot be queued in time is
  * counted (`datris_audit_unrecorded_total`) and emitted at ERROR on
  * `ai.datris.audit`. Residual: queued entries live in memory, so a hard kill
  * can lose entries that were accepted but not yet written.
  *
  * Everything here is a no-op while `useAuditLog` is off: no writer thread,
  * no collection, no indexes. */
object AuditLog {

    /** Structured audit stream — one line per event. Named so operators can
      * route it independently of the rest of the server log. */
    private val auditLogger: Logger = LoggerFactory.getLogger("ai.datris.audit")
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** Request attribute a controller may set to a String so the interceptor
      * records a meaningful `errorMessage` on failure. */
    val ErrorMessageAttr = "ai.datris.audit.errorMessage"

    /** Request attribute a controller may set to a JsonObject that becomes the
      * entry's `metadata` (redacted before persisting). */
    val MetadataAttr = "ai.datris.audit.metadata"

    /** Request attribute marking that an entry was already written for this
      * request by a denial path, so the interceptor doesn't double-log. */
    val RecordedAttr = "ai.datris.audit.recorded"

    /** `category:action` pairs whose bursts are folded into one record per
      * actor+resource per [[CollapseWindowMs]]. */
    val Collapsible: Set[String] = Set("document:upload", "pipeline:ingest", "pipeline:ingest-trigger")
    val CollapseWindowMs: Long = 60000L

    private val QueueCapacity = 10000
    private val WarnFraction = 0.8

    /** Strict: below `QueueCapacity - Headroom` new audited requests are
      * admitted. The headroom is sized above the request thread pool so every
      * request already past the gate can still queue its entry. */
    private val Headroom = 1000

    /** Strict: how long a producer waits for room before the entry is counted
      * as unrecorded. */
    private val StrictOfferTimeoutMs = 5000L

    /** Strict: retry backoff for a failed persist (doubling, capped). */
    private val RetryInitialMs = 100L
    private val RetryMaxMs = 5000L

    /** Strict: how long the shutdown flush keeps retrying before giving up. */
    private val ShutdownFlushMs = 10000L

    private val queue = new AuditQueue(QueueCapacity)
    private val started = new AtomicBoolean(false)
    private val dropped = new AtomicLong(0)
    private val unrecorded = new AtomicLong(0)

    /** The entry the writer thread is persisting right now (strict shutdown
      * accounting: an entry still in flight when the flush gives up is
      * counted as unrecorded rather than lost silently at JVM exit). */
    private val inFlight = new AtomicReference[AuditEntry](null)

    /** Set by the shutdown flush: strict retries stop here. */
    @volatile private var shutdownDeadlineNanos: Long = Long.MaxValue

    /** Set once the shutdown flush has drained the queue: an entry submitted
      * later would sit in memory until the JVM halts, so it is refused and
      * accounted for instead. */
    @volatile private var flushDone = false
    private val lastQueueWarnMs = new AtomicLong(0)
    private val indexedTables = mutable.Set[String]()

    // collapseKey -> (document id, window start)
    private val collapseState = mutable.Map[String, (ObjectId, Long)]()

    def enabled: Boolean = {
        val v = DatrisEnvironment.values
        v != null && v.useAuditLog
    }

    /** AUDIT_LOG_STRICT in effect (needs the audit log on). */
    def strict: Boolean = enabled && DatrisEnvironment.values.auditLogStrict

    /** Snapshot for the UI's disabled/enabled banner and for tests. */
    def droppedCount: Long = dropped.get()
    def queueDepth: Int = queue.size

    /** Entries that could not be queued under strict (since startup). */
    def unrecordedCount: Long = unrecorded.get()

    /** False only under strict while the queue is at its high-water mark
      * (capacity minus headroom): audited requests are then refused. Always
      * true in default mode. */
    def acceptingWrites: Boolean = !strict || queue.hasRoom(Headroom)

    // ------------------------------------------------------------------
    // Producers
    // ------------------------------------------------------------------

    /** Test sink; null = the bounded queue. Still gated by [[enabled]]. */
    @volatile private[datris] var sinkOverride: AuditEntry => Unit = null

    /** Test seam for the persist step of [[write]]; null = Mongo. A throw is a
      * failed persist. */
    @volatile private[datris] var persistOverride: AuditEntry => Unit = null

    /** Test seam: fixed delay between strict retries; < 0 = the real backoff. */
    @volatile private[datris] var retryDelayMsOverride: Long = -1L

    /** Test seam: strict producer wait for room; < 0 = StrictOfferTimeoutMs. */
    @volatile private[datris] var strictOfferTimeoutMsOverride: Long = -1L

    private def strictOfferTimeoutMs: Long = {
        val o = strictOfferTimeoutMsOverride
        if (o >= 0) o else StrictOfferTimeoutMs
    }

    /** Enqueue an entry. Returns false when auditing is off or the entry was
      * rejected; never throws. Never blocks in default mode; under strict it
      * waits (bounded) for room instead of evicting an older entry. */
    def submit(entry: AuditEntry): Boolean = {
        if (!enabled) return false
        try {
            val withTable =
                if (entry.tableName != null) entry
                else entry.copy(tableName = DatrisEnvironment.current.auditLogTableName)
            val sink = sinkOverride
            if (sink != null) {
                sink(withTable)
                return true
            }
            if (flushDone) {
                if (strict) markUnrecorded(withTable, "submitted after the shutdown flush")
                else logger.warn("Audit entry not recorded (" + withTable.category + ":" + withTable.action + "): submitted after the shutdown flush")
                return false
            }
            ensureWriter()
            if (strict) {
                val queued =
                    try queue.offerBlocking(withTable, strictOfferTimeoutMs)
                    catch {
                        case _: InterruptedException =>
                            Thread.currentThread().interrupt()
                            false
                    }
                if (!queued) {
                    markUnrecorded(withTable, "audit queue full for " + strictOfferTimeoutMs + "ms")
                    return false
                }
            } else if (queue.offer(withTable)) {
                dropped.incrementAndGet()
                Metrics.counter("datris_audit_dropped_total").increment()
            }
            warnIfNearlyFull()
            true
        } catch {
            case e: Exception =>
                logger.warn("Audit entry not queued: " + e.getMessage)
                false
        }
    }

    /** An event with no HTTP request behind it: scheduler, consumer, startup. */
    def system(
        category: String,
        action: String,
        resourceType: String = null,
        resourceName: String = null,
        metadata: JsonObject = null,
        outcome: String = "success",
        errorMessage: String = null
    ): Unit = {
        if (!enabled) return
        submit(AuditEntry(
            ts = Instant.now(),
            actor = AuditActorInfo.System,
            category = category,
            action = action,
            resourceType = Option(resourceType),
            resourceName = Option(resourceName),
            outcome = outcome,
            errorMessage = Option(errorMessage),
            metadata = Option(metadata).map(redact)
        ))
    }

    /** A controller-side event that needs richer detail than the interceptor
      * sees (CSV export, spoof attempt). Marks the request as recorded so the
      * interceptor does not add a second entry. */
    def record(
        request: HttpServletRequest,
        category: String,
        action: String,
        resourceType: String = null,
        resourceName: String = null,
        outcome: String = "success",
        httpStatus: Int = 200,
        metadata: JsonObject = null,
        errorMessage: String = null
    ): Unit = {
        if (!enabled) return
        request.setAttribute(RecordedAttr, java.lang.Boolean.TRUE)
        submit(AuditEntry(
            ts = Instant.now(),
            actor = AuditActor.resolve(request),
            category = category,
            action = action,
            resourceType = Option(resourceType),
            resourceName = Option(resourceName),
            outcome = outcome,
            httpStatus = Some(httpStatus),
            errorMessage = Option(errorMessage),
            request = Some(requestInfo(request)),
            metadata = Option(metadata).map(redact)
        ))
    }

    /** A request refused by an auth interceptor. Called at the point of
      * denial because a `false` from an earlier interceptor's preHandle means
      * the audit interceptor (registered last) never sees the request. */
    def denied(request: HttpServletRequest, reason: String, httpStatus: Int, required: Option[String] = None): Unit = {
        if (!enabled) return
        request.setAttribute(RecordedAttr, java.lang.Boolean.TRUE)
        val md = new JsonObject()
        md.addProperty("reason", reason)
        required.foreach(md.addProperty("required", _))
        val route = AuditClassifier.classify(request.getMethod, request.getRequestURI, logReads = true)
        route.foreach { r =>
            md.addProperty("category", r.category)
            md.addProperty("action", r.action)
        }
        val entry = AuditEntry(
            ts = Instant.now(),
            actor = AuditActor.resolve(request),
            category = "security",
            action = "denied",
            resourceType = route.map(_.resourceType),
            resourceName = None,
            outcome = "denied",
            httpStatus = Some(httpStatus),
            errorMessage = Some(reason),
            request = Some(requestInfo(request)),
            metadata = Some(md)
        )
        // Strict with the gate closed: a denial changes nothing, so do not hold
        // the request thread waiting for queue room; count it at once.
        if (strict && !acceptingWrites && sinkOverride == null)
            markUnrecorded(
                entry.copy(tableName = Option(entry.tableName).getOrElse(DatrisEnvironment.current.auditLogTableName)),
                "audit log not accepting entries"
            )
        else submit(entry)
    }

    def requestInfo(request: HttpServletRequest): AuditRequestInfo = {
        AuditRequestInfo(
            method = request.getMethod,
            path = request.getRequestURI,
            query = Option(request.getQueryString).filter(_.nonEmpty).map(LogRedactUtil.redactQueryString),
            ip = clientIp(request),
            userAgent = Option(request.getHeader("User-Agent")).map(_.take(256))
        )
    }

    /** First hop of X-Forwarded-For when present (the UI sits behind nginx in
      * compose), else the socket address. */
    def clientIp(request: HttpServletRequest): Option[String] = {
        val fwd = Option(request.getHeader("X-Forwarded-For")).map(_.split(",")(0).trim).filter(_.nonEmpty)
        fwd.orElse(Option(request.getRemoteAddr))
    }

    def redact(md: JsonObject): JsonObject = LogRedactUtil.redactJson(md).getAsJsonObject

    /** Strict: an entry that will never reach the store. Counted and emitted
      * whole at ERROR on `ai.datris.audit` so the log stream still has it. */
    private def markUnrecorded(entry: AuditEntry, why: String): Unit = {
        unrecorded.incrementAndGet()
        Metrics.counter("datris_audit_unrecorded_total").increment()
        try auditLogger.error("audit-unrecorded ({}) {}", why: Any, StructuredArguments.raw("audit", entry.toJson.toString): Any)
        catch { case _: Exception => }
    }

    // ------------------------------------------------------------------
    // Writer
    // ------------------------------------------------------------------

    private def ensureWriter(): Unit = {
        if (started.compareAndSet(false, true)) {
            val t = new Thread(() => writerLoop(), "audit-log-writer")
            t.setDaemon(true)
            t.start()
            Runtime.getRuntime.addShutdownHook(new Thread(() => jvmShutdownHook(), "audit-log-flush"))
        }
    }

    private def writerLoop(): Unit = {
        while (true) {
            try {
                queue.poll(1000L).foreach { e =>
                    inFlight.set(e)
                    try write(e, Long.MaxValue, tracked = true)
                    finally inFlight.compareAndSet(e, null)
                }
            } catch {
                case _: InterruptedException => return
                case e: Throwable =>
                    logger.warn("Audit writer loop error (continuing): " + e.getMessage)
            }
        }
    }

    /** True once a Spring context owns the shutdown flush (see
      * [[AuditLogLifecycle]]): it then runs after the web server has stopped
      * taking requests and before Spring shuts logging down, and the JVM
      * shutdown hook below stands aside. */
    @volatile private[datris] var lifecycleManaged = false

    private val flushed = new AtomicBoolean(false)

    /** Runs the shutdown flush once (Spring lifecycle stop, or the JVM hook
      * when no Spring context manages it). */
    private[datris] def shutdownFlush(): Unit =
        if (started.get() && flushed.compareAndSet(false, true)) flushOnShutdown(ShutdownFlushMs)

    private def jvmShutdownHook(): Unit = if (!lifecycleManaged) shutdownFlush()

    /** Test seam: run the shutdown flush with a short bound. The caller resets
      * with [[resetShutdownForTest]] once the writer has settled. */
    private[datris] def runShutdownFlushForTest(flushMs: Long): Unit = flushOnShutdown(flushMs)

    private[datris] def resetShutdownForTest(): Unit = {
        shutdownDeadlineNanos = Long.MaxValue
        flushDone = false
        flushed.set(false)
    }

    /** Bounded to `flushMs` in both modes: every persist attempt runs on
      * [[flushExecutor]] with the time left, so a config store that hangs
      * (rather than fails) cannot hold the JVM. Under strict, retries stop at
      * the deadline (for the writer thread's in-flight entry too) and each
      * entry left is counted as unrecorded once. The outcome is also written
      * to stderr, which outlives the logging system at JVM exit. */
    private def flushOnShutdown(flushMs: Long): Unit = {
        var lost = 0
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(flushMs)
            shutdownDeadlineNanos = deadline
            system("system", "stop")
            val pending = queue.drain()
            flushDone = true
            // Anything a producer queued between drain() and the line above.
            val stragglers = queue.drain()
            (pending ++ stragglers).foreach(e =>
                try { if (!write(e, deadline, tracked = false)) lost += 1 }
                catch { case _: Throwable => lost += 1 }
            )
            if (strict) {
                // The writer thread may still be retrying (or hanging in) its
                // entry: wait for it up to the deadline, then count it.
                while (inFlight.get() != null && System.nanoTime() < deadline) Thread.sleep(20)
                val left = inFlight.getAndSet(null)
                if (left != null) {
                    markUnrecorded(left, "still being written at shutdown")
                    lost += 1
                }
            }
        } catch {
            case _: Throwable =>
        } finally {
            if (strict || lost > 0)
                try
                    System.err.println(
                        "audit-unrecorded total=" + unrecorded.get() + " at shutdown (" + lost + " entr" + (if (lost == 1) "y" else "ies") +
                            " not persisted by the shutdown flush" + (if (strict) "" else "; AUDIT_LOG_STRICT off") + ")"
                    )
                catch { case _: Throwable => }
        }
    }

    /** One daemon thread for persist attempts made by the shutdown flush, so
      * each attempt can be abandoned at the deadline. */
    private lazy val flushExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { (r: Runnable) =>
            val t = new Thread(r, "audit-log-flush-persist")
            t.setDaemon(true)
            t
        }

    /** persist(entry) with a hard time limit; throws on timeout (the attempt
      * is interrupted and abandoned). */
    private def persistWithin(entry: AuditEntry, remainingNanos: Long): Unit = {
        val f = flushExecutor.submit(new java.util.concurrent.Callable[Unit] { def call(): Unit = persist(entry) })
        try f.get(math.max(0L, remainingNanos), TimeUnit.NANOSECONDS)
        catch {
            case _: java.util.concurrent.TimeoutException =>
                f.cancel(true)
                throw new java.util.concurrent.TimeoutException("config store did not answer before the shutdown deadline")
            case ee: java.util.concurrent.ExecutionException =>
                ee.getCause match {
                    case e: Exception => throw e
                    case t => throw new RuntimeException(t)
                }
        }
    }

    /** No Mongo config store: the entry is log-only (default) or a failed
      * persist (strict). */
    private final class NoConfigStore extends RuntimeException("Audit log requires the Mongo config store")

    /** Persist one entry; true when it was stored. Default: one attempt, a
      * failure is logged and the entry skipped. Strict: the same entry is
      * retried with backoff until it lands (the writer holds the queue's order
      * meanwhile); only the shutdown deadline stops it.
      *
      * `tracked`: the entry is the writer thread's (published in [[inFlight]]),
      * so at the shutdown deadline it is counted only if the writer, not the
      * flush, releases it. `tracked = false` is the shutdown flush: each
      * attempt is time-limited, the deadline is checked before every attempt,
      * and an entry left at the deadline is always counted (strict). */
    private def write(entry: AuditEntry, deadlineNanos: Long, tracked: Boolean): Boolean = {
        val json = entry.toJson
        if (emitLogLine)
            auditLogger.info("audit {}", StructuredArguments.raw("audit", json.toString))
        Metrics.counter("datris_audit_events_total", "category", entry.category, "outcome", entry.outcome).increment()

        var attempt = 0
        var delayMs = RetryInitialMs
        while (true) {
            if (!tracked && System.nanoTime() >= deadlineNanos) {
                if (strict) markUnrecorded(entry, "shutdown flush deadline reached")
                else logger.warn("Audit entry not persisted (" + entry.category + ":" + entry.action + "): shutdown flush deadline reached")
                return false
            }
            try {
                if (tracked) persist(entry) else persistWithin(entry, deadlineNanos - System.nanoTime())
                if (attempt > 0)
                    logger.info(
                        "Audit entry persisted after " + attempt + " retr" + (if (attempt == 1) "y" else "ies") + " (" + entry.category + ":" + entry.action + ")"
                    )
                return true
            } catch {
                case _: NoConfigStore if !strict => return false
                case e: Exception if !strict =>
                    logger.warn("Audit entry not persisted (" + entry.category + ":" + entry.action + "): " + e.getMessage, e)
                    return false
                case e: Exception =>
                    attempt += 1
                    if (System.nanoTime() >= math.min(deadlineNanos, shutdownDeadlineNanos)) {
                        // The writer's own entry is counted by whichever of the
                        // writer and the shutdown flush releases it first.
                        if (!tracked || inFlight.compareAndSet(entry, null)) markUnrecorded(entry, "not persisted before shutdown: " + e.getMessage)
                        return false
                    }
                    if (attempt == 1 || attempt % 20 == 0)
                        logger.warn(
                            "Audit entry not persisted (" + entry.category + ":" + entry
                                .action + "), AUDIT_LOG_STRICT is on, retrying (attempt " + attempt + ", queue depth " + queue.size + "): " + e.getMessage
                        )
                    val fixed = retryDelayMsOverride
                    val sleepMs = if (fixed >= 0) fixed else delayMs
                    // The flush never sleeps past its deadline.
                    Thread.sleep(if (tracked) sleepMs else math.max(0L, math.min(sleepMs, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()))))
                    delayMs = math.min(delayMs * 2, RetryMaxMs)
            }
        }
        false
    }

    private def persist(entry: AuditEntry): Unit = {
        val o = persistOverride
        if (o != null) {
            o(entry)
            return
        }
        val coll = collectionFor(entry.tableName)
        if (coll == null) throw new NoConfigStore
        ensureIndexes(entry.tableName, coll)
        val key = entry.category + ":" + entry.action
        if (Collapsible.contains(key)) writeCollapsed(entry, coll)
        else coll.insertOne(entry.toDocument)
    }

    /** Same actor + category + action + resource within the window → one
      * record with `metadata.count` / `metadata.lastTs` bumped in place. */
    private def writeCollapsed(entry: AuditEntry, coll: com.mongodb.client.MongoCollection[Document]): Unit = {
        val nowMs = entry.ts.toEpochMilli
        val key = entry.collapseKey
        collapseState.get(key) match {
            case Some((id, windowStart)) if nowMs - windowStart < CollapseWindowMs =>
                coll.updateOne(
                    Filters.eq("_id", id),
                    Updates.combine(
                        Updates.inc("metadata.count", 1),
                        Updates.set("metadata.lastTs", entry.ts.toString),
                        Updates.set("outcome", if (entry.outcome == "success") "success" else entry.outcome)
                    )
                )
            case _ =>
                val doc = entry.toDocument
                val md = Option(doc.get("metadata", classOf[Document])).getOrElse(new Document())
                md.put("count", 1: java.lang.Integer)
                md.put("firstTs", entry.ts.toString)
                md.put("lastTs", entry.ts.toString)
                doc.put("metadata", md)
                coll.insertOne(doc)
                collapseState.put(key, (doc.getObjectId("_id"), nowMs))
                // Bound the state map: drop windows that have long expired.
                if (collapseState.size > 1000)
                    collapseState.retain { case (_, (_, start)) => nowMs - start < CollapseWindowMs }
        }
    }

    private def collectionFor(table: String): com.mongodb.client.MongoCollection[Document] = {
        NoSQLDbUtil match {
            case m: MongoDBUtil => m.collection(table)
            case _ =>
                logger.warn("Audit log requires the Mongo config store; entries are log-only")
                null
        }
    }

    /** Lazily create indexes the first time a table is written to. TTL expiry
      * follows `auditLog.retentionDays`; changing it recreates the index. */
    private def ensureIndexes(table: String, coll: com.mongodb.client.MongoCollection[Document]): Unit = {
        if (indexedTables.contains(table)) return
        val retentionDays = Option(DatrisEnvironment.values).map(_.auditLogRetentionDays).getOrElse(90)
        try {
            coll.createIndex(new Document("category", 1))
            coll.createIndex(new Document("actor.label", 1))
            coll.createIndex(new Document("actor.keyId", 1))
            coll.createIndex(new Document("resource.name", 1))
            if (retentionDays > 0) {
                val opts = new IndexOptions().expireAfter(retentionDays.toLong * 86400L, TimeUnit.SECONDS)
                try coll.createIndex(new Document("tsDate", 1), opts)
                catch {
                    case e: MongoCommandException if e.getErrorCode == 85 || e.getErrorCode == 86 =>
                        // IndexOptionsConflict / IndexKeySpecsConflict: retention changed.
                        coll.dropIndex("tsDate_1")
                        coll.createIndex(new Document("tsDate", 1), opts)
                }
            } else {
                try coll.dropIndex("tsDate_1")
                catch { case _: MongoCommandException => /* no TTL index to drop */ }
                coll.createIndex(new Document("tsDate", 1))
            }
            indexedTables += table
        } catch {
            case e: Exception =>
                logger.warn("Audit log index creation failed for " + table + " (will retry on next write): " + e.getMessage)
        }
    }

    private def emitLogLine: Boolean =
        Option(DatrisEnvironment.values).forall(_.auditLogEmitLogLine)

    // Registered once; micrometer warns on re-registration.
    private lazy val queueDepthGauge: AuditQueue =
        Metrics.gauge("datris_audit_queue_depth", queue, (q: AuditQueue) => q.size.toDouble)

    private def warnIfNearlyFull(): Unit = {
        val depth = queueDepthGauge.size
        if (depth > QueueCapacity * WarnFraction) {
            val now = System.currentTimeMillis()
            val last = lastQueueWarnMs.get()
            if (now - last > 60000L && lastQueueWarnMs.compareAndSet(last, now))
                logger.warn(
                    if (strict)
                        "Audit log queue is " + depth + "/" + QueueCapacity + " full — AUDIT_LOG_STRICT is on: audited requests get 503 from " + (QueueCapacity - Headroom) + " until the config store catches up"
                    else
                        "Audit log queue is " + depth + "/" + QueueCapacity + " full — entries will be dropped if the config store cannot keep up (dropped so far: " + dropped
                            .get() + ")"
                )
        }
    }
}
