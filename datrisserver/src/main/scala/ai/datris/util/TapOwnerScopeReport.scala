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

    private val worker: Thread = {
        val t = new Thread(() => work(), "tap-owner-report")
        t.setDaemon(true)
        t.start()
        t
    }

    private def work(): Unit =
        while (true) {
            val task =
                try queue.take()
                catch { case _: InterruptedException => null }
            if (task != null) safe("report thread")(task.run())
        }

    /** Offer `task` to the report thread; false (dropped) when the queue is
      * full. Never blocks, never throws. */
    private[util] def enqueue(task: Runnable): Boolean =
        try queue.offer(task)
        catch { case e: Throwable if scala.util.control.NonFatal(e) => false }

    /** Specs: wait until every report queued before this call has run. */
    private[util] def awaitIdle(timeoutMs: Long = 10000L): Boolean = {
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
                    if (firstToday(OwnerUnresolvedAction + "|" + tap.name)) {
                        logger.warn(
                            "Tap " + q(tap.name) + " fed pipeline " + q(tap.targetPipeline) + " on a scheduled run; its owner " +
                                q(String.valueOf(tap.createdByKeyLabel)) + " no longer exists (a revoked key or a deleted user). " +
                                "In the next release such a tap's scheduled runs feed nothing and its callback reads stay in its own catalog; " +
                                "to keep it feeding, recreate the tap as a current user or key." + Doctor
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
                    if (TapOwnerScopeScan.feedOutsideOwner(owner, target) && firstToday(FeedOutsideOwnerAction + "|" + tap.name)) {
                        logger.warn(
                            "Tap " + q(tap.name) + " fed pipeline " + q(tap.targetPipeline) + " on a scheduled run; its owner " +
                                q(tap.createdByKeyLabel) + " may not load into that pipeline. " +
                                "In the next release this tap's scheduled runs will not feed it; to keep it feeding, give the owning user " +
                                "edit on that pipeline's catalog, point the tap at a pipeline its owner may load into, or recreate the tap " +
                                "as a user or key that may load into it." + Doctor
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
      * metadata `rule`, `site`, `enforced: false`), at most once per tap and
      * rule a UTC day. `reason` names nothing of another tap. Never marks
      * the request as audited; never throws. */
    def referenceWouldBeRefused(request: HttpServletRequest, tapName: String, rule: Int, site: String, reason: String): Unit =
        safe("reference") {
            if (tapName == null || tapName.isEmpty || CatalogOps.Placeholder.is(tapName)) return
            if (!firstToday(ReferenceWouldBeRefusedAction + "|" + rule + "|" + tapName)) return
            val label = CapabilityCheck.resolvedKey(request).map(_.label).orNull
            logger.warn(
                "Tap " + q(tapName) + ": " + reason + " (" + site + " by " + q(String.valueOf(label)) + "). " +
                    "A later release refuses this (script reference and secret rule " + rule + ")." + ReferencesDoctor
            )
            if (AuditLog.enabled) {
                val md = new JsonObject()
                md.addProperty("rule", rule.toString)
                md.addProperty("site", site)
                md.addProperty("enforced", false)
                AuditLog.submit(
                    AuditEntry(
                        ts = Instant.now(),
                        actor = AuditActor.resolve(request),
                        category = "tap",
                        action = ReferenceWouldBeRefusedAction,
                        resourceType = Some("tap"),
                        resourceName = Option(tapName),
                        outcome = "warning",
                        request = Option(request).map(AuditLog.requestInfo),
                        metadata = Some(md)
                    )
                )
            }
        }

    /** `POST /tap/review` by an identity holding no `tap:update` (rule 4):
      * the label is recorded in [[TapReviewAccessLog]] and one WARN line is
      * written at most once per label and tap a UTC day. `tapName` may be
      * null (a review naming no tap). Never throws. */
    def reviewWithoutUpdate(request: HttpServletRequest, tapName: String): Unit =
        safe("review") {
            val label = CapabilityCheck.resolvedKey(request).map(_.label).orNull
            if (label == null) return
            TapReviewAccessLog.record(label)
            val tap = Option(tapName).filter(_.trim.nonEmpty).getOrElse("(no tap named)")
            if (firstToday("review-without-update|" + label + "|" + tap))
                logger.warn(
                    "Tap review of " + q(tap) + " by " + q(label) + ", which holds no tap update. A later release requires tap update " +
                        "for review; use a user or key that may update the tap." + ReferencesDoctor
                )
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
