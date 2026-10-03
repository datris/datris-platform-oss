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
  * alias another secret. */
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
        ""
    )
    private val safe = Seq("field-protection", "ai-primary", "my secret", "tap.github_1")

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
}
