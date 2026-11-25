package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Database, DatrisException}
import org.scalatest.funsuite.AnyFunSuite

import java.util.Locale

/** Story: Databricks query: a read from a file path, or from a stream's
  * stored state, is refused (plans/stories/databricks-path-reads-refused.md),
  * Acceptance. Pure: no workspace, no network.
  *
  * Lead ruling: every recommended option stands. Decision 1 (a): a two-part
  * name, the first part not directly preceded by `.`, whose second part is
  * backticked, is refused when the first part (bare or backticked, any letter
  * case) is one of json, csv, avro, parquet, orc, binaryfile, text, delta, xml
  * and the backticked part holds `/` or `:`; or when the first part is any
  * name and the backticked part starts with `/` or holds `:/`. Gap characters
  * (whitespace, Unicode separators, control and format characters) may stand
  * on both sides of the dot; a doubled backtick is part of a name. Matched
  * over the whole text, string literals included. Decisions 2-3:
  * read_statestore, read_state_metadata, cloud_files_state and list_secrets
  * join `BLOCKED_FUNCTIONS`. Decision 4: a path is refused as
  * `Queries cannot read from a file path; name a table instead`, echoing
  * none of the path.
  *
  * The new pure `pathRead(sql): Boolean` is reached by name
  * (QuerySafetyReflect) so this spec compiles before it exists; it is called
  * directly only in the timing case, so the text-length limit in
  * `validateQuery` cannot turn that case into a refusal test. */
class DatabricksPathReadSpec extends AnyFunSuite {

    private val PathRefusal = "Queries cannot read from a file path; name a table instead"

    private def validate(sql: String): Unit = { QuerySafetyReflect.call(DatabricksQueryUtil, "validateQuery", sql); () }

    /** None when accepted, Some(message) when refused. */
    private def outcomeOf(sql: String): Option[String] =
        try { validate(sql); None }
        catch { case e: DatrisException => Some(e.getMessage) }

    /** Every text in `sqls` is refused with exactly the path sentence. Lists
      * every text that is not, so one run shows the whole picture. */
    private def allRefusedAsPath(sqls: Seq[String]): Unit = {
        val wrong = sqls.map(s => s -> outcomeOf(s)).filterNot(_._2.contains(PathRefusal))
        if (wrong.nonEmpty)
            fail(
                wrong.size + " of " + sqls.size + " not refused as a path read:\n" +
                    wrong.map { case (s, o) => "  " + visible(s) + " -> " + o.getOrElse("accepted") }.mkString("\n")
            )
    }

    private def allAccepted(sqls: Seq[String]): Unit = {
        val wrong = sqls.map(s => s -> outcomeOf(s)).filter(_._2.isDefined)
        if (wrong.nonEmpty)
            fail("refused but should be accepted:\n" + wrong.map { case (s, o) => "  " + visible(s) + " -> " + o.get }.mkString("\n"))
    }

    /** Non-ASCII and control characters shown as escapes in failure text. */
    private def visible(s: String): String =
        s.flatMap(c => if (c >= 0x20 && c < 0x7f) c.toString else "\\u%04X".format(c.toInt))

    private def cases(s: String): Seq[String] = Seq(
        s.toUpperCase(Locale.ROOT),
        s.toLowerCase(Locale.ROOT),
        s.zipWithIndex.map {
            case (c, i) => if (i % 2 == 0) c.toUpper else c.toLower
        }.mkString,
        s.zipWithIndex.map {
            case (c, i) => if (i % 2 == 0) c.toLower else c.toUpper
        }.mkString
    ).distinct

    private val FormatWords = Seq("json", "csv", "avro", "parquet", "orc", "binaryfile", "text", "delta", "xml")

    private val Paths = Seq(
        "`s3://b/p`",
        "`/Volumes/c/s/v/f.csv`",
        "`dbfs:/mnt/p`",
        "`file:/Workspace/p`",
        "`abfss://c@a.dfs.core.windows.net/p`",
        "`somedir/t`"
    )

    /** The places a table name stands in a read. */
    private def placed(name: String): Seq[String] = Seq(
        "SELECT * FROM " + name,
        "SELECT * FROM " + name + " LIMIT 5",
        "SELECT a.id FROM main.default.t a JOIN " + name + " b ON a.id = b.id",
        "WITH x AS (SELECT * FROM " + name + ") SELECT * FROM x",
        "DESCRIBE TABLE " + name
    )

