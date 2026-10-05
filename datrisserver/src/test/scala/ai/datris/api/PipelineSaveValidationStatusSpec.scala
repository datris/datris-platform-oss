package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.{Gson, JsonParser}
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.mock
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

/** Story: an invalid pipeline config is a 400, not a 500
  * (plans/stories/pipeline-save-validation-400.md), Step 2.
  *
  * `PipelineAPIController.putPipeline` is called directly, no Spring. API
  * keys are off in the installed environment, and every config here fails
  * `PipelineValidatorUtil.validate`, which runs before any Postgres probe or
  * config-store read, so no live service is touched. Today the catch-all
  * answers 500; the story moves validator refusals to 400 with the same
  * `{"error": <message>}` body. */
class PipelineSaveValidationStatusSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val env: DatrisEnvironment = DatrisEnvironment(
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
        mongoDbConfig = null,
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
        multiTenant = false
    )

    private var savedValues: DatrisEnvironment = _

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        DatrisEnvironment.init(env)
        TenantContext.set(env)
    }

    override def afterEach(): Unit = {
        TenantContext.clear()
        DatrisEnvironment.values = savedValues
    }

    private val gson = new Gson()
    private val postgres = """"database":{"dbName":"datris","schema":"public","table":"e2e_bad","usePostgres":true}"""

    private def save(json: String): (Int, String) = {
        val r = new PipelineAPIController().putPipeline(null, null, gson.fromJson(json, classOf[PipelineConfig]), mock(classOf[HttpServletRequest]))
        (r.getStatusCode.value, r.getBody)
    }

    private def assert400(json: String, expected: String): Unit = {
        val (status, body) = save(json)
        assert(status == 400, s"status $status, body $body")
        val o = JsonParser.parseString(body).getAsJsonObject
        assert(o.has("error"), body)
        assert(o.get("error").getAsString == expected, body)
    }

    test("POST /pipeline with an unknown (reserved) protect method returns 400 with the validator's message") {
        assert400(
            s"""{"name":"e2e_bad","source":{"schemaProperties":{"fields":[{"name":"mrn","type":"string","protect":{"method":"fpe"}}]},"fileAttributes":{"csvAttributes":{"header":true}}},"destination":{$postgres}}""",
            "Field 'mrn': protect.method 'fpe' is not yet supported"
        )
    }

    test("POST /pipeline with a duplicate destination field returns 400 with the validator's message") {
        assert400(
            s"""{"name":"e2e_bad","source":{"fileAttributes":{"csvAttributes":{"header":true}},"schemaProperties":{"fields":[{"name":"id","type":"string"}]}},
               |"destination":{$postgres,"schemaProperties":{"fields":[{"name":"id","type":"string"},{"name":"ID","type":"string"}]}}}""".stripMargin,
            "Duplicate field name(s) found in destination schema: id"
        )
    }

    test("POST /pipeline for a preset pipeline with an unprotected identifier returns 400 with the validator's message") {
        assert400(
            s"""{"name":"e2e_bad","source":{"fileAttributes":{"csvAttributes":{"header":true}},"schemaProperties":{"fields":[{"name":"mrn","type":"string","protect":{"method":"hmac"}},{"name":"phone","type":"string"}]}},
               |"destination":{$postgres},"protection":{"preset":"hipaa-safe-harbor"}}""".stripMargin,
            "Preset 'hipaa-safe-harbor': field 'phone' looks like phone and has no protection. Add protect to it or list it under protection.presetExempt"
        )
    }
}
