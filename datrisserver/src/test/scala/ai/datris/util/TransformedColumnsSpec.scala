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
  * (`TransformedColumns`): as string, appended, stored once,
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

    test("reconcile appends the added columns to the destination schema, as string, and stores them once") {
        val status = new RecordingStatusUtil
        val stored = ListBuffer[(String, List[SchemaField])]()
        val out = TransformedColumns.reconcile(ctx(config(), header, rows, status), Nil, (n, f) => stored += ((n, f)))
        val added = List("temp_f" -> "string", "label" -> "string", "day" -> "string", "empty" -> "string")
        assert(destOf(out.config) == source.toList ++ added)
        assert(out.config.destination.schemaProperties.schemaVersion == 2)
        assert(out.config.source.schemaProperties.fields.size == 2, "the source schema is not changed")
        assert(out.data.headerWithSchema.map(f => f.name -> f.`type`) == source.toList ++ added)
        assert(stored.toList.map(s => s._1 -> s._2.map(f => f.name -> f.`type`)) == List("weather" -> added))
        assert(status.messages.exists(
            _._2 == "CodeGen transformation added columns to the destination schema: temp_f (string), label (string), day (string), empty (string)"
        ))
    }

    test("reconcile changes nothing when every header column is already a destination column") {
        val status = new RecordingStatusUtil
        val in = ctx(config(), List("temp_c", "city"), List("4.7,Shanghai"), status)
        val out = TransformedColumns.reconcile(in, Nil, (_, _) => fail("nothing to store"))
        assert(out eq in)
        assert(status.messages.isEmpty)
    }

    test("a rehearsal run lands the added columns but stores nothing") {
        val out = PipelineScripts.withRehearsal(true) {
            TransformedColumns.reconcile(ctx(config(), header, rows, new RecordingStatusUtil), Nil, (_, _) => fail("a rehearsal stores nothing"))
        }
        assert(destOf(out.config).size == 6)
    }

    test("withDestFields skips names the schema already lists and leaves the config alone when nothing is new") {
        val cfg = config()
        assert(TransformedColumns.withDestFields(cfg, List(SchemaField("City", "int"))) eq cfg)
        val grown = TransformedColumns.withDestFields(cfg, List(SchemaField("City", "int"), SchemaField("temp_f", "double")))
        assert(destOf(grown) == source.toList :+ ("temp_f" -> "double"))
    }

    test("on an object store pipeline an added column the destination cannot hold, or two with one name, stops the run before anything is stored") {
        val orc = config(ObjectStore(prefixKey = "weather", fileFormat = "orc"))
        val bad = intercept[DatrisException](
            TransformedColumns.reconcile(ctx(orc, List("city", "temp_c", "a`b"), List("x,1,2"), new RecordingStatusUtil), Nil, (_, _) => fail("not stored"))
        )
        assert(bad.getMessage.contains("the transformation added a column named 'a`b'"))
        val twice = intercept[DatrisException](
            TransformedColumns.reconcile(
                ctx(orc, List("city", "temp_c", "f", "F"), List("x,1,2,3"), new RecordingStatusUtil),
                Nil,
                (_, _) => fail("not stored")
            )
        )
        assert(twice.getMessage.contains("two columns named"))
    }

    // ---- Upgrade safety (plans/stories/transformation-added-columns-upgrade-safe.md) ----
    //  A pipeline that ran on v1.60.0 keeps running: on a destination with no
    //  object store a name the destination cannot hold, or a name two added
    //  columns share, is left out with a warn line (v1.60.0's outcome plus the
    //  warning); an added column is stored as string, the rule a new source
    //  column follows, so no later run's values can fail the load.

    private class LevelStatusUtil extends StatusUtil {
        val infos = new ListBuffer[String]()
        val warns = new ListBuffer[String]()
        val errors = new ListBuffer[String]()
        override def overrideProcessName(processName: String): Unit = ()
        override def info(state: String, description: String): Unit = infos += description
        override def warn(state: String, description: String): Unit = warns += description
        override def error(state: String, description: String): Unit = errors += description
    }

    /** The weather pipeline loading Postgres only (no object store). */
    private def pgConfig(): PipelineConfig = {
        val c = config()
        c.copy(destination = c.destination.copy(database = Database(usePostgres = true, table = "weather")))
    }

    private def jl(xs: String*): java.util.List[String] = new java.util.ArrayList[String](java.util.Arrays.asList(xs: _*))

    private def typesOf(fields: List[SchemaField]): List[(String, String)] = fields.map(f => f.name -> f.`type`)

    test("an added column whose name the destination cannot hold is left out with a warning and the run continues") {
        val overLong = "s" * 64 // 64 bytes; Postgres keeps at most 63
        val cases = Seq(
            " " -> "blank",
            "a\u0001b" -> "control character",
            overLong -> "64 bytes"
        )
        for ((bad, reason) <- cases) {
            val label = IngestColumnNames.display(bad)
            val status = new LevelStatusUtil
            val stored = ListBuffer[(String, List[SchemaField])]()
            val hdr = List("city", "temp_c", bad, "score")
            val in = ctx(pgConfig(), hdr, List("Shanghai,4.7,zzz,7"), status)
            val out =
                try TransformedColumns.reconcile(in, Nil, (n, f) => stored += ((n, f)))
                catch { case e: DatrisException => fail("'" + label + "': the run must continue, but reconcile threw: " + e.getMessage) }

            assert(destOf(out.config) == source.toList :+ ("score" -> "string"), label + ": the unholdable column is left out, the rest is added")
            assert(stored.toList.map(s => s._1 -> typesOf(s._2)) == List("weather" -> List("score" -> "string")), label + ": only score is stored")
            assert(
                status.warns.exists(w => w.contains("'" + label + "'") && w.contains(reason)),
                label + ": a warn line names the column and why: " + status.warns
            )
            assert(status.errors.isEmpty, label + ": " + status.errors)

            // The load then leaves the column out by name and keeps every other value in its own column.
            val rows = DestSchemaProjector.project(out, Iterator("Shanghai,4.7,zzz,7", "Lagos,31,yyy,8")).toList
            assert(rows == List("Shanghai,4.7,7", "Lagos,31,8"), label + ": " + rows)
        }
    }

    test("two added columns with one name are both left out with a warning") {
        val status = new LevelStatusUtil
        val stored = ListBuffer[(String, List[SchemaField])]()
        val hdr = List("city", "temp_c", "f", "score", "F")
        val out =
            try TransformedColumns.reconcile(ctx(pgConfig(), hdr, List("Shanghai,4.7,1,7,2"), status), Nil, (n, f) => stored += ((n, f)))
            catch { case e: DatrisException => fail("the run must continue, but reconcile threw: " + e.getMessage) }

        assert(destOf(out.config) == source.toList :+ ("score" -> "string"), "neither f nor F joins the schema")
        assert(stored.toList.map(s => s._1 -> typesOf(s._2)) == List("weather" -> List("score" -> "string")))
        assert(status.warns.exists(w => w.contains("'f'") || w.contains("'F'")), "a warn line names the shared column: " + status.warns)
        assert(DestSchemaProjector.project(out, Iterator("Shanghai,4.7,1,7,2")).toList == List("Shanghai,4.7,7"))
    }

    test("with an object store destination an unholdable added name still fails with today's message") {
        def msg(c: PipelineConfig, hdr: List[String]): String =
            intercept[DatrisException](
                TransformedColumns.reconcile(ctx(c, hdr, List("x,1,2,3"), new LevelStatusUtil), Nil, (_, _) => fail("nothing is stored"))
            ).getMessage

        val orc = config(ObjectStore(prefixKey = "weather", fileFormat = "orc"))
        assert(
            msg(orc, List("city", "temp_c", "a`b")) ==
                "Pipeline weather: the transformation added a column named 'a`b', which the destination cannot hold: " +
                "it holds a backtick, which ORC files cannot keep in a column name. Change the instruction so the column has another name"
        )
        val parquet = config(ObjectStore(prefixKey = "weather", fileFormat = "parquet"))
        assert(
            msg(parquet, List("city", "temp_c", "a\u0001b")) ==
                "Pipeline weather: the transformation added a column named 'a\\u0001b', which the destination cannot hold: " +
                "it holds a control character. Change the instruction so the column has another name"
        )
        // Postgres and an object store together: the object store's positional read cannot skip a column.
        val both = parquet.copy(destination = parquet.destination.copy(database = Database(usePostgres = true, table = "weather")))
        assert(
            msg(both, List("city", "temp_c", "s" * 64)) ==
                "Pipeline weather: the transformation added a column named '" + ("s" * 64) + "', which the destination cannot hold: " +
                "it is 64 bytes long; the new destination allows at most 63 bytes. Change the instruction so the column has another name"
        )
        assert(
            msg(parquet, List("city", "temp_c", "f", "F")) ==
                "Pipeline weather: the transformation's output has two columns named 'f'. Change the instruction so each new column has its own name"
        )
    }

    test("an added column is stored as string whatever the first run's values") {
        val status = new LevelStatusUtil
        val stored = ListBuffer[(String, List[SchemaField])]()
        val hdr = List("city", "temp_c", "temp_f", "flag", "day", "n", "big", "at")
        val in = ctx(
            pgConfig(),
            hdr,
            List("Shanghai,4.7,40.5,true,2026-11-20,7,9000000000,2026-11-20T10:00:00", "Lagos,31,87.8,false,2026-11-21,8,9000000001,2026-11-21T10:00:00"),
            status
        )
        val out = TransformedColumns.reconcile(in, Nil, (n, f) => stored += ((n, f)))
        val added = List("temp_f", "flag", "day", "n", "big", "at").map(_ -> "string")
        assert(destOf(out.config) == source.toList ++ added)
        assert(out.data.headerWithSchema.drop(2).map(f => f.name -> f.`type`) == added, "the run's own header schema matches what is stored")
        assert(stored.toList.map(s => s._1 -> typesOf(s._2)) == List("weather" -> added))
        assert(status.infos.exists(_.startsWith("CodeGen transformation added columns to the destination schema: ")), status.infos)
        assert(status.warns.isEmpty, status.warns)
    }

    test("a second run whose added column holds text after a first run of integers produces the same stored schema and no failure") {
        // The stored pipeline, updated the way persistStored updates it.
        var storedConfig = pgConfig()
        val persist: (String, List[SchemaField]) => Unit = (_, f) => storedConfig = TransformedColumns.withDestFields(storedConfig, f)
        val hdr = List("city", "temp_c", "score")

        TransformedColumns.reconcile(ctx(storedConfig, hdr, List("Shanghai,4.7,7"), new LevelStatusUtil), Nil, persist)
        val afterFirst = destOf(storedConfig)
        assert(afterFirst == source.toList :+ ("score" -> "string"), "run 1 (score = 7) stores score as string, which every later value fits")

        val status2 = new LevelStatusUtil
        val out2 = TransformedColumns.reconcile(ctx(storedConfig, hdr, List("Lagos,31,n/a"), status2), Nil, persist)
        assert(destOf(storedConfig) == afterFirst, "run 2 (score = n/a) leaves the stored schema as run 1 left it")
        assert(destOf(out2.config) == afterFirst)
        assert(status2.warns.isEmpty && status2.errors.isEmpty, status2.warns.toString + status2.errors)
        assert(DestSchemaProjector.project(out2, Iterator("Lagos,31,n/a")).toList == List("Lagos,31,n/a"))
    }

    test("a rehearsal leaves an unholdable added column out with a warning and stores nothing") {
        val status = new LevelStatusUtil
        val out = PipelineScripts.withRehearsal(true) {
            try TransformedColumns.reconcile(
                    ctx(pgConfig(), List("city", "temp_c", " ", "score"), List("x,1,2,3"), status),
                    Nil,
                    (_, _) => fail("a rehearsal stores nothing")
                )
            catch { case e: DatrisException => fail("the rehearsal must continue, but reconcile threw: " + e.getMessage) }
        }
        assert(destOf(out.config) == source.toList :+ ("score" -> "string"))
        assert(status.warns.nonEmpty)
    }

    test("pin: a normal added column still arrives in the destination on a Postgres pipeline") {
        val status = new LevelStatusUtil
        val stored = ListBuffer[(String, List[SchemaField])]()
        val out =
            TransformedColumns.reconcile(ctx(pgConfig(), List("city", "temp_c", "score"), List("Shanghai,4.7,7"), status), Nil, (n, f) => stored += ((n, f)))
        assert(destOf(out.config).map(_._1) == List("city", "temp_c", "score"))
        assert(out.config.destination.schemaProperties.schemaVersion == 2)
        assert(stored.toList.map(s => s._1 -> s._2.map(_.name)) == List("weather" -> List("score")), "stored once")
        assert(status.infos.exists(m => m.startsWith("CodeGen transformation added columns to the destination schema: ") && m.contains("score")), status.infos)
        assert(status.warns.isEmpty)
        assert(DestSchemaProjector.project(out, Iterator("Shanghai,4.7,7", "Lagos,31,8")).toList == List("Shanghai,4.7,7", "Lagos,31,8"))
    }

    test("pin: on an object store pipeline an added column joins the schema and the merge-key/partition refusal is unchanged") {
        val os = ObjectStore(
            prefixKey = "weather",
            fileFormat = "iceberg",
            writeMode = "merge",
            keyFields = jl("city"),
            partitionBy = jl("city"),
            deleteBeforeWrite = true
        )
        val keep = TransformedColumns.reconcile(ctx(config(os), List("city", "temp_c", "score"), List("x,1,2"), new LevelStatusUtil), Nil, (_, _) => ())
        assert(destOf(keep.config).map(_._1) == List("city", "temp_c", "score"))
        val keepFields = keep.config.destination.schemaProperties.fields.asScala.toList
        SparkObjectStoreLoader.refuseNullFilledRequired(keep.config, keepFields, List("city", "temp_c", "score"), 1L) // not refused

        val lost = TransformedColumns.reconcile(ctx(config(os), List("temp_c", "score"), List("1,2"), new LevelStatusUtil), Nil, (_, _) => ())
        val lostFields = lost.config.destination.schemaProperties.fields.asScala.toList
        val e = intercept[DatrisException](SparkObjectStoreLoader.refuseNullFilledRequired(lost.config, lostFields, List("temp_c", "score"), 1L))
        assert(e.getMessage.contains("missing required key field(s): city"), e.getMessage)

        val parquet = config(ObjectStore(prefixKey = "weather", fileFormat = "parquet", partitionBy = jl("city")))
        val p = TransformedColumns.reconcile(ctx(parquet, List("temp_c", "score"), List("1,2"), new LevelStatusUtil), Nil, (_, _) => ())
        val pe = intercept[DatrisException](
            SparkObjectStoreLoader.refuseNullFilledRequired(p.config, p.config.destination.schemaProperties.fields.asScala.toList, List("temp_c", "score"), 1L)
        )
        assert(pe.getMessage.contains("missing required partition column(s): city"), pe.getMessage)
    }

    // ---- A source column the destination schema leaves out stays out ----
    //  docs/schemas.mdx: a destination schema can "drop fields that should not
    //  reach the destination". Such a column is still in the rows a
    //  transformation reads and writes back; it was not created by the
    //  transformation, so it must not join the destination schema, be stored,
    //  be loaded or produce a warning, whatever the transformation did.

    private val excludedSource = source :+ ("notes" -> "string")

    /** Source [city, temp_c, notes], destination [city, temp_c]. */
    private def excludedConfig(objectStore: ObjectStore = null, database: Database = Database(usePostgres = true, table = "weather")): PipelineConfig =
        PipelineConfig(
            name = "weather",
            source = Source(schemaProperties = schema(excludedSource: _*), fileAttributes = FileAttributes(csvAttributes = CsvAttributes(delimiter = ","))),
            destination = Destination(schemaProperties = schema(source: _*), objectStore = objectStore, database = database)
        )

    private def recorder(): (ListBuffer[(String, List[SchemaField])], (String, List[SchemaField]) => Unit) = {
        val stored = ListBuffer[(String, List[SchemaField])]()
        (stored, (n, f) => stored += ((n, f)))
    }

    test("a source column the destination schema leaves out stays out: only the transformation's own column is added") {
        val status = new LevelStatusUtil
        val (stored, persist) = recorder()
        val hdr = List("city", "temp_c", "notes", "score")
        val out = TransformedColumns.reconcile(ctx(excludedConfig(), hdr, List("Shanghai,4.7,secret,7"), status), Nil, persist)
        assert(destOf(out.config) == source.toList :+ ("score" -> "string"))
        assert(stored.toList.map(s => s._1 -> typesOf(s._2)) == List("weather" -> List("score" -> "string")), "persist receives only score")
        assert(status.warns.isEmpty, status.warns)
        assert(status.infos.forall(!_.contains("notes")), status.infos)
        assert(DestSchemaProjector.project(out, Iterator("Shanghai,4.7,secret,7", "Lagos,31,private,n/a")).toList == List("Shanghai,4.7,7", "Lagos,31,n/a"))
    }

    test("a transformation that changes no columns adds nothing, stores nothing and says nothing") {
        val status = new LevelStatusUtil
        val in = ctx(excludedConfig(), List("city", "temp_c", "notes"), List("Shanghai,4.7,secret"), status)
        val out = TransformedColumns.reconcile(in, Nil, (_, _) => fail("nothing to store"))
        assert(out eq in)
        assert(status.infos.isEmpty && status.warns.isEmpty, status.infos.toString + status.warns)
        assert(DestSchemaProjector.project(out, Iterator("Shanghai,4.7,secret")).toList == List("Shanghai,4.7"))
    }

    test("an excluded source column the transformation renames only by letter case or surrounding spaces stays out") {
        for (renamed <- Seq("NOTES", " notes", "Notes ")) {
            val status = new LevelStatusUtil
            val (stored, persist) = recorder()
            val hdr = List("city", "temp_c", renamed, "score")
            val out = TransformedColumns.reconcile(ctx(excludedConfig(), hdr, List("Shanghai,4.7,secret,7"), status), Nil, persist)
            assert(destOf(out.config) == source.toList :+ ("score" -> "string"), "'" + renamed + "'")
            assert(stored.toList.flatMap(_._2.map(_.name)) == List("score"), "'" + renamed + "'")
            assert(status.warns.isEmpty, status.warns)
            assert(DestSchemaProjector.project(out, Iterator("Shanghai,4.7,secret,7")).toList == List("Shanghai,4.7,7"), "'" + renamed + "'")
        }
    }

    test("an excluded source column the transformation removes and re-emits elsewhere stays out") {
        val status = new LevelStatusUtil
        val (stored, persist) = recorder()
        val hdr = List("score", "temp_c", "city", "notes")
        val out = TransformedColumns.reconcile(ctx(excludedConfig(), hdr, List("7,4.7,Shanghai,secret"), status), Nil, persist)
        assert(destOf(out.config) == source.toList :+ ("score" -> "string"))
        assert(stored.toList.flatMap(_._2.map(_.name)) == List("score"))
        assert(status.warns.isEmpty, status.warns)
        assert(DestSchemaProjector.project(out, Iterator("7,4.7,Shanghai,secret")).toList == List("Shanghai,4.7,7"))
    }

    test("an excluded source column whose name the destination could not hold produces no warning") {
        val long = "n" * 64
        val cfg0 = excludedConfig()
        val cfg = cfg0.copy(source = cfg0.source.copy(schemaProperties = schema((source :+ (long -> "string")): _*)))
        val status = new LevelStatusUtil
        val (stored, persist) = recorder()
        val out = TransformedColumns.reconcile(ctx(cfg, List("city", "temp_c", long, "score"), List("Shanghai,4.7,secret,7"), status), Nil, persist)
        assert(destOf(out.config) == source.toList :+ ("score" -> "string"))
        assert(stored.toList.flatMap(_._2.map(_.name)) == List("score"))
        assert(status.warns.isEmpty, status.warns)
    }

    test("on an object store pipeline an excluded source column in the rows leaves the schema as it is, so the load is v1.60.0's") {
        for (fmt <- Seq("parquet", "orc", "iceberg")) {
            val os = ObjectStore(prefixKey = "weather", fileFormat = fmt)
            for (hdr <- Seq(List("city", "temp_c", "notes"), List("city", "temp_c", "notes", "score"), List("city", "notes", "score"))) {
                val status = new LevelStatusUtil
                val in = ctx(excludedConfig(objectStore = os, database = null), hdr, List(hdr.map(_ => "x").mkString(",")), status)
                val out = TransformedColumns.reconcile(in, Nil, (_, _) => fail("nothing is stored: " + fmt + " " + hdr))
                assert(destOf(out.config) == source.toList, fmt + " " + hdr)
                assert(out.config.destination.schemaProperties.schemaVersion == in.config.destination.schemaProperties.schemaVersion)
                assert(status.warns.isEmpty && status.errors.isEmpty, status.warns.toString + status.errors)
            }
        }
    }

    test("the run warning for an over-long added name does not speak of a new destination") {
        val status = new LevelStatusUtil
        TransformedColumns.reconcile(ctx(pgConfig(), List("city", "temp_c", "s" * 64), List("x,1,2"), status), Nil, (_, _) => ())
        assert(status.warns.exists(_.contains("it is 64 bytes long; the destination allows at most 63 bytes")), status.warns)
        assert(status.warns.forall(!_.contains("new destination")), status.warns)
    }

    test("a column the rows carried before the transformation (a preprocessor's, or one the source schema does not list) is not added") {
        val status = new LevelStatusUtil
        val (stored, persist) = recorder()
        val before = List("city", "temp_c", "region")
        val hdr = List("city", "temp_c", "region", "score")
        val out = TransformedColumns.reconcile(ctx(pgConfig(), hdr, List("Shanghai,4.7,east,7"), status), before, persist)
        assert(destOf(out.config) == source.toList :+ ("score" -> "string"))
        assert(stored.toList.flatMap(_._2.map(_.name)) == List("score"))
        assert(status.warns.isEmpty, status.warns)
        assert(DestSchemaProjector.project(out, Iterator("Shanghai,4.7,east,7")).toList == List("Shanghai,4.7,7"))

        // the same header without a pre-transformation header: region counts as created
        val alone = TransformedColumns.reconcile(ctx(pgConfig(), hdr, List("Shanghai,4.7,east,7"), new LevelStatusUtil), Nil, (_, _) => ())
        assert(destOf(alone.config).map(_._1) == List("city", "temp_c", "region", "score"))
    }

    test("a missing or empty source schema falls back to the pre-transformation header") {
        def raw(cfg: PipelineConfig, hdr: List[String], status: StatusUtil): JobContext =
            JobContext(
                pipelineToken = "job-token-1",
                metadata = PipelineMetadata("weather", "rows.csv", "/tmp/rows.csv", "pub-1", bulkUpload = false),
                data = Data(1L, hdr, hdr.map(h => SchemaField(h, "string")), List("Shanghai,4.7,secret,7"), null),
                config = cfg,
                pipelineProperties = null,
                state = null,
                thread = null,
                statusUtil = status
            )
        val base = excludedConfig()
        val noSchema = base.copy(source = base.source.copy(schemaProperties = null))
        val emptyFields = base.copy(source = base.source.copy(schemaProperties = schema()))
        val noSource = base.copy(source = null)
        for (cfg <- Seq(noSchema, emptyFields, noSource)) {
            val status = new LevelStatusUtil
            val (stored, persist) = recorder()
            val out =
                TransformedColumns.reconcile(raw(cfg, List("city", "temp_c", "notes", "score"), status), List("city", "temp_c", "notes"), persist)
            assert(destOf(out.config) == source.toList :+ ("score" -> "string"))
            assert(stored.toList.flatMap(_._2.map(_.name)) == List("score"))
            assert(status.warns.isEmpty, status.warns)
        }
    }

    test("persistStored reads the stored pipeline fresh, appends the columns once and writes only when something is new") {
        val writes = ListBuffer[PipelineConfig]()
        var db: Map[String, PipelineConfig] = Map("weather" -> excludedConfig())
        val read: String => PipelineConfig = n => db.getOrElse(n, null)
        val write: PipelineConfig => Unit = c => { writes += c; db = db.updated(c.name, c) }

        TransformedColumns.persistStored("weather", List(SchemaField("score", "string")), read, write)
        assert(writes.size == 1)
        assert(destOf(db("weather")) == source.toList :+ ("score" -> "string"))
        assert(db("weather").destination.schemaProperties.schemaVersion == 2)
        assert(db("weather").source.schemaProperties.fields.asScala.map(_.name).toList == excludedSource.map(_._1).toList, "the source schema is untouched")

        // a second run with the same column: nothing new, nothing written
        TransformedColumns.persistStored("weather", List(SchemaField("SCORE", "string")), read, write)
        assert(writes.size == 1)

        // a stored column of another type (this branch's earlier behaviour) is kept as stored
        db = db.updated("weather", TransformedColumns.withDestFields(excludedConfig(), List(SchemaField("score", "int"))))
        TransformedColumns.persistStored("weather", List(SchemaField("score", "string")), read, write)
        assert(writes.size == 1)
        assert(destOf(db("weather")).last == ("score" -> "int"))

        // a pipeline deleted meanwhile, or one with no destination schema, is not written
        TransformedColumns.persistStored("gone", List(SchemaField("score", "string")), read, write)
        val noDest = excludedConfig().copy(name = "nodest")
        db = db.updated("nodest", noDest.copy(destination = noDest.destination.copy(schemaProperties = null)))
        TransformedColumns.persistStored("nodest", List(SchemaField("score", "string")), read, write)
        assert(writes.size == 1)
    }

    test("reconcile hands persistStored's own arguments through: the stored schema matches the run's") {
        var db: Map[String, PipelineConfig] = Map("weather" -> excludedConfig())
        val persist: (String, List[SchemaField]) => Unit =
            (n, f) => TransformedColumns.persistStored(n, f, x => db.getOrElse(x, null), c => db = db.updated(c.name, c))
        val hdr = List("city", "temp_c", "notes", "score")
        val out =
            TransformedColumns.reconcile(ctx(excludedConfig(), hdr, List("Shanghai,4.7,secret,7"), new LevelStatusUtil), hdr.take(3), persist)
        assert(destOf(db("weather")) == destOf(out.config))
        assert(destOf(db("weather")) == source.toList :+ ("score" -> "string"))
    }
}