    // ---- Decision 1 ----

    test("a format word followed by a backticked path is refused in any letter case") {
        val sqls = for {
            word <- FormatWords
            w <- cases(word) :+ "binaryFile"
            path <- Paths
            sql <- placed(w + "." + path)
        } yield sql
        allRefusedAsPath(sqls.distinct)
    }

    test("a backticked format word is refused too") {
        val sqls = Seq(
            "SELECT * FROM `csv`.`spreadsheets/data.csv`",
            "SELECT * FROM `csv`.`spreadsheets/data.csv` WITH (CREDENTIAL c)",
            "SELECT * FROM `CSV`.`spreadsheets/data.csv` WITH (CREDENTIAL some_credential)",
            "SELECT * FROM `Delta`.`somedir/delta_table`"
        ) ++ (for {
            word <- FormatWords
            w <- cases(word)
            path <- Paths
        } yield "SELECT * FROM `" + w + "`." + path)
        allRefusedAsPath(sqls.distinct)
    }

    test("any qualifier before a backticked absolute location is refused") {
        allRefusedAsPath(
            Seq(
                "SELECT * FROM xyz.`/Volumes/c/s/v/f`",
                "SELECT * FROM `org.example.source`.`s3://b/p`",
                "SELECT * FROM XYZ.`/Volumes/c/s/v/f` WHERE a = 1",
                "SELECT * FROM my_source.`s3://b/p`",
                "SELECT * FROM `my source`.`abfss://c@a.dfs.core.windows.net/p`",
                "SELECT a.id FROM main.default.t a JOIN xyz.`/mnt/p` b ON a.id = b.id",
                "WITH x AS (SELECT * FROM xyz.`gs://b/p`) SELECT * FROM x",
                "DESCRIBE TABLE xyz.`/Volumes/c/s/v/f`"
            )
        )
    }

    test("whitespace of the wide set on either side of the dot does not hide a path") {
        val gaps = Seq(" ", "\t", "\n", "\u00A0", "\u3000", "\u2003", "\u200B", "\u0001") ++
            Seq("\r", "\f", "\u000B", "\u1680", "\u202F", "\u2028", "\uFEFF", " \t\u00A0\u3000")
        val sqls = for {
            g <- gaps
            (before, after) <- Seq((g, ""), ("", g), (g, g))
            name <- Seq(
                "json" + before + "." + after + "`s3://b/p`",
                "DELTA" + before + "." + after + "`somedir/t`",
                "`csv`" + before + "." + after + "`spreadsheets/data.csv`",
                "xyz" + before + "." + after + "`/Volumes/c/s/v/f`"
            )
        } yield "SELECT * FROM " + name
        allRefusedAsPath(sqls)
    }

    test("a doubled backtick inside the path does not end it early") {
        // a doubled backtick is part of the name on the accepting side too
        allAccepted(
            Seq(
                // the qualifier is the name my`csv, not a format word
                "SELECT * FROM `my``csv`.`a/b`",
                // one name, my`.`json, then a relative path under it
                "SELECT * FROM `my``.``json`.`a/b`",
                // one one-part name that only looks like a qualifier, a dot and a path
                "SELECT * FROM `a``.``/etc`",
                "SELECT * FROM json.`a``b`"
            )
        )
        allRefusedAsPath(
            Seq(
                "SELECT * FROM json.`a``b/c`",
                "SELECT * FROM JSON.`a````b/c`",
                "SELECT * FROM xyz.`a``:/b`",
                "SELECT * FROM `cs``v`.`/p`"
            )
        )
    }

    test("a path inside a string literal is refused, as the function list behaves") {
        allRefusedAsPath(
            Seq(
                "SELECT 'json.`s3://b/p`' AS v",
                "SELECT * FROM main.default.t WHERE note = 'see xyz.`/Volumes/c/s/v/f`'",
                "SELECT * FROM main.default.t WHERE note = \"csv.`somedir/t`\""
            )
        )
    }

