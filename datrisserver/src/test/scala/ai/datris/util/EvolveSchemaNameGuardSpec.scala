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

    test("an ORC object store refuses a new backticked column before persist; a dotted one is added") {
        val fs = fields("id", "amount")
        val cfg = PipelineConfig(
            name = "col_orc",
            source = Source(schemaProperties = SchemaProperties("db", fs), fileAttributes = FileAttributes(csvAttributes = CsvAttributes())),
            destination =
                Destination(schemaProperties = SchemaProperties("db", fields("id", "amount")), objectStore = ObjectStore(prefixKey = "col", fileFormat = "orc"))
        )
        val su = new LevelStatusUtil
        val persisted = ListBuffer[PipelineConfig]()
        val e = intercept[DatrisException](DataUtil.evolveSchema(List("id", "amount", "x`y"), cfg, su, c => persisted += c))
        assert(e.getMessage.contains("col_orc") && e.getMessage.contains("x`y"), e.getMessage)
        assert(persisted.isEmpty && su.events.isEmpty)

        val (evolved, _, _, _) = DataUtil.evolveSchema(List("id", "amount", "a.b"), cfg, new LevelStatusUtil, c => persisted += c)
        assert(evolved.source.schemaProperties.fields.asScala.map(_.name).toList == List("id", "amount", "a.b"))
        assert(persisted.size == 1)
    }

    // ---- plans/stories/object-store-load-missing-columns.md, Decisions 2, 3, 5 ----
    //  A file that lacks an ordinary schema column loads (the column is null).
    //  A file that lacks an Iceberg merge key (`destination.objectStore.keyFields`)
    //  or a `partitionBy` column fails here, before staging, naming the column.

    private def objectStoreConfig(objectStore: ObjectStore, names: String*): PipelineConfig =
        PipelineConfig(
            name = "os_cols",
            source = Source(schemaProperties = SchemaProperties("db", fields(names: _*)), fileAttributes = FileAttributes(csvAttributes = CsvAttributes())),
            destination = Destination(schemaProperties = SchemaProperties("db", fields(names: _*)), objectStore = objectStore)
        )

    private def keyList(ks: String*): java.util.List[String] = new java.util.ArrayList[String](ks.asJava)

    private def mergeOn(keys: String*): ObjectStore =
        ObjectStore(prefixKey = "os_cols", fileFormat = "iceberg", writeMode = "merge", keyFields = keyList(keys: _*))

    private val nullLine = "CSV is missing columns (will be NULL in destination): "

    test("a missing object-store merge key fails with the missing required key message and persists nothing") {
        // stored lower-case as the validator persists them, and a caller-cased one
        for (keys <- Seq(Seq("id"), Seq("ID"))) {
            val su = new LevelStatusUtil
            val persisted = ListBuffer[PipelineConfig]()
            val cfg = objectStoreConfig(mergeOn(keys: _*), "id", "name", "city")
            val e = intercept[DatrisException](DataUtil.evolveSchema(List("name", "city"), cfg, su, c => persisted += c))
            assert(e.getMessage.contains("CSV is missing required key field(s): id"), keys + ": " + e.getMessage)
            assert(persisted.isEmpty, keys + ": " + persisted)
            assert(!su.events.exists(_._2.contains("will be NULL")), keys + ": no will-be-NULL line for a refused file: " + su.events)
            assert(cfg.source.schemaProperties.fields.asScala.map(_.name).toList == List("id", "name", "city"))
        }
    }

    test("a missing partition column fails naming it and persists nothing") {
        for (fmt <- Seq("parquet", "orc", "iceberg")) {
            val su = new LevelStatusUtil
            val persisted = ListBuffer[PipelineConfig]()
            val cfg = objectStoreConfig(ObjectStore(prefixKey = "os_cols", fileFormat = fmt, partitionBy = keyList("City")), "id", "name", "city")
            val e = intercept[DatrisException](DataUtil.evolveSchema(List("id", "name"), cfg, su, c => persisted += c))
            assert(e.getMessage.toLowerCase.contains("partition column"), fmt + ": names the kind: " + e.getMessage)
            assert(e.getMessage.toLowerCase.contains("city"), fmt + ": names the column: " + e.getMessage)
            assert(persisted.isEmpty, fmt + ": " + persisted)
            assert(!su.events.exists(_._2.contains("will be NULL")), fmt + ": " + su.events)
        }
    }

    test("a missing ordinary column on an object-store pipeline is returned in missingColumns with the will-be-NULL line") {
        // merge key and partition column both present; only `note` is missing
        val stores = Seq(
            ObjectStore(prefixKey = "os_cols", fileFormat = "parquet"),
            ObjectStore(prefixKey = "os_cols", fileFormat = "orc", partitionBy = keyList("city")),
            ObjectStore(prefixKey = "os_cols", fileFormat = "iceberg", writeMode = "merge", keyFields = keyList("id"), partitionBy = keyList("city"))
        )
        stores.foreach { os =>
            val su = new LevelStatusUtil
            val persisted = ListBuffer[PipelineConfig]()
            val cfg = objectStoreConfig(os, "id", "name", "city", "note")
            val (evolved, schemaColumns, presentColumns, missingColumns) =
                DataUtil.evolveSchema(List("ID", "city", "name"), cfg, su, c => persisted += c)
            assert(schemaColumns == List("id", "name", "city", "note"), os.toString)
            assert(presentColumns == List("id", "name", "city"), os.toString)
            assert(missingColumns == List("note"), os.toString)
            assert(su.events.toList == List(("info", nullLine + "note")), os + ": " + su.events)
            assert(persisted.isEmpty && (evolved eq cfg), os.toString)
        }
    }

    test("pin: an object-store file with every column, key and partition column evolves nothing and reports nothing") {
        val cfg = objectStoreConfig(
            ObjectStore(prefixKey = "os_cols", fileFormat = "iceberg", writeMode = "merge", keyFields = keyList("id"), partitionBy = keyList("city")),
            "id",
            "name",
            "city"
        )
        val su = new LevelStatusUtil
        val persisted = ListBuffer[PipelineConfig]()
        val (_, schemaColumns, presentColumns, missingColumns) = DataUtil.evolveSchema(List("id", "name", "city"), cfg, su, c => persisted += c)
        assert(schemaColumns == List("id", "name", "city") && presentColumns == schemaColumns && missingColumns.isEmpty)
        assert(su.events.isEmpty && persisted.isEmpty)
    }

    test("a database pipeline's missing-key and missing-column behaviour is unchanged") {
        def pg(keys: String*): PipelineConfig = {
            val base = postgresConfig("id", "name", "city")
            base.copy(destination = base.destination.copy(database = base.destination.database.copy(keyFields = keyList(keys: _*))))
        }
        val e = intercept[DatrisException](DataUtil.evolveSchema(List("name", "city"), pg("id"), new LevelStatusUtil, _ => ()))
        assert(
            e.getMessage == "CSV is missing required key field(s): id. CSV columns: name, city. Expected columns: id, name, city",
            e.getMessage
        )
        val su = new LevelStatusUtil
        val (_, _, present, missing) = DataUtil.evolveSchema(List("id", "name"), pg("id"), su, _ => ())
        assert(present == List("id", "name") && missing == List("city"))
        assert(su.events.toList == List(("info", nullLine + "city")), su.events.toString)
        // no keys at all: a missing column is only reported
        val (_, _, _, missing2) = DataUtil.evolveSchema(List("name"), postgresConfig("id", "name"), new LevelStatusUtil, _ => ())
        assert(missing2 == List("id"))
    }

    test("object-store keyFields refuse a file only on an Iceberg merge; stray keys elsewhere load the column as null") {
        val stores = Seq(
            ObjectStore(prefixKey = "os_cols", fileFormat = "iceberg", writeMode = "append", keyFields = keyList("id")),
            ObjectStore(prefixKey = "os_cols", fileFormat = "iceberg", keyFields = keyList("id")),
            ObjectStore(prefixKey = "os_cols", fileFormat = "parquet", writeMode = "merge", keyFields = keyList("id")),
            ObjectStore(prefixKey = "os_cols", fileFormat = "orc", keyFields = keyList("id")),
            ObjectStore(prefixKey = "os_cols", keyFields = keyList("id"))
        )
        stores.foreach { os =>
            val su = new LevelStatusUtil
            val persisted = ListBuffer[PipelineConfig]()
            val (_, _, present, missing) = DataUtil.evolveSchema(List("name", "city"), objectStoreConfig(os, "id", "name", "city"), su, c => persisted += c)
            assert(present == List("name", "city") && missing == List("id"), os.toString)
            assert(su.events.toList == List(("info", nullLine + "id")), os + ": " + su.events)
            assert(persisted.isEmpty, os.toString)
        }
        // a new column in the same refused file is not persisted either
        val persisted = ListBuffer[PipelineConfig]()
        val e = intercept[DatrisException](
            DataUtil.evolveSchema(
                List("name", "city", "extra"),
                objectStoreConfig(mergeOn("id"), "id", "name", "city"),
                new LevelStatusUtil,
                c => persisted += c
            )
        )
        assert(
            e.getMessage == "CSV is missing required key field(s): id. CSV columns: name, city, extra. Expected columns: id, name, city, extra",
            e.getMessage
        )
        assert(persisted.isEmpty, persisted.toString)
    }
}
