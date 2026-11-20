package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.HiddenTapScrub
import ai.datris.model._
import org.apache.spark.sql.{SaveMode, SparkSession}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._

/** The object-store query of a Parquet pipeline, and of an ORC one, returns every column
  * its data has, not the columns of one file.
  *
  * {{{
  * object ObjectStoreQueryUtil {
  *     private[util] def readSchemaFor(config: PipelineConfig): Option[ReadSchema]
  *     private[util] def readPath(spark, path, format, limit, resolved, bucket, readSchema: Option[ReadSchema], schemaScanMaxFiles: Int = FileSchemaScanMaxFiles, stats: ReadStats = new ReadStats()): QueryResult
  * }
  * }}}
  * `query` (the route and the MCP `query_objectstore` tool) reads with
  * `readSchemaFor(<stored config>)`. Files are written in batches with a
  * growing schema the way SparkObjectStoreLoader writes them (its staged
  * read, then `write.mode(append)[.partitionBy].format(fmt).save`). Local
  * Spark, file:// paths. */
class ObjectStoreQueryReadSpec extends AnyFunSuite with BeforeAndAfterAll {

    private lazy val localSpark: SparkSession =
        SparkSession.builder().master("local[1]").appName("ObjectStoreQueryReadSpec").config("spark.ui.enabled", "false").getOrCreate()
    private var started = false
    private def spark: SparkSession = { started = true; localSpark }
    override def afterAll(): Unit = if (started) localSpark.stop()

    private def stage(rows: String*): String = {
        val f = Files.createTempFile("object-store-query-read-", ".csv")
        f.toFile.deleteOnExit()
        Files.write(f, rows.mkString("\n").getBytes(StandardCharsets.UTF_8))
        f.toAbsolutePath.toString
    }

    /** One loader write of `rows` under `fields`. */
    private def load(out: String, fmt: String, fields: List[SchemaField], partitionBy: Seq[String], rows: String*): Unit = {
        val df = SparkObjectStoreLoader.stagedDataFrame(spark, stage(rows: _*), fields, ",")
        val w = df.write.mode(SaveMode.Append)
        (if (partitionBy.nonEmpty) w.partitionBy(partitionBy: _*) else w).format(fmt).save(out)
    }

    private def config(fmt: String, fields: List[SchemaField], provenance: ProvenanceConfig = null): PipelineConfig = {
        val fs = new java.util.ArrayList[SchemaField](fields.asJava)
        PipelineConfig(
            name = "q",
            source = Source(schemaProperties = SchemaProperties("db", fs), fileAttributes = FileAttributes(csvAttributes = CsvAttributes())),
            destination = Destination(schemaProperties = SchemaProperties("db", fs), objectStore = ObjectStore(prefixKey = "q", fileFormat = fmt)),
            provenance = provenance
        )
    }

    private def read(out: String, fmt: String, cfg: PipelineConfig, limit: Int = 100): ObjectStoreQueryUtil.QueryResult =
        ObjectStoreQueryUtil.readPath(spark, out, fmt, limit, None, null, ObjectStoreQueryUtil.readSchemaFor(cfg))

    private def rowsBy(r: ObjectStoreQueryUtil.QueryResult, key: String): Map[Any, Map[String, Any]] =
        r.rows.asScala.map(m => m.get(key) -> m.asScala.toMap).toMap

    private def newDir(label: String): (Path, String) = {
        val root = Files.createTempDirectory("object-store-query-read-" + label + "-")
        (root, root.resolve("data").toUri.toString)
    }

    private val f1 = List(SchemaField("id", "int"), SchemaField("region", "string"), SchemaField("amount", "double"))

    private def growing(fmt: String): List[List[SchemaField]] = {
        val odd = List(SchemaField("a.b", "string"), SchemaField("a. b c", "string")) ++
            (if (fmt == "parquet") List(SchemaField("x`y", "string")) else Nil)
        List(f1, f1 :+ SchemaField("extra", "string"), (f1 :+ SchemaField("extra", "string")) ++ odd)
    }

