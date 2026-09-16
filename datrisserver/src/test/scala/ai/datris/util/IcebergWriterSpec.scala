package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.apache.iceberg.hadoop.HadoopTables
import org.apache.iceberg.{Snapshot, Table}
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
  *  The writer must use `df.sparkSession` (not `SparkSessionManager.getOrCreate()`),
  *  because that is what the pipeline hands it and it is what lets this spec
  *  run without a DatrisEnvironment. The session below carries exactly the two
  *  settings the story adds to SparkSessionManager (extension + `datris` hadoop
  *  catalog) so the path-identifier MERGE form is exercised the same way it will
  *  be in production.
  *
  *  Hadoop 3.3.4's UserGroupInformation calls Subject.getSubject, which throws
  *  on JDK 24+ unless the JVM is started with -Djava.security.manager=allow.
  *  CI and the Docker runtime are JDK 17; on a newer dev JDK run:
  *    sbt 'set Test / javaOptions += "-Djava.security.manager=allow"' "testOnly ai.datris.util.IcebergWriterSpec"
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
        assert(table.properties().get("format-version") == "2")
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
}
