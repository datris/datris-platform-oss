package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.TapConfig
import org.scalatest.funsuite.AnyFunSuite

/** A tap may not reference a server-managed secret (SecretNames.ServerManaged):
  * POST /tap refuses it with 400 via serverManagedSecretProblem, and
  * TapScriptRunner refuses it again at run time (SecretNames.requireNotServerManaged,
  * covered in SecretNamesSpec) for taps saved before the check. */
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
}