    test("every column of every batch comes back, in the stored order, with nulls for earlier rows (Parquet and ORC, unpartitioned and partitioned)") {
        for (fmt <- Seq("parquet", "orc"); partitioned <- Seq(false, true)) {
            val label = fmt + (if (partitioned) " partitioned" else "")
            val (_, out) = newDir(fmt + partitioned)
            val parts = if (partitioned) Seq("region") else Nil
            val Seq(s1, s2, s3) = growing(fmt)
            load(out, fmt, s1, parts, "1,east,1.5")
            load(out, fmt, s2, parts, "2,west,2.5,e2")
            val oddValues = if (fmt == "parquet") ",d3,s3,b3" else ",d3,s3"
            load(out, fmt, s3, parts, "3,east,3.5,e3" + oddValues)
            val r = read(out, fmt, config(fmt, s3))
            assert(r.columns.asScala.toList == s3.map(_.name), label + ": " + r.columns)
            val byId = rowsBy(r, "id")
            assert(byId.keySet == Set(1, 2, 3), label + ": " + r.rows)
            assert(byId(1)("region") == "east" && byId(2)("region") == "west", label + ": partition values")
            assert(byId(1)("extra") == null && byId(2)("extra") == "e2" && byId(3)("extra") == "e3", label + ": " + byId)
            assert(byId(1)("a.b") == null && byId(2)("a.b") == null && byId(3)("a.b") == "d3", label + ": " + byId)
            assert(byId(3)("a. b c") == "s3", label)
            if (fmt == "parquet") assert(byId(3)("x`y") == "b3" && byId(1)("x`y") == null, label)
            assert(byId(1)("amount") == 1.5 && byId(3)("amount") == 3.5, label)
        }
    }

    test("a column in the stored schema that no file holds yet reads as null; a column removed from the schema is still returned, after the schema") {
        for (fmt <- Seq("parquet", "orc")) {
            val (_, out) = newDir(fmt + "-removed")
            val withOld = f1 :+ SchemaField("old", "string")
            load(out, fmt, withOld, Nil, "1,east,1.5,o1")
            val stored = f1 :+ SchemaField("later", "string")
            val r = read(out, fmt, config(fmt, stored))
            assert(r.columns.asScala.toList == List("id", "region", "amount", "later", "old"), fmt + ": " + r.columns)
            assert(r.rows.asScala.head.get("later") == null && r.rows.asScala.head.get("old") == "o1", fmt + ": " + r.rows)
        }
    }

    /** (stored type before, after, CSV value written before, written after) */
    private val typeEdits = Seq(
        ("string", "int", "abc", "7"),
        ("int", "bigint", "5", "6"),
        ("float", "double", "1.5", "2.5"),
        ("int", "string", "5", "x"),
        ("date", "timestamp", "2024-01-02", "2024-01-03 04:05:06"),
        ("int", "decimal(10,2)", "5", "6.25")
    )

    test("a field type edited after files were written: the query still returns every row, under the files' own types") {
        for (fmt <- Seq("parquet", "orc"); (before, after, oldValue, newValue) <- typeEdits) {
            val label = fmt + " " + before + " -> " + after
            val oldFields = List(SchemaField("id", "int"), SchemaField("c", before))
            val newFields = List(SchemaField("id", "int"), SchemaField("c", after))
            // only old files
            val (_, onlyOld) = newDir(fmt + "-old")
            load(onlyOld, fmt, oldFields, Nil, "1," + oldValue, "2," + oldValue)
            val r1 = read(onlyOld, fmt, config(fmt, newFields))
            assert(r1.columns.asScala.toList == List("id", "c"), label + ": " + r1.columns)
            assert(r1.rows.size == 2 && r1.rows.asScala.forall(_.get("c") != null), label + ": " + r1.rows)
            // old and new files
            val (_, mixed) = newDir(fmt + "-mixed")
            load(mixed, fmt, oldFields, Nil, "1," + oldValue)
            load(mixed, fmt, newFields, Nil, "2," + newValue)
            val r2 = read(mixed, fmt, config(fmt, newFields))
            assert(r2.columns.asScala.toList == List("id", "c"), label + ": " + r2.columns)
            assert(rowsBy(r2, "id").keySet == Set(1, 2), label + ": " + r2.rows)
            assert(r2.rows.asScala.forall(_.get("c") != null), label + ": no value lost: " + r2.rows)
            // the limit met by one file
            val r3 = read(mixed, fmt, config(fmt, newFields), limit = 1)
            assert(r3.rows.size == 1 && r3.rows.get(0).get("c") != null, label + ": " + r3.rows)
        }
    }

