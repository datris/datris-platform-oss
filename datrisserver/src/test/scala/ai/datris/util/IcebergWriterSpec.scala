package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.apache.iceberg.catalog.{Catalog, TableIdentifier}
import org.apache.iceberg.hadoop.{HadoopCatalog, HadoopTables}
import org.apache.iceberg.{HasTableOperations, Schema, Snapshot, Table, TableUtil}
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
  *  pinned iceberg-spark-runtime jar. The "rest target" cases at the end
  *  (Unity Catalog 5, `catalogMode: rest`) cover the one exception: with a
  *  `RestTarget` every commit goes through a catalog identifier, exercised
  *  against a path-backed HadoopCatalog stand-in instead of a REST server.
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
  *          snapshotId: java.lang.Long = null,       // iceberg only; null for parquet and orc
  *          snapshotTimestamp: String = null          // ISO-8601 instant; iceberg only
  *      )
  *      // limit is already capped by query(); path is s3a:// in production, file:// here.
  *      private[util] def readPath(spark: SparkSession, path: String, format: String, limit: Int): QueryResult
  *  }
  *  }}}
  *
  *  A location with no table (no `metadata/` for iceberg; PATH_NOT_FOUND for
  *  non-iceberg, i.e. a plain parquet or an ORC prefix) returns an empty QueryResult from `readPath`, never throws.
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
/** Path-backed stand-in for the Unity Catalog REST catalog (Unity Catalog 5,
  *  Step 5): a HadoopCatalog that records, per catalog name, every table it
  *  loads and every table builder it hands out. Spark instantiates it through
  *  `spark.sql.catalog.<name>.catalog-impl` (so `writeTo` / `MERGE INTO`
  *  against `<name>.<schema>.<table>` show up as loads under `<name>`), and the
  *  spec instantiates a second one directly as `RestTarget.catalog`. */
class RecordingHadoopCatalog extends HadoopCatalog {
    override def loadTable(ident: TableIdentifier): Table = {
        RecordingHadoopCatalog.loads.add(name() + ":" + ident)
        super.loadTable(ident)
    }
    override def buildTable(ident: TableIdentifier, schema: Schema): Catalog.TableBuilder = {
        RecordingHadoopCatalog.builds.add(name() + ":" + ident)
        super.buildTable(ident, schema)
    }
}

object RecordingHadoopCatalog {
    val loads = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val builds = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    def clear(): Unit = { loads.clear(); builds.clear() }
}

/** Stand-in for a catalog that ignores the requested location (Databricks
  *  creates a managed Iceberg table in the schema's storage): the table
  *  builder drops `withLocation`, so the table lands at the HadoopCatalog
  *  default `<warehouse>/<schema>/<table>`. */
