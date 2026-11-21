package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import com.mongodb.{MongoCommandException, MongoCredential, MongoSecurityException, MongoSocketOpenException, MongoSocketReadTimeoutException, ServerAddress}
import org.bson.BsonDocument
import org.mockito.ArgumentMatchers.{anyInt, anyString}
import org.mockito.Mockito.{doThrow, inOrder, mock, never, verify, when}
import org.scalatest.funsuite.AnyFunSuite

import java.net.{ConnectException, SocketTimeoutException, UnknownHostException}
import java.sql.{Connection, SQLException, SQLTimeoutException, Statement}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.util.Try

/** Story: Pipeline delete: a table, collection or prefix that could not be
  * removed is reported, and no cleanup can hang the request
  * (plans/stories/pipeline-delete-reports-failed-cleanup.md), Acceptance,
  * `DestinationDropSpec`, plus the Decision 3 bounds through seams.
  *
  * Pinned seam (no Spring, no DatrisEnvironment, no store):
  * {{{
  * object DestinationDrop {
  *     // The sibling's VectorStoreDrop.Outcome, one type (moved here and
  *     // aliased from VectorStoreDrop, or aliased here):
  *     Outcome; Removed, Absent, NotSent, Failed(reason: String)
  *     val TimeoutSeconds: Int                     // 30, DestinationProbe.DefaultDeadline
  *     case class Step(label: String, countsAsData: Boolean, run: () => Outcome)
  *     def classify(kind: String, e: Throwable): Outcome   // pure, walks causes
  *     def runAll(steps: Seq[Step]): Seq[(Step, Outcome)]  // never throws
  *     def warning(label: String, reason: String, configDeleted: Boolean): String
  *     // Decision 3 bounds:
  *     def jdbcProperties(user: String, password: String): java.util.Properties
  *     def execute(kind: String, conn: java.sql.Connection, sql: String): Outcome
  *     def mongoSettings(connectionString: String): com.mongodb.MongoClientSettings
  *     def pgvectorJdbc(secret: Option[java.util.Map[String, String]], tenantDatabase: Option[String])
  *         : Either[Outcome, (String, java.util.Properties)]
  * }
  * }}}
  * `kind` is the destination kind: postgres, pgvector, mongodb, snowflake,
  * sqlserver, databricks, objectstore, scratch.
  *
  * The codes below stand in for the story's Step 3 recording. Postgres:
  * SQLSTATEs from the PostgreSQL error-code appendix. "Missing" answers
  * reuse the ones DestinationProbe already treats as "does not exist"
  * (postgresMissing, snowflakeMissingDatabase, sqlServerMissingDatabase,
  * databricksMissingCatalog / databricksObjectMissing, objectStoreMissing).
  * Snowflake 390100 / 3001 / 604, SQL Server 18456 / 3701 / 08S01,
  * Databricks INSUFFICIENT_PERMISSIONS / SCHEMA_NOT_FOUND and MongoDB codes
  * 13 / 26 are from the drivers' documentation only, not a live store. The
  * Databricks and Snowflake connect failures arrive as the DatrisException
  * text their *ConnectionUtil builds today (DatabricksConnectionUtil
  * .translateConnectError drops the cause), and a missing secret as the
  * DatrisException SecretsRetrieverUtil / CredentialResolver throw; the
  * story leaves those helpers unchanged, so classify sees exactly these. */
class DestinationDropSpec extends AnyFunSuite {

    import DestinationDrop._

    private val NC = "the store is not configured"
    private val UR = "the store could not be reached"
    private val TO = "the store did not answer in time"
    private val CR = "the store refused the credentials"
    private val PM = "the store refused permission to remove it"
    private val RE = "the store reported an error"
    private val ClosedReasons = Set(NC, UR, TO, CR, PM, RE)

    private val Kinds = Seq("postgres", "pgvector", "mongodb", "snowflake", "sqlserver", "databricks", "objectstore", "scratch")

    private def sql(state: String, code: Int = 0, msg: String = "driver said something"): SQLException = new SQLException(msg, state, code)

    private def expect(kind: String, e: Throwable, want: Any): Unit = {
        val got = classify(kind, e)
        val wanted = want match {
            case r: String => Failed(r)
            case o => o
        }
        assert(got == wanted, s"$kind: classify(${e.getClass.getSimpleName}: ${e.getMessage}) = $got, expected $wanted")
    }

    private val addr = new ServerAddress("mongo-host.internal", 27017)
    private def mongoCommand(code: Int, msg: String): MongoCommandException =
        new MongoCommandException(BsonDocument.parse("{\"ok\": 0, \"code\": " + code + ", \"errmsg\": \"" + msg + "\"}"), addr)

    // ------------------------------------------------------------ bullet 1

