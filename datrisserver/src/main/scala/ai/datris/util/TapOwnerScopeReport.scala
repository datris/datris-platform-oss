package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditActor, AuditEntry, AuditLog}
import ai.datris.auth.{CallerVisibility, CapabilityCheck, TapPrincipal}
import ai.datris.auth.TapPrincipal.{Resolved, Unresolved}
import ai.datris.model.{PipelineConfig, TapConfig, TenantContext}
import com.google.gson.JsonObject
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}

import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.concurrent.{ArrayBlockingQueue, ConcurrentHashMap, CountDownLatch, TimeUnit}

/** The run-time report lines of plans/stories/tap-runs-hold-owner-scope.md:
  * where the next release will hold a tap to its owner's or caller's access,
  * one WARN line (and, for rules 1, 3 and 4, one audit row) naming the tap,
  * the pipeline and the identity. Nothing here changes an outcome: every
  * entry point catches everything, never throws, never marks a request as
  * audited and adds no store read to a callback request. Catalog placeholder
  * taps are never reported.
  *
  * Volume: at most one line (and row) per tap per rule per UTC day, kept in
  * a bounded in-memory map ([[MaxEntries]]); a restart or an overflow resets
  * it, which costs at most one extra line.
  *
  * The report after a scheduled feed (rules 3 and 4) runs on this object's
  * own daemon thread: the run thread only offers it to a bounded queue
  * ([[QueueCapacity]]); when the queue is full the report is dropped, which
  * costs nothing but a later line. Its owner lookup is made at most once per
  * tap, owner and target per UTC day whatever its outcome, so a failing
  * user or key store is asked once per tap a day, not once per run. */
object TapOwnerScopeReport {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    val FeedOutsideOwnerAction = "feed-outside-owner-scope"
    val OwnerUnresolvedAction = "owner-unresolved"
    val ReadsNarrowedAction = "reads-narrowed"

    /** Entries the de-duplication keeps before it drops earlier days, then everything. */
    val MaxEntries = 10000

    /** After-feed reports waiting for the report thread before new ones are dropped. */
    val QueueCapacity = 64

    private val seen = new ConcurrentHashMap[String, java.lang.Long]()

    /** The current UTC day (epoch day). A spec seam. */
    @volatile private[util] var today: () => Long = () => LocalDate.now(ZoneOffset.UTC).toEpochDay

    private def seenToday(key: String): Boolean = Option(seen.get(key)).exists(_.longValue == today())

    /** True the first time `key` is marked on the current UTC day. Past
      * [[MaxEntries]] entries, the entries of earlier days are dropped, then,
      * if still over, every entry but `key`. */
    private[util] def firstToday(key: String): Boolean = {
        val d = today()
        val prev = seen.put(key, d)
        if (seen.size > MaxEntries) {
            seen.entrySet().removeIf(e => e.getValue.longValue != d)
            if (seen.size > MaxEntries) { seen.clear(); seen.put(key, d) }
        }
        prev == null || prev.longValue != d
    }

    /** Entries in the de-duplication map (specs). */
    private[util] def seenSize: Int = seen.size

    private def safe(what: String)(body: => Unit): Unit =
        try body
        catch {
            case e: Throwable if scala.util.control.NonFatal(e) =>
                logger.debug("Tap owner access report skipped (" + what + "): " + e.getMessage)
        }

    // ------------------------------------------------------------ the report thread

    private val queue = new ArrayBlockingQueue[Runnable](QueueCapacity)

    /** The report thread: started on the first offer, and started again by a
      * later offer when it is found dead. Null until then. */
    @volatile private var worker: Thread = null

    /** The current report thread (specs). */
    private[util] def workerThread: Thread = worker

    /** Start the report thread unless one is alive. A failed start is logged
      * and leaves the report to a later offer; never throws. */
    private def ensureWorker(): Unit =
        try {
            val w = worker
            if (w == null || !w.isAlive) synchronized {
                val again = worker
                if (again == null || !again.isAlive) {
                    val t = new Thread(() => work(), "tap-owner-report")
                    t.setDaemon(true)
                    t.start()
                    worker = t
                }
            }
        } catch {
            case e: Throwable => logger.warn("Tap owner access report thread could not be started: " + e)
        }

