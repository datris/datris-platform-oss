package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{CodeRepoConfig, TapConfig, TenantContext}
import org.scalatest.funsuite.AnyFunSuite

import java.io.{BufferedReader, ByteArrayInputStream, InputStream}
import java.net.URI
import java.nio.file.Files
import scala.collection.mutable

class TapCodeStoreSpec extends AnyFunSuite {

    private def tap(storage: String = null): TapConfig =
        TapConfig(name = "orders", description = "d", targetPipeline = "p", scriptStorage = storage)

    // --- backend resolution -------------------------------------------------

    test("forTap resolves minio for legacy taps with no storage field") {
        assert(TapCodeStore.forTap(tap(null)) == MinioCodeStore)
        assert(TapCodeStore.forTap(tap("minio")) == MinioCodeStore)
        assert(TapCodeStore.forTap(null) == MinioCodeStore)
    }

    test("forTap resolves github when the tap says so") {
        assert(TapCodeStore.forTap(tap("github")) == GithubCodeStore)
    }

    // --- repo path building -------------------------------------------------

    test("scriptRepoPath joins prefix and tap name") {
        assert(GithubCodeStore.scriptRepoPath("orders", CodeRepoConfig(pathPrefix = "taps/")) == "taps/orders.py")
    }

    test("scriptRepoPath adds missing slash and tolerates empty/null prefix") {
        assert(GithubCodeStore.scriptRepoPath("orders", CodeRepoConfig(pathPrefix = "taps")) == "taps/orders.py")
        assert(GithubCodeStore.scriptRepoPath("orders", CodeRepoConfig(pathPrefix = "")) == "orders.py")
        assert(GithubCodeStore.scriptRepoPath("orders", CodeRepoConfig(pathPrefix = null)) == "orders.py")
    }

    // --- commit messages ----------------------------------------------------

    test("commitMessage renders the default template") {
        val msg = GithubCodeStore.commitMessage(CodeRepoConfig(), "orders", "update", "todd")
        assert(msg == "tap(orders): update via Datris")
    }

    test("commitMessage substitutes user token and falls back to datris") {
        val cfg = CodeRepoConfig(commitMessageTemplate = "{action} {name} by {user}")
        assert(GithubCodeStore.commitMessage(cfg, "orders", "create", "todd") == "create orders by todd")
        assert(GithubCodeStore.commitMessage(cfg, "orders", "create", null) == "create orders by datris")
        assert(GithubCodeStore.commitMessage(cfg, "orders", "create", "") == "create orders by datris")
    }

    test("commitMessage falls back to default template when unset") {
        val cfg = CodeRepoConfig(commitMessageTemplate = null)
        assert(GithubCodeStore.commitMessage(cfg, "orders", "delete", null) == "tap(orders): delete via Datris")
    }

    // --- commit identity parsing -------------------------------------------

    test("commitIdentity parses Name <email>") {
        val identity = GithubClient.commitIdentity(CodeRepoConfig(commitAuthor = "Datris Bot <bot@datris.ai>"))
        assert(identity.isDefined)
        assert(identity.get.get("name").getAsString == "Datris Bot")
        assert(identity.get.get("email").getAsString == "bot@datris.ai")
    }

    test("commitIdentity is None for blank or malformed authors") {
        assert(GithubClient.commitIdentity(CodeRepoConfig(commitAuthor = null)).isEmpty)
        assert(GithubClient.commitIdentity(CodeRepoConfig(commitAuthor = "  ")).isEmpty)
        assert(GithubClient.commitIdentity(CodeRepoConfig(commitAuthor = "no-email-here")).isEmpty)
    }

    // --- script cache -------------------------------------------------------

