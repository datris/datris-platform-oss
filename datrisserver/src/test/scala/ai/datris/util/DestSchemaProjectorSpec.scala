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

/** `DestSchemaProjector` (plans/stories/streaming-pipeline-phase2.md, step 3):
  *  the one copy of the source→destination column projection that today is
  *  duplicated in `PostgresLoader.projectRowsToDestSchema`,
  *  `SnowflakeLoader.projectRowsToDestSchema` and
  *  `DatabricksLoader.projectRowsToDestSchema`, lifted to an
  *  `Iterator[String] => Iterator[String]`.
  *
  *  Expected seam:
  *  {{{
  *  object DestSchemaProjector {
  *      def project(jobContext: JobContext, rows: Iterator[String]): Iterator[String]
  *  }
  *  }}}
  *
  *  `legacyProject` below is the pre-change Postgres algorithm copied verbatim
  *  (Snowflake and Databricks are the same minus the "Projecting ..." info
  *  line), operating on a List. Every case asserts the projector's output is
  *  identical to it, and that the "Dropped columns" status message is the same.
  */
class DestSchemaProjectorSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root: Path = Files.createTempDirectory("dest-schema-projector-spec")

    private def env: DatrisEnvironment = DatrisEnvironment(
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

    override def beforeAll(): Unit = TenantContext.set(env)
    override def afterAll(): Unit = TenantContext.clear()

    private class RecordingStatusUtil extends StatusUtil {
        val messages = new ListBuffer[(String, String)]()
        override def overrideProcessName(processName: String): Unit = ()
        override def info(state: String, description: String): Unit = messages += ((state, description))
        override def warn(state: String, description: String): Unit = messages += ((state, description))
        override def error(state: String, description: String): Unit = messages += ((state, description))
        def dropped: List[String] = messages.map(_._2).filter(_.startsWith("Dropped columns")).toList
    }

    private def fields(names: String*): java.util.List[SchemaField] =
        new java.util.ArrayList[SchemaField](names.map(n => SchemaField(n, "string")).asJava)

    private def config(source: Seq[String], dest: Seq[String], delimiter: String): PipelineConfig =
        PipelineConfig(
            name = "proj",
            source = Source(
                schemaProperties = SchemaProperties("db", fields(source: _*)),
                fileAttributes = FileAttributes(csvAttributes = CsvAttributes(delimiter = delimiter))
            ),
            destination = Destination(
                schemaProperties = SchemaProperties("db", fields(dest: _*)),
                database = Database(usePostgres = true, table = "t")
            )
        )

    private def ctx(cfg: PipelineConfig, header: List[String], rows: List[String], delimiter: String, status: StatusUtil): JobContext =
        JobContext(
            pipelineToken = "job-token-1",
            metadata = PipelineMetadata("proj", "rows.csv", "/tmp/rows.csv", "pub-1", bulkUpload = false),
            data = Data(rows.map(_.length.toLong + 1L).sum, header, cfg.source.schemaProperties.fields.asScala.toList, rows, null, delimiter = delimiter),
            config = cfg,
            pipelineProperties = null,
            state = null,
            thread = null,
            statusUtil = status
        )

    /** PostgresLoader.projectRowsToDestSchema as it was before Phase 2, verbatim
      * except that `config`, `jobContext.data.header` and `statusUtil` are passed in. */
    private def legacyProject(jobContext: JobContext, rows: List[String]): List[String] = {
        val config = jobContext.config
        val statusUtil = jobContext.statusUtil
        val sourceFields = config.source.schemaProperties.fields.asScala.toList
        val destFields = config.destination.schemaProperties.fields.asScala.toList

        val delimiter = if (config.source.fileAttributes != null && config.source.fileAttributes.csvAttributes != null)
            config.source.fileAttributes.csvAttributes.delimiter
        else
            ","

        if (sourceFields.size == destFields.size && sourceFields.map(_.name.toLowerCase) == destFields.map(_.name.toLowerCase)) {
            return rows
        }

        val sourceIndex: Map[String, Int] = {
            if (jobContext.data.header != null && jobContext.data.header.nonEmpty)
                jobContext.data.header.zipWithIndex.map { case (name, idx) => name.toLowerCase -> idx }.toMap
            else
                sourceFields.zipWithIndex.map { case (f, idx) => f.name.toLowerCase -> idx }.toMap
        }

        val projectedDest = destFields.filter(f => sourceIndex.contains(f.name.toLowerCase))
        val destColumnIndices = projectedDest.map(f => sourceIndex(f.name.toLowerCase))

        if (projectedDest.size < destFields.size) {
            val missing = destFields.filterNot(f => sourceIndex.contains(f.name.toLowerCase)).map(_.name)
            statusUtil.info("processing", "Dropped columns (will be NULL in destination): " + missing.mkString(", "))
        }

        rows.map { row =>
            val columns = row.split(delimiter, -1).toList
            destColumnIndices.map(idx => if (idx < columns.size) columns(idx) else "").mkString(delimiter)
        }
    }

    /** Run both and return (projector output, legacy output, projector status, legacy status). */
    private def both(
        source: Seq[String],
        dest: Seq[String],
        header: List[String],
        rows: List[String],
        delimiter: String = ","
    ): (List[String], List[String], RecordingStatusUtil, RecordingStatusUtil) = {
        val cfg = config(source, dest, delimiter)
        val newStatus = new RecordingStatusUtil
        val oldStatus = new RecordingStatusUtil
        val projected = DestSchemaProjector.project(ctx(cfg, header, rows, delimiter, newStatus), rows.iterator).toList
        val legacy = legacyProject(ctx(cfg, header, rows, delimiter, oldStatus), rows)
        (projected, legacy, newStatus, oldStatus)
    }

    // Acceptance 3: matched
    test("matched schemas: rows pass through untouched and no Dropped-columns message is sent") {
        val rows = List("1,alice,10", "2,bob,20", "3,\"c,d\",30")
        val (projected, legacy, newStatus, _) = both(Seq("id", "name", "age"), Seq("id", "name", "age"), List("id", "name", "age"), rows)
        assert(projected == rows)
        assert(projected == legacy)
        assert(newStatus.dropped.isEmpty)
    }

    // Acceptance 3: matched (case-insensitive, as the loaders compare lowercase names)
    test("matched schemas differing only in case pass through untouched") {
        val rows = List("1,alice", "2,bob")
        val (projected, legacy, newStatus, _) = both(Seq("ID", "Name"), Seq("id", "name"), List("ID", "Name"), rows)
        assert(projected == rows)
        assert(projected == legacy)
        assert(newStatus.dropped.isEmpty)
    }

    // Acceptance 3: reordered
    test("reordered destination columns are selected by the data header, in destination order") {
        val rows = List("1,alice,10", "2,bob,20", "3,,30")
        val (projected, legacy, _, _) = both(Seq("id", "name", "age"), Seq("age", "id", "name"), List("id", "name", "age"), rows)
        assert(projected == List("10,1,alice", "20,2,bob", "30,3,"))
        assert(projected == legacy)
    }

    // Acceptance 3: reordered, header absent — falls back to source-field order
    test("with no data header the source schema order is used for column lookup") {
        val rows = List("1,alice,10", "2,bob,20")
        val (projected, legacy, _, _) = both(Seq("id", "name", "age"), Seq("name", "id"), null, rows)
        assert(projected == List("alice,1", "bob,2"))
        assert(projected == legacy)
    }

    // Acceptance 3: dropped column (destination has a column the source does not)
    test("a destination column missing from the source is dropped and reported once, exactly as before") {
        val rows = List("1,alice", "2,bob")
        val (projected, legacy, newStatus, oldStatus) =
            both(Seq("id", "name"), Seq("id", "name", "created_at", "region"), List("id", "name"), rows)
        assert(projected == List("1,alice", "2,bob"))
        assert(projected == legacy)
        assert(oldStatus.dropped == List("Dropped columns (will be NULL in destination): created_at, region"))
        assert(newStatus.dropped == oldStatus.dropped, s"Dropped-columns message differs: ${newStatus.dropped} vs ${oldStatus.dropped}")
    }

    // Acceptance 3: source column not in destination is left out; short rows pad with ""
    test("source columns absent from the destination are omitted and short rows pad with empty strings") {
        val rows = List("1,alice,10,x", "2,bob", "3")
        val (projected, legacy, newStatus, _) = both(Seq("id", "name", "age", "extra"), Seq("id", "age"), List("id", "name", "age", "extra"), rows)
        assert(projected == List("1,10", "2,", "3,"))
        assert(projected == legacy)
        assert(newStatus.dropped.isEmpty)
    }

    // Acceptance 3: non-comma delimiter
    test("a non-comma delimiter is used for both splitting and joining") {
        val rows = List("1;alice;10", "2;bob;20")
        val (projected, legacy, _, _) = both(Seq("id", "name", "age"), Seq("age", "name"), List("id", "name", "age"), rows, delimiter = ";")
        assert(projected == List("10;alice", "20;bob"))
        assert(projected == legacy)
    }

    // Acceptance 3: non-comma delimiter, matched schema is a pass-through regardless of delimiter
    test("a non-comma delimiter with matched schemas passes rows through untouched") {
        val rows = List("1;alice", "2;b,ob")
        val (projected, legacy, _, _) = both(Seq("id", "name"), Seq("id", "name"), List("id", "name"), rows, delimiter = ";")
        assert(projected == rows)
        assert(projected == legacy)
    }

    // Review finding 5: the projector splits the delimiter literally. The
    // deleted loaders used String.split(delimiter) — a regex — so "|" split on
    // every character; that output was wrong, so the legacy oracle is not the
    // reference here.
    test("a regex-special delimiter (|) splits literally, unlike the old regex split") {
        val rows = List("1|alice|10", "2|b.ob|20")
        val cfg = config(Seq("id", "name", "age"), Seq("age", "name"), "|")
        val status = new RecordingStatusUtil
        val projected = DestSchemaProjector.project(ctx(cfg, List("id", "name", "age"), rows, "|", status), rows.iterator).toList
        assert(projected == List("10|alice", "20|b.ob"))
        val legacy = legacyProject(ctx(cfg, List("id", "name", "age"), rows, "|", new RecordingStatusUtil), rows)
        assert(legacy != projected, "the regex split produced per-character garbage; the projector must not reproduce it")
    }

    // Review finding 1: the "No data to load" guard the SQL loaders run before
    // any DDL / TRUNCATE. A delimited payload that came out empty (a row
    // function dropped every row) must be rejected up front.
    test("hasCsvBody is false for an empty delimited payload and true once it has rows") {
        val cfg = config(Seq("id", "name"), Seq("id", "name"), ",")
        val empty = ctx(cfg, List("id", "name"), Nil, ",", new RecordingStatusUtil)
        assert(empty.data.staged.rowCount == 0L)
        assert(!DestSchemaProjector.hasCsvBody(empty))
        assertThrows[DatrisException](DestSchemaProjector.requireCsvBody(empty))
        assertThrows[DatrisException](DestSchemaProjector.csvBody(empty))
        val some = ctx(cfg, List("id", "name"), List("1,a"), ",", new RecordingStatusUtil)
        assert(DestSchemaProjector.hasCsvBody(some))
        val body = DestSchemaProjector.csvBody(some)
        try assert(body.mkString == "1,a")
        finally body.close()
    }

    // The point of the extraction: it must stream, not collect.
    test("projection is lazy: an unbounded row iterator can be consumed one element at a time") {
        val cfg = config(Seq("id", "name"), Seq("name", "id"), ",")
        val status = new RecordingStatusUtil
        val jc = ctx(cfg, List("id", "name"), List("0,seed"), ",", status)
        val endless = Iterator.from(1).map(i => i + ",n" + i)
        val first3 = DestSchemaProjector.project(jc, endless).take(3).toList
        assert(first3 == List("n1,1", "n2,2", "n3,3"))
    }
}
