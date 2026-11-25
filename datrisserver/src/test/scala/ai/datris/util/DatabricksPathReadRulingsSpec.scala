package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.scalatest.funsuite.AnyFunSuite

/** Points the path-read rule leaves open, pinned beside DatabricksPathReadSpec:
  * where the letter case is compared, a further part after the path, a spaced
  * three-part name, a format word inside a longer name, `xml`, and the quoting
  * forms the scan must not be misled by (a backtick inside a string literal,
  * raw strings, double-quoted names). Pure: no workspace, no network. */
class DatabricksPathReadRulingsSpec extends AnyFunSuite {

    private val PathRefusal = "Queries cannot read from a file path; name a table instead"

    private def pathRead(sql: String): Boolean =
        QuerySafetyReflect.call(DatabricksQueryUtil, "pathRead", sql).asInstanceOf[java.lang.Boolean].booleanValue

    private def outcomeOf(sql: String): Option[String] =
        try { QuerySafetyReflect.call(DatabricksQueryUtil, "validateQuery", sql); None }
        catch { case e: DatrisException => Some(e.getMessage) }

    private def visible(s: String): String =
        s.flatMap(c => if (c >= 0x20 && c < 0x7f) c.toString else "\\u%04X".format(c.toInt))

    private def refused(sqls: String*): Unit = {
        val wrong = sqls.filterNot(s => outcomeOf(s).contains(PathRefusal))
        assert(wrong.isEmpty, wrong.map(s => visible(s) + " -> " + outcomeOf(s).getOrElse("accepted")).mkString("\n"))
    }

    private def accepted(sqls: String*): Unit = {
        val wrong = sqls.filter(s => outcomeOf(s).isDefined)
        assert(wrong.isEmpty, wrong.map(s => visible(s) + " -> " + outcomeOf(s).get).mkString("\n"))
    }

    test("the path check compares the format word in any letter case when called directly") {
        assert(pathRead("SELECT * FROM JSON.`a/b`"))
        assert(pathRead("SELECT * FROM `DeLtA`.`a:b`"))
        assert(pathRead("SELECT * FROM BinaryFile.`dir/f`"))
        assert(!pathRead("SELECT * FROM JSON.`my table`"))
        assert(!pathRead(null))
        // a letter that String.equalsIgnoreCase folds to an ASCII one is that letter
        assert(pathRead("SELECT * FROM j\u017Fon.`a/b`"))
        refused("SELECT * FROM j\u017Fon.`a/b`")
        // a look-alike that does not fold to an ASCII letter makes another name
        accepted("SELECT * FROM \u0441sv.`a/b`")
    }

    test("a further part after the path does not make it a table name") {
        refused(
            "SELECT * FROM json.`a/b`.c",
            "SELECT * FROM delta.`x:y`.t",
            "SELECT * FROM xyz.`/Volumes/c`.t"
        )
    }

    test("a part preceded by a dot is not a first part, whatever gap stands between") {
        accepted(
            "SELECT * FROM main . json . `a/b`",
            "SELECT * FROM main\u3000.\njson\t.\u00A0`a/b`",
            "SELECT * FROM main.\u200Bjson.`/p`",
            "SELECT * FROM `main` .\u2003`delta`. `s3://b/p`"
        )
    }

    test("a format word directly after a letter, digit or underscore is part of a longer name") {
        accepted(
            "SELECT * FROM \u00E9json.`a/b`",
            "SELECT * FROM \u0663json.`a/b`",
            "SELECT * FROM \uD835\uDC00json.`a/b`",
            "SELECT * FROM _csv.`a:b`",
            "SELECT * FROM json\u00E9.`a/b`"
        )
        // the longer name is still a qualifier, so an absolute location under it is refused
        refused(
            "SELECT * FROM \u00E9json.`/p`",
            "SELECT * FROM \uD835\uDC00json.`s3://b/p`",
            "SELECT * FROM _csv.`/Volumes/c`"
        )
    }

    test("xml is a format word") {
        refused("SELECT * FROM xml.`dir/f.xml`", "SELECT * FROM `XML`.`a:b`")
        accepted("SELECT * FROM xml.`my table`")
    }

    test("a backtick inside a string literal does not hide a path after it") {
        refused(
            "SELECT '`' AS a FROM json.`s3://b/p`",
            "SELECT \"`\" AS a FROM json.`a/b`",
            "SELECT 'it\\'s `' AS a FROM json.`a/b`",
            // a raw string ends at its next quote: the backslash escapes nothing
            "SELECT r'\\' AS a, '`' AS b FROM json.`a/b`",
            "SELECT R\"\\\" AS a, '`' AS b FROM csv.`a/b`"
        )
    }

    test("a double-quoted part is read as a quoted name too") {
        refused(
            "SELECT * FROM json.\"s3://b/p\"",
            "SELECT * FROM \"csv\".\"a/b\"",
            "SELECT * FROM xyz.\"/Volumes/c\"",
            // escapes are not decoded, so an escaped part counts as a path
            "SELECT * FROM json.\"\\u002Fp\""
        )
        accepted("SELECT * FROM t WHERE a = \"x/y\"", "SELECT * FROM t WHERE a = \"json\" AND b = \"a/b\"")
    }

    test("a format word spelled with letters that fold to ASCII ones, as Spark compares data source names, is refused") {
        refused(
            "SELECT * FROM `j\u017Fon`.`dir/f`",
            "SELECT * FROM `c\u017Fv`.`dir/f.csv`",
            "SELECT * FROM `b\u0131naryfile`.`dir/f`",
            "SELECT * FROM `b\u0130naryfile`.`dir/f`",
            "SELECT * FROM b\u0130naryfile.`dir/f`",
            "SELECT * FROM `PARQUET`.`a:b`"
        )
        accepted("SELECT * FROM `j\u017Fon`.`my table`", "SELECT * FROM `\u0441sv`.`dir/f`")
    }

    test("table_changes_by_path is refused as a call in any letter case; table_changes still reads") {
        Seq(
            "SELECT * FROM table_changes_by_path('s3://b/p', 0)",
            "SELECT * FROM TABLE_CHANGES_BY_PATH('/p', 0)",
            "SELECT * FROM Table_Changes_By_Path\u00A0('/p', 0)",
            "SELECT * FROM `table_changes_by_path`('/p', 0)"
        ).foreach { s =>
            assert(outcomeOf(s).contains("Query contains blocked function: table_changes_by_path"), visible(s) + " -> " + outcomeOf(s))
        }
        assert(QuerySafetyReflect.stringList(DatabricksQueryUtil, "BLOCKED_FUNCTIONS").contains("table_changes_by_path"))
        accepted("SELECT * FROM table_changes('main.default.t', 2)", "SELECT table_changes_by_path_count FROM t")
    }

    test("the path check stays fast on quote-heavy input") {
        val n = 200000
        def rep(unit: String): String = {
            val sb = new StringBuilder("SELECT * FROM ")
            while (sb.length < n) sb.append(unit)
            sb.toString
        }
        Seq(rep("'"), rep("\""), rep("r\""), rep("r'"), rep("\"\\"), rep("json.\""), rep("\u00E9"), rep("\uD835\uDC00.`"))
            .foreach { s =>
                val started = System.nanoTime()
                pathRead(s)
                val ms = (System.nanoTime() - started) / 1000000L
                assert(ms < 5000L, ms + " ms on " + visible(s.take(40)))
            }
    }
}