    test(
        "classify postgres: 08xxx and ConnectException are 'could not be reached'; SocketTimeoutException, SQLTimeoutException, 57014 and 55P03 are 'did not answer in time'; 28xxx is 'refused the credentials'; 42501 is 'refused permission'; 3D000 and 3F000 are Absent; anything else is 'reported an error'"
    ) {
        Seq("postgres", "pgvector").foreach { k =>
            Seq("08001", "08006", "08004", "08003").foreach(s => expect(k, sql(s), UR))
            expect(k, new ConnectException("Connection refused"), UR)
            expect(k, new SQLException("Connection to db-host:5432 refused", "08001", new ConnectException("Connection refused")), UR)
            expect(k, new SocketTimeoutException("Read timed out"), TO)
            expect(k, new SQLTimeoutException("timed out"), TO)
            expect(k, sql("57014", msg = "canceling statement due to statement timeout"), TO)
            expect(k, sql("55P03", msg = "could not obtain lock on relation"), TO)
            Seq("28000", "28P01").foreach(s => expect(k, sql(s, msg = "password authentication failed for user \"datris\""), CR))
            expect(k, sql("42501", msg = "must be owner of table cf_pg"), PM)
            expect(k, sql("3D000", msg = "database \"gone\" does not exist"), Absent)
            expect(k, sql("3F000", msg = "schema \"gone\" does not exist"), Absent)
            expect(k, sql("XX000"), RE)
            expect(k, sql(null), RE)
            expect(k, new RuntimeException("boom"), RE)
            // Causes are walked: a wrapped driver exception classifies as itself.
            expect(k, new RuntimeException("wrapped", sql("28P01")), CR)
            expect(k, new java.util.concurrent.ExecutionException(sql("42501")), PM)
            expect(k, new RuntimeException("wrapped", new SocketTimeoutException("Read timed out")), TO)
        }
    }

    // ------------------------------------------------------------ bullet 2

    test(
        "classify mongodb, snowflake, sqlserver, databricks and objectstore: one case per reason, from the exception classes and codes recorded in Step 3; a missing bucket or prefix is Absent"
    ) {
        // MongoDB (driver 4.11): socket open, socket read timeout, auth, codes 13 / 26.
        expect("mongodb", new MongoSocketOpenException("Exception opening socket", addr), UR)
        expect("mongodb", new MongoSocketReadTimeoutException("Timeout while receiving message", addr, new SocketTimeoutException("Read timed out")), TO)
        val cred = MongoCredential.createCredential("datris", "admin", "pw".toCharArray)
        expect("mongodb", new MongoSecurityException(cred, "Exception authenticating"), CR)
        expect("mongodb", mongoCommand(13, "not authorized on analytics to execute command { drop: orders }"), PM)
        expect("mongodb", mongoCommand(26, "ns not found"), Absent)
        expect("mongodb", mongoCommand(2, "BadValue"), RE)

        // Snowflake: the util's 404 answer, login 390100, privileges 3001,
        // statement timeout 604 / 57014, missing database 2003 (the probe's).
        expect(
            "snowflake",
            new DatrisException(
                "No Snowflake account answers at jdbc:snowflake://acct.snowflakecomputing.com/ (HTTP 404). The credentials secret's 'account' field ..."
            ),
            UR
        )
        expect(
            "snowflake",
            new SQLException("JDBC driver encountered communication error", "08001", new UnknownHostException("acct.snowflakecomputing.com")),
            UR
        )
        expect("snowflake", new SQLException("Statement reached its statement or warehouse timeout of 30 second(s) and was canceled.", "57014", 604), TO)
        expect("snowflake", new SocketTimeoutException("Read timed out"), TO)
        expect("snowflake", new SQLException("Incorrect username or password was specified.", "08004", 390100), CR)
        expect("snowflake", new SQLException("SQL access control error: Insufficient privileges to operate on table 'T'", "42501", 3001), PM)
        // Lead ruling 4: "does not exist or not authorized" is ambiguous (the
        // table may still be there), so it is a reported error, not Absent.
        expect("snowflake", new SQLException("SQL compilation error: Database 'GONE' does not exist or not authorized.", "02000", 2003), RE)
        expect("snowflake", new SQLException("SQL compilation error", "42000", 1003), RE)

        // SQL Server: 08S01 link failure, query timeout, login 18456,
        // permission 3701 (IF EXISTS: a missing table is not an error),
        // database cannot be opened 4060 (the probe's) / does not exist 911.
        expect("sqlserver", new SQLException("The TCP/IP connection to the host db-host, port 1433 has failed.", "08S01", 0), UR)
        expect("sqlserver", new SQLTimeoutException("The query has timed out."), TO)
        expect("sqlserver", new SQLException("Login failed for user 'loader'.", "S0001", 18456), CR)
        expect("sqlserver", new SQLException("Cannot drop the table 'dbo.t', because it does not exist or you do not have permission.", "S0001", 3701), PM)
        // Lead ruling 4: 4060 is missing OR no user for the login: a reported error.
        expect("sqlserver", new SQLException("Cannot open database \"gone\" requested by the login. The login failed.", "S0001", 4060), RE)
        expect("sqlserver", new SQLException("Database 'gone' does not exist. Make sure that the name is entered correctly.", "S0001", 911), Absent)
        expect("sqlserver", new SQLException("Incorrect syntax", "S0001", 102), RE)

        // Databricks: the util's translated connect errors (cause dropped),
        // then the SQL error classes of the DROP itself.
        expect(
            "databricks",
            new DatrisException("No Databricks workspace answers at jdbc:databricks://dbc-x.cloud.databricks.com:443. The credentials ..."),
            UR
        )
        expect(
            "databricks",
            new DatrisException("Databricks connection timed out — the SQL warehouse may still be auto-starting ... Underlying driver error: x"),
            TO
        )
        expect("databricks", new SocketTimeoutException("Read timed out"), TO)
        expect(
            "databricks",
            new DatrisException("Databricks token authentication failed — verify the secret's 'token' ... Underlying driver error: HTTP Response code: 401"),
            CR
        )
        expect("databricks", new DatrisException("Databricks OAuth M2M authentication failed — verify the secret's 'clientId'/'clientSecret' ..."), CR)
        expect("databricks", new SQLException("[INSUFFICIENT_PERMISSIONS] Insufficient privileges: User does not have MANAGE on Table 'c.s.t'.", "42501"), PM)
        expect("databricks", new SQLException("[SCHEMA_NOT_FOUND] The schema `c`.`s` cannot be found.", "42704"), Absent)
        expect("databricks", new SQLException("[NO_SUCH_CATALOG_EXCEPTION] Catalog 'gone' was not found. NO_SUCH_CATALOG", "42704"), Absent)
        expect("databricks", new SQLException("[PARSE_SYNTAX_ERROR] Syntax error", "42601"), RE)

        // Object store and scratch (S3A): connect refused / unknown host,
        // socket timeout, 403 AccessDenied, missing prefix or bucket.
        Seq("objectstore", "scratch").foreach { k =>
            expect(k, new java.io.IOException("doesBucketExist on oss-data", new ConnectException("Connection refused")), UR)
            expect(k, new UnknownHostException("minio"), UR)
            expect(k, new SocketTimeoutException("Read timed out"), TO)
            expect(k, new java.nio.file.AccessDeniedException("s3a://oss-data/cf_os: delete on cf_os: AccessDenied"), PM)
            expect(k, new java.io.FileNotFoundException("No such file or directory: s3a://oss-data/cf_os"), Absent)
            expect(k, new org.apache.hadoop.fs.s3a.UnknownStoreException("s3a://nope", "bucket does not exist"), Absent)
            expect(k, new java.io.IOException("partial delete"), RE)
        }
    }

