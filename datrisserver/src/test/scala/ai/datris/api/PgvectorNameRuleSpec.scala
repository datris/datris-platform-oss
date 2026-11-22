package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, DestinationTargets, SharedDatasets}
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

/** Story: pgvector schema, table and column names are escaped everywhere, and
  * a pgvector pipeline always names its table
  * (plans/stories/pgvector-identifier-escaping.md), Decision 2.
  *
  * The general save rule `PipelineAPIController.vectorTableNameRefusal(candidate,
  * stored)` takes no request: it applies to every caller (unrestricted
  * sessions, API keys, auth off), with the texts restricted sessions already
  * get, and is skipped when the stored config has the identical schema and
  * table (`DestinationTargets.sameVectorTableNames`). A version restore runs
  * the same rule with the snapshot as the candidate and the live config as
  * the stored one (`EntityVersionAPIController.pipelineRestoreRefusal`). */
class PgvectorNameRuleSpec extends AnyFunSuite {

    // --------------------------------------------------------------- requests

    private def req(rk: ResolvedKey): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/pipeline/version/restore")
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def editor: HttpServletRequest = req(APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null)))
    private def fullKey: HttpServletRequest = req(ResolvedKey(None, "full", Nil, isLegacyFullAccess = true))
    private def authOff: HttpServletRequest = req(null)
    private def callers: Seq[(String, HttpServletRequest)] = Seq("unrestricted session" -> editor, "API key" -> fullKey, "auth off" -> authOff)

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    // --------------------------------------------------------------- fixtures

    private def pgv(name: String, table: String, schema: String = "public", catalog: String = "Sales"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            destination = Destination(pgvector = PGVectorConfig(table, schema, ChunkingConfig(), null, null, null))
        )

    private def pg(name: String, table: String): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            createdByKeyLabel = "someone",
            destination = Destination(database = Database(dbName = "datris", schema = "public", table = table, usePostgres = true))
        )

    private def errorOf(r: ResponseEntity[String]): String =
        JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString

    private def assertRefused(r: Option[ResponseEntity[String]], text: String, clue: String): Unit = {
        assert(r.isDefined, clue + ": not refused")
        assert(r.get.getStatusCode.value == 400, clue + ": " + r)
        assert(errorOf(r.get) == text, clue)
    }

    private def rule(candidate: PipelineConfig, stored: PipelineConfig): Option[ResponseEntity[String]] =
        PipelineAPIController.vectorTableNameRefusal(candidate, stored)

    /** A probe no unrestricted restore may call. */
    private val noProbe: DestinationTargets.Target => Boolean = t => throw new AssertionError("probe called for " + t)

    private def restore(r: HttpServletRequest, snap: PipelineConfig, live: PipelineConfig): Seq[Option[ResponseEntity[String]]] =
        Seq(
            EntityVersionAPIController.pipelineRestoreRefusal(r, snap, live, Nil),
            EntityVersionAPIController.pipelineRestoreRefusal(r, snap, live, Nil, env, noProbe)
        )

    // (schema, table) pairs that can never be right
    private val unsafeNames: Seq[(String, String)] = Seq(
        ("public", "x\"; DROP TABLE public.canary; --"),
        ("public", "\""),
        ("public", "a\u0000b"),
        ("s\"q", "docs"),
        ("s\"q", "t\"q"),
        ("pub\u0000lic", "docs"),
        ("", "docs")
    )
    private val blankTables: Seq[String] = Seq(null, "", " ", "   ")

    // ------------------------------------------------------------------ save

    test(
        "a new pgvector save with a double quote or NUL in schema or table, or an empty schema, is 400 UnsafeVectorTableRefusal for an unrestricted session, an API key and auth off"
    ) {
        for ((s, t) <- unsafeNames) {
            val clue = "[" + s + "].[" + t + "]"
            assertRefused(rule(pgv("v", t, schema = s), null), DestinationTargets.UnsafeVectorTableRefusal, clue + " new")
            // a changed table: the stored one had another (plain) name
            assertRefused(rule(pgv("v", t, schema = s), pgv("v", "docs_old")), DestinationTargets.UnsafeVectorTableRefusal, clue + " changed")
            // a stored config that was not pgvector grandfathers nothing
            assertRefused(rule(pgv("v", t, schema = s), pg("v", t)), DestinationTargets.UnsafeVectorTableRefusal, clue + " was postgres")
            // the rule takes no request; every caller reaches it (restore path, deleted pipeline)
            for ((who, r) <- callers; out <- restore(r, pgv("v", t, schema = s), null))
                assertRefused(out, DestinationTargets.UnsafeVectorTableRefusal, clue + " " + who)
        }
    }

    test("a null or blank table is 400 BlankVectorTableRefusal for the same callers") {
        for (t <- blankTables; s <- Seq("public", null, "vec")) {
            val clue = "[" + s + "].[" + t + "]"
            assertRefused(rule(pgv("v", t, schema = s), null), DestinationTargets.BlankVectorTableRefusal, clue + " new")
            assertRefused(rule(pgv("v", t, schema = s), pgv("v", "docs", schema = s)), DestinationTargets.BlankVectorTableRefusal, clue + " changed")
            for ((who, r) <- callers; out <- restore(r, pgv("v", t, schema = s), null))
                assertRefused(out, DestinationTargets.BlankVectorTableRefusal, clue + " " + who)
        }
        // one blank is not another
        assertRefused(rule(pgv("v", null), pgv("v", " ")), DestinationTargets.BlankVectorTableRefusal, "null over blank")
        assertRefused(rule(pgv("v", ""), pgv("v", null)), DestinationTargets.BlankVectorTableRefusal, "empty over null")
    }

    test("a stored config with the identical schema and table is not refused, quote and null included") {
        for ((s, t) <- unsafeNames)
            assert(rule(pgv("v", t, schema = s), pgv("v", t, schema = s)).isEmpty, "[" + s + "].[" + t + "]")
        for (t <- blankTables; s <- Seq("public", null, "vec"))
            assert(rule(pgv("v", t, schema = s), pgv("v", t, schema = s)).isEmpty, "[" + s + "].[" + t + "]")
        // other fields may change: only schema and table are compared
        val stored = pgv("v", "x\"y", catalog = "Sales")
        val edited = stored.copy(tags = java.util.Arrays.asList("changed"), catalog = "Finance", version = 3)
        assert(rule(edited, stored).isEmpty)
    }

    test("changing only the schema of such a config is refused") {
        assertRefused(rule(pgv("v", "x\"y", schema = "vec"), pgv("v", "x\"y", schema = "public")), DestinationTargets.UnsafeVectorTableRefusal, "quote table")
        assertRefused(rule(pgv("v", "docs", schema = "t\"q"), pgv("v", "docs", schema = "s\"q")), DestinationTargets.UnsafeVectorTableRefusal, "quote schema")
        assertRefused(rule(pgv("v", "docs", schema = ""), pgv("v", "docs", schema = "public")), DestinationTargets.UnsafeVectorTableRefusal, "empty schema")
        for (t <- blankTables) {
            assertRefused(rule(pgv("v", t, schema = "vec"), pgv("v", t, schema = "public")), DestinationTargets.BlankVectorTableRefusal, "blank " + t)
            // nulls are compared as written: a null schema is not "public" here
            assertRefused(rule(pgv("v", t, schema = null), pgv("v", t, schema = "public")), DestinationTargets.BlankVectorTableRefusal, "null schema " + t)
        }
        // and changing only the table to another bad name
        assertRefused(rule(pgv("v", "x\"z"), pgv("v", "x\"y")), DestinationTargets.UnsafeVectorTableRefusal, "other quote table")
    }

    test("plain, mixed-case, hyphenated and spaced names are not refused") {
        val plain = Seq(
            ("public", "documents"),
            ("public", "Orders"),
            ("public", "pv-ok"),
            ("public", "My Docs"),
            ("public", "sales.v2"),
            ("Vec Store", "chunks"),
            ("my-schema", "o'brien;notes"),
            (null, "docs")
        )
        for ((s, t) <- plain) {
            val clue = "[" + s + "].[" + t + "]"
            assert(rule(pgv("v", t, schema = s), null).isEmpty, clue + " new")
            assert(rule(pgv("v", t, schema = s), pgv("v", "x\"y")).isEmpty, clue + " away from a quoted name")
            assert(rule(pgv("v", t, schema = s), pgv("v", null)).isEmpty, clue + " away from a null table")
            for ((who, r) <- callers; out <- restore(r, pgv("v", t, schema = s), null))
                assert(out.isEmpty, clue + " " + who)
        }
    }

    test("a non-pgvector config is not refused") {
        assert(rule(pg("p", "x\"; DROP TABLE public.canary; --"), null).isEmpty)
        assert(rule(pg("p", "orders"), pgv("p", null)).isEmpty)
        assert(rule(PipelineConfig(name = "x"), null).isEmpty)
        assert(rule(PipelineConfig(name = "x", destination = Destination()), null).isEmpty)
        assert(
            rule(PipelineConfig(name = "q", destination = Destination(qdrant = QdrantConfig(null, ChunkingConfig(), null, null, null))), null).isEmpty
        )
        assert(rule(null, null).isEmpty)
    }

    // --------------------------------------------------------------- restore

    test("a version restore of such a snapshot onto a different or missing live config is refused; onto identical live names it is not") {
        for ((who, r) <- callers) {
            for ((s, t) <- unsafeNames) {
                val snap = pgv("v", t, schema = s).copy(version = 2)
                val clue = "[" + s + "].[" + t + "] " + who
                for (out <- restore(r, snap, null)) assertRefused(out, DestinationTargets.UnsafeVectorTableRefusal, clue + " missing live")
                for (out <- restore(r, snap, pgv("v", "docs"))) assertRefused(out, DestinationTargets.UnsafeVectorTableRefusal, clue + " other live")
                for (out <- restore(r, snap, pgv("v", t, schema = s + "x")))
                    assertRefused(out, DestinationTargets.UnsafeVectorTableRefusal, clue + " other schema")
                for (out <- restore(r, snap, pgv("v", t, schema = s))) assert(out.isEmpty, clue + " identical live")
            }
            for (t <- blankTables) {
                val snap = pgv("v", t).copy(version = 2)
                val clue = "[" + t + "] " + who
                for (out <- restore(r, snap, null)) assertRefused(out, DestinationTargets.BlankVectorTableRefusal, clue + " missing live")
                for (out <- restore(r, snap, pgv("v", "docs"))) assertRefused(out, DestinationTargets.BlankVectorTableRefusal, clue + " other live")
                for (out <- restore(r, snap, pgv("v", t, schema = "vec")))
                    assertRefused(out, DestinationTargets.BlankVectorTableRefusal, clue + " other schema")
                for (out <- restore(r, snap, pgv("v", t))) assert(out.isEmpty, clue + " identical live")
            }
        }
    }
}