    /** Runs each queued report; a report that throws anything is logged and
      * the next one runs. Ends only when the thread is interrupted
      * (shutdown). */
    private def work(): Unit =
        while (!Thread.currentThread().isInterrupted) {
            val task =
                try queue.take()
                catch { case _: InterruptedException => return }
            try task.run()
            catch {
                case _: InterruptedException => return
                case e: Throwable => logger.warn("Tap owner access report failed; later reports still run: " + e)
            }
        }

    /** Offer `task` to the report thread (started or replaced first when
      * needed); false (dropped) when the queue is full. Never blocks, never
      * throws. */
    private[util] def enqueue(task: Runnable): Boolean =
        try {
            ensureWorker()
            queue.offer(task)
        } catch { case e: Throwable if scala.util.control.NonFatal(e) => false }

    /** Specs: wait until every report queued before this call has run. */
    private[util] def awaitIdle(timeoutMs: Long = 10000L): Boolean = {
        ensureWorker()
        val done = new CountDownLatch(1)
        queue.offer(() => done.countDown(), timeoutMs, TimeUnit.MILLISECONDS) && done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private def metadata(rule: String, pipeline: String, identity: (String, String)): JsonObject = {
        val md = new JsonObject()
        md.addProperty("rule", rule)
        md.addProperty("pipeline", pipeline)
        md.addProperty(identity._1, identity._2)
        md.addProperty("enforced", false)
        md
    }

    private def q(s: String): String = "'" + s + "'"

    private val Doctor = " Nothing is refused in this release; `datris doctor` lists every such tap under tap.owner_scope (reported once a day)."

    // ------------------------------------------------------------ rules 3 and 4: the feed of a scheduled run

    /** After a scheduled (cron) run of `tap` handed its records to the target
      * pipeline. The calling (run) thread only offers the report to the
      * report thread's queue (dropped when full) with the request's tenant;
      * the report thread then resolves the owner (at most once per tap,
      * owner and target per UTC day, whatever the outcome) and reports rule 4
      * (the owner no longer resolves) or rule 3 (a limited owner may not
      * load into the stored target, read through `readPipeline` only then).
      * Never throws. */
    def afterScheduledFeed(tap: TapConfig, resolveOwner: String => TapPrincipal.Resolution, readPipeline: String => PipelineConfig): Unit = {
        val tenant = TenantContext.get()
        enqueue(() => {
            tenant.foreach(TenantContext.set)
            try feedReport(tap, resolveOwner, readPipeline)
            finally if (tenant.isDefined) TenantContext.clear()
        })
        ()
    }

    /** The report of [[afterScheduledFeed]], on the report thread. */
    private[util] def feedReport(tap: TapConfig, resolveOwner: String => TapPrincipal.Resolution, readPipeline: String => PipelineConfig): Unit =
        safe("feed") {
            if (tap == null || tap.targetPipeline == null || tap.targetPipeline.trim.isEmpty || TapOwnerScopeScan.placeholder(tap)) return
            val checkKey = "feed-checked|" + tap.name + "|" + tap.createdByKeyLabel + "|" + tap.targetPipeline
            // Marked before the lookup: a lookup that fails or answers nothing
            // is not repeated on the tap's later runs the same day.
            if (!firstToday(checkKey)) return
            val owner = resolveOwner(tap.createdByKeyLabel)
            if (owner == null) return
            owner match {
                case Unresolved =>
                    // Keyed by owner too: a tap handed to another owner that
                    // is also gone is reported again the same day.
                    if (firstToday(OwnerUnresolvedAction + "|" + tap.name + "|" + tap.createdByKeyLabel)) {
                        logger.warn(
                            "Tap " + q(tap.name) + " fed pipeline " + q(tap.targetPipeline) + " on a scheduled run; its owner " +
                                q(String.valueOf(tap.createdByKeyLabel)) + " no longer exists (a revoked key or a deleted user). " +
                                "In the next release such a tap's scheduled runs feed nothing and its callback reads stay in its own catalog; " +
                                "to keep it feeding, have an administrator change the tap's owner (POST /api/v1/tap/owner) to a current user or key." + Doctor
                        )
                        AuditLog.system(
                            "tap",
                            OwnerUnresolvedAction,
                            "tap",
                            tap.name,
                            metadata("owner-unresolved", tap.targetPipeline, "owner" -> tap.createdByKeyLabel),
                            outcome = "warning"
                        )
                    }
                case Resolved(rk) if rk != null && CallerVisibility.limited(rk) =>
                    val target = readPipeline(tap.targetPipeline)
                    // Keyed by owner too: a tap handed to another owner that
                    // also may not load into the target is reported again the same day.
                    if (
                        TapOwnerScopeScan.feedOutsideOwner(owner, target) &&
                        firstToday(FeedOutsideOwnerAction + "|" + tap.name + "|" + tap.createdByKeyLabel)
                    ) {
                        logger.warn(
                            "Tap " + q(tap.name) + " fed pipeline " + q(tap.targetPipeline) + " on a scheduled run; its owner " +
                                q(tap.createdByKeyLabel) + " may not load into that pipeline. " +
                                "In the next release this tap's scheduled runs will not feed it; to keep it feeding, give the owning user " +
                                "edit on that pipeline's catalog, point the tap at a pipeline its owner may load into, or have an administrator " +
                                "change the tap's owner (POST /api/v1/tap/owner) to a user or key that may load into it." + Doctor
                        )
                        AuditLog.system(
                            "tap",
                            FeedOutsideOwnerAction,
                            "tap",
                            tap.name,
                            metadata("feed-outside-owner-scope", tap.targetPipeline, "owner" -> tap.createdByKeyLabel),
                            outcome = "warning"
                        )
                    }
                case _ => ()
            }
        }

    // ------------------------------------------------------------ rule 1: test and manual run

    /** After `POST /tap/test` or `POST /tap/run` ran `tap`: when the caller
      * narrows reads ([[TapOwnerScopeScan.narrowsReads]]: limited, or lacking
      * some read the run token carries) and the script read platform data
      * through the callback, one WARN line and one `tap/reads-narrowed` audit
      * row. Reads nothing; never marks the request as audited. Never throws. */
    def callerReads(request: HttpServletRequest, tap: TapConfig, result: TapScriptResult): Unit =
        safe("caller reads") {
            if (tap == null || result == null || request == null || TapOwnerScopeScan.placeholder(tap)) return
            val caller = CapabilityCheck.resolvedKey(request)
            if (!TapOwnerScopeScan.callerReadsNarrowed(caller, result.usedCallback)) return
            if (!firstToday(ReadsNarrowedAction + "|" + tap.name)) return
            val label = caller.map(_.label).orNull
            val why = caller.flatMap(TapOwnerScopeScan.narrowingWords).getOrElse("has limited access")
            logger.warn(
                "Tap " + q(tap.name) + " read platform data through the callback on a run by " + q(label) + ", which " + why +
                    ". In the next release a test or manual run's callback reads are held to the access of whoever runs it, " +
                    "so reads outside it are refused; run the tap as a user or key that holds the reads its script makes." + Doctor
            )
            if (AuditLog.enabled)
                AuditLog.submit(
                    AuditEntry(
                        ts = Instant.now(),
                        actor = AuditActor.resolve(request),
                        category = "tap",
                        action = ReadsNarrowedAction,
                        resourceType = Some("tap"),
                        resourceName = Option(tap.name),
                        outcome = "warning",
                        request = Some(AuditLog.requestInfo(request)),
                        metadata = Some(metadata("reads-narrowed", tap.targetPipeline, "caller" -> label))
                    )
                )
        }

    // ------------------------------------------------------------ rule 5: save

    /** After a successful `POST /tap`: when an API key whose loads are
      * checked saved a tap whose target is not stored, one WARN line.
      * `readPipeline` is the save's own (memoized) read, already made for
      * that caller by the target rule, so this adds no read. Never throws. */
    def saveTarget(request: HttpServletRequest, tap: TapConfig, readPipeline: String => PipelineConfig): Unit =
        safe("save target") {
            if (tap == null || request == null || tap.targetPipeline == null || tap.targetPipeline.trim.isEmpty || TapOwnerScopeScan.placeholder(tap))
                return
            val caller = CapabilityCheck.resolvedKey(request)
            if (!caller.exists(rk => !rk.restrictedSession && CallerVisibility.loadChecked(rk))) return
            val target = readPipeline(tap.targetPipeline)
            if (TapOwnerScopeScan.saveTargetMissing(caller, tap, target) && firstToday("save-target-missing|" + tap.name))
                logger.warn(
                    "Tap " + q(tap.name) + " was saved by API key " + q(caller.get.label) + " with target pipeline " + q(tap.targetPipeline) +
                        ", which does not exist yet. In the next release this save is refused for that key until the pipeline exists; " +
                        "create the pipeline before the tap." + Doctor
                )
        }

    // ------------------------------------------------------------ script references, secrets and review
    // plans/stories/tap-save-references-in-scope.md, rules 3 (at version restore) and 4-8.

    val ReferenceWouldBeRefusedAction = "reference-would-be-refused"

    private val ReferencesDoctor =
        " Nothing is refused in this release; `datris doctor` lists such taps and keys under tap.references (reported once a day)."

    /** A script reference or secret rule that a later release enforces would
      * refuse this `site` (save, test, restore, run, generate) of `tapName`
      * for the request's caller: one WARN line and one
      * `tap/reference-would-be-refused` audit row (outcome `warning`,
      * metadata `rule`, `site`, `enforced: false`), at most once per
      * environment, tap and rule a UTC day. `reason` names nothing of
      * another tap. Never marks the request as audited; never throws. */
    def referenceWouldBeRefused(request: HttpServletRequest, tapName: String, rule: Int, site: String, reason: String): Unit =
        safe("reference") {
            reportReference(
                request,
                tapName,
                rule.toString,
                site,
                reason,
                "A later release refuses this (script reference and secret rule " + rule + ")." + ReferencesDoctor
            )
        }

    /** Rules S4 and S5 of plans/stories/tap-script-store-writes-and-deletes-in-scope.md
      * at the script upload (`site` "script"): the upload went ahead as
      * before, and a later release changes it as `later` says (the whole
      * closing text of the line). One WARN line
      * and one `tap/reference-would-be-refused` audit row (metadata `rule`
      * S4 or S5, `site`, `enforced: false`), at most once per environment,
      * tap and rule a UTC day. `reason` names nothing of another tap. Never
      * marks the request as audited; never throws. */
    def scriptStoreWouldChange(request: HttpServletRequest, tapName: String, rule: String, site: String, reason: String, later: String): Unit =
        safe("script store") {
            reportReference(request, tapName, rule, site, reason, later)
        }

    /** Rule S4 at the script upload (`site` "script") enforced for a caller
      * limited to a catalog or to what it created: the upload was refused
      * and nothing was written. One WARN line and one
      * `tap/reference-would-be-refused` audit row (outcome `denied`, metadata
      * `rule`, `site`, `enforced: true`) for every refusal. `reason` names
      * nothing of another tap. Never marks the request as audited; never
      * throws. */
    def scriptStoreRefused(request: HttpServletRequest, tapName: String, rule: String, site: String, reason: String): Unit =
        safe("script store refused") {
            reportReference(request, tapName, rule, site, reason, "The upload was refused and nothing was written.", enforced = true)
        }

    private def reportReference(
        request: HttpServletRequest,
        tapName: String,
        rule: String,
        site: String,
        reason: String,
        later: String,
        enforced: Boolean = false
    ): Unit = {
        if (tapName == null || tapName.isEmpty || CatalogOps.Placeholder.is(tapName)) return
        val env = Option(ai.datris.model.DatrisEnvironment.current).map(e => String.valueOf(e.environment)).getOrElse("null")
        if (!enforced && !firstToday(ReferenceWouldBeRefusedAction + "|" + env + "|" + rule + "|" + tapName)) return
        val label = CapabilityCheck.resolvedKey(request).map(_.label).orNull
        logger.warn(
            "Tap " + q(LogSafe.text(tapName)) + ": " + reason + " (" + site + " by " + q(LogSafe.text(label)) + "). " + later
        )
        if (AuditLog.enabled) {
            val md = new JsonObject()
            md.addProperty("rule", rule)
            md.addProperty("site", site)
            md.addProperty("enforced", enforced)
            AuditLog.submit(
                AuditEntry(
                    ts = Instant.now(),
                    actor = AuditActor.resolve(request),
                    category = "tap",
                    action = ReferenceWouldBeRefusedAction,
                    resourceType = Some("tap"),
                    resourceName = Option(tapName),
                    outcome = if (enforced) "denied" else "warning",
                    request = Option(request).map(AuditLog.requestInfo),
                    metadata = Some(md)
                )
            )
        }
    }

    /** `POST /tap/review` by an identity holding no `tap:update` (rule 4):
      * the label is recorded in [[TapReviewAccessLog]] for the current
      * environment; one WARN line is written at most once per label and tap
      * a UTC day; and one `tap/reference-would-be-refused` audit row (rule
      * `4`, site `review`, `enforced: false`, outcome `warning`) at most once
      * per label and environment a UTC day, through [[AuditLog.submit]] so
      * the request's own audit entry is kept. `tapName` may be null (a review
      * naming no tap). Never throws. */
    def reviewWithoutUpdate(request: HttpServletRequest, tapName: String): Unit =
        safe("review") {
            val label = CapabilityCheck.resolvedKey(request).map(_.label).orNull
            if (label == null) return
            val env = Option(ai.datris.model.DatrisEnvironment.current).map(e => String.valueOf(e.environment)).getOrElse("null")
            TapReviewAccessLog.record(label, env)
            val named = Option(tapName).filter(_.trim.nonEmpty)
            val tap = named.getOrElse("(no tap named)")
            if (firstToday("review-without-update|" + env + "|" + label + "|" + tap))
                logger.warn(
                    "Tap review of " + q(LogSafe.text(tap)) + " by " + q(LogSafe.text(label)) + ", which holds no tap update. " +
                        "A later release requires tap update for review; use a user or key that may update the tap." + ReferencesDoctor
                )
            if (AuditLog.enabled && firstToday("review-without-update-audit|" + env + "|" + label)) {
                val md = new JsonObject()
                md.addProperty("rule", "4")
                md.addProperty("site", "review")
                md.addProperty("enforced", false)
                AuditLog.submit(
                    AuditEntry(
                        ts = Instant.now(),
                        actor = AuditActor.resolve(request),
                        category = "tap",
                        action = ReferenceWouldBeRefusedAction,
                        resourceType = Some("tap"),
                        resourceName = named,
                        outcome = "warning",
                        request = Option(request).map(AuditLog.requestInfo),
                        metadata = Some(md)
                    )
                )
            }
        }

    // ------------------------------------------------------------ rule 6: script upload

    /** After `POST /tap/script` stored a script for the stored `tap`: when
      * the save-time target rule would refuse its stored target for the
      * caller, one WARN line. The target is read only for a restricted
      * session or a key whose loads are checked, and at most once per tap a
      * UTC day whatever the answer (a read that throws is not counted).
      * Never throws. */
    def scriptTarget(request: HttpServletRequest, tap: TapConfig, readPipeline: String => PipelineConfig): Unit =
        safe("script target") {
            if (tap == null || request == null || tap.targetPipeline == null || tap.targetPipeline.trim.isEmpty || TapOwnerScopeScan.placeholder(tap))
                return
            val caller = CapabilityCheck.resolvedKey(request)
            if (!caller.exists(rk => rk.restrictedSession || CallerVisibility.loadChecked(rk))) return
            val key = "script-target|" + tap.name
            if (seenToday(key)) return
            val target = readPipeline(tap.targetPipeline)
            val first = firstToday(key)
            if (first && TapOwnerScopeScan.scriptTargetRefused(caller, tap, target))
                logger.warn(
                    "A script was stored for tap " + q(tap.name) + " by " + q(caller.get.label) + ", which may not load into its target pipeline " +
                        q(tap.targetPipeline) + ". In the next release this script upload is refused for that caller; point the tap at a " +
                        "pipeline that caller may load into, or store the script as a user or key that may load into this one." + Doctor
                )
        }
}