    test("ORC files that disagree on a type: rows come back, the column as text where the files disagree") {
        val (_, out) = newDir("orc-disagree")
        load(out, "orc", List(SchemaField("id", "int"), SchemaField("c", "string")), Nil, "1,abc")
        load(out, "orc", List(SchemaField("id", "int"), SchemaField("c", "int")), Nil, "2,7")
        val r = read(out, "orc", config("orc", List(SchemaField("id", "int"), SchemaField("c", "int"))))
        assert(rowsBy(r, "id").map { case (k, m) => k -> m("c") } == Map(1 -> "abc", 2 -> "7"), r.rows)
    }

    test("the row limit is honoured") {
        for (fmt <- Seq("parquet", "orc")) {
            val (_, out) = newDir(fmt + "-limit")
            (1 to 5).foreach(i => load(out, fmt, f1, Nil, s"$i,east,$i.0"))
            assert(read(out, fmt, config(fmt, f1), limit = 2).rows.size == 2, fmt)
        }
    }

    test("stamp columns the provenance block selects come back after the schema, null for unstamped rows, and are scrubbed for a caller limited for taps") {
        for (fmt <- Seq("parquet", "orc")) {
            val (_, out) = newDir(fmt + "-stamps")
            load(out, fmt, f1, Nil, "1,east,1.5")
            val stamped = f1 ++ List(SchemaField("_datris_run_id", "string"), SchemaField("_datris_tap_run", "string"))
            load(out, fmt, stamped, Nil, "2,west,2.5,r2,t2")
            val cfg = config(fmt, f1, ProvenanceConfig(stamp = true, fields = java.util.Arrays.asList("_datris_run_id", "_datris_tap_run")))
            val r = read(out, fmt, cfg)
            assert(r.columns.asScala.toList == List("id", "region", "amount", "_datris_run_id", "_datris_tap_run"), fmt + ": " + r.columns)
            val byId = rowsBy(r, "id")
            assert(byId(1)("_datris_tap_run") == null && byId(2)("_datris_tap_run") == "t2" && byId(2)("_datris_run_id") == "r2", fmt + ": " + byId)
            // what QueryAPIController.restrictedObjectStoreResult does for a caller limited for taps
            val cols = HiddenTapScrub.dropTapStampNames(r.columns).asScala.toList
            val rows = HiddenTapScrub.dropTapStampColumns(r.rows).asScala.toList
            assert(cols == List("id", "region", "amount", "_datris_run_id"), fmt + ": " + cols)
            assert(rows.forall(m => !m.containsKey("_datris_tap_run")), fmt + ": " + rows)
            // no provenance block: only the stamp columns the data holds
            // (ORC: every file's; Parquet: the sampled file's, here one of two)
            val noBlock = read(out, fmt, config(fmt, f1)).columns.asScala.toList
            if (fmt == "orc") assert(noBlock == List("id", "region", "amount", "_datris_run_id", "_datris_tap_run"), fmt + ": " + noBlock)
            else
                assert(
                    noBlock == List("id", "region", "amount") || noBlock == List("id", "region", "amount", "_datris_run_id", "_datris_tap_run"),
                    fmt + ": " + noBlock
                )
        }
    }

    test("readSchemaFor follows the stored destination schema and its types, and is None without fields") {
        val s = ObjectStoreQueryUtil.readSchemaFor(config("parquet", List(SchemaField("id", "int"), SchemaField("a.b", "string")))).get
        assert(s.stored.fieldNames.toList == List("id", "a.b"))
        assert(s.stored("id").dataType == org.apache.spark.sql.types.IntegerType)
        assert(s.stamps.isEmpty)
        assert(ObjectStoreQueryUtil.readSchemaFor(config("parquet", Nil)).isEmpty)
    }

