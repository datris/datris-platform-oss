package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

/** Story: Column types: one exact rule for the type a schema field may carry,
  * checked on every save and again before any destination statement
  * (plans/stories/column-type-text-escaping.md), Decisions 1, 2, 3 and 5.
  *
  * {{{
  * object ColumnTypeText {
  *     def safe(t: String): Boolean
  *     def problem(field: SchemaField, schema: String): Option[String]
  *     def changedProblem(candidate: PipelineConfig, stored: PipelineConfig): Option[String]
  *     def loadProblem(fields: Seq[SchemaField], existingColumns: Set[String]): Option[String]
  * }
  * }}}
  *
  * `safe`: the whole text, case-insensitive, is one of `boolean, int,
  * tinyint, smallint, bigint, float, double, string, date, timestamp`, or
  * `decimal(P)` / `decimal(P,S)` (1-4 digits each), `varchar(N)` / `char(N)`
  * (1-9 digits) or `varchar(max)`; spaces only next to the parentheses and the
  * comma. Plus `integer` (Decision 6, load only). Null is not safe. Pure. */
class ColumnTypeTextSpec extends AnyFunSuite {

    private val evil = "varchar(10)); DROP TABLE public.canary; --"

    private def fields(fs: SchemaField*): SchemaProperties = SchemaProperties(null, java.util.Arrays.asList(fs: _*))

    /** A pipeline with the given source and destination schema fields (null = no schema). */
    private def cfg(src: Seq[SchemaField], dest: Seq[SchemaField]): PipelineConfig =
        PipelineConfig(
            name = "ty",
            catalog = "Sales",
            source = Source(schemaProperties = if (src == null) null else fields(src: _*), fileAttributes = FileAttributes(csvAttributes = CsvAttributes())),
            destination = Destination(
                schemaProperties = if (dest == null) null else fields(dest: _*),
                database = Database(dbName = "datris", schema = "public", table = "ty", usePostgres = true)
            )
        )

    private val plain = Seq("boolean", "int", "tinyint", "smallint", "bigint", "float", "double", "string", "date", "timestamp")

    // -------------------------------------------------------------------- safe

    test("each plain type name is safe in any letter case") {
        for (t <- plain; v <- Seq(t, t.toUpperCase, t.capitalize, t.zipWithIndex.map { case (c, i) => if (i % 2 == 0) c.toUpper else c }.mkString))
            assert(ColumnTypeText.safe(v), v)
    }

    test("integer is safe at load (Files: Decision 1 plus integer; Decision 6)") {
        for (v <- Seq("integer", "INTEGER", "Integer")) assert(ColumnTypeText.safe(v), v)
    }

    test(
        "decimal with one or two numbers, varchar and char with one number, and varchar(max) are safe, with or without spaces beside the parentheses and comma"
    ) {
        val ok = Seq(
            "decimal(10,2)",
            "decimal(12,2)",
            "decimal(1)",
            "decimal(10)",
            "decimal(9999,9999)",
            "decimal(38,0)",
            "DECIMAL(10,2)",
            "Decimal(10,2)",
            "decimal(10, 2)",
            "decimal(10 ,2)",
            "decimal( 10,2)",
            "decimal(10,2 )",
            "decimal( 10 , 2 )",
            "decimal( 10 )",
            "varchar(255)",
            "varchar(1)",
            "varchar(123456789)",
            "VARCHAR(255)",
            "varchar( 255 )",
            "char(1)",
            "CHAR(1)",
            "char( 10 )",
            "char(123456789)",
            "varchar(max)",
            "VARCHAR(MAX)",
            "varchar(Max)",
            "varchar( max )"
        )
        for (t <- ok) assert(ColumnTypeText.safe(t), "[" + t + "]")
    }

    test(
        "text after the closing parenthesis, a second parenthesis, a semicolon, a quote, a comment marker, a line break, a missing or empty number, a five-digit precision and null are not safe"
    ) {
        val bad: Seq[String] = Seq(
            // after the closing parenthesis
            "varchar(10) not null",
            "decimal(10,2) default 0",
            "char(1) collate \"C\"",
            "varchar(10)x",
            "int not null",
            "string,",
            // a second parenthesis
            "varchar(10))",
            "varchar((10))",
            "decimal(10,2)(3)",
            "varchar(10)()",
            evil,
            // a semicolon
            "int;",
            "varchar(10;)",
            "varchar(10);",
            // a quote
            "varchar('10')",
            "varchar(\"10\")",
            "varchar(10)'",
            "char(1)`",
            // a comment marker
            "int--",
            "varchar(10)--",
            "varchar(10)/*x*/",
            "decimal(10,2)#",
            // a line break (or other control text, or a tab)
            "varchar(10)\n",
            "varchar(\n10)",
            "int\r\n",
            "decimal(10,\n2)",
            "varchar(\t10)",
            // a missing or empty number
            "decimal()",
            "decimal( )",
            "varchar()",
            "char()",
            "decimal(,2)",
            "decimal(10,)",
            "decimal(10,2,3)",
            "varchar(",
            "varchar(10",
            "decimal",
            "char",
            // five-digit precision (and the other limits of Decision 1)
            "decimal(10000,2)",
            "decimal(10000)",
            "decimal(10,10000)",
            "varchar(1234567890)",
            "char(1234567890)",
            // not numbers
            "decimal(10,-2)",
            "decimal(-1)",
            "decimal(1.5)",
            "varchar(10a)",
            "varchar(0x10)",
            "decimal(10 2)",
            // empty and null
            "",
            " ",
            null
        )
        for (t <- bad) assert(!ColumnTypeText.safe(t), "[" + t + "]")
    }