    // ------------------------------------------------------------ bullet 3

    test("a missing secret or credentials secret is 'the store is not configured' for every kind, including pgvector with no secret and with no jdbcUrl") {
        // Platform Postgres and MongoDB (SecretsRetrieverUtil's texts).
        Seq(
            "Could not retrieve database information from Secrets Manager, secret name: oss/postgres",
            "Could not retrieve the Postgres username from Secrets Manager",
            "Could not retrieve the Postgres jdbcUrl from Secrets Manager"
        ).foreach(m => expect("postgres", new DatrisException(m), NC))
        Seq(
            "Could not retrieve database information from Secrets Manager, secret name: oss/mongodb",
            "Could not retrieve the MongoDB connectionString from Secrets Manager"
        ).foreach(m => expect("mongodb", new DatrisException(m), NC))

        // Snowflake, SQL Server, Databricks: the resolvers' own exceptions
        // where they throw before any secret read, else their texts.
        def thrown(f: => Any): Throwable = Try(f).failed.get
        expect("snowflake", thrown(CredentialResolver.resolveSnowflake(null)), NC)
        expect(
            "snowflake",
            new DatrisException("Snowflake credentialsSecret not found in Secrets Manager at path 'oss/sf' (looked up by name 'sf'). Create it ..."),
            NC
        )
        expect("snowflake", new DatrisException("Snowflake credentialsSecret 'sf' is missing required field 'user'."), NC)
        expect("sqlserver", thrown(CredentialResolver.resolveSqlServer(null)), NC)
        expect("sqlserver", thrown(CredentialResolver.resolveSqlServerFromSecret("ms", new java.util.HashMap[String, String]())), NC)
        expect(
            "sqlserver",
            new DatrisException("SQL Server credentialsSecret not found in Secrets Manager at path 'oss/ms' (looked up by name 'ms'). Create it ..."),
            NC
        )
        expect("databricks", thrown(CredentialResolver.resolveDatabricks(null)), NC)
        expect(
            "databricks",
            new DatrisException("Databricks credentialsSecret not found in Secrets Manager at path 'oss/dbx' (looked up by name 'dbx'). Create it ..."),
            NC
        )
        // Object store on an S3 bucket with its own credentials secret.
        expect(
            "objectstore",
            new DatrisException("S3 credentialsSecret not found in Secrets Manager at path 'oss/s3' (looked up by name 's3'). Create it ..."),
            NC
        )
        expect("objectstore", new DatrisException("S3 credentialsSecret 's3' is missing required field 'accessKey' (also accepted: ...)"), NC)

        // pgvector: today the delete skips silently; now "not configured".
        def secret(kv: (String, String)*): java.util.Map[String, String] = {
            val m = new java.util.HashMap[String, String]()
            kv.foreach { case (k, v) => m.put(k, v) }
            m
        }
        Seq(None, Some(secret("username" -> "u", "password" -> "p")), Some(secret("jdbcUrl" -> "  ", "username" -> "u"))).foreach { s =>
            assert(pgvectorJdbc(s, None) == Left(Failed(NC)), s"pgvector secret $s")
            assert(pgvectorJdbc(s, Some("tenant")) == Left(Failed(NC)), s"pgvector secret $s (multi-tenant)")
        }
        // A usable secret: the URL the probe and the loader use, with the bounds.
        val raw = "jdbc:postgresql://pgv-host:5432/datris"
        pgvectorJdbc(Some(secret("jdbcUrl" -> raw, "username" -> "u", "password" -> "p")), Some("tenant")) match {
            case Right((url, props)) =>
                assert(url == DestinationProbe.pgvectorJdbcUrl(raw, Some("tenant")), url)
                assert(props.getProperty("user") == "u" && props.getProperty("password") == "p", props)
                Seq("connectTimeout", "loginTimeout").foreach(p => assert(props.getProperty(p) == "30", s"$p: $props"))
                // Review finding 2: the socket waits past the bound so the statement timeout answers first.
                assert(props.getProperty("socketTimeout") == "40", props)
            case other => fail(s"expected a connection target, got $other")
        }
    }

