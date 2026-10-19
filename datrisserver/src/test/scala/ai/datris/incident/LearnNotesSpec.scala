package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{TapConfig, TapPromptFragment}
import ai.datris.util.AiSampleValues
import com.google.gson.JsonObject
import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: learn notes as suggested prompt fragments
  * (plans/stories/self-healing-6-env-defaults-learn-notes.md), Acceptance bullet 2 —
  * the fragment LearnNotes.suggest writes. The runner-driven cases (which
  * closes write a suggestion and which do not) are in IncidentRunnerSpec,
  * next to its in-memory incident store.
  *
  * Contract pinned here:
  * {{{
  * object LearnNotes {   // ai.datris.incident
  *   /** Longest note kept; longer notes are clipped. */
  *   val MaxNoteChars: Int = 2000
  *   /** Spec seam: the fragment write (production TapPromptFragmentIO.write). */
  *   @volatile private[incident] var write: TapPromptFragment => Unit
  *   /** Writes TapPromptFragment(key = "incident-" + incident.id,
  *     *   aliases = [TapSourceResolver.resolve(tap)], content = note clipped to
  *     *   MaxNoteChars (and AiSampleValues.scrubErrorForModel'd when
  *     *   DATRIS_AI_SAMPLE_VALUES=false), enabled = false, status = "suggested",
  *     *   origin = "incident", incidentId = incident.id).
  *     * The tap is read through IncidentRunner.tapLookup(incident.resourceName).
  *     * Never throws. */
  *   def suggest(incident: Incident, note: String): Unit
  * }
  * case class TapPromptFragment(..., status: String = null, origin: String = null, incidentId: String = null)
  * }}}
  */
class LearnNotesSpec extends AnyFunSuite {

    private val Host = "api.weather.gov"

    private def resolvedIncident(id: String = "inc_000000000042") = Incident(
        id = id,
        kind = Incident.KindTapFailure,
        resourceType = "tap",
        resourceName = "prices",
        openedAt = Instant.now(),
        state = Incident.Resolved,
        trigger = new JsonObject()
    )

    /** Run `body` with the write and tap-read seams faked; returns every fragment written. */
    private def capture(body: => Unit): List[TapPromptFragment] = {
        val written = mutable.ArrayBuffer[TapPromptFragment]()
        val savedWrite = LearnNotes.write
        val savedLookup = IncidentRunner.tapLookup
        LearnNotes.write = (f: TapPromptFragment) => written.synchronized { written += f; () }
        IncidentRunner.tapLookup = (name: String) =>
            if (name == "prices") Some(TapConfig(name = "prices", description = "d", targetPipeline = "orders", source = Host)) else None
        try body
        finally {
            LearnNotes.write = savedWrite
            IncidentRunner.tapLookup = savedLookup
        }
        written.toList
    }

    private def withSampleValues[T](value: String)(body: => T): T = {
        val saved = sys.props.get(AiSampleValues.Property)
        sys.props(AiSampleValues.Property) = value
        try body
        finally saved match {
                case Some(v) => sys.props(AiSampleValues.Property) = v
                case None => sys.props -= AiSampleValues.Property
            }
    }

    test("suggest writes incident-<id>: disabled, status suggested, origin incident, incidentId, alias = source host") {
        val note = "the source returns timestamps in local time without an offset; convert with the station timezone"
        val i = resolvedIncident()
        val written = withSampleValues("true")(capture(LearnNotes.suggest(i, note)))
        assert(written.size == 1, written)
        val f = written.head
        assert(f.key == "incident-" + i.id)
        assert(!f.enabled, "a model-written fragment never reaches a prompt until a person enables it")
        assert(f.status == "suggested")
        assert(f.origin == "incident")
        assert(f.incidentId == i.id)
        assert(Option(f.aliases).map(_.asScala.toList).contains(List(Host)), f.aliases)
        assert(f.content == note)
    }

    test("a 5,000-char note is clipped to 2,000") {
        val note = (0 until 5000).map(n => ('a' + (n % 26)).toChar).mkString
        val written = withSampleValues("true")(capture(LearnNotes.suggest(resolvedIncident(), note)))
        assert(written.size == 1, written)
        val c = written.head.content
        assert(LearnNotes.MaxNoteChars == 2000)
        assert(c.length <= 2000 && c.length >= 1990, "clipped length: " + c.length)
        assert(c.startsWith(note.take(1900)), "the clip keeps the start of the note")
    }

    test("DATRIS_AI_SAMPLE_VALUES=false: the content passes through the scrubber") {
        val note = "rows for customer alice@example.com come back with a null region; default it to the account region"
        val written = withSampleValues("false")(capture(LearnNotes.suggest(resolvedIncident(), note)))
        assert(written.size == 1, written)
        val c = written.head.content
        assert(c == AiSampleValues.scrubErrorForModel(note), c)
        assert(!c.contains("alice@example.com"), c)
    }
}