    test("without a read schema (no stored config) the files' schemas are merged") {
        val (_, out) = newDir("merge")
        load(out, "parquet", f1, Nil, "1,east,1.5")
        load(out, "parquet", f1 :+ SchemaField("extra", "string"), Nil, "2,west,2.5,e2")
        val r = ObjectStoreQueryUtil.readPath(spark, out, "parquet", 100)
        assert(r.columns.asScala.toSet == Set("id", "region", "amount", "extra"), r.columns)
    }

    // ------------------------------------------------ review follow-ups

    test("a pipeline whose provenance block has stamp=false, and that never stamped, gets no stamp columns") {
        for (fmt <- Seq("parquet", "orc")) {
            val (_, out) = newDir(fmt + "-nostamp")
            load(out, fmt, f1, Nil, "1,east,1.5")
            load(out, fmt, f1, Nil, "2,west,2.5")
            val cfg = config(fmt, f1, ProvenanceConfig(stamp = false))
            assert(ObjectStoreQueryUtil.readSchemaFor(cfg).get.stamps.isEmpty)
            val r = read(out, fmt, cfg)
            assert(r.columns.asScala.toList == List("id", "region", "amount"), fmt + ": " + r.columns)
            assert(r.rows.asScala.forall(m => m.keySet.asScala == Set("id", "region", "amount")), fmt + ": " + r.rows)
        }
    }

    test("stamping switched off after stamped files exist: ORC shows the stamps its files hold; Parquet shows them when the sampled file holds them") {
        for (fmt <- Seq("parquet", "orc")) {
            val (_, out) = newDir(fmt + "-stampoff")
            val stamped = f1 :+ SchemaField("_datris_run_id", "string")
            load(out, fmt, stamped, Nil, "1,east,1.5,r1")
            load(out, fmt, stamped, Nil, "2,west,2.5,r2")
            val r = read(out, fmt, config(fmt, f1, ProvenanceConfig(stamp = false)))
            // every file holds the stamp, so whichever file Parquet samples holds it
            assert(r.columns.asScala.toList == List("id", "region", "amount", "_datris_run_id"), fmt + ": " + r.columns)
            assert(rowsBy(r, "id").map { case (k, m) => k -> m("_datris_run_id") } == Map(1 -> "r1", 2 -> "r2"), fmt)
        }
    }

