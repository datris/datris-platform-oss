package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.{HiddenTapScrub, ScopeDenialFixtures}
import ai.datris.incident.{Incident, IncidentStep}
import ai.datris.model._
import ai.datris.util.InMemoryUserDb
import com.google.gson.JsonObject
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import scala.collection.JavaConverters._

/** Story: Four small read leaks for callers limited to a catalog
  * (plans/stories/limited-caller-read-leaks.md), D1 cost (lead ruling 3):
  * the free-text scrub of a response does work proportional to the text it
  * reads, not to the number of hidden names times the number of fields.
  * Asserted on the work the scrubber counts (names tested, trie steps) and
  * on the stores being listed once, never on wall-clock time. */
class LimitedCallerIncidentTextCostSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val HiddenPerKind = 500 // 1000 hidden names in all
    private val Incidents = 200
    private val StepsPerIncident = 5 // summary + detail: 10 text fields each

    private def hiddenTap(k: Int): String = f"hr_tap_$k%04d"
    private def hiddenPipe(k: Int): String = f"hr_pipe_$k%04d"

    private val hiddenTaps: Set[String] = (0 until HiddenPerKind).map(hiddenTap).toSet
    private val hiddenPipes: Set[String] = (0 until HiddenPerKind).map(hiddenPipe).toSet

    /** Field `f` of incident `n`: two hidden names, words sharing their
      * prefix that are not hidden names, and visible names. */
    private def field(n: Int, f: Int): String = {
        val k = (n * 10 + f) % HiddenPerKind
        "tap " + hiddenTap(k) + " fed hr_tap_x and orders; downstream " + hiddenPipe(k) + " and hr_pipe_draft stale; orders_eu fine"
    }
    private val Scrubbed: String =
        "tap [hidden] fed hr_tap_x and orders; downstream [hidden] and hr_pipe_draft stale; orders_eu fine"

    test("ruling 3: a thousand hidden names over two thousand fields are found by one scan per field, not one test per name") {
        val names = new HiddenTapScrub.NameScrubber(hiddenTaps, hiddenPipes)
        assert(names.size == 2 * HiddenPerKind)
        val texts = for (n <- 0 until Incidents; f <- 0 until 2 * StepsPerIncident) yield (n, f, field(n, f))
        texts.foreach { case (n, f, t) => assert(names(t) == Scrubbed, s"$n/$f: $t") }
        val fields = texts.size.toLong
        val chars = texts.map(_._3.length.toLong).sum
        val longest = (hiddenTaps ++ hiddenPipes).map(_.length).max
        // one boundary test per occurrence of a hidden name: two per field
        assert(names.namesTested == 2 * fields, names.namesTested)
        // the trie walk is bounded by the text read, whatever the number of names
        assert(names.steps <= chars * (longest + 1), names.steps + " steps for " + chars + " chars")
        // one test per name per field would be names x fields (two million here)
        assert(names.steps + names.namesTested < (hiddenTaps.size + hiddenPipes.size).toLong * fields / 10, "work must not scale with names x fields")
    }

    test("ruling 3: text holding no character a hidden name starts with is not walked at all, and empty sets return the text itself") {
        val names = new HiddenTapScrub.NameScrubber(hiddenTaps, hiddenPipes)
        val text = "pipeline orders failed; 0 rows; retry at 09:00"
        assert(names(text) eq text)
        assert(names.steps == 0 && names.namesTested == 0, names.steps + " / " + names.namesTested)
        assert(HiddenTapScrub.scrubNames(text, Set.empty, Set.empty) eq text)
        assert(new HiddenTapScrub.NameScrubber(Set.empty, Set.empty)(text) eq text)
    }

    // ------------------------------------------------------------ through the list seam

    private val t0 = Instant.parse("2026-11-18T10:00:00Z")

    private val salesKey: ResolvedKey =
        ScopeDenialFixtures.key("sales", "pipeline:read:catalog=Sales", "tap:read:catalog=Sales", "incident:read")

    private val orders = PipelineConfig(name = "orders", catalog = "Sales", createdByKeyLabel = "own")
    private val ordersEu = PipelineConfig(name = "orders_eu", catalog = "Sales", createdByKeyLabel = "own")
    private val pipelines: Seq[PipelineConfig] =
        Seq(orders, ordersEu) ++ hiddenPipes.toSeq.map(n => PipelineConfig(name = n, catalog = "HR", createdByKeyLabel = "someone"))
    private val taps: Seq[TapConfig] =
        hiddenTaps.toSeq.map(n => TapConfig(name = n, description = "d", targetPipeline = "orders", catalog = "HR", createdByKeyLabel = "someone"))

    private def incident(n: Int): Incident = Incident(
        id = "inc-" + n,
        kind = "pipeline_failure",
        resourceType = "pipeline",
        resourceName = "orders",
        openedAt = t0.minusSeconds(n.toLong),
        state = "diagnosing",
        trigger = new JsonObject(),
        steps = (0 until StepsPerIncident).toList.map(k => IncidentStep(t0, "diagnose", field(n, 2 * k), detail = Some(field(n, 2 * k + 1))))
    )

    private var savedEnv: DatrisEnvironment = _

    override def beforeEach(): Unit = {
        savedEnv = DatrisEnvironment.values
        val e = InMemoryUserDb.env(useAuditLog = true)
        DatrisEnvironment.init(e)
        TenantContext.set(e)
        AuditLog.sinkOverride = (_: AuditEntry) => ()
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        TenantContext.clear()
        DatrisEnvironment.values = savedEnv
    }

    test("ruling 3: two hundred incidents shown to a limited caller list the stores once and scrub every field") {
        val r = ScopeDenialFixtures.request(salesKey, "GET", "/api/v1/incidents")._1
        var listed = 0
        val items = () => { listed += 1; IncidentsAPIController.StoredItems(pipelines, taps) }
        val arr = IncidentsAPIController.listedJson(
            r,
            (0 until Incidents).toList.map(incident),
            n => pipelines.find(_.name == n).orNull,
            n => taps.find(_.name == n).orNull,
            IncidentsAPIController.NoRuns,
            items
        )
        assert(arr.size == Incidents)
        assert(listed == 1, "stores listed " + listed + " times")
        arr.asScala.zipWithIndex.foreach { case (el, n) =>
            val steps = el.getAsJsonObject.getAsJsonArray("steps").asScala.map(_.getAsJsonObject).toList
            steps.zipWithIndex.foreach { case (st, k) =>
                assert(st.get("summary").getAsString == Scrubbed && st.get("detail").getAsString == Scrubbed, s"$n/$k: $st")
            }
            assert(!el.toString.contains("hr_tap_0") && !el.toString.contains("hr_pipe_0"), el)
        }
    }
}
