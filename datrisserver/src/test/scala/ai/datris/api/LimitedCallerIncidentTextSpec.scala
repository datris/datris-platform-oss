package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.{ChatIdentity, HiddenTapScrub, ScopeDenialFixtures}
import ai.datris.incident.{Incident, IncidentStep}
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, InMemoryUserDb, OpsAgentPrompt}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}
import jakarta.servlet.http.HttpServletRequest
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import scala.collection.JavaConverters._

/** Story: Four small read leaks for callers limited to a catalog
  * (plans/stories/limited-caller-read-leaks.md), Acceptance bullet 1 (gap 9,
  * owner decision D1 (a)): in incident free text shown to a caller limited
  * to a catalog or to what it created, the names of taps and pipelines it
  * cannot read read `[hidden]`; everyone else gets the stored text.
  *
  * Names under test:
  * {{{
  * object HiddenTapScrub {
  *   // scrubText's tap contexts for hiddenTaps, plus whole-name replacement
  *   // of hidden names in any letter case; names in `keep` are left whole;
  *   // null stays null; empty hidden sets return `text` itself
  *   def scrubNames(text: String, hiddenTaps: Set[String], hiddenPipelines: Set[String], keep: Set[String]): String
  * }
  * object IncidentsAPIController {
  *   // every stored pipeline and tap, for the names a limited caller may not read
  *   final case class StoredItems(pipelines: Seq[PipelineConfig], taps: Seq[TapConfig])
  *   // one request's listing of both stores, shared by the readers and `items`
  *   final class StoreListing(listPipelines: () => Seq[PipelineConfig], listTaps: () => Seq[TapConfig])
  * }
  * // a required parameter `items: () => IncidentsAPIController.StoredItems`
  * // (called at most once per response, never for a caller that is not
  * // limited) on IncidentsAPIController.shownIncident, visibleIncidents,
  * // visibleIncidentsFor, listedJson, getResponse and the 4-reader
  * // OpsChatAPIController.incidentsFor. Passed by name here.
  * }}}
  * The text fields are those the story lists: step `summary` and `detail`,
  * trigger `error` and `aiSummary`, `outcome`, and every string leaf of
  * `proposal`. The evidence carries no free text; its structured scrub is
  * pinned as it is today. */
class LimitedCallerIncidentTextSpec extends AnyFunSuite with BeforeAndAfterEach {

    // ================================================================== callers

    private def req(rk: ResolvedKey, uri: String = "/api/v1/incidents"): HttpServletRequest = ScopeDenialFixtures.request(rk, "GET", uri)._1
    private def key(label: String, caps: String*): ResolvedKey = ScopeDenialFixtures.key(label, caps: _*)

