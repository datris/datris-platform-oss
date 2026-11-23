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

    private val OtherCommitValues = Seq("main", "release/1", "/abs", "..", "../abc1234", "abc1234/..", "./abc1234", "abc1234/x", "abc12", "g123456", "")

    test("the script cache accepts only a commit id as its folder name: any other value is a miss on get and writes nothing on put") {
        withCacheDir { dir =>
            for (sha <- OtherCommitValues) {
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
            for (sha <- OtherCommitValues.filter(_.nonEmpty)) {
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

    private val Sha40 = "0123456789abcdef0123456789abcdef01234567"

    private def repoDirOf(dir: java.nio.file.Path, repo: String): java.nio.file.Path =
        dir.resolve(java.util.UUID.nameUUIDFromBytes(repo.getBytes("UTF-8")).toString)

    private def sha256Hex(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")).map(b => "%02x".format(b & 0xff)).mkString

    test("a local copy is named by the SHA-256 of the exact repository path, plus its extension, under <root>/<uuid of repo>/<commit id>/") {
        withCacheDir { dir =>
            assert(TapScriptCache.fileName("taps/orders.py") == sha256Hex("taps/orders.py") + ".py")
            assert(TapScriptCache.fileName("taps/Makefile") == sha256Hex("taps/Makefile"))
            assert(TapScriptCache.fileName("taps/.py") == sha256Hex("taps/.py"))
            assert(TapScriptCache.fileName(null) == null)
            TapScriptCache.put("acme/scripts", "abcdef1", "taps/sub/x.py", "print(2)")
            val expected = repoDirOf(dir, "acme/scripts").resolve("abcdef1").resolve(sha256Hex("taps/sub/x.py") + ".py")
            assert(Files.isRegularFile(expected), filesUnder(dir).toString)
            assert(filesUnder(dir) == List(expected))
        }
    }

    /** Pairs of different repository paths that differ only in a separator, case or form. */
    private val DifferentPaths = Seq(
        "taps/a/b.py" -> "taps/a_b.py",
        "taps/x/y/z.py" -> "taps/x_y/z.py",
        "taps/Orders.py" -> "taps/orders.py",
        "taps/caf\u00e9.py" -> "taps/cafe\u0301.py",
        ("taps/" + "n" * 300 + ".py") -> ("taps/" + "n" * 299 + "_.py")
    )

    test("two different repository paths have two local copies, and each read returns its own content") {
        withCacheDir { dir =>
            for (((a, b), i) <- DifferentPaths.zipWithIndex) {
                val sha = "abcdef" + i
                assert(TapScriptCache.fileName(a) != TapScriptCache.fileName(b), a + " / " + b)
                TapScriptCache.put("acme/scripts", sha, a, "print('a')")
                TapScriptCache.put("acme/scripts", sha, b, "print('b')")
                assert(TapScriptCache.get("acme/scripts", sha, a).contains("print('a')"), a)
                assert(TapScriptCache.get("acme/scripts", sha, b).contains("print('b')"), b)
            }
            assert(filesUnder(dir).size == DifferentPaths.size * 2, filesUnder(dir).toString)
            // through the store: each path is read from the repository once, then served locally with its own content
            val contents = Map("taps/a/b.py" -> "print('first')", "taps/a_b.py" -> "print('second')")
            val reads = new java.util.concurrent.atomic.AtomicInteger(0)
            val store = new GithubScriptStore(
                n => ScriptFamily.Tap(n),
                null,
                () => Some(CodeRepoConfig(repo = "acme/other", branch = "main", pathPrefix = "taps/")),
                _ => Sha40,
                (_, path, ref) => { reads.incrementAndGet(); contents.get(path).map(c => RepoFile(c, "blob", ref)) }
            )
            for (_ <- 1 to 2; (path, content) <- contents) {
                val ref = ScriptRef("t", "github", scriptRepoPath = path, scriptCommitSha = Sha40)
                assert(store.readScript(ref).contains(content), path)
            }
            assert(reads.get == 2, "one repository read per path: " + reads.get)
        }
    }

    test("a local copy under any other file name is not used: the script is read from the repository once, then served locally") {
        withCacheDir { dir =>
            val commitDir = repoDirOf(dir, "acme/scripts").resolve(Sha40)
            Files.createDirectories(commitDir)
            Files.write(commitDir.resolve("taps_orders.py"), "print('other file')".getBytes("UTF-8"))
            val repo = new CountingRepo()
            val ref = ScriptRef("orders", "github", scriptRepoPath = "taps/orders.py", scriptCommitSha = Sha40)
            assert(repo.store.readScript(ref).contains("print('from the repository')"))
            assert(repo.store.readScript(ref).contains("print('from the repository')"))
            assert(repo.reads.get == 1, "read from the repository once: " + repo.reads.get)
            assert(Files.isRegularFile(commitDir.resolve(TapScriptCache.fileName("taps/orders.py"))))
        }
    }

    test("the script cache reads and writes only a file directly inside a commit folder of its directory") {
        withCacheDir { dir =>
            val sha = "abc1234"
            TapScriptCache.put("o/r", sha, "", "print(1)")
            assert(TapScriptCache.get("o/r", sha, "").isEmpty)
            assert(TapScriptCache.location("o/r", sha, "").isEmpty)
            assert(TapScriptCache.location("o/r", sha, null).isEmpty)
            assert(filesUnder(dir).isEmpty, filesUnder(dir).toString)
            // any other path, however written, is one hexadecimal file directly inside the commit folder
            val commitDir = repoDirOf(dir, "o/r").resolve(sha)
            for (path <- Seq("..", ".", "/", "../../x.py", "taps/../../x.py", "a/b/c.py")) {
                val loc = TapScriptCache.location("o/r", sha, path)
                assert(loc.exists(p => p.getParent == commitDir && p.getFileName.toString.matches("[0-9a-f]{64}(\\.py)?")), path + " -> " + loc)
                TapScriptCache.put("o/r", sha, path, "print('" + path + "')")
                assert(TapScriptCache.get("o/r", sha, path).contains("print('" + path + "')"), path)
            }
            assert(filesUnder(dir).forall(_.getParent == commitDir), filesUnder(dir).toString)
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

    test("a read's contents URL names a file inside the repository's contents, however the path is written") {
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

    // --- code repository writes and deletes ---------------------------------

    /** (configured prefix, path, contents URL) for ordinary names and paths:
      * the URLs every operation sent before this change. */
    private val OrdinaryUrls: Seq[(String, String, String)] = Seq(
        ("taps/", "taps/orders.py", "https://api.github.com/repos/o/r/contents/taps/orders.py"),
        ("taps", "taps/orders.py", "https://api.github.com/repos/o/r/contents/taps/orders.py"),
        ("", "orders.py", "https://api.github.com/repos/o/r/contents/orders.py"),
        (null, "orders.py", "https://api.github.com/repos/o/r/contents/orders.py"),
        ("infra/datris/", "infra/datris/sales.orders.py", "https://api.github.com/repos/o/r/contents/infra/datris/sales.orders.py"),
        ("taps/", "taps/a/b.py", "https://api.github.com/repos/o/r/contents/taps/a/b.py"),
        ("taps/", "taps/a b.py", "https://api.github.com/repos/o/r/contents/taps/a%20b.py"),
        ("taps/", "taps/50%-off.py", "https://api.github.com/repos/o/r/contents/taps/50%25-off.py"),
        ("taps/", "taps/a+b.py", "https://api.github.com/repos/o/r/contents/taps/a%2Bb.py"),
        ("taps/", "taps/caf\u00e9.py", "https://api.github.com/repos/o/r/contents/taps/caf%C3%A9.py"),
        ("taps/", "taps/...py", "https://api.github.com/repos/o/r/contents/taps/...py"),
        ("taps/", "taps/x/...py", "https://api.github.com/repos/o/r/contents/taps/x/...py"),
        ("taps/", "taps/pipelines.py", "https://api.github.com/repos/o/r/contents/taps/pipelines.py"),
        ("taps/", "taps/pipelines/orders/transformation.py", "https://api.github.com/repos/o/r/contents/taps/pipelines/orders/transformation.py"),
        ("taps/", "other/orders.py", "https://api.github.com/repos/o/r/contents/other/orders.py"),
        ("/taps/", "/taps/orders.py", "https://api.github.com/repos/o/r/contents/taps/orders.py"),
        ("org//taps/", "org//taps/orders.py", "https://api.github.com/repos/o/r/contents/org/taps/orders.py")
    )

    /** Paths whose part after the configured prefix has an empty, `.` or `..` segment. */
    private val RefusedChanges: Seq[(String, String)] = Seq(
        "taps/" -> "taps/../orders.py",
        "taps/" -> "taps/./orders.py",
        "taps/" -> "taps//orders.py",
        "taps/" -> "taps/a/../../b.py",
        "taps/" -> "taps/orders.py/",
        "taps/" -> "taps/a/./b.py",
        "taps/" -> "/orders.py",
        "" -> "../orders.py",
        "" -> "./orders.py",
        "" -> "a//b.py",
        "taps/" -> ".."
    )

    /** A fake transport for [[GithubClient]] that records every request. */
    private class FakeTransport {
        val sent = mutable.Buffer[(String, String)]()
        def install(): Unit =
            GithubClient.transport = (request, _) =>
                sent.synchronized {
                    sent += (request.getMethod -> request.getURI.toString)
                    request.getMethod match {
                        case "GET" => (200, "{\"content\": \"cHJpbnQoMSk=\", \"sha\": \"blob1\"}")
                        case _ => (200, "{\"commit\": {\"sha\": \"" + Sha40 + "\"}}")
                    }
                }
    }

    private def withTransport[T](f: FakeTransport => T): T = {
        val saved = GithubClient.transport
        val t = new FakeTransport
        t.install()
        try f(t)
        finally GithubClient.transport = saved
    }

    private def repoCfg(prefix: String): CodeRepoConfig = CodeRepoConfig(repo = "o/r", authSecretName = "s", branch = "main", pathPrefix = prefix)

    test("ordinary names and paths: reads, writes and deletes use the same contents URLs as before") {
        for ((prefix, path, url) <- OrdinaryUrls) {
            val cfg = repoCfg(prefix)
            assert(GithubClient.contentsUrl(cfg, path) == url, path)
            // the URL as built before this change: empty segments dropped, each segment encoded
            val before = "https://api.github.com/repos/o/r/contents/" +
                path.split("/").filter(_.nonEmpty).map(seg => java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")).mkString("/")
            assert(before == url, path)
            withTransport { t =>
                assert(GithubClient.putFile(cfg, path, "print(1)", "m", null) == Sha40)
                GithubClient.deleteFile(cfg, path, "m")
                assert(t.sent.map(_._1).toList == List("GET", "PUT", "GET", "DELETE"), path + ": " + t.sent)
                assert(t.sent.map(_._2).map(_.takeWhile(_ != '?')).forall(_ == url), path + ": " + t.sent)
            }
        }
    }

    test("a write or delete whose path has an empty, '.' or '..' segment after the configured prefix is refused and sends nothing") {
        for ((prefix, path) <- RefusedChanges) {
            val cfg = repoCfg(prefix)
            withTransport { t =>
                val put = intercept[ai.datris.model.ValidationException](GithubClient.putFile(cfg, path, "print(1)", "m", null))
                val del = intercept[ai.datris.model.ValidationException](GithubClient.deleteFile(cfg, path, "m"))
                assert(t.sent.isEmpty, path + ": " + t.sent)
                assert(put.getMessage == del.getMessage)
                assert(!put.getMessage.contains(path) || path == "..", put.getMessage)
                assert(ai.datris.api.ApiErrors.classify(put)._1 == 400)
            }
        }
    }

    test("a configured prefix with empty or dot segments is the operator's setting: the path the store writes under it is written") {
        for (prefix <- Seq("/taps", "//taps/", "taps//", "./taps/", "org//taps/", "/", "./", "a/../taps/")) {
            val cfg = repoCfg(prefix)
            val path = GithubCodeStore.scriptRepoPath("orders", cfg)
            assert(GithubClient.changeRefusal(cfg, path).isEmpty, prefix + " " + path)
            withTransport { t =>
                GithubClient.putFile(cfg, path, "print(1)", "m", null)
                GithubClient.deleteFile(cfg, path, "m")
                assert(t.sent.size == 4, prefix + ": " + t.sent)
                // every request names one file, the one the read checks
                assert(t.sent.map(_._2.takeWhile(_ != '?')).distinct.size == 1, prefix + ": " + t.sent)
            }
        }
    }

    /** Tap names whose script path would have an empty, `.` or `..` segment. */
    private val RefusedTapNames = Seq("../orders", "./orders", "a//b", "/orders", "a/./b", "a/../b", "a/../../b")

    /** Tap names that keep working: separators, dots inside a segment, a trailing separator, and names that only start like the pipelines folder. */
    private val AcceptedTapNames = Seq("orders", "a/b", "sales.orders", "..", "x/..", "a/", "...", "pipelines", "pipelines-x", "pipelines_x", "Pipelines/x")

    test("the repository path of a tap refuses a name with an empty, '.' or '..' segment, or under the pipelines folder, with a 400") {
        for (prefix <- Seq("taps/", "", "./taps/"); name <- RefusedTapNames :+ "pipelines/orders") {
            val e = intercept[ai.datris.model.ValidationException](GithubCodeStore.scriptRepoPath(name, repoCfg(prefix)))
            assert(ai.datris.api.ApiErrors.classify(e)._1 == 400, name)
            intercept[ai.datris.model.ValidationException](GithubCodeStore.scriptRepoPath(ScriptFamily.Tap(name), repoCfg(prefix)))
        }
        for (name <- AcceptedTapNames)
            assert(GithubCodeStore.scriptRepoPath(name, repoCfg("taps/")) == "taps/" + name + ".py", name)
        // a pipeline's own script path is unchanged
        assert(GithubCodeStore.scriptRepoPath(ScriptFamily.Pipeline("orders", "transformation"), repoCfg("taps/")) == "taps/pipelines/orders/transformation.py")
    }

    test("storing a tap script in the repository under a refused name sends nothing; an accepted name writes <prefix><name>.py") {
        val cfg = repoCfg("taps/")
        val store = new GithubScriptStore(n => ScriptFamily.Tap(n), null, () => Some(cfg), _ => Sha40, (_, _, _) => None)
        withCacheDir { _ =>
            for (name <- RefusedTapNames :+ "pipelines/orders")
                withTransport { t =>
                    intercept[ai.datris.model.ValidationException](store.storeScript(name, "print(1)", null, "todd"))
                    intercept[ai.datris.model.ValidationException](store.repoPathFor(name))
                    assert(t.sent.isEmpty, name + ": " + t.sent)
                }
            for (name <- AcceptedTapNames)
                withTransport { t =>
                    val stored = store.storeScript(name, "print(1)", null, "todd")
                    assert(stored.scriptRepoPath == "taps/" + name + ".py" && stored.scriptCommitSha == Sha40, name)
                    assert(t.sent.map(_._1).toList == List("GET", "PUT"), name + ": " + t.sent)
                }
            // a stored reference with such a segment after the prefix is not written or deleted either
            withTransport { t =>
                val prior = ScriptRef("orders", "github", scriptRepoPath = "taps/../orders.py", scriptCommitSha = Sha40)
                intercept[ai.datris.model.ValidationException](store.storeScript("orders", "print(1)", prior, "todd"))
                intercept[ai.datris.model.ValidationException](store.deleteScript(prior))
                assert(t.sent.isEmpty, t.sent.toString)
            }
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

    // --- the built-in tap script store ---------------------------------------

    private def withInfoLog[T](f: => T): (T, List[String]) = {
        val names = Seq(classOf[MinioScriptStore].getName, TapScriptGenerator.getClass.getName)
        val loggers = names.map(n => org.slf4j.LoggerFactory.getLogger(n).asInstanceOf[ch.qos.logback.classic.Logger])
        val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
        appender.start()
        val levels = loggers.map(_.getLevel)
        loggers.foreach { l => l.addAppender(appender); l.setLevel(ch.qos.logback.classic.Level.INFO) }
        try {
            val out = f
            import scala.collection.JavaConverters._
            (out, appender.list.asScala.filter(_.getLevel == ch.qos.logback.classic.Level.INFO).map(_.getFormattedMessage).toList)
        } finally loggers.zip(levels).foreach { case (l, lv) => l.detachAppender(appender); l.setLevel(lv) }
    }

    test("the built-in tap store deletes a key under tap-scripts/ and leaves any other stored reference in place, logging the tap's name only") {
        TenantContext.set(SpecEnv.testEnv)
        try {
            val objects = new MemObjects
            val bucket = SpecEnv.testEnv.environment + "-config"
            val store = MinioCodeStore.tapStore(objects)
            val own = store.storeScript("orders", "print('own')", null, "todd")
            assert(own.scriptPath.startsWith("tap-scripts/orders_"), own.scriptPath)
            objects.objects((bucket, "uploads/data.csv")) = "a,b"
            objects.objects((bucket, "pipeline-scripts/p/transformation_1.py")) = "print(2)"

            val (_, keptLog) = withInfoLog {
                store.deleteScript(ScriptRef("elsewhere", "minio", scriptPath = "uploads/data.csv"))
                store.deleteScript(ScriptRef("elsewhere", "minio", scriptPath = "pipeline-scripts/p/transformation_1.py"))
            }
            assert(objects.objects.contains((bucket, "uploads/data.csv")))
            assert(objects.objects.contains((bucket, "pipeline-scripts/p/transformation_1.py")))
            assert(
                keptLog.size == 2 && keptLog.forall(l => l.contains("'elsewhere'") && !l.contains("uploads") && !l.contains("pipeline-scripts")),
                keptLog.toString
            )

            assert(store.deleteUnlessEdited(ScriptRef("orders", "minio", scriptPath = own.scriptPath)).isEmpty)
            assert(!objects.objects.contains((bucket, own.scriptPath)), "the tap's own script is deleted as before")
        } finally TenantContext.clear()
    }

    test("tap version cleanup deletes only keys under tap-scripts/; any other reference is left in place with one INFO line naming the tap") {
        TenantContext.set(SpecEnv.testEnv)
        try {
            val objects = new MemObjects
            val bucket = SpecEnv.testEnv.environment + "-config"
            Seq("tap-scripts/orders_1.py", "tap-scripts/orders_2.py", "uploads/data.csv", "pipeline-scripts/p/x_1.py").foreach(k =>
                objects.objects((bucket, k)) = "x"
            )
            val (_, log) = withInfoLog {
                TapScriptGenerator.deleteTapScripts(
                    "orders",
                    Seq("tap-scripts/orders_1.py", "uploads/data.csv", "tap-scripts/orders_2.py", "pipeline-scripts/p/x_1.py", null, ""),
                    objects = objects
                )
            }
            assert(objects.objects.keys.map(_._2).toSet == Set("uploads/data.csv", "pipeline-scripts/p/x_1.py"), objects.objects.keys.toString)
            assert(log.size == 1 && log.head.contains("'orders'") && !log.head.contains("uploads") && !log.head.contains("pipeline-scripts"), log.toString)
            // nothing outside: no line; an outside reference already reported by the store delete: no second line
            val (_, quiet) = withInfoLog {
                TapScriptGenerator.deleteTapScripts("orders", Seq("tap-scripts/orders_3.py"), objects = objects)
                TapScriptGenerator.deleteTapScripts("orders", Seq("uploads/data.csv"), alreadyReported = Set("uploads/data.csv"), objects = objects)
            }
            assert(quiet.isEmpty, quiet.toString)
            assert(objects.objects.contains((bucket, "uploads/data.csv")))
        } finally TenantContext.clear()
    }

    test("the built-in tap store keeps names with a separator working, and refuses an empty, '.' or '..' segment before writing") {
        TenantContext.set(SpecEnv.testEnv)
        try {
            val objects = new MemObjects
            val store = MinioCodeStore.tapStore(objects)
            for (name <- Seq("a/b", "sales.orders", "..", "x/..", "a/", "pipelines/x")) {
                val stored = store.storeScript(name, "print(1)", null, "todd")
                assert(stored.scriptPath.startsWith("tap-scripts/" + name + "_") && stored.scriptPath.endsWith(".py"), stored.scriptPath)
                assert(store.readScript(ScriptRef(name, "minio", scriptPath = stored.scriptPath)).contains("print(1)"), name)
            }
            val before = objects.objects.size
            for (name <- RefusedTapNames) {
                val e = intercept[ai.datris.model.ValidationException](store.storeScript(name, "print(1)", null, "todd"))
                assert(ai.datris.api.ApiErrors.classify(e)._1 == 400, name)
            }
            assert(objects.objects.size == before, "nothing written")
            assert(objects.objects.keys.forall(_._2.startsWith("tap-scripts/")))
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

    // --- repository responses that are not a single file ---------------------

    /** Text placed in every fake response below; no error message may contain it. */
    private val ResponseText = "resp-text-5e1d9"

    /** Installs a transport answering every GET with `get` and every other
      * request with `other`; restores the previous one afterwards. */
    private def withResponses[T](get: (Int, String), other: (Int, String) = (200, "{\"commit\": {\"sha\": \"" + Sha40 + "\"}}"))(f: => T): T = {
        val saved = GithubClient.transport
        GithubClient.transport = (request, _) => if (request.getMethod == "GET") get else other
        try f
        finally GithubClient.transport = saved
    }

    /** Every message of `e` and its causes, joined. */
    private def messages(e: Throwable): String =
        Iterator.iterate(e)(_.getCause).takeWhile(_ != null).map(x => String.valueOf(x.getMessage)).mkString(" | ")

    /** Responses that are JSON but not a single file. */
    private val NotAFile = Seq(
        "[{\"type\": \"file\", \"name\": \"" + ResponseText + ".py\", \"content\": \"cHJpbnQoMSk=\", \"sha\": \"" + ResponseText + "\"}]",
        "[]",
        "{\"type\": \"dir\", \"name\": \"" + ResponseText + "\", \"sha\": \"" + ResponseText + "\"}",
        "{\"type\": \"symlink\", \"target\": \"" + ResponseText + "\", \"content\": \"cHJpbnQoMSk=\", \"sha\": \"" + ResponseText + "\"}",
        "{\"type\": \"submodule\", \"submodule_git_url\": \"" + ResponseText + "\", \"sha\": \"" + ResponseText + "\"}",
        "{\"type\": 7, \"content\": \"cHJpbnQoMSk=\", \"sha\": \"" + ResponseText + "\"}",
        "{\"content\": {\"x\": \"" + ResponseText + "\"}, \"sha\": \"" + ResponseText + "\"}",
        "{\"sha\": \"" + ResponseText + "\"}",
        "\"" + ResponseText + "\"",
        "null"
    )

    /** Bodies that cannot be read as the expected JSON. */
    private val Unreadable = Seq(
        "{\"content\": \"" + ResponseText,
        ResponseText + " <html>",
        "{\"type\": \"file\", \"content\": \"" + ResponseText + "!!\", \"sha\": \"" + ResponseText + "\"}"
    )

    test("a repository read whose response is not a single file is no such file, the same as a missing file") {
        val cfg = repoCfg("taps/")
        for (body <- NotAFile)
            withResponses((200, body)) {
                assert(GithubClient.getFile(cfg, "taps/orders.py", Sha40).isEmpty, body)
            }
        withResponses((404, "{\"message\": \"" + ResponseText + "\"}")) {
            assert(GithubClient.getFile(cfg, "taps/orders.py", Sha40).isEmpty)
        }
        // a single file reads as before, with or without its type
        for (body <- Seq("{\"content\": \"cHJpbnQoMSk=\\n\", \"sha\": \"blob1\"}", "{\"type\": \"file\", \"content\": \"cHJpbnQoMSk=\", \"sha\": \"blob1\"}"))
            withResponses((200, body)) {
                assert(GithubClient.getFile(cfg, "taps/orders.py", Sha40).contains(RepoFile("print(1)", "blob1", Sha40)), body)
            }
    }

    test("a repository response that cannot be read is a fixed error carrying the status code and none of the response text") {
        val cfg = repoCfg("taps/")
        for (body <- Unreadable)
            withResponses((200, body)) {
                val e = intercept[ai.datris.model.DatrisException](GithubClient.getFile(cfg, "taps/orders.py", Sha40))
                assert(e.getMessage == "Code repository read of 'taps/orders.py' failed (HTTP 200): the response could not be read.", e.getMessage)
                assert(e.getCause == null)
            }
        for (body <- Unreadable ++ NotAFile.filter(_ != "null")) {
            withResponses((200, body), (200, body)) {
                val calls: Seq[(String, () => Any)] = Seq(
                    "validation" -> (() => GithubClient.repoInfo(cfg)),
                    "branch listing" -> (() => GithubClient.listBranches(cfg)),
                    "branch lookup" -> (() => GithubClient.branchHeadSha(cfg)),
                    "write" -> (() => GithubClient.putFile(cfg, "taps/orders.py", "print(1)", "m", null))
                )
                for ((name, call) <- calls) {
                    val outcome =
                        try { call(); None }
                        catch { case e: Exception => Some(e) }
                    outcome.foreach { e =>
                        assert(!messages(e).contains(ResponseText), name + " " + body + ": " + messages(e))
                        assert(e.getCause == null, name + " " + body)
                    }
                }
            }
        }
    }

    /** The fixed hint each refused status shows. */
    private val RefusedHints = Seq(
        401 -> "Token was rejected. It may be expired or revoked — update the repo token secret.",
        403 -> "Token lacks access. Fine-grained tokens may be disabled by org policy, or the token is missing Contents read/write on this repo.",
        404 -> "Repository or path not found — check the repo name, or the token cannot see this repository.",
        500 -> ""
    )

    /** The errors every repository call gives for a refused response
      * `(status, body)`; a read's 404 is no such file, so it has none. */
    private def refusedErrors(cfg: CodeRepoConfig, status: Int, body: String): Seq[(String, ai.datris.model.DatrisException)] =
        withResponses((status, body), (status, body)) {
            Seq(
                "validation" -> intercept[ai.datris.model.DatrisException](GithubClient.repoInfo(cfg)),
                "branch listing" -> intercept[ai.datris.model.DatrisException](GithubClient.listBranches(cfg)),
                "branch lookup for 'main'" -> intercept[ai.datris.model.DatrisException](GithubClient.branchHeadSha(cfg))
            ) ++ (if (status == 404) Nil
                  else Seq("read of 'taps/orders.py'" -> intercept[ai.datris.model.DatrisException](GithubClient.getFile(cfg, "taps/orders.py", Sha40))))
        }

    private def refusedMessage(context: String, status: Int, reason: String, hint: String): String =
        ("Code repository " + context + " failed (HTTP " + status + "). " + reason + " " + hint).trim

    test("a refused repository request shows the status code, the repository's short reason and the fixed hint, and nothing else from the response") {
        val cfg = repoCfg("taps/")
        for ((status, hint) <- RefusedHints; reason <- Seq("Bad credentials", "Not Found")) {
            val body = "{\"message\": \"" + reason + "\", \"documentation_url\": \"" + ResponseText + "\", \"errors\": [\"" + ResponseText + "\"]}"
            for ((context, e) <- refusedErrors(cfg, status, body)) {
                assert(e.getMessage == refusedMessage(context, status, reason, hint), e.getMessage)
                assert(!messages(e).contains(ResponseText), e.getMessage)
            }
        }
        // the hint is still chosen from the response's reason
        withResponses((403, "{\"message\": \"API rate limit exceeded for 10.0.0.1.\", \"documentation_url\": \"" + ResponseText + "\"}")) {
            val e = intercept[ai.datris.model.DatrisException](GithubClient.branchHeadSha(cfg))
            assert(
                e.getMessage == "Code repository branch lookup for 'main' failed (HTTP 403). API rate limit exceeded for 10.0.0.1. GitHub rate limit exceeded. Retry later.",
                e.getMessage
            )
        }
        withResponses((403, "{\"message\": \"Resource protected by organization SAML enforcement.\"}")) {
            val e = intercept[ai.datris.model.DatrisException](GithubClient.branchHeadSha(cfg))
            assert(
                e.getMessage.endsWith("enforcement. The organization enforces SSO — authorize the token for this organization in GitHub settings."),
                e.getMessage
            )
        }
    }

    test("a refused repository request shows at most 200 characters of the repository's reason") {
        val cfg = repoCfg("taps/")
        val long = "x" * 200 + ResponseText
        for ((status, hint) <- RefusedHints; (context, e) <- refusedErrors(cfg, status, "{\"message\": \"" + long + "\"}")) {
            assert(e.getMessage == refusedMessage(context, status, "x" * 200, hint), e.getMessage)
            assert(!messages(e).contains(ResponseText), e.getMessage)
        }
    }

    test("a refused repository request shows the repository's reason as one line with no control characters") {
        val cfg = repoCfg("taps/")
        // JSON escapes: LF, CR LF + tab, line separator, paragraph separator, BEL, NEL
        val bs = "\\"
        val multiLine =
            "Bad" + bs + "ncredentials" + bs + "r" + bs + "n" + bs + "tfor" + bs + "u2028this" + bs + "u2029token" + bs + "u0007now" + bs + "u0085ok"
        for ((status, hint) <- RefusedHints; (context, e) <- refusedErrors(cfg, status, "{\"message\": \"" + multiLine + "\"}")) {
            assert(e.getMessage == refusedMessage(context, status, "Bad credentials for this token now ok", hint), e.getMessage)
            assert(!e.getMessage.exists(c => Character.isISOControl(c) || c.toInt == 0x2028 || c.toInt == 0x2029), e.getMessage)
        }
    }

    test("a refused repository response with no string message shows none of the response text") {
        val cfg = repoCfg("taps/")
        val bodies = Seq(
            "{\"message\": {\"text\": \"" + ResponseText + "\"}, \"documentation_url\": \"" + ResponseText + "\"}",
            "{\"message\": [\"" + ResponseText + "\"]}",
            "{\"message\": 7, \"error\": \"" + ResponseText + "\"}",
            "{\"error\": \"" + ResponseText + "\"}",
            "[{\"message\": \"" + ResponseText + "\"}]",
            "[\"" + ResponseText + "\"]",
            ResponseText,
            "<html>" + ResponseText + "</html>",
            "{\"message\": \"" + ResponseText,
            ""
        )
        for ((status, hint) <- RefusedHints; body <- bodies; (context, e) <- refusedErrors(cfg, status, body)) {
            assert(e.getMessage == refusedMessage(context, status, "", hint), body + ": " + e.getMessage)
            assert(!messages(e).contains(ResponseText), e.getMessage)
        }
    }

    test("the repository script store's reads, existence checks and delete warnings carry none of the response text") {
        val cfg = repoCfg("taps/")
        val store = new GithubScriptStore(n => ScriptFamily.Tap(n), null, () => Some(cfg))
        val ref = ScriptRef("orders", "github", scriptRepoPath = "taps/orders.py", scriptCommitSha = Sha40)
        withCacheDir { _ =>
            for (body <- NotAFile)
                withResponses((200, body)) {
                    assert(store.readScript(ref).isEmpty, body)
                    assert(!store.scriptExists(ref), body)
                }
            for (body <- Unreadable) {
                withResponses((200, body)) {
                    val e = intercept[ai.datris.model.DatrisException](store.readScript(ref))
                    assert(!messages(e).contains(ResponseText), messages(e))
                    assert(e.getMessage.contains("(HTTP 200): the response could not be read."), e.getMessage)
                    val warning = store.deleteUnlessEdited(ref)
                    assert(warning.isDefined && !warning.get.contains(ResponseText), warning.toString)
                }
            }
            withResponses((500, ResponseText), (500, ResponseText)) {
                val warning = store.deleteUnlessEdited(ref)
                assert(warning.isDefined && !warning.get.contains(ResponseText), warning.toString)
            }
        }
    }

    // --- one tap-name rule on every path that stores a tap script ------------

    test("storing a built-in script refuses a tap name with an empty, '.' or '..' segment with the upload route's 400, writing nothing") {
        TenantContext.set(SpecEnv.testEnv)
        try {
            val objects = new MemObjects
            for (name <- RefusedTapNames) {
                val e = intercept[ai.datris.model.ValidationException](TapScriptGenerator.storeScript(name, "print(1)", null, objects = objects))
                assert(Some(e.getMessage) == ScriptPaths.tapNameRefusal(name), name)
                assert(ai.datris.api.ApiErrors.classify(e) == (400 -> ai.datris.api.ApiErrors.errorBody(e.getMessage)), name)
            }
            assert(objects.objects.isEmpty, "nothing written: " + objects.objects.keys)
            for (name <- AcceptedTapNames :+ "tap" :+ "") {
                val key = TapScriptGenerator.storeScript(name, "print(1)", "tap-scripts/old_1.py", objects = objects)
                assert(key.matches(java.util.regex.Pattern.quote("tap-scripts/" + name + "_") + "[0-9a-f]{8}\\.py"), key)
                assert(objects.objects.get((SpecEnv.testEnv.environment + "-config", key)).contains("print(1)"), key)
            }
            // a pipeline CodeGen script is stored under its prefix as before
            assert(
                TapScriptGenerator.storeScript("dataQuality", "x", null, "pipeline-scripts/orders/", objects).startsWith("pipeline-scripts/orders/dataQuality_")
            )
        } finally TenantContext.clear()
    }
}
