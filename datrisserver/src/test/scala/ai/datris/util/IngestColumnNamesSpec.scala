package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets

/** Story: Column names from ingested data: checked once at ingest, quoted at
  * every destination, never able to change what a load does
  * (plans/stories/ingest-column-name-escaping.md), Decisions 2-4.
  *
  * Pinned seam (pure, no I/O):
  * {{{
  * object IngestColumnNames {
  *     type Limits                                     // opaque here: built by limitsFor only
  *     def limitsFor(config: PipelineConfig): Limits   // engine flags of destination.database + the other destinations
  *     def problem(newNames: List[String], storedNames: List[String], limits: Limits): Option[String]
  *     def display(name: String): String               // control characters as backslash-u plus 4 hex digits, cut at 80 characters
  * }
  * }}}
  *
  * `problem` looks at NEW names only (the cells not already in the stored
  * schema). It refuses NUL / control characters, a new name equal to a stored
  * or another new name after trim and case folding, a name longer than the
  * destination allows (Postgres 63 UTF-8 bytes, SQL Server 128 characters,
  * Snowflake and Databricks 255), and on Postgres a new name sharing its first
  * 63 bytes with another column. Everything else passes, whatever it holds.
  * The message names the cell (escaped) and its 1-based position among the
  * new names. */
class IngestColumnNamesSpec extends AnyFunSuite {

    private def fields(names: String*): java.util.List[SchemaField] =
        new java.util.ArrayList[SchemaField](java.util.Arrays.asList(names.map(n => SchemaField(n, "string")): _*))

    private def withDestination(d: Destination): PipelineConfig =
        PipelineConfig(
            name = "col_pg",
            source =
                Source(schemaProperties = SchemaProperties("db", fields("id", "amount")), fileAttributes = FileAttributes(csvAttributes = CsvAttributes())),
            destination = d
        )

    private def database(db: Database): PipelineConfig =
        withDestination(Destination(schemaProperties = SchemaProperties("db", fields("id", "amount")), database = db))

    private val postgres = database(Database(dbName = "datris", schema = "public", table = "col_pg", usePostgres = true))
    private val sqlServer = database(Database(dbName = "datris", schema = "dbo", table = "col_ms", useSqlServer = true, credentialsSecret = "ms"))
    private val snowflake = database(Database(dbName = "DATRIS", schema = "PUBLIC", table = "COL_SF", useSnowflake = true, credentialsSecret = "sf"))
    private val databricks = database(Database(dbName = "main", schema = "default", table = "col_db", useDatabricks = true, credentialsSecret = "db"))
    private val mongo = database(Database(dbName = "datris", table = "col_mongo", useMongoDB = true))
    private val objectStore =
        withDestination(Destination(schemaProperties = SchemaProperties("db", fields("id", "amount")), objectStore = ObjectStore(prefixKey = "col")))
    private val scratch = withDestination(Destination(scratch = ScratchConfig()))
    private val kafka = withDestination(Destination(kafka = Kafka("topic", "id", null)))
    private val activeMQ = withDestination(Destination(activeMQ = ActiveMQ("queue")))
    private val rest = withDestination(Destination(restEndpoint = RestEndpoint("http://localhost:9/sink")))

    private val allEngines = Seq(
        "postgres" -> postgres,
        "sqlserver" -> sqlServer,
        "snowflake" -> snowflake,
        "databricks" -> databricks,
        "mongodb" -> mongo,
        "objectstore" -> objectStore,
        "scratch" -> scratch,
        "kafka" -> kafka,
        "activemq" -> activeMQ,
        "rest" -> rest
    )

    private def problem(newNames: List[String], stored: List[String], cfg: PipelineConfig): Option[String] =
        IngestColumnNames.problem(newNames, stored, IngestColumnNames.limitsFor(cfg))

    private def bytes(s: String): Int = s.getBytes(StandardCharsets.UTF_8).length

    /** The digit `n` appears in `msg` as a number on its own. */
    private def mentionsPosition(msg: String, n: Int): Boolean = ("(?<![0-9])" + n + "(?![0-9])").r.findFirstIn(msg).isDefined

    // ---------------------------------------------------------------- passes

    test("plain, spaced, hyphenated, dotted, quoted, semicolon and non-ASCII new names have no problem") {
        val names = List("note", "unit price", "cafe-total", "a.b", "say \"hi\"", "x;y", "café-total", "名前", "Mixed_Case_9", "$amount", "[x]", "`y`")
        for ((label, cfg) <- allEngines) {
            assert(problem(names, List("id", "amount"), cfg) == None, label)
            names.foreach(n => assert(problem(List(n), List("id", "amount"), cfg) == None, label + ": " + n))
        }
    }

    // --------------------------------------------------- control characters

    test("a NUL, tab, newline, DEL or C1 control character is refused and the message shows it escaped with the cell position") {
        val cases = Seq(
            "a\u0000b" -> "\\u0000",
            "a\tb" -> "\\u0009",
            "a\nb" -> "\\u000a",
            "a\rb" -> "\\u000d",
            "a\u007fb" -> "\\u007f",
            "a\u0085b" -> "\\u0085",
            "a\u009bb" -> "\\u009b"
        )
        for ((label, cfg) <- allEngines; (bad, escaped) <- cases) {
            val second = problem(List("note", bad), List("id"), cfg)
            assert(second.isDefined, label + ": " + escaped + " not refused")
            val msg = second.get
            assert(msg.toLowerCase.contains("a" + escaped + "b"), label + ": the cell is shown escaped as a" + escaped + "b: " + msg)
            assert(!msg.contains(bad), label + ": the raw control character is in the message: " + msg)
            assert(mentionsPosition(msg, 2), label + ": the message names position 2: " + msg)

            val first = problem(List(bad, "note"), List("id"), cfg)
            assert(first.isDefined && mentionsPosition(first.get, 1), label + ": the message names position 1: " + first)
        }
    }

