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
            assert(TapScriptCache.get("o/r", "abc1234", "taps/x.py").isEmpty)
            TapScriptCache.put("o/r", "abc1234", "taps/x.py", "print('hi')")
            assert(TapScriptCache.get("o/r", "abc1234", "taps/x.py").contains("print('hi')"))
            assert(TapScriptCache.get("o/r", "def4567", "taps/x.py").isEmpty)
            assert(TapScriptCache.get("other/repo", "abc1234", "taps/x.py").isEmpty)
        } finally {
            System.clearProperty("datris.tap.cache.dir")
        }
    }

    // --- script cache: only a commit id names a cache folder ----------------

    private val CacheProp = "datris.tap.cache.dir"

    private def withCacheDir[T](f: java.nio.file.Path => T): T = {
        val dir = Files.createTempDirectory("tap-cache-commit-id")
        val old = sys.props.get(CacheProp)
        sys.props(CacheProp) = dir.toString
        try f(dir)
        finally old match { case Some(v) => sys.props(CacheProp) = v; case None => sys.props.remove(CacheProp) }
    }

    private def filesUnder(dir: java.nio.file.Path): List[java.nio.file.Path] = {
        import scala.collection.JavaConverters._
        Files.walk(dir).iterator().asScala.filter(Files.isRegularFile(_)).toList
    }

    /** A repository store whose reads are answered by a fake client that counts them. */
    private class CountingRepo(content: String = "print('from the repository')") {
        val reads = new java.util.concurrent.atomic.AtomicInteger(0)
        val refs = mutable.Buffer[String]()
        val store = new GithubScriptStore(
            n => ScriptFamily.Tap(n),
            null,
            () => Some(CodeRepoConfig(repo = "acme/scripts", branch = "main", pathPrefix = "taps/")),
            _ => "0123456789abcdef0123456789abcdef01234567",
            (_, _, ref) => {
                reads.incrementAndGet()
                refs.synchronized(refs += ref)
                Some(RepoFile(content, "blob", ref))
            }
        )
    }

    private val NotCommitIds = Seq("main", "release/1", "/abs", "..", "../abc1234", "abc1234/..", "./abc1234", "abc1234/x", "abc12", "g123456", "")

    test("the script cache accepts only a commit id as its folder name: any other value is a miss on get and writes nothing on put") {
        withCacheDir { dir =>
            for (sha <- NotCommitIds) {
                assert(!TapScriptCache.isCommitId(sha), sha)
                TapScriptCache.put("o/r", sha, "taps/x.py", "print(1)")
                assert(TapScriptCache.get("o/r", sha, "taps/x.py").isEmpty, sha)
                assert(TapScriptCache.location("o/r", sha, "taps/x.py").isEmpty, sha)
            }
            assert(filesUnder(dir).isEmpty, "nothing written: " + filesUnder(dir))
            assert(!TapScriptCache.isCommitId(null))
            for (sha <- Seq("abc1234", "ABC1234", "0123456789abcdef0123456789abcdef01234567", "a" * 64))
                assert(TapScriptCache.isCommitId(sha), sha)
            assert(!TapScriptCache.isCommitId("a" * 65))
        }
    }

    test("a repository read with a commit value that is not a commit id goes to the repository every time and caches nothing") {
        withCacheDir { dir =>
            for (sha <- NotCommitIds.filter(_.nonEmpty)) {
                val repo = new CountingRepo()
                val ref = ScriptRef("orders", "github", scriptRepoPath = "taps/orders.py", scriptCommitSha = sha)
                assert(repo.store.readScript(ref).contains("print('from the repository')"), sha)
                assert(repo.store.readScript(ref).contains("print('from the repository')"), sha)
                assert(repo.reads.get == 2, sha + ": each read goes to the repository")
                assert(repo.refs.toList == List(sha, sha), "the value is sent as the ref, as on a cold cache")
                assert(repo.store.scriptExists(ref) && repo.reads.get == 3, sha)
            }
            assert(filesUnder(dir).isEmpty, "nothing written: " + filesUnder(dir))
        }
    }

    test("a short and a long commit id still cache as before: the first read goes to the repository, the next is served locally") {
        withCacheDir { dir =>
            for (sha <- Seq("abc1234", "0123456789abcdef0123456789abcdef01234567", "f" * 64)) {
                val repo = new CountingRepo()
                val ref = ScriptRef("orders", "github", scriptRepoPath = "taps/orders.py", scriptCommitSha = sha)
                assert(repo.store.readScript(ref).contains("print('from the repository')"))
                assert(repo.store.readScript(ref).contains("print('from the repository')"))
                assert(repo.reads.get == 1, sha + ": " + repo.reads.get)
                assert(repo.store.scriptExists(ref) && repo.reads.get == 1, sha)
            }
            assert(filesUnder(dir).size == 3, filesUnder(dir).toString)
        }
    }

    test("cache files written by v1.61.0 are still found: <root>/<uuid of repo>/<commit id>/<path with / as _>") {
        withCacheDir { dir =>
            val sha = "0123456789abcdef0123456789abcdef01234567"
            val repoDir = dir.resolve(java.util.UUID.nameUUIDFromBytes("acme/scripts".getBytes("UTF-8")).toString)
            Files.createDirectories(repoDir.resolve(sha))
            Files.write(repoDir.resolve(sha).resolve("taps_orders.py"), "print('cached by v1.61.0')".getBytes("UTF-8"))
            val repo = new CountingRepo()
            val ref = ScriptRef("orders", "github", scriptRepoPath = "taps/orders.py", scriptCommitSha = sha)
            assert(repo.store.readScript(ref).contains("print('cached by v1.61.0')"))
            assert(repo.reads.get == 0, "served from the existing cache file")
            // and a write lands where v1.61.0 put it
            TapScriptCache.put("acme/scripts", "abcdef1", "taps/sub/x.py", "print(2)")
            assert(Files.isRegularFile(repoDir.resolve("abcdef1").resolve("taps_sub_x.py")))
        }
    }

    test("the script cache reads and writes only a file directly inside a commit folder of its directory") {
        withCacheDir { dir =>
            val sha = "abc1234"
            for (path <- Seq("..", ".", "")) {
                TapScriptCache.put("o/r", sha, path, "print(1)")
                assert(TapScriptCache.get("o/r", sha, path).isEmpty, "'" + path + "'")
                assert(TapScriptCache.location("o/r", sha, path).isEmpty, "'" + path + "'")
            }
            assert(filesUnder(dir).isEmpty, filesUnder(dir).toString)
            // the containment check on its own, whatever the commit value
            val root = dir
            assert(TapScriptCache.inside(root, root.resolve("r").resolve("c").resolve("f.py")))
            assert(!TapScriptCache.inside(root, root.resolve("r").resolve("c")))
            assert(!TapScriptCache.inside(root, root.resolve("r").resolve("..").resolve("..").resolve("x").resolve("y").resolve("f.py")))
            assert(!TapScriptCache.inside(root, root.resolve("r").resolve("c").resolve("..").resolve("f.py")))
            assert(!TapScriptCache.inside(root, root.getParent.resolve("other").resolve("r").resolve("c").resolve("f.py")))
            assert(!TapScriptCache.inside(root, root.resolve("r").resolve("c").resolve("d").resolve("f.py")))
            assert(TapScriptCache.location("o/r", sha, "taps/x.py").exists(p => TapScriptCache.inside(root, p)))
        }
    }

    test("the contents URL of a repository path always names a file inside the repository's contents") {
        val cfg = CodeRepoConfig(repo = "o/r", authSecretName = "s")
        val base = "https://api.github.com/repos/o/r/contents/"
        assert(GithubClient.contentsUrl(cfg, "taps/x.py") == base + "taps/x.py")
        assert(GithubClient.contentsUrl(cfg, "/taps//x.py") == base + "taps/x.py", "empty segments dropped as before")
        assert(GithubClient.contentsUrl(cfg, "./taps/./x.py") == base + "taps/x.py")
        assert(GithubClient.contentsUrl(cfg, "taps/sub/../x.py") == base + "taps/x.py")
        for (p <- Seq("../x.py", "taps/../../x.py", "../../../x.py")) {
            val url = GithubClient.contentsUrl(cfg, p)
            assert(url == base + "x.py", p + " -> " + url)
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

    // --- drift check and the disabled-repository message (story 2 review) ---

    /** A store whose branch head is `head` with `headContent`, and whose pinned
      * read returns `pinnedContent`. */
    private class DriftStub(head: String, headContent: String, pinnedContent: String) extends CodeStore {
        override def storage: String = "github"
        override def readScript(ref: ScriptRef): Option[String] = Option(pinnedContent)
        override def storeScript(name: String, script: String, prior: ScriptRef, actor: String): StoredScript = throw new UnsupportedOperationException
        override def deleteScript(ref: ScriptRef): Unit = ()
        override def scriptExists(ref: ScriptRef): Boolean = true
        override def pullLatest(ref: ScriptRef): Option[(String, String)] = Option(headContent).map(c => (c, head))
    }

    private val pinnedRef = ScriptRef("transformation", "github", scriptRepoPath = "taps/pipelines/orders/transformation.py", scriptCommitSha = "s0")

    test("driftHead default: same sha or same content is no drift; new content reports the head sha") {
        assert(new DriftStub("s0", "print(1)", "print(1)").driftHead(pinnedRef).isEmpty, "same sha")
        assert(new DriftStub("s1", "print(1)", "print(1)").driftHead(pinnedRef).isEmpty, "new sha, same content")
        assert(new DriftStub("s1", "print(2)", "print(1)").driftHead(pinnedRef).contains("s1"), "new sha, new content")
        assert(new DriftStub("s1", null, "print(1)").driftHead(pinnedRef).isEmpty, "file gone at head")
        assert(new MinioScriptStore("pipeline-scripts/orders/").driftHead(pinnedRef).isEmpty, "built-in store has no branch")
    }

    test("GithubScriptStore.driftHead compares blob shas and never writes the script cache") {
        val path = pinnedRef.scriptRepoPath
        val cacheDir = Files.createTempDirectory("tap-cache-drift")
        val prop = "datris.tap.cache.dir"
        val old = sys.props.get(prop)
        sys.props(prop) = cacheDir.toString
        try {
            def store(head: String, blobs: Map[String, String]): GithubScriptStore =
                new GithubScriptStore(
                    k => ScriptFamily.Pipeline("orders", k),
                    null,
                    () => Some(CodeRepoConfig(repo = "acme/scripts")),
                    _ => head,
                    (_, p, ref) => if (p == path) blobs.get(ref).map(b => RepoFile("content-" + b, b, ref)) else None
                )
            assert(store("s0", Map("s0" -> "b0")).driftHead(pinnedRef).isEmpty, "head is the recorded commit")
            assert(store("s1", Map("s0" -> "b0", "s1" -> "b0")).driftHead(pinnedRef).isEmpty, "other files moved; same blob")
            assert(store("s1", Map("s0" -> "b0", "s1" -> "b1")).driftHead(pinnedRef).contains("s1"), "the file changed")
            assert(store("s1", Map("s0" -> "b0")).driftHead(pinnedRef).isEmpty, "file deleted at head")
            assert(store("s1", Map("s1" -> "b1")).driftHead(pinnedRef).contains("s1"), "recorded commit unreadable counts as drift")
            val written = Files.walk(cacheDir).filter(Files.isRegularFile(_)).count()
            assert(written == 0, "drift polling writes nothing to the script cache")
        } finally {
            old match { case Some(v) => sys.props(prop) = v; case None => sys.props.remove(prop) }
        }
    }

    test("a disabled repository: pipeline scripts get the pipeline message, taps keep theirs") {
        val tapText =
            "This tap stores its script in a code repository, but no enabled repository is configured. " +
                "Re-enable it under Configuration > Code Repository, or move the tap back to built-in storage."
        assert(GithubScriptStore.TapDisabledMessage == tapText, "tap text unchanged")
        assert(GithubScriptStore.PipelineDisabledMessage.startsWith("This pipeline's CodeGen script is stored in a code repository"))
        assert(GithubScriptStore.PipelineDisabledMessage.contains("storage=builtin"))
        assert(!GithubScriptStore.PipelineDisabledMessage.contains("tap"))

        val ref = ScriptRef("dataQuality", "github", scriptRepoPath = "taps/pipelines/orders/data-quality.py", scriptCommitSha = "s0")
        val pipelineStore = new GithubScriptStore(k => ScriptFamily.Pipeline("orders", k), GithubScriptStore.PipelineDisabledMessage, () => None)
        val e1 = intercept[ai.datris.model.DatrisException](pipelineStore.readScript(ref))
        assert(e1.getMessage == GithubScriptStore.PipelineDisabledMessage)
        val tapStore = new GithubScriptStore(n => ScriptFamily.Tap(n), null, () => None)
        val e2 = intercept[ai.datris.model.DatrisException](tapStore.readScript(ref))
        assert(e2.getMessage == tapText, "a store with no message (the tap store) uses the tap text")
    }
}
