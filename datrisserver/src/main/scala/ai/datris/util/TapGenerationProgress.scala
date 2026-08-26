package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisEnvironment

import java.util.concurrent.ConcurrentHashMap

/** Live progress of in-flight tap script generations, so the UI can show what
  * a 1–3 minute blocking `/tap/generate` call is actually doing instead of a
  * silent spinner. In-memory only: generation is a single-server, short-lived
  * operation and its progress is worthless after the request returns.
  *
  * Phases: `calling-model` → (`retrying-format` on a parse failure) → `storing`.
  * Entries are removed in the generator's finally block; a poll after removal
  * (or after a restart) reports inactive, which the UI treats as "the request
  * itself will tell me the outcome". */
object TapGenerationProgress {

    case class Progress(phase: String, attempt: Int, startedAtMs: Long, phaseStartedAtMs: Long)

    private val active = new ConcurrentHashMap[String, Progress]()

    /** Tenant-scoped key: two tenants may generate same-named taps concurrently. */
    private def key(tapName: String): String = DatrisEnvironment.current.environment + "|" + tapName

    def start(tapName: String): Unit = {
        val now = System.currentTimeMillis()
        active.put(key(tapName), Progress("calling-model", 1, now, now))
    }

    def phase(tapName: String, phase: String, attempt: Int = 0): Unit = {
        val k = key(tapName)
        val existing = active.get(k)
        if (existing != null) {
            val a = if (attempt > 0) attempt else existing.attempt
            active.put(k, existing.copy(phase = phase, attempt = a, phaseStartedAtMs = System.currentTimeMillis()))
        }
    }

    def finish(tapName: String): Unit = {
        active.remove(key(tapName))
    }

    /** None when no generation is in flight for this tap. */
    def get(tapName: String): Option[Progress] = Option(active.get(key(tapName)))
}
