package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import io.github.jopenlibs.vault.Vault
import org.mockito.Mockito.{mock, verifyNoInteractions}
import org.scalatest.funsuite.AnyFunSuite

/** A secret name or Vault path can only address the secret it names: the
  * Vault driver puts the path into a URI unencoded, so `?` / `#` / `%` would
  * alias another secret.
  *
  * Tap secret scope (plans/stories/field-protection-8-tap-secret-scope.md)
  * pins on SecretNames:
  *   - `tapScope: String` — "any" or "tap", read at call time from the
  *     system property `datris.tapSecretScope`, else env
  *     `DATRIS_TAP_SECRET_SCOPE`; trimmed, lowercased; default and any
  *     unknown value -> "tap".
  *   - `tapScopeEnforced: Boolean` — tapScope != "any".
  *   - `tapSecretProblem(tapName: String, secretName: String,
  *     storedType: Option[String], enforced: Boolean): Option[String]` — the
  *     refusal message when enforced and storedType != Some("tap"). */
class SecretNamesSpec extends AnyFunSuite {

    private val unsafe = Seq(
        "field-protection?x",
        "field-protection#x",
        "field-protection?",
        "a%3Fb",
        "..",
        ".",
        "a/b",
        "a\\b",
        "a\u0000b",
        "a\nb",
        "a\u007fb",
        "my secret",
        "field-protection ",
        "a\u00a0b",
        "a\u200bb",
        "a\tb",
        "a\"b",
        "a<b",
        "a>b",
        "a|b",
        "a^b",
        "a`b",
        "a{b",
        "a}b",
        "a[b",
        "a]b",
        ""
    )
    private val safe = Seq("field-protection", "ai-primary", "my_secret", "tap.github_1", "team:prod")

    test("unsafe names are rejected") {
        unsafe.foreach(n => assert(!SecretNames.isSafe(n), "[" + n + "] must be unsafe"))
    }

    test("ordinary names are accepted") {
        safe.foreach(n => assert(SecretNames.isSafe(n), "[" + n + "] must be safe"))
    }

    test("paths: nested names are fine, aliasing segments and // are not") {
        assert(SecretNames.isSafePath("oss/field-protection"))
        assert(SecretNames.isSafePath("oss/tap/github_1"))
        assert(SecretNames.isSafePath("oss"))
        assert(!SecretNames.isSafePath("oss/field-protection?x=1"))
        assert(!SecretNames.isSafePath("oss/field-protection#x"))
        assert(!SecretNames.isSafePath("oss/../field-protection"))
        assert(!SecretNames.isSafePath("oss//field-protection"))
        assert(!SecretNames.isSafePath(null))
    }

    test("VaultSecretsUtil rejects an unsafe path before any Vault call, never as absent") {
        val vault = mock(classOf[Vault])
        val util = new VaultSecretsUtil(vault)
        val bad = "oss/field-protection?x=1"
        intercept[DatrisException](util.getSecretMap(bad))
        assert(util.tryGetSecretMap(bad).isFailure)
        intercept[DatrisException](util.getSecretField(bad, "enc.v1"))
        intercept[DatrisException](util.listSecrets("oss#x"))
        intercept[DatrisException](util.writeSecret(bad, new java.util.HashMap[String, Object]()))
        intercept[DatrisException](util.deleteSecret(bad))
        verifyNoInteractions(vault)
    }

    test("isServerManaged: the field-protection keys and the API-key stores, trimmed and case-insensitive") {
        for (n <- Seq("field-protection", "FIELD-PROTECTION", " field-protection ", "oss/field-protection", "api-keys", "api-key-metadata", "ui-api-key"))
            assert(SecretNames.isServerManaged(n), n)
        for (n <- Seq(null, "", "ai-primary", "codegen", "embedding", "ai-keys", "field-protection-copy", "my-tap"))
            assert(!SecretNames.isServerManaged(n), String.valueOf(n))
        val e = intercept[DatrisException](SecretNames.requireNotServerManaged("field-protection"))
        assert(e.getMessage == "'field-protection' is a platform secret and cannot be used as a tap or pipeline secret")
    }

    test("SecretsRetrieverUtil.userSecret refuses a server-managed name before any secret store read") {
        // Throws before SecretsUtil (a Vault-backed lazy val) is touched.
        for (n <- Seq("oss/field-protection", "oss/api-keys", "oss/api-key-metadata", "oss/ui-api-key")) {
            val e = intercept[DatrisException](SecretsRetrieverUtil.userSecret(n))
            assert(e.getMessage.contains("is a platform secret and cannot be used as a tap or pipeline secret"), n)
        }
    }

