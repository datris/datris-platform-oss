package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.util.UUID

/** Story: Query validation: checking a query's text takes time in proportion
  * to its length, and very long text is refused
  * (plans/stories/query-validation-bounded-time.md), Acceptance. Pure: no
  * store, no network.
  *
  * Timing cases follow the story's shape: many match starts (40,000, every
  * text under the length limit), one warm-up call on a short text, then one
  * wall-clock bound of 10 seconds. Each call runs on its own daemon thread; a
  * call still running at the bound fails the test there and the thread is
  * stopped, so a slow validator neither runs to the end nor keeps the JVM busy
  * for later tests.
  *
  * The length limit (Decisions 1-3): 1,000,000 characters (`String.length` of
  * the text as received), checked first in each of the four `validateQuery`
  * methods by a new `QueryTextLimit` object, refused as
  * `Query text is too long: <n> characters (the limit is 1000000)`. Members
  * the story adds are reached by name (QuerySafetyReflect) so this spec
  * compiles before they exist. */
class QueryValidationBoundedTimeSpec extends AnyFunSuite {

    private val Limit = 1000000
    private val BoundMillis = 10000L
    private val Starts = 40000

    private val Validators: Seq[(String, String => Unit)] = Seq(
        "PostgreSQL" -> ((s: String) => PostgresQueryUtil.validateQuery(s)),
        "Snowflake" -> ((s: String) => { QuerySafetyReflect.call(SnowflakeQueryUtil, "validateQuery", s); () }),
        "SQL Server" -> ((s: String) => SqlServerQueryUtil.validateQuery(s)),
        "Databricks" -> ((s: String) => { QuerySafetyReflect.call(DatabricksQueryUtil, "validateQuery", s); () })
    )

    private def validator(name: String): String => Unit = Validators.find(_._1 == name).get._2

    /** None when `body` accepts, Some(message) when it refuses with a
      * DatrisException. Fails when it does not return within the bound. */
    private def outcome(label: String)(body: => Unit): Option[String] = {
        @volatile var result: Either[Throwable, Option[String]] = null
        val t = new Thread(
            new Runnable {
                def run(): Unit =
                    result =
                        try { body; Right(None) }
                        catch {
                            case e: DatrisException => Right(Some(e.getMessage))
                            case e: Throwable => Left(e)
                        }
            },
            "bounded-validation"
        )
        t.setDaemon(true)
        val started = System.nanoTime()
        t.start()
        t.join(BoundMillis)
        if (t.isAlive) {
            val ms = (System.nanoTime() - started) / 1000000L
            stop(t)
            val state = if (t.isAlive) "still running at minimum priority" else "stopped"
            fail(label + ": did not return within " + (BoundMillis / 1000) + " s (" + state + " after " + ms + " ms)")
        }
        result match {
            case Left(e) => throw e
            case Right(r) => r
        }
    }

    /** Stops a validation still running at the bound. `Thread.stop` works on
      * Java 17 (the project's JDK); where it is gone the thread is a daemon
      * left at minimum priority. */
    private def stop(t: Thread): Unit = {
        t.setPriority(Thread.MIN_PRIORITY)
        try classOf[Thread].getMethod("stop").invoke(t)
        catch { case _: Throwable => () }
        t.join(2000L)
    }

    /** Warm up `name` on a short text, then validate `sql` within the bound. */
    private def bounded(name: String, label: String, sql: String): Option[String] = {
        assert(sql.length < Limit, label + ": the text must be under the length limit, is " + sql.length)
        val v = validator(name)
        outcome(name + " warm-up")(v("SELECT a FROM t WHERE b = 1"))
        outcome(name + ": " + label + " (" + sql.length + " characters)")(v(sql))
    }

    private def accepted(name: String, label: String, sql: String): Unit = {
        val r = bounded(name, label, sql)
        assert(r.isEmpty, name + ": " + label + " -> " + r)
    }

    private def refusedFunction(name: String, label: String, sql: String): Unit = {
        val r = bounded(name, label, sql)
        assert(r.exists(_.startsWith("Query contains blocked function: ")), name + ": " + label + " -> " + r)
    }

    private def tooLong(n: Int): String = "Query text is too long: " + n + " characters (the limit is 1000000)"

    /** `SELECT a FROM t WHERE a IN (1, 2, ...)` of exactly `length` characters. */
    private def inList(length: Int): String = {
        val sb = new StringBuilder("SELECT a FROM t WHERE a IN (")
        var i = 1
        while (sb.length < length - 20) { sb.append(i).append(", "); i += 1 }
        sb.append(i)
        while (sb.length < length - 1) sb.append(' ')
        sb.append(')')
        val s = sb.toString
        assert(s.length == length)
        s
    }