    /** A signed-in user restricted to the Sales catalog. */
    private val bob: ResolvedKey = APIKeyValidator.resolveFromSession(
        User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "view"))
    )

    /** A key limited to the Sales catalog. */
    private val salesKey: ResolvedKey =
        key("sales", "pipeline:read:catalog=Sales", "tap:read:catalog=Sales", "incident:read", "approval:read")

    /** A key limited to what it created (label `own`). */
    private val ownKey: ResolvedKey = key("own", "pipeline:read:owner=self", "tap:read:owner=self", "incident:read", "approval:read")

    private val limited: Seq[(String, ResolvedKey)] = Seq("restricted session" -> bob, "catalog= key" -> salesKey, "owner=self key" -> ownKey)

    private val admin: ResolvedKey = APIKeyValidator.resolveFromSession(User("admin", "h", User.RoleAdmin, null, null, null))
    private val editor: ResolvedKey = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
    private val legacy: ResolvedKey = ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)

    private def unchanged: Seq[(String, HttpServletRequest)] = Seq(
        "admin session" -> req(admin),
        "unrestricted editor session" -> req(editor),
        "*:* key" -> req(key("full", "*:*")),
        "unscoped incident key" -> req(key("ops-inc", "incident:read", "pipeline:read", "tap:read")),
        "legacy key" -> req(legacy),
        "keys off (no identity)" -> ScopeDenialFixtures.request(null, "GET", "/api/v1/incidents")._1
    )

    // ================================================================== stored items

    private def pipe(name: String, catalog: String, owner: String = "someone"): PipelineConfig =
        PipelineConfig(name = name, catalog = catalog, createdByKeyLabel = owner)
    private def tap(name: String, catalog: String, target: String, owner: String = "someone"): TapConfig =
        TapConfig(name = name, description = "d", targetPipeline = target, catalog = catalog, createdByKeyLabel = owner)

    // visible to all three limited callers (Sales, created by `own`)
    private val orders = pipe("orders", "Sales", owner = "own")
    private val ordersEu = pipe("orders_eu", "Sales", owner = "own")
    private val tOrders = tap("t_orders", "Sales", "orders", owner = "own")
    private val ordersFeedTap = tap("orders_feed", "Sales", "orders", owner = "own")
    // hidden from all three (HR, created by someone else)
    private val hrPayroll = pipe("hr_payroll", "HR")
    private val pay = pipe("pay", "HR")
    private val hiddenNamedLikeVisibleTap = pipe("orders_feed", "HR")
    private val hrFeed = tap("hr_feed", "HR", "orders")

    private val pipelines: Seq[PipelineConfig] = Seq(orders, ordersEu, hrPayroll, pay, hiddenNamedLikeVisibleTap)
    private val taps: Seq[TapConfig] = Seq(tOrders, ordersFeedTap, hrFeed)

    /** Lookups by name, as storeReaders gives them. A name held by two
      * stored pipelines (none here but orders_feed, which is a tap and a
      * pipeline) resolves to the pipeline. */
    private val readPipeline: String => PipelineConfig = n => pipelines.find(_.name == n).orNull
    private val readTap: String => TapConfig = n => taps.find(_.name == n).orNull
    private def items: () => IncidentsAPIController.StoredItems = () => IncidentsAPIController.StoredItems(pipelines, taps)

    private val failReadPipeline: String => PipelineConfig = n => fail("the pipeline store must not be read for this caller: " + n)
    private val failReadTap: String => TapConfig = n => fail("the tap store must not be read for this caller: " + n)
    private val failItems: () => IncidentsAPIController.StoredItems = () => fail("the stores must not be listed for this caller")
    private val noRuns: String => Option[RunLineage] = IncidentsAPIController.NoRuns

    // ================================================================== incidents

    private val t0 = Instant.parse("2026-11-18T10:00:00Z")

    private def obj(json: String): JsonObject = JsonParser.parseString(json).getAsJsonObject

    private def evidence: JsonObject = obj(
        """{"blastRadius":{"downstreamPipelines":["hr_payroll","orders_eu"],"downstreamDatasets":[],"ucTables":[],"consumersFromContracts":[]},"verdict":"pass","computedAt":"2026-11-18T10:00:00Z"}"""
    )

    /** An incident on the visible pipeline `orders` whose free text names
      * the hidden tap `hr_feed` and the hidden downstream pipelines
      * `hr_payroll` and `pay`, next to visible names and ordinary words
      * that contain them. */
    private def namingHidden(id: String = "inc-1", withEvidence: Boolean = true): Incident = Incident(
        id = id,
        kind = "pipeline_failure",
        resourceType = "pipeline",
        resourceName = "orders",
        openedAt = t0,
        state = "diagnosing",
        trigger = obj("""{"error":"load failed for tap 'hr_feed'","aiSummary":"hr_payroll depends on orders","pipelineToken":"tok-1"}"""),
        steps = List(
            IncidentStep(t0, "open", "pipeline orders failed loading tap-hr_feed.csv"),
            IncidentStep(
                t0.plusSeconds(60),
                "diagnose",
                "tap hr_feed sent 0 rows; downstream hr_payroll and pay will be stale",
                detail = Some("hr_payroll reads orders. Tap: hr_feed last ran at 09:00. payment totals in orders_eu unaffected")
            ),
            IncidentStep(t0.plusSeconds(120), "verify", "re-ran orders; hr_payroll not re-run")
        ),
        classification = Some("transient"),
        proposal = Some(obj("""{"action":"rerun","targets":["hr_payroll","orders"],"rationale":{"text":"tap hr_feed recovered"},"count":2,"dryRun":true}""")),
        outcome = Some("resolved; tap hr_feed recovered, hr_payroll re-ran"),
        evidence = if (withEvidence) Some(evidence) else None
    )

    /** An incident on `orders` whose text names only items every limited caller here may read. */
    private def namingVisible: Incident = Incident(
        id = "inc-2",
        kind = "pipeline_failure",
        resourceType = "pipeline",
        resourceName = "orders",
        openedAt = t0,
        state = "diagnosing",
        trigger = obj("""{"error":"load failed for tap t_orders","aiSummary":"orders_eu depends on orders"}"""),
        steps = List(
            IncidentStep(t0, "open", "pipeline orders failed loading tap-t_orders.csv"),
            IncidentStep(t0.plusSeconds(60), "diagnose", "tap t_orders sent 0 rows; payment totals in orders_eu stale", detail = Some("repay: tap t_orders"))
        ),
        proposal = Some(obj("""{"action":"rerun","targets":["orders","orders_eu"]}""")),
        outcome = Some("resolved; tap t_orders recovered"),
        evidence = Some(
            obj("""{"blastRadius":{"downstreamPipelines":["orders_eu"],"downstreamDatasets":[],"ucTables":[],"consumersFromContracts":[]},"verdict":"pass"}""")
        )
    )

    private def str(o: JsonObject, k: String): String = o.get(k).getAsString
    private def steps(o: JsonObject): Seq[JsonObject] = o.getAsJsonArray("steps").asScala.map(_.getAsJsonObject).toSeq

    /** The fields of a shown incident (toPublicJson) that hold free text. */
    private def assertScrubbed(o: JsonObject, who: String): Unit = {
        val s = steps(o)
        assert(str(s(0), "summary") == "pipeline orders failed loading tap-[hidden].csv", who + ": " + s(0))
        assert(str(s(1), "summary") == "tap [hidden] sent 0 rows; downstream [hidden] and [hidden] will be stale", who + ": " + s(1))
        assert(str(s(1), "detail") == "[hidden] reads orders. Tap: [hidden] last ran at 09:00. payment totals in orders_eu unaffected", who + ": " + s(1))
        assert(str(s(2), "summary") == "re-ran orders; [hidden] not re-run", who + ": " + s(2))
        val trig = o.getAsJsonObject("trigger")
        assert(str(trig, "error") == "load failed for tap '[hidden]'", who + ": " + trig)
        assert(str(trig, "aiSummary") == "[hidden] depends on orders", who + ": " + trig)
        assert(str(trig, "pipelineToken") == "tok-1", who + ": " + trig)
        assert(str(o, "outcome") == "resolved; tap [hidden] recovered, [hidden] re-ran", who + ": " + o)
        val prop = o.getAsJsonObject("proposal")
        assert(
            prop.toString == """{"action":"rerun","targets":["[hidden]","orders"],"rationale":{"text":"tap [hidden] recovered"},"count":2,"dryRun":true}""",
            who + ": " + prop
        )
        assert(str(o, "resource") == "orders" && str(o, "classification") == "transient", who + ": " + o)
        val body = o.toString
        Seq("hr_feed", "hr_payroll", "[[hidden]]").foreach(n => assert(!body.contains(n), who + " must not be shown '" + n + "': " + body))
    }

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

    // ================================================================== D1: the text rule (pure)

    private def scrub(text: String, hiddenTaps: Set[String], hiddenPipelines: Set[String], keep: Set[String] = Set.empty): String =
        HiddenTapScrub.scrubNames(text, hiddenTaps, hiddenPipelines, keep)

    test("bullet 1 / D1: a hidden tap name reads [hidden] in every tap context and a hidden pipeline name wherever it stands as a whole name") {
        val t = Set("hr_feed")
        val p = Set("hr_payroll")
        assert(scrub("Loaded tap-hr_feed.csv and tap-hr_feed.json", t, p) == "Loaded tap-[hidden].csv and tap-[hidden].json")
        assert(scrub("staged tap-docs/hr_feed/a.pdf", t, p) == "staged tap-docs/[hidden]/a.pdf")
        assert(scrub("tap hr_feed, tap 'hr_feed', tap \"hr_feed\", Tap: hr_feed, tap:hr_feed.", t, p) ==
            "tap [hidden], tap '[hidden]', tap \"[hidden]\", Tap: [hidden], tap:[hidden].")
        assert(scrub("hr_payroll failed; rerun hr_payroll. (hr_payroll) `hr_payroll`, 'hr_payroll'", t, p) ==
            "[hidden] failed; rerun [hidden]. ([hidden]) `[hidden]`, '[hidden]'")
        assert(scrub("downstream: hr_payroll\nnext line", t, p) == "downstream: [hidden]\nnext line")
    }

    test("D1: a hidden name inside a longer visible name or an ordinary word leaves the visible text as it is") {
        // hidden pipeline `pay` and hidden tap `hr`; the visible text holds them only as parts of other words and names
        // (`hr.eu` is the name of a visible item, so it is kept whole: a dot alone does not continue a name)
        val text = "payment of payroll via pay_v2 and pay-day; repay; tap hr_feed; tap-hr_feed.csv; hr.eu; shr; orders_pay"
        assert(scrub(text, Set("hr"), Set("pay"), keep = Set("hr.eu")) == text)
        // a hidden tap `hr` is not the visible tap `hr_feed` named in a tap context
        assert(scrub("tap hr_feed ran", Set("hr"), Set.empty) == "tap hr_feed ran")
        // a hidden pipeline `orders` does not touch the visible `orders_eu` or `orders-archive`
        assert(scrub("orders_eu and orders-archive landed; orders failed", Set.empty, Set("orders")) == "orders_eu and orders-archive landed; [hidden] failed")
    }

    test("D1: a dot after a hidden name ends it: file names, dated files and schema-qualified names read [hidden] before the dot") {
        val p = Set("payroll")
        assert(scrub("loaded payroll.csv", Set.empty, p) == "loaded [hidden].csv")
        assert(scrub("loaded payroll.20261101.csv", Set.empty, p) == "loaded [hidden].20261101.csv")
        assert(scrub("wrote payroll.public and public.payroll", Set.empty, p) == "wrote [hidden].public and public.[hidden]")
        assert(scrub("tap payroll.", Set("payroll"), Set.empty) == "tap [hidden].")
        // a name continued by a name char is still another name
        assert(scrub("payroll_v2.csv and payroll-eu.csv", Set.empty, p) == "payroll_v2.csv and payroll-eu.csv")
    }

    test("D1: a visible name holding a dot is kept whole; a hidden name holding a dot is replaced where it stands") {
        // visible `hr.eu`, hidden `hr`
        assert(scrub("hr.eu landed; hr.eu.csv too", Set("hr"), Set("hr"), keep = Set("hr.eu")) == "hr.eu landed; hr.eu.csv too")
        assert(scrub("hr failed; hr.csv; hr.eu fine", Set("hr"), Set("hr"), keep = Set("hr.eu")) == "[hidden] failed; [hidden].csv; hr.eu fine")
        // a hidden name after a kept one is still found
        assert(scrub("hr.eu.payroll", Set.empty, Set("payroll", "hr"), keep = Set("hr.eu")) == "hr.eu.[hidden]")
        // hidden `hr.eu`, visible `hr`: only the longer hidden name is replaced
        assert(scrub("hr.eu failed; hr ran; hr.eu.csv", Set.empty, Set("hr.eu"), keep = Set("hr")) == "[hidden] failed; hr ran; [hidden].csv")
        // without a kept name the shorter hidden name ends at the dot
        assert(scrub("hr.eu", Set.empty, Set("hr")) == "[hidden].eu")
    }

    test("D1: hidden names are found in any letter case; a kept name no hidden name shares protects every spelling of itself") {
        assert(scrub("Payroll failed; PAYROLL retried; payroll ok", Set.empty, Set("payroll")) == "[hidden] failed; [hidden] retried; [hidden] ok")
        assert(scrub("Tap HR_Feed sent tap-HR_FEED.csv", Set("hr_feed"), Set.empty) == "Tap [hidden] sent tap-[hidden].csv")
        // a name that is only kept is kept in every spelling
        assert(scrub("Orders and ORDERS and orders", Set.empty, Set("payroll"), keep = Set("orders")) == "Orders and ORDERS and orders")
        // a kept name and a hidden name differing only by letter case: only the exact kept spelling is kept
        assert(scrub("Orders and ORDERS and orders", Set.empty, Set("ORDERS"), keep = Set("orders")) == "[hidden] and [hidden] and orders")
        assert(scrub("HR.EU and hr.Eu stay; HR goes", Set.empty, Set("hr"), keep = Set("hr.eu")) == "HR.EU and hr.Eu stay; [hidden] goes")
    }

    test("D1: a visible pipeline and a hidden pipeline whose names differ only by letter case: the visible spelling is kept, every other spelling replaced") {
        val visibleUpper = pipe("Orders", "Sales", owner = "own")
        val hiddenLower = pipe("orders", "HR")
        val both = IncidentsAPIController.StoredItems(Seq(visibleUpper, hiddenLower), Nil)
        for ((who, rk) <- limited) {
            val names = IncidentsAPIController.nameScrubberFor(rk, both)
            assert(names("Orders ran; orders failed; ORDERS retried") == "Orders ran; [hidden] failed; [hidden] retried", who)
        }
    }

    test("D1: names holding regex-special characters are matched literally and the replacement is literal") {
        val hiddenPipelines = Set("a.b+c", "x$1", "(q)", "back\\slash", "star*")
        val text = "rerun a.b+c then x$1; also (q), back\\slash and star* — but not aXb+c, ab+c, x$12, q, backslash or starry"
        assert(
            scrub(text, Set("t[1]"), hiddenPipelines) ==
                "rerun [hidden] then [hidden]; also [hidden], [hidden] and [hidden] — but not aXb+c, ab+c, x$12, q, backslash or starry"
        )
        assert(scrub("tap t[1] failed; tap t1 ok", Set("t[1]"), Set.empty) == "tap [hidden] failed; tap t1 ok")
        assert(scrub("tap-t[1].csv", Set("t[1]"), Set.empty) == "tap-[hidden].csv")
    }

    test("D1: the same hidden name appearing several times is replaced every time, longest names first") {
        assert(scrub("hr_payroll, hr_payroll and hr_payroll.", Set.empty, Set("hr_payroll")) == "[hidden], [hidden] and [hidden].")
        assert(scrub("tap hr_feed then tap hr_feed then tap-hr_feed.csv", Set("hr_feed"), Set.empty) == "tap [hidden] then tap [hidden] then tap-[hidden].csv")
        // overlapping hidden names: the longer one is replaced whole, never as `[hidden]_payroll`
        assert(scrub("hr and hr_payroll", Set.empty, Set("hr", "hr_payroll")) == "[hidden] and [hidden]")
        assert(scrub("tap hr_feed and tap hr", Set("hr", "hr_feed"), Set.empty) == "tap [hidden] and tap [hidden]")
    }

    test("D1: a hidden item named like the placeholder word never produces a nested placeholder") {
        assert(scrub("tap hr_feed failed; hidden ran", Set("hr_feed"), Set("hidden")) == "tap [hidden] failed; [hidden] ran")
        assert(scrub("tap hr_feed failed", Set("hr_feed", "hidden"), Set("hidden")) == "tap [hidden] failed")
    }

    test("D1: text with no hidden name is returned unchanged; null stays null; empty sets return the text itself") {
        val text = "pipeline orders failed after tap t_orders sent tap-t_orders.csv; orders_eu unaffected"
        assert(scrub(text, Set("hr_feed"), Set("hr_payroll", "pay")) == text)
        assert(scrub(null, Set("hr_feed"), Set("hr_payroll")) == null)
        assert(scrub("", Set("hr_feed"), Set("hr_payroll")) == "")
        assert(scrub(text, Set.empty, Set.empty) eq text)
    }

    // ================================================================== D1 through the incident seams

    test("bullet 1: the single read shows [hidden] for the hidden tap and the hidden downstream pipelines, for each limited caller") {
        for ((who, rk) <- limited) {
            val stored = namingHidden()
            val before = stored.toPublicJson.toString
            val r = IncidentsAPIController.getResponse(
                req(rk, "/api/v1/incidents/inc-1"),
                "inc-1",
                Some(stored),
                readPipeline,
                readTap,
                run = noRuns,
                items = items
            )
            assert(r.getStatusCode.value == 200, who + ": " + r.getBody)
            assertScrubbed(JsonParser.parseString(r.getBody).getAsJsonObject, who)
            assert(stored.toPublicJson.toString == before, who + ": the stored incident is never altered")
        }
    }

    test("bullet 1: the list shows [hidden] in the same places, also for an incident stored without evidence") {
        for ((who, rk) <- limited) {
            val arr = IncidentsAPIController.listedJson(
                req(rk),
                List(namingHidden("inc-1"), namingHidden("inc-3", withEvidence = false)),
                readPipeline,
                readTap,
                run = noRuns,
                items = items
            )
            assert(arr.size == 2, who + ": " + arr)
            arr.asScala.foreach(el => assertScrubbed(el.getAsJsonObject, who + " " + el.getAsJsonObject.get("id")))
        }
    }

    test("bullet 1: shownIncident and visibleIncidentsFor (the request-free path) apply the same rule") {
        for ((who, rk) <- limited) {
            assertScrubbed(IncidentsAPIController.shownIncident(req(rk), namingHidden(), readPipeline, readTap, run = noRuns, items = items).toPublicJson, who)
            val listed = IncidentsAPIController.visibleIncidentsFor(rk, List(namingHidden()), readPipeline, readTap, run = noRuns, items = items)
            assert(listed.size == 1, who)
            assertScrubbed(listed.head.toPublicJson, who + " (visibleIncidentsFor)")
        }
    }

    test("bullet 1: the evidence route and an approval's incidentEvidence show no hidden name (structured scrub, as today)") {
        def pending(incidentId: String): ai.datris.policy.PendingAction = ai.datris.policy.PendingAction(
            id = "pa_1",
            action = "pipeline:run",
            resourceType = Some("pipeline"),
            resourceName = Some("orders"),
            resourceVersion = None,
            actor = ai.datris.audit.AuditActorInfo("api-key", "recovery"),
            reason = None,
            agentSession = None,
            incidentId = Some(incidentId),
            method = "POST",
            path = "/api/v1/pipeline/upload",
            query = None,
            contentType = None,
            body = Some("{}"),
            bodyHash = "h",
            createdAt = t0,
            expiresAt = t0.plusSeconds(3600),
            state = "pending"
        )
        for ((who, rk) <- limited) {
            val e = IncidentsAPIController.evidenceResponse(
                req(rk, "/api/v1/incidents/inc-1/evidence"),
                "inc-1",
                Some(namingHidden()),
                readPipeline,
                readTap,
                run = noRuns
            )
            assert(e.getStatusCode.value == 200, who + ": " + e.getBody)
            val down = JsonParser.parseString(e.getBody).getAsJsonObject.getAsJsonObject("blastRadius").getAsJsonArray("downstreamPipelines")
            assert(down.toString == """["[hidden]","orders_eu"]""", who + ": " + down)
            assert(!e.getBody.contains("hr_payroll") && !e.getBody.contains("hr_feed"), who + ": " + e.getBody)

            val load: String => Option[Incident] = { case "inc-1" => Some(namingHidden()); case _ => None }
            val a = ApprovalsAPIController.withIncidentEvidence(
                req(rk, "/api/v1/approvals/pa_1"),
                pending("inc-1"),
                load,
                readPipeline,
                readTap,
                noRuns
            )
            assert(a.has("incidentEvidence"), who + ": " + a)
            assert(
                a.getAsJsonObject("incidentEvidence").getAsJsonObject("blastRadius").getAsJsonArray("downstreamPipelines").toString == """["[hidden]","orders_eu"]""",
                who
            )
            assert(!a.toString.contains("hr_payroll") && !a.toString.contains("hr_feed"), who + ": " + a)
        }
    }

    test("bullet 1: the Ops chat context block shows [hidden] for a catalog= key holding incident:read; a restricted session still gets no incidents") {
        val ctx = new JsonObject()
        ctx.addProperty("window", "24h")
        val keyed = ChatIdentity(None, Some(key("sales-ops", "incident:read", "pipeline:read:catalog=Sales", "tap:read:catalog=Sales")), ownKey = true)
        val shown = OpsChatAPIController.incidentsFor(keyed, () => List(namingHidden()), readPipeline, readTap, items = items)
        assert(shown.size == 1, shown)
        val msg = OpsAgentPrompt.renderContextMessage(ctx, shown)
        assert(
            msg.contains("  - inc-1: pipeline_failure on pipeline `orders`, state=diagnosing, classified transient — re-ran orders; [hidden] not re-run"),
            msg
        )
        Seq("hr_payroll", "hr_feed").foreach(n => assert(!msg.contains(n), msg))
        // a restricted session holds no incident:read: still nothing, and no store is read
        val session = ChatIdentity(
            Some(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "view"))),
            Some(bob),
            ownKey = false
        )
        assert(OpsChatAPIController.incidentsFor(session, () => fail("loaded"), failReadPipeline, failReadTap, items = failItems) == Nil)
    }

    test("D1 (owner decision: names of hidden taps are replaced): a hidden tap name written outside a tap context also reads [hidden]") {
        val inc = namingHidden().copy(steps = List(IncidentStep(t0, "diagnose", "the hr_feed tap returned 0 rows; hr_feed is the source")))
        for ((who, rk) <- limited) {
            val o = IncidentsAPIController.shownIncident(req(rk), inc, readPipeline, readTap, run = noRuns, items = items).toPublicJson
            assert(str(steps(o).head, "summary") == "the [hidden] tap returned 0 rows; [hidden] is the source", who + ": " + o)
        }
    }

    test("D1: a name a visible item carries is never replaced, even when a hidden item of the other kind shares it") {
        // `orders_feed` is a visible tap (Sales) and a hidden pipeline (HR): text naming the visible tap stays readable
        val inc = namingVisible.copy(steps = List(IncidentStep(t0, "diagnose", "tap orders_feed sent 0 rows; orders_feed will retry")))
        for ((who, rk) <- limited) {
            val o = IncidentsAPIController.shownIncident(req(rk), inc, readPipeline, readTap, run = noRuns, items = items).toPublicJson
            assert(str(steps(o).head, "summary") == "tap orders_feed sent 0 rows; orders_feed will retry", who + ": " + o)
        }
    }

    test("D1: the previous names of a hidden pipeline are replaced; a visible pipeline's previous names are kept") {
        // payroll_v1 was the hidden hr_payroll's name; orders_legacy was the visible orders' name;
        // shared_old was a name of both (kept: a visible item carries it)
        val renamedHidden = hrPayroll.copy(previousNames = java.util.Arrays.asList("payroll_v1", "shared_old"))
        val renamedVisible = orders.copy(previousNames = java.util.Arrays.asList("orders_legacy", "shared_old"))
        val renamed = IncidentsAPIController.StoredItems(Seq(renamedVisible, ordersEu, renamedHidden, pay, hiddenNamedLikeVisibleTap), taps)
        val inc = namingVisible.copy(steps =
            List(IncidentStep(t0, "diagnose", "payroll_v1 and Payroll_V1.csv stale; orders_legacy and shared_old fine; hr_payroll stale"))
        )
        for ((who, rk) <- limited) {
            val o = IncidentsAPIController.shownIncident(req(rk), inc, readPipeline, readTap, run = noRuns, items = () => renamed).toPublicJson
            assert(
                str(steps(o).head, "summary") == "[hidden] and [hidden].csv stale; orders_legacy and shared_old fine; [hidden] stale",
                who + ": " + o
            )
        }
    }

    test("D1: a visible pipeline's previous name that a hidden pipeline now carries is replaced") {
        // the visible orders was once named payroll_now, the name the hidden hr_payroll carries now
        val renamedVisible = orders.copy(previousNames = java.util.Arrays.asList("payroll_now", "orders_legacy"))
        val hiddenNow = hrPayroll.copy(name = "payroll_now")
        val stored = IncidentsAPIController.StoredItems(Seq(renamedVisible, ordersEu, hiddenNow, pay), taps)
        val inc = namingVisible.copy(steps = List(IncidentStep(t0, "diagnose", "payroll_now stale; orders_legacy and orders fine")))
        for ((who, rk) <- limited) {
            val o = IncidentsAPIController.shownIncident(req(rk), inc, readPipeline, readTap, run = noRuns, items = () => stored).toPublicJson
            assert(str(steps(o).head, "summary") == "[hidden] stale; orders_legacy and orders fine", who + ": " + o)
        }
    }

    test("D1: a visible pipeline's previous name that a hidden tap now carries is replaced") {
        // the visible orders was once named hr_sync, the name the hidden HR tap carries now
        val renamedVisible = orders.copy(previousNames = java.util.Arrays.asList("hr_sync", "orders_legacy"))
        val hiddenTapNow = tap("hr_sync", "HR", "hr_payroll")
        val stored = IncidentsAPIController.StoredItems(Seq(renamedVisible, ordersEu, hrPayroll, pay), taps :+ hiddenTapNow)
        val inc = namingVisible.copy(steps =
            List(IncidentStep(t0, "diagnose", "the hr_sync tap sent 0 rows; hr_sync retries; orders_legacy and orders fine"))
        )
        for ((who, rk) <- limited) {
            val o = IncidentsAPIController.shownIncident(req(rk), inc, readPipeline, readTap, run = noRuns, items = () => stored).toPublicJson
            assert(str(steps(o).head, "summary") == "the [hidden] tap sent 0 rows; [hidden] retries; orders_legacy and orders fine", who + ": " + o)
            val names = IncidentsAPIController.nameScrubberFor(rk, stored)
            assert(names("hr_sync failed") == "[hidden] failed", who)
            assert(names("orders_legacy ran") == "orders_legacy ran", who)
        }
    }

    test("D1: through the incident seams, a visible name holding a dot is never cut by a hidden name") {
        val hr = pipe("hr", "HR")
        val hrEu = pipe("hr.eu", "Sales", owner = "own")
        val dotted = IncidentsAPIController.StoredItems(pipelines ++ Seq(hr, hrEu), taps)
        val inc = namingVisible.copy(steps = List(IncidentStep(t0, "diagnose", "hr.eu landed hr.eu.20261101.csv; HR and hr.csv stale")))
        for ((who, rk) <- limited) {
            val o = IncidentsAPIController.shownIncident(req(rk), inc, readPipeline, readTap, run = noRuns, items = () => dotted).toPublicJson
            assert(str(steps(o).head, "summary") == "hr.eu landed hr.eu.20261101.csv; [hidden] and [hidden].csv stale", who + ": " + o)
        }
    }

    // ================================================================== one store listing per response

    /** A listing of `pipelines` and `taps` that counts how often each store is listed. */
    private final class CountedListing {
        var pipelineLists = 0
        var tapLists = 0
        val listing = new IncidentsAPIController.StoreListing(
            () => { pipelineLists += 1; pipelines },
            () => { tapLists += 1; taps }
        )
    }

    test("one store listing serves the readers and the free-text scrub: each store is listed at most once per limited response") {
        for ((who, rk) <- limited) {
            val c = new CountedListing
            val l = c.listing
            val arr = IncidentsAPIController.listedFor(
                req(rk),
                20,
                _ => (1 to 5).map(i => namingHidden("inc-" + i)).toList,
                l.readPipeline,
                l.readTap,
                noRuns,
                l.items
            )
            assert(arr.size == 5, who)
            arr.asScala.foreach(el => assertScrubbed(el.getAsJsonObject, who))
            assert(c.pipelineLists == 1 && c.tapLists == 1, who + ": listed " + c.pipelineLists + "/" + c.tapLists)

            val g = new CountedListing
            val r =
                IncidentsAPIController.getResponse(req(rk), "inc-1", Some(namingHidden()), g.listing.readPipeline, g.listing.readTap, noRuns, g.listing.items)
            assertScrubbed(JsonParser.parseString(r.getBody).getAsJsonObject, who)
            assert(g.pipelineLists == 1 && g.tapLists == 1, who + ": listed " + g.pipelineLists + "/" + g.tapLists)

            // abandon / proceed: both stores are listed before the change, none after
            val a = new CountedListing
            IncidentsAPIController.listBeforeChange(req(rk), a.listing)
            assert(a.pipelineLists == 1 && a.tapLists == 1, who)
            IncidentsAPIController.incidentRefusal(req(rk), "inc-1", Some(namingHidden()), a.listing.readPipeline, a.listing.readTap)
            IncidentsAPIController.shownIncident(req(rk), namingHidden(), a.listing.readPipeline, a.listing.readTap, noRuns, a.listing.items)
            assert(a.pipelineLists == 1 && a.tapLists == 1, who + ": nothing listed after the change")
        }
        for ((who, r) <- unchanged) {
            val c = new CountedListing
            IncidentsAPIController.listBeforeChange(r, c.listing)
            IncidentsAPIController.listedFor(r, 20, _ => List(namingHidden()), c.listing.readPipeline, c.listing.readTap, noRuns, c.listing.items)
            assert(c.pipelineLists == 0 && c.tapLists == 0, who + ": nothing listed for a caller that is not limited")
        }
    }

    test("every incident route, approvals and the Ops chat context take their readers and items from one storeListing") {
        def source(path: String): String = new String(java.nio.file.Files.readAllBytes(locate(path)), "UTF-8")
        def locate(path: String): java.nio.file.Path = {
            var dir = java.nio.file.Paths.get("").toAbsolutePath
            while (dir != null && !java.nio.file.Files.exists(dir.resolve(path))) dir = dir.getParent
            dir.resolve(path)
        }

        /** The text of `def name(` up to the next `@GetMapping`/`@PostMapping`/`def ` at the same indentation. */
        def body(src: String, start: String): String = {
            val at = src.indexOf(start)
            assert(at >= 0, start)
            val indent = src.lastIndexOf('\n', at)
            val pad = src.substring(indent + 1, at)
            val next = Seq("\n" + pad + "def ", "\n" + pad + "@").map(src.indexOf(_, at + start.length)).filter(_ > 0)
            src.substring(at, if (next.isEmpty) src.length else next.min)
        }
        val incidents = source("datrisserver/src/main/scala/ai/datris/api/IncidentsAPIController.scala")
        assert(!incidents.contains("def storeReaders(") && !incidents.contains("def storeItems("))
        for (route <- Seq("def list(", "def get(", "def evidence(", "def abandon(", "def proceed(")) {
            val b = body(incidents, route)
            assert(b.split("storeListing\\(\\)", -1).length - 1 == 1, route + " lists the stores through one storeListing: " + b)
        }
        for (route <- Seq("def abandon(", "def proceed(")) {
            val b = body(incidents, route)
            val listed = b.indexOf("listBeforeChange(request, listing)")
            val change = math.max(b.indexOf("IncidentIO.transition("), b.indexOf("IncidentRunner.proceed("))
            assert(listed > 0 && change > listed, route + ": the stores are listed before the change")
        }
        val approvals = source("datrisserver/src/main/scala/ai/datris/api/ApprovalsAPIController.scala")
        val ops = source("datrisserver/src/main/scala/ai/datris/api/OpsChatAPIController.scala")
        for (src <- Seq(approvals, ops)) assert(!src.contains("storeReaders(") && !src.contains("storeItems("))
        assert(ops.contains("incidentsFor(identity, load, listing.readPipeline, listing.readTap, listing.items)"))
    }

    // ================================================================== unchanged

    test("unchanged: an admin and every caller that is not limited get the stored incident itself; no store is read or listed") {
        val gson = new Gson
        for ((who, r) <- unchanged) {
            val stored = namingHidden()
            val expected = stored.toPublicJson.toString
            val shown = IncidentsAPIController.shownIncident(r, stored, failReadPipeline, failReadTap, run = _ => fail("run read"), items = failItems)
            assert(shown eq stored, who)
            val g = IncidentsAPIController.getResponse(r, "inc-1", Some(stored), failReadPipeline, failReadTap, run = _ => fail("run read"), items = failItems)
            assert(g.getStatusCode.value == 200 && g.getBody == gson.toJson(stored.toPublicJson), who + ": " + g.getBody)
            assert(g.getBody.contains("tap hr_feed sent 0 rows; downstream hr_payroll and pay will be stale"), who)
            val l = IncidentsAPIController.listedJson(r, List(stored), failReadPipeline, failReadTap, run = _ => fail("run read"), items = failItems)
            assert(l.size == 1 && l.get(0).toString == expected, who)
            val e =
                IncidentsAPIController.evidenceResponse(r, "inc-1", Some(stored), failReadPipeline, failReadTap, run = _ => fail("run read"))
            assert(e.getBody == gson.toJson(stored.evidence.get), who)
        }
        // admin in the Ops chat: the loaded list itself
        val adminId = ChatIdentity(Some(User("admin", "h", User.RoleAdmin, null, null, null)), Some(admin), ownKey = false)
        val loaded = List(namingHidden())
        assert(OpsChatAPIController.incidentsFor(adminId, () => loaded, failReadPipeline, failReadTap, items = failItems) eq loaded)
    }

    test("unchanged: a limited caller reading an incident that names only items inside its scope gets the stored text byte for byte") {
        for ((who, rk) <- limited) {
            val stored = namingVisible
            val o = IncidentsAPIController.shownIncident(req(rk), stored, readPipeline, readTap, run = noRuns, items = items).toPublicJson
            assert(o.toString == stored.toPublicJson.toString, who + ": " + o)
            val arr: JsonArray = IncidentsAPIController.listedJson(req(rk), List(stored), readPipeline, readTap, run = noRuns, items = items)
            assert(arr.get(0).toString == stored.toPublicJson.toString, who)
        }
    }

    test("the stores are listed at most once per response, however many incidents and fields are shown") {
        for ((who, rk) <- limited) {
            var listed = 0
            val counted: () => IncidentsAPIController.StoredItems = () => { listed += 1; IncidentsAPIController.StoredItems(pipelines, taps) }
            val arr = IncidentsAPIController.listedJson(
                req(rk),
                (1 to 5).map(i => namingHidden("inc-" + i)).toList,
                readPipeline,
                readTap,
                run = noRuns,
                items = counted
            )
            assert(arr.size == 5, who)
            assert(listed <= 1, who + ": listed " + listed + " times")
        }
    }
}
