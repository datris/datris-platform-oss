package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.apache.iceberg.hadoop.HadoopTables
import org.apache.iceberg.{Snapshot, Table, TableUtil}
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/** Go/no-go gate for the Iceberg destination: proves the embedded Spark 3.5 /
  *  Scala 2.12 session can create, append to, overwrite, MERGE INTO and evolve
  *  an Iceberg table addressed purely by path (no catalog registration), on the
  *  pinned iceberg-spark-runtime jar.
  *
  *  Expected API surface (plans/stories/iceberg-runtime-and-writer-spec.md, step 4):
  *
  *  {{{
  *  object IcebergWriter {
  *      final case class WriteResult(snapshotId: Long, addedRecords: Long, deletedRecords: Long, totalRecords: Long)
  *      def write(
  *          df: DataFrame,
  *          location: String,          // file:// or s3a:// table root; the Iceberg table lives directly here
  *          writeMode: String,         // append | overwrite | merge | ignore | errorifexists
  *          partitionBy: Seq[String],  // identity partition columns; Nil = unpartitioned
  *          keyFields: Seq[String],    // MERGE ON columns; only read when writeMode == "merge"
  *          destSchema: StructType,    // pipeline dest schema; drives create + evolution
  *          statusUtil: StatusUtil
  *      ): WriteResult
  *  }
  *  }}}
  *
  *  Reader seam (plans/stories/iceberg-loader-reader-lineage.md, steps 6-7).
  *  `ObjectStoreQueryUtil.query(pipelineName, limit)` needs a live config DB,
  *  so the Spark-read body of that method (the code inside its `Future`,
  *  including the empty-location catches) must be lifted into a package-private
  *  seam that this spec drives directly with a file:// path:
  *
  *  {{{
  *  object ObjectStoreQueryUtil {
  *      case class QueryResult(
  *          columns: java.util.List[String],
  *          rows: java.util.List[java.util.Map[String, Any]],
  *          path: String,
  *          format: String,
  *          snapshotId: java.lang.Long = null,       // iceberg only; null for parquet/orc
  *          snapshotTimestamp: String = null          // ISO-8601 instant; iceberg only
  *      )
  *      // limit is already capped by query(); path is s3a:// in production, file:// here.
  *      private[util] def readPath(spark: SparkSession, path: String, format: String, limit: Int): QueryResult
  *  }
  *  }}}
  *
  *  A location with no table (no `metadata/` for iceberg; PATH_NOT_FOUND for
  *  parquet/orc) returns an empty QueryResult from `readPath`, never throws.
  *
  *  The writer must use `df.sparkSession` (not `SparkSessionManager.getOrCreate()`),
  *  because that is what the pipeline hands it and it is what lets this spec
  *  run without a DatrisEnvironment. SparkSessionManager sets five Iceberg
  *  settings; the session below carries only two of them (the extension and
  *  the `datris` hadoop catalog). It deliberately omits the `default_iceberg`
  *  hadoop catalog and the `datris_cache` cached-table catalog, so every test
  *  here also exercises IcebergWriter.ensureCatalogs, the fallback that
  *  defines them at runtime for sessions built outside SparkSessionManager.
  *
  *  Hadoop 3.3.4's UserGroupInformation calls Subject.getSubject, which JDK 24+
  *  rejects unconditionally (JEP 486 also refuses -Djava.security.manager=allow
  *  at JVM startup), so a local SparkContext cannot start there. CI and the
  *  Docker runtime are Temurin 17; on a newer dev JDK point sbt at a 17/21 JDK:
  *    JAVA_HOME=/path/to/jdk-17 sbt "testOnly ai.datris.util.IcebergWriterSpec"
  */
class IcebergWriterSpec extends AnyFunSuite with BeforeAndAfterAll {

    private var warehouse: Path = _
    private var spark: SparkSession = _

    private class RecordingStatusUtil extends StatusUtil {
        val messages = new ListBuffer[(String, String)]()
        override def overrideProcessName(processName: String): Unit = ()
        override def info(state: String, description: String): Unit = messages += ((state, description))
        override def warn(state: String, description: String): Unit = messages += ((state, description))
        override def error(state: String, description: String): Unit = messages += ((state, description))
    }

