package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.mock
import org.scalatest.funsuite.AnyFunSuite

/** GET / PUT / DELETE /secrets/{name} refuse a name that could address a
  * different Vault secret than it names (a decoded `?` or `#` reaches
  * `<env>/field-protection` under another name and would skip every
  * name-keyed rule). The check runs before the API key check and before any
  * secret store call, so the handlers can be driven directly. */
class SecretsNameGuardSpec extends AnyFunSuite {

    private val names = Seq("field-protection?x", "field-protection#x", "field-protection?", "a%3Fb", "..", "a/b", "a\u0001b")

    test("unsafe names are 400 on GET, PUT and DELETE") {
        val c = new SecretsAPIController
        val req = mock(classOf[HttpServletRequest])
        names.foreach { n =>
            val responses = Seq(
                "GET" -> c.getSecret(null, n, req),
                "PUT" -> c.putSecret(null, n, "{\"key\": \"x\"}", req),
                "DELETE" -> c.deleteSecret(null, n, req)
            )
            responses.foreach { case (m, r) =>
                assert(r.getStatusCode.value == 400, s"$m [$n] -> ${r.getStatusCode}")
                assert(r.getBody == "{\"error\": \"Invalid secret name\"}", s"$m [$n] -> ${r.getBody}")
            }
        }
    }

    test("rejectUnsafeName passes ordinary names") {
        Seq("field-protection", "ai-primary", "my secret", "tap.github_1").foreach(n =>
            assert(SecretsAPIController.rejectUnsafeName(n).isEmpty, n)
        )
    }
}
