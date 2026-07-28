package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{CodeRepoConfig, TapConfig}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files

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
}
