package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.CodeRepoAPIController
import ai.datris.model.{CodeRepoConfig, TapConfig}
import com.google.gson.Gson
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.JavaConverters._

/** Story: A tap's script upload and delete act only on that tap's own script
  * file (plans/stories/tap-script-store-writes-and-deletes-in-scope.md).
  * The pure rules: what a tap's own script file is, the delete plan, the
  * upload target check, and the earlier path prefixes recorded on the
  * repository configuration (owner decision D1: option C).
  *
  * Names the implementation must provide:
  * {{{
  * // ai.datris.model
  * case class CodeRepoConfig(..., earlierPathPrefixes: java.util.List[String] = <empty list>, ...)
  *
  * // ai.datris.util
  * object TapScriptOwnership {
  *   /** The one warning a delete gives for a script file it leaves in place, whatever the cause. */
  *   val KeptWarning: String
  *   /** Store reads the rules need, each made at most once per call and only when needed. */
  *   case class Lookups(allTaps: () => Seq[TapConfig], repoConfig: () => Option[CodeRepoConfig])
  *   /** `key` is exactly tap-scripts/ + tapName + "_" + 8 hexadecimal characters + ".py". */
  *   def ownBuiltIn(tapName: String, key: String): Boolean
  *   /** `path`, normalised (TapReferenceRules.normalizedRef), is <p><tapName>.py for one of `prefixes`,
  *     * and is not in the pipelines folder of any of them. */
  *   def ownRepo(tapName: String, path: String, prefixes: Seq[String]): Boolean
  *   /** The configured pathPrefix, then every recorded earlier prefix (a null list is none). */
  *   def prefixesOf(cfg: CodeRepoConfig): Seq[String]
  *   sealed trait DeletePlan
  *   case object Delete extends DeletePlan
  *   case object Keep extends DeletePlan
  *   /** Delete only when `ref` (ref.name is the tap) is that tap's own file and no other stored tap's
  *     * current reference is the same file once normalised. The tap listing is read only when the
  *     * own-file test passes; a listing or configuration read that fails is Keep. */
  *   def deletePlan(ref: ScriptRef, lookups: Lookups): DeletePlan
  *   /** True when `targetPath` is, normalised, the current repository script of a stored tap of another name. */
  *   def uploadTargetTaken(tapName: String, targetPath: String, allTaps: Seq[TapConfig]): Boolean
  * }
  *
  * // ai.datris.api
  * object CodeRepoAPIController {
  *   /** What `PUT /code-repo` stores for `incoming` over the `stored` configuration: the earlier
  *     * prefixes are the body's list, or the stored list when the body has none; a save that changes
  *     * the path prefix adds the stored prefix to them. Every other field is the body's. */
  *   private[datris] def withEarlierPrefixes(stored: Option[CodeRepoConfig], incoming: CodeRepoConfig): CodeRepoConfig
  * }
  * }}}
  */
class TapScriptOwnershipSpec extends AnyFunSuite {

    import TapScriptOwnership.{Delete, Keep, Lookups}

    private val Prefix = "datris-taps/"

    private def cfg(prefix: String = Prefix, earlier: Seq[String] = Nil): CodeRepoConfig =
        CodeRepoConfig(
            repo = "o/r",
            authSecretName = "s",
            enabled = true,
            pathPrefix = prefix,
            earlierPathPrefixes = new java.util.ArrayList[String](earlier.asJava)
        )

    private def builtIn(name: String, key: String): TapConfig =
        TapConfig(name = name, description = "d", targetPipeline = "p", scriptPath = key)

    private def repo(name: String, path: String): TapConfig =
        TapConfig(name = name, description = "d", targetPipeline = "p", scriptStorage = "github", scriptRepoPath = path, scriptCommitSha = "abc1234")

    private class Counted(taps: => Seq[TapConfig], config: => Option[CodeRepoConfig] = Some(cfg())) {
        val tapReads = new AtomicInteger(0)
        val configReads = new AtomicInteger(0)
        val lookups: Lookups = Lookups(
            () => { tapReads.incrementAndGet(); taps },
            () => { configReads.incrementAndGet(); config }
        )
    }

