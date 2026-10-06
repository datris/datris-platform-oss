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
}
