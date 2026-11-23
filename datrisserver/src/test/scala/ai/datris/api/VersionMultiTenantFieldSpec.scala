package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.Gson
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: retire the multi-tenant setting (plans/stories/retire-multi-tenant-setting.md),
  * Acceptance bullet 3 and Decision 3.
  *
  * `GET /api/v1/version` keeps its `multiTenant` field, a string, always
  * "false" (deprecated in the API docs), so the UI and outside readers of the
  * field are unaffected. With the setting off every field is as it was.
  * Entry point and environment install as in VersionAPIControllerSpec.
  */
class VersionMultiTenantFieldSpec extends AnyFunSuite with BeforeAndAfterEach {

    private def env(multiTenant: Boolean): DatrisEnvironment = DatrisEnvironment(
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
        multiTenant = multiTenant,
        tapScriptTimeoutSeconds = 300,
        tapScriptTimeoutSecondsSet = false,
        tapRunTimeoutSeconds = 3600,
        tapRunTimeoutSecondsSet = false,
        pipelineMaxPayloadMB = -1,
        useUserAuth = false
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
        assert(response.getStatusCode.value == 200, response.getBody)
        new Gson().fromJson(response.getBody, classOf[java.util.Map[String, String]]).asScala.toMap
    }

    private val fieldsWithTheSettingOff = Set(
        "version",
        "gitHeadCommit",
        "builtAtMillis",
        "environment",
        "multiTenant",
        "hosted",
        "useUserAuth",
        "oidcEnabled",
        "useApiKeys",
        "useAuditLog",
        "useAgentPolicy",
        "recoveryAgentEnabled",
        "postgresDatabase",
        "mongodbDatabase",
        "useTapRunner",
        "pipelineMaxPayloadMB",
        "pipelineMaxPayloadMBSource",
        "tapScriptTimeoutSeconds",
        "tapScriptTimeoutSecondsSource",
        "tapRunTimeoutSeconds",
        "tapRunTimeoutSecondsSource"
    )

    test("with the setting off, multiTenant is present and \"false\" and every other field is unchanged") {
        val json = body(env(multiTenant = false))
        assert(json.get("multiTenant").contains("false"), json)
        assert((json.keySet - "environmentLabel") == fieldsWithTheSettingOff, json.keySet)
        assert(json.get("environment").contains("test"), json)
        assert(json.get("mongodbDatabase").contains("datris"), "the configured MongoDB database: " + json)
        assert(json.get("hosted").contains("false"), json)
        val raw = new Gson().toJson(json.asJava)
        assert(raw.contains("\"multiTenant\":\"false\""), "still a string: " + raw)
    }

    test("multiTenant is always \"false\", whatever the environment flag holds") {
        val json = body(env(multiTenant = true))
        assert(json.get("multiTenant").contains("false"), json)
        assert((json.keySet - "environmentLabel") == fieldsWithTheSettingOff, json.keySet)
    }
}
