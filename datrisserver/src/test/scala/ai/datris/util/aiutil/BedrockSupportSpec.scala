package ai.datris.util.aiutil

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.JsonParser
import ai.datris.model.AIConfig
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

class BedrockSupportSpec extends AnyFunSuite {

    private def cfg(endpoint: String, model: String = "anthropic.claude-sonnet-5") =
        AIConfig(provider = "bedrock", endpoint = endpoint, model = model, apiKey = "")

    // ---- invokeEndpoint ----

    test("invokeEndpoint derives the regional runtime URL when endpoint is blank") {
        assert(
            BedrockSupport.invokeEndpoint(cfg(""), "us-east-1") ==
                "https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude-sonnet-5/invoke"
        )
    }

    test("invokeEndpoint URL-encodes versioned model ids") {
        val ep = BedrockSupport.invokeEndpoint(cfg("", "anthropic.claude-opus-4-5-20251101-v1:0"), "us-west-2")
        assert(ep == "https://bedrock-runtime.us-west-2.amazonaws.com/model/anthropic.claude-opus-4-5-20251101-v1%3A0/invoke")
    }

    test("invokeEndpoint appends the model path to a base-URL override") {
        val ep = BedrockSupport.invokeEndpoint(cfg("https://bedrock-runtime.us-gov-west-1.amazonaws.com/"), "us-east-1")
        assert(ep == "https://bedrock-runtime.us-gov-west-1.amazonaws.com/model/anthropic.claude-sonnet-5/invoke")
    }

    test("invokeEndpoint uses a full invoke URL as-is") {
        val full = "https://vpce-abc.bedrock-runtime.us-east-1.vpce.amazonaws.com/model/anthropic.claude-sonnet-5/invoke"
        assert(BedrockSupport.invokeEndpoint(cfg(full), "eu-west-1") == full)
    }

    test("invokeEndpoint rejects a blank model") {
        intercept[ai.datris.model.DatrisException] {
            BedrockSupport.invokeEndpoint(cfg("", model = ""), "us-east-1")
        }
    }

    // ---- transformBodyForInvoke ----

    test("transformBodyForInvoke strips model and stream, adds anthropic_version") {
        val body = """{"model":"anthropic.claude-sonnet-5","stream":true,"max_tokens":4096,"messages":[{"role":"user","content":"hi"}]}"""
        val out = JsonParser.parseString(BedrockSupport.transformBodyForInvoke(body)).getAsJsonObject
        assert(!out.has("model"))
        assert(!out.has("stream"))
        assert(out.get("anthropic_version").getAsString == "bedrock-2023-05-31")
        assert(out.get("max_tokens").getAsInt == 4096)
        assert(out.getAsJsonArray("messages").size() == 1)
    }

    test("transformBodyForInvoke preserves an explicit anthropic_version and passthrough fields") {
        val body = """{"anthropic_version":"custom","system":"s","thinking":{"type":"adaptive"},"messages":[]}"""
        val out = JsonParser.parseString(BedrockSupport.transformBodyForInvoke(body)).getAsJsonObject
        assert(out.get("anthropic_version").getAsString == "custom")
        assert(out.get("system").getAsString == "s")
        assert(out.getAsJsonObject("thinking").get("type").getAsString == "adaptive")
    }

    // ---- mergeDiscovery ----

    private val foundationModels =
        """{"modelSummaries":[
          |  {"modelId":"anthropic.claude-sonnet-5","modelName":"Claude Sonnet 5","providerName":"Anthropic",
          |   "outputModalities":["TEXT"],"inferenceTypesSupported":["ON_DEMAND"],"modelLifecycle":{"status":"ACTIVE"}},
          |  {"modelId":"anthropic.claude-opus-5","modelName":"Claude Opus 5","providerName":"Anthropic",
          |   "outputModalities":["TEXT"],"inferenceTypesSupported":["INFERENCE_PROFILE"],"modelLifecycle":{"status":"ACTIVE"}},
          |  {"modelId":"anthropic.claude-legacy","modelName":"Legacy","providerName":"Anthropic",
          |   "outputModalities":["TEXT"],"inferenceTypesSupported":["ON_DEMAND"],"modelLifecycle":{"status":"LEGACY"}},
          |  {"modelId":"anthropic.claude-orphan","modelName":"Orphan","providerName":"Anthropic",
          |   "outputModalities":["TEXT"],"inferenceTypesSupported":["INFERENCE_PROFILE"],"modelLifecycle":{"status":"ACTIVE"}},
          |  {"modelId":"amazon.nova-pro","modelName":"Nova Pro","providerName":"Amazon",
          |   "outputModalities":["TEXT"],"inferenceTypesSupported":["ON_DEMAND"],"modelLifecycle":{"status":"ACTIVE"}}
          |]}""".stripMargin

    private val inferenceProfiles =
        """{"inferenceProfileSummaries":[
          |  {"inferenceProfileId":"us.anthropic.claude-opus-5","status":"ACTIVE","type":"SYSTEM_DEFINED",
          |   "models":[{"modelArn":"arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-opus-5"}]}
          |]}""".stripMargin

    test("mergeDiscovery: ON_DEMAND models keep their bare modelId") {
        val out = BedrockSupport.mergeDiscovery(foundationModels, List(inferenceProfiles))
        val values = out.asScala.map(_.getAsJsonObject.get("value").getAsString).toList
        assert(values.contains("anthropic.claude-sonnet-5"))
    }

    test("mergeDiscovery: profile-only models resolve to their inference-profile id") {
        val out = BedrockSupport.mergeDiscovery(foundationModels, List(inferenceProfiles))
        val values = out.asScala.map(_.getAsJsonObject.get("value").getAsString).toList
        assert(values.contains("us.anthropic.claude-opus-5"))
        assert(!values.contains("anthropic.claude-opus-5"), "profile-only model must not surface its bare (non-invokable) id")
    }

    test("mergeDiscovery: inactive, non-Anthropic, and profile-less models are dropped") {
        val out = BedrockSupport.mergeDiscovery(foundationModels, List(inferenceProfiles))
        val values = out.asScala.map(_.getAsJsonObject.get("value").getAsString).toList
        assert(!values.exists(_.contains("legacy")))
        assert(!values.exists(_.contains("orphan")))
        assert(!values.exists(_.contains("nova")))
        assert(values.size == 2)
    }

    test("mergeDiscovery: labels come from modelName") {
        val out = BedrockSupport.mergeDiscovery(foundationModels, List(inferenceProfiles))
        val byValue = out.asScala.map(_.getAsJsonObject).map(o => o.get("value").getAsString -> o.get("label").getAsString).toMap
        assert(byValue("anthropic.claude-sonnet-5") == "Claude Sonnet 5")
        assert(byValue("us.anthropic.claude-opus-5") == "Claude Opus 5")
    }

    test("mergeDiscovery tolerates empty payloads") {
        assert(BedrockSupport.mergeDiscovery("{}", Nil).size() == 0)
        assert(BedrockSupport.mergeDiscovery("{}", List("{}")).size() == 0)
    }
}