    // ------------------------------------------------------------ bullet 4

    test("runAll: a step that throws is Failed, later steps still run, order is kept") {
        val ran = new AtomicInteger(0)
        val steps = Seq(
            Step("postgres table datris.public.a", countsAsData = true, () => { ran.incrementAndGet(); Removed }),
            Step("mongodb collection datris.b", countsAsData = true, () => { ran.incrementAndGet(); throw new RuntimeException("boom at mongo-host") }),
            Step("object-store prefix oss-data.c", countsAsData = true, () => { ran.incrementAndGet(); throw new NullPointerException() }),
            Step("snowflake table D.S.T", countsAsData = true, () => { ran.incrementAndGet(); Failed(UR) }),
            Step("scratch folder of this pipeline", countsAsData = false, () => { ran.incrementAndGet(); Absent })
        )
        val results = runAll(steps)
        assert(ran.get == 5, "every step runs")
        assert(results.map(_._1) == steps, "order and steps kept")
        assert(results(0)._2 == Removed)
        results(1)._2 match {
            case Failed(r) => assert(ClosedReasons.contains(r) && !r.contains("boom") && !r.contains("mongo-host"), r)
            case o => fail(s"a throwing step must be Failed, got $o")
        }
        results(2)._2 match {
            case Failed(r) => assert(ClosedReasons.contains(r), r)
            case o => fail(s"a throwing step must be Failed, got $o")
        }
        assert(results(3)._2 == Failed(UR))
        assert(results(4)._2 == Absent)
        assert(runAll(Nil).isEmpty)
    }

    // ------------------------------------------------------------ bullet 5

    test("no reason and no warning contains the exception message, SQL text, a host, a JDBC URL or a secret name") {
        val leak =
            "LEAK-MSG DROP TABLE IF EXISTS \"public\".\"other_t\" at db-host.internal:5432 jdbc:postgresql://db-host.internal:5432/datris secret oss/pg-secret"
        val samples: Seq[Throwable] = Seq(
            new SQLException(leak, "08001"),
            new SQLException(leak, "28P01"),
            new SQLException(leak, "42501"),
            new SQLException(leak, "57014"),
            new SQLException(leak, "XX000"),
            new SQLException(leak, "S0001", 18456),
            new SQLException(leak, "42501", 3001),
            new SQLTimeoutException(leak),
            new SocketTimeoutException(leak),
            new ConnectException(leak),
            new RuntimeException(leak, new SQLException(leak, "08006")),
            new DatrisException(leak),
            new DatrisException("Could not retrieve database information from Secrets Manager, secret name: oss/pg-secret"),
            new DatrisException("Snowflake credentialsSecret not found in Secrets Manager at path 'oss/pg-secret' (looked up by name 'pg-secret')."),
            new java.nio.file.AccessDeniedException(leak),
            new java.io.IOException(leak),
            new MongoSocketOpenException(leak, addr),
            mongoCommand(13, "LEAK-MSG not authorized"),
            new MongoSecurityException(MongoCredential.createCredential("datris", "admin", "pw".toCharArray), leak)
        )
        val forbidden =
            Seq("LEAK-MSG", "DROP TABLE", "other_t", "db-host", "mongo-host", "5432", "27017", "jdbc:", "oss/pg-secret", "pg-secret", "Secrets Manager")
        val label = "postgres table datris.public.cf_pg"
        for (kind <- Kinds; e <- samples) {
            classify(kind, e) match {
                case Failed(r) =>
                    assert(ClosedReasons.contains(r), s"$kind: not a closed-set reason: '$r'")
                    assert(!r.startsWith("the store answered HTTP"), r)
                    forbidden.foreach(f => assert(!r.contains(f), s"$kind: reason '$r' contains '$f'"))
                    Seq(true, false).foreach { cd =>
                        val w = warning(label, r, cd)
                        forbidden.foreach(f => assert(!w.contains(f), s"$kind: warning '$w' contains '$f'"))
                    }
                case Removed | Absent | NotSent =>
                case other => fail(s"$kind: unexpected outcome $other")
            }
        }
    }