    test("cache round-trips by repo, sha, and path; misses on other shas") {
        val dir = Files.createTempDirectory("tap-cache-spec")
        System.setProperty("datris.tap.cache.dir", dir.toString)
        try {
            assert(TapScriptCache.get("o/r", "abc123", "taps/x.py").isEmpty)
            TapScriptCache.put("o/r", "abc123", "taps/x.py", "print('hi')")
            assert(TapScriptCache.get("o/r", "abc123", "taps/x.py").contains("print('hi')"))
            assert(TapScriptCache.get("o/r", "def456", "taps/x.py").isEmpty)
            assert(TapScriptCache.get("other/repo", "abc123", "taps/x.py").isEmpty)
        } finally {
            System.clearProperty("datris.tap.cache.dir")
        }
    }

    // --- pipeline scripts (story: codegen-script-pinning) -------------------
    //
    // Pinned seam (plans/stories/codegen-script-pinning.md, Files):
    // {{{
    // case class ScriptRef(name: String, storage: String, scriptPath: String = null,
    //                      scriptRepoPath: String = null, scriptCommitSha: String = null)
    // trait CodeStore {
    //     def storage: String
    //     def readScript(ref: ScriptRef): Option[String]
    //     def storeScript(name: String, script: String, prior: ScriptRef, actor: String): StoredScript
    //     def deleteScript(ref: ScriptRef): Unit
    //     def scriptExists(ref: ScriptRef): Boolean
    // }
    // object MinioCodeStore {
    //     /** Built-in store for one pipeline's CodeGen scripts, keys under `pipeline-scripts/<pipeline>/`. */
    //     def forPipeline(pipeline: String, objects: ObjectStoreUtility = ObjectStoreUtil): CodeStore
    // }
    // }}}
    // The object store is injected so the spec runs without MinIO.

    /** In-memory object store keyed by (bucket, key). */
    private class MemObjects extends ObjectStoreUtility {
        val objects = mutable.LinkedHashMap[(String, String), String]()
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
        override def writeBucketObjectFromStream(b: String, k: String, s: ByteArrayInputStream, l: Long): Unit =
            throw new UnsupportedOperationException
        override def deleteFolder(b: String, k: String): Unit = objects.keys.filter(_._2.startsWith(k)).toList.foreach(objects.remove)
        override def deleteBucketObject(b: String, k: String): Unit = objects.remove((b, k))
        override def listObjects(b: String, k: String): List[String] = objects.keys.filter(x => x._1 == b && x._2.startsWith(k)).map(_._2).toList
        override def listSummaries(b: String, k: String): List[StoredObjectSummary] = listObjects(b, k).map(StoredObjectSummary(_, 1L))
        override def keyExists(b: String, k: String): Boolean = objects.contains((b, k))
    }

    private object SpecEnv extends AiSampleValuesMarkers

    test("pipeline scripts are written under pipeline-scripts/<pipeline>/") {
        TenantContext.set(SpecEnv.testEnv)
        try {
            val objects = new MemObjects
            val store: CodeStore = MinioCodeStore.forPipeline("orders", objects)
            assert(store.storage == "minio")

            val stored = store.storeScript("dataQuality", "print('dq')", null, "todd")
            assert(stored.storage == "minio")
            assert(stored.scriptPath != null && stored.scriptPath.startsWith("pipeline-scripts/orders/"), stored.scriptPath)
            assert(stored.scriptPath.endsWith(".py"), stored.scriptPath)
            assert(objects.objects.keys.map(_._2).toList == List(stored.scriptPath), "exactly one object, under the pipeline prefix")
            assert(!stored.scriptPath.startsWith("tap-scripts/"))

            val ref = ScriptRef("dataQuality", "minio", scriptPath = stored.scriptPath)
            assert(store.readScript(ref).contains("print('dq')"))
            assert(store.scriptExists(ref))

            // Another pipeline's scripts never share the prefix.
            val other = MinioCodeStore.forPipeline("orders_v2", objects).storeScript("transformation", "print('tx')", null, "todd")
            assert(other.scriptPath.startsWith("pipeline-scripts/orders_v2/"), other.scriptPath)

            store.deleteScript(ref)
            assert(store.readScript(ref).isEmpty)
            assert(!store.scriptExists(ref))
        } finally TenantContext.clear()
    }

