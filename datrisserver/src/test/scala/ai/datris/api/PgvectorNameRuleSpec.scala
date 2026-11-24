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

    // ------------------------------------------------------------------------
    // Story: pgvector: two table names that PostgreSQL cuts to the same 63
    // bytes are treated as one table
    // (plans/stories/pgvector-long-names-one-table.md), Acceptance, refusal
    // spec. `vectorTableNameRefusal` adds a length case after the unsafe and
    // blank cases: a pgvector schema or table over 63 UTF-8 bytes (each on its
    // own) is 400 `DestinationTargets.LongVectorTableRefusal` for every caller,
    // skipped when `sameVectorTableNames(candidate, stored)`. Version restore
    // (`pipelineRestoreRefusal`) and catalog restore (`savePipelineInternal`)
    // run the same function. `LongVectorTableRefusal` and
    // `longVectorTableName` are reached by reflection so this file compiles
    // before they exist.

    private val LongText = "The pgvector schema and table names must each be at most 63 bytes, the PostgreSQL limit for a name."

    private def longRefusalConstant: String =
        try DestinationTargets.getClass.getMethod("LongVectorTableRefusal").invoke(DestinationTargets).asInstanceOf[String]
        catch { case _: NoSuchMethodException => throw new AssertionError("DestinationTargets has no LongVectorTableRefusal (added by the story)") }

    private def longVectorTableName(c: PipelineConfig): Boolean = {
        val m =
            try DestinationTargets.getClass.getMethod("longVectorTableName", classOf[PipelineConfig])
            catch {
                case _: NoSuchMethodException => throw new AssertionError("DestinationTargets has no longVectorTableName(PipelineConfig) (added by the story)")
            }
        m.invoke(DestinationTargets, c).asInstanceOf[java.lang.Boolean].booleanValue
    }

    private def restricted: HttpServletRequest =
        req(
            APIKeyValidator.resolveFromSession(
                User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = ai.datris.util.InMemoryUserDb.grants("Sales" -> "edit"))
            )
        )

    private def source(rel: String): String =
        new String(
            java.nio.file.Files.readAllBytes(
                Seq(java.nio.file.Paths.get("src/main/scala/" + rel), java.nio.file.Paths.get("datrisserver/src/main/scala/" + rel))
                    .find(java.nio.file.Files.exists(_))
                    .get
            ),
            java.nio.charset.StandardCharsets.UTF_8
        )

    private val eAcute = "\u00e9"
    private val euro = "\u20ac"

    // (schema, table) pairs with a schema or a table over 63 bytes
    private val longNames: Seq[(String, String)] = Seq(
        ("public", "t" * 64),
        ("public", "t" * 70),
        ("public", "t" * 63 + "_one"),
        ("public", "t" * 62 + eAcute), // 64 bytes, 63 characters
        ("public", eAcute * 32), // 64 bytes, 32 characters
        ("public", euro * 22),
        ("s" * 64, "docs"),
        ("s" * 62 + eAcute, "docs"),
        ("s" * 64, "t" * 64),
        (null, "t" * 64)
    )
    // at the limit or under it, each on its own
    private val atLimit: Seq[(String, String)] = Seq(
        ("public", "t" * 63),
        ("public", "t" * 61 + eAcute),
        ("public", euro * 21),
        ("public", eAcute * 31 + "t"),
        ("s" * 63, "docs"),
        ("s" * 63, "t" * 63), // 127 bytes together: each is judged alone
        (null, "t" * 63)
    )

    test(
        "a new or changed pgvector table or schema over 63 bytes is refused with LongVectorTableRefusal for every caller; the same stored names are accepted; a version restore and a catalog restore are refused the same way"
    ) {
        // ---- the plain rule (POST /pipeline, every caller: it takes no request)
        for ((s, t) <- longNames) {
            val clue = "[" + s + "].[" + t + "]"
            assertRefused(rule(pgv("v", t, schema = s), null), LongText, clue + " new")
            assertRefused(rule(pgv("v", t, schema = s), pgv("v", "docs_old")), LongText, clue + " changed from a short name")
            assertRefused(rule(pgv("v", t, schema = s), pg("v", t)), LongText, clue + " was postgres")
        }
        assert(longRefusalConstant == LongText)
        for ((s, t) <- longNames) assert(longVectorTableName(pgv("v", t, schema = s)), "[" + s + "].[" + t + "]")
        for ((s, t) <- atLimit) assert(!longVectorTableName(pgv("v", t, schema = s)), "[" + s + "].[" + t + "]")
        assert(!longVectorTableName(pg("p", "t" * 70)))
        assert(!longVectorTableName(PipelineConfig(name = "x")))
        assert(!longVectorTableName(null))

        // at the limit: never refused
        for ((s, t) <- atLimit) {
            val clue = "[" + s + "].[" + t + "]"
            assert(rule(pgv("v", t, schema = s), null).isEmpty, clue + " new")
            assert(rule(pgv("v", t, schema = s), pgv("v", "t" * 70)).isEmpty, clue + " away from a long name")
        }

        // the unsafe and blank cases come first, unchanged
        assertRefused(rule(pgv("v", "x\"" + "t" * 70), null), DestinationTargets.UnsafeVectorTableRefusal, "quote and long")
        assertRefused(rule(pgv("v", "docs", schema = "s\"" + "s" * 70), null), DestinationTargets.UnsafeVectorTableRefusal, "quote schema and long")
        assertRefused(rule(pgv("v", null, schema = "s" * 70), null), DestinationTargets.BlankVectorTableRefusal, "blank table, long schema")
        assertRefused(rule(pgv("v", " ", schema = "s" * 70), null), DestinationTargets.BlankVectorTableRefusal, "spaces table, long schema")

        // ---- grandfathered: a stored over-long name saved again unchanged
        for ((s, t) <- longNames) {
            val clue = "[" + s + "].[" + t + "]"
            val stored = pgv("v", t, schema = s)
            assert(rule(stored, stored).isEmpty, clue + " unchanged")
            assert(rule(stored.copy(tags = java.util.Arrays.asList("changed"), catalog = "Finance", version = 3), stored).isEmpty, clue + " other fields")
        }
        // changed to another over-long name (even one PostgreSQL cuts to the same table): refused
        assertRefused(rule(pgv("v", "t" * 80), pgv("v", "t" * 70)), LongText, "other long table")
        assertRefused(rule(pgv("v", "t" * 63 + "_two"), pgv("v", "t" * 63 + "_one")), LongText, "same cut, other name")
        assertRefused(rule(pgv("v", "docs", schema = "s" * 80), pgv("v", "docs", schema = "s" * 70)), LongText, "other long schema")
        // keeping the long table but changing the schema: refused
        assertRefused(rule(pgv("v", "t" * 70, schema = "vec"), pgv("v", "t" * 70, schema = "public")), LongText, "long table, schema changed")
        assert(rule(pgv("v", "t" * 70, schema = null), pgv("v", "t" * 70, schema = "public")).isEmpty, "long table, null schema")
        // the length rule's grandfathering compares the effective schema (a
        // null schema is "public", as the loader addresses it): the UI always
        // sends a schema, so a stored long table saved with none stays editable
        assert(rule(pgv("v", "t" * 70, schema = "public"), pgv("v", "t" * 70, schema = null)).isEmpty, "stored null schema saved as public")
        assert(
            rule(pgv("v", "t" * 70, schema = "public").copy(tags = java.util.Arrays.asList("edited")), pgv("v", "t" * 70, schema = null)).isEmpty,
            "stored null schema, other fields edited"
        )
        assert(rule(pgv("v", "docs", schema = "s" * 70), pgv("v", "docs", schema = "s" * 70)).isEmpty, "long schema unchanged")
        // a different schema with the same long table is another table: refused
        assertRefused(rule(pgv("v", "t" * 70, schema = "vec"), pgv("v", "t" * 70, schema = null)), LongText, "stored null schema, schema changed")
        assertRefused(rule(pgv("v", "t" * 70, schema = " "), pgv("v", "t" * 70, schema = null)), LongText, "stored null schema, spaces schema")
        // the same through version restore, for every caller
        for ((who, r) <- callers) {
            for (out <- restore(r, pgv("v", "t" * 70, schema = "public").copy(version = 2), pgv("v", "t" * 70, schema = null)))
                assert(out.isEmpty, who + " restore public over null")
            for (out <- restore(r, pgv("v", "t" * 70, schema = null).copy(version = 2), pgv("v", "t" * 70, schema = "public")))
                assert(out.isEmpty, who + " restore null over public")
            for (out <- restore(r, pgv("v", "t" * 70, schema = "vec").copy(version = 2), pgv("v", "t" * 70, schema = null)))
                assertRefused(out, LongText, who + " restore other schema over null")
        }
        // changing a long name to a short one: accepted
        assert(rule(pgv("v", "docs"), pgv("v", "t" * 70)).isEmpty)

        // ---- every caller, through the version restore forms
        val allCallers = callers :+ ("restricted session" -> restricted)
        for ((s, t) <- longNames; (who, r) <- allCallers) {
            val snap = pgv("v", t, schema = s).copy(version = 2)
            val clue = "[" + s + "].[" + t + "] " + who
            for (out <- restore(r, snap, null)) assertRefused(out, LongText, clue + " missing live")
            for (out <- restore(r, snap, pgv("v", "docs"))) assertRefused(out, LongText, clue + " other live")
            for (out <- restore(r, snap, pgv("v", t, schema = "vec"))) assertRefused(out, LongText, clue + " other live schema")
        }
        for ((s, t) <- longNames; (who, r) <- callers)
            for (out <- restore(r, pgv("v", t, schema = s).copy(version = 2), pgv("v", t, schema = s)))
                assert(out.isEmpty, "[" + s + "].[" + t + "] " + who + " identical live")

        // ---- a new pipeline colliding, after the cut, with a stored long name:
        // the length rule answers, before the container rule is reached
        val storedLong = pgv("long_a", "t" * 63 + "_one")
        val newLong = pgv("long_b", "t" * 63 + "_two")
        for ((who, r) <- callers) {
            assertRefused(EntityVersionAPIController.pipelineRestoreRefusal(r, newLong, null, Seq(storedLong)), LongText, who + " collision, 4-arg")
            assertRefused(
                EntityVersionAPIController.pipelineRestoreRefusal(r, newLong, null, Seq(storedLong), env, noProbe),
                LongText,
                who + " collision, 6-arg"
            )
        }
        // the stored pair (saved before this change) restored onto itself: accepted
        val storedPair = Seq(storedLong, newLong)
        for ((who, r) <- callers)
            assert(EntityVersionAPIController.pipelineRestoreRefusal(r, newLong.copy(version = 2), newLong, storedPair).isEmpty, who + " stored pair")
        assert(PipelineAPIController.vectorContainerRefusal(editor, newLong, newLong, storedPair).isEmpty)

        // ---- catalog restore and POST /pipeline: both save through savePipelineCore,
        // which runs this same rule on the stored config of the name (source pin;
        // the live restore needs MongoDB)
        val pc = source("ai/datris/api/PipelineAPIController.scala")
        val core = pc.indexOf("private def savePipelineCore(")
        assert(core > 0 && pc.indexOf("PipelineAPIController.vectorTableNameRefusal(preserved, existing)", core) > core)
        assert(pc.indexOf("savePipelineCore(config, changeNote, request, None)") > 0)
        assert(source("ai/datris/api/CatalogAPIController.scala").contains("pipelineController.savePipelineInternal(toSave, note, request)"))
    }
}
