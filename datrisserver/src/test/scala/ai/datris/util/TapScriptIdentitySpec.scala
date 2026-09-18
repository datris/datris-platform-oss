package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.TapConfig
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Story: Test-before-cron gate (plans/stories/test-before-cron.md).
  *
  *  `TapScriptIdentity.of` names the exact bytes (or endpoint) a schedule would
  *  run, so a successful `mode=test` can be stamped against it and a later
  *  script edit drops the green:
  *
  *  {{{
  *  object TapScriptIdentity {
  *      // "gh:" + scriptCommitSha for repo-backed taps; "http:" + endpointUrl
  *      // for HTTP taps; sha256 hex of the script text for MinIO-backed taps.
  *      // None when the script cannot be read (missing object, dead repo,
  *      // unpinned repo tap) — an unreadable script must never mint an
  *      // identity that could match a stamp.
  *      def of(tap: TapConfig): Option[String]
  *      // Same, with the script reader injected so the MinIO branch is testable
  *      // without an object store. `of(tap)` == `of(tap, t => TapCodeStore.forTap(t).readScript(t))`.
  *      def of(tap: TapConfig, readScript: TapConfig => Option[String]): Option[String]
  *  }
  *  }}}
  */
class TapScriptIdentitySpec extends AnyFunSuite {

    private def sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString

    private val noRead: TapConfig => Option[String] =
        _ => throw new AssertionError("identity for this tap kind must not read the script")

    private def minio(path: String): TapConfig =
        TapConfig(name = "orders", description = "d", targetPipeline = "p", scriptPath = path)

    test("github-backed taps key on scriptCommitSha") {
        val t = TapConfig(
            name = "orders", description = "d", targetPipeline = "p",
            scriptStorage = "github", scriptRepoPath = "taps/orders.py", scriptCommitSha = "0123abcd0123abcd"
        )
        assert(TapScriptIdentity.of(t).contains("gh:0123abcd0123abcd"))
        assert(TapScriptIdentity.of(t, noRead).contains("gh:0123abcd0123abcd"), "repo identity is the pin, not the bytes")
    }

    test("HTTP taps key on endpointUrl") {
        val t = TapConfig(
            name = "feed", description = "d", targetPipeline = "p",
            scriptKind = "http", endpointUrl = "https://feeds.example.org/v1/prices"
        )
        assert(TapScriptIdentity.of(t).contains("http:https://feeds.example.org/v1/prices"))
        assert(TapScriptIdentity.of(t, noRead).contains("http:https://feeds.example.org/v1/prices"))
    }

    test("MinIO-backed taps key on the sha256 of the script bytes") {
        val script = "import requests\nprint(requests.get('https://example.org').text)\n"
        val id = TapScriptIdentity.of(minio("tap-scripts/orders_1.py"), _ => Some(script))
        assert(id.contains(sha256Hex(script)), id.toString)
        // Stable for the same bytes, different for different bytes, regardless of path.
        assert(TapScriptIdentity.of(minio("tap-scripts/orders_2.py"), _ => Some(script)) == id)
        assert(TapScriptIdentity.of(minio("tap-scripts/orders_1.py"), _ => Some(script + "# edited\n")) != id)
    }

    test("an unreadable script yields None") {
        // Missing object.
        assert(TapScriptIdentity.of(minio("tap-scripts/gone.py"), _ => None).isEmpty)
        // Backend failure (connection refused, dead repo) must not surface as an identity either.
        assert(TapScriptIdentity.of(minio("tap-scripts/orders_1.py"), _ => throw new RuntimeException("connection refused")).isEmpty)
        // No script reference at all: nothing to identify, no I/O attempted.
        assert(TapScriptIdentity.of(minio(null)).isEmpty)
        assert(TapScriptIdentity.of(minio("")).isEmpty)
        // Repo-backed tap with no pinned commit: there are no exact bytes to promise.
        val unpinned = TapConfig(
            name = "orders", description = "d", targetPipeline = "p",
            scriptStorage = "github", scriptRepoPath = "taps/orders.py", scriptCommitSha = null
        )
        assert(TapScriptIdentity.of(unpinned, _ => None).isEmpty)
    }
}
