package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** Story: Column names from ingested data: checked once at ingest, quoted at
  * every destination, never able to change what a load does
  * (plans/stories/ingest-column-name-escaping.md), Decision 5.
  *
  * `TapRunner.jsonToCsv` (JSON-object records): the header line's cells go
  * through the same quoting the value cells use (a cell holding the
  * delimiter, `"`, `\n` or `\r` is wrapped in `"` with `"` doubled). Plain
  * keys are written exactly as before; keys are never normalised.
  * {{{
  * private[util] def jsonToCsv(staged: StagedPayload, delimiter: String = ","): StagedPayload
  * }}} */
class TapJsonHeaderQuotingSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root: Path = Files.createTempDirectory("tap-json-header-quoting-spec")

    private def testEnv: DatrisEnvironment = DatrisEnvironment(
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
        multiTenant = false,
        tempDir = root.toString
    )

    override def beforeAll(): Unit = TenantContext.set(testEnv)
    override def afterAll(): Unit = TenantContext.clear()

    private def drop(staged: StagedPayload): Unit =
        if (staged != null && staged.path != null) {
            val p = Paths.get(staged.path)
            Files.deleteIfExists(p)
            StagingArea.delete(p.getParent.getFileName.toString)
        }

    /** Stage `json` (a JSON array of objects) as the tap lane does, project it
      * with jsonToCsv, and return (raw file text, CSV records). */
    private def project(json: String, delimiter: String): (String, List[String]) = {
        val staged = PayloadStager.stageJson("spec-tap-header", new java.io.StringReader(json))
        try {
            val csv = TapRunner.jsonToCsv(staged, delimiter)
            try {
                val text = new String(Files.readAllBytes(Paths.get(csv.path)), StandardCharsets.UTF_8)
                val it = StagedRows.delimited(csv.path, delimiter)
                val records =
                    try it.toList
                    finally it.close()
                (text, records)
            } finally drop(csv)
        } finally drop(staged)
    }

    test("plain keys produce the header line the v1.60.0 code built") {
        // v1.60.0: columns.mkString(delimiter), key union in first-seen order.
        val json = """[{"id": 1, "EPS Estimate": 1.5, "a.b": "x"}, {"id": 2, "café-total": 3, "Mixed_Case": true}]"""
        val (text, records) = project(json, ",")
        assert(text.startsWith("id,EPS Estimate,a.b,café-total,Mixed_Case\n"), text)
        assert(records.head == "id,EPS Estimate,a.b,café-total,Mixed_Case")
        assert(records.size == 3)

        val (pipeText, _) = project("""[{"id": 1, "unit price": 2, "x;y": 3}]""", "|")
        assert(pipeText.startsWith("id|unit price|x;y\n"), "a key holding a character that is not the delimiter is unchanged: " + pipeText)
    }

    test("a key holding the delimiter, a double quote or a line break is one quoted cell and validatedCsvHeader returns as many columns as there are keys") {
        val keys = List("id", "a,b", "say \"hi\"", "line1\nline2", "cr\rhere", "plain")
        val obj = new com.google.gson.JsonObject
        keys.zipWithIndex.foreach { case (k, i) => obj.addProperty(k, i) }
        val (text, records) = project("[" + obj.toString + "]", ",")
        assert(text.startsWith("id,\"a,b\",\"say \"\"hi\"\"\",\"line1\nline2\",\"cr\rhere\",plain\n"), "header cells quoted as value cells are: " + text)
        assert(records.size == 2, "one header record and one data record: " + records)
        val header = DataUtil.validatedCsvHeader(records.head, ",", "tap_pipe")
        assert(header.size == keys.size, "as many columns as keys: " + header)
        assert(header == keys.map(_.toLowerCase), header)
        assert(records(1) == "0,1,2,3,4,5")

        // the same with a non-comma delimiter
        val pipeKeys = List("id", "a|b", "q\"x")
        val pipeObj = "{\"id\": 1, \"a|b\": 2, \"q\\\"x\": 3}"
        val (pipeText, pipeRecords) = project("[" + pipeObj + "]", "|")
        assert(pipeText.startsWith("id|\"a|b\"|\"q\"\"x\"\n"), pipeText)
        assert(DataUtil.validatedCsvHeader(pipeRecords.head, "|", "tap_pipe") == pipeKeys)
    }
}