    // ================================================================ own file: built-in

    test("a built-in key is the tap's own file only in the exact form the built-in store writes for that tap") {
        assert(TapScriptOwnership.ownBuiltIn("orders", "tap-scripts/orders_1a2b3c4d.py"))
        assert(TapScriptOwnership.ownBuiltIn("orders", "tap-scripts/orders_00000000.py"))
        assert(TapScriptOwnership.ownBuiltIn("a/b", "tap-scripts/a/b_deadbeef.py"))
        assert(TapScriptOwnership.ownBuiltIn("sales.orders", "tap-scripts/sales.orders_0f0f0f0f.py"))
        val notOwn = Seq(
            "tap-scripts/orders_1a2b3c4.py", // seven characters
            "tap-scripts/orders_1a2b3c4d5.py", // nine characters
            "tap-scripts/orders_1a2b3c4g.py", // not hexadecimal
            "tap-scripts/orders_1.py",
            "tap-scripts/orders.py",
            "tap-scripts/orders_1a2b3c4d.py.bak",
            "tap-scripts/orders_x_1a2b3c4d.py",
            "tap-scripts/other_1a2b3c4d.py",
            "tap-scripts//orders_1a2b3c4d.py",
            "/tap-scripts/orders_1a2b3c4d.py",
            "./tap-scripts/orders_1a2b3c4d.py",
            "tap-scripts/x/../orders_1a2b3c4d.py",
            "TAP-SCRIPTS/orders_1a2b3c4d.py",
            "pipeline-scripts/orders/orders_1a2b3c4d.py",
            "uploads/orders_1a2b3c4d.py",
            "",
            null
        )
        for (k <- notOwn) assert(!TapScriptOwnership.ownBuiltIn("orders", k), String.valueOf(k))
        assert(!TapScriptOwnership.ownBuiltIn(null, "tap-scripts/orders_1a2b3c4d.py"))
        assert(!TapScriptOwnership.ownBuiltIn("", "tap-scripts/_1a2b3c4d.py"))
    }

    test("every key the built-in store writes for a tap is that tap's own file, and no other tap's") {
        TapScriptGeneratorEnv.withEnv {
            val objects = new InMemoryObjects
            for (name <- Seq("orders", "a/b", "sales.orders", "pipelines", "x/..")) {
                val key = TapScriptGenerator.storeScript(name, "print(1)", null, objects = objects)
                assert(TapScriptOwnership.ownBuiltIn(name, key), name + " " + key)
                assert(!TapScriptOwnership.ownBuiltIn(name + "x", key), name + " " + key)
            }
        }
    }

    // ================================================================ own file: code repository

    test("a repository path is the tap's own file when it is <prefix><tap>.py, however the path is spelled") {
        val prefixes = Seq(Prefix)
        assert(TapScriptOwnership.ownRepo("orders", "datris-taps/orders.py", prefixes))
        assert(TapScriptOwnership.ownRepo("orders", "datris-taps//orders.py", prefixes))
        assert(TapScriptOwnership.ownRepo("orders", "/datris-taps/orders.py", prefixes))
        assert(TapScriptOwnership.ownRepo("orders", "./datris-taps/orders.py", prefixes))
        assert(TapScriptOwnership.ownRepo("a/b", "datris-taps/a/b.py", prefixes))
        assert(TapScriptOwnership.ownRepo("orders", "orders.py", Seq("")))
        assert(TapScriptOwnership.ownRepo("orders", "datris-taps/orders.py", Seq("datris-taps")), "a prefix without its trailing separator")
        // the names that only start with the pipelines word keep their own file
        for (n <- Seq("pipelines", "pipelines-x", "pipelines_x"))
            assert(TapScriptOwnership.ownRepo(n, Prefix + n + ".py", prefixes), n)
    }

