package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisEnvironment
import ai.datris.util.DoctorService.{CheckResult, Report, StatusError, StatusOk}
import com.google.gson.JsonObject
import org.slf4j.LoggerFactory

import java.time.Instant

/** Periodic doctor run (Phase 3). Every `doctor.intervalMinutes` the full
  * server-side report runs (never the opt-in AI probes — no spend on a
  * timer), the result is logged like the boot subset, and any check that
  * FLIPS to error — or recovers from it — is POSTed to the incident webhook
  * (`recoveryAgent.webhookUrl`) so the operator hears about a dying Vault
  * token or a filling disk before a run fails on it.
  *
  * Off by default (interval 0). The first run after boot is compared against
  * an all-ok baseline, so an error already present when the timer starts is
  * reported once, then silent until it changes. Pure pieces (`diff`, `tick`)
  * take their collaborators as arguments so a spec can drive them without a
  * server. */
object DoctorMonitor {

    private val logger = LoggerFactory.getLogger(getClass)

    val EventError = "doctor_error"
    val EventRecovered = "doctor_recovered"

    /** A status change worth telling someone about. */
    case class Transition(event: String, check: CheckResult)

    /** Compare the previous statuses (by check id) with a fresh run. Flip
      * INTO error → `doctor_error`; from error back to anything else that
      * is not a skip → `doctor_recovered`. Warn ↔ ok movement is logged but
      * never posted — the webhook is for things that need a person. A check
      * that becomes a skip (probe unavailable this run) is neither. */
    def diff(previous: Map[String, String], now: Seq[CheckResult]): Seq[Transition] =
        now.flatMap { r =>
            val before = previous.getOrElse(r.id, StatusOk)
            if (r.status == StatusError && before != StatusError) Some(Transition(EventError, r))
            else if (before == StatusError && r.status != StatusError && r.status != DoctorService.StatusSkip) Some(Transition(EventRecovered, r))
            else None
        }

    /** Webhook body; same envelope style as the incident events so one
      * receiver can handle both (`event`, `ts`, a `detail` line). */
    def payload(t: Transition, ts: Instant): JsonObject = {
        val o = new JsonObject()
        o.addProperty("event", t.event)
        o.addProperty("check", t.check.id)
        o.addProperty("status", t.check.status)
        o.addProperty("detail", t.check.detail)
        if (t.check.remediation.nonEmpty) o.addProperty("remediation", t.check.remediation)
        o.addProperty("surface", "server")
        o.addProperty("ts", ts.toString)
        o
    }

    /** One scheduler tick. Runs the report only when the interval has
      * elapsed since the last run; returns the transitions it posted (empty
      * when it did not run). `post` receives the JSON body for each one. */
    def tick(
        nowMillis: Long,
        intervalMinutes: Int,
        lastRunMillis: Option[Long],
        previous: Map[String, String],
        runReport: () => Report,
        post: JsonObject => Unit
    ): Option[(Seq[Transition], Map[String, String])] = {
        if (intervalMinutes <= 0) return None
        val due = lastRunMillis.forall(last => nowMillis - last >= intervalMinutes.toLong * 60000L)
        if (!due) return None
        val report = runReport()
        val transitions = diff(previous, report.checks)
        transitions.foreach { t =>
            try post(payload(t, Instant.ofEpochMilli(nowMillis)))
            catch {
                // Visible on purpose: a webhook that never delivers is exactly
                // the kind of silent failure the doctor exists to surface
                // (a private-network receiver needs DATRIS_ALLOW_PRIVATE_EGRESS=true).
                case e: Exception => logger.warn("DOCTOR webhook POST failed for " + t.check.id + ": " + e.getMessage)
            }
        }
        // Skips keep their prior status so a probe that is briefly
        // unavailable does not turn the next real error into a non-event.
        val next = previous ++ report.checks.filter(_.status != DoctorService.StatusSkip).map(r => r.id -> r.status)
        Some((transitions, next))
    }

    // ------------------------------------------------------------ live state

    @volatile private var intervalMinutes: Int = 0
    @volatile private var lastRun: Option[Long] = None
    @volatile private var statuses: Map[String, String] = Map.empty

    /** Called once from StartupRunner. */
    def configure(minutes: Int): Unit = {
        intervalMinutes = math.max(0, minutes)
        if (intervalMinutes > 0)
            logger.info("DOCTOR periodic run every " + intervalMinutes + " min" +
                (if (webhookUrl.isEmpty) " (no incident webhook configured — findings go to the log only)" else " → incident webhook"))
    }

    def enabled: Boolean = intervalMinutes > 0

    private def webhookUrl: String =
        Option(DatrisEnvironment.values).flatMap(v => Option(v.incidentWebhookUrl)).map(_.trim).getOrElse("")

    private def postLive(body: JsonObject): Unit = {
        val url = webhookUrl
        if (url.nonEmpty) HttpUtil.post(url, "application/json", body.toString)
    }

    /** Scheduler entry point; safe to call every minute. A doctor bug never
      * escapes into the scheduler thread. */
    def runIfDue(): Unit = {
        if (!enabled) return
        try {
            tick(System.currentTimeMillis(), intervalMinutes, lastRun, statuses, () => DoctorService.runLive("full", Set.empty, Map.empty), postLive)
                .foreach { case (transitions, next) =>
                    lastRun = Some(System.currentTimeMillis())
                    val counts = Seq(StatusOk, DoctorService.StatusWarn, StatusError, DoctorService.StatusSkip)
                        .map(s => s -> next.values.count(_ == s)).collect { case (s, n) if n > 0 => n + " " + s }
                    logger.info("DOCTOR periodic run: " + counts.mkString(", ") +
                        (if (transitions.nonEmpty) "; " + transitions.size + " change(s) posted" else ""))
                    val flipped = transitions.map(_.check.id).toSet
                    next.foreach { case (id, status) =>
                        if (status == StatusError || status == DoctorService.StatusWarn)
                            logger.warn("DOCTOR " + id + ": " + status + (if (flipped.contains(id)) " (changed)" else ""))
                    }
                    transitions.foreach(t => logger.warn("DOCTOR " + t.event + " " + t.check.id + ": " + t.check.detail +
                        (if (t.check.remediation.nonEmpty) " — " + t.check.remediation else "")))
                    statuses = next
                }
        } catch {
            case e: Exception =>
                lastRun = Some(System.currentTimeMillis())
                logger.warn("DOCTOR periodic run failed (continuing): " + e.getMessage)
        }
    }
}