    test("stamps written through the real ProvenanceStamper come back after the schema columns") {
        val root = Files.createTempDirectory("object-store-query-read-stamper-")
        val env = DatrisEnvironment(
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
        TenantContext.set(env)
        try {
            for (fmt <- Seq("parquet", "orc")) {
                val (_, out) = newDir(fmt + "-stamper")
                load(out, fmt, f1, Nil, "1,east,1.5") // before stamping was turned on
                val cfg: PipelineConfig =
                    config(fmt, f1, ProvenanceConfig(stamp = true, fields = java.util.Arrays.asList("_datris_run_id", "_datris_config_version")))
                val ctx = JobContext(
                    "run-9",
                    null,
                    Data(10L, List("id", "region", "amount"), f1, List("2,west,2.5"), null),
                    cfg,
                    null,
                    INITIALIZED,
                    null,
                    null
                )
                val stamped = ProvenanceStamper.stamp(ctx)
                val fields = stamped.config.destination.schemaProperties.fields.asScala.toList
                val df = SparkObjectStoreLoader.stagedDataFrame(spark, stamped.data.staged.path, fields, ",")
                df.write.mode(SaveMode.Append).format(fmt).save(out)
                val r = read(out, fmt, cfg)
                assert(r.columns.asScala.toList == List("id", "region", "amount", "_datris_run_id", "_datris_config_version"), fmt + ": " + r.columns)
                val byId = rowsBy(r, "id")
                assert(byId(1)("_datris_run_id") == null && byId(2)("_datris_run_id") == "run-9", fmt + ": " + byId)
            }
        } finally TenantContext.clear()
    }

    test("a partition column returns the same values on both formats, as the plain read did (directory values, type inferred)") {
        val withZip = List(SchemaField("id", "int"), SchemaField("zip", "string"))
        val got = Seq("parquet", "orc").map { fmt =>
            val (_, out) = newDir(fmt + "-zip")
            load(out, fmt, withZip, Seq("zip"), "1,00123", "2,00456")
            val r = read(out, fmt, config(fmt, withZip))
            assert(r.columns.asScala.toList == List("id", "zip"), fmt + ": " + r.columns)
            val plain = spark.read.format(fmt).load(out).collect().map(row => row.getAs[Any]("id") -> row.getAs[Any]("zip")).toMap
            val mine = rowsBy(r, "id").map { case (k, m) => k -> m("zip") }
            assert(mine == plain, fmt + ": " + mine + " vs the plain read " + plain)
            mine
        }
        assert(got(0) == got(1), "Parquet and ORC agree: " + got)
    }

    test("an existing location with no data files returns an empty result on both formats") {
        for (fmt <- Seq("parquet", "orc")) {
            val (root, out) = newDir(fmt + "-empty")
            Files.createDirectories(root.resolve("data"))
            Files.write(root.resolve("data").resolve("_SUCCESS"), Array[Byte]())
            val r = read(out, fmt, config(fmt, f1))
            assert(r.rows.isEmpty, fmt + ": " + r.rows)
        }
    }

    test("ORC above the merge threshold reads with the stored types; a type edit the sampled file shows still returns rows") {
        val (_, out) = newDir("orc-threshold")
        load(out, "orc", f1, Nil, "1,east,1.5")
        load(out, "orc", f1 :+ SchemaField("extra", "string"), Nil, "2,west,2.5,e2")
        val r = ObjectStoreQueryUtil.readPath(
            spark,
            out,
            "orc",
            100,
            None,
            null,
            ObjectStoreQueryUtil.readSchemaFor(config("orc", f1 :+ SchemaField("extra", "string"))),
            schemaScanMaxFiles = 1
        )
        assert(r.columns.asScala.toList == List("id", "region", "amount", "extra"), r.columns)
        assert(rowsBy(r, "id").map { case (k, m) => k -> m("extra") } == Map(1 -> null, 2 -> "e2"))
        val (_, typed) = newDir("orc-threshold-typed")
        load(typed, "orc", List(SchemaField("id", "int"), SchemaField("c", "string")), Nil, "1,abc", "2,def")
        val t = ObjectStoreQueryUtil.readPath(
            spark,
            typed,
            "orc",
            100,
            None,
            null,
            ObjectStoreQueryUtil.readSchemaFor(config("orc", List(SchemaField("id", "int"), SchemaField("c", "int")))),
            schemaScanMaxFiles = 0
        )
        assert(t.rows.asScala.map(_.get("c")).toSet == Set("abc", "def"), "no silent null: " + t.rows)
    }

    test("a Parquet limit is honoured across files with a growing schema") {
        val (_, out) = newDir("parquet-limit-growing")
        load(out, "parquet", f1, Nil, "1,east,1.5", "2,east,2.5")
        load(out, "parquet", f1 :+ SchemaField("extra", "string"), Nil, "3,west,3.5,e3", "4,west,4.5,e4")
        val r = read(out, "parquet", config("parquet", f1 :+ SchemaField("extra", "string")), limit = 3)
        assert(r.rows.size == 3 && r.columns.asScala.toList == List("id", "region", "amount", "extra"), r.toString)
    }

    // ------------------------------------------------ round-two review

    private def readStats(
        out: String,
        fmt: String,
        cfg: PipelineConfig,
        limit: Int = 100,
        maxScan: Int = ObjectStoreQueryUtil.FileSchemaScanMaxFiles,
        hook: () => Unit = () => ()
    ): (ObjectStoreQueryUtil.QueryResult, ObjectStoreQueryUtil.ReadStats) = {
        val stats = new ObjectStoreQueryUtil.ReadStats(hook)
        (ObjectStoreQueryUtil.readPath(spark, out, fmt, limit, None, null, ObjectStoreQueryUtil.readSchemaFor(cfg), maxScan, stats), stats)
    }

    private def dataFiles(out: String): List[java.nio.file.Path] = {
        val root = java.nio.file.Paths.get(java.net.URI.create(out))
        val st = Files.walk(root)
        try st.iterator().asScala.filter(p => Files.isRegularFile(p) && !p.getFileName.toString.startsWith(".") && !p.getFileName.toString.startsWith("_")).toList
        finally st.close()
    }

    private val vInt = List(SchemaField("id", "int"), SchemaField("v", "int"), SchemaField("code", "string"))
    private val vBig = List(SchemaField("id", "int"), SchemaField("v", "bigint"), SchemaField("code", "string"))

    test("files holding a column under two types: partition values are those the plain read gives (numeric-looking, text and null directories)") {
        for (fmt <- Seq("parquet", "orc"); dirs <- Seq(Seq("007", "A12", ""), Seq("007", "012", ""))) {
            val (_, out) = newDir(fmt + "-fallback-parts")
            load(out, fmt, vInt, Seq("code"), dirs.zipWithIndex.map { case (d, i) => s"${i + 1},${i + 1},$d" }: _*)
            load(out, fmt, vBig, Seq("code"), s"9,9,${dirs.head}")
            val r = read(out, fmt, config(fmt, vBig))
            val plain = spark.read.format(fmt).load(out).select("id", "code").collect().map(row => row.getAs[Any]("id") -> row.getAs[Any]("code")).toMap
            val mine = rowsBy(r, "id").map { case (k, m) => k -> m("code") }
            assert(mine == plain, fmt + " " + dirs + ": " + mine + " vs the plain read " + plain)
            assert(rowsBy(r, "id").keySet == Set(1, 2, 3, 9), fmt + ": " + r.rows)
        }
    }

    test("two partition columns, numeric-looking and null values, match the plain read") {
        val f = List(SchemaField("id", "int"), SchemaField("yr", "string"), SchemaField("code", "string"))
        for (fmt <- Seq("parquet", "orc")) {
            val (_, out) = newDir(fmt + "-two-parts")
            load(out, fmt, f, Seq("yr", "code"), "1,2024,007", "2,,A1", "3,2025,")
            val r = read(out, fmt, config(fmt, f))
            assert(r.columns.asScala.toList == List("id", "yr", "code"), fmt + ": " + r.columns)
            val plain = spark.read.format(fmt).load(out).collect().map(row => row.getAs[Any]("id") -> ((row.getAs[Any]("yr"), row.getAs[Any]("code")))).toMap
            val mine = rowsBy(r, "id").map { case (k, m) => k -> ((m("yr"), m("code"))) }
            assert(mine == plain, fmt + ": " + mine + " vs " + plain)
        }
    }

    test("a partition column the stored schema does not name is still returned, last") {
        val (_, out) = newDir("parquet-unnamed-part")
        load(out, "parquet", f1, Seq("region"), "1,east,1.5")
        val r = read(out, "parquet", config("parquet", List(SchemaField("id", "int"), SchemaField("amount", "double"))))
        assert(r.columns.asScala.toList == List("id", "amount", "region"), r.columns)
    }

    test("zero-byte files are skipped, with and without a type difference") {
        for (fmt <- Seq("parquet", "orc"); fields <- Seq(vInt, vBig)) {
            val (_, out) = newDir(fmt + "-zero")
            load(out, fmt, vInt, Nil, "1,1,a")
            load(out, fmt, fields, Nil, "2,2,b")
            Files.write(java.nio.file.Paths.get(java.net.URI.create(out)).resolve("zzz-0000." + fmt), Array[Byte]())
            val r = read(out, fmt, config(fmt, vBig))
            assert(rowsBy(r, "id").keySet == Set(1, 2), fmt + ": " + r.rows)
            assert(read(out, fmt, config(fmt, vBig), limit = 2).rows.size == 2, fmt)
        }
    }

    test("a file that cannot be read, or that disappears after the listing, fails the query naming it; never an empty or partial result") {
        for (fmt <- Seq("parquet", "orc")) {
            val (_, out) = newDir(fmt + "-corrupt")
            load(out, fmt, vInt, Nil, "1,1,a")
            load(out, fmt, vBig, Nil, "2,2,b")
            val bad = java.nio.file.Paths.get(java.net.URI.create(out)).resolve("zzz-0001." + fmt)
            Files.write(bad, "not a data file at all".getBytes(StandardCharsets.UTF_8))
            val e = intercept[Exception](read(out, fmt, config(fmt, vBig)))
            assert(String.valueOf(e.getMessage).contains("zzz-0001"), fmt + ": " + e.getMessage)

            val (_, gone) = newDir(fmt + "-deleted")
            load(gone, fmt, vInt, Nil, "1,1,a")
            load(gone, fmt, vBig, Nil, "2,2,b")
            val victim = dataFiles(gone).head
            val e2 = intercept[Exception](readStats(gone, fmt, config(fmt, vBig), hook = () => Files.delete(victim)))
            assert(String.valueOf(e2.getMessage).contains(victim.getFileName.toString), fmt + ": " + e2.getMessage)
        }
    }

    test("cost is bounded: one footer per file and one Spark read per distinct schema; none above the scan cap") {
        for (fmt <- Seq("parquet", "orc")) {
            val (_, out) = newDir(fmt + "-cost")
            (1 to 3).foreach(i => load(out, fmt, vInt, Nil, s"$i,$i,a"))
            (4 to 6).foreach(i => load(out, fmt, vBig, Nil, s"$i,$i,b"))
            val n = dataFiles(out).size
            val (r, st) = readStats(out, fmt, config(fmt, vBig))
            assert(r.rows.size == 6 && st.footersRead == n && st.dataReads == 2, fmt + s": footers ${st.footersRead} of $n, reads ${st.dataReads}")
            val (same, st2) = readStats(newDirWith(fmt, vInt, 4), fmt, config(fmt, vInt))
            assert(same.rows.size == 4 && st2.dataReads == 1, fmt + ": " + st2.dataReads)
            // exactly at the cap the footers are read; one above, none are
            val (_, at) = readStats(out, fmt, config(fmt, vBig), maxScan = n)
            assert(at.footersRead == n, fmt)
            val (_, above) = readStats(newDirWith(fmt, vInt, 4), fmt, config(fmt, vInt), maxScan = 3)
            assert(above.footersRead == 0 && above.dataReads == 1, fmt)
        }
        assert(ObjectStoreQueryUtil.FileSchemaScanMaxFiles == 1000)
    }

    private def newDirWith(fmt: String, fields: List[SchemaField], files: Int): String = {
        val (_, out) = newDir(fmt + "-n")
        (1 to files).foreach(i => load(out, fmt, fields, Nil, s"$i,$i,a"))
        out
    }

    test("above the scan cap, files that disagree on a type fail the query with a clear message, as the plain read failed") {
        val (_, out) = newDir("parquet-above-cap")
        load(out, "parquet", vInt, Nil, "1,1,a")
        load(out, "parquet", vBig, Nil, "2,2,b")
        val e = intercept[Exception](readStats(out, "parquet", config("parquet", vBig), maxScan = 1))
        assert(String.valueOf(e.getMessage).contains("different types"), e.getMessage)
    }

    test("widening edits stay numbers, other differences are text, and the typing does not depend on the limit") {
        val cases = Seq(
            ("int", "bigint", "5", "6", true),
            ("smallint", "int", "5", "6", true),
            ("float", "double", "1.5", "2.5", true),
            ("bigint", "int", "5", "6", true),
            ("decimal(10,2)", "decimal(12,2)", "1.25", "2.50", false), // decimals are always strings in the JSON
            ("int", "string", "5", "x", false),
            ("decimal(10,2)", "decimal(10,3)", "1.25", "2.500", false),
            ("boolean", "string", "true", "y", false)
        )
        for (fmt <- Seq("parquet", "orc"); (a, b, va, vb, numeric) <- cases) {
            val label = fmt + " " + a + "/" + b
            val (_, out) = newDir(fmt + "-widen")
            load(out, fmt, List(SchemaField("id", "int"), SchemaField("c", a)), Nil, "1," + va)
            load(out, fmt, List(SchemaField("id", "int"), SchemaField("c", b)), Nil, "2," + vb)
            val stored = config(fmt, List(SchemaField("id", "int"), SchemaField("c", b)))
            val kinds = Seq(1, 100).map { lim =>
                val r = read(out, fmt, stored, limit = lim)
                assert(r.rows.size == math.min(lim, 2), label)
                r.rows.asScala.map(_.get("c")).map(v => v.isInstanceOf[Number]).toSet
            }
            assert(kinds.forall(_ == Set(numeric)), label + ": " + kinds)
        }
    }
}