    /** `prefix` padded with spaces to exactly `length` characters, then `suffix`. */
    private def padded(prefix: String, length: Int, suffix: String = ""): String = {
        val s = prefix + (" " * (length - prefix.length - suffix.length)) + suffix
        assert(s.length == length)
        s
    }

    private val NonAsciiLetter = "é"

    // ---- timing ----

    test("Databricks: many starts of a listed name inside one long name validate within the bound") {
        accepted("Databricks", "one long name made of repeated listed-name starts", "SELECT " + (("ai_" + NonAsciiLetter) * Starts))
    }

    test("Snowflake: many IDENTIFIER words on one comment line, and one per line over many comment lines, validate within the bound") {
        accepted("Snowflake", "IDENTIFIER words on one comment line", "SELECT " + ("identifier//" * Starts))
        accepted("Snowflake", "IDENTIFIER then one comment line per IDENTIFIER", "SELECT identifier" + ("\n//identifier" * Starts))
    }

    test("PostgreSQL: a long name built from a repeated listed fragment validates within the bound") {
        // A non-ASCII letter directly after a listed name is refused (Backward compat).
        refusedFunction("PostgreSQL", "a long name of repeated fragments", "SELECT pg_create_" + ("replication_slot" * Starts) + NonAsciiLetter)
    }

    test("SQL Server: many blocked keywords inside longer names, deep parentheses and a long word list validate within the bound") {
        accepted("SQL Server", "names ending in a blocked keyword", "SELECT " + Seq.fill(Starts)("xinsert").mkString(", ") + " FROM t")
        accepted("SQL Server", "one name of repeated blocked keywords", "SELECT " + ("xinto" * Starts) + " FROM t")
        accepted("SQL Server", "deep parentheses", "SELECT " + ("(" * Starts) + "1" + (")" * Starts))
        accepted("SQL Server", "a long word list", "SELECT " + (1 to Starts).map("c" + _).mkString(", ") + " FROM t")
    }

    test("controls with many starts stay within the bound on all four") {
        // Snowflake
        bounded("Snowflake", "SYSTEM$ names chained with non-ASCII letters", "SELECT " + (("SYSTEM$" + NonAsciiLetter) * Starts))
        bounded("Snowflake", "SYSTEM$TYPEOF repeats", "SELECT " + Seq.fill(Starts)("SYSTEM$TYPEOF(a)").mkString(", ") + " FROM t")
        bounded("Snowflake", "identifier repeats", "SELECT " + ("identifier " * Starts) + "FROM t")
        bounded("Snowflake", "quoted identifier repeats", "SELECT " + ("\"identifier\" " * Starts) + "FROM t")
        bounded("Snowflake", "NEXTVAL repeats", "SELECT " + Seq.fill(Starts)("s.NEXTVAL").mkString(", "))
        // Databricks
        bounded("Databricks", "secret repeats", "SELECT " + ("secret " * Starts) + "FROM t")
        bounded("Databricks", "backticked ai_ name repeats", "SELECT " + ("`ai_x` " * Starts) + "FROM t")
        bounded("Databricks", "one very long ai_ name", "SELECT ai_" + ("x" * 400000) + " FROM t")
        // PostgreSQL
        Seq("pg_backup_", "pg_logical_", "dblink", "query_to_xml").foreach { f =>
            bounded("PostgreSQL", f + " repeats", "SELECT " + (f * Starts) + " FROM t")
            bounded("PostgreSQL", f + " repeats joined by a non-ASCII letter", "SELECT " + ((f + NonAsciiLetter) * Starts) + " FROM t")
        }
        bounded("PostgreSQL", "u& repeats", "SELECT " + ("u&" * Starts) + " FROM t")
        // a plain IN list of close to 1,000,000 characters on all four
        val list = inList(Limit - 10000)
        Validators.foreach { case (name, _) => accepted(name, "a plain IN list near the limit", list) }
        // SQL Server shapes near 1,000,000 characters
        accepted("SQL Server", "nested parentheses", "SELECT " + ("(" * 495000) + "1" + (")" * 495000))
        bounded("SQL Server", "many words", "SELECT " + ("a " * 490000) + "FROM t")
        accepted("SQL Server", "many UNION SELECTs", "SELECT 1 " + ("UNION SELECT 1 " * 60000))
        accepted("SQL Server", "many bracketed names", "SELECT " + ("[a], " * 190000) + "[a] FROM t")
        val limited = outcome("SQL Server applyLimit on a long IN list")(SqlServerQueryUtil.applyLimit(list, 100))
        assert(limited.isEmpty, limited)
    }