    // ------------------------------------------------------------ bullet 6

    test("warning text for a config delete and for a reset equals the sibling's sentences around the label") {
        assert(
            warning("postgres table datris.public.cf_pg", UR, configDeleted = true) ==
                "Could not remove postgres table datris.public.cf_pg: the store could not be reached. Its pipeline configuration was deleted; remove it in the store."
        )
        assert(
            warning("mongodb collection analytics.orders", CR, configDeleted = false) ==
                "Could not remove mongodb collection analytics.orders: the store refused the credentials. Run the reset again once the store is available."
        )
        assert(
            warning("scratch folder of this pipeline", PM, configDeleted = true) ==
                "Could not remove scratch folder of this pipeline: the store refused permission to remove it. Its pipeline configuration was deleted; remove it in the store."
        )
        // One implementation: around a vector container's label the sentences
        // are exactly VectorStoreDrop.warning's, for every reason and both modes.
        val keys = Seq(
            VectorContainers.key("qdrant", "vd_q", None).get,
            VectorContainers.key("chroma", "vd_c", None).get,
            VectorContainers.key("pgvector", "vd_t", Some("public")).get
        )
        for (k <- keys; r <- ClosedReasons.toSeq; cd <- Seq(true, false))
            assert(warning(VectorContainers.label(k), r, cd) == VectorStoreDrop.warning(k, r, cd), s"$k / $r / $cd")
    }

    test("one Outcome type: DestinationDrop's outcomes are the sibling's VectorStoreDrop outcomes") {
        val removed: VectorStoreDrop.Outcome = Removed
        val absent: VectorStoreDrop.Outcome = Absent
        val notSent: VectorStoreDrop.Outcome = NotSent
        assert(removed == VectorStoreDrop.Removed && absent == VectorStoreDrop.Absent && notSent == VectorStoreDrop.NotSent)
        val f: VectorStoreDrop.Outcome = Failed(UR)
        assert(f == VectorStoreDrop.Failed(UR))
        assert((VectorStoreDrop.Failed(TO): Any) match { case Failed(r) => r == TO; case _ => false })
    }

    // ------------------------------------------------------------ Decision 3 (bounds)

    test("Decision 3: the bound is 30 s, the probe's deadline, on login, socket and every JDBC property the drops set") {
        assert(TimeoutSeconds == 30)
        assert(TimeoutSeconds.toLong == DestinationProbe.DefaultDeadline.toSeconds)
        val p = jdbcProperties("datris", "pw")
        assert(p.getProperty("user") == "datris" && p.getProperty("password") == "pw")
        Seq("connectTimeout", "loginTimeout").foreach(k => assert(p.getProperty(k) == "30", s"$k: $p"))
        // Review finding 2: the bound plus a margin, so 57014 arrives before the socket gives up.
        assert(p.getProperty("socketTimeout") == "40", p)
        assert(SocketTimeoutSeconds == TimeoutSeconds + 10)
    }

