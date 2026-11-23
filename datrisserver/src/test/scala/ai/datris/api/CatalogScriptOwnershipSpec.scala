package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.ScopeDenialFixtures
import ai.datris.model.{CodeRepoConfig, DatrisEnvironment, ResolvedKey, TapConfig, User}
import ai.datris.util.{APIKeyValidator, CatalogArchive, TapOwnerScopeReport}
import ai.datris.util.InMemoryUserDb.grants
import jakarta.servlet.http.HttpServletRequest
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.AtomicInteger

/** Story: A tap's script upload and delete act only on that tap's own script
  * file (plans/stories/tap-script-store-writes-and-deletes-in-scope.md), at
  * the catalog routes.
  *
  *  - Catalog restore onto a code repository: a tap script the restore would
  *    commit, for a tap whose live copy holds no repository reference of its
  *    own (missing, on built-in storage, or with no repository path), is a
  *    clash when its file is the current repository script of a stored tap of
  *    another name. The restore refuses as for any other clash, before
  *    anything is written; the clash names only the restored tap and its own
  *    file. A restore that hits no such file is unchanged. For a caller
  *    held to its own tap's script file the clash does not say that another
  *    tap runs from the file.
  *  - Catalog restore onto a code repository, rule S4: for a caller held to
  *    its own tap's script file (a restricted session, a key limited for
  *    taps, a key whose tap update carries a scope), a tap the restore would
  *    commit whose live copy is a repository tap with a stored path that is
  *    not the tap's own file is a clash naming only that tap, with one audit
  *    row; the restore refuses as for any other clash. Every other caller's
  *    restore is unchanged, and so is one where the live path is the tap's
  *    own file.
  *  - Catalog cascade delete: one tap listing serves every tap delete of the
  *    cascade, read at most once (a failed read stays failed, so each delete
  *    keeps its file), and taps already deleted in the cascade no longer count
  *    as using a file.
  *
  * Names the implementation must provide:
  * {{{
  * object CatalogAPIController {
  *   private[api] def restoreTargetClashes(plan: CatalogArchive.RepoScriptPlan, cfg: CodeRepoConfig, liveTaps: Seq[TapConfig],
  *                                         held: Boolean = false): Seq[String]
  *   private[api] def restoreNotOwnClashes(request: HttpServletRequest, plan: CatalogArchive.RepoScriptPlan, cfg: CodeRepoConfig,
  *                                         liveTaps: Seq[TapConfig]): Seq[String]
  *   def restoreNotOwnMessage(tapName: String): String
  *   private[api] def cascadeListing(read: () => Seq[TapConfig]): (() => Seq[TapConfig], String => Unit)
  * }
  * }}}
  */
class CatalogScriptOwnershipSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val env: DatrisEnvironment = ScopeDenialFixtures.auditEnv()
    private var savedValues: DatrisEnvironment = _
    private val audit = scala.collection.mutable.ArrayBuffer[AuditEntry]()

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

    private val Prefix = "datris-taps/"
    private val cfg = CodeRepoConfig(repo = "o/r", authSecretName = "s", enabled = true, pathPrefix = Prefix, branch = "main")

    private def plan(write: String*): CatalogArchive.RepoScriptPlan = CatalogArchive.RepoScriptPlan(Nil, Set.empty, write.toSet, Set.empty, Set.empty)

    private def builtIn(name: String): TapConfig =
        TapConfig(name = name, description = "d", targetPipeline = "p", scriptPath = "tap-scripts/" + name + "_1a2b3c4d.py")

    private def repo(name: String, path: String): TapConfig =
        TapConfig(name = name, description = "d", targetPipeline = "p", scriptStorage = "github", scriptRepoPath = path, scriptCommitSha = "abc1234")

    // ================================================================ restore

    test("restore: a member on built-in storage whose repository file another stored tap runs from is a clash naming only the member and its file") {
        val taps = Seq(builtIn("orders"), repo("finance-hand", Prefix + "orders.py"))
        val clashes = CatalogAPIController.restoreTargetClashes(plan("orders"), cfg, taps)
        assert(clashes.size == 1, clashes.toString)
        assert(clashes.head.contains("'orders'") && clashes.head.contains(Prefix + "orders.py"), clashes.head)
        assert(!clashes.head.contains("finance-hand"), clashes.head)
        // in any spelling of the other tap's reference
        assert(CatalogAPIController.restoreTargetClashes(plan("orders"), cfg, Seq(builtIn("orders"), repo("x", "datris-taps//orders.py"))).size == 1)
    }

    test("restore: a tap that is new here is a clash when its repository file is another stored tap's script") {
        val taps = Seq(repo("finance-hand", Prefix + "orders.py"))
        assert(CatalogAPIController.restoreTargetClashes(plan("orders"), cfg, taps).size == 1)
    }

    test("restore: unchanged when no stored tap of another name runs from the file") {
        val free = Seq(builtIn("orders"), repo("other", Prefix + "other.py"), builtIn("orders-copy"))
        assert(CatalogAPIController.restoreTargetClashes(plan("orders"), cfg, free).isEmpty)
        // the member's own repository reference is written over, as before, even while another tap shares it
        val ownRef = Seq(repo("orders", Prefix + "orders.py"), repo("copy", Prefix + "orders.py"))
        assert(CatalogAPIController.restoreTargetClashes(plan("orders"), cfg, ownRef).isEmpty)
        // a tap the restore does not commit (reused or absent from the plan) is not judged
        assert(CatalogAPIController.restoreTargetClashes(plan(), cfg, Seq(builtIn("orders"), repo("x", Prefix + "orders.py"))).isEmpty)
        // a built-in reference of another tap is not a repository file
        assert(CatalogAPIController.restoreTargetClashes(plan("orders"), cfg, Seq(builtIn("orders"), builtIn("x"))).isEmpty)
    }

    test("restore: each committed tap is judged, in name order") {
        val taps = Seq(builtIn("a"), builtIn("b"), repo("x", Prefix + "b.py"), repo("y", Prefix + "a.py"))
        val clashes = CatalogAPIController.restoreTargetClashes(plan("b", "a", "c"), cfg, taps)
        assert(clashes.size == 2 && clashes.head.contains("'a'") && clashes(1).contains("'b'"), clashes.toString)
    }

    test("restore: a caller held to its own tap's script file is told only that the file cannot be written, not that another tap runs from it") {
        val taps = Seq(builtIn("orders"), repo("finance-hand", Prefix + "orders.py"))
        val clashes = CatalogAPIController.restoreTargetClashes(plan("orders"), cfg, taps, held = true)
        assert(clashes.size == 1, clashes.toString)
        assert(clashes.head.contains("'orders'") && clashes.head.contains(Prefix + "orders.py"), clashes.head)
        assert(!clashes.head.contains("another tap") && !clashes.head.contains("runs from") && !clashes.head.contains("finance-hand"), clashes.head)
        // an unrestricted caller keeps the current text
        assert(CatalogAPIController.restoreTargetClashes(plan("orders"), cfg, taps).head.contains("another tap runs from"))
    }

    // ================================================================ restore, rule S4

    private def key(label: String, caps: String*): ResolvedKey = ScopeDenialFixtures.key(label, caps: _*)
    private def session(u: User): ResolvedKey = APIKeyValidator.resolveFromSession(u)

    private val rita = session(User("rita", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit")))
    private val salesKey = key("sales-agent", "pipeline:read:catalog=Sales", "tap:read:catalog=Sales", "tap:create:catalog=Sales", "tap:update:catalog=Sales")
    private val builder = key("builder", "pipeline:read", "tap:read:owner=self", "tap:create", "tap:update:owner=self")
    private val scopedUpdater = key("scoped-updater", "pipeline:read", "tap:read", "tap:create:catalog=Sales", "tap:update:catalog=Sales")
    private val held = Seq(rita, salesKey, builder, scopedUpdater)

    private val admin = session(User("ada", "h", User.RoleAdmin, null, null, null))
    private val editor = session(User("ed", "h", User.RoleEditor, null, null, null))
    private val fullKey = key("full-agent", "*:*")
    private val pipelineOnly = key("pipeline-only", "pipeline:read:catalog=Sales", "tap:read", "tap:create", "tap:update")

    private def req(rk: ResolvedKey): HttpServletRequest = ScopeDenialFixtures.request(rk, "POST", "/api/v1/catalog/Sales/restore")._1

    private def refusedRows(name: String): List[AuditEntry] =
        audit.synchronized(audit.toList).filter(e => e.resourceName.contains(name) && e.action == TapOwnerScopeReport.ReferenceWouldBeRefusedAction)

    private val handPlaced = Prefix + "lib/shared.py"

    test("restore S4: a held caller's restore of a tap whose live repository path is not its own file is a clash naming only that tap, with one audit row") {
        for (rk <- held) {
            audit.clear()
            val clashes = CatalogAPIController.restoreNotOwnClashes(req(rk), plan("orders"), cfg, Seq(repo("orders", handPlaced)))
            assert(clashes == Seq(CatalogAPIController.restoreNotOwnMessage("orders")), rk.label + ": " + clashes)
            assert(clashes.head.contains("'orders'") && clashes.head.contains("administrator"), clashes.head)
            assert(!clashes.head.contains(handPlaced) && !clashes.head.contains("lib/"), clashes.head)
            val rows = refusedRows("orders")
            assert(rows.size == 1, rk.label + ": " + rows)
            val md = rows.head.metadata.getOrElse(fail("no metadata"))
            assert(md.get("rule").getAsString == "S4" && md.get("site").getAsString == "catalog-restore" && md.get("enforced").getAsBoolean, md.toString)
            assert(rows.head.outcome == "denied", rows.head.toString)
        }
    }

    test("restore S4: every committed tap is judged, in name order, and one row is written for each refused tap") {
        val taps = Seq(repo("b", Prefix + "lib/b.py"), repo("a", "elsewhere/a.py"), repo("c", Prefix + "c.py"))
        val clashes = CatalogAPIController.restoreNotOwnClashes(req(salesKey), plan("c", "b", "a"), cfg, taps)
        assert(clashes == Seq(CatalogAPIController.restoreNotOwnMessage("a"), CatalogAPIController.restoreNotOwnMessage("b")), clashes.toString)
        assert(refusedRows("a").size == 1 && refusedRows("b").size == 1 && refusedRows("c").isEmpty)
    }

    test("restore S4: unchanged for callers not held to their own tap's script file") {
        val callers =
            Seq(req(admin), req(editor), req(fullKey), req(pipelineOnly), ScopeDenialFixtures.request(null, "POST", "/api/v1/catalog/Sales/restore")._1)
        for (r <- callers)
            assert(CatalogAPIController.restoreNotOwnClashes(r, plan("orders"), cfg, Seq(repo("orders", handPlaced))).isEmpty)
        assert(CatalogAPIController.restoreNotOwnClashes(null, plan("orders"), cfg, Seq(repo("orders", handPlaced))).isEmpty)
        assert(refusedRows("orders").isEmpty, audit.toString)
    }

    test("restore S4: unchanged under log-only capability mode for a request the route gate passed only as a would-deny") {
        val (r, attrs) = ScopeDenialFixtures.request(salesKey, "POST", "/api/v1/catalog/Sales/restore")
        attrs.put(ai.datris.config.CapabilityInterceptor.LogOnlyWouldDenyAttr, java.lang.Boolean.TRUE)
        assert(CatalogAPIController.restoreNotOwnClashes(r, plan("orders"), cfg, Seq(repo("orders", handPlaced))).isEmpty)
        assert(refusedRows("orders").isEmpty)
    }

    test("restore S4: unchanged when the live path is the tap's own file, under the current or a recorded earlier prefix") {
        val withEarlier = cfg.copy(earlierPathPrefixes = new java.util.ArrayList[String](java.util.Arrays.asList("old-taps/")))
        for (rk <- held) {
            assert(CatalogAPIController.restoreNotOwnClashes(req(rk), plan("orders"), cfg, Seq(repo("orders", Prefix + "orders.py"))).isEmpty, rk.label)
            assert(CatalogAPIController.restoreNotOwnClashes(req(rk), plan("orders"), withEarlier, Seq(repo("orders", "old-taps/orders.py"))).isEmpty, rk.label)
        }
        assert(refusedRows("orders").isEmpty)
    }

    test("restore S4: a tap with no live repository reference, or one the restore does not commit, is not judged") {
        for (rk <- held) {
            // new here, on built-in storage, or a repository tap with no stored path
            for (live <- Seq(Nil, Seq(builtIn("orders")), Seq(repo("orders", ""))))
                assert(CatalogAPIController.restoreNotOwnClashes(req(rk), plan("orders"), cfg, live).isEmpty, rk.label + " " + live)
            // reused or absent from the plan
            assert(CatalogAPIController.restoreNotOwnClashes(req(rk), plan(), cfg, Seq(repo("orders", handPlaced))).isEmpty, rk.label)
        }
        assert(refusedRows("orders").isEmpty)
    }

    // ================================================================ cascade delete listing

    test("cascade delete: the tap listing is read once for every tap delete, and taps deleted so far no longer count") {
        val reads = new AtomicInteger(0)
        val taps = Seq(builtIn("a"), builtIn("b"), builtIn("c"))
        val (listing, markDeleted) = CatalogAPIController.cascadeListing(() => { reads.incrementAndGet(); taps })
        assert(reads.get == 0, "nothing is read before a delete needs it")
        assert(listing().map(_.name) == Seq("a", "b", "c"))
        markDeleted("a")
        assert(listing().map(_.name) == Seq("b", "c"))
        markDeleted("c")
        assert(listing().map(_.name) == Seq("b"))
        assert(reads.get == 1, reads.get.toString)
    }

    test("cascade delete: a listing that cannot be read is read once and stays unreadable for every later delete") {
        val reads = new AtomicInteger(0)
        val (listing, markDeleted) = CatalogAPIController.cascadeListing(() => { reads.incrementAndGet(); throw new IllegalStateException("down") })
        for (n <- Seq("a", "b")) {
            assert(scala.util.Try(listing()).isFailure)
            markDeleted(n)
        }
        assert(reads.get == 1, reads.get.toString)
    }

    test("cascade delete: an orphaned built-in script stays while a not yet deleted tap of the cascade still uses it") {
        import ai.datris.util.{ScriptRef, TapScriptOwnership}
        val key = "tap-scripts/draft_0a1b2c3d.py"
        val taps = Seq(builtIn("a").copy(scriptPath = key), builtIn("b").copy(scriptPath = key))
        val (listing, markDeleted) = CatalogAPIController.cascadeListing(() => taps)
        val lookups = TapScriptOwnership.Lookups(listing, () => None)
        assert(TapScriptOwnership.deletePlan(ScriptRef("a", "minio", scriptPath = key), lookups) == TapScriptOwnership.Keep)
        markDeleted("a")
        assert(TapScriptOwnership.deletePlan(ScriptRef("b", "minio", scriptPath = key), lookups) == TapScriptOwnership.Delete)
    }
}
