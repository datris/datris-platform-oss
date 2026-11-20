package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model._
import com.google.gson.{Gson, JsonParser}
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

/** Version restore of a pipeline whose schema holds a column name that is
  * not plain (kept from schema evolution): the restore is a save checked
  * against the LIVE config, so a version that has the name restores onto a
  * live pipeline that has it, and is refused (400, the validator's message)
  * onto one that does not or onto a deleted pipeline.
  *
  * `EntityVersionAPIController.restorePipelineSnapshot` is called directly,
  * no Spring, auth off, no stores. A restore that passes the name rule is
  * shown by the validator's NEXT refusal: the snapshot also carries a field
  * type the validator refuses (`blob`), checked after the field's name, so
  * nothing reaches a probe or a store. */
class StoredColumnNameRestoreSpec extends AnyFunSuite with BeforeAndAfterEach {

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

    private val gson = new Gson

    private def authOff: HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/pipeline/version/restore")
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => null)
        r
    }

    private def pipeline(fields: String, dest: String): PipelineConfig =
        gson.fromJson(
            s"""{"name":"col_r","source":{"fileAttributes":{"csvAttributes":{"header":true}},"schemaProperties":{"fields":$fields}},""" +
                s""""destination":{"schemaProperties":{"fields":$fields},$dest}}""",
            classOf[PipelineConfig]
        )

    private val postgres = """"database":{"dbName":"datris","schema":"public","table":"col_r","usePostgres":true}"""
    private val parquet = """"objectStore":{"prefixKey":"col_r","fileFormat":"parquet"}"""

    private val withOdd = """[{"name":"id","type":"string"},{"name":"a.b","type":"string"}]"""
    private val withOddAndBadType = """[{"name":"id","type":"string"},{"name":"a.b","type":"string"},{"name":"z","type":"blob"}]"""
    private val plainOnly = """[{"name":"id","type":"string"}]"""

    private def restore(snap: PipelineConfig, live: PipelineConfig): (Int, String) = {
        val out = new EntityVersionAPIController().restorePipelineSnapshot("col_r", 1, snap, live, authOff)
        (out.getStatusCode.value, JsonParser.parseString(out.getBody).getAsJsonObject.get("error").getAsString)
    }

    private val nameRefusal = "Column name: a.b is invalid.  Valid characters are a-z, 0-9 and _"

    test("a version with a kept name restores past the name rule onto a live pipeline that has the name") {
        for (dest <- Seq(postgres, parquet)) {
            val (status, error) = restore(pipeline(withOddAndBadType, dest), pipeline(withOdd, dest))
            assert(status == 400, error)
            assert(error.startsWith("Invalid field type passed: blob"), "the name rule passed and the next check refused: " + error)
        }
    }

    test("a version with the name onto a live pipeline without it, or onto a deleted pipeline, is refused with the name rule's message") {
        for (dest <- Seq(postgres, parquet)) {
            assert(restore(pipeline(withOddAndBadType, dest), pipeline(plainOnly, dest)) == ((400, nameRefusal)))
            assert(restore(pipeline(withOddAndBadType, dest), null) == ((400, nameRefusal)))
        }
    }

    test("a version whose destination differs from the live one checks the kept names against the version's destination") {
        val withTick = """[{"name":"id","type":"string"},{"name":"x`y","type":"string"},{"name":"z","type":"blob"}]"""
        val liveTick = """[{"name":"id","type":"string"},{"name":"x`y","type":"string"}]"""
        val orc = """"objectStore":{"prefixKey":"col_r","fileFormat":"orc"}"""
        val (status, error) = restore(pipeline(withTick, orc), pipeline(liveTick, parquet))
        assert(status == 400 && error.contains("cannot be kept for the new destination") && error.contains("backtick"), error)
        // the same destination as the live pipeline: the name rule passes and the next check refuses
        val (s2, e2) = restore(pipeline(withTick, parquet), pipeline(liveTick, parquet))
        assert(s2 == 400 && e2.startsWith("Invalid field type passed: blob"), e2)
    }
}
