package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.SchemaField
import ai.datris.model.{CsvAttributes, Destination, FileAttributes, ObjectStore, PipelineConfig, SchemaProperties, Source}
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

    test("rows in the destination schema's own order are read by position, with or without a header") {
        val rows = List("1,alice,true,2.5,2024-01-02", "2,bob,false,0.5,2024-01-03")
        val expected = legacyRows(rows, ",")
        val path = stageRows(rows)
        val names = schemaFields.map(_.name)
        Seq(null, Nil, names, names.map(_.toUpperCase)).foreach { header =>
            val df = SparkObjectStoreLoader.stagedDataFrameFor(spark, path, schemaFields, header, ",")
            assert(df.schema == sparkSchema(schemaFields))
            assert(df.collect().toList.sortBy(_.getInt(0)) == expected, s"header $header")
        }
    }

    test("rows a transformation reordered or trimmed land under their own names; a column they lack is null") {
        // header order: score, id, day, name (no `active`)
        val rows = List("2.5,1,2024-01-02,alice", ",2,2024-01-03,\"b,ob\"")
        val df = SparkObjectStoreLoader.stagedDataFrameFor(spark, stageRows(rows), schemaFields, List("score", "ID", "day", "name"), ",")
        assert(df.schema == sparkSchema(schemaFields), s"the frame keeps the destination schema, got ${df.schema}")
        val actual = df.collect().toList.sortBy(_.getInt(0))
        assert(
            actual == List(
                Row(1, "alice", null, 2.5, java.sql.Date.valueOf("2024-01-02")),
                Row(2, "b,ob", null, null, java.sql.Date.valueOf("2024-01-03"))
            )
        )
    }

    test("a header naming a column the destination does not have keeps the positional read") {
        val rows = List("1,alice,true,2.5,2024-01-02")
        val df = SparkObjectStoreLoader.stagedDataFrameFor(spark, stageRows(rows), schemaFields, List("a", "b", "c", "d", "e"), ",")
        assert(df.collect().toList == legacyRows(rows, ","))
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

    // ---- plans/stories/ingest-column-name-escaping.md: an odd column name ----
    //  Parquet and ORC take column names through StructField (no SQL), via the
    //  same staged read the loader uses. A column named with a space, a double
    //  quote and a semicolon either round-trips under exactly that name or the
    //  write fails; nothing outside the write path is touched.

    private def filesUnder(root: java.nio.file.Path, excluding: java.nio.file.Path): Set[String] = {
        import scala.collection.JavaConverters._
        val s = Files.walk(root)
        try s.iterator().asScala.filter(p => !p.startsWith(excluding)).map(p =>
                root.relativize(p).toString + (if (Files.isDirectory(p)) "/" else "@" + Files.size(p))
            ).toSet
        finally s.close()
    }

    test(
        "a column named with a space, a double quote and a semicolon either round-trips under exactly that name or fails the write; no other table or path is touched"
    ) {
        val odd = "unit \"price\"; drop table canary"
        val fields = List(SchemaField("id", "int"), SchemaField(odd, "string"))
        val root = Files.createTempDirectory("spark-object-store-odd-names-")
        val canaryDf =
            SparkObjectStoreLoader.stagedDataFrame(spark, stageRows(List("1,x", "2,y")), List(SchemaField("id", "int"), SchemaField("v", "string")), ",")
        for (fmt <- Seq("parquet", "orc")) {
            val canary = root.resolve(fmt + "-canary")
            canaryDf.write.format(fmt).save(canary.toUri.toString)
            val out = root.resolve("pipeline").resolve(fmt)
            Files.createDirectories(out.getParent)
            val before = filesUnder(root, out)

            val df = SparkObjectStoreLoader.stagedDataFrame(spark, stageRows(List("1,a", "2,\"b;c\"")), fields, ",")
            val written = scala.util.Try(df.write.mode("append").format(fmt).save(out.toUri.toString))
            info(fmt + ": " + written.map(_ => "round-trips").recover { case e => "fails: " + String.valueOf(e.getMessage).split("\n").head }.get)
            if (written.isSuccess) {
                val back = spark.read.format(fmt).load(out.toUri.toString)
                assert(back.columns.toSeq == Seq("id", odd), fmt + ": " + back.columns.toSeq)
                val rows = back.collect().map(r => r.getInt(0) -> r.getString(1)).toMap
                assert(rows == Map(1 -> "a", 2 -> "b;c"), fmt + ": " + rows)
            }
            assert(filesUnder(root, out) == before, fmt + ": a path outside " + out + " changed")
            assert(spark.read.format(fmt).load(canary.toUri.toString).count() == 2, fmt + ": canary")
        }
    }

    // ---- dotted, spaced and backticked column names (e2e follow-up) ----

    private def writeAndRead(fmt: String, name: String, root: java.nio.file.Path): (Seq[String], org.apache.spark.sql.DataFrame) = {
        val fields = List(SchemaField("id", "int"), SchemaField("v", "string"), SchemaField(name, "string"))
        val schema = SparkObjectStoreLoader.buildSchema(fields)
        val staged = SparkObjectStoreLoader.stagedDataFrame(spark, stageRows(List("1,a,x1")), fields, ",")
        val out = root.resolve(fmt + "-" + Integer.toHexString(name.hashCode)).toUri.toString
        SparkObjectStoreLoader.projectToSchema(staged, schema).write.mode("append").format(fmt).save(out)
        val back = spark.read.format(fmt).load(out)
        (back.columns.toSeq, back)
    }

    test("a dotted, spaced or backticked column round-trips under exactly that name for parquet and ORC, through the loader's projection") {
        val names = Seq("a.b", "a. b c", "x`y", "unit \"price\"; drop table canary")
        val root = Files.createTempDirectory("spark-object-store-tricky-names-")
        import scala.collection.JavaConverters._
        // ORC does not keep a backtick in a column name (see the next test);
        // the ingest guard refuses such a new name for ORC destinations.
        for (fmt <- Seq("parquet", "orc"); name <- names if !(fmt == "orc" && name.contains("`"))) {
            val fields = List(SchemaField("id", "int"), SchemaField("v", "string"), SchemaField(name, "string"))
            val schema = SparkObjectStoreLoader.buildSchema(fields)
            val staged = SparkObjectStoreLoader.stagedDataFrame(spark, stageRows(List("1,a,x1", "2,b,\"x;2\"")), fields, ",")
            val projected = SparkObjectStoreLoader.projectToSchema(staged, schema)
            assert(projected.columns.toSeq == Seq("id", "v", name), fmt + " " + name + ": " + projected.columns.toSeq)
            val out = root.resolve(fmt + "-" + names.indexOf(name)).toUri.toString
            projected.write.mode("append").format(fmt).save(out)
            projected.write.mode("append").format(fmt).save(out)
            val back = spark.read.format(fmt).load(out)
            assert(back.columns.toSeq == Seq("id", "v", name), fmt + " " + name + ": " + back.columns.toSeq)
            val rows = back.collect().map(r => (r.getInt(0), r.getString(1), r.getString(2))).toList.sorted
            assert(rows == List((1, "a", "x1"), (1, "a", "x1"), (2, "b", "x;2"), (2, "b", "x;2")), fmt + " " + name + ": " + rows)
            // the object-store query read returns the values under that name
            val q = ObjectStoreQueryUtil.readPath(spark, out, fmt, 100)
            assert(q.columns.asScala.toList == List("id", "v", name), fmt + " " + name + ": " + q.columns)
            assert(q.rows.asScala.map(r => r.get(name)).toSet == Set("x1", "x;2"), fmt + " " + name + ": " + q.rows)
        }
    }

    test("projectToSchema keeps every column by its exact name, never as a nested-field path") {
        val fields = List(SchemaField("a", "string"), SchemaField("a.b", "string"), SchemaField("x`y", "string"))
        val schema = SparkObjectStoreLoader.buildSchema(fields)
        val staged = SparkObjectStoreLoader.stagedDataFrame(spark, stageRows(List("p,q,r")), fields, ",")
        // reversed order: the projection selects by name
        val reversed = staged.select(staged.columns.reverse.map(c => staged.col("`" + c.replace("`", "``") + "`")): _*)
        val projected = SparkObjectStoreLoader.projectToSchema(reversed, schema)
        assert(projected.columns.toSeq == Seq("a", "a.b", "x`y"))
        assert(projected.collect().head.toSeq == Seq("p", "q", "r"))
    }

    test("ORC does not keep a backtick in a column name, so the ingest guard refuses one for ORC destinations") {
        val root = Files.createTempDirectory("spark-object-store-orc-backtick-")
        val (columns, _) = writeAndRead("orc", "x`y", root)
        info("orc x`y reads back as: " + columns.mkString(", "))
        assert(columns != Seq("id", "v", "x`y"), "ORC now keeps a backtick name; the ORC backtick refusal in IngestColumnNames can go")
        assert(writeAndRead("parquet", "x`y", root)._1 == Seq("id", "v", "x`y"))

        def orcConfig(fmt: String): ai.datris.model.PipelineConfig = {
            import ai.datris.model._
            val fs = new java.util.ArrayList[SchemaField](java.util.Arrays.asList(SchemaField("id", "int")))
            PipelineConfig(
                name = "col_os",
                source = Source(schemaProperties = SchemaProperties("db", fs), fileAttributes = FileAttributes(csvAttributes = CsvAttributes())),
                destination = Destination(schemaProperties = SchemaProperties("db", fs), objectStore = ObjectStore(prefixKey = "col", fileFormat = fmt))
            )
        }
        val refused = IngestColumnNames.problem(List("x`y"), List("id"), IngestColumnNames.limitsFor(orcConfig("orc")))
        assert(refused.exists(m => m.contains("x`y") && m.contains("#1") && m.contains("ORC")), refused.toString)
        assert(IngestColumnNames.problem(List("x`y"), List("id"), IngestColumnNames.limitsFor(orcConfig(" ORC "))).isDefined)
        for (fmt <- Seq("parquet", "iceberg", null))
            assert(IngestColumnNames.problem(List("x`y", "a.b"), List("id"), IngestColumnNames.limitsFor(orcConfig(fmt))) == None, String.valueOf(fmt))
        assert(IngestColumnNames.problem(List("a.b", "a. b c"), List("id"), IngestColumnNames.limitsFor(orcConfig("orc"))) == None)
    }

    test("the object-store query reads these names back the way the route does (readSchemaFor of the stored config)") {
        import scala.collection.JavaConverters._
        val names = Seq("a.b", "a. b c", "unit \"price\"; drop table canary")
        val root = Files.createTempDirectory("spark-object-store-route-read-")
        for (fmt <- Seq("parquet", "orc")) {
            val fields = List(SchemaField("id", "int")) ++ names.map(n => SchemaField(n, "string"))
            val out = root.resolve(fmt).toUri.toString
            SparkObjectStoreLoader.stagedDataFrame(
                spark,
                stageRows(List("1,v0")),
                fields.take(1) ++ fields.drop(1).take(1),
                ","
            )
                .write.mode("append").format(fmt).save(out)
            SparkObjectStoreLoader.stagedDataFrame(spark, stageRows(List("2," + names.indices.map("w" + _).mkString(","))), fields, ",")
                .write.mode("append").format(fmt).save(out)
            val fs = new java.util.ArrayList[SchemaField](fields.asJava)
            val cfg = PipelineConfig(
                name = "route",
                source = Source(schemaProperties = SchemaProperties("db", fs), fileAttributes = FileAttributes(csvAttributes = CsvAttributes())),
                destination = Destination(schemaProperties = SchemaProperties("db", fs), objectStore = ObjectStore(prefixKey = "route", fileFormat = fmt))
            )
            val q = ObjectStoreQueryUtil.readPath(spark, out, fmt, 100, None, null, ObjectStoreQueryUtil.readSchemaFor(cfg))
            assert(q.columns.asScala.toList == fields.map(_.name), fmt + ": " + q.columns)
            val byId = q.rows.asScala.map(m => m.get("id") -> m).toMap
            assert(byId(1).get("a.b") == "v0" && byId(1).get("a. b c") == null && byId(2).get(names(2)) == "w2", fmt + ": " + q.rows)
        }
    }

    // ---- plans/stories/object-store-load-missing-columns.md -------------------
    //  A later file that lacks an ordinary schema column loads with that column
    //  null. Driven through the real ingest read (`DataUtil.read`: header off
    //  the file, `evolveSchema`, `CSVReader.readToWriter` into the staged file,
    //  `Data.header`), then the loader's `stagedDataFrameFor` with that header,
    //  then the write the loader does for parquet and ORC, read back.

    private def ingestEnv(root: java.nio.file.Path): ai.datris.model.DatrisEnvironment = ai.datris.model.DatrisEnvironment(
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

    private class CapturingStatus extends StatusUtil {
        val events = scala.collection.mutable.ListBuffer[String]()
        override def info(state: String, description: String): Unit = events += description
        override def warn(state: String, description: String): Unit = events += description
        override def error(state: String, description: String): Unit = events += description
    }

    private def osConfig(fields: List[SchemaField], fmt: String): PipelineConfig = {
        import scala.collection.JavaConverters._
        // A pipeline built from a first file: the source schema is all strings,
        // the destination schema carries the types.
        val src = new java.util.ArrayList[SchemaField](fields.map(f => SchemaField(f.name, "string")).asJava)
        val dest = new java.util.ArrayList[SchemaField](fields.asJava)
        PipelineConfig(
            name = "os_cols",
            source = Source(schemaProperties = SchemaProperties("db", src), fileAttributes = FileAttributes(csvAttributes = CsvAttributes())),
            destination = Destination(schemaProperties = SchemaProperties("db", dest), objectStore = ObjectStore(prefixKey = "os_cols", fileFormat = fmt))
        )
    }

    /** One file through the ingest read and the loader's staged read, as the
      * run does: returns the frame the loader would write, the `Data.header`,
      * and the status lines. */
    private def ingestAndStage(fields: List[SchemaField], fmt: String, csv: String): (org.apache.spark.sql.DataFrame, List[String], List[String]) = {
        val root = Files.createTempDirectory("spark-object-store-missing-cols-")
        ai.datris.model.TenantContext.set(ingestEnv(root))
        try {
            val su = new CapturingStatus
            val token = "missing-cols-" + java.util.UUID.randomUUID().toString
            val (data, _) = StagingArea.withToken(token) {
                DataUtil.read(
                    List("s3://raw/os_cols/f.csv"),
                    _ => new java.io.ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                    csv.length.toLong,
                    osConfig(fields, fmt),
                    su
                )
            }
            val df = SparkObjectStoreLoader.stagedDataFrameFor(spark, data.staged.path, fields, data.header, ",")
            // materialise before the staged file can go away
            val cached = spark.createDataFrame(java.util.Arrays.asList(df.collect(): _*), df.schema)
            (cached, data.header, su.events.toList)
        } finally ai.datris.model.TenantContext.clear()
    }

    private val cityFields = List(SchemaField("id", "int"), SchemaField("name", "string"), SchemaField("city", "string"))

    test("a source file that lacks the last schema column loads with that column null, for parquet and for orc") {
        for (fmt <- Seq("parquet", "orc")) {
            val out = Files.createTempDirectory("missing-last-" + fmt).resolve("t").toUri.toString
            // a.csv: every column (loads exactly as before)
            val (full, fullHeader, _) = ingestAndStage(cityFields, fmt, "id,name,city\n1,alice,paris\n2,bob,rome\n")
            assert(fullHeader == List("id", "name", "city"), fmt)
            full.write.mode("append").format(fmt).save(out)
            // b.csv: no city
            val (short, shortHeader, events) = ingestAndStage(cityFields, fmt, "id,name\n3,carol\n4,dave\n")
            assert(shortHeader == List("id", "name"), fmt + ": Data.header is the present columns")
            assert(events.exists(_.contains("CSV is missing columns (will be NULL in destination): city")), fmt + ": " + events)
            assert(short.schema == sparkSchema(cityFields), fmt + ": " + short.schema)
            short.write.mode("append").format(fmt).save(out)

            val back = spark.read.format(fmt).load(out)
            assert(back.columns.toSeq == Seq("id", "name", "city"), fmt + ": " + back.columns.toSeq)
            val rows = back.collect().map(r => (r.getInt(0), r.getString(1), Option(r.getString(2)))).toList.sortBy(_._1)
            assert(
                rows == List((1, "alice", Some("paris")), (2, "bob", Some("rome")), (3, "carol", None), (4, "dave", None)),
                fmt + ": " + rows
            )
        }
    }

    test("a missing first column and a missing middle column land every present value under its own column") {
        // A shift by one would put a name into `id` (int) or a date into `score`
        // (double): FAILFAST would throw, or the assertion shows it.
        val fields = List(SchemaField("id", "int"), SchemaField("name", "string"), SchemaField("score", "double"), SchemaField("day", "date"))
        for (fmt <- Seq("parquet", "orc")) {
            val out = Files.createTempDirectory("missing-first-middle-" + fmt).resolve("t").toUri.toString
            val (noFirst, h1, _) = ingestAndStage(fields, fmt, "name,score,day\nalice,2.5,2024-01-02\n")
            assert(h1 == List("name", "score", "day"), fmt)
            val (noMiddle, h2, _) = ingestAndStage(fields, fmt, "id,score,day\n7,0.5,2024-03-04\n")
            assert(h2 == List("id", "score", "day"), fmt)
            // a file whose header is in another order than the schema
            val (reordered, h3, _) = ingestAndStage(fields, fmt, "day,id,name\n2024-05-06,8,erin\n")
            assert(h3 == List("id", "name", "day"), fmt + ": Data.header is in schema order")
            Seq(noFirst, noMiddle, reordered).foreach(_.write.mode("append").format(fmt).save(out))

            val back = spark.read.format(fmt).load(out)
            assert(back.columns.toSeq == Seq("id", "name", "score", "day"), fmt)
            val rows = back.collect().map(r => (Option(r.get(0)), Option(r.get(1)), Option(r.get(2)), Option(r.get(3)))).toSet
            assert(
                rows == Set(
                    (None, Some("alice"), Some(2.5), Some(java.sql.Date.valueOf("2024-01-02"))),
                    (Some(7), None, Some(0.5), Some(java.sql.Date.valueOf("2024-03-04"))),
                    (Some(8), Some("erin"), None, Some(java.sql.Date.valueOf("2024-05-06")))
                ),
                fmt + ": " + rows
            )
        }
    }

    test("the null column has the schema's type in the written file") {
        val fields = List(SchemaField("id", "int"), SchemaField("score", "double"), SchemaField("day", "date"), SchemaField("big", "bigint"))
        for (fmt <- Seq("parquet", "orc")) {
            val out = Files.createTempDirectory("missing-typed-" + fmt).resolve("t").toUri.toString
            val (short, _, _) = ingestAndStage(fields, fmt, "id\n1\n")
            short.write.mode("append").format(fmt).save(out)
            // read with no schema: the types come from the file itself
            val fileSchema = spark.read.format(fmt).load(out).schema
            assert(fileSchema.fieldNames.toSeq == Seq("id", "score", "day", "big"), fmt)
            assert(fileSchema("id").dataType == IntegerType, fmt + ": " + fileSchema)
            assert(fileSchema("score").dataType == DoubleType, fmt + ": " + fileSchema)
            assert(fileSchema("day").dataType == DateType, fmt + ": " + fileSchema)
            assert(fileSchema("big").dataType == LongType, fmt + ": " + fileSchema)
        }
    }

    test("a value that does not cast still fails the load") {
        // `id` is present and holds a value that is not an int; `city` is missing.
        for (fmt <- Seq("parquet", "orc")) {
            val e = intercept[Exception](ingestAndStage(cityFields, fmt, "id,name\nnot-a-number,alice\n"))
            val chain = Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null).map(t => String.valueOf(t.getMessage)).mkString(" | ")
            assert(chain.toLowerCase.contains("malformed") || chain.contains("FAILFAST"), fmt + ": " + chain)
        }
    }

    test("pin: a file with every column, partitioned by a column it carries, loads exactly as before") {
        for (fmt <- Seq("parquet", "orc")) {
            val rows = "1,alice,paris\n2,bob,rome\n3,carol,paris"
            val (df, header, events) = ingestAndStage(cityFields, fmt, "id,name,city\n" + rows + "\n")
            assert(header == List("id", "name", "city"))
            assert(!events.exists(_.contains("missing")), fmt + ": " + events)
            // the same rows as the positional read the loader used on v1.60.0
            val positional = SparkObjectStoreLoader.stagedDataFrame(spark, stageRows(rows.split("\n").toList), cityFields, ",")
            assert(df.collect().toList.sortBy(_.getInt(0)) == positional.collect().toList.sortBy(_.getInt(0)), fmt)
            val out = Files.createTempDirectory("pin-partitioned-" + fmt).resolve("t")
            df.write.mode("append").partitionBy("city").format(fmt).save(out.toUri.toString)
            assert(Files.isDirectory(out.resolve("city=paris")) && Files.isDirectory(out.resolve("city=rome")), fmt)
            val back = spark.read.format(fmt).load(out.toUri.toString)
            assert(back.filter("city = 'paris'").count() == 2 && back.filter("city is null").count() == 0, fmt)
        }
    }

    // ---- loader guard: rows that would load a null merge key or partition ----
    //  Whatever produced the header (source file, AI transformation, REST
    //  preprocessor), the loader refuses rows that lack a merge key or a
    //  partition column instead of filling it with null.

    private def guardConfig(fields: List[SchemaField], os: ObjectStore): PipelineConfig = {
        import scala.collection.JavaConverters._
        val dest = new java.util.ArrayList[SchemaField](fields.asJava)
        PipelineConfig(
            name = "os_guard",
            source = Source(schemaProperties = SchemaProperties("db", dest), fileAttributes = FileAttributes(csvAttributes = CsvAttributes())),
            destination = Destination(schemaProperties = SchemaProperties("db", dest), objectStore = os)
        )
    }

    private def jl(xs: String*): java.util.List[String] = new java.util.ArrayList[String](java.util.Arrays.asList(xs: _*))

    private def guard(os: ObjectStore, header: List[String], fields: List[SchemaField] = cityFields, rowCount: Long = 1L): Either[String, Unit] =
        try Right(SparkObjectStoreLoader.refuseNullFilledRequired(guardConfig(fields, os), fields, header, rowCount))
        catch { case e: ai.datris.model.DatrisException => Left(e.getMessage) }

    test("a transformation header without the Iceberg merge key fails naming it") {
        for (keys <- Seq(jl("id"), jl("ID"))) {
            val os = ObjectStore(prefixKey = "os_guard", fileFormat = "iceberg", writeMode = "merge", keyFields = keys)
            val r = guard(os, List("name", "city"))
            assert(r.isLeft, keys.toString)
            val m = r.left.get
            assert(m.contains("missing required key field(s): id"), m)
            assert(!m.contains("CSV"), "worded for every route: " + m)
        }
    }

    test("a transformation header without the partition column fails naming it, for parquet, orc and iceberg") {
        // Iceberg: before the write lock only when deleteBeforeWrite rebuilds the
        // table from the config; otherwise IcebergWriter checks the table's spec.
        for (fmt <- Seq("parquet", "orc", "iceberg")) {
            val r =
                guard(ObjectStore(prefixKey = "os_guard", fileFormat = fmt, partitionBy = jl("City"), deleteBeforeWrite = fmt == "iceberg"), List("name", "id"))
            assert(r.isLeft, fmt)
            assert(r.left.get.contains("missing required partition column(s): city"), fmt + ": " + r.left.get)
        }
    }

    test("a partition column that exists only in the destination schema fails: the ingest header never carries it") {
        // the source schema is id,name; the destination adds city and partitions by it
        val r = guard(ObjectStore(prefixKey = "os_guard", fileFormat = "parquet", partitionBy = jl("city")), List("id", "name"))
        assert(r.left.toOption.exists(_.contains("partition column(s): city")), r.toString)
    }

    test("the refusal comes before the write lock and deleteBeforeWrite, so existing data is untouched") {
        val fields = cityFields
        val os = ObjectStore(prefixKey = "os_guard", fileFormat = "parquet", partitionBy = jl("city"), deleteBeforeWrite = true)
        val path = stageRows(List("1,alice", "2,bob"))
        val data = ai.datris.model.Data(
            size = 0L,
            header = List("id", "name"),
            headerWithSchema = null,
            staged = ai.datris.model.StagedPayload(path, ai.datris.model.StagedFormat.Delimited(","), 2L, 0L),
            rawBytes = null
        )
        val su = new CapturingStatus
        val ctx = ai.datris.model.JobContext(null, null, data, guardConfig(fields, os), null, null, null, su)
        val e = intercept[ai.datris.model.DatrisException](new SparkObjectStoreLoader(ctx).process())
        assert(e.getMessage.contains("partition column(s): city"), e.getMessage)
        assert(e.getMessage.endsWith("Nothing was written to the object store"), e.getMessage)
        assert(!su.events.exists(_.contains("Deleting existing data")), su.events.toString)
        assert(su.events.toList == List("Writing data to object store"), "nothing after the begin line: " + su.events)
    }

    test("zero rows with a short header are not refused; one row with the same header is") {
        val os = ObjectStore(prefixKey = "os_guard", fileFormat = "iceberg", writeMode = "merge", keyFields = jl("id"), partitionBy = jl("city"))
        assert(guard(os, List("name"), rowCount = 0L) == Right(()), "an empty transformation output loads as on main")
        val one = guard(os, List("name"), rowCount = 1L)
        assert(one.left.toOption.exists(_.contains("key field(s): id")), one.toString)
    }

    test("pin: stray keyFields on an append, ORC or Parquet pipeline do not refuse rows that lack them") {
        val stores = Seq(
            ObjectStore(prefixKey = "os_guard", fileFormat = "iceberg", writeMode = "append", keyFields = jl("id")),
            ObjectStore(prefixKey = "os_guard", fileFormat = "iceberg", keyFields = jl("id")),
            ObjectStore(prefixKey = "os_guard", fileFormat = "parquet", writeMode = "merge", keyFields = jl("id")),
            ObjectStore(prefixKey = "os_guard", fileFormat = "orc", keyFields = jl("id"))
        )
        stores.foreach(os => assert(guard(os, List("name", "city")) == Right(()), os.toString))
    }

    test("pin: a full header, a reordered full header and the positional path are never refused") {
        val os = ObjectStore(prefixKey = "os_guard", fileFormat = "iceberg", writeMode = "merge", keyFields = jl("id"), partitionBy = jl("city"))
        for (h <- Seq(List("id", "name", "city"), List("city", "id", "name"), Nil, null, List("id", "name", "extra"), List("name", "name")))
            assert(guard(os, h) == Right(()), String.valueOf(h))
        assert(SparkObjectStoreLoader.nullFilledColumns(cityFields, List("id", "name", "city")).isEmpty)
        assert(SparkObjectStoreLoader.nullFilledColumns(cityFields, List("id", "name", "extra")).isEmpty, "positional read fills nothing")
        assert(SparkObjectStoreLoader.nullFilledColumns(cityFields, List("NAME", "id")) == List("city"))
        // an ordinary column missing from the rows is still loaded as null
        assert(guard(os, List("city", "id")) == Right(()))
    }

    // ---- E2E follow-ups: field-protection drop, ignore mode, the Iceberg spec ----

    private val idName = List(SchemaField("id", "int"), SchemaField("name", "string"))

    test("a partition column field protection drops is refused before the write: parquet and orc, and iceberg with deleteBeforeWrite") {
        // FieldProtection strips a `drop` field from the destination schema and
        // the header; partitionBy still names it.
        val stores = Seq(
            ObjectStore(prefixKey = "os_guard", fileFormat = "parquet", partitionBy = jl("city")),
            ObjectStore(prefixKey = "os_guard", fileFormat = "orc", partitionBy = jl("City"), writeMode = "ignore"),
            ObjectStore(prefixKey = "os_guard", fileFormat = "iceberg", partitionBy = jl("city"), deleteBeforeWrite = true)
        )
        for (os <- stores; rows <- Seq(0L, 2L)) {
            val r = guard(os, List("id", "name"), fields = idName, rowCount = rows)
            // named as configured (`City` on the ORC case)
            assert(
                r.left.toOption.exists(m => m.toLowerCase.contains("partition column(s): city") && m.endsWith("Nothing was written to the object store")),
                os + ": " + r
            )
        }
    }

    test("an Iceberg merge key field protection drops is refused before the write") {
        val os = ObjectStore(prefixKey = "os_guard", fileFormat = "iceberg", writeMode = "merge", keyFields = jl("id"))
        val r = guard(os, List("name", "city"), fields = List(SchemaField("name", "string"), SchemaField("city", "string")))
        assert(r.left.toOption.exists(_.contains("key field(s): id")), r.toString)
    }

    test("iceberg without deleteBeforeWrite leaves partition columns to the table (IcebergWriter), so the loader refuses none") {
        for (mode <- Seq("append", "overwrite", "ignore", "errorifexists")) {
            val os = ObjectStore(prefixKey = "os_guard", fileFormat = "iceberg", writeMode = mode, partitionBy = jl("city"))
            assert(guard(os, List("id", "name")) == Right(()), mode + ": null-filled")
            assert(guard(os, List("id", "name"), fields = idName) == Right(()), mode + ": dropped")
        }
    }

    test("Parquet and ORC writeMode ignore: a null-filled partition is checked only when nothing exists at the path") {
        for (fmt <- Seq("parquet", "orc")) {
            val os = ObjectStore(prefixKey = "os_guard", fileFormat = fmt, writeMode = "ignore", partitionBy = jl("city"))
            assert(guard(os, List("id", "name")) == Right(()), fmt + ": not refused before the lock (Spark skips an existing path)")
            val cfg = guardConfig(cityFields, os)
            def at(exists: () => Boolean, rows: Long = 2L): Either[String, Unit] =
                try Right(SparkObjectStoreLoader.refuseIgnoreWrite(cfg, cityFields, List("id", "name"), rows, exists))
                catch { case e: ai.datris.model.DatrisException => Left(e.getMessage) }
            assert(at(() => true) == Right(()), fmt + ": the path exists, nothing is written")
            assert(at(() => throw new java.io.IOException("store down")) == Right(()), fmt + ": an unanswered check leaves the write as before")
            assert(at(() => false, rows = 0L) == Right(()), fmt + ": no rows")
            assert(at(() => false).left.toOption.exists(_.contains("partition column(s): city")), fmt)
            // with deleteBeforeWrite the path is emptied first: refused up front
            val del = os.copy(deleteBeforeWrite = true)
            assert(guard(del, List("id", "name")).isLeft, fmt + ": deleteBeforeWrite")
        }
    }

    test("process(): a dropped partition column is refused before deleteBeforeWrite and before any store access") {
        for (fmt <- Seq("parquet", "orc", "iceberg")) {
            // provider is not one the loader knows: reaching the store setup would fail differently
            val os = ObjectStore(prefixKey = "os_guard", fileFormat = fmt, partitionBy = jl("city"), deleteBeforeWrite = true, provider = "spec-no-store")
            val data = ai.datris.model.Data(
                size = 0L,
                header = List("id", "name"),
                headerWithSchema = null,
                staged = ai.datris.model.StagedPayload(stageRows(List("1,alice")), ai.datris.model.StagedFormat.Delimited(","), 1L, 0L),
                rawBytes = null
            )
            val su = new CapturingStatus
            val ctx = ai.datris.model.JobContext(null, null, data, guardConfig(idName, os), null, null, null, su)
            val e = intercept[ai.datris.model.DatrisException](new SparkObjectStoreLoader(ctx).process())
            assert(e.getMessage.contains("partition column(s): city"), fmt + ": " + e.getMessage)
            assert(su.events.toList == List("Writing data to object store"), fmt + ": " + su.events)
        }
    }

    test("pin (v1.60.0): Spark refuses a partition column the frame lacks even in ignore mode on an existing path") {
        val out = Files.createTempDirectory("pin-ignore-dropped").resolve("t")
        val full = SparkObjectStoreLoader.stagedDataFrame(spark, stageRows(List("1,alice,paris")), cityFields, ",")
        full.write.mode("append").partitionBy("city").parquet(out.toUri.toString)
        val dropped = SparkObjectStoreLoader.stagedDataFrame(spark, stageRows(List("2,bob")), idName, ",")
        intercept[Exception](dropped.write.mode("ignore").partitionBy("city").parquet(out.toUri.toString))
    }

    test("pin: with the ingest check exempting ignore mode, the loader still refuses an ignore write whose target is absent") {
        // Parquet and ORC: refuseIgnoreWrite under the lock. Iceberg: IcebergWriter
        // checks a new table against the config's list (IcebergWriterSpec).
        for (fmt <- Seq("parquet", "orc")) {
            val os = ObjectStore(prefixKey = "os_guard", fileFormat = fmt, writeMode = "ignore", partitionBy = jl("city"))
            val cfg = guardConfig(cityFields, os)
            val absent =
                intercept[ai.datris.model.DatrisException](SparkObjectStoreLoader.refuseIgnoreWrite(cfg, cityFields, List("id", "name"), 1L, () => false))
            assert(absent.getMessage.contains("partition column(s): city"), absent.getMessage)
            SparkObjectStoreLoader.refuseIgnoreWrite(cfg, cityFields, List("id", "name"), 1L, () => true) // target exists: nothing is written, nothing refused
            // the ingest exemption does not reach the loader's up-front guard for a dropped column
            assert(guard(os, List("id", "name"), fields = idName).isLeft, fmt)
        }
    }

    // A source column the destination schema leaves out (docs/schemas.mdx) is
    // still in the rows the transformation writes back. On an object store the
    // transformation stage then leaves the destination schema as it is, so the
    // loader reads those rows exactly as v1.60.0 did: by position against the
    // destination schema.
    test("pin (v1.60.0): an excluded source column in the transformed rows keeps the object store's positional read") {
        import scala.collection.JavaConverters._
        def outcome(df: => org.apache.spark.sql.DataFrame): Either[String, List[Row]] =
            try Right(df.collect().toList)
            catch { case e: Throwable => Left(e.getClass.getName) }
        val dest = List(SchemaField("id", "int"), SchemaField("name", "string"))
        val cases = Seq(
            List("id", "name", "notes") -> List("1,alice,secret"),
            List("id", "name", "notes", "score") -> List("1,alice,secret,7"),
            List("id", "notes") -> List("1,secret"),
            List("id", "notes", "score") -> List("1,secret,7")
        )
        for ((header, rows) <- cases) {
            val cfg = PipelineConfig(
                name = "os_excluded",
                source = Source(
                    schemaProperties = SchemaProperties("db", new java.util.ArrayList[SchemaField]((dest :+ SchemaField("notes", "string")).asJava)),
                    fileAttributes = FileAttributes(csvAttributes = CsvAttributes())
                ),
                destination = Destination(
                    schemaProperties = SchemaProperties("db", new java.util.ArrayList[SchemaField](dest.asJava)),
                    objectStore = ObjectStore(prefixKey = "os_excluded", fileFormat = "parquet")
                )
            )
            val jc = ai.datris.model.JobContext(
                pipelineToken = "t",
                metadata = ai.datris.model.PipelineMetadata("os_excluded", "f.csv", "/tmp/f.csv", "p", bulkUpload = false),
                data = ai.datris.model.Data(1L, header, header.map(h => SchemaField(h, "string")), rows, null),
                config = cfg,
                pipelineProperties = null,
                state = null,
                thread = null,
                statusUtil = new CapturingStatus
            )
            val out = TransformedColumns.reconcile(jc, (_, _) => fail("nothing is stored"), inputHeader = List("id", "name", "notes"))
            val fields = out.config.destination.schemaProperties.fields.asScala.toList
            assert(fields == dest, header.toString)
            val path = stageRows(rows)
            assert(
                outcome(SparkObjectStoreLoader.stagedDataFrameFor(spark, path, fields, out.data.header, ",")) ==
                    outcome(SparkObjectStoreLoader.stagedDataFrame(spark, path, dest, ",")),
                header.toString
            )
        }
    }
}
