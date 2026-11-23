package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{CodeRepoConfig, TapConfig}
import ai.datris.util.CatalogArchive
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
  *    file. A restore that hits no such file is unchanged.
  *  - Catalog cascade delete: one tap listing serves every tap delete of the
  *    cascade, read at most once (a failed read stays failed, so each delete
  *    keeps its file), and taps already deleted in the cascade no longer count
  *    as using a file.
  *
  * Names the implementation must provide:
  * {{{
  * object CatalogAPIController {
  *   private[api] def restoreTargetClashes(plan: CatalogArchive.RepoScriptPlan, cfg: CodeRepoConfig, liveTaps: Seq[TapConfig]): Seq[String]
  *   private[api] def cascadeListing(read: () => Seq[TapConfig]): (() => Seq[TapConfig], String => Unit)
  * }
  * }}}
  */
class CatalogScriptOwnershipSpec extends AnyFunSuite {

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