class IgnoringLocationCatalog extends HadoopCatalog {
    override def buildTable(ident: TableIdentifier, schema: Schema): Catalog.TableBuilder = {
        val inner = super.buildTable(ident, schema)
        new Catalog.TableBuilder {
            override def withPartitionSpec(spec: org.apache.iceberg.PartitionSpec): Catalog.TableBuilder = { inner.withPartitionSpec(spec); this }
            override def withSortOrder(order: org.apache.iceberg.SortOrder): Catalog.TableBuilder = { inner.withSortOrder(order); this }
            override def withLocation(location: String): Catalog.TableBuilder = this
            override def withProperties(props: java.util.Map[String, String]): Catalog.TableBuilder = { inner.withProperties(props); this }
            override def withProperty(key: String, value: String): Catalog.TableBuilder = { inner.withProperty(key, value); this }
            override def create(): Table = inner.create()
            override def createTransaction(): org.apache.iceberg.Transaction = inner.createTransaction()
            override def replaceTransaction(): org.apache.iceberg.Transaction = inner.replaceTransaction()
            override def createOrReplaceTransaction(): org.apache.iceberg.Transaction = inner.createOrReplaceTransaction()
        }
    }
}

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

    // Story: Unity Catalog 4: Iceberg register spike (plans/stories/unity-catalog-4-iceberg-register.md),
    // Acceptance bullet 3. WriteResult gains `metadataLocation: String = null`,
    // read from HasTableOperations.operations().current().metadataFileLocation().
    test("result carries the current metadata file location, which advances on the next commit") {
        val location = newLocation("metadata-location/t")

        val first = write(df((1L, "east", 1.0)), location, "append")
        assert(first.metadataLocation != null, s"$first")
        assert(first.metadataLocation.endsWith(".metadata.json"), first.metadataLocation)
        assert(first.metadataLocation.contains("/metadata/"), first.metadataLocation)
        val onDisk = new java.io.File(java.net.URI.create(first.metadataLocation))
        assert(onDisk.isFile, s"metadata file must exist on disk: ${first.metadataLocation}")
        // Under this table's root.
        val root = new java.io.File(java.net.URI.create(location)).getCanonicalPath
        assert(onDisk.getCanonicalPath.startsWith(root + java.io.File.separator + "metadata" + java.io.File.separator), s"${onDisk.getCanonicalPath} vs $root")

        val second = write(df((2L, "west", 2.0)), location, "append")
        assert(second.metadataLocation != null && second.metadataLocation != first.metadataLocation, s"$first vs $second")
        assert(new java.io.File(java.net.URI.create(second.metadataLocation)).isFile, second.metadataLocation)

        val current = loadTable(location).asInstanceOf[org.apache.iceberg.HasTableOperations].operations().current().metadataFileLocation()
        assert(second.metadataLocation == current, s"${second.metadataLocation} vs $current")
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

    // ---- v1.32.0 E2E: session created on another thread ---------------------
    //
    // In the server the shared SparkSession is created by whichever thread
    // first asks for it (a Tomcat request thread after a restart, typically);
    // pipeline runs then execute on a JobRunner thread that never created it.
    // Spark's CatalogManager resolves `spark.sql.catalog.<name>` through
    // SQLConf.get, which reads the thread-ACTIVE session only (no fallback to
    // the default session), so a DataFrame Iceberg write on such a thread
    // failed with "Catalog 'default_iceberg' plugin class not found". The
    // session here is created in beforeAll on the ScalaTest thread; a plain
    // `new Thread` has no active session, which reproduces the server state.

    /** Run `body` on a thread that has never created or activated a session.
      *  The active session is an InheritableThreadLocal, so a thread spawned
      *  here would inherit the ScalaTest thread's; server pool threads are
      *  created before the session exists and inherit nothing — clear it to
      *  model that. */
    private def onForeignThread[T](body: => T): T = {
        var result: Option[T] = None
        var failure: Option[Throwable] = None
        val t = new Thread(
            () => {
                SparkSession.clearActiveSession()
                try result = Some(body)
                catch { case e: Throwable => failure = Some(e) }
            },
            "iceberg-spec-foreign"
        )
        t.start()
        t.join(120000)
        failure.foreach(e => throw e)
        result.getOrElse(fail("foreign thread did not finish"))
    }

    test("append and overwrite succeed on a thread that did not create the SparkSession") {
        val location = newLocation("foreign-thread/t")

        val appended = onForeignThread {
            assert(SparkSession.getActiveSession.isEmpty, "precondition: foreign thread must start with no active session")
            write(df((1L, "east", 1.0), (2L, "west", 2.0)), location, "append")
        }
        assert(appended.snapshotId == loadTable(location).currentSnapshot().snapshotId())
        assert(appended.addedRecords == 2)
        assert(readAll(location).count() == 2)

        val overwritten = onForeignThread {
            write(df((3L, "north", 3.0)), location, "overwrite")
        }
        assert(overwritten.snapshotId != appended.snapshotId)
        assert(overwritten.totalRecords == 1)
        assert(readAll(location).collect().map(_.getLong(0)).toList == List(3L))

        // The reader runs on its own thread pool in the server (never the
        // session creator) — same requirement.
        val read = onForeignThread { ObjectStoreQueryUtil.readPath(spark, location, "iceberg", 100) }
        assert(read.rows.size() == 1)
        assert(read.snapshotId != null && read.snapshotId.longValue() == overwritten.snapshotId)
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

    // ---- Story 3 handoff: non-iceberg never-run pipeline => 0 rows, not PATH_NOT_FOUND / HTTP 500 ----

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

    // ---- Story objectstore-endpoint-ssrf-and-error-bodies, B4: table-location authority check ----
    // CONDITIONAL: story step 8 says B4 is done only if steps 1-7 leave room in
    // the session; if the implementer defers B4 these two cases stay red and the
    // lead decides. After `HadoopTables.load(path)` (ObjectStoreQueryUtil.readIceberg
    // and IcebergWriter.write's `exists` load), `table.location()` must normalise
    // to the same scheme+authority+path as the requested location, and the
    // current snapshot's manifest-list must share that scheme+authority;
    // otherwise a DatrisException naming the mismatch. On file:// scheme and
    // authority are always equal, so the planted mismatch is a PATH mismatch:
    // a real table is written at A and its metadata/ copied verbatim under B,
    // so B's metadata says `"location": A` and its manifest lists live under A.
    // Today B loads fine and silently reads (and would write) A's data.

    /** Write a real table at `<name>/a`, copy its metadata/ to `<name>/b`, return (a, b). */
    private def plantRelocatedMetadata(name: String): (String, String) = {
        val a = newLocation(name + "/a")
        val b = newLocation(name + "/b")
        write(df((1L, "east", 1.0), (2L, "west", 2.0)), a, "append")

        val aMeta = java.nio.file.Paths.get(java.net.URI.create(a)).resolve("metadata")
        val bMeta = java.nio.file.Paths.get(java.net.URI.create(b)).resolve("metadata")
        Files.createDirectories(bMeta)
        Files.list(aMeta).iterator().asScala.foreach { f =>
            Files.copy(f, bMeta.resolve(f.getFileName.toString))
        }
        // Sanity: B has metadata only, no data/ of its own, and it loads with A's location.
        assert(!Files.exists(java.nio.file.Paths.get(java.net.URI.create(b)).resolve("data")))
        assert(loadTable(b).location().stripSuffix("/") == a, s"planting failed: expected B's metadata to claim location $a")
        (a, b)
    }

    test("B4: readPath(iceberg) refuses a table whose metadata points at a different location") {
        val (a, b) = plantRelocatedMetadata("authority-read")

        val e = intercept[DatrisException] {
            ObjectStoreQueryUtil.readPath(spark, b, "iceberg", 100)
        }
        val msg = e.getMessage
        assert(msg.contains(b) || msg.contains(a), s"message must name the mismatching locations: $msg")
    }

    test("B4: IcebergWriter.write refuses an existing table whose metadata points at a different location") {
        val (a, b) = plantRelocatedMetadata("authority-write")
        val aSnapshotsBefore = snapshots(a).size

        val e = intercept[DatrisException] {
            write(df((3L, "north", 3.0)), b, "append")
        }
        val msg = e.getMessage
        assert(msg.contains(b) || msg.contains(a), s"message must name the mismatching locations: $msg")
        // Nothing may have been written through B into A's table.
        assert(snapshots(a).size == aSnapshotsBefore, "a refused write must not commit to the real table")
        assert(readAll(a).count() == 2)
    }

    test("B4: a normally-created file:// table passes the location check on read and write") {
        val location = newLocation("authority-ok/t")
        write(df((1L, "east", 1.0)), location, "append")
        write(df((2L, "west", 2.0)), location, "append")
        val result = ObjectStoreQueryUtil.readPath(spark, location, "iceberg", 100)
        assert(result.rows.size() == 2)
    }

    // ---- Story: Unity Catalog 5: Iceberg via RESTCatalog --------------------
    // (plans/stories/unity-catalog-5-iceberg-restcatalog.md), Acceptance bullet 3.
    //
    // Seam this block pins:
    //   IcebergWriter.RestTarget(sparkCatalogName: String, ident: TableIdentifier,
    //                            catalog: org.apache.iceberg.catalog.Catalog)
    //   IcebergWriter.write(df, location, writeMode, partitionBy, keyFields, destSchema,
    //                       statusUtil, pipelineName, restCatalog: Option[RestTarget]): WriteResult
    //
    // The stand-in: a Spark catalog `catalog-impl=RecordingHadoopCatalog` over
    // a temp warehouse (so Spark SQL resolves `<name>.<schema>.<table>`), and a
    // second RecordingHadoopCatalog instance over the same warehouse as
    // RestTarget.catalog. HadoopCatalog only accepts its default location
    // (<warehouse>/<schema>/<table>), so that is the location the writer is
    // given. Loads recorded under the Spark catalog's name prove append and
    // MERGE addressed the identifier; a path write (format("iceberg").save)
    // resolves through default_iceberg and would not appear there.

    private val RestSparkCatalog = "datris_uc_writer_spec"
    private val RestJavaCatalog = "java_side"
    private val RestSchema = "sales"

    private lazy val restWarehouse: String = {
        val dir = Files.createDirectories(warehouse.resolve("rest-warehouse")).toRealPath()
        val uri = dir.toUri.toString.stripSuffix("/")
        val root = "spark.sql.catalog." + RestSparkCatalog
        spark.conf.set(root, "org.apache.iceberg.spark.SparkCatalog")
        spark.conf.set(root + ".catalog-impl", classOf[RecordingHadoopCatalog].getName)
        spark.conf.set(root + ".warehouse", uri)
        spark.conf.set(root + ".cache-enabled", "false")
        uri
    }

    private def javaCatalog(): RecordingHadoopCatalog = {
        val c = new RecordingHadoopCatalog
        c.setConf(spark.sessionState.newHadoopConf())
        c.initialize(RestJavaCatalog, Map("warehouse" -> restWarehouse).asJava)
        c
    }

    private def restIdent(table: String): TableIdentifier = TableIdentifier.of(RestSchema, table)
    private def restLocation(table: String): String = restWarehouse + "/" + RestSchema + "/" + table
    private def restSql(table: String): String = s"`$RestSparkCatalog`.`$RestSchema`.`$table`"

    private def restWrite(
        data: DataFrame,
        table: String,
        catalog: Catalog,
        writeMode: String,
        keyFields: Seq[String] = Nil,
        pipelineName: String = null,
        status: StatusUtil = new RecordingStatusUtil
    ): IcebergWriter.WriteResult =
        IcebergWriter.write(
            data,
            restLocation(table),
            writeMode,
            Nil,
            keyFields,
            baseSchema,
            status,
            pipelineName,
            Some(IcebergWriter.RestTarget(sparkCatalogName = RestSparkCatalog, ident = restIdent(table), catalog = catalog))
        )

    private def currentMetadata(catalog: Catalog, table: String): String =
        catalog.loadTable(restIdent(table)).asInstanceOf[HasTableOperations].operations().current().metadataFileLocation()

    test("rest target: create through the catalog lands metadata under the requested location") {
        val table = "rest_create"
        val catalog = javaCatalog()
        RecordingHadoopCatalog.clear()
        assert(!catalog.tableExists(restIdent(table)))

        val result = restWrite(df((1L, "east", 1.0), (2L, "west", 2.0)), table, catalog, "append", pipelineName = "Rest Create")

        assert(
            RecordingHadoopCatalog.builds.asScala.exists(_ == s"$RestJavaCatalog:$RestSchema.$table"),
            s"the table must be created through RestTarget.catalog: builds=${RecordingHadoopCatalog.builds}"
        )
        assert(catalog.tableExists(restIdent(table)), "the catalog must know the table after the first write")
        val loaded = catalog.loadTable(restIdent(table))
        val want = new java.io.File(java.net.URI.create(restLocation(table))).getCanonicalPath
        val have = new java.io.File(java.net.URI.create(loaded.location().replaceFirst("^file:/+", "file:///"))).getCanonicalPath
        assert(have == want, s"table location ${loaded.location()} vs requested ${restLocation(table)}")
        assert(loaded.properties().get(IcebergWriter.PipelineProperty) == "Rest Create", loaded.properties())
        assert(TableUtil.formatVersion(loaded) == 2)
        assert(loaded.properties().get("write.format.default") == "parquet")

        assert(result.metadataLocation != null, s"$result")
        val meta = new java.io.File(java.net.URI.create(result.metadataLocation.replaceFirst("^file:/+", "file:///")))
        assert(meta.isFile, result.metadataLocation)
        assert(meta.getCanonicalPath.startsWith(want + java.io.File.separator + "metadata" + java.io.File.separator), s"${meta.getCanonicalPath} vs $want")
        assert(result.metadataLocation == currentMetadata(catalog, table), s"${result.metadataLocation} vs catalog")
        assert(result.addedRecords == 2 && result.totalRecords == 2, s"$result")
        assert(spark.table(restSql(table)).count() == 2)
    }

    test("rest target: append and MERGE commit through the catalog identifier and resultOf advances") {
        val table = "rest_merge"
        val catalog = javaCatalog()
        val first = restWrite(df((1L, "east", 1.0), (2L, "west", 2.0)), table, catalog, "append")

        RecordingHadoopCatalog.clear()
        val second = restWrite(df((3L, "north", 3.0)), table, catalog, "append")
        val sparkLoads = RecordingHadoopCatalog.loads.asScala.toList
        assert(
            sparkLoads.contains(s"$RestSparkCatalog:$RestSchema.$table"),
            s"append must go through writeTo(<catalog>.<schema>.<table>), not a path save: loads=$sparkLoads"
        )
        assert(second.metadataLocation != null && second.metadataLocation != first.metadataLocation, s"$first vs $second")
        assert(second.metadataLocation == currentMetadata(catalog, table), s"${second.metadataLocation} vs catalog")
        assert(second.snapshotId != first.snapshotId && second.totalRecords == 3, s"$second")

        RecordingHadoopCatalog.clear()
        val status = new RecordingStatusUtil
        val third = restWrite(df((1L, "east", 10.0), (4L, "south", 4.0)), table, catalog, "merge", keyFields = Seq("id"), status = status)
        val mergeLoads = RecordingHadoopCatalog.loads.asScala.toList
        assert(
            mergeLoads.contains(s"$RestSparkCatalog:$RestSchema.$table"),
            s"MERGE must target the catalog identifier, not the datris_cache table: loads=$mergeLoads"
        )
        assert(status.messages.exists(_._2.toLowerCase.contains("merge")), status.messages.mkString("\n"))
        assert(third.metadataLocation != second.metadataLocation, s"$second vs $third")
        assert(third.metadataLocation == currentMetadata(catalog, table), s"${third.metadataLocation} vs catalog")
        assert(third.snapshotId == catalog.loadTable(restIdent(table)).currentSnapshot().snapshotId(), s"$third")

        val rows = spark.table(restSql(table)).collect().map(r => r.getLong(0) -> r.getDouble(2)).toMap
        assert(rows == Map(1L -> 10.0, 2L -> 2.0, 3L -> 3.0, 4L -> 4.0), s"$rows")
        assert(third.totalRecords == 4, s"$third")
    }

    // ---- Unity Catalog 5 E2E follow-ups -------------------------------------
    // A table committed through a catalog is read at the catalog's current
    // metadata file, never through version-hint.text (REST commits do not
    // update it). The stand-in commits through a path-backed HadoopCatalog,
    // then rewinds the hint to simulate a REST catalog.

    test("catalog-committed table is read at its current snapshot, not the version hint") {
        val table = "rest_read"
        val catalog = javaCatalog()
        restWrite(df((1L, "east", 1.0), (2L, "west", 2.0)), table, catalog, "append")
        val last = restWrite(df((3L, "north", 3.0)), table, catalog, "append")
        val current = currentMetadata(catalog, table)
        assert(last.metadataLocation == current, s"$last vs $current")

        // Make the last commit look like a REST commit: a uuid-named metadata
        // file that version-hint.text does not know about (HadoopTableOperations
        // also probes v<N+1>, so the v3 name must go), hint at v2.
        val metaDir = new java.io.File(java.net.URI.create(restLocation(table) + "/metadata"))
        val v3 = new java.io.File(java.net.URI.create(current.replaceFirst("^file:/+", "file:///")))
        assert(v3.getName == "v3.metadata.json", v3.getName)
        val restStyle = new java.io.File(metaDir, "00003-5d6c7b8a-9e0f-4a1b-8c2d-3e4f5a6b7c8d.metadata.json")
        Files.move(v3.toPath, restStyle.toPath)
        Files.deleteIfExists(new java.io.File(metaDir, ".v3.metadata.json.crc").toPath)
        Files.write(new java.io.File(metaDir, "version-hint.text").toPath, "2".getBytes("UTF-8"))
        Files.deleteIfExists(new java.io.File(metaDir, ".version-hint.text.crc").toPath)
        val restCurrent = restStyle.toURI.toString

        val byPath = ObjectStoreQueryUtil.readPath(spark, restLocation(table), "iceberg", 100)
        assert(byPath.rows.size() == 2, s"the stand-in must reproduce the stale path read: ${byPath.rows}")

        val byCatalog = ObjectStoreQueryUtil.readPath(spark, restLocation(table), "iceberg", 100, Some(restCurrent))
        assert(byCatalog.rows.size() == 3, s"${byCatalog.rows}")
        assert(byCatalog.snapshotId == java.lang.Long.valueOf(last.snapshotId), s"${byCatalog.snapshotId} vs ${last.snapshotId}")
        assert(byCatalog.path == restLocation(table))
    }

    test("orphan catalog metadata (uuid-named file only, no data) does not block a path-based create") {
        val location = newLocation("orphan/t")
        val metaDir = new java.io.File(java.net.URI.create(location + "/metadata"))
        metaDir.mkdirs()
        Files.write(new java.io.File(metaDir, "00000-0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b.metadata.json").toPath, "{}".getBytes("UTF-8"))

        val status = new RecordingStatusUtil
        val result = write(df((1L, "east", 1.0)), location, "append", status = status)
        assert(result.totalRecords == 1, s"$result")
        assert(status.messages.exists(_._2.contains("ignoring orphan catalog metadata file(s)")), status.messages.mkString("\n"))
    }

    test("a prefix with an orphan metadata file AND data/ is still refused") {
        val location = newLocation("orphan-data/t")
        val metaDir = new java.io.File(java.net.URI.create(location + "/metadata"))
        metaDir.mkdirs()
        Files.write(new java.io.File(metaDir, "00000-0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b.metadata.json").toPath, "{}".getBytes("UTF-8"))
        val dataDir = new java.io.File(java.net.URI.create(location + "/data"))
        dataDir.mkdirs()
        Files.write(new java.io.File(dataDir, "part-0.parquet").toPath, "x".getBytes("UTF-8"))
        val e = intercept[DatrisException](write(df((1L, "east", 1.0)), location, "append"))
        assert(e.getMessage.contains("no loadable table"), e.getMessage)
    }

    test("orphanCatalogMetadata: only uuid-named catalog metadata files under a lone metadata/") {
        val orphan = "00000-0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b.metadata.json"
        assert(IcebergWriter.orphanCatalogMetadata(Seq("metadata"), Seq(orphan)))
        assert(IcebergWriter.orphanCatalogMetadata(Seq("metadata"), Seq(orphan, "." + orphan + ".crc")))
        assert(!IcebergWriter.orphanCatalogMetadata(Seq("metadata"), Nil))
        assert(!IcebergWriter.orphanCatalogMetadata(Seq("metadata", "data"), Seq(orphan)))
        assert(!IcebergWriter.orphanCatalogMetadata(Seq("metadata"), Seq(orphan, "version-hint.text")))
        assert(!IcebergWriter.orphanCatalogMetadata(Seq("metadata"), Seq(orphan, "v1.metadata.json")))
        assert(!IcebergWriter.orphanCatalogMetadata(Seq("metadata"), Seq(orphan, "snap-1-1-abc.avro")))
    }

    // ---- Live Databricks probe follow-ups -----------------------------------

    test("rest target: a catalog that ignores the requested location is refused before any data is written") {
        val table = "rest_managed"
        val catalog = new IgnoringLocationCatalog
        catalog.setConf(spark.sessionState.newHadoopConf())
        catalog.initialize("managed_side", Map("warehouse" -> restWarehouse).asJava)
        val requested = newLocation("requested-elsewhere/t")
        val created = scala.collection.mutable.ListBuffer[Table]()
        val target = IcebergWriter.RestTarget(RestSparkCatalog, restIdent(table), catalog, onCreated = t => created += t)

        val e = intercept[IcebergWriter.RestLocationRefused] {
            IcebergWriter.write(df((1L, "east", 1.0)), requested, "append", Nil, Nil, baseSchema, new RecordingStatusUtil, "p", Some(target))
        }
        assert(IcebergWriter.sameRestLocation(e.tableLocation, restLocation(table)), s"${e.tableLocation}")
        assert(e.requested == requested)
        assert(e.created, "the refusal follows a catalog create")
        assert(created.isEmpty, "onCreated must not run for a refused table")
        // The catalog holds the (empty) table; nothing was written, anywhere.
        assert(catalog.loadTable(restIdent(table)).currentSnapshot() == null, "no data may be committed through the catalog")
        assert(!new java.io.File(java.net.URI.create(requested)).exists(), "nothing written at the requested location either")
    }

    test("rest target: onCreated runs once, after a create at the requested location and before the data commit") {
        val table = "rest_oncreated"
        val catalog = javaCatalog()
        val seen = scala.collection.mutable.ListBuffer[(String, Boolean)]()
        val target = IcebergWriter.RestTarget(
            RestSparkCatalog,
            restIdent(table),
            catalog,
            onCreated = t => seen += ((t.location(), t.currentSnapshot() == null))
        )
        val r = IcebergWriter.write(df((1L, "east", 1.0)), restLocation(table), "append", Nil, Nil, baseSchema, new RecordingStatusUtil, "p", Some(target))
        assert(seen.size == 1, s"$seen")
        assert(IcebergWriter.sameRestLocation(seen.head._1, restLocation(table)), s"$seen")
        assert(seen.head._2, "called before any data commit")
        assert(r.totalRecords == 1)
        // A second write loads the existing table: onCreated is not called again.
        IcebergWriter.write(df((2L, "west", 2.0)), restLocation(table), "append", Nil, Nil, baseSchema, new RecordingStatusUtil, "p", Some(target))
        assert(seen.size == 1, s"$seen")
    }

    test("sameRestLocation: s3/s3a/s3n equal, trailing slash tolerant, different prefix or bucket not equal") {
        assert(IcebergWriter.sameRestLocation("s3://datris/uc/probe/t", "s3a://datris/uc/probe/t/"))
        assert(IcebergWriter.sameRestLocation("s3n://datris/uc/probe/t", "s3a://datris/uc/probe/t"))
        assert(!IcebergWriter.sameRestLocation("s3://datris/uc/__unitystorage/schemas/a/tables/b", "s3a://datris/uc/probe/t"))
        assert(!IcebergWriter.sameRestLocation("s3://other/uc/probe/t", "s3a://datris/uc/probe/t"))
        assert(!IcebergWriter.sameRestLocation("s3a://datris/uc/probe/t/sub", "s3a://datris/uc/probe/t"))
        assert(!IcebergWriter.sameRestLocation(null, "s3a://datris/uc/probe/t"))
    }
}