    test("any other repository file is not the tap's own file: another tap's, a pipeline script, another folder, a near spelling") {
        val prefixes = Seq(Prefix)
        val notOwn = Seq(
            "datris-taps/other.py",
            "datris-taps/orders.py.bak",
            "datris-taps/Orders.py",
            "datris-taps/sub/orders.py",
            "datris-taps/orders",
            "datris-taps/pipelines/orders/transformation.py",
            "datris-taps/pipelines/orders/data-quality.py",
            "datris-taps/pipelines",
            "old-taps/orders.py",
            "orders.py",
            "README.md",
            "src/app/orders.py",
            "",
            null
        )
        for (p <- notOwn) assert(!TapScriptOwnership.ownRepo("orders", p, prefixes), String.valueOf(p))
    }

    test("a tap whose name reaches into the pipelines folder never owns a pipeline script") {
        for (prefix <- Seq(Prefix, "")) {
            val pre = TapScriptOwnershipNames.normalizePrefix(prefix)
            assert(!TapScriptOwnership.ownRepo("pipelines/orders/transformation", pre + "pipelines/orders/transformation.py", Seq(prefix)), prefix)
            assert(!TapScriptOwnership.ownRepo("pipelines/orders/data-quality", pre + "pipelines/orders/data-quality.py", Seq(prefix)), prefix)
        }
    }

    test("a recorded earlier prefix is treated exactly as the current one; an unrecorded one is not") {
        val c = cfg(Prefix, Seq("old-taps/", "legacy"))
        assert(TapScriptOwnership.prefixesOf(c).toList == List(Prefix, "old-taps/", "legacy"))
        for (p <- Seq("datris-taps/orders.py", "old-taps/orders.py", "legacy/orders.py", "old-taps//orders.py"))
            assert(TapScriptOwnership.ownRepo("orders", p, TapScriptOwnership.prefixesOf(c)), p)
        assert(!TapScriptOwnership.ownRepo("orders", "older/orders.py", TapScriptOwnership.prefixesOf(c)))
        assert(!TapScriptOwnership.ownRepo("orders", "old-taps/pipelines/orders/transformation.py", TapScriptOwnership.prefixesOf(c)))
        assert(!TapScriptOwnership.ownRepo("orders", "old-taps/orders.py", TapScriptOwnership.prefixesOf(cfg(Prefix))))
    }

    test("a repository configuration with no recorded earlier prefixes reads as the current prefix only") {
        assert(CodeRepoConfig().earlierPathPrefixes != null && CodeRepoConfig().earlierPathPrefixes.isEmpty, "the default is an empty list")
        assert(TapScriptOwnership.prefixesOf(CodeRepoConfig(pathPrefix = Prefix)).toList == List(Prefix))
        assert(TapScriptOwnership.prefixesOf(CodeRepoConfig(pathPrefix = Prefix, earlierPathPrefixes = null)).toList == List(Prefix))
        // a configuration stored before this release has no such field
        val stored = new Gson().fromJson("{\"name\":\"default\",\"repo\":\"o/r\",\"pathPrefix\":\"datris-taps/\",\"enabled\":true}", classOf[CodeRepoConfig])
        assert(TapScriptOwnership.prefixesOf(stored).toList == List(Prefix))
    }

    // ================================================================ the delete plan

    private val ordersKey = "tap-scripts/orders_1a2b3c4d.py"
    private val otherTap = builtIn("other", "tap-scripts/other_5e6f7a8b.py")
    private val repoOther = repo("repo-other", Prefix + "repo-other.py")

    test("a delete removes the tap's own file when no other stored tap uses it (both stores)") {
        val taps = Seq(builtIn("orders", ordersKey), otherTap, repo("repo-orders", Prefix + "repo-orders.py"), repoOther)
        assert(TapScriptOwnership.deletePlan(ScriptRef("orders", "minio", scriptPath = ordersKey), new Counted(taps).lookups) == Delete)
        assert(
            TapScriptOwnership.deletePlan(ScriptRef("repo-orders", "github", scriptRepoPath = Prefix + "repo-orders.py"), new Counted(taps).lookups) == Delete
        )
    }

