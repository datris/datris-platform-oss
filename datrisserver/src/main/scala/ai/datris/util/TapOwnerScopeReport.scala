package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditActor, AuditEntry, AuditLog}
import ai.datris.auth.{CallerVisibility, CapabilityCheck, TapPrincipal}
import ai.datris.auth.TapPrincipal.{Resolved, Unresolved}
import ai.datris.model.{PipelineConfig, TapConfig}
import com.google.gson.JsonObject
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}

import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.concurrent.ConcurrentHashMap

/** The run-time report lines of plans/stories/tap-runs-hold-owner-scope.md:
  * where the next release will hold a tap to its owner's or caller's access,
  * one WARN line (and, for rules 1, 3 and 4, one audit row) naming the tap,
  * the pipeline and the identity. Nothing here changes an outcome: every
  * entry point catches everything, never throws, never marks a request as
  * audited and adds no store read to a callback request.
  *
  * Volume: at most one line (and row) per tap per rule per UTC day, kept in
  * a bounded in-memory map ([[MaxEntries]]); a restart or an overflow resets
  * it, which costs at most one extra line. */
object TapOwnerScopeReport {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    val FeedOutsideOwnerAction = "feed-outside-owner-scope"
    val OwnerUnresolvedAction = "owner-unresolved"
    val ReadsNarrowedAction = "reads-narrowed"

    /** Entries the de-duplication keeps before it drops earlier days, then everything. */
    val MaxEntries = 10000

    private val seen = new ConcurrentHashMap[String, java.lang.Long]()

    private def today: Long = LocalDate.now(ZoneOffset.UTC).toEpochDay

    private def seenToday(key: String): Boolean = Option(seen.get(key)).exists(_.longValue == today)

    /** True the first time `key` is marked on the current UTC day. */
    private[util] def firstToday(key: String): Boolean = {
        val d = today
        val prev = seen.put(key, d)
        if (seen.size > MaxEntries) {
            seen.entrySet().removeIf(e => e.getValue.longValue != d)
            if (seen.size > MaxEntries) { seen.clear(); seen.put(key, d) }
        }
        prev == null || prev.longValue != d
    }

    private def safe(what: String)(body: => Unit): Unit =
        try body
        catch {
            case e: Throwable if scala.util.control.NonFatal(e) =>
                logger.debug("Tap owner access report skipped (" + what + "): " + e.getMessage)
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
      * pipeline: resolve the owner (at most once per tap, owner and target
      * per UTC day) and report rule 4 (the owner no longer resolves) or rule
      * 3 (a limited owner may not load into the stored target, read through
      * `readPipeline` only then). Never throws. */
    def afterScheduledFeed(tap: TapConfig, resolveOwner: String => TapPrincipal.Resolution, readPipeline: String => PipelineConfig): Unit =
        safe("feed") {
            if (tap == null || tap.targetPipeline == null || tap.targetPipeline.trim.isEmpty) return
            val checkKey = "feed-checked|" + tap.name + "|" + tap.createdByKeyLabel + "|" + tap.targetPipeline
            if (seenToday(checkKey)) return
            val owner = resolveOwner(tap.createdByKeyLabel)
            if (owner == null) return
            owner match {
                case Unresolved =>
                    firstToday(checkKey)
                    if (firstToday(OwnerUnresolvedAction + "|" + tap.name)) {
                        logger.warn(
                            "Tap " + q(tap.name) + " fed pipeline " + q(tap.targetPipeline) + " on a scheduled run; its owner " +
                                q(String.valueOf(tap.createdByKeyLabel)) + " no longer exists (a revoked key or a deleted user). " +
                                "In the next release such a tap's scheduled runs feed nothing and its callback reads stay in its own catalog." + Doctor
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
                    firstToday(checkKey)
                    if (TapOwnerScopeScan.feedOutsideOwner(owner, target) && firstToday(FeedOutsideOwnerAction + "|" + tap.name)) {
                        logger.warn(
                            "Tap " + q(tap.name) + " fed pipeline " + q(tap.targetPipeline) + " on a scheduled run; its owner " +
                                q(tap.createdByKeyLabel) + " may not load into that pipeline. " +
                                "In the next release this tap's scheduled runs will not feed it." + Doctor
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
                case _ => firstToday(checkKey)
            }
        }

    // ------------------------------------------------------------ rule 1: test and manual run

    /** After `POST /tap/test` or `POST /tap/run` ran `tap`: when the caller
      * is limited and the script read platform data through the callback,
      * one WARN line and one `tap/reads-narrowed` audit row. Reads nothing;
      * never marks the request as audited. Never throws. */
    def callerReads(request: HttpServletRequest, tap: TapConfig, result: TapScriptResult): Unit =
        safe("caller reads") {
            if (tap == null || result == null || request == null) return
            val caller = CapabilityCheck.resolvedKey(request)
            if (!TapOwnerScopeScan.callerReadsNarrowed(caller, result.usedCallback)) return
            if (!firstToday(ReadsNarrowedAction + "|" + tap.name)) return
            val label = caller.map(_.label).orNull
            logger.warn(
                "Tap " + q(tap.name) + " read platform data through the callback on a run by " + q(label) +
                    ", whose access is limited. In the next release those reads are held to that caller's access, " +
                    "so reads outside it are refused." + Doctor
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
            if (tap == null || request == null || tap.targetPipeline == null || tap.targetPipeline.trim.isEmpty) return
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

    // ------------------------------------------------------------ rule 6: script upload

    /** After `POST /tap/script` stored a script for the stored `tap`: when
      * the save-time target rule would refuse its stored target for the
      * caller, one WARN line. The target is read only for a restricted
      * session or a key whose loads are checked, and only once a day per tap.
      * Never throws. */
    def scriptTarget(request: HttpServletRequest, tap: TapConfig, readPipeline: String => PipelineConfig): Unit =
        safe("script target") {
            if (tap == null || request == null || tap.targetPipeline == null || tap.targetPipeline.trim.isEmpty) return
            val caller = CapabilityCheck.resolvedKey(request)
            if (!caller.exists(rk => rk.restrictedSession || CallerVisibility.loadChecked(rk))) return
            val key = "script-target|" + tap.name
            if (seenToday(key)) return
            val target = readPipeline(tap.targetPipeline)
            if (TapOwnerScopeScan.scriptTargetRefused(caller, tap, target) && firstToday(key))
                logger.warn(
                    "A script was stored for tap " + q(tap.name) + " by " + q(caller.get.label) + ", which may not load into its target pipeline " +
                        q(tap.targetPipeline) + ". In the next release this script upload is refused for that caller." + Doctor
                )
        }
}