    test("ordinary names are accepted") {
        allAccepted(
            Seq(
                "SELECT * FROM main.default.t",
                "SELECT * FROM `main`.`default`.`my table`",
                "SELECT * FROM json.`my table`",
                "SELECT * FROM delta.`events`",
                "SELECT * FROM main.json.`a/b`",
                "SELECT * FROM main.`json`.`a/b`",
                "SELECT * FROM main.delta.`x:y`",
                "SELECT * FROM main.default.`a/b:c`",
                "SELECT * FROM default.t",
                "SELECT * FROM t",
                "SELECT t.json, t.text, delta FROM csv_imports t",
                "SELECT a AS `in/out`, b AS `k:v` FROM t",
                "SELECT x.`ratio a/b` FROM x",
                "SELECT * FROM t WHERE u = 'https://example.com/a'",
                // a longer name that only ends or begins with a format word
                "SELECT * FROM myjson.`a/b`",
                "SELECT * FROM json_raw.`a/b`",
                "SELECT * FROM csv2.`a:b`",
                // the same names in other letter cases
                "SELECT * FROM JSON.`my table`",
                "SELECT * FROM MAIN.Json.`A/B`",
                // the docs examples (DatabricksQueryValidationSpec)
                "SELECT symbol, close FROM main.default.stock_price WHERE volume > 1000000",
                "SELECT symbol, close FROM main.default.stock_price WHERE volume > 1000000 LIMIT 10",
                "WITH big AS (SELECT symbol, close FROM main.default.stock_price WHERE volume > 1000000) SELECT * FROM big",
                "SHOW CATALOGS",
                "SHOW SCHEMAS IN main",
                "SHOW TABLES IN main.default",
                "DESCRIBE TABLE main.default.stock_price",
                "SELECT column_name, data_type FROM main.information_schema.columns WHERE table_name = 'stock_price'"
            )
        )
    }

    test("the default preview text for a pipeline is accepted") {
        val dbs = Seq(
            Database(dbName = "main", schema = "default", table = "stock_price", useDatabricks = true),
            Database(dbName = "my-cat", schema = "my schema", table = "a/b", useDatabricks = true),
            Database(dbName = "json", schema = "x-y", table = "t", useDatabricks = true),
            Database(dbName = "main", schema = "json", table = "s3://b/p", useDatabricks = true),
            Database(dbName = "c`at", schema = "delta", table = "/Volumes/x", useDatabricks = true)
        )
        val sqls = dbs.map(db => "SELECT * FROM " + DatabricksConnectionUtil.qualifiedTable(db))
        // the backtick-quoted forms really are quoted
        assert(sqls(1).contains("`my-cat`.`my schema`.`a/b`"), sqls(1))
        assert(sqls(4).contains("`c``at`.delta.`/Volumes/x`"), sqls(4))
        allAccepted(sqls)
    }

    // ---- Decisions 2 and 3 ----

    private def refusedNaming(sql: String, name: String): Option[String] =
        outcomeOf(sql) match {
            case Some(m) if m == "Query contains blocked function: " + name => None
            case other => Some(visible(sql) + " -> " + other.getOrElse("accepted"))
        }

    private val StateCalls = Seq(
        "read_statestore('/checkpoints/q')" -> "read_statestore",
        "read_state_metadata('/checkpoints/q')" -> "read_state_metadata",
        "cloud_files_state('/checkpoints/ingest')" -> "cloud_files_state",
        "cloud_files_state(TABLE(main.default.t))" -> "cloud_files_state",
        "list_secrets()" -> "list_secrets",
        "list_secrets('scope')" -> "list_secrets"
    )

    test(
        "read_statestore, read_state_metadata, cloud_files_state and list_secrets are refused in any letter case with the function named; with a Unicode space before the parenthesis too"
    ) {
        val gaps = Seq(" ", "\u00A0", "\u3000", "\u2003", "\u200B", "\u0001", "\u202F")
        val problems = StateCalls.flatMap {
            case (call, name) =>
                val fn = call.takeWhile(_ != '(')
                val args = call.dropWhile(_ != '(')
                cases(fn).flatMap { f =>
                    Seq(
                        "SELECT * FROM " + f + args,
                        "SELECT " + f + args,
                        "WITH x AS (SELECT * FROM " + f + args + ") SELECT * FROM x",
                        "SELECT * FROM `" + f + "`" + args
                    ) ++ gaps.map(g => "SELECT * FROM " + f + g + args)
                }.flatMap(refusedNaming(_, name))
        }
        if (problems.nonEmpty) fail(problems.size + " not refused with the function named:\n" + problems.mkString("\n"))
        val listed = QuerySafetyReflect.stringList(DatabricksQueryUtil, "BLOCKED_FUNCTIONS")
        Seq("read_statestore", "read_state_metadata", "cloud_files_state", "list_secrets").foreach { n =>
            assert(listed.contains(n), n + " in " + listed)
        }
    }