    // --- pipeline scripts in the code repository (story: codegen-script-git-storage)
    //
    // Pinned seam (plans/stories/codegen-script-git-storage.md, Files):
    // {{{
    // // ai.datris.util
    // sealed trait ScriptFamily
    // object ScriptFamily {
    //     case class Tap(name: String) extends ScriptFamily
    //     /** kind: "dataQuality" | "transformation" (the CodeGen kind). */
    //     case class Pipeline(pipeline: String, kind: String) extends ScriptFamily
    // }
    // object GithubCodeStore {
    //     def scriptRepoPath(tapName: String, cfg: CodeRepoConfig): String          // unchanged
    //     def scriptRepoPath(family: ScriptFamily, cfg: CodeRepoConfig): String
    //     def commitMessage(cfg: CodeRepoConfig, tapName: String, action: String, actor: String): String   // unchanged
    //     def commitMessage(cfg: CodeRepoConfig, family: ScriptFamily, action: String, actor: String): String
    //     /** Repository-backed store for one pipeline's CodeGen scripts. */
    //     def forPipeline(pipeline: String): CodeStore
    // }
    // }}}
    // Pipeline paths: `<prefix>pipelines/<pipeline>/data-quality.py` and
    // `<prefix>pipelines/<pipeline>/transformation.py`. `{name}` in a commit
    // message is `<pipeline>/<kind>`, kind as above. A template that is null,
    // empty or the stored tap default literal renders pipeline scripts with
    // `pipeline({name}): {action} via Datris`.

    private val TapDefaultTemplate = "tap({name}): {action} via Datris"

    test("tap repo path and commit message are unchanged") {
        val cfgs = Seq(
            CodeRepoConfig(),
            CodeRepoConfig(pathPrefix = "taps"),
            CodeRepoConfig(pathPrefix = ""),
            CodeRepoConfig(pathPrefix = null),
            CodeRepoConfig(pathPrefix = "infra/datris/", commitMessageTemplate = "{action} {name} by {user}"),
            CodeRepoConfig(commitMessageTemplate = ""),
            CodeRepoConfig(commitMessageTemplate = null)
        )
        val expectedPaths = List("taps/orders.py", "taps/orders.py", "orders.py", "orders.py", "infra/datris/orders.py", "taps/orders.py", "taps/orders.py")
        val expectedMessages = List(
            "tap(orders): update via Datris",
            "tap(orders): update via Datris",
            "tap(orders): update via Datris",
            "tap(orders): update via Datris",
            "update orders by todd",
            "tap(orders): update via Datris",
            "tap(orders): update via Datris"
        )
        cfgs.zip(expectedPaths).zip(expectedMessages).foreach { case ((cfg, path), msg) =>
            assert(GithubCodeStore.scriptRepoPath("orders", cfg) == path)
            assert(GithubCodeStore.scriptRepoPath(ScriptFamily.Tap("orders"), cfg) == path, s"tap family path for $cfg")
            assert(GithubCodeStore.commitMessage(cfg, "orders", "update", "todd") == msg)
            assert(GithubCodeStore.commitMessage(cfg, ScriptFamily.Tap("orders"), "update", "todd") == msg, s"tap family message for $cfg")
        }
        // The stored default literal still renders as the tap default for taps.
        val stored = CodeRepoConfig(commitMessageTemplate = TapDefaultTemplate)
        assert(GithubCodeStore.commitMessage(stored, ScriptFamily.Tap("orders"), "delete", null) == "tap(orders): delete via Datris")
        assert(GithubCodeStore.commitMessage(stored, "orders", "create", null) == "tap(orders): create via Datris")
    }