    test("Decision 3: the drop statement gets setQueryTimeout(30) before it runs; a cancelled statement is 'did not answer in time' and is closed") {
        def fake(behaviour: Statement => Unit): (Connection, Statement) = {
            val conn = mock(classOf[Connection])
            val st = mock(classOf[Statement])
            when(conn.createStatement()).thenReturn(st)
            behaviour(st)
            (conn, st)
        }
        val (c1, s1) = fake(st => when(st.execute(anyString())).thenReturn(false))
        assert(DestinationDrop.execute("postgres", c1, "DROP TABLE IF EXISTS \"public\".\"cf_pg\"") == Removed)
        val order = inOrder(s1)
        order.verify(s1).setQueryTimeout(30)
        order.verify(s1).execute("DROP TABLE IF EXISTS \"public\".\"cf_pg\"")
        verify(s1).close()

        // The server abandons the lock wait (57014): the table is not dropped
        // late, and the caller is told it did not answer in time.
        val (c2, s2) = fake(st => when(st.execute(anyString())).thenThrow(new SQLException("canceling statement due to statement timeout", "57014")))
        assert(DestinationDrop.execute("pgvector", c2, "DROP TABLE IF EXISTS \"public\".\"v\"") == Failed(TO))
        verify(s2).setQueryTimeout(30)
        verify(s2).close()

        // A statement that refuses a query timeout still runs (as the probe's Try).
        val (c3, s3) = fake { st =>
            doThrow(new SQLException("not supported")).when(st).setQueryTimeout(anyInt())
            when(st.execute(anyString())).thenReturn(false)
        }
        assert(DestinationDrop.execute("postgres", c3, "DROP TABLE IF EXISTS \"public\".\"t\"") == Removed)
        verify(s3).close()

        // A failing createStatement never throws out of execute.
        val c4 = mock(classOf[Connection])
        when(c4.createStatement()).thenThrow(new SQLException("connection closed", "08003"))
        assert(DestinationDrop.execute("postgres", c4, "DROP TABLE IF EXISTS \"public\".\"t\"") == Failed(UR))
        val (c5, s5) = fake(st => when(st.execute(anyString())).thenThrow(new SQLException("x", "42501")))
        assert(DestinationDrop.execute("postgres", c5, "DROP") == Failed(PM))
        verify(s5, never()).executeUpdate(anyString())
    }

    test("Decision 3: the MongoDB drop client has the probe's 30 s server selection, connect and read timeouts") {
        val s = mongoSettings("mongodb://user:pw@mongo-host.internal:27017/?authSource=admin")
        assert(s.getClusterSettings.getServerSelectionTimeout(TimeUnit.SECONDS) == 30)
        assert(s.getSocketSettings.getConnectTimeout(TimeUnit.SECONDS) == 30)
        assert(s.getSocketSettings.getReadTimeout(TimeUnit.SECONDS) == 30)
        assert(s.getClusterSettings.getHosts.get(0).getHost == "mongo-host.internal", "the connection string is applied")
    }

    // ------------------------------------------------------------ review follow-ups

    test("review 2: a connection-class error caused by a socket timeout is 'did not answer in time'; a connect timeout is 'could not be reached'") {
        Seq("postgres", "pgvector").foreach { k =>
            expect(k, new SQLException("An I/O error occurred while sending to the backend.", "08006", new SocketTimeoutException()), TO)
            expect(k, new SQLException("An I/O error occurred", "08006", new SocketTimeoutException("Read timed out")), TO)
            expect(k, new SQLException("The connection attempt failed.", "08001", new SocketTimeoutException("connect timed out")), UR)
            expect(k, new SQLException("Connection refused", "08001", new ConnectException("Connection refused")), UR)
        }
        expect("sqlserver", new SQLException("Read timed out", "08S01", new SocketTimeoutException("Read timed out")), TO)
        expect("sqlserver", new SQLException("The TCP/IP connection to the host failed.", "08S01", 0), UR)
    }

    private def fakeConn(): (Connection, Statement) = {
        val conn = mock(classOf[Connection])
        val st = mock(classOf[Statement])
        when(conn.createStatement()).thenReturn(st)
        (conn, st)
    }

    test("review 3: a Postgres drop runs in one transaction: SET LOCAL and the drop, then one commit") {
        val (conn, st) = fakeConn()
        when(st.execute(anyString())).thenReturn(false)
        assert(DestinationDrop.execute("postgres", conn, "DROP TABLE IF EXISTS \"public\".\"t\"") == Removed)
        val o = inOrder(conn, st)
        o.verify(conn).setAutoCommit(false)
        o.verify(st).execute(StatementTimeoutSql)
        o.verify(st).execute("DROP TABLE IF EXISTS \"public\".\"t\"")
        o.verify(conn).commit()
        verify(conn, org.mockito.Mockito.times(1)).commit()
        verify(conn, never()).rollback()
        assert(StatementTimeoutSql == "SET LOCAL statement_timeout = 30000")
    }

    test("review 3: a drop cancelled by the statement timeout (57014) is rolled back and never committed") {
        val (conn, st) = fakeConn()
        when(st.execute(StatementTimeoutSql)).thenReturn(false)
        when(st.execute("DROP TABLE IF EXISTS \"public\".\"t\"")).thenThrow(new SQLException("canceling statement due to statement timeout", "57014"))
        assert(DestinationDrop.execute("pgvector", conn, "DROP TABLE IF EXISTS \"public\".\"t\"") == Failed(TO))
        verify(conn).rollback()
        verify(conn, never()).commit()
        verify(st).close()
    }

