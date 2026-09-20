package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.SchemaField
import org.apache.spark.sql.types._
import org.apache.spark.sql.{Row, SparkSession}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}

/** Per-pipeline write lock for the object store loader
  *  (plans/stories/iceberg-loader-reader-lineage.md, step 5).
  *
  *  Plan §12 Q1 was answered YES in story 1: runs of one pipeline can overlap
  *  (ScheduledBatchTasks.startJobs gates only on destination.database.table),
  *  so writes to one objectstore destination must be serialised — Iceberg
  *  commits would otherwise race on metadata, and parquet and ORC appends would
  *  interleave. The lock is keyed on the pipeline name and must not serialise
  *  unrelated pipelines.
  *
  *  Expected seam (companion object of the loader, no JobContext needed):
  *
  *  {{{
  *  object SparkObjectStoreLoader {
  *      private[util] def withPipelineWriteLock[T](pipelineName: String)(body: => T): T
  *      // Phase 2 (plans/stories/streaming-pipeline-phase2.md, step 7): the staged
  *      // CSV is read by Spark's CSV reader (file://, header=false, the configured
  *      // delimiter, empty -> null) instead of parallelize(rows) + split.
  *      private[util] def stagedDataFrame(spark: SparkSession, stagedPath: String, schemaFields: List[SchemaField], delimiter: String): DataFrame
  *  }
  *  }}}
  */
class SparkObjectStoreLoaderSpec extends AnyFunSuite with BeforeAndAfterAll {

    private def onThread[T](body: => T): java.util.concurrent.Future[T] = {
        val ex = Executors.newSingleThreadExecutor()
        val task: Callable[T] = () => body
        try ex.submit(task)
        finally ex.shutdown()
    }

    test("withPipelineWriteLock serialises concurrent writes of the same pipeline") {
        val inside = new AtomicInteger(0)
        val maxInside = new AtomicInteger(0)
        val runs = new AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(4)
        val tasks = (1 to 4).map { _ =>
            val task: Callable[Unit] = () => {
                (1 to 3).foreach { _ =>
                    SparkObjectStoreLoader.withPipelineWriteLock("orders") {
                        val now = inside.incrementAndGet()
                        maxInside.accumulateAndGet(now, Math.max)
                        Thread.sleep(20)
                        inside.decrementAndGet()
                        runs.incrementAndGet()
                    }
                }
            }
            pool.submit(task)
        }
        tasks.foreach(_.get(30, TimeUnit.SECONDS))
        pool.shutdown()
        assert(runs.get() == 12)
        assert(maxInside.get() == 1, s"two writers of the same pipeline overlapped (max concurrent = ${maxInside.get()})")
    }

    test("withPipelineWriteLock does not block a different pipeline") {
        val aHeld = new CountDownLatch(1)
        val releaseA = new CountDownLatch(1)

        val a = onThread {
            SparkObjectStoreLoader.withPipelineWriteLock("pipeline-a") {
                aHeld.countDown()
                assert(releaseA.await(10, TimeUnit.SECONDS), "test harness never released pipeline-a")
            }
        }
        assert(aHeld.await(10, TimeUnit.SECONDS), "pipeline-a never acquired its lock")

        // While A's lock is held, B must acquire promptly.
        val b = onThread {
            SparkObjectStoreLoader.withPipelineWriteLock("pipeline-b") { "b-done" }
        }
        try assert(b.get(2, TimeUnit.SECONDS) == "b-done")
        catch {
            case _: java.util.concurrent.TimeoutException =>
                releaseA.countDown()
                fail("pipeline-b was blocked behind pipeline-a's write lock — the lock is not per-pipeline")
        }
        releaseA.countDown()
        a.get(10, TimeUnit.SECONDS)
    }

    test("withPipelineWriteLock releases the lock when the body throws") {
        intercept[IllegalStateException] {
            SparkObjectStoreLoader.withPipelineWriteLock("flaky") { throw new IllegalStateException("boom") }
        }
        val next = onThread {
            SparkObjectStoreLoader.withPipelineWriteLock("flaky") { "recovered" }
        }
        assert(next.get(2, TimeUnit.SECONDS) == "recovered", "lock leaked after an exception in the body")
    }

    test("withPipelineWriteLock returns the body's value") {
        assert(SparkObjectStoreLoader.withPipelineWriteLock("p") { 42 } == 42)
    }

    // ---- Phase 2: staged-path read replaces parallelize(rows) ------------------