    test("a pipeline script path is <prefix>pipelines/<pipeline>/<kind>.py") {
        val cfg = CodeRepoConfig()
        assert(GithubCodeStore.scriptRepoPath(ScriptFamily.Pipeline("orders", "dataQuality"), cfg) == "taps/pipelines/orders/data-quality.py")
        assert(GithubCodeStore.scriptRepoPath(ScriptFamily.Pipeline("orders", "transformation"), cfg) == "taps/pipelines/orders/transformation.py")
        assert(
            GithubCodeStore.scriptRepoPath(ScriptFamily.Pipeline("orders", "transformation"), CodeRepoConfig(pathPrefix = "infra/datris")) ==
                "infra/datris/pipelines/orders/transformation.py"
        )
        assert(GithubCodeStore.scriptRepoPath(
            ScriptFamily.Pipeline("orders", "dataQuality"),
            CodeRepoConfig(pathPrefix = "")
        ) == "pipelines/orders/data-quality.py")
        assert(GithubCodeStore.scriptRepoPath(
            ScriptFamily.Pipeline("orders", "dataQuality"),
            CodeRepoConfig(pathPrefix = null)
        ) == "pipelines/orders/data-quality.py")
        // A pipeline's path never collides with a tap of the same name.
        assert(
            GithubCodeStore.scriptRepoPath(ScriptFamily.Pipeline("orders", "transformation"), cfg) !=
                GithubCodeStore.scriptRepoPath(ScriptFamily.Tap("orders"), cfg)
        )
        // The repository-backed store for a pipeline stamps "github" (no network call).
        assert(GithubCodeStore.forPipeline("orders").storage == "github")
    }

    test("with the stored tap default template the pipeline commit message is pipeline(<pipeline>/<kind>): <action> via Datris") {
        Seq(
            CodeRepoConfig(),
            CodeRepoConfig(commitMessageTemplate = TapDefaultTemplate),
            CodeRepoConfig(commitMessageTemplate = ""),
            CodeRepoConfig(commitMessageTemplate = null)
        )
            .foreach { cfg =>
                assert(
                    GithubCodeStore.commitMessage(cfg, ScriptFamily.Pipeline("orders", "dataQuality"), "create", "todd") ==
                        "pipeline(orders/dataQuality): create via Datris",
                    s"$cfg"
                )
                assert(
                    GithubCodeStore.commitMessage(cfg, ScriptFamily.Pipeline("orders", "transformation"), "update", null) ==
                        "pipeline(orders/transformation): update via Datris"
                )
                assert(
                    GithubCodeStore.commitMessage(cfg, ScriptFamily.Pipeline("orders", "transformation"), "delete", null) ==
                        "pipeline(orders/transformation): delete via Datris"
                )
            }
    }

    test("a custom template is applied with name <pipeline>/<kind>") {
        val cfg = CodeRepoConfig(commitMessageTemplate = "{action} {name} by {user}")
        assert(GithubCodeStore.commitMessage(
            cfg,
            ScriptFamily.Pipeline("orders", "transformation"),
            "update",
            "ci-key"
        ) == "update orders/transformation by ci-key")
        // Generated during a run: no saving key.
        assert(GithubCodeStore.commitMessage(cfg, ScriptFamily.Pipeline("orders", "dataQuality"), "create", null) == "create orders/dataQuality by datris")
        assert(GithubCodeStore.commitMessage(cfg, ScriptFamily.Pipeline("orders", "dataQuality"), "create", "") == "create orders/dataQuality by datris")
        // A custom template written for taps is applied as written.
        val tapStyle = CodeRepoConfig(commitMessageTemplate = "tap({name}): {action} [skip ci]")
        assert(GithubCodeStore.commitMessage(
            tapStyle,
            ScriptFamily.Pipeline("orders", "dataQuality"),
            "delete",
            null
        ) == "tap(orders/dataQuality): delete [skip ci]")
    }
}