    test("review 3: a snowflake drop sends no SET LOCAL and no commit") {
        val (conn, st) = fakeConn()
        when(st.execute(anyString())).thenReturn(false)
        assert(DestinationDrop.execute("snowflake", conn, "DROP TABLE IF EXISTS T") == Removed)
        verify(st).setQueryTimeout(30)
        verify(st).execute("DROP TABLE IF EXISTS T")
        verify(st, never()).execute(StatementTimeoutSql)
        verify(conn, never()).commit()
        verify(conn, never()).setAutoCommit(false)
    }

    test("review 9: a store that rejects SET LOCAL gets the drop as before: rolled back, autocommit restored, drop run, no commit") {
        val (conn, st) = fakeConn()
        when(st.execute(StatementTimeoutSql)).thenThrow(new SQLException("unrecognized configuration parameter", "42704"))
        when(st.execute("DROP TABLE IF EXISTS \"public\".\"t\"")).thenReturn(false)
        assert(DestinationDrop.execute("postgres", conn, "DROP TABLE IF EXISTS \"public\".\"t\"") == Removed)
        val o = inOrder(conn, st)
        o.verify(st).execute(StatementTimeoutSql)
        o.verify(conn).rollback()
        o.verify(conn).setAutoCommit(true)
        o.verify(st).execute("DROP TABLE IF EXISTS \"public\".\"t\"")
        verify(conn, never()).commit()
        verify(st).setQueryTimeout(30)
    }

    test("review 3: a pgvector secret without username connects as postgres, as the loader does") {
        val m = new java.util.HashMap[String, String]()
        m.put("jdbcUrl", "jdbc:postgresql://pgv-host:5432/datris")
        pgvectorJdbc(Some(m), None) match {
            case Right((url, props)) =>
                assert(url == "jdbc:postgresql://pgv-host:5432/datris")
                assert(props.getProperty("user") == "postgres" && props.getProperty("password") == "", props)
            case other => fail(s"expected a connection target, got $other")
        }
    }

    test(
        "review 1: the Databricks delete waits up to 900 s (the driver default) for a warehouse start; the per-request socket timeout never cuts it shorter; nothing set by default"
    ) {
        // Round 3, ruling 4: 900 s, what main tolerated (was 300).
        assert(DatabricksWarehouseStartSeconds == 900)
        val p = DatabricksConnectionUtil.timeoutProperties(Some(TimeoutSeconds), Some(DatabricksWarehouseStartSeconds)).toMap
        assert(p == Map("socketTimeout" -> "900", "TemporarilyUnavailableRetryTimeout" -> "900"), p)
        // The probe's (and any other bounded) use: unchanged.
        assert(DatabricksConnectionUtil.timeoutProperties(Some(30), None) == Seq("socketTimeout" -> "30"))
        // Loads and queries: driver defaults.
        assert(DatabricksConnectionUtil.timeoutProperties(None, None).isEmpty)
    }

    test("round 3: SQL Server timeout properties: login and query from the bound, socket from its own bound (ms)") {
        assert(
            SqlServerConnectionUtil.timeoutProperties(Some(30), Some(40)).toMap ==
                Map("loginTimeout" -> "30", "queryTimeout" -> "30", "socketTimeout" -> "40000")
        )
        assert(
            SqlServerConnectionUtil.timeoutProperties(Some(30), None).toMap ==
                Map("loginTimeout" -> "30", "queryTimeout" -> "30", "socketTimeout" -> "30000")
        )
        assert(SqlServerConnectionUtil.timeoutProperties(None, None).isEmpty)
        assert(SqlServerConnectionUtil.timeoutProperties(Some(TimeoutSeconds), Some(SocketTimeoutSeconds)).toMap.get("socketTimeout").contains("40000"))
    }