    private lazy val localSpark: SparkSession =
        SparkSession.builder()
            .master("local[1]")
            .appName("SparkObjectStoreLoaderSpec")
            .config("spark.ui.enabled", "false")
            .getOrCreate()
    private var sparkStarted = false
    private def spark: SparkSession = { sparkStarted = true; localSpark }

    override def afterAll(): Unit = if (sparkStarted) localSpark.stop()

    private val schemaFields: List[SchemaField] = List(
        SchemaField("id", "int"),
        SchemaField("name", "string"),
        SchemaField("active", "boolean"),
        SchemaField("score", "double"),
        SchemaField("day", "date")
    )

    /** The loader's pre-change buildSchema, verbatim for the types the fixture uses. */
    private def sparkSchema(fields: List[SchemaField]): StructType = StructType(fields.map { field =>
        val dataType = field.`type`.toLowerCase match {
            case "boolean" => BooleanType
            case "int" | "integer" => IntegerType
            case "double" => DoubleType
            case "date" => DateType
            case _ => StringType
        }
        StructField(field.name, dataType, nullable = true)
    })

    /** The loader's pre-change castValue, verbatim for the types the fixture uses. */
    private def castValue(value: String, fieldType: String): Any = fieldType.toLowerCase match {
        case "boolean" => value.toBoolean
        case "int" | "integer" => value.toInt
        case "double" => value.toDouble
        case "date" => java.sql.Date.valueOf(value)
        case _ => value
    }

    /** The old path: parallelize(rows.map(split + castValue)) + createDataFrame. */
    private def legacyRows(rows: List[String], delimiter: String): List[Row] = {
        val sparkRows = rows.map { row =>
            val values = row.split(delimiter, -1)
            Row.fromSeq(values.indices.map { i =>
                val value = if (i < values.length) values(i).trim else ""
                if (value.isEmpty) null
                else castValue(value, schemaFields(i).`type`)
            })
        }
        val df = spark.createDataFrame(spark.sparkContext.parallelize(sparkRows), sparkSchema(schemaFields))
        df.collect().toList.sortBy(_.getInt(0))
    }

    private def stageRows(rows: List[String]): String = {
        val file = Files.createTempFile("spark-object-store-loader-spec-", ".csv")
        file.toFile.deleteOnExit()
        Files.write(file, rows.mkString("\n").getBytes(StandardCharsets.UTF_8))
        file.toAbsolutePath.toString
    }

    test("the staged-path read yields the same DataFrame rows as the old parallelize path for an unquoted fixture") {
        val rows = List(
            "1,alice,true,2.5,2024-01-02",
            "2,,false,,",
            "3,bob,true,1.0,2024-03-04"
        )
        val expected = legacyRows(rows, ",")
        assert(expected.size == 3 && expected(1).isNullAt(1) && expected(1).isNullAt(3) && expected(1).isNullAt(4), "oracle: empty values are null")

        val df = SparkObjectStoreLoader.stagedDataFrame(spark, stageRows(rows), schemaFields, ",")
        assert(df.schema == sparkSchema(schemaFields), s"schema must be the loader's buildSchema, got ${df.schema}")
        val actual = df.collect().toList.sortBy(_.getInt(0))
        assert(actual == expected, s"staged read differs from the parallelize path:\n  staged:      $actual\n  parallelize: $expected")
    }

    test("the staged-path read honours a non-comma delimiter") {
        val rows = List("1;alice;true;2.5;2024-01-02", "2;bob;false;0.5;2024-01-03")
        val expected = legacyRows(rows, ";")
        val actual = SparkObjectStoreLoader.stagedDataFrame(spark, stageRows(rows), schemaFields, ";").collect().toList.sortBy(_.getInt(0))
        assert(actual == expected)
    }

    test("a quoted value containing the delimiter now parses as one column (release-notes observable)") {
        // Under the old split(delimiter, -1) path this row has six values and the
        // name column is "\"smith". Spark's CSV reader parses RFC 4180 quoting.
        val rows = List("4,\"smith, jr\",true,1.0,2024-01-01")
        val actual = SparkObjectStoreLoader.stagedDataFrame(spark, stageRows(rows), schemaFields, ",").collect().toList
        assert(actual.size == 1)
        val row = actual.head
        assert(row.length == 5)
        assert(row.getInt(0) == 4)
        assert(row.getString(1) == "smith, jr", s"quoted delimiter must stay inside the value, got ${row.getString(1)}")
        assert(row.getBoolean(2))
        assert(row.getDouble(3) == 1.0)
        assert(row.getDate(4) == java.sql.Date.valueOf("2024-01-01"))
    }
}
