package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.TapAPIController
import ai.datris.model.{DatrisEnvironment, TapConfig}
import com.google.gson.JsonArray

import java.nio.charset.StandardCharsets

/** In-memory hold of the last successful `mode=test` preview per tenant + tap,
  * so `POST /tap/run` with `reuseLastTest: true` (sent by
  * `propose_pipeline_schema`) can answer from the rows the user's test already
  * returned instead of running the tap a second time.
  *
  * In memory only (precedent: [[TapGenerationProgress]]): raw source rows are
  * never written to the config DB or a catalog backup, and a restart only costs
  * one re-run. A held entry is valid only while the tap's script identity
  * ([[TapScriptIdentity]]) is unchanged, for [[TapTestPreviewCache.HoldMs]], and
  * when the request asks for no more preview rows than were held.
  */
class TapTestPreviewCache(clock: () => Long = () => System.currentTimeMillis()) {

    import TapTestPreviewCache._

    // Insertion-ordered so the eldest entry is evicted first once the bound is hit.
    private val entries = new java.util.LinkedHashMap[String, Held]()

    /** Tenant-scoped key, same shape as `TapGenerationProgress.key`. */
    private def key(tapName: String): String = DatrisEnvironment.current.environment + "|" + tapName

    def put(
        tap: TapConfig,
        records: JsonArray,
        columns: java.util.List[String],
        recordCount: Int,
        recordsTruncated: Boolean,
        testLimit: Int,
        dataType: String = "json",
        scriptId: Option[String] = None
    ): Unit = {
        if (tap == null || tap.name == null || records == null || records.size() == 0) return
        if (records.toString.getBytes(StandardCharsets.UTF_8).length > MaxPreviewBytes) {
            drop(tap.name)
            return
        }
        // The identity captured before the run when the caller has it (a script
        // replaced mid-test must not get the old script's rows), else read now.
        val id = scriptId.orElse(TapScriptIdentity.of(tap)) match {
            case Some(id) => id
            case None =>
                // An unreadable script can never be matched later; do not hold.
                drop(tap.name)
                return
        }
        val held = Held(
            records = records.deepCopy(),
            columns = columns,
            recordCount = recordCount,
            recordsTruncated = recordsTruncated,
            scriptId = id,
            testLimit = testLimit,
            capturedAtMs = clock(),
            dataType = dataType
        )
        val k = key(tap.name)
        entries.synchronized {
            entries.remove(k)
            entries.put(k, held)
            while (entries.size() > MaxEntries) {
                val eldest = entries.keySet().iterator()
                eldest.next()
                eldest.remove()
            }
        }
    }

    /** The held preview for `tap`, or None when nothing is held, the script
      * identity changed, the entry is older than [[TapTestPreviewCache.HoldMs]],
      * or `requestedTestLimit` previews more rows than the held test did. */
    def get(tap: TapConfig, requestedTestLimit: Int): Option[Held] = {
        if (tap == null || tap.name == null) return None
        val k = key(tap.name)
        val held = entries.synchronized(Option(entries.get(k)))
        held.flatMap { h =>
            val age = clock() - h.capturedAtMs
            if (age > HoldMs) {
                entries.synchronized { if (entries.get(k) eq h) entries.remove(k) }
                None
            } else if (TapAPIController.testPreviewSize(requestedTestLimit) > TapAPIController.testPreviewSize(h.testLimit)) None
            else if (!TapScriptIdentity.of(tap).contains(h.scriptId)) None
            else Some(h)
        }
    }

    def drop(tapName: String): Unit = {
        if (tapName == null) return
        val k = key(tapName)
        entries.synchronized(entries.remove(k))
    }
}

object TapTestPreviewCache extends TapTestPreviewCache(() => System.currentTimeMillis()) {

    /** How long a held test preview stays usable: 30 minutes. */
    val HoldMs: Long = 30L * 60L * 1000L

    /** Bound on held taps across all tenants; the oldest entry is evicted. */
    val MaxEntries: Int = 200

    /** A preview whose JSON exceeds this is not held. */
    val MaxPreviewBytes: Int = 4 * 1024 * 1024

    case class Held(
        records: JsonArray,
        columns: java.util.List[String],
        recordCount: Int,
        recordsTruncated: Boolean,
        scriptId: String,
        testLimit: Int,
        capturedAtMs: Long,
        dataType: String = "json"
    )
}
