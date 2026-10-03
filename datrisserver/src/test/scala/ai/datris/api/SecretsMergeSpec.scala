package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: Editing a secret keeps the values you did not change
  * (plans/stories/secrets-edit-preserves-sensitive-fields.md).
  *
  *  `SecretsAPIController.putSecret` merges the incoming JSON body against the
  *  stored secret inline, and today only the literal mask `••••••••` preserves a
  *  stored sensitive value — an empty string is written as a real value and wipes
  *  the credential. Reaching that loop needs Vault, an API-key validator and a
  *  servlet request, so the merge must be lifted into a seam this spec drives
  *  directly (same placement as `QueryAPIController.objectStoreResponseJson`):
  *
  *  {{{
  *  object SecretsAPIController {
  *      // `existing` is the secret currently stored at the path (empty map when
  *      // none). `incoming` is the request body's primitive entries, in order.
  *      // Returns the map that will be handed to SecretsUtil.writeSecret BEFORE
  *      // the codegen apiKey fallback, provider-change clearing and owner-tagging
  *      // steps, which stay in putSecret and are out of scope here.
  *      private[api] def mergeIncoming(
  *          name: String,
  *          existing: Map[String, String],
  *          incoming: Seq[(String, String)]
  *      ): java.util.LinkedHashMap[String, Object]
  *  }
  *  }}}
  *
  *  Rules pinned: for a sensitive key (per the existing `isSensitive` markers —
  *  AWS_SECRET_ACCESS_KEY, AWS_ACCESS_KEY_ID, apiKey all match "key"), an
  *  incoming value that is the mask OR empty/blank preserves a stored non-empty
  *  value; otherwise the incoming value is written. Omission still means removal
  *  for every secret except `ai-keys`, which merges stored keys back. Non-sensitive
  *  keys (REGION) are written verbatim, including `""`.
  */
class SecretsMergeSpec extends AnyFunSuite {

    private val Mask = "••••••••"

    private def merge(name: String, existing: Map[String, String], incoming: (String, String)*): Map[String, String] =
        SecretsAPIController.mergeIncoming(name, existing, incoming.toSeq).asScala.toMap.map { case (k, v) => k -> String.valueOf(v) }

    private val storedS3 = Map(
        "AWS_ACCESS_KEY_ID" -> "AKIA_STORED_ID",
        "AWS_SECRET_ACCESS_KEY" -> "stored-secret-value",
        "_type" -> "tap"
    )

    test("mask preserves a stored sensitive value") {
        val out = merge("throwaway-s3", storedS3, "AWS_ACCESS_KEY_ID" -> Mask, "AWS_SECRET_ACCESS_KEY" -> Mask, "_type" -> "tap")
        assert(out("AWS_ACCESS_KEY_ID") == "AKIA_STORED_ID", out.toString)
        assert(out("AWS_SECRET_ACCESS_KEY") == "stored-secret-value", out.toString)
    }

    test("empty string preserves a stored non-empty sensitive value") {
        val out = merge("throwaway-s3", storedS3, "AWS_ACCESS_KEY_ID" -> "", "AWS_SECRET_ACCESS_KEY" -> "", "REGION" -> "us-east-1", "_type" -> "tap")
        assert(out("AWS_ACCESS_KEY_ID") == "AKIA_STORED_ID", "empty must not blank the stored key: " + out)
        assert(out("AWS_SECRET_ACCESS_KEY") == "stored-secret-value", "empty must not blank the stored secret: " + out)
        assert(out("REGION") == "us-east-1")
    }

    test("blank (whitespace-only) string also preserves a stored sensitive value") {
        val out = merge("throwaway-s3", storedS3, "AWS_SECRET_ACCESS_KEY" -> "   ")
        assert(out("AWS_SECRET_ACCESS_KEY") == "stored-secret-value", out.toString)
    }

    test("empty string stays empty when nothing is stored for that key") {
        val out = merge("throwaway-s3", Map.empty[String, String], "AWS_SECRET_ACCESS_KEY" -> "", "REGION" -> "eu-west-1")
        assert(out.contains("AWS_SECRET_ACCESS_KEY"), "key must still be present: " + out)
        assert(out("AWS_SECRET_ACCESS_KEY") == "", out.toString)
        assert(out("REGION") == "eu-west-1")
    }

    test("empty string stays empty when the stored value is itself empty") {
        val out = merge("throwaway-s3", Map("AWS_SECRET_ACCESS_KEY" -> ""), "AWS_SECRET_ACCESS_KEY" -> "")
        assert(out("AWS_SECRET_ACCESS_KEY") == "", out.toString)
    }

    test("a new non-mask value replaces the stored sensitive value") {
        val out = merge("throwaway-s3", storedS3, "AWS_SECRET_ACCESS_KEY" -> "rotated-secret-value")
        assert(out("AWS_SECRET_ACCESS_KEY") == "rotated-secret-value", out.toString)
    }

    test("an omitted key is absent from the result for a non-ai-keys secret") {
        val out = merge("throwaway-s3", storedS3, "AWS_SECRET_ACCESS_KEY" -> Mask, "_type" -> "tap")
        assert(!out.contains("AWS_ACCESS_KEY_ID"), "omission must mean removal: " + out)
        assert(out("AWS_SECRET_ACCESS_KEY") == "stored-secret-value")
        assert(out("_type") == "tap")
    }

