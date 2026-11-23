package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.ScopeDenialFixtures
import ai.datris.config.CapabilityInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, CatalogOps, UserStore}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.{JsonObject, JsonParser}
import jakarta.servlet.http.HttpServletRequest
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: Change a tap's owner (plans/stories/tap-change-owner.md),
  * Acceptance bullets 2 and 3 (`TapChangeOwnerRouteSpec`): `POST
  * /api/v1/tap/owner`, body `{"name", "user"}` or `{"name", "key"}`.
  *
  *  - Who may call (O1): an administrator session or a full-access API key
  *    (and, with sign-in and keys both off, anyone, as on every route).
  *    Everyone else gets one 403 that names nothing of the tap, before
  *    anything is read, in enforce and in log-only capability mode alike.
  *  - A missing tap is 404; a catalog placeholder is 400; an ineligible new
  *    owner is 409 (TapOwnerChangeSpec has the rules).
  *  - The write (O3): the stored tap differs from before only in its owner.
  *    No tap version is written. The same owner writes nothing. A tap
  *    moved, repointed or deleted between the decision and the write is
  *    not changed.
  *  - One audit row `tap / owner-change` with `from` and `to`; a refused
  *    call's denial row is classified as the same action.
  *  - After a change, `owner=self` access follows the new owner at once.
  *
  * Names the implementation must provide:
  * {{{
  * object TapAPIController {
  *   /** The store reads and the write of POST /tap/owner. `writeVersioned`
  *     * is never called by this route (no tap version is written). */
  *   private[datris] case class TapOwnerChangeIO(
  *     readTap: String => TapConfig,
  *     write: TapConfig => Unit,
  *     findUser: String => Option[User],
  *     keyByLabel: String => Option[ResolvedKey],
  *     readPipeline: String => PipelineConfig,
  *     secretOwner: String => Option[Option[String]],
  *     writeVersioned: (TapConfig, String, String) => TapConfig
  *   )
  * }
  * class TapAPIController {
  *   /** POST /tap/owner after key validation; changeTapOwner delegates to it
  *     * with the live store reads. */
  *   private[datris] def changeTapOwnerWith(body: java.util.Map[String, String], request: HttpServletRequest,
  *                                          io: TapAPIController.TapOwnerChangeIO): ResponseEntity[String]
  * }
  * }}}
  * The handler reads the tap once to decide and once more under the catalog
  * lock before writing.
  */
class TapChangeOwnerRouteSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val env: DatrisEnvironment = ScopeDenialFixtures.auditEnv()
    private var savedValues: DatrisEnvironment = _
    private val audit = mutable.ArrayBuffer[AuditEntry]()

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        DatrisEnvironment.init(env)
        ScopeDenialFixtures.install(env)
        audit.clear()
        AuditLog.sinkOverride = (e: AuditEntry) => audit.synchronized { audit += e; () }
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        ScopeDenialFixtures.install(savedValues)
    }

    private val Path = "/api/v1/tap/owner"
    private val Refusal = "Changing a tap's owner needs an administrator or a full-access API key"

    // ---------------------------------------------------------------- identities

    private def key(label: String, caps: String*): ResolvedKey = ScopeDenialFixtures.key(label, caps: _*)
    private def session(u: User): ResolvedKey = APIKeyValidator.resolveFromSession(u)

    private val ada = User("ada", "h", User.RoleAdmin, null, null, null)
    private val ed = User("ed", "h", User.RoleEditor, null, null, null)
    private val vic = User("vic", "h", User.RoleViewer, null, null, null)
    private val rita = User("rita", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit"))

    private val adminSession = session(ada)
    private val fullKey = key("full-agent", "*:*")
    private val legacyKey = ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)

    private val refusedCallers: Seq[(String, ResolvedKey)] = Seq(
        "editor session" -> session(ed),
        "viewer session" -> session(vic),
        "restricted user" -> session(rita),
        "key holding only tap:update" -> key("updater", "tap:update"),
        "key holding tap:* in one catalog" -> key("sales-taps", "tap:*:catalog=Sales"),
        "key holding *:* on what it created" -> key("self-all", "*:*:owner=self"),
        "key holding *:* in one catalog" -> key("sales-all", "*:*:catalog=Sales")
    )

    /** New owners. */
    private val builder = key("builder", "pipeline:read", "tap:read:owner=self", "tap:create", "tap:update:owner=self", "tap:run:owner=self")
    private val oldBuilder = key("old-builder", "pipeline:read", "tap:read:owner=self", "tap:create", "tap:update:owner=self", "tap:run:owner=self")
    private val salesKey = key(
        "sales-agent",
        "pipeline:read:catalog=Sales",
        "pipeline:run:catalog=Sales",
        "tap:read:catalog=Sales",
        "tap:create:catalog=Sales",
        "tap:update:catalog=Sales",
        "tap:run:catalog=Sales"
    )

    // ---------------------------------------------------------------- stored items

    private val salesOrders = PipelineConfig(name = "sales-orders", catalog = "Sales", createdByKeyLabel = "session:ada")
    private val financeLedger = PipelineConfig(name = "finance-ledger", catalog = "Finance", createdByKeyLabel = "session:ada")

    private def jl(xs: String*): java.util.List[String] = new java.util.ArrayList[String](xs.asJava)

    /** A tap with every field the change must leave alone set to a non-default value. */
    private val stored = TapConfig(
        name = "orders-feed",
        description = "orders",
        scriptPath = "tap-scripts/orders-feed.py",
        targetPipeline = "sales-orders",
        packages = jl("requests"),
        secretName = "weather",
        cronExpression = "0 0 * * * ?",
        enabled = false,
        lastRunStatus = "failure",
        lastRunTime = "2026-11-22 01:00:00",
        lastRunRecordCount = 17,
        lastRunError = "boom",
        lastRunDataType = "json",
        lastRunColumns = jl("a", "b"),
        lastTestRunStatus = "success",
        lastTestRunTime = "2026-11-21 01:00:00",
        lastTestRunRecordCount = 5,
        lastTestRunDataType = "json",
        lastTestRunColumns = jl("a"),
        createdAt = "2026-01-01 00:00:00",
        updatedAt = "2026-11-20 12:00:00",
        catalog = "Sales",
        createdByKeyLabel = "old-builder",
        version = 7,
        lastRunTrigger = "cron",
        lastRunRetrySafe = true,
        retryCount = 2,
        tags = jl("orders"),
        source = "source.example",
        lastTestRunScriptId = "sha-1",
        dependsOn = jl(),
        inferredDependsOn = jl(),
        lastRunUsedCallback = false
    )

    // ---------------------------------------------------------------- stores

    /** In-memory stores. `reads`: answers of successive tap reads (the last
      * one repeats); every call of every store is counted. */
    private class Store(
        reads: Seq[TapConfig] = Seq(stored),
        pipelines: Seq[PipelineConfig] = Seq(salesOrders, financeLedger),
        users: Seq[User] = Seq(ada, ed, vic, rita),
        keys: Seq[ResolvedKey] = Seq(builder, oldBuilder, salesKey, fullKey),
        secretOwners: Map[String, Option[String]] = Map("weather" -> Some("builder")),
        failUsers: Boolean = false,
        failPipelines: Boolean = false
    ) {
        val tapReads = mutable.Buffer[String]()
        val otherReads = mutable.Buffer[String]()
        val writes = mutable.Buffer[TapConfig]()
        val versionWrites = mutable.Buffer[(TapConfig, String, String)]()
        private var i = 0
        val io: TapAPIController.TapOwnerChangeIO = TapAPIController.TapOwnerChangeIO(
            readTap = n => {
                tapReads += n
                val t = reads(math.min(i, reads.size - 1))
                i += 1
                if (t != null && t.name == n) t else null
            },
            write = t => { writes += t; () },
            findUser = n => {
                otherReads += "user:" + n
                if (failUsers) throw new IllegalStateException("user store down")
                users.find(_.username == UserStore.normalize(n))
            },
            keyByLabel = l => { otherReads += "key:" + l; keys.find(_.label == l) },
            readPipeline = n => {
                otherReads += "pipeline:" + n
                if (failPipelines) throw new IllegalStateException("pipeline store down")
                pipelines.find(_.name == n).orNull
            },
            secretOwner = n => { otherReads += "secret:" + n; secretOwners.get(n) },
            writeVersioned = (t, note, actor) => { versionWrites += ((t, note, actor)); t }
        )
        def readNothing: Boolean = tapReads.isEmpty && otherReads.isEmpty
    }

    private def req(rk: ResolvedKey, logOnlyPassed: Boolean = false): HttpServletRequest = {
        val (r, attrs) = ScopeDenialFixtures.request(rk, "POST", Path)
        if (logOnlyPassed) attrs.put(CapabilityInterceptor.LogOnlyWouldDenyAttr, java.lang.Boolean.TRUE)
        r
    }

    private def body(pairs: (String, String)*): java.util.Map[String, String] = {
        val m = new java.util.LinkedHashMap[String, String]()
        pairs.foreach { case (k, v) => m.put(k, v) }
        m
    }

    /** The route's answer; a thrown store failure is an answer with no status. */
    private def change(store: Store, request: HttpServletRequest, b: java.util.Map[String, String]): Either[Throwable, ResponseEntity[String]] =
        try Right(new TapAPIController().changeTapOwnerWith(b, request, store.io))
        catch { case e: Exception => Left(e) }

    private def ok(store: Store, request: HttpServletRequest, b: java.util.Map[String, String]): JsonObject =
        change(store, request, b) match {
            case Right(r) =>
                assert(r.getStatusCode.value == 200, r.getStatusCode + " " + r.getBody)
                JsonParser.parseString(r.getBody).getAsJsonObject
            case Left(e) => fail("threw " + e)
        }

    private def status(store: Store, request: HttpServletRequest, b: java.util.Map[String, String]): ResponseEntity[String] =
        change(store, request, b) match {
            case Right(r) => r
            case Left(e) => fail("threw " + e)
        }

    private def errorOf(r: ResponseEntity[String]): String = JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString

    private def ownerChangeRows: List[AuditEntry] = audit.synchronized(audit.toList).filter(e => e.category == "tap" && e.action == "owner-change")

    // ================================================================ who may call

    test("an administrator session changes the owner") {
        val s = new Store()
        val o = ok(s, req(adminSession), body("name" -> "orders-feed", "key" -> "builder"))
        assert(o.get("changed").getAsBoolean, o.toString)
        assert(s.writes.size == 1 && s.writes.head.createdByKeyLabel == "builder", s.writes.toString)
    }

    test("an unscoped full-access key and a legacy key change the owner") {
        for (rk <- Seq(fullKey, legacyKey)) {
            val s = new Store()
            val o = ok(s, req(rk), body("name" -> "orders-feed", "user" -> "ed"))
            assert(o.get("changed").getAsBoolean, rk.label + " " + o)
            assert(s.writes.size == 1 && s.writes.head.createdByKeyLabel == "session:ed", rk.label + " " + s.writes)
        }
    }

    test("with user sign-in and API keys both off, the caller is let through, as on every route of such an install") {
        val anonymous = ResolvedKey(None, "anonymous", Seq(Capability.FullAccess), isLegacyFullAccess = true)
        for (r <- Seq(req(null), req(anonymous))) {
            val s = new Store()
            ok(s, r, body("name" -> "orders-feed", "key" -> "builder"))
            assert(s.writes.size == 1)
        }
    }

    test("every other caller gets one 403 that names nothing, and the tap is not read") {
        for ((who, rk) <- refusedCallers) {
            val s = new Store()
            val r = status(s, req(rk), body("name" -> "orders-feed", "key" -> "builder"))
            assert(r.getStatusCode.value == 403, who + ": " + r.getStatusCode + " " + r.getBody)
            assert(errorOf(r) == Refusal, who + ": " + r.getBody)
            for (name <- Seq("orders-feed", "Sales", "old-builder", "builder", "sales-orders"))
                assert(!r.getBody.contains(name), who + ": '" + name + "' named in " + r.getBody)
            assert(s.readNothing, who + ": read " + s.tapReads + " " + s.otherReads)
            assert(s.writes.isEmpty && s.versionWrites.isEmpty, who)
        }
    }

    test("the same 403, nothing read, for a caller the route gate let through only because capability enforcement is log-only") {
        for ((who, rk) <- refusedCallers) {
            val s = new Store()
            val r = status(s, req(rk, logOnlyPassed = true), body("name" -> "orders-feed", "key" -> "builder"))
            assert(r.getStatusCode.value == 403 && errorOf(r) == Refusal, who + ": " + r.getStatusCode + " " + r.getBody)
            assert(s.readNothing && s.writes.isEmpty, who)
        }
    }

    test("a refused call is recorded as a denial of the owner-change action, naming no new owner") {
        val s = new Store()
        status(s, req(key("updater", "tap:update")), body("name" -> "orders-feed", "key" -> "builder"))
        val denied = audit.synchronized(audit.toList).filter(e => e.category == "security" && e.action == "denied")
        assert(denied.size == 1, audit.toString)
        val md = denied.head.metadata.get
        assert(md.get("category").getAsString == "tap" && md.get("action").getAsString == "owner-change", md.toString)
        assert(denied.head.httpStatus.contains(403))
        assert(ownerChangeRows.isEmpty, "no success row for a refused call")
    }

    // ================================================================ the tap and the new owner

    test("a missing tap is 404 and nothing is written") {
        val s = new Store(reads = Seq(null))
        val r = status(s, req(adminSession), body("name" -> "no-such-tap", "key" -> "builder"))
        assert(r.getStatusCode == TapAPIController.tapNotFound("no-such-tap").getStatusCode, r.getBody)
        assert(r.getBody == TapAPIController.tapNotFound("no-such-tap").getBody, r.getBody)
        assert(s.writes.isEmpty)
    }

    test("a catalog placeholder is 400 and nothing is written") {
        val placeholder = TapConfig(name = CatalogOps.Placeholder.name("Sales"), description = null, targetPipeline = null, catalog = "Sales")
        val s = new Store(reads = Seq(placeholder))
        val r = status(s, req(adminSession), body("name" -> placeholder.name, "key" -> "builder"))
        assert(r.getStatusCode.value == 400, r.getStatusCode + " " + r.getBody)
        assert(s.writes.isEmpty)
    }

    test("a body naming both a user and a key, neither, an unknown user or an unknown key is 400 and nothing is written") {
        val bodies = Seq(
            body("name" -> "orders-feed", "user" -> "ed", "key" -> "builder"),
            body("name" -> "orders-feed"),
            body("name" -> "orders-feed", "user" -> "nobody"),
            body("name" -> "orders-feed", "key" -> "never-issued"),
            body("name" -> "orders-feed", "key" -> "anonymous")
        )
        for (b <- bodies) {
            val s = new Store()
            val r = status(s, req(adminSession), b)
            assert(r.getStatusCode.value == 400, b + ": " + r.getStatusCode + " " + r.getBody)
            assert(s.writes.isEmpty, b.toString)
        }
    }

    test("a new owner that cannot write the tap where it is, or may not load into its target, is 409 and nothing is written") {
        val inOps = stored.copy(catalog = "Ops", targetPipeline = null)
        val feedsFinance = stored.copy(targetPipeline = "finance-ledger")
        for (t <- Seq(inOps, feedsFinance)) {
            val s = new Store(reads = Seq(t))
            val r = status(s, req(adminSession), body("name" -> "orders-feed", "key" -> "sales-agent"))
            assert(r.getStatusCode.value == 409, t.catalog + "/" + t.targetPipeline + ": " + r.getStatusCode + " " + r.getBody)
            assert(s.writes.isEmpty && s.versionWrites.isEmpty)
            assert(ownerChangeRows.isEmpty)
        }
    }

    test("a store failure while resolving the new owner or its target is an error and nothing is written") {
        val users = new Store(failUsers = true)
        change(users, req(adminSession), body("name" -> "orders-feed", "user" -> "ed")) match {
            case Right(r) => assert(r.getStatusCode.is5xxServerError, r.getStatusCode + " " + r.getBody)
            case Left(_) =>
        }
        assert(users.writes.isEmpty)
        val pipelines = new Store(failPipelines = true)
        change(pipelines, req(adminSession), body("name" -> "orders-feed", "key" -> "sales-agent")) match {
            case Right(r) => assert(r.getStatusCode.is5xxServerError, r.getStatusCode + " " + r.getBody)
            case Left(_) =>
        }
        assert(pipelines.writes.isEmpty)
    }

    // ================================================================ the write

    test("the stored tap differs from before only in its owner; no version is written") {
        val s = new Store()
        ok(s, req(adminSession), body("name" -> "orders-feed", "key" -> "builder"))
        assert(s.writes.size == 1, s.writes.toString)
        assert(s.writes.head == stored.copy(createdByKeyLabel = "builder"), "only the owner changed: " + s.writes.head)
        assert(s.versionWrites.isEmpty, "no tap version: " + s.versionWrites)
    }

    test("the answer names the tap, the new and previous owner, that it changed, and the warnings") {
        val s = new Store()
        val o = ok(s, req(adminSession), body("name" -> "orders-feed", "key" -> "builder"))
        assert(o.get("name").getAsString == "orders-feed", o.toString)
        assert(o.get("owner").getAsString == "builder", o.toString)
        assert(o.get("previousOwner").getAsString == "old-builder", o.toString)
        assert(o.get("changed").getAsBoolean, o.toString)
        assert(o.get("warnings").isJsonArray && o.getAsJsonArray("warnings").size == 0, o.toString)
        // a warning the rules return is passed on, and the change still happens
        val missing = new Store(reads = Seq(stored.copy(targetPipeline = "not-created-yet")))
        val w = ok(missing, req(adminSession), body("name" -> "orders-feed", "key" -> "sales-agent"))
        assert(w.getAsJsonArray("warnings").size >= 1, w.toString)
        assert(missing.writes.size == 1 && missing.writes.head.createdByKeyLabel == "sales-agent")
    }

    test("a user value stores session:<stored username>") {
        val s = new Store()
        val o = ok(s, req(adminSession), body("name" -> "orders-feed", "user" -> "Ed"))
        assert(o.get("owner").getAsString == "session:ed", o.toString)
        assert(s.writes.head == stored.copy(createdByKeyLabel = "session:ed"))
    }

    test("a tap stored before owners were recorded (no owner) can be handed to a user") {
        val s = new Store(reads = Seq(stored.copy(createdByKeyLabel = null)))
        val o = ok(s, req(adminSession), body("name" -> "orders-feed", "user" -> "ed"))
        assert(o.get("changed").getAsBoolean, o.toString)
        assert(s.writes.size == 1 && s.writes.head == stored.copy(createdByKeyLabel = "session:ed"))
    }

    test("the same owner as stored answers changed: false and writes nothing") {
        val s = new Store()
        val o = ok(s, req(adminSession), body("name" -> "orders-feed", "key" -> "old-builder"))
        assert(!o.get("changed").getAsBoolean, o.toString)
        assert(o.get("owner").getAsString == "old-builder" && o.get("previousOwner").getAsString == "old-builder", o.toString)
        assert(s.writes.isEmpty && s.versionWrites.isEmpty)
        // twice in a row: the second call finds the owner already changed
        val twice = new Store(reads = Seq(stored.copy(createdByKeyLabel = "builder")))
        assert(!ok(twice, req(adminSession), body("name" -> "orders-feed", "key" -> "builder")).get("changed").getAsBoolean)
        assert(twice.writes.isEmpty)
    }

    test("a tap moved to another catalog, repointed or deleted between the decision and the write is not changed") {
        val moved = stored.copy(catalog = "Ops")
        val repointed = stored.copy(targetPipeline = "finance-ledger")
        for (fresh <- Seq(moved, repointed)) {
            val s = new Store(reads = Seq(stored, fresh))
            val r = status(s, req(adminSession), body("name" -> "orders-feed", "key" -> "builder"))
            assert(r.getStatusCode.value == 409, fresh.catalog + "/" + fresh.targetPipeline + ": " + r.getStatusCode + " " + r.getBody)
            assert(errorOf(r).contains("nothing was changed"), r.getBody)
            assert(s.writes.isEmpty && s.versionWrites.isEmpty)
            assert(s.tapReads.size >= 2, "decided on one read, re-read before writing: " + s.tapReads)
        }
        val gone = new Store(reads = Seq(stored, null))
        val r = status(gone, req(adminSession), body("name" -> "orders-feed", "key" -> "builder"))
        assert(r.getStatusCode.value == 404, r.getStatusCode + " " + r.getBody)
        assert(gone.writes.isEmpty)
    }

    test("a run that finished between the decision and the write is kept: the write is the re-read tap with the new owner") {
        val ranSince = stored.copy(lastRunStatus = "success", lastRunTime = "2026-11-22 02:00:00", lastRunRecordCount = 99, retryCount = 0)
        val s = new Store(reads = Seq(stored, ranSince))
        ok(s, req(adminSession), body("name" -> "orders-feed", "key" -> "builder"))
        assert(s.writes == Seq(ranSince.copy(createdByKeyLabel = "builder")), s.writes.toString)
    }

    // ================================================================ audit

    test("one audit row tap / owner-change naming the tap, with from and to") {
        val s = new Store()
        ok(s, req(adminSession), body("name" -> "orders-feed", "key" -> "builder"))
        val rows = ownerChangeRows
        assert(rows.size == 1, audit.toString)
        val row = rows.head
        assert(row.resourceType.contains("tap") && row.resourceName.contains("orders-feed"), row.toString)
        assert(row.outcome == "success", row.toString)
        val md = row.metadata.getOrElse(fail("no metadata: " + row))
        assert(md.get("from").getAsString == "old-builder" && md.get("to").getAsString == "builder", md.toString)
    }

    // ================================================================ owner=self follows the owner (bullet 3)

    test("after a change to an owner=self key, that key may read and update the tap and the previous owning key may not") {
        val before = stored
        // before: the old owner's tap
        assert(TapAPIController.mayRead(req(oldBuilder), before))
        assert(TapAPIController.storedTapWriteRefusal(req(oldBuilder), before.catalog, before.createdByKeyLabel).isEmpty)
        assert(!TapAPIController.mayRead(req(builder), before))
        assert(TapAPIController.storedTapWriteRefusal(req(builder), before.catalog, before.createdByKeyLabel).isDefined)

        val s = new Store()
        ok(s, req(adminSession), body("name" -> "orders-feed", "key" -> "builder"))
        val after = s.writes.head
        assert(TapAPIController.mayRead(req(builder), after), "the new owner reads it")
        assert(TapAPIController.storedTapWriteRefusal(req(builder), after.catalog, after.createdByKeyLabel).isEmpty, "the new owner updates it")
        assert(!TapAPIController.mayRead(req(oldBuilder), after), "the previous owner no longer reads it")
        val refused = TapAPIController.storedTapWriteRefusal(req(oldBuilder), after.catalog, after.createdByKeyLabel)
        assert(refused.exists(_.getStatusCode.value == 403), "the previous owner no longer updates it: " + refused)
    }
}