    test("a delete keeps any file that is not the tap's own: another tap's script, another tap's older script, any other file (both stores)") {
        val taps = Seq(builtIn("orders", "tap-scripts/orders_1a2b3c4d.py"), otherTap, repoOther)
        val builtInRefs = Seq(
            otherTap.scriptPath, // another tap's current script
            "tap-scripts/other_99999999.py", // an older script of another tap
            "tap-scripts/orders_1.py",
            "uploads/sales/orders.csv",
            "pipeline-scripts/sales-orders/transformation_1a2b3c4d.py",
            "tap-scripts/../uploads/x.py"
        )
        for (p <- builtInRefs)
            assert(TapScriptOwnership.deletePlan(ScriptRef("hand-ref", "minio", scriptPath = p), new Counted(taps).lookups) == Keep, p)
        val repoRefs = Seq(
            repoOther.scriptRepoPath,
            Prefix + "pipelines/sales-orders/transformation.py",
            Prefix + "pipelines",
            "README.md",
            "src/app/main.py",
            "old-taps/hand-ref.py"
        )
        for (p <- repoRefs)
            assert(TapScriptOwnership.deletePlan(ScriptRef("hand-ref", "github", scriptRepoPath = p), new Counted(taps).lookups) == Keep, p)
    }

    test("a delete keeps the tap's own file while another stored tap's current reference is that file, in any spelling") {
        val sharedBuiltIn = Seq(builtIn("orders", ordersKey), builtIn("copy", ordersKey))
        assert(TapScriptOwnership.deletePlan(ScriptRef("orders", "minio", scriptPath = ordersKey), new Counted(sharedBuiltIn).lookups) == Keep)
        val respelled = Seq(builtIn("orders", ordersKey), builtIn("copy", "tap-scripts//orders_1a2b3c4d.py"))
        assert(TapScriptOwnership.deletePlan(ScriptRef("orders", "minio", scriptPath = ordersKey), new Counted(respelled).lookups) == Keep)
        val own = Prefix + "orders.py"
        for (other <- Seq(own, "datris-taps//orders.py", "/datris-taps/orders.py")) {
            val shared = Seq(repo("orders", own), repo("copy", other))
            assert(TapScriptOwnership.deletePlan(ScriptRef("orders", "github", scriptRepoPath = own), new Counted(shared).lookups) == Keep, other)
        }
        // the tap itself in the listing is not "another tap"
        assert(TapScriptOwnership.deletePlan(ScriptRef("orders", "github", scriptRepoPath = own), new Counted(Seq(repo("orders", own))).lookups) == Delete)
    }

    test("a delete keeps the file when the tap listing or the repository configuration cannot be read") {
        val failing = Lookups(() => throw new IllegalStateException("mongo down"), () => Some(cfg()))
        assert(TapScriptOwnership.deletePlan(ScriptRef("orders", "minio", scriptPath = ordersKey), failing) == Keep)
        assert(TapScriptOwnership.deletePlan(ScriptRef("orders", "github", scriptRepoPath = Prefix + "orders.py"), failing) == Keep)
        val noConfig = Lookups(() => Nil, () => throw new IllegalStateException("mongo down"))
        assert(TapScriptOwnership.deletePlan(ScriptRef("orders", "github", scriptRepoPath = Prefix + "orders.py"), noConfig) == Keep)
        val disabled = Lookups(() => Nil, () => None)
        assert(TapScriptOwnership.deletePlan(ScriptRef("orders", "github", scriptRepoPath = Prefix + "orders.py"), disabled) == Keep)
    }

    test("the tap listing is read only when the file passed the own-file test, and at most once; built-in files read no repository settings") {
        val notOwn = new Counted(Seq(otherTap))
        TapScriptOwnership.deletePlan(ScriptRef("hand-ref", "minio", scriptPath = "uploads/x.csv"), notOwn.lookups)
        TapScriptOwnership.deletePlan(ScriptRef("hand-ref", "github", scriptRepoPath = "README.md"), notOwn.lookups)
        assert(notOwn.tapReads.get == 0, notOwn.tapReads.get.toString)
        val own = new Counted(Seq(otherTap))
        TapScriptOwnership.deletePlan(ScriptRef("orders", "minio", scriptPath = ordersKey), own.lookups)
        assert(own.tapReads.get == 1 && own.configReads.get == 0, own.tapReads.get + "/" + own.configReads.get)
        val repoOwn = new Counted(Seq(otherTap))
        TapScriptOwnership.deletePlan(ScriptRef("orders", "github", scriptRepoPath = Prefix + "orders.py"), repoOwn.lookups)
        assert(repoOwn.tapReads.get == 1 && repoOwn.configReads.get <= 1, repoOwn.tapReads.get + "/" + repoOwn.configReads.get)
    }