    test("numeric, timestamp with time zone, varchar without a length and char(max) are not safe") {
        for (
            t <- Seq(
                "numeric",
                "numeric(10,2)",
                "NUMERIC(10,2)",
                "timestamp with time zone",
                "timestamptz",
                "timestamp without time zone",
                "varchar",
                "VARCHAR",
                "char(max)",
                "CHAR(MAX)",
                "double precision",
                "text",
                "json",
                "xml"
            )
        ) assert(!ColumnTypeText.safe(t), "[" + t + "]")
    }

    // ----------------------------------------------------------------- problem

    test("problem names the field and schema and shows at most 80 characters of the type with control characters replaced") {
        // a safe type has no problem
        assert(ColumnTypeText.problem(SchemaField("amount", "decimal(10,2)"), "destination").isEmpty)
        assert(ColumnTypeText.problem(SchemaField("amount", "int"), "source").isEmpty)

        for (schema <- Seq("source", "destination")) {
            val p = ColumnTypeText.problem(SchemaField("amount_x", evil), schema)
            assert(p.isDefined, schema)
            assert(p.get.contains("Invalid field type passed"), p.get)
            assert(p.get.contains("amount_x"), "names the field: " + p.get)
            assert(p.get.contains(schema), "names the schema: " + p.get)
            assert(p.get.contains(evil), "a short type is shown whole: " + p.get)
        }

        // at most 80 characters of the type
        val long = "varchar(10) " + ("abcdefghij" * 30)
        val pl = ColumnTypeText.problem(SchemaField("note", long), "destination").get
        assert(pl.contains(long.take(80)), pl)
        assert(!pl.contains(long.take(81)), "more than 80 characters of the type shown: " + pl)

        // control characters replaced: the message is one line and holds none
        val ctl = "varchar(10)\n; DROP TABLE public.canary;\r\n--\u0000\t\u001b[31m"
        val pc = ColumnTypeText.problem(SchemaField("note", ctl), "source").get
        assert(
            !pc.exists(c => Character.isISOControl(c)),
            "control character in: " + pc.map(c => if (Character.isISOControl(c)) "\\u%04x".format(c.toInt) else c.toString).mkString
        )
        assert(pc.contains("note") && pc.contains("source"), pc)

        // a null type is a problem too
        assert(ColumnTypeText.problem(SchemaField("note", null), "source").isDefined)
    }

    // ---------------------------------------------------------- changedProblem

    test("changedProblem is None when every type is safe") {
        val all = (plain ++ Seq("decimal(12,2)", "decimal(10, 2)", "decimal(10)", "varchar(255)", "char(1)", "varchar(max)")).zipWithIndex.map { case (t, i) =>
            SchemaField("c" + i, t)
        }
        val c = cfg(all, all)
        assert(ColumnTypeText.changedProblem(c, null).isEmpty, "no stored")
        assert(ColumnTypeText.changedProblem(c, c).isEmpty, "stored identical")
        assert(ColumnTypeText.changedProblem(c, cfg(Seq(SchemaField("other", "string")), null)).isEmpty, "stored different")
        assert(ColumnTypeText.changedProblem(cfg(all, null), null).isEmpty, "source only")
        assert(ColumnTypeText.changedProblem(cfg(null, all), null).isEmpty, "destination only")
        // null-safe for missing schemas and configs
        assert(ColumnTypeText.changedProblem(cfg(null, null), null).isEmpty)
        assert(ColumnTypeText.changedProblem(PipelineConfig(name = "x"), PipelineConfig(name = "x")).isEmpty)
        assert(ColumnTypeText.changedProblem(null, null).isEmpty)
    }

