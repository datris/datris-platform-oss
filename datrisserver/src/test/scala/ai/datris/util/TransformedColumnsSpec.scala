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

/** Columns an AI transformation adds join the destination schema
  * (`TransformedColumns`): typed from the run's values, appended, stored once,
  * and never stored by a rehearsal run. */
class TransformedColumnsSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root: Path = Files.createTempDirectory("transformed-columns-spec")

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

    private def schema(fields: (String, String)*): SchemaProperties =
        SchemaProperties("db", new java.util.ArrayList[SchemaField](fields.map(f => SchemaField(f._1, f._2)).asJava))

    private val source = Seq("city" -> "string", "temp_c" -> "double")

    private def config(objectStore: ObjectStore = null): PipelineConfig =
        PipelineConfig(
            name = "weather",
            source = Source(schemaProperties = schema(source: _*), fileAttributes = FileAttributes(csvAttributes = CsvAttributes(delimiter = ","))),
            destination = Destination(schemaProperties = schema(source: _*), objectStore = objectStore)
        )

    private def ctx(cfg: PipelineConfig, header: List[String], rows: List[String], status: StatusUtil): JobContext = {
        val known = cfg.source.schemaProperties.fields.asScala.map(f => f.name -> f).toMap
        JobContext(
            pipelineToken = "job-token-1",
            metadata = PipelineMetadata("weather", "rows.csv", "/tmp/rows.csv", "pub-1", bulkUpload = false),
            data = Data(1L, header, header.map(h => known.getOrElse(h, SchemaField(h, "string"))), rows, null),
            config = cfg,
            pipelineProperties = null,
            state = null,
            thread = null,
            statusUtil = status
        )
    }

    private def destOf(c: PipelineConfig): List[(String, String)] =
        c.destination.schemaProperties.fields.asScala.toList.map(f => f.name -> f.`type`)

    private val header = List("city", "temp_c", "temp_f", "label", "day", "empty")
    private val rows = List("Shanghai,4.7,40.5,\"Rain, heavy\",2026-11-20,", "Lagos,31,87.8,Clear sky,2026-11-20,")

    test("added lists the header columns the destination schema lacks, in header order, ignoring case") {
        val dest = schema(source: _*).fields.asScala.toList
        assert(TransformedColumns.added(List("CITY", "temp_f", "temp_c", "label"), dest) == List("temp_f", "label"))
        assert(TransformedColumns.added(List("temp_c", "city"), dest) == Nil)
        assert(TransformedColumns.added(null, dest) == Nil)
    }

    test("inferTypes reads every row: numbers widen, a quoted delimiter stays in its value, an empty column is string") {
        val types = TransformedColumns.inferTypes(rows.iterator, header, List("temp_f", "label", "day", "empty"), ",")
        assert(types.map(f => f.name -> f.`type`) == List("temp_f" -> "double", "label" -> "string", "day" -> "date", "empty" -> "string"))
        val mixed = TransformedColumns.inferTypes(Iterator("1,7", "x,8", "2,9.5"), List("a", "b"), List("a", "b"), ",")
        assert(mixed.map(_.`type`) == List("string", "double"))
    }

    test("reconcile appends the added columns to the destination schema, typed, and stores them once") {
        val status = new RecordingStatusUtil
        val stored = ListBuffer[(String, List[SchemaField])]()
        val out = TransformedColumns.reconcile(ctx(config(), header, rows, status), (n, f) => stored += ((n, f)))
        val added = List("temp_f" -> "double", "label" -> "string", "day" -> "date", "empty" -> "string")
        assert(destOf(out.config) == source.toList ++ added)
        assert(out.config.destination.schemaProperties.schemaVersion == 2)
        assert(out.config.source.schemaProperties.fields.size == 2, "the source schema is not changed")
        assert(out.data.headerWithSchema.map(f => f.name -> f.`type`) == source.toList ++ added)
        assert(stored.toList.map(s => s._1 -> s._2.map(f => f.name -> f.`type`)) == List("weather" -> added))
        assert(status.messages.exists(
            _._2 == "CodeGen transformation added columns to the destination schema: temp_f (double), label (string), day (date), empty (string)"
        ))
    }

    test("reconcile changes nothing when every header column is already a destination column") {
        val status = new RecordingStatusUtil
        val in = ctx(config(), List("temp_c", "city"), List("4.7,Shanghai"), status)
        val out = TransformedColumns.reconcile(in, (_, _) => fail("nothing to store"))
        assert(out eq in)
        assert(status.messages.isEmpty)
    }

    test("a rehearsal run lands the added columns but stores nothing") {
        val out = PipelineScripts.withRehearsal(true) {
            TransformedColumns.reconcile(ctx(config(), header, rows, new RecordingStatusUtil), (_, _) => fail("a rehearsal stores nothing"))
        }
        assert(destOf(out.config).size == 6)
    }

    test("withDestFields skips names the schema already lists and leaves the config alone when nothing is new") {
        val cfg = config()
        assert(TransformedColumns.withDestFields(cfg, List(SchemaField("City", "int"))) eq cfg)
        val grown = TransformedColumns.withDestFields(cfg, List(SchemaField("City", "int"), SchemaField("temp_f", "double")))
        assert(destOf(grown) == source.toList :+ ("temp_f" -> "double"))
    }

    test("an added column the destination cannot hold, or two with one name, stops the run before anything is stored") {
        val orc = config(ObjectStore(prefixKey = "weather", fileFormat = "orc"))
        val bad = intercept[DatrisException](
            TransformedColumns.reconcile(ctx(orc, List("city", "temp_c", "a`b"), List("x,1,2"), new RecordingStatusUtil), (_, _) => fail("not stored"))
        )
        assert(bad.getMessage.contains("the transformation added a column named 'a`b'"))
        val twice = intercept[DatrisException](
            TransformedColumns.reconcile(
                ctx(config(), List("city", "temp_c", "f", "F"), List("x,1,2,3"), new RecordingStatusUtil),
                (_, _) => fail("not stored")
            )
        )
        assert(twice.getMessage.contains("two columns named"))
    }
}