    test("ai-keys still merges omitted stored keys back") {
        val existing = Map("anthropic" -> "sk-ant-stored", "openai" -> "sk-openai-stored")
        val out = merge("ai-keys", existing, "grok" -> "xai-new")
        assert(out("anthropic") == "sk-ant-stored", out.toString)
        assert(out("openai") == "sk-openai-stored", out.toString)
        assert(out("grok") == "xai-new", out.toString)
    }

    test("ai-keys does not merge back a stored key whose value is empty") {
        val existing = Map("anthropic" -> "sk-ant-stored", "openai" -> "")
        val out = merge("ai-keys", existing, "anthropic" -> Mask)
        assert(out("anthropic") == "sk-ant-stored")
        assert(!out.contains("openai"), out.toString)
    }

    test("ai-keys carve-out: empty string clears a stored key and the mask preserves any field") {
        // Story amendment: in the shared per-provider key store an explicit ""
        // is the only removal path (omission merges back), and the Configuration
        // tab's Azure auth-mode switch relies on it; the mask preserves the
        // stored value for ANY field, marker or not.
        val existing = Map("azureApiKey" -> "azure-stored", "awsRegion" -> "us-east-1", "anthropicApiKey" -> "sk-ant-stored")
        val out = merge("ai-keys", existing, "azureApiKey" -> "", "awsRegion" -> Mask)
        assert(out.contains("azureApiKey"), out.toString)
        assert(out("azureApiKey") == "", "empty must clear an ai-keys field: " + out)
        assert(out("awsRegion") == "us-east-1", "mask must preserve a non-marker ai-keys field: " + out)
        assert(out("anthropicApiKey") == "sk-ant-stored", out.toString)
    }

    test("a non-sensitive key (REGION) sent as empty string is written as empty string") {
        val out = merge("throwaway-s3", storedS3 + ("REGION" -> "us-east-1"), "AWS_ACCESS_KEY_ID" -> Mask, "AWS_SECRET_ACCESS_KEY" -> Mask, "REGION" -> "")
        assert(out.contains("REGION"), out.toString)
        assert(out("REGION") == "", "non-sensitive empty must be written verbatim: " + out)
        assert(out("AWS_SECRET_ACCESS_KEY") == "stored-secret-value")
    }

    test("the mask sent for a sensitive key with nothing stored writes nothing for that key") {
        // Today's behaviour, kept: a mask with no backing value has nothing to preserve.
        val out = merge("throwaway-s3", Map.empty[String, String], "apiKey" -> Mask, "REGION" -> "x")
        assert(!out.contains("apiKey"), out.toString)
        assert(out("REGION") == "x")
    }

    test("result preserves incoming key order") {
        val out = SecretsAPIController.mergeIncoming(
            "throwaway-s3",
            storedS3,
            Seq("REGION" -> "r", "AWS_ACCESS_KEY_ID" -> Mask, "AWS_SECRET_ACCESS_KEY" -> "", "_type" -> "tap")
        )
        assert(out.keySet().asScala.toList == List("REGION", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "_type"), out.toString)
    }

    // --- Field protection 5 (e2e): the field-protection key secret ------------

    private val fpHmac = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    private val fpV1 = "4ccbadf2aa11bb22cc33dd44ee55ff6600112233445566778899aabbccb5e0a7"
    private val fpV2 = "af479a6b00112233445566778899aabbccddeeff0011223344556677ff31a789"
    private val storedFp = Map("key" -> fpHmac, "enc.v1" -> fpV1, "enc.v2" -> fpV2, "encCurrent" -> "2")

    test("field-protection: every enc.v<n> and the hmac key are masked on read; encCurrent stays visible") {
        val shown = SecretsAPIController.maskedFields("field-protection", storedFp.toSeq).asScala.toMap
        assert(shown("enc.v1") == Mask && shown("enc.v2") == Mask && shown("key") == Mask, shown.toString)
        assert(shown("encCurrent") == "2", shown.toString)
        Seq(fpHmac, fpV1, fpV2).foreach(v => assert(!shown.values.exists(_.contains(v)), "no key material in the response"))
        // Any future field of that secret is masked too (per-secret rule, not a name marker).
        assert(SecretsAPIController.maskedFields("field-protection", Seq("enc.v9" -> "ab", "somethingNew" -> "cd")).asScala.values.forall(_ == Mask))
        // The per-secret rule does not mask ordinary fields of other secrets.
        assert(SecretsAPIController.maskedFields("other", Seq("encCurrent" -> "2", "region" -> "x")).asScala.toMap == Map("encCurrent" -> "2", "region" -> "x"))
    }

    test("field-protection: an edit that sends the masks back preserves every key") {
        val out = merge("field-protection", storedFp, "key" -> Mask, "enc.v1" -> Mask, "enc.v2" -> Mask, "encCurrent" -> "2")
        assert(out == storedFp, out.toString)
        // A blank box never wipes a key either.
        val blank = merge("field-protection", storedFp, "key" -> "", "enc.v1" -> " ", "enc.v2" -> Mask, "encCurrent" -> "2")
        assert(blank == storedFp, blank.toString)
        // Retiring a version is omitting its field (documented manual step).
        val retired = merge("field-protection", storedFp, "key" -> Mask, "enc.v2" -> Mask, "encCurrent" -> "2")
        assert(retired == storedFp - "enc.v1", retired.toString)
    }
}