    test("a tap stored under a recorded earlier prefix deletes its own file; under an unrecorded one the file is kept") {
        val taps = Seq(repo("orders", "old-taps/orders.py"))
        val recorded = new Counted(taps, Some(cfg(Prefix, Seq("old-taps/"))))
        assert(TapScriptOwnership.deletePlan(ScriptRef("orders", "github", scriptRepoPath = "old-taps/orders.py"), recorded.lookups) == Delete)
        val unrecorded = new Counted(taps, Some(cfg(Prefix)))
        assert(TapScriptOwnership.deletePlan(ScriptRef("orders", "github", scriptRepoPath = "old-taps/orders.py"), unrecorded.lookups) == Keep)
    }

    test("the kept-file warning is one fixed text that names no file and no other tap") {
        assert(
            TapScriptOwnership.KeptWarning ==
                "The tap's script file was left in place because it is not a file this tap alone owns; remove it by hand if it is no longer wanted."
        )
    }

    // ================================================================ the upload target

    test("an upload target is taken when it is, in any spelling, the current repository script of a stored tap of another name") {
        val hand = repo("finance-hand", "datris-taps//sales-new.py")
        val taps = Seq(repo("repo-other", Prefix + "repo-other.py"), hand, builtIn("sales-new-builtin", "tap-scripts/sales-new_1a2b3c4d.py"))
        assert(TapScriptOwnership.uploadTargetTaken("sales-new", Prefix + "sales-new.py", taps))
        assert(TapScriptOwnership.uploadTargetTaken("x", Prefix + "repo-other.py", taps))
        // free paths: no stored tap, the tap's own stored reference, a built-in reference
        assert(!TapScriptOwnership.uploadTargetTaken("brand-new", Prefix + "brand-new.py", taps))
        assert(!TapScriptOwnership.uploadTargetTaken("repo-other", Prefix + "repo-other.py", taps), "the tap of that name is not another tap")
        assert(!TapScriptOwnership.uploadTargetTaken("tap-scripts/sales-new_1a2b3c4d", "tap-scripts/sales-new_1a2b3c4d.py", taps))
        assert(!TapScriptOwnership.uploadTargetTaken("anything", Prefix + "anything.py", Nil))
    }

    // ================================================================ built-in scripts written under another name
    //
    // A tap created in the wizard and renamed before its first save, or whose
    // script was generated before it had its name, references a built-in
    // script whose key carries another name. Such a key, in the store's form
    // tap-scripts/<N>_<8 hex>.py, may be deleted for the tap that references
    // it when no stored tap is named N and no other stored tap's current
    // script is that key. Any other key, and every repository file, is judged
    // as before.
    //
    //   def builtInKeyName(key: String): Option[String]   // N for a key of the store's form, else None

    private val draftKey = "tap-scripts/draft-name_0a1b2c3d.py"

