package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.TapConfig
import ai.datris.util.SecretNames
import org.scalatest.funsuite.AnyFunSuite

/** A tap may not reference a server-managed secret (SecretNames.ServerManaged):
  * POST /tap refuses it with 400 via serverManagedSecretProblem, and
  * TapScriptRunner refuses it again at run time (SecretNames.requireNotServerManaged,
  * covered in SecretNamesSpec) for taps saved before the check.
  *
  * Field protection 8 (plans/stories/field-protection-8-tap-secret-scope.md)
  * pins `TapAPIController.tapSecretSaveProblem(tap: TapConfig,
  * typeOf: String => Option[Option[String]], enforced: Boolean): Option[String]`
  * on the companion: the text POST /tap returns with 400. Outer None from
  * typeOf = the secret does not exist; inner Option = its stored `_type`.
  * Server-managed names are refused whatever `enforced` says. */
class TapServerManagedSecretSpec extends AnyFunSuite {

    private def tap(secret: String) = TapConfig(name = "t", description = "d", targetPipeline = "p", secretName = secret)

    test("save refuses a tap whose secretName is a server-managed secret") {
        for (n <- Seq("field-protection", "Field-Protection", "api-keys", "api-key-metadata", "ui-api-key")) {
            val p = TapAPIController.serverManagedSecretProblem(tap(n))
            assert(p.contains("'" + n + "' is a platform secret and cannot be used as a tap or pipeline secret"), s"[$n] -> $p")
        }
    }

    test("save accepts no secret or an ordinary secret") {
        assert(TapAPIController.serverManagedSecretProblem(tap(null)).isEmpty)
        assert(TapAPIController.serverManagedSecretProblem(tap("github-token")).isEmpty)
        assert(TapAPIController.serverManagedSecretProblem(tap("ai-primary")).isEmpty)
    }

    test("save refuses a tap secretName that is not a safe secret path") {
        for (n <- Seq("field-protection/.", "field-protection?x=1", "field-protection#a", "my secret")) {
            val p = TapAPIController.serverManagedSecretProblem(tap(n))
            assert(p.contains("Invalid secret name '" + n + "'"), s"[$n] -> $p")
        }
    }

    // ---- Field protection 8: tap secret scope on save

    private val types: Map[String, Option[String]] =
        Map("weather-api" -> Some("tap"), "ai-primary" -> Some("ai-provider"), "databricks" -> None, "field-protection" -> Some("tap"))
    private val typeOf: String => Option[Option[String]] = n => types.get(n.split("/").last)

    test("saving a tap on a platform secret is refused with 400 text") {
        for (secret <- Seq("ai-primary", "databricks")) {
            val p = TapAPIController.tapSecretSaveProblem(tap(secret), typeOf, enforced = true)
            assert(
                p.exists(m =>
                    m.contains("Tap 't' uses secret '" + secret + "'") && m.contains("platform secret") &&
                        m.contains("DATRIS_TAP_SECRET_SCOPE=any")
                ),
                s"[$secret] -> $p"
            )
        }
    }

    test("saving a tap on a tap secret passes") {
        assert(TapAPIController.tapSecretSaveProblem(tap("weather-api"), typeOf, enforced = true).isEmpty)
        assert(TapAPIController.tapSecretSaveProblem(tap(null), _ => fail("no secret: no lookup"), enforced = true).isEmpty)
        // Opt-out: a platform secret saves.
        assert(TapAPIController.tapSecretSaveProblem(tap("ai-primary"), typeOf, enforced = false).isEmpty)
    }

    test("server-managed names are refused even when the scope is any") {
        for (enforced <- Seq(true, false); n <- Seq("field-protection", "api-keys", "api-key-metadata", "ui-api-key")) {
            // Even tagged _type=tap, and even if the lookup says it is missing.
            for (lookup <- Seq[String => Option[Option[String]]](typeOf, (_: String) => Some(Some("tap")), (_: String) => None)) {
                val p = TapAPIController.tapSecretSaveProblem(tap(n), lookup, enforced)
                assert(p.contains(SecretNames.serverManagedMessage(n)), s"[$n enforced=$enforced] -> $p")
            }
        }
    }

    // ---- review follow-up: restoring a tap version is a save

    test("restoring a tap version on a platform secret is refused; a tap secret restores") {
        for (secret <- Seq("ai-primary", "databricks")) {
            val p = EntityVersionAPIController.tapRestoreProblem(tap(secret), typeOf, enforced = true)
            assert(p.exists(m => m.contains("Tap 't' uses secret '" + secret + "'") && m.contains("DATRIS_TAP_SECRET_SCOPE=any")), s"[$secret] -> $p")
        }
        assert(EntityVersionAPIController.tapRestoreProblem(tap("weather-api"), typeOf, enforced = true).isEmpty)
        assert(EntityVersionAPIController.tapRestoreProblem(tap(null), typeOf, enforced = true).isEmpty)
        assert(EntityVersionAPIController.tapRestoreProblem(tap("ai-primary"), typeOf, enforced = false).isEmpty)
        assert(
            EntityVersionAPIController.tapRestoreProblem(tap("field-protection"), typeOf, enforced = false)
                .contains(SecretNames.serverManagedMessage("field-protection"))
        )
    }

    test("the restore endpoint applies the tap secret rule before saving") {
        // Wiring pin: restoreTapVersion consults tapRestoreProblem ahead of the
        // TapCronGate / writeVersioned path.
        val rel = "src/main/scala/ai/datris/api/EntityVersionAPIController.scala"
        val file = Seq(java.nio.file.Paths.get(rel), java.nio.file.Paths.get("datrisserver", rel))
            .find(java.nio.file.Files.isRegularFile(_)).getOrElse(fail("EntityVersionAPIController.scala not found"))
        val src = new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)
        val restore = src.substring(src.indexOf("def restoreTapVersion"))
        val check = restore.indexOf("tapRestoreProblem")
        val write = restore.indexOf("TapConfigIO.writeVersioned")
        assert(check > 0 && write > check, "tapRestoreProblem must run before writeVersioned")
    }
}