    // ---- boundaries ----

    // Supplementary-plane format characters (Cf): U+E0001 LANGUAGE TAG, U+1D173
    // MUSICAL SYMBOL BEGIN BEAM, U+E0020 TAG SPACE, written as surrogate pairs.
    private val NonBmpFormat = Seq("󠀁", "𝅳", "󠀠")

    test("Snowflake: a format character outside the Basic Multilingual Plane before the parenthesis does not hide an IDENTIFIER call") {
        val v = validator("Snowflake")
        NonBmpFormat.foreach { f =>
            Seq(
                "SELECT IDENTIFIER" + f + "('t')",
                "SELECT identifier " + f + " ('t')",
                "SELECT IDENTIFIER" + f + "//x\n" + f + "('t')",
                "SELECT * FROM \"IDENTIFIER\"" + f + "('db.s.t')"
            ).foreach { sql =>
                val e = intercept[DatrisException](v(sql))
                assert(e.getMessage == "Query contains blocked function: IDENTIFIER", sql + " -> " + e.getMessage)
            }
        }
        // the gap is read by code point: the gap after IDENTIFIER ends at the parenthesis
        val text = "SELECT IDENTIFIER" + NonBmpFormat.head + "('t')"
        val m = QuerySafetyReflect.methods(SnowflakeQueryUtil, "gapEnds").find(_.getParameterTypes.toSeq == Seq(classOf[String])).getOrElse(
            throw new AssertionError("ai.datris.util.SnowflakeQueryUtil has no member gapEnds(String) (added by the story)")
        )
        m.setAccessible(true)
        val ends: Int => Int = QuerySafetyReflect.invoke(SnowflakeQueryUtil, m, text) match {
            case a: Array[Int] => a(_)
            case s: scala.collection.Seq[_] => i => s(i).asInstanceOf[Int]
            case l: java.util.List[_] => i => l.get(i).asInstanceOf[Number].intValue
            case other => throw new AssertionError("gapEnds returned " + other)
        }
        val after = "SELECT IDENTIFIER".length
        assert(ends(after) == after + 2, "gap starting after IDENTIFIER ends at " + ends(after))
        assert(text.charAt(ends(after)) == '(')
    }

    test("Databricks: a listed name directly after a non-ASCII letter is accepted; the same name in backticks after that letter is refused") {
        val v = validator("Databricks")
        Seq(NonAsciiLetter, "ß", "ж", "١").foreach { before =>
            Seq("secret('scope', 'key')", "http_request(conn => 'c')", "ai_query('e', 'p')", "identifier('t')").foreach { call =>
                val plain = "SELECT " + before + call
                withClue(plain + " -> ")(v(plain))
                val ticked = "SELECT " + before + "`" + call.takeWhile(_ != '(') + "`" + call.dropWhile(_ != '(')
                val e = intercept[DatrisException](v(ticked))
                assert(e.getMessage.startsWith("Query contains blocked function: "), ticked + " -> " + e.getMessage)
            }
        }
        // an ASCII neighbour still makes it part of a longer name, a space still a boundary
        v("SELECT xsecret('scope', 'key')")
        intercept[DatrisException](v("SELECT secret('scope', 'key')"))
    }

    test("PostgreSQL: a listed function name with a non-ASCII letter directly after it is refused") {
        Seq(
            "SELECT pg_terminate_backend" + NonAsciiLetter + "(1)",
            "SELECT pg_read_file" + NonAsciiLetter + "('x')",
            "SELECT * FROM dblink" + "ж" + "('c', 'q')",
            "SELECT pg_create_logical_replication_slot" + NonAsciiLetter + "('s', 'p')",
            "SELECT lo_export" + "ß" + "(1, '/tmp/x')"
        ).foreach { sql =>
            val e = intercept[DatrisException](PostgresQueryUtil.validateQuery(sql))
            assert(e.getMessage.startsWith("Query contains blocked function: "), sql + " -> " + e.getMessage)
        }
        // still accepted: a longer ASCII name, a listed name only as a prefix of a column
        PostgresQueryUtil.validateQuery("SELECT pg_read_file_count, lo_exported FROM t")
    }

    // ---- length limit ----

