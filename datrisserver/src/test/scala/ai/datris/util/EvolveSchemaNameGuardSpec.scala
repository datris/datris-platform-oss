package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/** Story: Column names from ingested data: checked once at ingest, quoted at
  * every destination, never able to change what a load does
  * (plans/stories/ingest-column-name-escaping.md), Decisions 2 and 3.
  *
  * `DataUtil.evolveSchema` calls `IngestColumnNames.problem` on the new
  * columns first, before the "Schema evolved" log line, before any status
  * line and before `persist`, and throws a `DatrisException` with its text.
  * The `persist` parameter (default `PipelineConfigIO.write`) is the seam:
  * {{{
  * def evolveSchema(sourceColumns: List[String], storedConfig: PipelineConfig, statusUtil: StatusUtil,
  *                  persist: PipelineConfig => Unit = PipelineConfigIO.write)
  *     : (PipelineConfig, List[String], List[String], List[String])
  * }}}
  * Header cells already in the stored schema are never examined. */
class EvolveSchemaNameGuardSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root: Path = Files.createTempDirectory("evolve-schema-name-guard-spec")

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

    private class LevelStatusUtil extends StatusUtil {
        val events: ListBuffer[(String, String)] = ListBuffer[(String, String)]()
        override def info(state: String, description: String): Unit = events += (("info", description))
        override def warn(state: String, description: String): Unit = events += (("warn", description))
        override def error(state: String, description: String): Unit = events += (("error", description))
    }

    private def fields(names: String*): java.util.List[SchemaField] =
        new java.util.ArrayList[SchemaField](names.map(n => SchemaField(n, "string")).asJava)

    private def postgresConfig(names: String*): PipelineConfig =
        PipelineConfig(
            name = "col_pg",
            source = Source(schemaProperties = SchemaProperties("db", fields(names: _*)), fileAttributes = FileAttributes(csvAttributes = CsvAttributes())),
            destination = Destination(
                schemaProperties = SchemaProperties("db", fields(names: _*)),
                database = Database(dbName = "datris", schema = "public", table = "col_pg", usePostgres = true)
            )
        )

    test("a refused new column throws before persist is called and before any status line") {
        val refused = Seq("bad\tname", "nul\u0000name", "x" * 64, " amount", "dup")
        refused.foreach { cell =>
            val header = if (cell == "dup") List("id", "amount", "dup", "DUP") else List("id", "amount", cell)
            val su = new LevelStatusUtil
            val persisted = ListBuffer[PipelineConfig]()
            val cfg = postgresConfig("id", "amount")
            val e = intercept[DatrisException](DataUtil.evolveSchema(header, cfg, su, c => persisted += c))
            assert(e.getMessage != null && e.getMessage.nonEmpty)
            assert(persisted.isEmpty, "persist must not be called for " + header + ": " + persisted)
            assert(su.events.isEmpty, "no status line before the refusal for " + header + ": " + su.events)
            // the stored config object is untouched
            assert(cfg.source.schemaProperties.fields.asScala.map(_.name).toList == List("id", "amount"))
            assert(cfg.source.schemaProperties.schemaVersion == 1)
        }
    }

    test("a header made only of stored names, one of them 80 bytes long, evolves nothing and does not throw") {
        val long = "l" * 80
        val su = new LevelStatusUtil
        val persisted = ListBuffer[PipelineConfig]()
        val cfg = postgresConfig("id", long, "amount")
        val (evolved, schemaColumns, _, _) = DataUtil.evolveSchema(List("id", long, "amount"), cfg, su, c => persisted += c)
        assert(schemaColumns == List("id", long, "amount"))
        assert(evolved.source.schemaProperties.fields.asScala.map(_.name).toList == List("id", long, "amount"))
        assert(evolved.source.schemaProperties.schemaVersion == cfg.source.schemaProperties.schemaVersion)
        assert(persisted.isEmpty, "nothing evolved, nothing written")
        // and a NEW plain column beside the stored 80-byte one still evolves
        val (evolved2, _, _, _) = DataUtil.evolveSchema(List("id", long, "amount", "note"), cfg, new LevelStatusUtil, c => persisted += c)
        assert(evolved2.source.schemaProperties.fields.asScala.map(_.name).toList == List("id", long, "amount", "note"))
        assert(persisted.size == 1)
    }

    test("a new column with a space and a double quote is added as a string field and persisted once") {
        val odd = "unit \"price\""
        val su = new LevelStatusUtil
        val persisted = ListBuffer[PipelineConfig]()
        val cfg = postgresConfig("id", "amount")
        val (evolved, schemaColumns, _, _) = DataUtil.evolveSchema(List("id", "amount", odd), cfg, su, c => persisted += c)
        assert(schemaColumns == List("id", "amount", odd))
        assert(evolved.source.schemaProperties.fields.asScala.toList.last == SchemaField(odd, "string"))
        assert(evolved.destination.schemaProperties.fields.asScala.toList.last == SchemaField(odd, "string"))
        assert(persisted.size == 1, "one write, got " + persisted.size)
        assert(persisted.head.source.schemaProperties.fields.asScala.exists(_.name == odd))
    }

    test("the error names the pipeline's offending header cell") {
        val cfg = postgresConfig("id", "amount")
        val tab = intercept[DatrisException](DataUtil.evolveSchema(List("id", "amount", "bad\tname"), cfg, new LevelStatusUtil, _ => ()))
        assert(tab.getMessage.contains("col_pg"), "names the pipeline: " + tab.getMessage)
        assert(tab.getMessage.toLowerCase.contains("bad\\u0009name"), "names the cell, escaped: " + tab.getMessage)
        assert(!tab.getMessage.contains("\t"), tab.getMessage)

        val tooLong = "x" * 64
        val len = intercept[DatrisException](DataUtil.evolveSchema(List("id", "amount", tooLong), cfg, new LevelStatusUtil, _ => ()))
        assert(len.getMessage.contains("col_pg"), len.getMessage)
        assert(len.getMessage.contains(tooLong), "names the cell: " + len.getMessage)
    }
}
