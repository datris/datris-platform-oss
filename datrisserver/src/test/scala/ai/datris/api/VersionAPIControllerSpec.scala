package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import ai.datris.util.TapBudgets
import com.google.gson.Gson
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: agents size a tap run against the budgets actually in force
  * (plans/stories/tap-sizing-effective-budgets.md), Step 3.
  *
  * `GET /api/v1/version` lists feature flags only, so an MCP client has no way
  * to read the budgets this install runs with and quotes the documented
  * defaults instead. The story appends six additive string fields, taken from
  * `TapBudgets.effective(DatrisEnvironment.current)`:
  *
  * {{{
  * pipelineMaxPayloadMB, pipelineMaxPayloadMBSource,
  * tapScriptTimeoutSeconds, tapScriptTimeoutSecondsSource,
  * tapRunTimeoutSeconds, tapRunTimeoutSecondsSource
  * }}}
  *
  * All existing fields on this response are strings and none is renamed or
  * removed (story Backward compat), so the spec re-checks `version` and
  * `useTapRunner` — the only two the UI reads.
  *
  * Entry point is the controller method the story cites,
  * `new VersionAPIController().getVersion(null)` (the endpoint is deliberately
  * unauthenticated, so the apiKey argument is ignored). The tenant environment
  * is installed the same way the other controller-level specs do it:
  * `TenantContext.set(env)`, which is what `DatrisEnvironment.current` reads;
  * `DatrisEnvironment.init` is also set because the handler reads a few flags
  * off the global `values`.
  */
class VersionAPIControllerSpec extends AnyFunSuite with BeforeAndAfterEach {

    private def env(
        payloadMB: Int = -1,
        scriptTimeout: Int = 300,
        scriptSet: Boolean = false,
        runTimeout: Int = 3600,
        runSet: Boolean = false
    ): DatrisEnvironment = DatrisEnvironment(
        initialized = true,
        environment = "test",
        fileNotifierQueue = null,
        ttlFileNotifierQueueMessages = 0,
        pipelineTopic = null,
        pipelineTableName = null,
        archivedMetadataTableName = null,
        pipelineStatusTableName = null,
        fileNotifierMessageTableName = null,
        dataPullTableName = null,
        useApiKeys = false,
        apiKeysSecretName = null,
        postgresSecretName = null,
        mongoDbSecretName = null,
        kafkaProducerSecretName = null,
        kafkaConsumerConfig = null,
        mongoDbConfig = MongoDBConfig("mongodb://unused", "datris", "datris"),
        minIOConfig = null,
        activeMQConfig = null,
        aiConfig = null,
        aiEnabled = false,
        embeddingSecretName = null,
        qdrantSecretName = null,
        weaviateSecretName = null,
        milvusSecretName = null,
        chromaSecretName = null,
        pgvectorSecretName = null,
        multiTenant = false,
        tapScriptTimeoutSeconds = scriptTimeout,
        tapScriptTimeoutSecondsSet = scriptSet,
        tapRunTimeoutSeconds = runTimeout,
        tapRunTimeoutSecondsSet = runSet,
        pipelineMaxPayloadMB = payloadMB
    )

    private var savedValues: DatrisEnvironment = _

    override def beforeEach(): Unit = savedValues = DatrisEnvironment.values

    override def afterEach(): Unit = {
        TenantContext.clear()
        DatrisEnvironment.values = savedValues
    }

    private def body(e: DatrisEnvironment): Map[String, String] = {
        DatrisEnvironment.init(e)
        TenantContext.set(e)
        val response = new VersionAPIController().getVersion(null)
        assert(response.getStatusCodeValue == 200, response.getBody)
        val parsed = new Gson().fromJson(response.getBody, classOf[java.util.Map[String, String]])
        parsed.asScala.toMap
    }

    test("the response carries the budgets in force with their sources") {
        val json = body(env(payloadMB = 16384, runSet = true, runTimeout = 7200))
        assert(json.get("pipelineMaxPayloadMB").contains("16384"))
        assert(json.get("pipelineMaxPayloadMBSource").contains("env"))
        assert(json.get("tapRunTimeoutSeconds").contains("7200"))
        assert(json.get("tapRunTimeoutSecondsSource").contains("env"))
        assert(json.get("tapScriptTimeoutSecondsSource").contains("default"))
        assert(json.get("tapScriptTimeoutSeconds").contains("300"))
    }

    test("an install with nothing set reports the documented defaults as defaults") {
        val json = body(env())
        assert(json.get("pipelineMaxPayloadMB").contains("4096"))
        assert(json.get("pipelineMaxPayloadMBSource").contains("default"))
        assert(json.get("tapRunTimeoutSeconds").contains("3600"))
        assert(json.get("tapRunTimeoutSecondsSource").contains("default"))
    }

    test("the six fields agree with TapBudgets.effective and are strings") {
        val e = env(payloadMB = 0, scriptSet = true, scriptTimeout = 900)
        val json = body(e)
        val b = TapBudgets.effective(e)
        assert(json.get("pipelineMaxPayloadMB").contains(b.pipelineMaxPayloadMB.toString))
        assert(json.get("pipelineMaxPayloadMBSource").contains(b.pipelineMaxPayloadMBSource))
        assert(json.get("tapScriptTimeoutSeconds").contains(b.tapScriptTimeoutSeconds.toString))
        assert(json.get("tapScriptTimeoutSecondsSource").contains(b.tapScriptTimeoutSecondsSource))
        assert(json.get("tapRunTimeoutSeconds").contains(b.tapRunTimeoutSeconds.toString))
        assert(json.get("tapRunTimeoutSecondsSource").contains(b.tapRunTimeoutSecondsSource))
        // Additive only: string values, exactly as every existing field.
        val raw = new Gson().toJson(json.asJava)
        assert(raw.contains("\"pipelineMaxPayloadMB\":\"0\""), raw)
    }

    test("existing fields are untouched") {
        val json = body(env(payloadMB = 16384))
        assert(json.get("version").exists(_.nonEmpty))
        assert(json.contains("useTapRunner"))
        assert(json.contains("postgresDatabase"))
        assert(json.contains("mongodbDatabase"))
    }
}
