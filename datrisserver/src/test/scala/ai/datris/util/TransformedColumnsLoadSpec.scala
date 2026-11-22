package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import java.sql.SQLException
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/** A column the transformation added that a SQL table cannot take
  * (`TransformedColumns.RunColumns`, `evolve`, `manualTable`, `storeAfterLoad`
  * and each SQL loader's `prepareTable`).
  *
  * On v1.60.0 such a column was never loaded and the run succeeded. Here:
  * the ADD COLUMN is tried; when the store refuses it, or the table already
  * has a column of that name whose type cannot take every text value, or the
  * table is managed manually and lacks it, the column is left out of that
  * destination for that run with a warn line, the other columns load, and
  * the stored destination schema gains the column only after a run in which
  * every SQL table destination took it. A declared column, and every other
  * statement, behaves exactly as before.
  *
  * The loaders run here against [[FakeJdbc]]; no database is involved. */
class TransformedColumnsLoadSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root: Path = Files.createTempDirectory("transformed-columns-load-spec")

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

    private class LevelStatusUtil extends StatusUtil {
        val infos = new ListBuffer[String]()
        val warns = new ListBuffer[String]()
        val errors = new ListBuffer[String]()
        override def overrideProcessName(processName: String): Unit = ()
        override def info(state: String, description: String): Unit = infos += description
        override def warn(state: String, description: String): Unit = warns += description
        override def error(state: String, description: String): Unit = errors += description
    }

    private def schema(fields: (String, String)*): SchemaProperties =
        SchemaProperties("db", new java.util.ArrayList[SchemaField](fields.map(f => SchemaField(f._1, f._2)).asJava))

    private val source = Seq("city" -> "string", "temp_c" -> "double")

    private val pg = Database(usePostgres = true, dbName = "datris", schema = "public", table = "weather")
    private val mssql = Database(useSqlServer = true, dbName = "sales", schema = "dbo", table = "weather", credentialsSecret = "mssql")
    private val snow = Database(useSnowflake = true, dbName = "ANALYTICS", schema = "PUBLIC", table = "WEATHER", credentialsSecret = "sf")
    private val bricks = Database(useDatabricks = true, dbName = "main", schema = "default", table = "weather", credentialsSecret = "dbx")

    private def config(database: Database = pg, objectStore: ObjectStore = null, dest: Seq[(String, String)] = source): PipelineConfig =
        PipelineConfig(
            name = "weather",
            source = Source(schemaProperties = schema(source: _*), fileAttributes = FileAttributes(csvAttributes = CsvAttributes(delimiter = ","))),
            destination = Destination(schemaProperties = schema(dest: _*), database = database, objectStore = objectStore)
        )

    private def ctx(cfg: PipelineConfig, header: List[String], rows: List[String], status: StatusUtil): JobContext =
        JobContext(
            pipelineToken = "job-token-1",
            metadata = PipelineMetadata("weather", "rows.csv", "/tmp/rows.csv", "pub-1", bulkUpload = false),
            data = Data(1L, header, header.map(h => SchemaField(h, "string")), rows, null),
            config = cfg,
            pipelineProperties = null,
            state = null,
            thread = null,
            statusUtil = status
        )

    private val hdr = List("city", "temp_c", "score")
    private val input = List("city", "temp_c")

    private type Stored = ListBuffer[(String, List[String])]

    /** The context the loaders see after the transformation added `score`. */
    private def transformed(
        cfg: PipelineConfig = config(),
        status: StatusUtil = new LevelStatusUtil,
        stored: Stored = ListBuffer(),
        rows: List[String] = List("Shanghai,4.7,7", "Lagos,31,n/a")
    ): JobContext =
        TransformedColumns.reconcile(ctx(cfg, hdr, rows, status), input, (n, f) => stored += ((n, f.map(_.name))))

    private def destNames(c: JobContext): List[String] = c.config.destination.schemaProperties.fields.asScala.toList.map(_.name)

    private def refused(msg: String): Exception = new SQLException(msg)

    // ---- the stored schema ----

    test("on a pipeline with a SQL table destination nothing is stored at the transformation stage; storeAfterLoad stores the added columns after the load") {
        for (db <- Seq(pg, mssql, snow, bricks)) {
            val stored: Stored = ListBuffer()
            val out = transformed(config(db), stored = stored)
            assert(destNames(out) == List("city", "temp_c", "score"), "the loaders see the added column")
            assert(stored.isEmpty, "nothing is stored before the load: " + stored)
            assert(TransformedColumns.runColumns(out).map(_.fields.map(_.name)).contains(List("score")))
            TransformedColumns.storeAfterLoad(out)
            assert(stored.toList == List("weather" -> List("score")))
        }
    }

    test("storeAfterLoad stores nothing for a rehearsal, and nothing a SQL destination left out") {
        val stored: Stored = ListBuffer()
        val rehearsal = PipelineScripts.withRehearsal(true)(transformed(stored = stored))
        TransformedColumns.storeAfterLoad(rehearsal)
        assert(stored.isEmpty)

        val status = new LevelStatusUtil
        val out = transformed(stored = stored, status = status)
        TransformedColumns.runColumns(out).get.leaveOut("SCORE")
        TransformedColumns.storeAfterLoad(out)
        assert(stored.isEmpty, "a column one destination left out is not stored")
    }

    test("a pipeline with no SQL table destination stores at the transformation stage, as before") {
        val mongo = Database(useMongoDB = true, dbName = "datris", table = "weather")
        for (cfg <- Seq(config(database = null, objectStore = ObjectStore(prefixKey = "weather", fileFormat = "parquet")), config(database = mongo))) {
            val stored: Stored = ListBuffer()
            val out = transformed(cfg, stored = stored)
            assert(stored.toList == List("weather" -> List("score")))
            assert(TransformedColumns.runColumns(out).isEmpty)
            TransformedColumns.storeAfterLoad(out) // a no-op
            assert(stored.size == 1)
        }
    }

    test("storeAfterLoad turns a failed write into a warning: the rows are loaded and the next run adds the column again") {
        val status = new LevelStatusUtil
        val out = TransformedColumns.reconcile(ctx(config(), hdr, List("x,1,2"), status), input, (_, _) => throw new RuntimeException("config DB unreachable"))
        TransformedColumns.storeAfterLoad(out)
        assert(status.warns.exists(w => w.contains("'score'") && w.contains("config DB unreachable")), status.warns)
    }

    // ---- the shared table step ----

    private def noRead: () => Either[Throwable, Map[String, TransformedColumns.StoreColumn]] = () => fail("the table's column types are not read")

    test("an ADD COLUMN the store refuses for an added column leaves it out with a warning; the declared columns' statements run as before") {
        val status = new LevelStatusUtil
        val out = transformed(config(dest = source :+ ("note" -> "string")), status = status)
        val fields = out.config.destination.schemaProperties.fields.asScala.toList
        val adds = fields.filter(f => Set("note", "score").contains(f.name)).map(f => f -> ("ADD " + f.name))
        val ran = ListBuffer[String]()
        val left = TransformedColumns.evolve(
            out,
            "the Postgres table public.weather",
            TransformedColumns.Postgres,
            adds,
            Set("city", "temp_c"),
            sql => ran += "execute " + sql,
            sql => { ran += "attempt " + sql; Some(refused("ERROR: must be owner of table weather")) },
            noRead
        )
        assert(ran.toList == List("execute ADD note", "attempt ADD score"))
        assert(left == Set("score"))
        assert(TransformedColumns.runColumns(out).get.leftOut == Set("score"))
        val w = status.warns.mkString("\n")
        assert(status.warns.size == 1, w)
        assert(w.contains("'score'") && w.contains("the Postgres table public.weather") && w.contains("must be owner of table weather"), w)
        assert(w.contains("left out of this destination"), w)
        assert(status.infos.contains("Schema evolution: ADD note") && status.infos.contains("Schema evolution: ADD score"), status.infos)
    }

    test("a declared column's ADD COLUMN failure still fails the load") {
        val out = transformed(config(dest = source :+ ("note" -> "string")))
        val note = out.config.destination.schemaProperties.fields.asScala.find(_.name == "note").get
        val e = intercept[SQLException](
            TransformedColumns.evolve(
                out,
                "the Postgres table public.weather",
                TransformedColumns.Postgres,
                Seq(note -> "ADD note"),
                Set("city", "temp_c", "score"),
                _ => throw refused("ERROR: must be owner of table weather"),
                _ => fail("a declared column is never attempted"),
                noRead
            )
        )
        assert(e.getMessage == "ERROR: must be owner of table weather")

        // with no transformation columns at all, every ADD is a declared one
        val plain = ctx(config(dest = source :+ ("note" -> "string")), input, List("x,1"), new LevelStatusUtil)
        intercept[SQLException](
            TransformedColumns.evolve(
                plain,
                "t",
                TransformedColumns.Postgres,
                Seq(note -> "ADD note"),
                Set("city"),
                _ => throw refused("no"),
                _ => fail("never attempted"),
                noRead
            )
        )
    }

    test("an added column's ADD COLUMN that leaves the connection unusable fails the load") {
        val out = transformed()
        val score = out.config.destination.schemaProperties.fields.asScala.find(_.name == "score").get
        val e = intercept[SQLException](
            TransformedColumns.evolve(
                out,
                "t",
                TransformedColumns.Postgres,
                Seq(score -> "ADD score"),
                Set("city", "temp_c"),
                _ => fail("not a declared column"),
                _ => throw refused("the connection is gone"),
                noRead
            )
        )
        assert(e.getMessage == "the connection is gone")
    }

    test("an added column the table already has with a type that cannot take every text value is left out with a warning") {
        import TransformedColumns.StoreColumn
        def step(existingType: Either[Throwable, Map[String, StoreColumn]]): (Set[String], LevelStatusUtil) = {
            val status = new LevelStatusUtil
            val out = transformed(status = status)
            var reads = 0
            val left = TransformedColumns.evolve(
                out,
                "the Postgres table public.weather",
                TransformedColumns.Postgres,
                Nil,
                Set("city", "temp_c", "score"),
                _ => fail("nothing to add"),
                _ => fail("nothing to add"),
                () => { reads += 1; existingType }
            )
            assert(reads == 1, "read once")
            (left, status)
        }
        val (intLeft, intStatus) = step(Right(Map("city" -> StoreColumn("text"), "score" -> StoreColumn("integer"))))
        assert(intLeft == Set("score"))
        val w = intStatus.warns.mkString("\n")
        assert(w.contains("'score'") && w.contains("integer") && w.contains("the Postgres table public.weather") && w.contains("no value from this run"), w)

        val (textLeft, textStatus) = step(Right(Map("score" -> StoreColumn("text"))))
        assert(textLeft.isEmpty && textStatus.warns.isEmpty, textStatus.warns)

        val (failLeft, failStatus) = step(Left(refused("permission denied for information_schema")))
        assert(failLeft == Set("score"))
        assert(failStatus.warns.exists(w => w.contains("'score'") && w.contains("permission denied for information_schema")), failStatus.warns)

        // an added column this run's ADD just created is not read back
        val out = transformed()
        val score = out.config.destination.schemaProperties.fields.asScala.find(_.name == "score").get
        val left =
            TransformedColumns.evolve(out, "t", TransformedColumns.Postgres, Seq(score -> "ADD score"), Set("city", "temp_c"), _ => fail(), _ => None, noRead)
        assert(left.isEmpty)
    }

    test("which existing column types take every text value, per engine") {
        import TransformedColumns._
        val yes = Seq(
            Postgres -> StoreColumn("text"),
            Postgres -> StoreColumn("character varying"),
            Postgres -> StoreColumn("TEXT"),
            SqlServer -> StoreColumn("nvarchar", Some(-1L)),
            SqlServer -> StoreColumn("varchar", Some(-1L)),
            SqlServer -> StoreColumn("ntext", Some(1073741823L)),
            SqlServer -> StoreColumn("text", Some(2147483647L)),
            Snowflake -> StoreColumn("TEXT", Some(16777216L)),
            Snowflake -> StoreColumn("TEXT", Some(134217728L)),
            Snowflake -> StoreColumn("TEXT", None),
            Databricks -> StoreColumn("STRING"),
            Databricks -> StoreColumn("string")
        )
        val no = Seq(
            Postgres -> StoreColumn("integer"),
            Postgres -> StoreColumn("character varying", Some(10L)),
            Postgres -> StoreColumn("character", Some(1L)),
            Postgres -> StoreColumn("jsonb"),
            Postgres -> StoreColumn("USER-DEFINED"),
            Postgres -> StoreColumn(null),
            SqlServer -> StoreColumn("nvarchar", Some(50L)),
            SqlServer -> StoreColumn("int"),
            SqlServer -> StoreColumn("nchar", Some(10L)),
            Snowflake -> StoreColumn("TEXT", Some(20L)),
            Snowflake -> StoreColumn("NUMBER"),
            Databricks -> StoreColumn("INT"),
            Databricks -> StoreColumn("VARCHAR"),
            "unknown" -> StoreColumn("text")
        )
        yes.foreach { case (e, c) => assert(holdsAnyText(e, c), e + " " + c) }
        no.foreach { case (e, c) => assert(!holdsAnyText(e, c), e + " " + c) }
    }

    test("leaving a column out keeps every other value in its own column") {
        val out = transformed()
        val narrowed = TransformedColumns.withoutColumns(out, Set("score"))
        assert(destNames(narrowed) == List("city", "temp_c"))
        assert(narrowed.data eq out.data)
        assert(DestSchemaProjector.project(narrowed, Iterator("Shanghai,4.7,7", "Lagos,31,n/a")).toList == List("Shanghai,4.7", "Lagos,31"))
        // reordered by the transformation: still by name
        val re = TransformedColumns.reconcile(ctx(config(), List("score", "temp_c", "city"), List("7,4.7,Shanghai"), new LevelStatusUtil), input, (_, _) => ())
        assert(DestSchemaProjector.project(TransformedColumns.withoutColumns(re, Set("score")), Iterator("7,4.7,Shanghai")).toList == List("Shanghai,4.7"))
        assert(TransformedColumns.withoutColumns(out, Set.empty) eq out)
    }

    test("a manually managed table: an added column it lacks, or holds as another type, is left out; nothing is read without added columns") {
        import TransformedColumns.StoreColumn
        val status = new LevelStatusUtil
        val out = transformed(status = status)
        val left = TransformedColumns.manualTable(
            out,
            "the Postgres table public.weather",
            TransformedColumns.Postgres,
            () => Right(Map("city" -> StoreColumn("text")))
        )
        assert(left == Set("score"))
        assert(status.warns.exists(w => w.contains("'score'") && w.contains("does not have") && w.contains("manageTableManually")), status.warns)

        assert(TransformedColumns.manualTable(transformed(), "t", TransformedColumns.Postgres, () => Right(Map("score" -> StoreColumn("text")))).isEmpty)
        assert(
            TransformedColumns.manualTable(transformed(), "t", TransformedColumns.Postgres, () => Right(Map("score" -> StoreColumn("bigint")))) == Set("score")
        )
        assert(TransformedColumns.manualTable(transformed(), "t", TransformedColumns.Postgres, () => Left(refused("denied"))) == Set("score"))

        val plain = ctx(config(), input, List("x,1"), new LevelStatusUtil)
        assert(TransformedColumns.manualTable(plain, "t", TransformedColumns.Postgres, noRead).isEmpty)
    }

    // ---- savepoints ----

    test("attempt: in autocommit no savepoint; in a transaction a refused statement is rolled back to a savepoint; an unusable connection fails") {
        val auto = new FakeJdbc(autoCommit = true)
        assert(TransformedColumns.attempt(auto.connection, savepoint = true)(throw refused("no")).map(_.getMessage).contains("no"))
        assert(auto.savepoints.isEmpty)

        val tx = new FakeJdbc(autoCommit = false)
        assert(TransformedColumns.attempt(tx.connection, savepoint = true)(throw refused("no")).isDefined)
        assert(tx.savepoints.toList == List("set", "rollback"))
        tx.savepoints.clear()
        assert(TransformedColumns.attempt(tx.connection, savepoint = true)(()).isEmpty)
        assert(tx.savepoints.toList == List("set", "release"))

        val noSp = new FakeJdbc(autoCommit = false)
        assert(TransformedColumns.attempt(noSp.connection, savepoint = false)(throw refused("no")).isDefined)
        assert(noSp.savepoints.isEmpty)

        val gone = new FakeJdbc(autoCommit = false)
        gone.rollbackFails = true
        val e = intercept[SQLException](TransformedColumns.attempt(gone.connection, savepoint = true)(throw refused("must be owner")))
        assert(e.getMessage == "must be owner")
    }

    // ---- the four SQL loaders, on a scripted connection ----

    /** A table holding `cols` (name, type, max length). */
    private def table(jdbc: FakeJdbc, cols: (String, String, AnyRef)*): Unit =
        jdbc.answer = (sql, _) => {
            val s = sql.toLowerCase
            if (s.contains("data_type")) (Seq("column_name", "data_type", "character_maximum_length"), cols.map(c => Seq[AnyRef](c._1, c._2, c._3)))
            else if (s.contains("column_name")) (Seq("column_name"), cols.map(c => Seq[AnyRef](c._1)))
            else if (s.contains("information_schema.tables")) (Seq("x"), Seq(Seq[AnyRef]("1")))
            else (Nil, Nil)
        }

    private def refuseAddOf(jdbc: FakeJdbc, column: String, msg: String): Unit =
        jdbc.refuse = sql => if (sql.startsWith("ALTER TABLE") && sql.contains(column)) Some(refused(msg)) else None

    private trait Loader {
        def prepare(jdbc: FakeJdbc): Set[String]
        def context: JobContext
    }

    private def loaderFor(c: JobContext): Loader = {
        val db = c.config.destination.database
        if (db.usePostgres) new Loader {
            val l = new PostgresLoader(c)
            def prepare(j: FakeJdbc) = l.prepareTable(j.statement)
            def context = l.loadContext
        }
        else if (db.useSqlServer) new Loader {
            val l = new SqlServerLoader(c)
            def prepare(j: FakeJdbc) = l.prepareTable(j.statement)
            def context = l.loadContext
        }
        else if (db.useSnowflake) new Loader {
            val l = new SnowflakeLoader(c)
            def prepare(j: FakeJdbc) = l.prepareTable(j.statement)
            def context = l.loadContext
        }
        else new Loader {
            val l = new DatabricksLoader(c)
            def prepare(j: FakeJdbc) = l.prepareTable(j.statement)
            def context = l.loadContext
        }
    }

    private val textOf: Map[Database, (String, AnyRef)] = Map(
        pg -> ("text", null),
        mssql -> ("nvarchar", java.lang.Long.valueOf(-1L)),
        snow -> ("TEXT", java.lang.Long.valueOf(16777216L)),
        bricks -> ("STRING", null)
    )
    private val intOf: Map[Database, String] = Map(pg -> "integer", mssql -> "int", snow -> "NUMBER", bricks -> "INT")
    private val label: Map[Database, String] = Map(
        pg -> "the Postgres table public.weather",
        mssql -> "the SQL Server table dbo.weather",
        snow -> "the Snowflake table ANALYTICS.PUBLIC.WEATHER",
        bricks -> "the Databricks table main.default.weather"
    )

    test("each SQL loader: an ADD COLUMN the store refuses for an added column leaves it out of that destination, with a warning, and the run goes on") {
        for (db <- Seq(pg, mssql, snow, bricks); tx <- Seq(true, false)) {
            val name = db.toString.take(40) + " tx=" + tx
            val status = new LevelStatusUtil
            val out = transformed(config(db.copy(useTransaction = tx)), status = status)
            val jdbc = new FakeJdbc(autoCommit = !tx)
            table(jdbc, ("city", "text", null), ("temp_c", "double precision", null))
            refuseAddOf(jdbc, "score", "permission denied: must be owner of table weather")
            val loader = loaderFor(out)
            val left =
                try loader.prepare(jdbc)
                catch { case e: Exception => fail(name + ": the run must go on, but the table step threw: " + e) }
            assert(left == Set("score"), name)
            assert(destNames(loader.context) == List("city", "temp_c"), name)
            assert(TransformedColumns.runColumns(out).get.leftOut == Set("score"), name)
            val w = status.warns.mkString("\n")
            assert(w.contains("'score'") && w.contains(label(db)) && w.contains("must be owner of table weather"), name + ": " + w)
            assert(jdbc.executed.exists(s => s.startsWith("ALTER TABLE") && s.contains("score")), name + ": the ADD is tried")
            if (tx && (db.usePostgres || db.useSqlServer)) assert(jdbc.savepoints.toList == List("set", "rollback"), name)
            else assert(jdbc.savepoints.isEmpty, name)
        }
    }

    test("each SQL loader: a declared column's refused ADD COLUMN still fails the load, as before") {
        for (db <- Seq(pg, mssql, snow, bricks)) {
            val c = ctx(config(db, dest = source :+ ("note" -> "string")), input, List("x,1"), new LevelStatusUtil)
            val jdbc = new FakeJdbc()
            table(jdbc, ("city", "text", null), ("temp_c", "double precision", null))
            refuseAddOf(jdbc, "note", "must be owner")
            val e = intercept[SQLException](loaderFor(c).prepare(jdbc))
            assert(e.getMessage == "must be owner", db.toString)
            assert(jdbc.queries.forall(q => !q.toLowerCase.contains("data_type")), db.toString + ": no type read on the declared path")
        }
    }

    test("each SQL loader: an added column the table already has keeps loading when its type takes text, and is left out when it does not") {
        for (db <- Seq(pg, mssql, snow, bricks)) {
            val (t, len) = textOf(db)
            val okStatus = new LevelStatusUtil
            val okOut = transformed(config(db), status = okStatus)
            val ok = new FakeJdbc()
            table(ok, ("city", t, len), ("temp_c", t, len), ("score", t, len))
            val okLoader = loaderFor(okOut)
            assert(okLoader.prepare(ok).isEmpty, db.toString)
            assert(destNames(okLoader.context) == hdr, db.toString)
            assert(okStatus.warns.isEmpty, okStatus.warns)
            assert(!ok.executed.exists(_.startsWith("ALTER TABLE")), db.toString + ": nothing to add")

            val badStatus = new LevelStatusUtil
            val badOut = transformed(config(db), status = badStatus)
            val bad = new FakeJdbc()
            table(bad, ("city", t, len), ("temp_c", t, len), ("score", intOf(db), null))
            val badLoader = loaderFor(badOut)
            assert(badLoader.prepare(bad) == Set("score"), db.toString)
            assert(destNames(badLoader.context) == List("city", "temp_c"), db.toString)
            assert(badStatus.warns.exists(w => w.contains("'score'") && w.contains(intOf(db)) && w.contains(label(db))), badStatus.warns)
        }
    }

    test("each SQL loader: with no transformation columns the table step runs the same statements as before and reads no types") {
        for (db <- Seq(pg, mssql, snow, bricks)) {
            val c = ctx(config(db, dest = source :+ ("note" -> "string")), input, List("x,1"), new LevelStatusUtil)
            val jdbc = new FakeJdbc()
            table(jdbc, ("city", "text", null), ("temp_c", "double precision", null))
            val loader = loaderFor(c)
            assert(loader.prepare(jdbc).isEmpty)
            assert(loader.context eq c, db.toString)
            assert(jdbc.executed.count(s => s.startsWith("ALTER TABLE") && s.contains("note")) == 1, db.toString)
            assert(jdbc.queries.forall(q => !q.toLowerCase.contains("data_type")), db.toString)
            assert(jdbc.savepoints.isEmpty)
        }
    }

    test("each SQL loader: a manually managed table reads nothing without added columns and leaves out an added column it lacks") {
        for (db <- Seq(pg, mssql, snow, bricks)) {
            val manual = db.copy(manageTableManually = true)
            val plain = ctx(config(manual), input, List("x,1"), new LevelStatusUtil)
            val quiet = new FakeJdbc()
            assert(loaderFor(plain).prepare(quiet).isEmpty)
            assert(quiet.executed.isEmpty && quiet.queries.isEmpty, db.toString + ": " + quiet.executed + quiet.queries)

            val status = new LevelStatusUtil
            val out = transformed(config(manual), status = status)
            val jdbc = new FakeJdbc()
            table(jdbc, ("city", "text", null), ("temp_c", "double precision", null))
            val loader = loaderFor(out)
            assert(loader.prepare(jdbc) == Set("score"), db.toString)
            assert(destNames(loader.context) == List("city", "temp_c"))
            assert(jdbc.executed.isEmpty, db.toString + ": a manually managed table gets no statement: " + jdbc.executed)
            assert(status.warns.exists(_.contains("'score'")), status.warns)
        }
    }

    // ---- several destinations ----

    test("two SQL destinations, one refuses: the other loads the column, nothing is stored, and the next run warns again without failing") {
        val both = pg.copy(useSqlServer = true, credentialsSecret = "mssql")
        var storedConfig = config(both)
        val writes = ListBuffer[List[String]]()
        // Postgres takes the ADD on run 1 and has the column from then on.
        val pgTable = ListBuffer[(String, String, AnyRef)](("city", "text", null), ("temp_c", "double precision", null))
        def run(): (Set[String], Set[String], LevelStatusUtil, JobContext) = {
            val status = new LevelStatusUtil
            val out = TransformedColumns.reconcile(
                ctx(storedConfig, hdr, List("Shanghai,4.7,n/a"), status),
                input,
                (_, f) => { writes += f.map(_.name); storedConfig = TransformedColumns.withDestFields(storedConfig, f) }
            )
            val pgJdbc = new FakeJdbc()
            table(pgJdbc, pgTable.toList: _*)
            val pgLeft = new PostgresLoader(out).prepareTable(pgJdbc.statement)
            if (pgJdbc.executed.exists(s => s.startsWith("ALTER TABLE") && s.contains("score"))) pgTable += (("score", "text", null))
            val msJdbc = new FakeJdbc()
            table(msJdbc, ("city", "nvarchar", java.lang.Long.valueOf(-1L)), ("temp_c", "float", null))
            refuseAddOf(msJdbc, "score", "Cannot alter the table 'weather', because it does not exist or you do not have permission.")
            val msLeft = new SqlServerLoader(out).prepareTable(msJdbc.statement)
            TransformedColumns.storeAfterLoad(out)
            (pgLeft, msLeft, status, out)
        }

        val (pg1, ms1, s1, _) = run()
        assert(pg1.isEmpty && ms1 == Set("score"))
        assert(writes.isEmpty, "not stored while one destination cannot take it")
        assert(s1.warns.count(_.contains("'score'")) == 1, s1.warns)

        val (pg2, ms2, s2, out2) = run()
        assert(pg2.isEmpty, "Postgres has the column now and loads it")
        assert(ms2 == Set("score"))
        assert(s2.warns.count(_.contains("'score'")) == 1, s2.warns)
        assert(s2.errors.isEmpty)
        assert(destNames(out2) == hdr, "the run's own schema still carries the column for the destinations that take it")
        assert(writes.isEmpty)
    }

    test("a SQL destination beside an object store: the object store still gets the added column when the SQL table refuses it") {
        val stored: Stored = ListBuffer()
        val out = transformed(config(pg, objectStore = ObjectStore(prefixKey = "weather", fileFormat = "parquet")), stored = stored)
        assert(stored.isEmpty)
        val jdbc = new FakeJdbc()
        table(jdbc, ("city", "text", null), ("temp_c", "double precision", null))
        refuseAddOf(jdbc, "score", "must be owner")
        val loader = new PostgresLoader(out)
        assert(loader.prepareTable(jdbc.statement) == Set("score"))
        assert(destNames(out) == hdr, "the object store loader's context is not narrowed")
        TransformedColumns.storeAfterLoad(out)
        assert(stored.isEmpty)
    }
}