    test("query text over the limit is refused by all four validators before any other check, with its length and the limit in the message") {
        val overValid = inList(Limit + 1)
        val overWrite = padded("DELETE FROM t WHERE a = 1", Limit + 1)
        val overComment = padded("SELECT a FROM t -- x", Limit + 1)
        // counted on the text as received, before trimming
        val overSpaces = padded("SELECT 1", Limit + 1)
        val overLeading = padded("", Limit + 1, "SELECT 1")
        val farOver = inList(2 * Limit)
        Validators.foreach {
            case (name, v) =>
                Seq(overValid, overWrite, overComment, overSpaces, overLeading).foreach { sql =>
                    val r = outcome(name + ": over the limit")(v(sql))
                    assert(r.contains(tooLong(Limit + 1)), name + ": " + sql.take(40) + "... -> " + r)
                }
                val r = outcome(name + ": far over the limit")(v(farOver))
                assert(r.contains(tooLong(2 * Limit)), name + " -> " + r)
        }
    }

    test("query text exactly at the limit is checked as usual, and a 10,000-value IN list is accepted by all four") {
        val limitObject = QuerySafetyReflect.scalaObject("ai.datris.util.QueryTextLimit")
        val atLimit = inList(Limit)
        QuerySafetyReflect.call(limitObject, "check", atLimit): Unit
        val r = outcome("QueryTextLimit.check over the limit") { QuerySafetyReflect.call(limitObject, "check", atLimit + " "); () }
        assert(r.contains(tooLong(Limit + 1)), r)

        val writeAtLimit = padded("DELETE FROM t WHERE a = 1", Limit)
        val commentAtLimit = padded("SELECT a FROM t WHERE b = 1 -- x", Limit)
        val uuids = (1 to 10000).map(i => "'" + UUID.nameUUIDFromBytes(("v" + i).getBytes(StandardCharsets.UTF_8)) + "'").mkString(", ")
        val uuidList = "SELECT a FROM t WHERE id IN (" + uuids + ")"
        Validators.foreach {
            case (name, v) =>
                assert(outcome(name + ": at the limit")(v(atLimit)).isEmpty, name)
                val w = outcome(name + ": a write at the limit")(v(writeAtLimit))
                assert(w.exists(m => m.startsWith("Only ") && m.contains("allowed")), name + " -> " + w)
                val c = outcome(name + ": a comment at the limit")(v(commentAtLimit))
                assert(c.contains("SQL comments are not allowed in queries"), name + " -> " + c)
                assert(outcome(name + ": 10,000-value IN list")(v(uuidList)).isEmpty, name)
        }
    }

    // ---- review round 1 ----

    // Letters whose upper- or lower-case form is longer than themselves:
    // sharp s, n preceded by apostrophe, iota with dialytika and tonos (upper),
    // capital I with dot above (lower).
    private val Expanding = Seq("ß", "ŉ", "ΐ", "İ")
    private val ExpandingRun = 450000

    test("a long run of letters whose case form is longer than themselves validates within the bound on all four, and applyLimit too") {
        Expanding.foreach { ch =>
            val label = "a run of U+" + "%04X".format(ch.codePointAt(0))
            Validators.foreach { case (name, _) => accepted(name, label, "SELECT " + (ch * ExpandingRun)) }
            accepted("SQL Server", label + " as one name", "SELECT " + (ch * ExpandingRun) + " FROM t")
            val limited = outcome("SQL Server applyLimit on " + label)(SqlServerQueryUtil.applyLimit("SELECT " + (ch * ExpandingRun) + " FROM t", 100))
            assert(limited.isEmpty, limited)
        }
        val big = "SELECT " + (Expanding.mkString * 240000)
        assert(outcome("QueryTextCase.upper on about a million expanding letters") { QueryTextCase.upper(big); () }.isEmpty)
        assert(outcome("QueryTextCase.lower on about a million expanding letters") { QueryTextCase.lower(big); () }.isEmpty)
    }