    test("display escapes control characters as \\uXXXX and cuts at 80 characters") {
        assert(IngestColumnNames.display("plain name") == "plain name")
        assert(IngestColumnNames.display("a\tb").toLowerCase == "a\\u0009b")
        val long = IngestColumnNames.display("x" * 500)
        assert(long.startsWith("x" * 80), long)
        assert(!long.startsWith("x" * 81), "cut at 80 characters: " + long.length)
    }

    // ------------------------------------------------------------ collisions

    test("a new name equal to a stored name after trim and case folding is refused") {
        for ((label, cfg) <- allEngines; n <- Seq(" amount", "amount ", " AMOUNT ", "Amount ")) {
            val p = problem(List(n), List("id", "amount"), cfg)
            assert(p.isDefined, label + ": '" + n + "' collides with stored 'amount'")
            assert(p.get.contains("amount"), label + ": " + p.get)
        }
    }

    test("two equal new names are refused") {
        for ((label, cfg) <- allEngines; pair <- Seq(List("note", "note"), List("note", " NOTE"), List("Note ", "note"))) {
            val p = problem("extra" :: pair, List("id"), cfg)
            assert(p.isDefined, label + ": " + pair + " not refused")
        }
        assert(problem(List("note", "notes"), List("id"), postgres) == None)
    }

    // --------------------------------------------------------- length limits

    test("63 bytes pass and 64 bytes are refused for Postgres, counted in UTF-8 bytes") {
        val ascii63 = "a" * 63
        val ascii64 = "a" * 64
        val utf63 = "é" * 31 + "a" // 32 characters, 63 bytes
        val utf64 = "é" * 32 // 32 characters, 64 bytes
        assert(bytes(utf63) == 63 && bytes(utf64) == 64)
        assert(problem(List(ascii63), List("id"), postgres) == None)
        assert(problem(List(utf63), List("id"), postgres) == None)
        assert(problem(List(ascii64), List("id"), postgres).isDefined)
        assert(problem(List(utf64), List("id"), postgres).isDefined, "64 UTF-8 bytes in 32 characters is too long for Postgres")
    }

    test("two names sharing their first 63 bytes are refused for Postgres only") {
        val p = "c" * 63
        // Postgres keeps the first 63 bytes of the stored 70-byte name, which is
        // exactly the new 63-byte name.
        val stored = List("id", p + "_stored")
        assert(problem(List(p), stored, postgres).isDefined, "shares its first 63 bytes with a stored column")
        for ((label, cfg) <- allEngines.filterNot(_._1 == "postgres"))
            assert(problem(List(p), stored, cfg) == None, label)
    }

    test("128 / 129 characters for SQL Server and 255 / 256 for Snowflake and Databricks") {
        assert(problem(List("m" * 128), List("id"), sqlServer) == None)
        assert(problem(List("m" * 129), List("id"), sqlServer).isDefined)
        assert(problem(List("é" * 128), List("id"), sqlServer) == None, "SQL Server counts characters, not bytes")
        for (cfg <- Seq(snowflake, databricks)) {
            assert(problem(List("s" * 255), List("id"), cfg) == None)
            assert(problem(List("s" * 256), List("id"), cfg).isDefined)
            assert(problem(List("é" * 255), List("id"), cfg) == None, "characters, not bytes")
        }
    }

    test("object store, MongoDB, scratch, Kafka, ActiveMQ and REST destinations have no length limit") {
        val long = "z" * 1000
        for (
            (label, cfg) <-
                Seq("objectstore" -> objectStore, "mongodb" -> mongo, "scratch" -> scratch, "kafka" -> kafka, "activemq" -> activeMQ, "rest" -> rest)
        )
            assert(problem(List(long), List("id"), cfg) == None, label)
    }

    // ---------------------------------------------------------- stored names

    test("stored names are never examined") {
        val storedOdd = List("id", "bad\u0000name", "t\tab", "a" * 300, "dup", "DUP", " dup ", "c" * 70, "c" * 71)
        for ((label, cfg) <- allEngines)
            assert(problem(List("note"), storedOdd, cfg) == None, label)
        // no new names: nothing to refuse
        for ((label, cfg) <- allEngines)
            assert(problem(Nil, storedOdd, cfg) == None, label)
    }

    // ------------------------------------------- ORC and backticks (e2e follow-up)

    test("a new name holding a backtick is refused for an ORC object store only; dots and spaces pass everywhere") {
        val orc = withDestination(Destination(
            schemaProperties = SchemaProperties("db", fields("id", "amount")),
            objectStore = ObjectStore(prefixKey = "col", fileFormat = "orc")
        ))
        val p = problem(List("note", "x`y"), List("id"), orc)
        assert(p.isDefined && p.get.contains("x`y") && mentionsPosition(p.get, 2), p.toString)
        for ((label, cfg) <- allEngines)
            assert(problem(List("x`y", "a.b", "a. b c"), List("id"), cfg) == None, label)
        assert(problem(List("a.b", "a. b c"), List("id"), orc) == None)
    }
}
