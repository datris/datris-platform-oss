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

    import TransformedColumns.StoreColumn

    /** A column as the catalog reports it: by default a nullable, ordinary column. */
    private def sc(name: String, t: String, len: Option[Long] = None, nullable: Option[Boolean] = Some(true), generated: Option[Boolean] = Some(false)) =
        StoreColumn(name, t, len, nullable, generated)

    private def noRead: () => Either[Throwable, Seq[StoreColumn]] = () => fail("the table's columns are not read")

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

    /** The table step for `score`, which the table already has, given what its catalog says. */
    private def existingStep(engine: String, columns: Either[Throwable, Seq[StoreColumn]], header: List[String] = hdr): (Set[String], LevelStatusUtil) = {
        val status = new LevelStatusUtil
        val out = TransformedColumns.reconcile(ctx(config(), header, List("Shanghai,4.7,7"), status), input, (_, _) => ())
        var reads = 0
        val left = TransformedColumns.evolve(
            out,
            "the table t",
            engine,
            Nil,
            Set("city", "temp_c", "score"),
            _ => fail("nothing to add"),
            _ => fail("nothing to add"),
            () => { reads += 1; columns }
        )
        assert(reads == 1, "read once")
        (left, status)
    }

    test("an added column the table already has with a type that cannot take every text value is left out with a warning") {
        val (intLeft, intStatus) = existingStep(TransformedColumns.Postgres, Right(Seq(sc("city", "text"), sc("score", "integer"))))
        assert(intLeft == Set("score"))
        val w = intStatus.warns.mkString("\n")
        assert(w.contains("'score'") && w.contains("integer") && w.contains("the table t") && w.contains("no value from this run"), w)

        val (textLeft, textStatus) = existingStep(TransformedColumns.Postgres, Right(Seq(sc("score", "text"))))
        assert(textLeft.isEmpty && textStatus.warns.isEmpty, textStatus.warns)

        val (failLeft, failStatus) = existingStep(TransformedColumns.Postgres, Left(refused("permission denied for information_schema")))
        assert(failLeft == Set("score"))
        assert(failStatus.warns.exists(w => w.contains("'score'") && w.contains("permission denied for information_schema")), failStatus.warns)

        // an added column this run's ADD just created is not read back
        val out = transformed()
        val score = out.config.destination.schemaProperties.fields.asScala.find(_.name == "score").get
        val left =
            TransformedColumns.evolve(out, "t", TransformedColumns.Postgres, Seq(score -> "ADD score"), Set("city", "temp_c"), _ => fail(), _ => None, noRead)
        assert(left.isEmpty)
    }

    test("an existing text column that is NOT NULL, generated by the database, or of unknown kind is left out with a warning") {
        for (engine <- Seq(TransformedColumns.Postgres, TransformedColumns.SqlServer, TransformedColumns.Databricks)) {
            val t = if (engine == TransformedColumns.SqlServer) "nvarchar" else if (engine == TransformedColumns.Databricks) "STRING" else "text"
            val len = if (engine == TransformedColumns.SqlServer) Some(-1L) else None
            val cases = Seq(
                "NOT NULL" -> sc("score", t, len, nullable = Some(false)),
                "generates" -> sc("score", t, len, generated = Some(true)),
                "could not be read" -> sc("score", t, len, nullable = None),
                "could not be read" -> sc("score", t, len, generated = None)
            )
            for ((why, c) <- cases) {
                val (left, status) = existingStep(engine, Right(Seq(c)))
                assert(left == Set("score"), engine + " " + c)
                assert(
                    status.warns.exists(w => w.contains("'score'") && w.contains(why) && w.contains("no value from this run")),
                    engine + " " + c + ": " + status.warns
                )
            }
        }
    }

    test("an existing column whose stored name is not the one the load names is left out with a warning") {
        import TransformedColumns._
        // Postgres and SQL Server name the column exactly as the transformation wrote it.
        for (engine <- Seq(Postgres, SqlServer)) {
            val t = if (engine == SqlServer) "nvarchar" else "text"
            val len = if (engine == SqlServer) Some(-1L) else None
            val (left, status) = existingStep(engine, Right(Seq(sc("Score", t, len))))
            assert(left == Set("score"), engine)
            assert(status.warns.exists(w => w.contains("'Score'") && w.contains("'score'") && w.contains("letter case")), engine + ": " + status.warns)
            // the transformation writes Sector, the table has sector
            val (left2, _) = existingStep(engine, Right(Seq(sc("score", t, len))), header = List("city", "temp_c", "Score"))
            assert(left2 == Set("score"), engine)
            assert(existingStep(engine, Right(Seq(sc("score", t, len))))._1.isEmpty, engine)
        }
        // Snowflake folds a plain name to upper case: score resolves to SCORE, a quoted "score" column is another column.
        assert(existingStep(Snowflake, Right(Seq(sc("SCORE", "TEXT", Some(16777216L)))))._1.isEmpty)
        assert(existingStep(Snowflake, Right(Seq(sc("score", "TEXT", Some(16777216L)))))._1 == Set("score"))
        // Databricks matches names whatever their letter case.
        assert(existingStep(Databricks, Right(Seq(sc("Score", "STRING"))))._1.isEmpty)
        // two catalog columns differing only in case: the exact one decides
        assert(existingStep(Postgres, Right(Seq(sc("Score", "integer"), sc("score", "text"))))._1.isEmpty)
    }

    test("which existing column types take every text value, per engine") {
        import TransformedColumns._
        val yes = Seq(
            Postgres -> sc("x", "text"),
            Postgres -> sc("x", "character varying"),
            Postgres -> sc("x", "TEXT"),
            SqlServer -> sc("x", "nvarchar", Some(-1L)),
            SqlServer -> sc("x", "varchar", Some(-1L)),
            SqlServer -> sc("x", "ntext", Some(1073741823L)),
            SqlServer -> sc("x", "text", Some(2147483647L)),
            Snowflake -> sc("x", "TEXT", Some(16777216L)),
            Snowflake -> sc("x", "TEXT", Some(134217728L)),
            Snowflake -> sc("x", "TEXT", None),
            Databricks -> sc("x", "STRING"),
            Databricks -> sc("x", "string")
        )
        val no = Seq(
            Postgres -> sc("x", "integer"),
            Postgres -> sc("x", "character varying", Some(10L)),
            Postgres -> sc("x", "character", Some(1L)),
            Postgres -> sc("x", "jsonb"),
            Postgres -> sc("x", "USER-DEFINED"),
            Postgres -> sc("x", null),
            SqlServer -> sc("x", "nvarchar", Some(50L)),
            SqlServer -> sc("x", "int"),
            SqlServer -> sc("x", "nchar", Some(10L)),
            Snowflake -> sc("x", "TEXT", Some(20L)),
            Snowflake -> sc("x", "NUMBER"),
            Databricks -> sc("x", "INT"),
            Databricks -> sc("x", "VARCHAR"),
            "unknown" -> sc("x", "text")
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

    test("a manually managed table: an added column it lacks, or cannot take, is left out; nothing is read without added columns") {
        val status = new LevelStatusUtil
        val out = transformed(status = status)
        val left = TransformedColumns.manualTable(out, "the Postgres table public.weather", TransformedColumns.Postgres, () => Right(Seq(sc("city", "text"))))
        assert(left == Set("score"))
        assert(status.warns.exists(w => w.contains("'score'") && w.contains("does not have") && w.contains("manageTableManually")), status.warns)

        def manual(c: StoreColumn): Set[String] = TransformedColumns.manualTable(transformed(), "t", TransformedColumns.Postgres, () => Right(Seq(c)))
        assert(manual(sc("score", "text")).isEmpty)
        assert(manual(sc("score", "bigint")) == Set("score"))
        assert(manual(sc("score", "text", nullable = Some(false))) == Set("score"))
        assert(manual(sc("score", "text", generated = Some(true))) == Set("score"))
        assert(manual(sc("Score", "text")) == Set("score"))
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

    /** One table column for [[table]]. */
    private case class Col(name: String, dataType: String, len: AnyRef = null, nullable: String = "YES", generated: Boolean = false)

    /** The engine's catalog row for `c`, in the order its loader reads it. */
    private def catalogRow(db: Database, c: Col): Seq[AnyRef] =
        if (db.usePostgres) Seq(c.name, c.dataType, c.len, c.nullable, if (c.generated) "ALWAYS" else "NEVER", null)
        else if (db.useSqlServer) Seq(c.name, c.dataType, c.len, c.nullable, Integer.valueOf(if (c.generated) 1 else 0), Integer.valueOf(0))
        else if (db.useSnowflake) Seq(c.name, c.dataType, c.len, c.nullable, if (c.generated) "YES" else "NO")
        else Seq(c.name, c.dataType, c.nullable, if (c.generated) "ALWAYS" else "NEVER", if (c.generated) "upper(city)" else null, "NO")

    /** A table of `db`'s engine holding `cols`; lock timeouts are unbounded (each engine's default). */
    private def table(jdbc: FakeJdbc, db: Database, cols: Col*): Unit =
        jdbc.answer = (sql, _) => {
            val s = sql.toLowerCase
            if (s.contains("data_type")) (Nil, cols.map(c => catalogRow(db, c)))
            else if (s.contains("current_setting('lock_timeout')")) (Nil, Seq(Seq[AnyRef]("0")))
            else if (s.contains("@@lock_timeout")) (Nil, Seq(Seq[AnyRef](Integer.valueOf(-1))))
            else if (s.contains("column_name")) (Seq("column_name"), cols.map(c => Seq[AnyRef](c.name)))
            else if (s.contains("information_schema.tables")) (Seq("x"), Seq(Seq[AnyRef]("1")))
            else (Nil, Nil)
        }

    private def cityTemp(db: Database): Seq[Col] =
        Seq(Col(if (db.useSnowflake) "CITY" else "city", "text"), Col(if (db.useSnowflake) "TEMP_C" else "temp_c", "double"))

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

    /** `score` as each engine's table holds a text column Datris added. */
    private def textScore(db: Database, name: String = null): Col =
        if (db.usePostgres) Col(Option(name).getOrElse("score"), "text")
        else if (db.useSqlServer) Col(Option(name).getOrElse("score"), "nvarchar", java.lang.Long.valueOf(-1L))
        else if (db.useSnowflake) Col(Option(name).getOrElse("SCORE"), "TEXT", java.lang.Long.valueOf(16777216L))
        else Col(Option(name).getOrElse("score"), "STRING")
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
            table(jdbc, db, cityTemp(db): _*)
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
            table(jdbc, db, cityTemp(db): _*)
            refuseAddOf(jdbc, "note", "must be owner")
            val e = intercept[SQLException](loaderFor(c).prepare(jdbc))
            assert(e.getMessage == "must be owner", db.toString)
            assert(jdbc.queries.forall(q => !q.toLowerCase.contains("data_type")), db.toString + ": no type read on the declared path")
        }
    }

    /** The table step for `score` on a table that already has `existing`. */
    private def onExisting(db: Database, existing: Col): (Set[String], LevelStatusUtil, Loader, FakeJdbc) = {
        val status = new LevelStatusUtil
        val out = transformed(config(db), status = status)
        val jdbc = new FakeJdbc()
        table(jdbc, db, (cityTemp(db) :+ existing): _*)
        val loader = loaderFor(out)
        (loader.prepare(jdbc), status, loader, jdbc)
    }

    test("each SQL loader: an added column the table already has keeps loading when it takes text, and is left out when it does not") {
        for (db <- Seq(pg, mssql, snow, bricks)) {
            val (okLeft, okStatus, okLoader, ok) = onExisting(db, textScore(db))
            assert(okLeft.isEmpty, db.toString + ": " + okStatus.warns)
            assert(destNames(okLoader.context) == hdr, db.toString)
            assert(okStatus.warns.isEmpty, okStatus.warns)
            assert(!ok.executed.exists(_.startsWith("ALTER TABLE")), db.toString + ": nothing to add")

            val (badLeft, badStatus, badLoader, _) = onExisting(db, Col(if (db.useSnowflake) "SCORE" else "score", intOf(db)))
            assert(badLeft == Set("score"), db.toString)
            assert(destNames(badLoader.context) == List("city", "temp_c"), db.toString)
            assert(badStatus.warns.exists(w => w.contains("'score'") && w.contains(intOf(db)) && w.contains(label(db))), badStatus.warns)

            for ((why, col) <- Seq("NOT NULL" -> textScore(db).copy(nullable = "NO"), "generates" -> textScore(db).copy(generated = true))) {
                val (left, status, loader, _) = onExisting(db, col)
                assert(left == Set("score"), db.toString + " " + why)
                assert(destNames(loader.context) == List("city", "temp_c"))
                assert(status.warns.exists(w => w.contains("'score'") && w.contains(why) && w.contains(label(db))), db.toString + ": " + status.warns)
            }
        }
    }

    test("each SQL loader: an existing column whose stored name the load's quoted name does not reach is left out") {
        // Postgres and SQL Server: the table has "Score", the load names "score".
        for (db <- Seq(pg, mssql)) {
            val (left, status, _, _) = onExisting(db, textScore(db, "Score"))
            assert(left == Set("score"), db.toString)
            assert(status.warns.exists(w => w.contains("'Score'") && w.contains("letter case")), status.warns)
        }
        // Snowflake: a quoted lower-case "score" column; the load's plain score folds to SCORE.
        val (sfLeft, _, _, _) = onExisting(snow, textScore(snow, "score"))
        assert(sfLeft == Set("score"))
        // Databricks resolves names whatever their letter case.
        val (dbxLeft, _, _, _) = onExisting(bricks, textScore(bricks, "Score"))
        assert(dbxLeft.isEmpty)
    }

    test("each SQL loader: with no transformation columns the table step runs exactly the statements and queries it ran before this change") {
        // Literal sequences, read off each loader's table step and the
        // builders it calls at d4bc6dac~1 (before this change), for the
        // weather table holding city and temp_c, with note declared.
        val expected: Map[Database, (List[String], List[(String, Seq[String])])] = Map(
            pg -> (
                List(
                    "CREATE SCHEMA IF NOT EXISTS \"public\";",
                    "create table if not exists \"datris\".\"public\".\"weather\" (\"city\" text, \"temp_c\" float8, \"note\" text);",
                    "ALTER TABLE \"datris\".\"public\".\"weather\" ADD COLUMN IF NOT EXISTS \"note\" text"
                ),
                List(
                    (
                        "SELECT column_name FROM information_schema.columns\nWHERE table_catalog = ? AND table_schema = ?\nAND table_name = ?",
                        Seq("datris", "public", "weather")
                    )
                )
            ),
            mssql -> (
                List(
                    "IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = N'dbo') EXEC(N'CREATE SCHEMA dbo')",
                    "IF NOT EXISTS (SELECT 1 FROM sys.tables t JOIN sys.schemas s ON t.schema_id = s.schema_id WHERE s.name = N'dbo' AND t.name = N'weather')" +
                        " CREATE TABLE dbo.weather (city NVARCHAR(MAX), temp_c FLOAT, note NVARCHAR(MAX))",
                    "ALTER TABLE dbo.weather ADD note NVARCHAR(MAX)"
                ),
                List(("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = N'dbo' AND TABLE_NAME = N'weather'", Nil))
            ),
            snow -> (
                List(
                    "CREATE SCHEMA IF NOT EXISTS ANALYTICS.PUBLIC",
                    "CREATE TABLE IF NOT EXISTS ANALYTICS.PUBLIC.WEATHER (city VARCHAR, temp_c FLOAT, note VARCHAR)",
                    "ALTER TABLE ANALYTICS.PUBLIC.WEATHER ADD COLUMN IF NOT EXISTS note VARCHAR"
                ),
                List(("SELECT column_name FROM ANALYTICS.information_schema.columns\nWHERE table_schema = 'PUBLIC' AND table_name = 'WEATHER'", Nil))
            ),
            bricks -> (
                List(
                    "CREATE SCHEMA IF NOT EXISTS main.default",
                    "CREATE VOLUME IF NOT EXISTS main.default.datris_staging",
                    "CREATE TABLE IF NOT EXISTS main.default.weather (city STRING, temp_c DOUBLE, note STRING)",
                    "ALTER TABLE main.default.weather ADD COLUMN note STRING"
                ),
                List(("SELECT column_name FROM main.information_schema.columns\nWHERE lower(table_schema) = 'default' AND lower(table_name) = 'weather'", Nil))
            )
        )
        for (db <- Seq(pg, mssql, snow, bricks); tx <- Seq(true, false)) {
            val c = ctx(config(db.copy(useTransaction = tx), dest = source :+ ("note" -> "string")), input, List("x,1"), new LevelStatusUtil)
            val jdbc = new FakeJdbc(autoCommit = !tx)
            table(jdbc, db, cityTemp(db): _*)
            val loader = loaderFor(c)
            assert(loader.prepare(jdbc).isEmpty)
            assert(loader.context eq c, db.toString)
            val (stmts, queries) = expected(db)
            assert(jdbc.executed.toList == stmts, db.toString)
            assert(jdbc.queries.toList.zip(jdbc.queryParams.toList) == queries, db.toString)
            assert(jdbc.savepoints.isEmpty, db.toString)
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
            table(jdbc, db, cityTemp(db): _*)
            val loader = loaderFor(out)
            assert(loader.prepare(jdbc) == Set("score"), db.toString)
            assert(destNames(loader.context) == List("city", "temp_c"))
            assert(jdbc.executed.isEmpty, db.toString + ": a manually managed table gets no statement: " + jdbc.executed)
            assert(status.warns.exists(_.contains("'score'")), status.warns)
        }
    }

    // ---- lock waits for an added column's ADD COLUMN ----

    private def addedRun(db: Database, tx: Boolean, lockSetting: AnyRef = null, refuseAdd: Boolean = false): (FakeJdbc, LevelStatusUtil, Set[String]) = {
        val status = new LevelStatusUtil
        val out = transformed(config(db.copy(useTransaction = tx)), status = status)
        val jdbc = new FakeJdbc(autoCommit = !tx)
        table(jdbc, db, cityTemp(db): _*)
        if (lockSetting != null) {
            val base = jdbc.answer
            jdbc.answer = (sql, p) => if (sql.toLowerCase.contains("lock_timeout")) (Nil, Seq(Seq(lockSetting))) else base(sql, p)
        }
        if (refuseAdd) refuseAddOf(jdbc, "score", "ERROR: canceling statement due to lock timeout")
        (jdbc, status, loaderFor(out).prepare(jdbc))
    }

    private def around(jdbc: FakeJdbc): List[String] =
        jdbc.executed.toList.filter(s => s.contains("lock_timeout") || s.contains("LOCK_TIMEOUT") || (s.startsWith("ALTER TABLE") && s.contains("score")))

    test("Postgres: an added column's ADD COLUMN waits at most 5 seconds for its lock, and the setting is put back") {
        val alter = "ALTER TABLE \"datris\".\"public\".\"weather\" ADD COLUMN IF NOT EXISTS \"score\" text"
        val (auto, _, autoLeft) = addedRun(pg, tx = false)
        assert(autoLeft.isEmpty)
        assert(around(auto) == List("SET lock_timeout = '5s'", alter, "SET lock_timeout = 0"))

        val (tx, _, txLeft) = addedRun(pg, tx = true)
        assert(txLeft.isEmpty)
        assert(around(tx) == List("SET LOCAL lock_timeout = '5s'", alter, "SET LOCAL lock_timeout = 0"))
        assert(tx.savepoints.toList == List("set", "release"))

        // the lock wait runs out: left out with the store's reason; autocommit puts the setting back,
        // a transaction rolls back to the savepoint, which undoes the SET LOCAL
        val (autoT, autoStatus, autoTLeft) = addedRun(pg, tx = false, refuseAdd = true)
        assert(autoTLeft == Set("score"))
        assert(around(autoT) == List("SET lock_timeout = '5s'", alter, "SET lock_timeout = 0"))
        assert(autoStatus.warns.exists(_.contains("canceling statement due to lock timeout")), autoStatus.warns)
        val (txT, _, txTLeft) = addedRun(pg, tx = true, refuseAdd = true)
        assert(txTLeft == Set("score"))
        assert(around(txT) == List("SET LOCAL lock_timeout = '5s'", alter))
        assert(txT.savepoints.toList == List("set", "rollback"))

        // a bound the install already set is left alone
        val (bounded, _, _) = addedRun(pg, tx = false, lockSetting = "30s")
        assert(around(bounded) == List(alter))
    }

    test("SQL Server: an added column's ADD waits at most 5 seconds for its lock, and the setting is put back") {
        val alter = "ALTER TABLE dbo.weather ADD score NVARCHAR(MAX)"
        for (tx <- Seq(true, false); refuse <- Seq(false, true)) {
            val (jdbc, _, left) = addedRun(mssql, tx = tx, refuseAdd = refuse)
            assert(left == (if (refuse) Set("score") else Set.empty[String]), "tx=" + tx + " refuse=" + refuse)
            assert(around(jdbc) == List("SET LOCK_TIMEOUT 5000", alter, "SET LOCK_TIMEOUT -1"), "tx=" + tx + " refuse=" + refuse)
        }
        val (bounded, _, _) = addedRun(mssql, tx = false, lockSetting = Integer.valueOf(2000))
        assert(around(bounded) == List(alter))
    }

    // ---- several destinations ----

    test("two SQL destinations, one refuses: the other loads the column, nothing is stored, and the next run warns again without failing") {
        val both = pg.copy(useSqlServer = true, credentialsSecret = "mssql")
        var storedConfig = config(both)
        val writes = ListBuffer[List[String]]()
        // Postgres takes the ADD on run 1 and has the column from then on.
        val pgTable = ListBuffer[Col](Col("city", "text"), Col("temp_c", "double precision"))
        def run(): (Set[String], Set[String], LevelStatusUtil, JobContext) = {
            val status = new LevelStatusUtil
            val out = TransformedColumns.reconcile(
                ctx(storedConfig, hdr, List("Shanghai,4.7,n/a"), status),
                input,
                (_, f) => { writes += f.map(_.name); storedConfig = TransformedColumns.withDestFields(storedConfig, f) }
            )
            val pgJdbc = new FakeJdbc()
            table(pgJdbc, pg, pgTable.toList: _*)
            val pgLeft = new PostgresLoader(out).prepareTable(pgJdbc.statement)
            if (pgJdbc.executed.exists(s => s.startsWith("ALTER TABLE") && s.contains("score"))) pgTable += Col("score", "text")
            val msJdbc = new FakeJdbc()
            table(msJdbc, mssql, Col("city", "nvarchar", java.lang.Long.valueOf(-1L)), Col("temp_c", "float"))
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
        table(jdbc, pg, Col("city", "text"), Col("temp_c", "double precision"))
        refuseAddOf(jdbc, "score", "must be owner")
        val loader = new PostgresLoader(out)
        assert(loader.prepareTable(jdbc.statement) == Set("score"))
        assert(destNames(out) == hdr, "the object store loader's context is not narrowed")
        TransformedColumns.storeAfterLoad(out)
        assert(stored.isEmpty)
    }
}
