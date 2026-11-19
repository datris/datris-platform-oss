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

    // ------------------------------------------- implementer additions (lead ruling C)

    test("a space before the type name, between the name and the parenthesis, or after a plain type name is not safe") {
        // v1.60.0's validateColumns refuses each of these on save: plain names
        // are compared whole (no trim), and the parenthesised forms must start
        // with "decimal(", "varchar(" or "char(".
        for (t <- Seq("decimal (10,2)", "varchar (255)", "char (1)", "varchar (max)", " int", "int ", " decimal(10,2)", "decimal(10,  2)x"))
            assert(!ColumnTypeText.safe(t), "[" + t + "]")
        // more than one space beside a parenthesis or the comma is still a space beside it
        assert(ColumnTypeText.safe("decimal(  10  ,  2  )"))
    }

    test("spaces after the closing parenthesis are safe (v1.60.0 saved and sent them)") {
        for (t <- Seq("decimal(10,2) ", "varchar(50) ", "char(1)  ", "varchar(max) ", "decimal( 10 , 2 )   ", "DECIMAL(10) "))
            assert(ColumnTypeText.safe(t), "[" + t + "]")
        // still not: other text after it, or a tab or line break
        for (t <- Seq("decimal(10,2) x", "decimal(10,2)\t", "decimal(10,2) \n", "varchar(50) ;"))
            assert(!ColumnTypeText.safe(t), "[" + t + "]")
    }

    test("loadText: safe text as stored; other ASCII blanks in the accepted places removed; anything else None") {
        assert(ColumnTypeText.loadText("decimal(10,2)").contains("decimal(10,2)"))
        assert(ColumnTypeText.loadText("decimal(10, 2) ").contains("decimal(10, 2) "), "safe text is sent exactly as stored")
        assert(ColumnTypeText.loadText("decimal(10,\t2)").contains("decimal(10,2)"))
        assert(ColumnTypeText.loadText("decimal(10,2)\n").contains("decimal(10,2)"))
        assert(ColumnTypeText.loadText("varchar( 50 )\r\n").contains("varchar(50)"))
        assert(ColumnTypeText.loadText("varchar(\u000b50\f)").contains("varchar(50)"))
        for (
            t <- Seq(
                "decimal(10,2) /* x */",
                "varchar(10) not null",
                evil,
                "varchar(10)\n; DROP TABLE public.canary",
                "decimal(10\t2)",
                "varchar(1\n0)",
                "int\n",
                "\tint",
                "decimal\t(10,2)",
                null
            )
        ) assert(ColumnTypeText.loadText(t).isEmpty, "[" + t + "]")
        // a normalisable stored type is not unsafe at load, and the save rule stays strict
        assert(ColumnTypeText.unsafeFields(Seq(SchemaField("a", "decimal(10,\t2)"))).isEmpty)
        assert(ColumnTypeText.loadProblem(Seq(SchemaField("a", "varchar( 50 )\r\n")), Set.empty).isEmpty)
        assert(ColumnTypeText.problem(SchemaField("a", "decimal(10,\t2)"), "destination").isDefined)
    }

    test("problem shows at most 80 characters of the field name and no line or paragraph separator") {
        val longName = "n" * 200
        val p = ColumnTypeText.problem(SchemaField(longName, evil), "source").get
        assert(p.contains("n" * 80) && !p.contains("n" * 81), p)
        val sepName = "a\u2028b\u2029c\nd"
        val q = ColumnTypeText.problem(SchemaField(sepName, "varchar(1)\u2028x"), "source").get
        assert(!q.exists(c => c == '\u2028' || c == '\u2029' || Character.isISOControl(c)), q)
        assert(!ColumnTypeText.notUsedStatus(Seq(SchemaField(sepName, evil))).exists(c => c == '\u2028' || c == '\u2029' || Character.isISOControl(c)))
    }

    test("create and evolve: plain statements when every type loads; no create, only missing columns and a status line otherwise") {
        val ok = Seq(SchemaField("id", "int"), SchemaField("note", "string"))
        val c1 = ColumnTypeText.create(ok, Some("S"), "T")
        assert(c1 == ColumnTypeText.CreatePlan(Some("S"), Some("T")))
        assert(ColumnTypeText.evolve(ok, Set("id"), f => "ADD " + f.name) == Right(ColumnTypeText.EvolvePlan(Seq("ADD note"), None)))
        val odd = ok :+ SchemaField("b", "varchar(10) not null")
        // the create statements are never even built
        assert(ColumnTypeText.create(odd, throw new AssertionError("schema built"), throw new AssertionError("table built")) == ColumnTypeText.CreatePlan(
            None,
            None
        ))
        val kept = ColumnTypeText.evolve(odd, Set("id", "b"), f => "ADD " + f.name)
        assert(kept.isRight && kept.right.get.addColumns == Seq("ADD note") && kept.right.get.statusLine.exists(_.contains("b")), kept.toString)
        val refused = ColumnTypeText.evolve(odd, Set("id"), f => throw new AssertionError("statement built for " + f.name))
        assert(refused.isLeft && refused.left.get.contains("'b'"), refused.toString)
    }

    // ------------------------------------------- Unicode spaces and message escaping

    test("a stored type with a Unicode space normalises at load and is refused on a new save") {
        for ((t, sent) <- Seq("decimal(10,\u00a02)" -> "decimal(10,2)", "varchar(\u200350)" -> "varchar(50)", "decimal(10,2)\u00a0" -> "decimal(10,2)")) {
            assert(ColumnTypeText.loadText(t).contains(sent), "[" + t + "]")
            assert(!ColumnTypeText.safe(t), "save stays U+0020 only: [" + t + "]")
            assert(ColumnTypeText.changedProblem(cfg(Seq(SchemaField("a", t)), null), null).isDefined, "[" + t + "]")
        }
        for (t <- Seq("decimal(10\u00a02)", "var\u00a0char(10)", "decimal(10 2)", "var char(10)", "de\u2003cimal(10,2)"))
            assert(ColumnTypeText.loadText(t).isEmpty, "[" + t + "]")
    }

    test("a refused type or name with non-ASCII text is shown escaped") {
        val p = ColumnTypeText.problem(SchemaField("n\u00e9", "decimal(10,\u00a02) x"), "source").get
        assert(p.contains("decimal(10,\\u00a02) x"), p)
        assert(p.contains("n\\u00e9"), p)
        assert(p.forall(c => c >= ' ' && c <= '~'), p)
    }

    test("exhaustive: no character inserted anywhere into an accepted type makes loadText return anything but plain accepted ASCII") {
        // loadText reads each load blank as U+0020, checks safe (ASCII letters,
        // digits, parentheses, comma and U+0020 only), then removes the blanks;
        // so the result is the safe text with optional spaces removed.
        val bases = Seq("decimal(10,2)", "varchar(50)", "char(1)", "varchar(max)", "int")
        val expected = bases.toSet
        for (b <- bases; c <- 0 to 0xffff; i <- 0 to b.length) {
            val t = b.substring(0, i) + c.toChar + b.substring(i)
            ColumnTypeText.loadText(t).foreach { out =>
                assert(ColumnTypeText.safe(out) && out.forall(ch => ch >= ' ' && ch <= '~'), "[" + t + "] -> [" + out + "]")
                assert(expected.contains(out) || out == t, "[" + t + "] -> [" + out + "]")
            }
        }
    }
}
