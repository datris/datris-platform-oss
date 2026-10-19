package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.model.TapPromptFragment
import ai.datris.util.{AiSampleValues, TapPromptFragmentIO, TapPromptInjector, TapSourceResolver}
import com.google.gson.JsonObject
import org.slf4j.LoggerFactory

import java.time.Instant
import scala.util.control.NonFatal

/** Turns a resolved incident's `learnNote` (the source quirk the recovery
  * agent says is worth remembering) into a **suggested** tap prompt fragment.
  *
  * The fragment is written with `enabled = false` and `status = "suggested"`:
  * the injector only uses enabled fragments, so nothing a model wrote reaches
  * a prompt until a person accepts it (`POST /api/v1/tap-prompts` with
  * `enabled: true, status: "active"`). */
object LearnNotes {
    private val logger = LoggerFactory.getLogger(getClass)

    /** Longest note kept; longer notes are clipped. */
    val MaxNoteChars: Int = 2000

    private val ClipMarker = "… [clipped]"

    /** Spec seam: the fragment write (production TapPromptFragmentIO.write). */
    @volatile private[incident] var write: TapPromptFragment => Unit = (f: TapPromptFragment) => TapPromptFragmentIO.write(f)

    /** The fragment key for an incident's suggestion. */
    def keyFor(incidentId: String): String = "incident-" + incidentId

    private[incident] def clipNote(note: String): String =
        if (note.length <= MaxNoteChars) note
        else note.take(MaxNoteChars - ClipMarker.length) + ClipMarker

    /** Writes the suggested fragment `incident-<id>` for the incident's tap.
      * Never throws: a failure is a debug log. */
    def suggest(incident: Incident, note: String): Unit = { trySuggest(incident, note); () }

    /** [[suggest]], reporting whether the fragment was written. */
    private[incident] def trySuggest(incident: Incident, note: String): Boolean = {
        val trimmed = Option(note).map(_.trim).getOrElse("")
        if (incident == null || trimmed.isEmpty) return false
        try {
            val tap = IncidentRunner.tapLookup(incident.resourceName)
            val alias = tap.flatMap(t => Option(TapSourceResolver.resolve(t))).filter(_.nonEmpty)
            val safe = if (AiSampleValues.enabled) trimmed else AiSampleValues.scrubErrorForModel(trimmed)
            val now = Instant.now().toString
            val fragment = TapPromptFragment(
                key = keyFor(incident.id),
                aliases = alias.map(a => java.util.Arrays.asList(a)).getOrElse(new java.util.ArrayList[String]()),
                content = clipNote(safe),
                enabled = false,
                createdAt = now,
                updatedAt = now,
                status = TapPromptFragment.StatusSuggested,
                origin = TapPromptFragment.OriginIncident,
                incidentId = incident.id
            )
            write(fragment)
            try TapPromptInjector.invalidateCache()
            catch { case NonFatal(e) => logger.debug("learn note: cache invalidation skipped: " + e.getMessage) }
            try {
                val md = new JsonObject
                md.addProperty("incidentId", incident.id)
                md.addProperty("fragment", fragment.key)
                AuditLog.system("tap-prompt", "suggest", "tap", incident.resourceName, metadata = md)
            } catch { case NonFatal(e) => logger.debug("learn note: audit skipped: " + e.getMessage) }
            true
        } catch {
            case NonFatal(e) =>
                logger.debug("learn note for incident " + incident.id + " not saved: " + e.getMessage)
                false
        }
    }
}