    test("changedProblem is None for an unsafe type the stored config has under the same field name in the same schema, in any letter case") {
        val odd = "varchar(10) not null"
        for (t <- Seq(odd, evil)) {
            // destination schema
            val dest = cfg(Seq(SchemaField("b", "string")), Seq(SchemaField("a", "string"), SchemaField("b", t)))
            assert(ColumnTypeText.changedProblem(dest, dest).isEmpty, "dest identical " + t)
            assert(
                ColumnTypeText.changedProblem(cfg(Seq(SchemaField("b", "string")), Seq(SchemaField("a", "int"), SchemaField("B", t.toUpperCase))), dest).isEmpty,
                "dest case " + t
            )
            // source schema
            val src = cfg(Seq(SchemaField("a", "string"), SchemaField("b", t)), null)
            assert(ColumnTypeText.changedProblem(src, src).isEmpty, "source identical " + t)
            assert(ColumnTypeText.changedProblem(cfg(Seq(SchemaField("B", t.toUpperCase), SchemaField("c", "int")), null), src).isEmpty, "source case " + t)
            // other safe fields may change around it
            assert(
                ColumnTypeText.changedProblem(cfg(Seq(SchemaField("b", t), SchemaField("z", "decimal(12,2)")), null), src).isEmpty,
                "source with added safe field " + t
            )
        }
    }

    test("changedProblem refuses the same unsafe type under another field name, in the other schema, or with one character changed") {
        val stored = cfg(Seq(SchemaField("b", evil)), Seq(SchemaField("b", "string")))
        // another field name, same schema
        val renamed = ColumnTypeText.changedProblem(cfg(Seq(SchemaField("renamed_col", evil)), Seq(SchemaField("renamed_col", "string"))), stored)
        assert(renamed.isDefined, "another field name")
        assert(renamed.get.contains("renamed_col"), renamed.get)
        // the other schema (stored has it in source only)
        val other = ColumnTypeText.changedProblem(cfg(Seq(SchemaField("b", evil)), Seq(SchemaField("b", evil))), stored)
        assert(other.isDefined, "the other schema")
        assert(other.get.contains("destination"), "names the destination schema: " + other.get)
        val other2 = ColumnTypeText.changedProblem(
            cfg(Seq(SchemaField("b", "string")), Seq(SchemaField("b", evil))),
            cfg(Seq(SchemaField("b", evil)), null)
        )
        assert(other2.isDefined, "the other schema, source only stored")
        // one character changed
        for (t <- Seq(evil.replace("canary", "canarz"), evil + " ", evil.dropRight(1), "varchar(11)); DROP TABLE public.canary; --"))
            assert(ColumnTypeText.changedProblem(cfg(Seq(SchemaField("b", t)), Seq(SchemaField("b", "string"))), stored).isDefined, "[" + t + "]")
    }

    test("changedProblem refuses an unsafe type when there is no stored config") {
        for (t <- Seq(evil, "varchar(10) not null", "numeric(10,2)", null)) {
            val clue = "[" + t + "]"
            val d = ColumnTypeText.changedProblem(cfg(Seq(SchemaField("a", "string")), Seq(SchemaField("a", "string"), SchemaField("bad_d", t))), null)
            assert(d.isDefined && d.get.contains("bad_d") && d.get.contains("destination"), clue + " destination: " + d)
            val s = ColumnTypeText.changedProblem(cfg(Seq(SchemaField("bad_s", t)), null), null)
            assert(s.isDefined && s.get.contains("bad_s") && s.get.contains("source"), clue + " source: " + s)
            // a stored config with no schemas grandfathers nothing
            assert(ColumnTypeText.changedProblem(cfg(Seq(SchemaField("bad_s", t)), null), PipelineConfig(name = "ty")).isDefined, clue + " empty stored")
            assert(ColumnTypeText.changedProblem(cfg(Seq(SchemaField("bad_s", t)), null), cfg(null, null)).isDefined, clue + " stored without schemas")
        }
    }

    // ------------------------------------------------------------- loadProblem

    test("loadProblem ignores _json and _xml and fields present in existingColumns, and names the first other unsafe field") {
        val fs = Seq(
            SchemaField("_json", evil),
            SchemaField("_XML", evil),
            SchemaField("ok", "decimal(10,2)"),
            SchemaField("present", evil),
            SchemaField("Present2", "varchar(10) not null"),
            SchemaField("missing_one", "varchar(10) not null"),
            SchemaField("missing_two", evil)
        )
        val p = ColumnTypeText.loadProblem(fs, Set("present", "present2"))
        assert(p.isDefined, "missing_one is unsafe and absent")
        assert(p.get.contains("missing_one"), p.get)
        assert(!p.get.contains("missing_two"), "names only the first: " + p.get)
        // every unsafe field present: no problem
        assert(ColumnTypeText.loadProblem(fs, Set("present", "present2", "missing_one", "missing_two")).isEmpty)
        // only document columns and safe ones: no problem even with no table
        assert(ColumnTypeText.loadProblem(fs.take(3), Set.empty).isEmpty)
        assert(ColumnTypeText.loadProblem(Nil, Set.empty).isEmpty)
        // Databricks shape: no existing columns, the first unsafe field is named
        val all = ColumnTypeText.loadProblem(fs, Set.empty)
        assert(all.isDefined && all.get.contains("present") && !all.get.contains("missing_one"), "first unsafe: " + all)
        // integer is tolerated at load (Decision 6)
        assert(ColumnTypeText.loadProblem(Seq(SchemaField("n", "integer")), Set.empty).isEmpty)
    }
}
