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
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.collection.mutable

/** Durable, admin-readable record of who did what on the platform.
  *
  * Write path: request threads (via [[ai.datris.config.AuditInterceptor]] and
  * the direct `system` / `record` / `denied` calls) hand entries to a bounded
  * queue; one daemon writer thread drains it into Mongo (`{env}-audit-log`).
  * The request is never blocked and never sees a write failure. Every entry
  * is also emitted as a structured INFO line on logger `ai.datris.audit`
  * (one JSON object per event under the `production` profile) so a SIEM
  * already scraping container logs gets the trail with no integration work.
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

    private val queue = new AuditQueue(QueueCapacity)
    private val started = new AtomicBoolean(false)
    private val dropped = new AtomicLong(0)
    private val lastQueueWarnMs = new AtomicLong(0)
    private val indexedTables = mutable.Set[String]()

    // collapseKey -> (document id, window start)
    private val collapseState = mutable.Map[String, (ObjectId, Long)]()

    def enabled: Boolean = {
        val v = DatrisEnvironment.values
        v != null && v.useAuditLog
    }

    /** Snapshot for the UI's disabled/enabled banner and for tests. */
    def droppedCount: Long = dropped.get()
    def queueDepth: Int = queue.size

    // ------------------------------------------------------------------
    // Producers
    // ------------------------------------------------------------------

    /** Enqueue an entry. Returns false when auditing is off or the entry was
      * rejected; never throws, never blocks. */
    def submit(entry: AuditEntry): Boolean = {
        if (!enabled) return false
        try {
            val withTable =
                if (entry.tableName != null) entry
                else entry.copy(tableName = DatrisEnvironment.current.auditLogTableName)
            ensureWriter()
            if (queue.offer(withTable)) {
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
        submit(AuditEntry(
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
        ))
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

    // ------------------------------------------------------------------
    // Writer
    // ------------------------------------------------------------------

    private def ensureWriter(): Unit = {
        if (started.compareAndSet(false, true)) {
            val t = new Thread(() => writerLoop(), "audit-log-writer")
            t.setDaemon(true)
            t.start()
            Runtime.getRuntime.addShutdownHook(new Thread(() => flushOnShutdown(), "audit-log-flush"))
        }
    }

    private def writerLoop(): Unit = {
        while (true) {
            try {
                queue.poll(1000L).foreach(write)
            } catch {
                case _: InterruptedException => return
                case e: Throwable =>
                    logger.warn("Audit writer loop error (continuing): " + e.getMessage)
            }
        }
    }

    private def flushOnShutdown(): Unit = {
        try {
            system("system", "stop")
            val pending = queue.drain()
            pending.foreach(e => try write(e) catch { case _: Throwable => })
        } catch {
            case _: Throwable =>
        }
    }

    private def write(entry: AuditEntry): Unit = {
        val json = entry.toJson
        if (emitLogLine)
            auditLogger.info("audit {}", StructuredArguments.raw("audit", json.toString))
        Metrics.counter("datris_audit_events_total", "category", entry.category, "outcome", entry.outcome).increment()

        val coll = collectionFor(entry.tableName)
        if (coll == null) return
        try {
            ensureIndexes(entry.tableName, coll)
            val key = entry.category + ":" + entry.action
            if (Collapsible.contains(key)) writeCollapsed(entry, coll)
            else coll.insertOne(entry.toDocument)
        } catch {
            case e: Exception =>
                logger.warn("Audit entry not persisted (" + entry.category + ":" + entry.action + "): " + e.getMessage, e)
        }
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

    private def warnIfNearlyFull(): Unit = {
        val depth = queue.size
        Metrics.gauge("datris_audit_queue_depth", queue, (q: AuditQueue) => q.size.toDouble)
        if (depth > QueueCapacity * WarnFraction) {
            val now = System.currentTimeMillis()
            val last = lastQueueWarnMs.get()
            if (now - last > 60000L && lastQueueWarnMs.compareAndSet(last, now))
                logger.warn("Audit log queue is " + depth + "/" + QueueCapacity + " full — entries will be dropped if the config store cannot keep up (dropped so far: " + dropped.get() + ")")
        }
    }
}