    private def withWarns[T](f: => T): (T, List[String]) = {
        val rootLogger =
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[ch.qos.logback.classic.Logger]
        val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]()
        appender.start()
        rootLogger.addAppender(appender)
        try {
            val r = f
            (r, appender.list.asScala.toList.filter(_.getLevel == ch.qos.logback.classic.Level.WARN).map(_.getFormattedMessage))
        } finally rootLogger.detachAppender(appender)
    }

    test("the name in a built-in key is read only from the exact form the built-in store writes") {
        assert(TapScriptOwnership.builtInKeyName("tap-scripts/orders_1a2b3c4d.py").contains("orders"))
        assert(TapScriptOwnership.builtInKeyName("tap-scripts/orders_x_1a2b3c4d.py").contains("orders_x"))
        assert(TapScriptOwnership.builtInKeyName("tap-scripts/a/b_deadbeef.py").contains("a/b"))
        val none = Seq(
            "tap-scripts/orders_1a2b3c4.py",
            "tap-scripts/orders_1a2b3c4g.py",
            "tap-scripts/orders.py",
            "tap-scripts/_1a2b3c4d.py",
            "tap-scripts//orders_1a2b3c4d.py",
            "tap-scripts/x/../orders_1a2b3c4d.py",
            "tap-scripts/orders_1a2b3c4d.py.bak",
            "uploads/orders_1a2b3c4d.py",
            "pipeline-scripts/orders/orders_1a2b3c4d.py",
            "",
            null
        )
        for (k <- none) assert(TapScriptOwnership.builtInKeyName(k).isEmpty, String.valueOf(k))
        // it agrees with the own-file test
        TapScriptGeneratorEnv.withEnv {
            val objects = new InMemoryObjects
            for (name <- Seq("orders", "a/b", "sales.orders", "x_y")) {
                val key = TapScriptGenerator.storeScript(name, "print(1)", null, objects = objects)
                assert(TapScriptOwnership.builtInKeyName(key).contains(name), key)
            }
        }
    }

    test("a delete removes a built-in script written under a name no stored tap has, when no other stored tap uses it") {
        val renamed = builtIn("final-name", draftKey)
        val counted = new Counted(Seq(renamed, otherTap))
        assert(TapScriptOwnership.deletePlan(ScriptRef("final-name", "minio", scriptPath = draftKey), counted.lookups) == Delete)
        assert(counted.tapReads.get == 1 && counted.configReads.get == 0, counted.tapReads.get + "/" + counted.configReads.get)
    }

    test("a built-in script written under another name is kept while a stored tap has that name or another stored tap uses it") {
        val named = Seq(builtIn("final-name", draftKey), builtIn("draft-name", "tap-scripts/draft-name_99999999.py"))
        assert(TapScriptOwnership.deletePlan(ScriptRef("final-name", "minio", scriptPath = draftKey), new Counted(named).lookups) == Keep)
        val used = Seq(builtIn("final-name", draftKey), builtIn("copy", draftKey))
        assert(TapScriptOwnership.deletePlan(ScriptRef("final-name", "minio", scriptPath = draftKey), new Counted(used).lookups) == Keep)
        val respelled = Seq(builtIn("final-name", draftKey), builtIn("copy", "tap-scripts//draft-name_0a1b2c3d.py"))
        assert(TapScriptOwnership.deletePlan(ScriptRef("final-name", "minio", scriptPath = draftKey), new Counted(respelled).lookups) == Keep)
        // keys not in the store's form are still kept, and read no listing
        val notForm = new Counted(Seq(builtIn("final-name", "tap-scripts/draft-name.py")))
        for (k <- Seq("tap-scripts/draft-name.py", "tap-scripts/draft-name_1.py", "uploads/draft-name_0a1b2c3d.py"))
            assert(TapScriptOwnership.deletePlan(ScriptRef("final-name", "minio", scriptPath = k), notForm.lookups) == Keep, k)
        assert(notForm.tapReads.get == 0)
    }

    test("a repository file named after another tap is still kept: the repository rule is unchanged") {
        val taps = Seq(repo("final-name", Prefix + "draft-name.py"))
        assert(TapScriptOwnership.deletePlan(ScriptRef("final-name", "github", scriptRepoPath = Prefix + "draft-name.py"), new Counted(taps).lookups) == Keep)
    }

    test("a built-in script written under another name is kept, with one WARN line, when the tap listing cannot be read") {
        val failing = Lookups(() => throw new IllegalStateException("db password=s3cr3t unreachable"), () => Some(cfg()))
        val (plan, warns) = withWarns(TapScriptOwnership.deletePlan(ScriptRef("final-name", "minio", scriptPath = draftKey), failing))
        assert(plan == Keep)
        val mine = warns.filter(_.contains("final-name"))
        assert(mine.size == 1, warns.mkString("\n"))
        assert(!mine.head.contains("s3cr3t") && !mine.head.contains("draft-name"), mine.head)
    }

    test("the kept-on-unreadable-listing WARN line is written for an own file too, in either store, and never on a readable listing") {
        val failing = Lookups(() => throw new IllegalStateException("down"), () => Some(cfg()))
        val (_, builtInWarns) = withWarns(TapScriptOwnership.deletePlan(ScriptRef("orders", "minio", scriptPath = ordersKey), failing))
        assert(builtInWarns.count(_.contains("'orders'")) == 1, builtInWarns.mkString("\n"))
        val (_, repoWarns) = withWarns(TapScriptOwnership.deletePlan(ScriptRef("orders", "github", scriptRepoPath = Prefix + "orders.py"), failing))
        assert(repoWarns.count(_.contains("'orders'")) == 1, repoWarns.mkString("\n"))
        val (_, none) = withWarns(TapScriptOwnership.deletePlan(ScriptRef("orders", "minio", scriptPath = ordersKey), new Counted(Nil).lookups))
        assert(!none.exists(_.contains("'orders'")), none.mkString("\n"))
    }

    test("version cleanup deletes built-in scripts written under a name no stored tap has, and keeps the rest") {
        val pruned = Seq(
            "tap-scripts/final-name_11111111.py", // own, unused
            draftKey, // another name, no such tap, unused
            "tap-scripts/other_22222222.py", // another name, that tap exists
            "tap-scripts/shared-draft_33333333.py", // another name, no such tap, but another tap's current script
            "tap-scripts/final-name_44444444.py", // own, the tap's current script
            "tap-scripts/draft.py", // not in the store's form
            "uploads/x.py"
        )
        val taps = Seq(
            builtIn("final-name", "tap-scripts/final-name_44444444.py"),
            builtIn("other", "tap-scripts/other_55555555.py"),
            builtIn("copy", "tap-scripts/shared-draft_33333333.py")
        )
        val reads = new AtomicInteger(0)
        val out = TapScriptOwnership.deletableBuiltIn("final-name", pruned, () => { reads.incrementAndGet(); taps })
        assert(out.toSet == Set("tap-scripts/final-name_11111111.py", draftKey), out.toString)
        assert(reads.get == 1)
        // nothing in the store's form: no listing read
        val none = new AtomicInteger(0)
        assert(TapScriptOwnership.deletableBuiltIn("final-name", Seq("tap-scripts/draft.py", "uploads/x.py"), () => { none.incrementAndGet(); taps }).isEmpty)
        assert(none.get == 0)
    }

    test("version cleanup deletes nothing, with one WARN line, when the tap listing cannot be read") {
        val (out, warns) =
            withWarns(TapScriptOwnership.deletableBuiltIn(
                "final-name",
                Seq(draftKey, "tap-scripts/final-name_11111111.py"),
                () => throw new IllegalStateException("down")
            ))
        assert(out.isEmpty)
        assert(warns.count(_.contains("'final-name'")) == 1, warns.mkString("\n"))
    }

    // ================================================================ earlier prefixes on the repository configuration

    private def body(json: String): CodeRepoConfig = new Gson().fromJson(json, classOf[CodeRepoConfig])

    private def earlier(c: CodeRepoConfig): List[String] = Option(c.earlierPathPrefixes).map(_.asScala.toList).getOrElse(Nil)

    test("changing the path prefix records the earlier one") {
        val stored = cfg("taps/")
        val saved = CodeRepoAPIController.withEarlierPrefixes(Some(stored), body("{\"repo\":\"o/r\",\"pathPrefix\":\"datris-taps/\",\"enabled\":true}"))
        assert(saved.pathPrefix == "datris-taps/")
        assert(earlier(saved) == List("taps/"), earlier(saved).toString)
        // and a second change keeps the first
        val again = CodeRepoAPIController.withEarlierPrefixes(Some(saved), body("{\"repo\":\"o/r\",\"pathPrefix\":\"v3/\",\"enabled\":true}"))
        assert(earlier(again).toSet == Set("taps/", "datris-taps/"), earlier(again).toString)
    }

    test("a configuration saved without the list keeps the stored list; a save with the same prefix records nothing new") {
        val stored = cfg(Prefix, Seq("taps/"))
        for (incoming <- Seq(body("{\"repo\":\"o/r\",\"pathPrefix\":\"datris-taps/\",\"enabled\":true}"), stored.copy(earlierPathPrefixes = null))) {
            val saved = CodeRepoAPIController.withEarlierPrefixes(Some(stored), incoming)
            assert(earlier(saved) == List("taps/"), earlier(saved).toString)
        }
    }

    test("an administrator's list is stored as sent; with no stored configuration the list is empty, never null") {
        val stored = cfg(Prefix, Seq("taps/"))
        val sent = stored.copy(earlierPathPrefixes = new java.util.ArrayList[String](Seq("legacy/").asJava))
        assert(earlier(CodeRepoAPIController.withEarlierPrefixes(Some(stored), sent)) == List("legacy/"))
        val cleared = stored.copy(earlierPathPrefixes = new java.util.ArrayList[String]())
        assert(earlier(CodeRepoAPIController.withEarlierPrefixes(Some(stored), cleared)).isEmpty)
        val first = CodeRepoAPIController.withEarlierPrefixes(None, body("{\"repo\":\"o/r\",\"pathPrefix\":\"datris-taps/\"}"))
        assert(first.earlierPathPrefixes != null && first.earlierPathPrefixes.isEmpty)
        // every other field is the body's
        val incoming = cfg("v4/").copy(branch = "release", commitAuthor = "Bot <b@x.io>")
        val saved = CodeRepoAPIController.withEarlierPrefixes(Some(stored), incoming)
        assert(saved.copy(earlierPathPrefixes = null) == incoming.copy(earlierPathPrefixes = null))
        assert(earlier(saved).contains(Prefix), earlier(saved).toString)
    }
}

