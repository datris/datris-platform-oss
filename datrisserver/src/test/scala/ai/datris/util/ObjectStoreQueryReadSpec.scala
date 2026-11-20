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
  *     private[util] def readSchemaFor(config: PipelineConfig): Option[StructType]
  *     private[util] def readPath(spark, path, format, limit, resolved, bucket, readSchema: Option[StructType]): QueryResult
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

    test("a column in the stored schema that no file holds yet reads as null; a column removed from the schema is not returned") {
        for (fmt <- Seq("parquet", "orc")) {
            val (_, out) = newDir(fmt + "-removed")
            val withOld = f1 :+ SchemaField("old", "string")
            load(out, fmt, withOld, Nil, "1,east,1.5,o1")
            val stored = f1 :+ SchemaField("later", "string")
            val r = read(out, fmt, config(fmt, stored))
            assert(r.columns.asScala.toList == List("id", "region", "amount", "later"), fmt + ": " + r.columns)
            assert(r.rows.asScala.head.get("later") == null && !r.rows.asScala.head.containsKey("old"), fmt + ": " + r.rows)
        }
    }

    test("a column whose type changed between files fails the read clearly instead of returning wrong values") {
        for (fmt <- Seq("parquet", "orc")) {
            val (_, out) = newDir(fmt + "-typed")
            load(out, fmt, List(SchemaField("id", "int"), SchemaField("code", "string")), Nil, "1,abc")
            load(out, fmt, List(SchemaField("id", "int"), SchemaField("code", "int")), Nil, "2,7")
            val e = intercept[Exception](read(out, fmt, config(fmt, List(SchemaField("id", "int"), SchemaField("code", "int")))))
            val text = Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null).map(t => String.valueOf(t.getMessage)).mkString(" | ")
            assert(text.contains("cannot be converted") || text.contains("CANNOT_MERGE") || text.contains("merge"), fmt + ": " + text.take(400))
        }
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
            // no provenance block: no stamp columns are asked for
            assert(read(out, fmt, config(fmt, f1)).columns.asScala.toList == List("id", "region", "amount"), fmt)
        }
    }

    test("readSchemaFor follows the stored destination schema and its types, and is None without fields") {
        val s = ObjectStoreQueryUtil.readSchemaFor(config("parquet", List(SchemaField("id", "int"), SchemaField("a.b", "string")))).get
        assert(s.fieldNames.toList == List("id", "a.b"))
        assert(s("id").dataType == org.apache.spark.sql.types.IntegerType)
        assert(ObjectStoreQueryUtil.readSchemaFor(config("parquet", Nil)).isEmpty)
    }

    test("without a read schema (no stored config) the files' schemas are merged") {
        val (_, out) = newDir("merge")
        load(out, "parquet", f1, Nil, "1,east,1.5")
        load(out, "parquet", f1 :+ SchemaField("extra", "string"), Nil, "2,west,2.5,e2")
        val r = ObjectStoreQueryUtil.readPath(spark, out, "parquet", 100)
        assert(r.columns.asScala.toSet == Set("id", "region", "amount", "extra"), r.columns)
    }
}