    override def beforeAll(): Unit = {
        warehouse = Files.createTempDirectory("iceberg-writer-spec")
        spark = SparkSession.builder()
            .master("local[2]")
            .appName("IcebergWriterSpec")
            .config("spark.ui.enabled", "false")
            .config("spark.sql.shuffle.partitions", "2")
            .config("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
            .config("spark.sql.catalog.datris", "org.apache.iceberg.spark.SparkCatalog")
            .config("spark.sql.catalog.datris.type", "hadoop")
            .config("spark.sql.catalog.datris.warehouse", warehouse.toUri.toString)
            .getOrCreate()
    }

    override def afterAll(): Unit = {
        if (spark != null) spark.stop()
    }

    // ---- fixtures -------------------------------------------------------

    private val baseSchema = StructType(Seq(
        StructField("id", LongType, nullable = false),
        StructField("region", StringType, nullable = true),
        StructField("amount", DoubleType, nullable = true)
    ))

    private def df(rows: (Long, String, Double)*): DataFrame =
        spark.createDataFrame(
            rows.map { case (i, r, a) => Row(i, r, a) }.asJava,
            baseSchema
        )

    /** A fresh file:// table root under the temp warehouse. */
    private def newLocation(name: String): String = {
        val dir = warehouse.resolve(name)
        Files.createDirectories(dir.getParent)
        dir.toUri.toString.stripSuffix("/")
    }

    private def loadTable(location: String): Table = new HadoopTables(spark.sessionState.newHadoopConf()).load(location)

    private def snapshots(location: String): List[Snapshot] = loadTable(location).snapshots().asScala.toList

    private def readAll(location: String): DataFrame = spark.read.format("iceberg").load(location)

    private def write(
        data: DataFrame,
        location: String,
        writeMode: String,
        partitionBy: Seq[String] = Nil,
        keyFields: Seq[String] = Nil,
        destSchema: StructType = baseSchema,
        status: StatusUtil = new RecordingStatusUtil
    ): IcebergWriter.WriteResult =
        IcebergWriter.write(data, location, writeMode, partitionBy, keyFields, destSchema, status)

    // ---- Acceptance bullet 1: create + two appends ----------------------

    test("create + two appends -> 2 snapshots, row count correct, result carries snapshot id and counts") {
        val location = newLocation("append/t")

        val first = write(df((1L, "east", 1.0), (2L, "west", 2.0)), location, "append")
        val second = write(df((3L, "east", 3.0)), location, "append")

        val snaps = snapshots(location)
        assert(snaps.size == 2, s"expected 2 snapshots after create + 2 appends, got ${snaps.size}")
        assert(readAll(location).count() == 3)

        val table = loadTable(location)
        // format-version is a reserved property: Iceberg consumes it at create
        // time and never persists it in properties() (TableMetadata strips
        // RESERVED_PROPERTIES), so read it through the public TableUtil API.
        assert(TableUtil.formatVersion(table) == 2, s"expected format-version 2, got ${TableUtil.formatVersion(table)}")
        assert(table.properties().get("write.format.default") == "parquet")
        assert(table.properties().containsKey("datris.pipeline"), "table property datris.pipeline must be set on create")

        assert(second.snapshotId == table.currentSnapshot().snapshotId())
        assert(first.addedRecords == 2)
        assert(second.addedRecords == 1)
        assert(second.totalRecords == 3)
    }

    // ---- Acceptance bullet 2: overwrite ---------------------------------

    test("overwrite -> old rows gone, exactly one new snapshot, table never observed empty") {
        val location = newLocation("overwrite/t")
        write(df((1L, "east", 1.0), (2L, "west", 2.0)), location, "append")
        val before = snapshots(location).size

        write(df((9L, "north", 9.0)), location, "overwrite")

        val rows = readAll(location).collect().map(_.getLong(0)).toSet
        assert(rows == Set(9L), s"old rows must be gone after overwrite, got $rows")

        val snaps = snapshots(location)
        assert(snaps.size == before + 1, s"overwrite must commit exactly one snapshot, got ${snaps.size - before}")

        // Every committed state must hold rows: no intermediate "truncate then
        // insert" snapshot where a concurrent reader would see an empty table.
        snaps.foreach { s =>
            val total = Option(s.summary().get("total-records")).map(_.toLong).getOrElse(-1L)
            assert(total > 0, s"snapshot ${s.snapshotId()} (${s.operation()}) exposes an empty table: summary=${s.summary()}")
        }
    }

    test("partitioned overwrite replaces only the partitions present in the new rows") {
        val location = newLocation("overwrite-partitioned/t")
        write(df((1L, "east", 1.0), (2L, "west", 2.0)), location, "append", partitionBy = Seq("region"))
        val before = snapshots(location).size

        write(df((9L, "east", 9.0)), location, "overwrite", partitionBy = Seq("region"))

        val byRegion = readAll(location).collect().groupBy(_.getString(1)).mapValues(_.map(_.getLong(0)).toSet)
        assert(byRegion.get("west") == Some(Set(2L)), s"untouched partition must survive, got $byRegion")
        assert(byRegion.get("east") == Some(Set(9L)), s"overwritten partition must hold only the new rows, got $byRegion")

        val snaps = snapshots(location)
        assert(snaps.size == before + 1, s"partitioned overwrite must commit exactly one snapshot, got ${snaps.size - before}")
        snaps.foreach { s =>
            val total = Option(s.summary().get("total-records")).map(_.toLong).getOrElse(-1L)
            assert(total > 0, s"snapshot ${s.snapshotId()} (${s.operation()}) exposes an empty table: summary=${s.summary()}")
        }
    }

    // ---- Acceptance bullet 3: merge (go/no-go gate) ----------------------

    test("merge on keyFields -> matched row updated, unmatched row inserted, snapshot count +1") {
        val location = newLocation("merge/t")
        write(df((1L, "east", 1.0), (2L, "west", 2.0)), location, "append")
        val before = snapshots(location).size

        val result = write(df((2L, "west", 22.0), (3L, "north", 3.0)), location, "merge", keyFields = Seq("id"))

        val byId = readAll(location).collect().map(r => r.getLong(0) -> (r.getString(1), r.getDouble(2))).toMap
        assert(byId.size == 3, s"expected 3 rows after merge, got ${byId.keySet}")
        assert(byId(1L) == ("east", 1.0), "untouched row must survive merge")
        assert(byId(2L) == ("west", 22.0), "matched row must be updated")
        assert(byId(3L) == ("north", 3.0), "unmatched row must be inserted")

        val snaps = snapshots(location)
        assert(snaps.size == before + 1, s"merge must commit exactly one snapshot, got ${snaps.size - before}")
        assert(result.snapshotId == loadTable(location).currentSnapshot().snapshotId())
    }

    // ---- Acceptance bullet 4: partitioned append ------------------------

    test("partitioned append lands under data/<field>=<value>/") {
        val location = newLocation("partitioned/t")

        write(df((1L, "east", 1.0), (2L, "west", 2.0), (3L, "east", 3.0)), location, "append", partitionBy = Seq("region"))

        val root = java.nio.file.Paths.get(java.net.URI.create(location))
        val east = root.resolve("data").resolve("region=east")
        val west = root.resolve("data").resolve("region=west")
        assert(Files.isDirectory(east), s"expected partition dir $east")
        assert(Files.isDirectory(west), s"expected partition dir $west")
        assert(Files.list(east).iterator().asScala.exists(_.toString.endsWith(".parquet")), "partition dir must hold parquet data files")

        val spec = loadTable(location).spec()
        assert(spec.fields().size() == 1 && spec.fields().get(0).name() == "region", s"identity partition spec expected, got $spec")
        assert(readAll(location).filter("region = 'east'").count() == 2)
    }

    // ---- Acceptance bullet 5: schema evolution ---------------------------

    test("add-column evolution succeeds") {
        val location = newLocation("evolve-add/t")
        write(df((1L, "east", 1.0)), location, "append")

        val evolved = baseSchema.add(StructField("note", StringType, nullable = true))
        val evolvedDf = spark.createDataFrame(Seq(Row(2L, "west", 2.0, "hello")).asJava, evolved)

        write(evolvedDf, location, "append", destSchema = evolved)

        val cols = loadTable(location).schema().columns().asScala.map(_.name()).toList
        assert(cols.contains("note"), s"table schema must gain 'note', got $cols")

        val rows = readAll(location).collect().map(r => r.getLong(0) -> r.getAs[String]("note")).toMap
        assert(rows(1L) == null, "pre-existing rows read the new column as null")
        assert(rows(2L) == "hello")
    }

    test("types Iceberg widens on create (byte/short -> int) are not reported as a type change on later writes") {
        val location = newLocation("evolve-narrow/t")
        val narrow = StructType(Seq(
            StructField("id", LongType, nullable = false),
            StructField("tiny", ByteType, nullable = true),
            StructField("small", ShortType, nullable = true)
        ))
        def rows(i: Long, b: Byte, sh: Short): DataFrame =
            spark.createDataFrame(Seq(Row(i, b, sh)).asJava, narrow)

        write(rows(1L, 1.toByte, 10.toShort), location, "append", destSchema = narrow)
        write(rows(2L, 2.toByte, 20.toShort), location, "append", destSchema = narrow)

        assert(snapshots(location).size == 2)
        val collected = readAll(location).collect().map(r => r.getLong(0) -> (r.getInt(1), r.getInt(2))).toMap
        assert(collected == Map(1L -> (1, 10), 2L -> (2, 20)), s"unexpected rows: $collected")
    }

    test("type change fails with a readable message") {
        val location = newLocation("evolve-type/t")
        write(df((1L, "east", 1.0)), location, "append")

        val changed = StructType(Seq(
            StructField("id", LongType, nullable = false),
            StructField("region", StringType, nullable = true),
            StructField("amount", StringType, nullable = true) // double -> string
        ))
        val changedDf = spark.createDataFrame(Seq(Row(2L, "west", "2.0")).asJava, changed)

        val e = intercept[DatrisException] {
            write(changedDf, location, "append", destSchema = changed)
        }
        val msg = e.getMessage
        assert(msg.contains("amount"), s"message must name the column: $msg")
        assert(msg.toLowerCase.contains("type"), s"message must say it is a type change: $msg")

        // A refused evolution must not have committed anything.
        assert(snapshots(location).size == 1)
    }

    // ---- Acceptance bullet 6: non-Iceberg prefix guard -------------------

    test("non-Iceberg prefix guard refuses with the 'prefix contains non-Iceberg files' message") {
        val location = newLocation("guard/t")
        val root = java.nio.file.Paths.get(java.net.URI.create(location))
        Files.createDirectories(root)
        Files.write(root.resolve("part-00000.parquet"), Array[Byte](1, 2, 3))

        val e = intercept[DatrisException] {
            write(df((1L, "east", 1.0)), location, "append")
        }
        assert(e.getMessage.contains("prefix contains non-Iceberg files"), e.getMessage)
        assert(e.getMessage.contains("deleteBeforeWrite"), e.getMessage)
        assert(!Files.exists(root.resolve("metadata")), "guard must fire before any table is created")
    }

    // ---- Step 4: ignore / errorifexists keep today's semantics -----------

    test("ignore -> no-op when the table exists, creates when absent") {
        val location = newLocation("ignore/t")
        write(df((1L, "east", 1.0)), location, "ignore")
        assert(readAll(location).count() == 1)

        write(df((2L, "west", 2.0)), location, "ignore")
        assert(readAll(location).count() == 1, "ignore must not add rows to an existing table")
        assert(snapshots(location).size == 1)
    }

    test("errorifexists -> refuses when the table exists") {
        val location = newLocation("errorifexists/t")
        write(df((1L, "east", 1.0)), location, "errorifexists")

        intercept[Exception] {
            write(df((2L, "west", 2.0)), location, "errorifexists")
        }
        assert(readAll(location).count() == 1)
    }

    // ---- Story 3 (iceberg-loader-reader-lineage): reader via ObjectStoreQueryUtil.readPath ----

    test("readPath(iceberg) returns the committed rows and the current snapshot id") {
        val location = newLocation("reader/t")
        write(df((1L, "east", 1.0), (2L, "west", 2.0)), location, "append")
        val second = write(df((3L, "north", 3.0)), location, "append")

        val result = ObjectStoreQueryUtil.readPath(spark, location, "iceberg", 100)

        assert(result.format == "iceberg")
        assert(result.path == location)
        assert(result.columns.asScala.toList == List("id", "region", "amount"), s"columns were ${result.columns}")
        assert(result.rows.size() == 3, s"expected 3 rows, got ${result.rows.size()}")
        val byId = result.rows.asScala.map(r => r.get("id").asInstanceOf[Number].longValue() -> r.get("region")).toMap
        assert(byId == Map(1L -> "east", 2L -> "west", 3L -> "north"), s"rows were $byId")

        assert(result.snapshotId != null, "iceberg read must report the snapshot it read")
        assert(result.snapshotId.longValue() == second.snapshotId, s"expected snapshot ${second.snapshotId}, got ${result.snapshotId}")
        assert(result.snapshotId.longValue() == loadTable(location).currentSnapshot().snapshotId())
        assert(result.snapshotTimestamp != null && result.snapshotTimestamp.nonEmpty, "iceberg read must report the snapshot timestamp")
        // Parses as an ISO-8601 instant, consistent with sparkValueToJson's timestamp rendering.
        java.time.Instant.parse(result.snapshotTimestamp)
    }

    test("readPath(iceberg) honours the row limit") {
        val location = newLocation("reader-limit/t")
        write(df((1L, "east", 1.0), (2L, "west", 2.0), (3L, "north", 3.0)), location, "append")

        val result = ObjectStoreQueryUtil.readPath(spark, location, "iceberg", 2)
        assert(result.rows.size() == 2, s"limit 2 must return 2 rows, got ${result.rows.size()}")
        assert(result.snapshotId != null)
    }

    test("readPath(iceberg) on a location with no metadata/ returns an empty result, not an exception") {
        val location = newLocation("reader-empty/t")
        val root = java.nio.file.Paths.get(java.net.URI.create(location))
        Files.createDirectories(root) // prefix exists, but nothing was ever committed
        assert(!Files.exists(root.resolve("metadata")))

        val result = ObjectStoreQueryUtil.readPath(spark, location, "iceberg", 100)

        assert(result.rows.isEmpty, s"expected 0 rows, got ${result.rows.size()}")
        assert(result.columns.isEmpty)
        assert(result.format == "iceberg")
        assert(result.path == location)
        assert(result.snapshotId == null, "no table => no snapshot")
        assert(result.snapshotTimestamp == null)
    }

    test("readPath(iceberg) on a prefix that does not exist at all returns an empty result") {
        val location = newLocation("reader-missing/t")
        assert(!Files.exists(java.nio.file.Paths.get(java.net.URI.create(location))))

        val result = ObjectStoreQueryUtil.readPath(spark, location, "iceberg", 100)
        assert(result.rows.isEmpty && result.columns.isEmpty)
        assert(result.snapshotId == null)
    }

    test("readPath(iceberg) on a prefix whose metadata/ exists but holds no loadable table throws, not empty") {
        // Pins the contract "empty only when metadata/ is absent": once
        // metadata/ exists, load failures (corrupt table, and in production a
        // denied read that HadoopTables would otherwise swallow) must surface.
        val location = newLocation("reader-garbage/t")
        val meta = java.nio.file.Paths.get(java.net.URI.create(location)).resolve("metadata")
        Files.createDirectories(meta)
        Files.write(meta.resolve("version-hint.text"), "not-a-number".getBytes)
        Files.write(meta.resolve("v1.metadata.json"), "{garbage".getBytes)

        intercept[Exception] {
            ObjectStoreQueryUtil.readPath(spark, location, "iceberg", 100)
        }
    }

    // ---- Story 3 handoff: parquet/orc never-run pipeline => 0 rows, not PATH_NOT_FOUND / HTTP 500 ----

    test("readPath(parquet) on a never-written path returns 0 rows instead of raising PATH_NOT_FOUND") {
        val location = newLocation("reader-parquet-missing/t")
        assert(!Files.exists(java.nio.file.Paths.get(java.net.URI.create(location))))

        // Today Spark 3.5 raises AnalysisException[PATH_NOT_FOUND] here, which
        // query_objectstore surfaces as HTTP 500. The seam must swallow it.
        val result = ObjectStoreQueryUtil.readPath(spark, location, "parquet", 100)

        assert(result.rows.isEmpty, s"expected 0 rows, got ${result.rows.size()}")
        assert(result.columns.isEmpty)
        assert(result.format == "parquet")
        assert(result.path == location)
    }

    test("readPath(orc) on a never-written path returns 0 rows instead of raising PATH_NOT_FOUND") {
        val location = newLocation("reader-orc-missing/t")
        val result = ObjectStoreQueryUtil.readPath(spark, location, "orc", 100)
        assert(result.rows.isEmpty && result.columns.isEmpty)
        assert(result.format == "orc")
    }

    // ---- Story 3: parquet QueryResult still serialises with null snapshot fields ----

    test("readPath(parquet) on real parquet data returns rows with null snapshot fields, and Gson omits/nulls them") {
        val location = newLocation("reader-parquet/t")
        df((1L, "east", 1.0), (2L, "west", 2.0)).write.mode("overwrite").format("parquet").save(location)

        val result = ObjectStoreQueryUtil.readPath(spark, location, "parquet", 100)

        assert(result.rows.size() == 2)
        assert(result.columns.asScala.toList == List("id", "region", "amount"))
        assert(result.format == "parquet")
        assert(result.snapshotId == null, "parquet must not carry a snapshot id")
        assert(result.snapshotTimestamp == null, "parquet must not carry a snapshot timestamp")

        // The REST/MCP layer hands QueryResult to Gson; the additive fields must
        // not break that. Default Gson drops null members, so existing
        // consumers see exactly the four fields they saw before.
        val json = new com.google.gson.Gson().toJson(result)
        val obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject
        assert(obj.has("columns") && obj.has("rows") && obj.has("path") && obj.has("format"), json)
        assert(!obj.has("snapshotId") || obj.get("snapshotId").isJsonNull, s"snapshotId must serialise as null/absent for parquet: $json")
        assert(!obj.has("snapshotTimestamp") || obj.get("snapshotTimestamp").isJsonNull, s"snapshotTimestamp must serialise as null/absent for parquet: $json")

        // And with serializeNulls (the shape the story calls "null snapshot fields") they are explicit nulls.
        val withNulls = new com.google.gson.GsonBuilder().serializeNulls().create().toJson(result)
        val obj2 = com.google.gson.JsonParser.parseString(withNulls).getAsJsonObject
        assert(obj2.has("snapshotId") && obj2.get("snapshotId").isJsonNull, withNulls)
        assert(obj2.has("snapshotTimestamp") && obj2.get("snapshotTimestamp").isJsonNull, withNulls)
    }

    test("readPath(iceberg) result serialises the snapshot id as a JSON number") {
        val location = newLocation("reader-json/t")
        val w = write(df((1L, "east", 1.0)), location, "append")
        val result = ObjectStoreQueryUtil.readPath(spark, location, "iceberg", 100)
        val obj = com.google.gson.JsonParser.parseString(new com.google.gson.Gson().toJson(result)).getAsJsonObject
        assert(obj.has("snapshotId") && obj.get("snapshotId").getAsLong == w.snapshotId, obj.toString)
        assert(obj.has("snapshotTimestamp") && obj.get("snapshotTimestamp").getAsString.nonEmpty, obj.toString)
    }
}