/** The repository-path prefix form the stores write. */
private object TapScriptOwnershipNames {
    def normalizePrefix(p: String): String = ai.datris.api.TapReferenceRules.normalizePrefix(p)
}

/** The tenant environment the built-in store needs for its bucket name. */
private object TapScriptGeneratorEnv extends AiSampleValuesMarkers {
    def withEnv[T](f: => T): T = {
        ai.datris.model.TenantContext.set(testEnv)
        try f
        finally ai.datris.model.TenantContext.clear()
    }
}

/** In-memory object store keyed by (bucket, key). */
private class InMemoryObjects extends ObjectStoreUtility {
    import java.io.{BufferedReader, ByteArrayInputStream, InputStream}
    import java.net.URI
    val objects: scala.collection.mutable.Map[(String, String), String] = scala.collection.mutable.LinkedHashMap[(String, String), String]()
    override def getBucket(url: String): String = new URI(url).getHost
    override def getKey(url: String): String = new URI(url).getPath.stripPrefix("/")
    override def getURI(path: String): URI = new URI(path)
    override def getObjectMetadata(b: String, k: String): StoredObjectMetadata = StoredObjectMetadata(0L, "text/plain")
    override def readBucketObject(b: String, k: String): Option[String] = objects.get((b, k))
    override def readBucketObjectFirstRow(b: String, k: String): Option[String] = objects.get((b, k)).map(_.linesIterator.next())
    override def getBufferedReader(b: String, k: String): BufferedReader = throw new UnsupportedOperationException
    override def getInputStream(b: String, k: String): InputStream = throw new UnsupportedOperationException
    override def copyBucketObject(sb: String, sk: String, db: String, dk: String): Unit = throw new UnsupportedOperationException
    override def writeBucketObject(b: String, k: String, content: String): Unit = objects((b, k)) = content
    override def writeBucketObjectFromStream(b: String, k: String, s: ByteArrayInputStream, l: Long): Unit = throw new UnsupportedOperationException
    override def deleteFolder(b: String, k: String): Unit = objects.keys.filter(_._2.startsWith(k)).toList.foreach(objects.remove)
    override def deleteBucketObject(b: String, k: String): Unit = objects.remove((b, k))
    override def listObjects(b: String, k: String): List[String] = objects.keys.filter(x => x._1 == b && x._2.startsWith(k)).map(_._2).toList
    override def listSummaries(b: String, k: String): List[StoredObjectSummary] = listObjects(b, k).map(StoredObjectSummary(_, 1L))
    override def keyExists(b: String, k: String): Boolean = objects.contains((b, k))
}
