package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.ActivitySignals
import com.google.gson.JsonObject
import org.slf4j.LoggerFactory

/** The recovery agent's heartbeat: opens incidents for signals nothing else
  * hooks (stale taps, volume anomalies), resumes incidents whose approvals
  * were decided, and abandons whatever exceeded its limits. Runs on the
  * platform scheduler; a no-op while the feature is off. */
object IncidentSweep {

    private val logger = LoggerFactory.getLogger(getClass)

    def run(): Unit = {
        if (!IncidentRunner.enabled) return
        try {
            // 1. Move existing incidents forward first — approvals decided,
            //    limits exceeded.
            IncidentRunner.sweepOpenIncidents()

            // 2. Open incidents for sweep-only signals. Failures have their
            //    own hooks at the point of failure; the sweep covers what
            //    only shows up by looking: staleness and volume drift.
            val signals = ActivitySignals.compute()
            signals.staleTaps.foreach { s =>
                val trigger = new JsonObject()
                trigger.addProperty("cadence", s.cadenceLabel)
                s.lastRunIso.foreach(trigger.addProperty("lastRun", _))
                IncidentRunner.open(Incident.KindStale, "tap", s.name, trigger)
            }
            signals.anomalies.foreach { v =>
                val trigger = new JsonObject()
                trigger.addProperty("current", v.current)
                trigger.addProperty("prior", v.prior)
                v.deltaPct.foreach(d => trigger.addProperty("deltaPct", d))
                IncidentRunner.open(Incident.KindVolume, "pipeline", v.name, trigger)
            }
        } catch {
            case e: Exception => logger.warn("incident sweep failed: " + e.getMessage)
        }
    }
}