    test("event_log, table_changes, range and explode are accepted; columns named list_secrets_count and read_statestore_id are accepted") {
        allAccepted(
            Seq(
                "SELECT * FROM event_log(TABLE(main.default.t))",
                "SELECT * FROM event_log('0123-4567')",
                "SELECT * FROM table_changes('main.default.t', 2)",
                "SELECT * FROM range(10)",
                "SELECT explode(array(1, 2))",
                "SELECT list_secrets_count, read_statestore_id FROM t",
                "SELECT cloud_files_state_at, read_state_metadata_v2 FROM main.default.list_secrets_log",
                "SELECT * FROM main.default.read_statestore_runs WHERE list_secrets_count > 0"
            )
        )
    }

    // ---- Decision 4 ----

    test("the refusal text for a path is the Decision 4 sentence and echoes none of the path") {
        val pieces = Seq("s3", "bucket-secret-name", "warehouse/raw", "Volumes", "cat9", "xyz")
        Seq(
            "SELECT * FROM json.`s3://bucket-secret-name/warehouse/raw`",
            "SELECT * FROM xyz.`/Volumes/cat9/s/v/f`"
        ).foreach { sql =>
            val e = intercept[DatrisException](validate(sql))
            assert(e.getMessage == PathRefusal, e.getMessage)
            pieces.foreach(p => assert(!e.getMessage.contains(p), e.getMessage))
        }
    }

    // ---- timing ----

    private val BoundMillis = 5000L
    private val Length = 200000

    private def pathRead(sql: String): Boolean =
        QuerySafetyReflect.call(DatabricksQueryUtil, "pathRead", sql).asInstanceOf[java.lang.Boolean].booleanValue

    /** `unit` repeated after `prefix` to exactly `Length` characters. */
    private def repeated(prefix: String, unit: String): String = {
        val sb = new StringBuilder(prefix)
        while (sb.length < Length) sb.append(unit)
        sb.setLength(Length)
        sb.toString
    }

    /** Runs `pathRead(sql)` on its own daemon thread; fails when it does not
      * return within the bound, and stops the thread then. */
    private def withinBound(label: String, sql: String): Unit = {
        @volatile var result: Either[Throwable, Boolean] = null
        val t = new Thread(
            new Runnable {
                def run(): Unit =
                    result =
                        try Right(pathRead(sql))
                        catch { case e: Throwable => Left(e) }
            },
            "bounded-path-read"
        )
        t.setDaemon(true)
        val started = System.nanoTime()
        t.start()
        t.join(BoundMillis)
        if (t.isAlive) {
            val ms = (System.nanoTime() - started) / 1000000L
            t.setPriority(Thread.MIN_PRIORITY)
            try classOf[Thread].getMethod("stop").invoke(t)
            catch { case _: Throwable => () }
            t.join(2000L)
            fail(label + " (" + sql.length + " characters): did not return within " + (BoundMillis / 1000) + " s (after " + ms + " ms)")
        }
        result match {
            case Left(e) => throw e
            case Right(_) => ()
        }
    }

    test("the path check stays fast on crafted input") {
        // one warm-up on a short text
        withinBound("warm-up", "SELECT * FROM json.`s3://b/p`")
        withinBound("repeated format-word starts with no closing backtick", repeated("SELECT * FROM ", "json.`"))
        withinBound("repeated qualifier and backtick pairs", repeated("SELECT * FROM ", "a.`b"))
        withinBound("one unclosed backticked name", repeated("SELECT * FROM json.`", "x"))
        withinBound("one unclosed backticked name of slashes", repeated("SELECT * FROM xyz.`", "/"))
        withinBound("one unclosed backticked name of doubled backticks", repeated("SELECT * FROM json.`", "``"))
        withinBound("repeated format words and dots with gaps and no backtick", repeated("SELECT * FROM ", "json \u3000.\u00A0 "))
        withinBound("a long dotted name", repeated("SELECT * FROM ", "a."))
    }
}