    test(
        "round 3: the Databricks delete pre-checks the workspace with a 30 s TCP connect on the JDBC URL's port; a refused or timed-out connect is 'could not be reached' with no host in the warning"
    ) {
        val saved = DatabricksConnectionUtil.tcpConnect
        try {
            val seen = scala.collection.mutable.ArrayBuffer[(String, Int, Int)]()
            DatabricksConnectionUtil.tcpConnect = (h, p, t) => seen += ((h, p, t))
            DatabricksConnectionUtil.workspaceReachable(
                "dbc-x.cloud.databricks.com",
                DatabricksConnectionUtil.Port,
                "jdbc:databricks://dbc-x.cloud.databricks.com:443"
            )
            assert(seen == Seq(("dbc-x.cloud.databricks.com", 443, 30000)), seen)
            assert(DatabricksConnectionUtil.Port == 443 && DatabricksConnectionUtil.WorkspaceConnectSeconds == 30)

            Seq[Throwable](new ConnectException("Connection refused"), new SocketTimeoutException("connect timed out"), new UnknownHostException("dbc-x")).foreach {
                cause =>
                    DatabricksConnectionUtil.tcpConnect = (_, _, _) => throw cause
                    val e = intercept[DatrisException](
                        DatabricksConnectionUtil.workspaceReachable("dbc-x.cloud.databricks.com", 443, "jdbc:databricks://dbc-x.cloud.databricks.com:443")
                    )
                    assert(e.getMessage.startsWith("No Databricks workspace answers at"), e.getMessage)
                    assert(classify("databricks", e) == Failed(UR), cause.toString)
                    val w = warning("databricks table c.s.t", UR, configDeleted = true)
                    assert(!w.contains("dbc-x") && !w.contains("jdbc:"), w)
            }
        } finally DatabricksConnectionUtil.tcpConnect = saved

        // Wiring: only the delete (warehouseStartSeconds set) pre-checks, on the URL's port.
        val src = new String(
            java.nio.file.Files.readAllBytes(
                Seq("src/main/scala/", "datrisserver/src/main/scala/")
                    .map(r => java.nio.file.Paths.get(r + "ai/datris/util/DatabricksConnectionUtil.scala"))
                    .find(java.nio.file.Files.exists(_))
                    .get
            ),
            java.nio.charset.StandardCharsets.UTF_8
        )
        assert(src.contains("val jdbcUrl = \"jdbc:databricks://\" + host + \":\" + Port"))
        // Round 4: no proxy skip (the driver is never given UseProxy / UseSystemProxy here).
        assert(src.contains("if (warehouseStartSeconds.isDefined) workspaceReachable(host, Port, jdbcUrl)"))
        assert(!src.contains("proxyConfigured") && !src.contains("UseProxy\"") && !src.contains("UseSystemProxy\""))
    }

    test("round 4: the default pre-check connects to a listening loopback port and closes the socket; a closed port is refused") {
        val loop = java.net.InetAddress.getByName("127.0.0.1")
        val server = new java.net.ServerSocket(0, 5, loop)
        try {
            val port = server.getLocalPort
            DatabricksConnectionUtil.tcpConnect("127.0.0.1", port, 5000)
            val made = scala.collection.mutable.ArrayBuffer[java.net.Socket]()
            DatabricksConnectionUtil.connectAny(Seq(loop), port, 5000, () => { val s = new java.net.Socket(); made += s; s })
            assert(made.size == 1 && made.head.isClosed, made)
        } finally server.close()
        // A port nothing listens on (just released).
        val freed = { val t = new java.net.ServerSocket(0, 1, loop); val p = t.getLocalPort; t.close(); p }
        intercept[java.net.ConnectException](DatabricksConnectionUtil.tcpConnect("127.0.0.1", freed, 5000))
        intercept[java.net.UnknownHostException](DatabricksConnectionUtil.connectAny(Nil, freed, 5000))
    }

    test("round 4: every resolved address is tried: first refused, second accepts, so the workspace is reachable; all refused rethrows the last") {
        val loop = java.net.InetAddress.getByName("127.0.0.1")
        val server = new java.net.ServerSocket(0, 5, loop)
        try {
            val port = server.getLocalPort
            // The IPv6 loopback has no listener on this port (the server is bound
            // to 127.0.0.1 only): refused, or unavailable on an IPv4-only host.
            val v6 = java.net.InetAddress.getByName("::1")
            val made = scala.collection.mutable.ArrayBuffer[java.net.Socket]()
            DatabricksConnectionUtil.connectAny(Seq(v6, loop), port, 10000, () => { val s = new java.net.Socket(); made += s; s })
            assert(made.size == 2 && made.forall(_.isClosed), made)
            // Both refused: the last failure is thrown.
            val freed = { val t = new java.net.ServerSocket(0, 1, loop); val p = t.getLocalPort; t.close(); p }
            intercept[java.io.IOException](DatabricksConnectionUtil.connectAny(Seq(v6, loop), freed, 10000))
        } finally server.close()
    }

    test("round 4: one shared deadline: each address gets the remaining time divided by the addresses left") {
        val budgets = scala.collection.mutable.ArrayBuffer[Int]()
        class Fake extends java.net.Socket {
            override def connect(endpoint: java.net.SocketAddress, timeout: Int): Unit = {
                budgets += timeout
                // A blackholed address: the connect uses its whole budget.
                Thread.sleep(timeout.toLong)
                throw new java.net.SocketTimeoutException("connect timed out")
            }
        }
        val addrs = Seq("10.0.0.1", "10.0.0.2", "10.0.0.3").map(a => java.net.InetAddress.getByName(a))
        intercept[java.net.SocketTimeoutException](DatabricksConnectionUtil.connectAny(addrs, 443, 600, () => new Fake))
        assert(budgets.size == 3, budgets)
        // 600 ms shared by three: about 200 each, never more than what is left.
        assert(budgets.head <= 200 && budgets.head >= 150, budgets)
        assert(budgets.sum <= 600, budgets)
    }
}
