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
}