    test("query text case conversion gives the JDK's result; lower-casing differs only for a final capital sigma") {
        val special = Seq(0xdf, 0x149, 0x390, 0x3b0, 0x130, 0x131, 0x17f, 0x212a, 0x1f80, 0xfb00, 0xfb03, 0x587, 0x1e96, 0x3a3)
        val rnd = new scala.util.Random(20261124L)
        (1 to 20000).foreach { _ =>
            val sb = new java.lang.StringBuilder
            (1 to rnd.nextInt(12)).foreach { _ =>
                val cp = rnd.nextInt(8) match {
                    case 0 | 1 => 32 + rnd.nextInt(95)
                    case 2 => special(rnd.nextInt(special.size))
                    case 3 | 4 => 0x80 + rnd.nextInt(0x600)
                    case 5 | 6 => rnd.nextInt(0x10000)
                    case _ => 0x10000 + rnd.nextInt(0x100000)
                }
                sb.appendCodePoint(cp)
            }
            val s = sb.toString
            assert(QueryTextCase.upper(s) == s.toUpperCase(java.util.Locale.ROOT), s)
            val sigma = (x: String) => x.replace('ς', 'σ')
            assert(sigma(QueryTextCase.lower(s)) == sigma(s.toLowerCase(java.util.Locale.ROOT)), s)
            assert(QueryTextCase.upperPrefix(s, 3) == QueryTextCase.upper(s.substring(0, s.offsetByCodePoints(0, math.min(3, s.codePointCount(0, s.length))))))
        }
    }

    // U+1D400 MATHEMATICAL BOLD CAPITAL A (a letter) and U+1D7CE MATHEMATICAL
    // BOLD DIGIT ZERO (a number), outside the Basic Multilingual Plane, and
    // U+1F600 (an emoji, neither).
    private val NonBmpLetter = "𝐀"
    private val NonBmpDigit = "𝟎"
    private val NonBmpOther = "😀"

    test("Databricks: a listed name directly after a letter or number outside the Basic Multilingual Plane is accepted; in backticks it is refused") {
        val v = validator("Databricks")
        Seq(NonBmpLetter, NonBmpDigit).foreach { before =>
            Seq("secret('scope', 'key')", "ai_query('e', 'p')", "identifier('t')").foreach { call =>
                val plain = "SELECT " + before + call
                withClue(plain + " -> ")(v(plain))
                val ticked = "SELECT " + before + "`" + call.takeWhile(_ != '(') + "`" + call.dropWhile(_ != '(')
                val e = intercept[DatrisException](v(ticked))
                assert(e.getMessage.startsWith("Query contains blocked function: "), ticked + " -> " + e.getMessage)
            }
        }
        // a character outside the plane that is neither still counts as a boundary
        val e = intercept[DatrisException](v("SELECT " + NonBmpOther + "secret('scope', 'key')"))
        assert(e.getMessage == "Query contains blocked function: secret", e.getMessage)
    }

    test("Databricks: many starts of a listed name joined by letters outside the Basic Multilingual Plane validate within the bound") {
        accepted("Databricks", "listed-name starts joined by a letter outside the plane", "SELECT " + (("ai_" + NonBmpLetter) * Starts))
        accepted("Databricks", "listed-name starts joined by a number outside the plane", "SELECT " + (("ai_" + NonBmpDigit) * Starts))
    }

    test("the length limit counts the text as received on all four query routes, surrounding whitespace included") {
        // Over the limit only with its surrounding spaces; refused before the pipeline is read.
        val sql = (" " * 10) + inList(Limit - 20) + (" " * 11)
        assert(sql.length == Limit + 1)
        val routes: Seq[(String, () => Unit)] = Seq(
            "PostgreSQL" -> (() => { PostgresQueryUtil.query(sql); () }),
            "Snowflake" -> (() => { SnowflakeQueryUtil.query("p", Some(sql)); () }),
            "SQL Server" -> (() => { SqlServerQueryUtil.query("p", Some(sql)); () }),
            "Databricks" -> (() => { DatabricksQueryUtil.query("p", Some(sql)); () })
        )
        routes.foreach {
            case (name, run) =>
                val r = outcome(name + ": over the limit with surrounding spaces")(run())
                assert(r.contains(tooLong(Limit + 1)), name + " -> " + r)
        }
    }

    // ---- review round 2 ----

    // U+0301 COMBINING ACUTE ACCENT.
    private val CombiningMark = "́"

    test("a long run of combining marks validates within the bound on all four") {
        val afterLetter = "SELECT a" + (CombiningMark * ExpandingRun)
        val thenWords = "SELECT a" + (CombiningMark * ExpandingRun) + (" b" * (ExpandingRun / 4)) + " FROM t"
        Validators.foreach {
            case (name, _) =>
                accepted(name, "a run of combining marks after a letter", afterLetter)
                accepted(name, "half the text combining marks, then short words", thenWords)
        }
    }

    test("every Databricks listed function starts with a lower-case ASCII letter") {
        val entries = DatabricksQueryUtil.BLOCKED_FUNCTIONS
        assert(entries.forall(fn => fn.nonEmpty && fn.charAt(0) >= 'a' && fn.charAt(0) <= 'z'), entries)
    }
}