    test("referenceProblem: unsafe paths and server-managed names, nothing else") {
        assert(SecretNames.referenceProblem("field-protection/.").contains("Invalid secret name 'field-protection/.'"))
        assert(SecretNames.referenceProblem("field-protection?x=1").contains("Invalid secret name 'field-protection?x=1'"))
        assert(SecretNames.referenceProblem("field-protection#a").contains("Invalid secret name 'field-protection#a'"))
        assert(SecretNames.referenceProblem("e2e fp7b").contains("Invalid secret name 'e2e fp7b'"))
        assert(SecretNames.referenceProblem("field-protection").exists(_.contains("is a platform secret")))
        assert(SecretNames.referenceProblem("github-token").isEmpty)
        assert(SecretNames.referenceProblem("oss/embedding").isEmpty)
        assert(SecretNames.referenceProblem(null).isEmpty)
    }

    // ---- Field protection 8: tap secret scope

    private val ScopeProperty = "datris.tapSecretScope"

    private def withTapScope[A](value: Option[String])(body: => A): A = {
        val previous = sys.props.get(ScopeProperty)
        value match {
            case Some(v) => sys.props(ScopeProperty) = v
            case None => sys.props -= ScopeProperty
        }
        try body
        finally previous match {
                case Some(v) => sys.props(ScopeProperty) = v
                case None => sys.props -= ScopeProperty
            }
    }

    private val expectedRefusal =
        "Tap 'weather' uses secret 'ai-primary', which is a platform secret. Taps may only use tap secrets " +
            "(Configuration → Secrets → Tap). Create a tap secret with the fields this tap needs and select it, " +
            "or set DATRIS_TAP_SECRET_SCOPE=any to allow platform secrets."

    test("a tap-typed secret is allowed") {
        assert(SecretNames.tapSecretProblem("weather", "weather-api", Some("tap"), enforced = true).isEmpty)
        assert(SecretNames.tapSecretProblem("weather", "weather-api", Some("tap"), enforced = false).isEmpty)
    }

    test("a platform secret is refused when enforced, naming the tap, the secret and the opt-out") {
        val p = SecretNames.tapSecretProblem("weather", "ai-primary", Some("ai-provider"), enforced = true)
        assert(p.contains(expectedRefusal), p.toString)
        // A secret with no _type (a hand-made legacy one) counts as a platform secret.
        val untyped = SecretNames.tapSecretProblem("weather", "ai-primary", None, enforced = true)
        assert(untyped.contains(expectedRefusal), untyped.toString)
        // Only exactly "tap" passes.
        for (t <- Seq("TAP", " tap", "taps", "", "platform")) {
            val q = SecretNames.tapSecretProblem("t1", "s1", Some(t), enforced = true)
            assert(q.exists(m => m.contains("Tap 't1'") && m.contains("'s1'") && m.contains("DATRIS_TAP_SECRET_SCOPE=any")), s"[$t] -> $q")
        }
    }

    test("a platform secret is allowed when the scope is any") {
        assert(SecretNames.tapSecretProblem("weather", "ai-primary", Some("ai-provider"), enforced = false).isEmpty)
        assert(SecretNames.tapSecretProblem("weather", "ai-primary", None, enforced = false).isEmpty)
        withTapScope(Some("any")) {
            assert(!SecretNames.tapScopeEnforced)
            assert(SecretNames.tapSecretProblem("weather", "ai-primary", None, SecretNames.tapScopeEnforced).isEmpty)
        }
    }

    test("a missing secret is left to the existing missing-secret error") {
        // tapSecretProblem's storedType cannot tell a missing secret from an
        // untyped one (both None), so the "missing" decision is the caller's:
        // a lookup that says the secret does not exist (outer None) yields no
        // scope problem, leaving the existing "missing or empty" error to fire.
        assert(SecretNames.tapSecretProblem("weather", "weather-api", Some("tap"), enforced = true).isEmpty)
        assert(
            ai.datris.api.TapAPIController.tapSecretSaveProblem(
                ai.datris.model.TapConfig(name = "weather", description = "d", targetPipeline = "p", secretName = "not-there"),
                _ => None,
                enforced = true
            ).isEmpty
        )
    }

    test("tapScope defaults to tap and treats unknown values as tap") {
        assume(sys.env.get("DATRIS_TAP_SECRET_SCOPE").isEmpty, "DATRIS_TAP_SECRET_SCOPE is set in this environment")
        withTapScope(None) {
            assert(SecretNames.tapScope == "tap")
            assert(SecretNames.tapScopeEnforced)
        }
        for (v <- Seq("bogus", "", "all", "none", "tap")) withTapScope(Some(v)) {
            assert(SecretNames.tapScope == "tap", s"[$v]")
            assert(SecretNames.tapScopeEnforced, s"[$v]")
        }
        for (v <- Seq("any", "ANY", " Any ")) withTapScope(Some(v)) {
            assert(SecretNames.tapScope == "any", s"[$v]")
            assert(!SecretNames.tapScopeEnforced, s"[$v]")
        }
        // Read at call time, not cached.
        withTapScope(Some("any"))(assert(SecretNames.tapScope == "any"))
        withTapScope(Some("tap"))(assert(SecretNames.tapScope == "tap"))
    }
}
