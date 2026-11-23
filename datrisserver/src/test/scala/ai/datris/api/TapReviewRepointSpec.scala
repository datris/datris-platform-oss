package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.{CapabilityRoutes, RouteCheck, ScopeDenialFixtures}
import ai.datris.config.CapabilityInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, SecretNames, TapCronGate, TapFixResult, TapGenerateResult, TapOptimizeResult, TapReviewResult, TapReviewAccessLog}
import ai.datris.util.InMemoryUserDb.grants
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.google.gson.{JsonObject, JsonParser}
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.{doAnswer, mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: Tap script, script reference and secret rules, first release
  * (plans/stories/tap-save-references-in-scope.md), rules 1, 2 and 4 at the
  * four AI script routes: `POST /tap/generate`, `/tap/fix`, `/tap/review`,
  * `/tap/optimize`.
  *
  * Required behaviour:
  *  - Rule 1 (enforced): reviewing, generating, fixing or optimizing changes a
  *    stored tap only for a caller who may update that tap
  *    (`mayWriteStoredTap`: no identity -> yes; restricted session ->
  *    `scopeRefusal(.., "update")` empty; key -> holds `tap:create` or
  *    `tap:update` granting on the tap's catalog and owner, and
  *    `storedTapWriteRefusal` empty). A caller who may not still gets the
  *    same 200 and the same rewrite, the model is still called, and the
  *    stored tap, its versions and its test state are left as they are; the
  *    response carries [[Rule1Message]] under `error`. Every other caller's
  *    answer, and every refusal given before the model is called today, is
  *    unchanged.
  *  - Rule 2 (enforced): a request that names no tap (missing, null, blank)
  *    never reads or writes a stored tap, for every caller. Today review and
  *    optimize fall back to the literal name `"tap"` and generate to
  *    `"tap-<millis>"`; tap names are not validated, so a real tap can be
  *    called `tap`. A request that sends `tapName: "tap"` names that tap.
  *  - Rule 4 (reported only): a review by a caller that holds no `tap:update`
  *    is answered exactly as rule 1 says, and is recorded in
  *    `TapReviewAccessLog` with one WARN line (at most once per key and tap a
  *    UTC day); the route row stays `tap:read`.
  *
  * Seam the implementation must add (none exists: the routes call
  * `TapConfigIO`, the model and the script store directly):
  * {{{
  * object TapAPIController {
  *   private[api] case class TapAiIO(
  *     readTap: String => TapConfig,                                  // TapConfigIO.read
  *     readScript: TapConfig => Option[String],                       // TapCodeStore.forTap(t).readScript(t), for TapScriptIdentity
  *     write: TapConfig => Unit,                                      // TapConfigIO.write
  *     writeVersioned: (TapConfig, String, String) => TapConfig,      // TapConfigIO.writeVersioned
  *     secretType: String => Option[Option[String]],                  // liveSecretType (generate's secret type check)
  *     secretOwner: String => Option[Option[String]],                 // liveSecretOwner (rule 7 report at generate)
  *     generate: (String, String, String, String, String) => TapGenerateResult,  // description, tapName, oldScriptPath, secretName, tapType
  *     fix: (String, String, String, String, String, String, List[IterationRecord]) => TapFixResult,
  *     review: (String, String, Int, Long, String, String, List[IterationRecord]) => TapReviewResult,
  *     optimize: (String, String, Int, Long, String, String, List[IterationRecord]) => TapOptimizeResult
  *   )
  *   private[api] lazy val ProductionAiIO: TapAiIO
  *   def mayWriteStoredTap(request: HttpServletRequest, tap: TapConfig): Boolean
  *   val StoredTapLeftUnchanged: String   // == Rule1Message below
  * }
  * class TapAPIController {
  *   // The route body after APIKeyValidator.validate, every store, model and
  *   // secret call through `io` (the restrictedRefusalIfExists read included).
  *   private[api] def generateScriptWith(body: java.util.Map[String, String], request: HttpServletRequest, io: TapAiIO): ResponseEntity[String]
  *   private[api] def fixScriptWith(body: java.util.Map[String, String], request: HttpServletRequest, io: TapAiIO): ResponseEntity[String]
  *   private[api] def reviewScriptWith(body: java.util.Map[String, Object], request: HttpServletRequest, io: TapAiIO): ResponseEntity[String]
  *   private[api] def optimizeScriptWith(body: java.util.Map[String, Object], request: HttpServletRequest, io: TapAiIO): ResponseEntity[String]
  * }
  * object ai.datris.util.TapReviewAccessLog {
  *   def record(label: String): Unit
  *   def labels(): List[String]          // distinct, at most MaxLabels
  *   def since: java.time.Instant        // the server start (or the first record)
  *   val MaxLabels: Int
  * }
  * }}}
  * The route gate (CapabilityInterceptor) is not part of `*With`; which
  * callers reach each route is pinned through `CapabilityRoutes.lookup`.
  */
class TapReviewRepointSpec extends AnyFunSuite with BeforeAndAfterEach {

    /** The `error` text a caller who may not update the stored tap gets (story, Design). */
    val Rule1Message = "The stored tap was left unchanged: this caller may not update it"

    private val env: DatrisEnvironment = ScopeDenialFixtures.auditEnv()
    private var savedValues: DatrisEnvironment = _
    private var savedScope: Option[String] = None

    /** Audit rows (the audit log is on in `env`; this sink keeps every row in memory). */
    private val audit = mutable.ArrayBuffer[AuditEntry]()

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        DatrisEnvironment.init(env)
        ScopeDenialFixtures.install(env)
        savedScope = sys.props.get(SecretNames.TapScopeProperty)
        sys.props(SecretNames.TapScopeProperty) = "tap"
        audit.clear()
        AuditLog.sinkOverride = (e: AuditEntry) => audit.synchronized { audit += e; () }
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        ScopeDenialFixtures.install(savedValues)
        savedScope match {
            case Some(v) => sys.props(SecretNames.TapScopeProperty) = v
            case None => sys.props -= SecretNames.TapScopeProperty
        }
    }

    // ---------------------------------------------------------------- identities

    private def key(label: String, caps: String*): ResolvedKey = ScopeDenialFixtures.key(label, caps: _*)
    private def template(label: String, name: String): ResolvedKey = key(label, KeyTemplatesForSpecs.capabilities(name): _*)
    private def session(u: User): ResolvedKey = APIKeyValidator.resolveFromSession(u)

    private val admin = session(User("ada", "h", User.RoleAdmin, null, null, null))
    private val editor = session(User("ed", "h", User.RoleEditor, null, null, null))
    private val viewer = session(User("vera", "h", User.RoleViewer, null, null, null))
    private val rita = session(User("rita", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit")))
    private val ritaView = session(User("rvue", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "view")))
    private val fullKey = key("full-agent", "*:*")
    private val legacyKey = ResolvedKey(None, "root", Seq(Capability.FullAccess), isLegacyFullAccess = true)
    private val salesKey = key(
        "sales-agent",
        "pipeline:read:catalog=Sales",
        "pipeline:run:catalog=Sales",
        "tap:read:catalog=Sales",
        "tap:create:catalog=Sales",
        "tap:update:catalog=Sales",
        "tap:run:catalog=Sales"
    )
    private val readOnlyKey = template("ro-agent", "read-only")
    private val reportingKey = template("report-agent", "reporting")
    private val opsKey = template("ops-agent", "ops")
    private val ragKey = template("rag-agent", "rag-builder")
    private val fullTemplateKey = template("fa-agent", "full-access")
    private val plainReadKey = key("plain-reader", "tap:read")
    // can see every tap (unscoped read) but may update only Finance taps
    private val financeWriter = key("finance-writer", "tap:read", "tap:update:catalog=Finance")
    // limited to another catalog altogether
    private val financeKey = key("finance-agent", "tap:read:catalog=Finance", "tap:update:catalog=Finance")

    private def req(rk: ResolvedKey, path: String): HttpServletRequest = ScopeDenialFixtures.request(rk, "POST", path)._1

    // ---------------------------------------------------------------- the stores and the model

    private val OldPath = "tap-scripts/sales-tap_0ld0ld00.py"
    private val OldScript = "def fetch():\n    return [{'a': 1}]"
    private val NewScript = "def fetch():\n    return [{'a': 2}]"
    private def newPath(name: String): String = "tap-scripts/" + Option(name).filter(_.trim.nonEmpty).getOrElse("tap") + "_n3wb7t3s.py"

    private def jl(xs: String*): java.util.List[String] = new java.util.ArrayList[String](xs.asJava)

    private val salesTap = TapConfig(
        name = "sales-tap",
        description = "d",
        targetPipeline = "sales-orders",
        catalog = "Sales",
        createdByKeyLabel = "session:ada",
        scriptPath = OldPath,
        lastTestRunStatus = "success",
        lastTestRunScriptId = "sha-of-old",
        version = 4
    )

    /** In-memory taps and scripts; every store read, write and model call is recorded. */
    private class World(initial: Seq[TapConfig] = Seq(salesTap), rewritten: Boolean = true) {
        val taps: mutable.Map[String, TapConfig] = mutable.LinkedHashMap(initial.map(t => t.name -> t): _*)
        val reads: mutable.Buffer[String] = mutable.Buffer.empty
        val plainWrites: mutable.Buffer[TapConfig] = mutable.Buffer.empty
        val versioned: mutable.Buffer[(TapConfig, String)] = mutable.Buffer.empty
        val aiCalls: mutable.Buffer[(String, String)] = mutable.Buffer.empty
        val scripts: mutable.Map[String, String] = mutable.Map(OldPath -> OldScript)
        private def wrote(name: String): String = {
            val p = newPath(name)
            scripts(p) = NewScript
            p
        }
        val io: TapAPIController.TapAiIO = TapAPIController.TapAiIO(
            readTap = n => { reads += n; taps.get(n).orNull },
            readScript = t => scripts.get(t.scriptPath),
            write = t => { plainWrites += t; taps(t.name) = t },
            writeVersioned = (t, note, _) => {
                versioned += ((t, note))
                val s = t.copy(version = t.version + 1)
                taps(t.name) = s
                s
            },
            secretType = _ => Some(Some(SecretNames.TapScopeTap)),
            secretOwner = _ => None,
            generate = (_, n, _, _, _) => {
                aiCalls += ("generate" -> n)
                TapGenerateResult(NewScript, jl("requests"), wrote(n), jl(), "anthropic", "claude-opus-5-5")
            },
            fix = (n, _, _, _, _, _, _) => {
                aiCalls += ("fix" -> n)
                TapFixResult(NewScript, jl("requests"), wrote(n))
            },
            review = (n, _, _, _, _, _, _) => {
                aiCalls += ("review" -> n)
                if (rewritten) TapReviewResult(NewScript, jl("requests"), wrote(n), jl("handle HTTP 429"), rewritten = true)
                else TapReviewResult(OldScript, jl("requests"), OldPath, jl(), rewritten = false)
            },
            optimize = (n, _, _, _, _, _, _) => {
                aiCalls += ("optimize" -> n)
                TapOptimizeResult(NewScript, jl("requests"), wrote(n), jl("batch the requests"))
            }
        )
        def untouched(name: String, original: TapConfig): Unit = {
            assert(plainWrites.isEmpty && versioned.isEmpty, "nothing written: " + plainWrites + " " + versioned)
            assert(taps(name) eq original, "the stored tap, its versions and its test state are as they were: " + taps(name))
            assert(scripts(original.scriptPath) == OldScript, "the stored script object is untouched")
        }
    }

    // ---------------------------------------------------------------- the four routes

    private val Routes = Seq("generate", "fix", "review", "optimize")

    private def body(route: String, tapName: Option[String]): java.util.Map[String, Object] = {
        val m = new java.util.HashMap[String, Object]()
        tapName.foreach(n => m.put("tapName", n))
        route match {
            case "generate" =>
                m.put("description", "fetch the sales orders")
                m.put("oldScriptPath", OldPath)
                m.put("tapType", "structured")
            case "fix" =>
                m.put("script", OldScript)
                m.put("diagnosis", "the endpoint moved")
                m.put("logs", "HTTP 301")
                m.put("error", "moved")
                m.put("oldScriptPath", OldPath)
                m.put("priorIterations", "[]")
            case _ =>
                m.put("script", OldScript)
                m.put("recordCount", "5")
                m.put("durationMs", "120")
                m.put("logs", "HTTP 429 Too Many Requests")
                m.put("oldScriptPath", OldPath)
                m.put("priorIterations", "[]")
        }
        m
    }

    private def strings(m: java.util.Map[String, Object]): java.util.Map[String, String] = {
        val s = new java.util.HashMap[String, String]()
        m.asScala.foreach { case (k, v) => s.put(k, if (v == null) null else v.toString) }
        s
    }

    private def call(
        route: String,
        rk: ResolvedKey,
        w: World,
        tapName: Option[String] = Some("sales-tap"),
        request: HttpServletRequest = null
    ): ResponseEntity[String] = {
        val c = new TapAPIController()
        val b = body(route, tapName)
        val r = Option(request).getOrElse(req(rk, "/api/v1/tap/" + route))
        route match {
            case "generate" => c.generateScriptWith(strings(b), r, w.io)
            case "fix" => c.fixScriptWith(strings(b), r, w.io)
            case "review" => c.reviewScriptWith(b, r, w.io)
            case "optimize" => c.optimizeScriptWith(b, r, w.io)
        }
    }

    /** The 200 body each route gives on v1.61.0 for a model result written at
      * `path`, before any `error`: exactly these fields. */
    private def todayBody(route: String, path: String): JsonObject = {
        val o = new JsonObject()
        o.addProperty("script", NewScript)
        val pk = new com.google.gson.JsonArray()
        pk.add("requests")
        o.add("packages", pk)
        o.addProperty("scriptPath", path)
        route match {
            case "generate" =>
                o.add("injectedPrompts", new com.google.gson.JsonArray())
                o.addProperty("provider", "anthropic")
                o.addProperty("model", "claude-opus-5-5")
            case "fix" =>
            case "review" =>
                val ch = new com.google.gson.JsonArray()
                ch.add("handle HTTP 429")
                o.add("changes", ch)
                o.addProperty("rewritten", true)
            case "optimize" =>
                val ch = new com.google.gson.JsonArray()
                ch.add("batch the requests")
                o.add("changes", ch)
        }
        o
    }

    private def json(r: ResponseEntity[String]): JsonObject = JsonParser.parseString(r.getBody).getAsJsonObject

    private def withError(o: JsonObject, msg: String): JsonObject = {
        val c = o.deepCopy()
        c.addProperty("error", msg)
        c
    }

    private val NoteOf = Map("generate" -> "script generated", "fix" -> "script fixed", "review" -> "script reviewed", "optimize" -> "script optimized")

    /** v1.61.0's outcome for a caller who reaches the repoint: 200, today's
      * body, the stored tap repointed at the new bytes in one new version
      * whose note names the route. */
    private def who(rk: ResolvedKey): String = Option(rk).map(_.label).getOrElse("(no identity)")

    private def assertChangedAsToday(route: String, rk: ResolvedKey, request: HttpServletRequest = null): Unit = {
        val w = new World()
        val r = call(route, rk, w, request = request)
        assert(r.getStatusCode.value == 200, route + " " + who(rk) + ": " + r.getBody)
        assert(json(r) == todayBody(route, newPath("sales-tap")), route + " " + who(rk) + ": " + r.getBody)
        assert(w.versioned.size == 1 && w.plainWrites.isEmpty, route + " " + who(rk) + ": " + w.versioned)
        val (written, note) = w.versioned.head
        assert(note == NoteOf(route), note)
        assert(written == salesTap.copy(scriptPath = newPath("sales-tap")), "only the script reference changes: " + written)
        assert(w.aiCalls.map(_._1) == Seq(route))
    }

    /** Rule 1 for a caller who may read but not update the stored tap. */
    private def assertLeftUnchanged(route: String, rk: ResolvedKey, request: HttpServletRequest = null): Unit = {
        val w = new World()
        val r = call(route, rk, w, request = request)
        assert(r.getStatusCode.value == 200, route + " " + who(rk) + ": " + r.getBody)
        assert(json(r) == withError(todayBody(route, newPath("sales-tap")), Rule1Message), route + " " + who(rk) + ": " + r.getBody)
        w.untouched("sales-tap", salesTap)
        assert(w.aiCalls.map(_._1) == Seq(route), "the model is still called once: " + w.aiCalls)
    }

    /** A caller refused before the model today keeps exactly that refusal. */
    private def assertRefusedAsToday(route: String, rk: ResolvedKey): Unit = {
        val w = new World()
        val today = TapAPIController.restrictedRefusalIfExists(req(rk, "/api/v1/tap/" + route), "sales-tap", "update", _ => salesTap)
        assert(today.isDefined, route + " " + who(rk) + " is refused before the model on v1.61.0")
        val r = call(route, rk, w)
        assert(r.getStatusCode == today.get.getStatusCode && r.getBody == today.get.getBody, route + " " + who(rk) + ": " + r.getBody)
        assert(w.aiCalls.isEmpty, "no model call: " + w.aiCalls)
        w.untouched("sales-tap", salesTap)
    }

    // ================================================================ which callers reach each route (unchanged)

    test("the route rows are unchanged: generate needs tap create, fix and optimize tap update, review tap read (rule 4 is reported only)") {
        assert(CapabilityRoutes.lookup("POST", "/api/v1/tap/generate") == RouteCheck.Require("tap", "create"))
        assert(CapabilityRoutes.lookup("POST", "/api/v1/tap/fix") == RouteCheck.Require("tap", "update"))
        assert(CapabilityRoutes.lookup("POST", "/api/v1/tap/optimize") == RouteCheck.Require("tap", "update"))
        assert(CapabilityRoutes.lookup("POST", "/api/v1/tap/review") == RouteCheck.Require("tap", "read"))
    }

    // ================================================================ rule 1: mayWriteStoredTap

    test("rule 1: mayWriteStoredTap is true for every caller who may update the stored tap") {
        for (rk <- Seq(null, admin, editor, rita, fullKey, legacyKey, fullTemplateKey, salesKey))
            assert(TapAPIController.mayWriteStoredTap(req(rk, "/api/v1/tap/review"), salesTap), String.valueOf(rk))
    }

    test("rule 1: mayWriteStoredTap accepts tap create in scope (the rag-builder template keeps changing taps it may create, by the earlier owner decision)") {
        assert(TapAPIController.mayWriteStoredTap(req(ragKey, "/api/v1/tap/review"), salesTap))
        assert(TapAPIController.mayWriteStoredTap(req(key("creator", "tap:read", "tap:create"), "/api/v1/tap/review"), salesTap))
    }

    test("rule 1: mayWriteStoredTap is false for a caller who may read the tap but not update it") {
        val readers = Seq(readOnlyKey, reportingKey, opsKey, plainReadKey, viewer, ritaView, financeWriter, financeKey)
        for (rk <- readers) assert(!TapAPIController.mayWriteStoredTap(req(rk, "/api/v1/tap/review"), salesTap), rk.label)
        // a key limited to what it created may update only its own taps
        val selfKey = key("self-agent", "tap:read:owner=self", "tap:update:owner=self")
        assert(!TapAPIController.mayWriteStoredTap(req(selfKey, "/api/v1/tap/review"), salesTap))
        assert(TapAPIController.mayWriteStoredTap(req(selfKey, "/api/v1/tap/review"), salesTap.copy(createdByKeyLabel = "self-agent")))
    }

    // ================================================================ rule 1: review

    test("rule 1, review: a caller who may update the tap changes it exactly as on v1.61.0") {
        for (rk <- Seq(admin, editor, rita, fullKey, legacyKey, fullTemplateKey, salesKey, ragKey)) assertChangedAsToday("review", rk)
    }

    test("rule 1, review: with no identity (API keys off) the tap is changed exactly as on v1.61.0") {
        assertChangedAsToday("review", null)
    }

    test("rule 1, review: a caller who may read but not update gets the same 200 and rewrite, the stored tap is left unchanged, and the response says so") {
        for (rk <- Seq(readOnlyKey, reportingKey, opsKey, plainReadKey, viewer)) assertLeftUnchanged("review", rk)
    }

    test("rule 1, review: callers refused before the model on v1.61.0 keep that refusal (restricted viewer, keys whose update is limited to another catalog)") {
        for (rk <- Seq(ritaView, financeWriter, financeKey)) assertRefusedAsToday("review", rk)
    }

    test("rule 1, review: on a scheduled tap a caller who may update gets today's schedule-gate message; one who may not gets the unchanged-tap message") {
        val scheduled = salesTap.copy(cronExpression = "0 0 * * * ?")
        val ok = new World(Seq(scheduled))
        val r = call("review", editor, ok)
        assert(json(r) == withError(todayBody("review", newPath("sales-tap")), TapCronGate.storeRefusal("sales-tap")), r.getBody)
        ok.untouched("sales-tap", scheduled)
        val ro = new World(Seq(scheduled))
        val r2 = call("review", readOnlyKey, ro)
        assert(json(r2) == withError(todayBody("review", newPath("sales-tap")), Rule1Message), r2.getBody)
        ro.untouched("sales-tap", scheduled)
    }

    test("rule 1, review: a review that does not rewrite answers exactly as on v1.61.0, with no message, for every caller") {
        for (rk <- Seq(readOnlyKey, viewer, editor, admin)) {
            val w = new World(rewritten = false)
            val r = call("review", rk, w)
            val o = json(r)
            assert(!o.has("error"), rk.label + ": " + r.getBody)
            assert(o.get("rewritten").getAsBoolean == false && o.get("scriptPath").getAsString == OldPath, r.getBody)
            w.untouched("sales-tap", salesTap)
        }
    }

    // ================================================================ rule 1: generate, fix, optimize

    test("rule 1, generate / fix / optimize: every caller that reaches the repoint today still changes the tap exactly as on v1.61.0") {
        // generate's gate is tap:create; fix and optimize need tap:update. Each of these
        // callers holds the route's capability and may update (or create) the tap.
        for (rk <- Seq(admin, editor, rita, fullKey, legacyKey, fullTemplateKey, salesKey, null)) {
            assertChangedAsToday("generate", rk)
            assertChangedAsToday("fix", rk)
            assertChangedAsToday("optimize", rk)
        }
        // rag-builder holds tap:create (generate's gate) without tap:update: generate keeps changing the tap
        assertChangedAsToday("generate", ragKey)
    }

    test("rule 1, generate / fix / optimize: callers refused before the model on v1.61.0 keep that refusal") {
        for (route <- Seq("generate", "fix", "optimize"); rk <- Seq(ritaView, financeWriter, financeKey)) assertRefusedAsToday(route, rk)
    }

    test(
        "rule 1, generate / fix / optimize: a caller past the route gate who may not update the tap is not changed and is told so (no such caller today; the rule holds anyway)"
    ) {
        // A key holding only tap:read cannot pass these gates; called past the gate, the rule still holds.
        for (route <- Seq("generate", "fix", "optimize")) assertLeftUnchanged(route, plainReadKey)
    }

    // ================================================================ rule 2: no tap name

    private val tapNamedTap = salesTap.copy(name = "tap", scriptPath = OldPath)

    test("rule 2: a real tap can be called 'tap' (names are not validated); a request with no tap name never reads or writes it, for every caller") {
        for (route <- Routes; rk <- Seq(admin, editor, rita, fullKey, legacyKey, readOnlyKey, null)) {
            val w = new World(Seq(tapNamedTap, salesTap))
            val r = call(route, rk, w, tapName = None)
            assert(r.getStatusCode.value == 200, route + " " + String.valueOf(rk) + ": " + r.getBody)
            assert(w.reads.isEmpty, route + ": no stored tap is read: " + w.reads)
            assert(w.plainWrites.isEmpty && w.versioned.isEmpty, route + ": nothing written")
            assert(w.taps("tap") eq tapNamedTap)
            assert(!json(r).has("error"), route + ": " + r.getBody)
            assert(w.aiCalls.size == 1, "the model is still called: " + w.aiCalls)
        }
    }

    test("rule 2: a blank tap name never reads or writes a stored tap") {
        for (route <- Routes; blank <- Seq("", "   ")) {
            val w = new World(Seq(tapNamedTap, salesTap.copy(name = blank)))
            val r = call(route, admin, w, tapName = Some(blank))
            assert(r.getStatusCode.value == 200, route + ": " + r.getBody)
            assert(w.reads.isEmpty && w.plainWrites.isEmpty && w.versioned.isEmpty, route + " '" + blank + "': " + w.reads)
        }
    }

    test("rule 2: an explicit tapName 'tap' names that tap: an admin's review or optimize of it changes it as on v1.61.0") {
        for (route <- Seq("review", "optimize")) {
            val w = new World(Seq(tapNamedTap))
            val r = call(route, admin, w, tapName = Some("tap"))
            assert(r.getStatusCode.value == 200 && w.versioned.size == 1 && w.versioned.head._1.name == "tap", route + ": " + w.versioned)
        }
    }

    test("rule 2: a restricted session's review with no tap name is answered by the model, whatever a stored tap called 'tap' is") {
        // v1.61.0 looked up 'tap' for it: hidden -> 404, visible but not updatable -> 403
        val hiddenTap = tapNamedTap.copy(catalog = "Finance")
        for (route <- Seq("review", "optimize"); rk <- Seq(rita, ritaView)) {
            val w = new World(Seq(hiddenTap))
            val r = call(route, rk, w, tapName = None)
            assert(r.getStatusCode.value == 200, route + " " + rk.label + ": " + r.getBody)
            assert(!r.getBody.contains("Finance") && w.reads.isEmpty, r.getBody)
        }
    }

    // ================================================================ unchanged: the UI and the agents

    test("unchanged: the tap wizard as a signed-in editor (unnamed draft, then the named tap) gets v1.61.0's answers on all four routes") {
        for (route <- Routes) {
            // a draft the editor has not named yet: the wizard sends tapName ""
            val draft = new World(Seq(salesTap))
            val d = call(route, editor, draft, tapName = Some(""))
            assert(d.getStatusCode.value == 200 && !json(d).has("error") && draft.versioned.isEmpty && draft.plainWrites.isEmpty, route + ": " + d.getBody)
            // the saved tap
            assertChangedAsToday(route, editor)
        }
    }

    test("unchanged: the agents' only route here is generate (MCP create_tap with an instruction); every identity they call with gets v1.61.0's answer") {
        // An external MCP client's own key: a rag-builder or editor-shaped key, a full key.
        // The in-platform Assistant and the other chats: the ui key narrowed to the person's
        // session bundle (admin: full access relabelled session:<user>); a viewer is refused
        // by generate's gate (tap:create), as today.
        for (rk <- Seq(ragKey, fullKey, legacyKey, editor, admin, rita)) assertChangedAsToday("generate", rk)
        // MCP create_tap returns any body carrying `error` to the agent as a failure; no
        // identity that passes generate's gate gets the rule-1 message.
        for (rk <- Seq(ragKey, fullKey, legacyKey, editor, admin, rita, salesKey))
            assert(TapAPIController.mayWriteStoredTap(req(rk, "/api/v1/tap/generate"), salesTap), rk.label)
    }

    // ================================================================ rule 4 (reported only) at the review route

    private def withWarns[T](f: => T): (T, List[String]) = {
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[ch.qos.logback.classic.Logger]
        val appender = new ListAppender[ILoggingEvent]()
        appender.start()
        rootLogger.addAppender(appender)
        try {
            val r = f
            (r, appender.list.asScala.toList.filter(_.getLevel == Level.WARN).map(_.getFormattedMessage))
        } finally rootLogger.detachAppender(appender)
    }

    test("rule 4: a review by a key without tap update is recorded and logged once, and answered as rule 1 says") {
        val k = key("rule4-reader-" + System.nanoTime(), "tap:read")
        val (r, warns) = withWarns(call("review", k, new World()))
        assert(r.getStatusCode.value == 200 && json(r).get("script").getAsString == NewScript, r.getBody)
        assert(TapReviewAccessLog.labels().contains(k.label), TapReviewAccessLog.labels().toString)
        assert(warns.count(w => w.contains(k.label) && w.contains("sales-tap")) == 1, warns.mkString("\n"))
        // the same key reviewing the same tap again the same day: no second line
        val (_, again) = withWarns(call("review", k, new World()))
        assert(!again.exists(w => w.contains(k.label) && w.contains("sales-tap")), again.mkString("\n"))
    }

    test("rule 4: the rag-builder template (tap create, no update) is recorded too: the next release requires tap update for review") {
        val k = key("rule4-rag-" + System.nanoTime(), KeyTemplatesForSpecs.capabilities("rag-builder"): _*)
        val (r, _) = withWarns(call("review", k, new World()))
        assert(r.getStatusCode.value == 200)
        assert(TapReviewAccessLog.labels().contains(k.label))
    }

    test("rule 4: callers holding tap update, and requests with no identity, are not recorded or logged") {
        val k = key("rule4-updater-" + System.nanoTime(), "tap:read", "tap:update")
        val (_, warns) = withWarns(call("review", k, new World()))
        assert(!TapReviewAccessLog.labels().contains(k.label))
        assert(!warns.exists(_.contains(k.label)), warns.mkString("\n"))
        val before = TapReviewAccessLog.labels()
        call("review", null, new World())
        call("review", editor, new World())
        assert(!TapReviewAccessLog.labels().contains(editor.label))
        assert(TapReviewAccessLog.labels().size == before.size)
    }

    test("rule 4: the review access log is bounded") {
        for (i <- 1 to (TapReviewAccessLog.MaxLabels + 50)) TapReviewAccessLog.record("bounded-" + i)
        assert(TapReviewAccessLog.labels().size <= TapReviewAccessLog.MaxLabels)
        assert(TapReviewAccessLog.since != null)
    }

    private def reviewRows(label: String): List[AuditEntry] =
        audit.synchronized(audit.toList).filter(e => e.action == "reference-would-be-refused" && e.actor.label == label)

    test("rule 4: a review without tap update also writes one durable audit row per caller a UTC day, keeping the request's own audit entry") {
        val k = key("rule4-audit-" + System.nanoTime(), "tap:read")
        val (r1, attrs) = ScopeDenialFixtures.request(k, "POST", "/api/v1/tap/review")
        call("review", k, new World(), request = r1)
        val other = salesTap.copy(name = "other-tap")
        call("review", k, new World(Seq(other)), tapName = Some("other-tap"))
        call("review", k, new World(), tapName = None)
        val rows = reviewRows(k.label)
        assert(rows.size == 1, rows.toString)
        val row = rows.head
        assert(row.category == "tap" && row.outcome == "warning" && row.resourceType.contains("tap") && row.resourceName.contains("sales-tap"), row.toString)
        val md = row.metadata.getOrElse(fail("no metadata"))
        assert(md.get("rule").getAsString == "4" && md.get("site").getAsString == "review" && !md.get("enforced").getAsBoolean, md.toString)
        assert(!attrs.contains(AuditLog.RecordedAttr), "the request's own audit entry is kept")
    }

    test("rule 4: callers holding tap update write no review audit row") {
        val k = key("rule4-updater-row-" + System.nanoTime(), "tap:read", "tap:update")
        call("review", k, new World())
        call("review", editor, new World())
        assert(reviewRows(k.label).isEmpty && reviewRows(editor.label).isEmpty)
    }

    test("rule 4: the review access log is kept per environment, as the doctor row that reads it is") {
        val label = "rule4-env-" + System.nanoTime()
        TapReviewAccessLog.record(label, "env-a")
        assert(TapReviewAccessLog.labels("env-a").contains(label))
        assert(!TapReviewAccessLog.labels("env-b").contains(label))
        assert(!TapReviewAccessLog.labels().contains(label), "the current environment ('" + env.environment + "') does not list it")
    }

    // ================================================================ CAPABILITY_ENFORCEMENT=log-only

    /** An interceptor running as under CAPABILITY_ENFORCEMENT=log-only (its
      * `enforce` flag is read once from the environment at construction). */
    private def interceptor(logOnly: Boolean): CapabilityInterceptor = {
        val i = new CapabilityInterceptor()
        val f = classOf[CapabilityInterceptor].getDeclaredFields
            .find(f => f.getType == java.lang.Boolean.TYPE && f.getName.toLowerCase.contains("enforce"))
            .getOrElse(fail("CapabilityInterceptor has no boolean 'enforce' field"))
        f.setAccessible(true)
        f.setBoolean(i, !logOnly)
        i
    }

    /** The route gate on `request`: whether the handler runs. */
    private def gate(i: CapabilityInterceptor, request: HttpServletRequest): Boolean = {
        val resp = mock(classOf[HttpServletResponse])
        when(resp.getWriter).thenReturn(new java.io.PrintWriter(new java.io.StringWriter()))
        doAnswer(_ => null).when(resp).setStatus(anyInt())
        i.preHandle(request, resp, null)
    }

    /** Callers without the generate, fix and optimize gates' capability (tap create; tap update). */
    private val belowGate = Seq(readOnlyKey, reportingKey, plainReadKey, viewer)

    test("log-only: generate, fix and optimize change the stored tap as on v1.61.0 for a caller the route gate passed only because of log-only") {
        val lo = interceptor(logOnly = true)
        for (route <- Seq("generate", "fix", "optimize"); rk <- belowGate) {
            val (r, attrs) = ScopeDenialFixtures.request(rk, "POST", "/api/v1/tap/" + route)
            assert(gate(lo, r), route + " " + rk.label + ": log-only lets it through")
            assert(attrs.contains(CapabilityInterceptor.LogOnlyWouldDenyAttr), route + " " + rk.label + ": the gate noted the would-deny")
            assertChangedAsToday(route, rk, r)
        }
    }

    test("enforce: the same callers are refused at the generate, fix and optimize gates, and a request the gate did not pass under log-only keeps rule 1") {
        val en = interceptor(logOnly = false)
        for (route <- Seq("generate", "fix", "optimize"); rk <- belowGate) {
            val (r, attrs) = ScopeDenialFixtures.request(rk, "POST", "/api/v1/tap/" + route)
            assert(!gate(en, r), route + " " + rk.label + ": refused in enforce mode")
            assert(!attrs.contains(CapabilityInterceptor.LogOnlyWouldDenyAttr))
            assertLeftUnchanged(route, rk)
        }
    }

    test("log-only: a request the gate granted is not marked, and is answered as in enforce mode") {
        val lo = interceptor(logOnly = true)
        val granted = Seq("generate" -> ragKey) ++ (for (route <- Seq("generate", "fix", "optimize"); rk <- Seq(editor, salesKey)) yield route -> rk)
        for ((route, rk) <- granted) {
            val (r, attrs) = ScopeDenialFixtures.request(rk, "POST", "/api/v1/tap/" + route)
            assert(gate(lo, r), route + " " + rk.label)
            assert(!attrs.contains(CapabilityInterceptor.LogOnlyWouldDenyAttr), route + " " + rk.label)
            assertChangedAsToday(route, rk, r)
        }
        assert(!TapAPIController.passedGateOnlyInLogOnly(req(editor, "/api/v1/tap/fix")))
        assert(!TapAPIController.passedGateOnlyInLogOnly(null))
    }

    test("review in both modes: a caller who holds tap read but may not update the tap leaves it unchanged") {
        for (logOnly <- Seq(true, false); rk <- Seq(readOnlyKey, reportingKey, plainReadKey, viewer)) {
            val (r, attrs) = ScopeDenialFixtures.request(rk, "POST", "/api/v1/tap/review")
            assert(gate(interceptor(logOnly), r), rk.label + ": review's gate is tap read, which it holds")
            assert(!attrs.contains(CapabilityInterceptor.LogOnlyWouldDenyAttr))
            assertLeftUnchanged("review", rk, r)
        }
    }

    test("log-only: review changes the stored tap as on v1.61.0 for a caller the route gate passed only because of log-only; enforce refuses it at the gate") {
        val noTapRead = key("no-tap-read-" + System.nanoTime(), "pipeline:read")
        val (r, attrs) = ScopeDenialFixtures.request(noTapRead, "POST", "/api/v1/tap/review")
        assert(gate(interceptor(logOnly = true), r) && attrs.contains(CapabilityInterceptor.LogOnlyWouldDenyAttr))
        assertChangedAsToday("review", noTapRead, r)
        val (e, eAttrs) = ScopeDenialFixtures.request(noTapRead, "POST", "/api/v1/tap/review")
        assert(!gate(interceptor(logOnly = false), e), "refused in enforce mode")
        assert(!eAttrs.contains(CapabilityInterceptor.LogOnlyWouldDenyAttr))
        assertLeftUnchanged("review", noTapRead, e)
    }
}
