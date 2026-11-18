package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import ai.datris.util.{DestinationTargets, PGVectorSql, PgIdentifier}
import org.scalatest.funsuite.AnyFunSuite

/** Story: pgvector schema, table and column names are escaped everywhere
  * (plans/stories/pgvector-identifier-escaping.md), Decision 4: a delete never
  * drops a pgvector table for a null or blank `tableName`, for any caller, and
  * the response `warnings` gain one line
  * (`PipelineAPIController.blankVectorTableDeleteWarning`). Every other
  * pgvector delete drops exactly `PGVectorSql.dropTable(schema, table)`. Pure:
  * the JDBC drop itself is covered by the manual Verify. */
class PgvectorDeleteBlankTableSpec extends AnyFunSuite {

    private def pgv(name: String, table: String, schema: String = "public"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            destination = Destination(pgvector = PGVectorConfig(table, schema, ChunkingConfig(), null, null, null))
        )

    test("blankVectorTableDeleteWarning is one line for null, empty and whitespace, None otherwise") {
        for (t <- Seq(null, "", " ", "   ", "\t"); s <- Seq("public", null, "vec")) {
            val c = pgv("pv_null", t, schema = s)
            // the delete skips the drop exactly when this warning is given
            assert(DestinationTargets.blankVectorTableName(c), String.valueOf(t))
            val w = PipelineAPIController.blankVectorTableDeleteWarning(c)
            assert(w.isDefined, "[" + t + "]: no warning")
            assert(w.get.trim.nonEmpty, "[" + t + "]: empty warning")
            assert(!w.get.contains('\n') && !w.get.contains('\r'), "[" + t + "]: more than one line: " + w.get)
        }
        for (t <- Seq("docs", "My Docs", "x\"y", "null"))
            assert(PipelineAPIController.blankVectorTableDeleteWarning(pgv("pv", t)).isEmpty, t)
        assert(PipelineAPIController.blankVectorTableDeleteWarning(PipelineConfig(name = "x")).isEmpty)
        assert(PipelineAPIController.blankVectorTableDeleteWarning(PipelineConfig(name = "x", destination = Destination())).isEmpty)
        assert(
            PipelineAPIController
                .blankVectorTableDeleteWarning(
                    PipelineConfig(
                        name = "p",
                        destination = Destination(database = Database(dbName = "datris", schema = "public", table = null, usePostgres = true))
                    )
                )
                .isEmpty
        )
    }

    test("the drop statement for a quoted name is PGVectorSql.dropTable of the same names") {
        val cases = Seq(
            ("public", "x\"; DROP TABLE public.canary; --"),
            ("s\"q", "docs"),
            ("\"", "\"\""),
            ("public", "docs")
        )
        for ((s, t) <- cases) {
            val sql = PGVectorSql.dropTable(s, t)
            assert(sql == "DROP TABLE IF EXISTS " + PgIdentifier.qualified(s, t) + " CASCADE", sql)
        }
        assert(PGVectorSql.dropTable(
            "public",
            "x\"; DROP TABLE public.canary; --"
        ) == "DROP TABLE IF EXISTS \"public\".\"x\"\"; DROP TABLE public.canary; --\" CASCADE")
        // a stored config's null schema is public, as the loader writes it
        val legacy = pgv("pv_q", "a\"b", schema = null).destination.pgvector
        assert(
            PGVectorSql.dropTable(PGVectorSql.schemaOf(legacy), PGVectorSql.tableOf(legacy)) ==
                "DROP TABLE IF EXISTS \"public\".\"a\"\"b\" CASCADE"
        )
    }
}
