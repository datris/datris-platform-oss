package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, ColumnTypeText, DestinationTargets, SharedDatasets}
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

/** Story: Column types: one exact rule for the type a schema field may carry,
  * checked on every save and again before any destination statement
  * (plans/stories/column-type-text-escaping.md), Decision 2.
  *
  * {{{
  * object PipelineAPIController {
  *     def columnTypeRefusal(candidate: PipelineConfig, stored: PipelineConfig): Option[ResponseEntity[String]]
  * }
  * }}}
  *
  * Takes no request: on every save path (POST /pipeline for every caller,
  * through `savePipelineCore` directly after `databaseNameRefusal`, and
  * version restore through every `EntityVersionAPIController.pipelineRestoreRefusal`
  * overload, after `databaseNameRefusal`) the first source or destination
  * field whose type fails `ColumnTypeText.safe` is 400 `{"error":
  * ColumnTypeText.changedProblem(...)}`, on every pipeline type, unless the
  * stored config has a field of the same name in the same schema with the
  * same type (both case-insensitive). Fixtures copied from
  * DatabaseNameRuleSpec. */
class ColumnTypeRuleSpec extends AnyFunSuite {

    // --------------------------------------------------------------- requests

    private def req(rk: ResolvedKey): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/pipeline/version/restore")
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def grants(kv: (String, String)*): java.util.Map[String, String] = {
        val m = new java.util.HashMap[String, String]()
        kv.foreach { case (k, v) => m.put(k, v) }
        m
    }

    private def editor: HttpServletRequest = req(APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null)))
    private def restricted: HttpServletRequest =
        req(APIKeyValidator.resolveFromSession(User(
            "bob",
            "h",
            User.RoleEditor,
            null,
            null,
            null,
            restricted = true,
            catalogGrants = grants("Sales" -> "edit")
        )))
    private def fullKey: HttpServletRequest = req(ResolvedKey(None, "full", Nil, isLegacyFullAccess = true))
    private def authOff: HttpServletRequest = req(null)

    /** Callers that see every pipeline (the four-argument restore reads no environment for them). */
    private def unlimited: Seq[(String, HttpServletRequest)] = Seq("unrestricted session" -> editor, "API key" -> fullKey, "auth off" -> authOff)
    private def callers: Seq[(String, HttpServletRequest)] = unlimited :+ ("restricted session" -> restricted)

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    /** No refused restore may reach a destination probe: the type rule runs before it. */
    private val noProbe: DestinationTargets.Target => Boolean = t => throw new AssertionError("probe called for " + t)

    // --------------------------------------------------------------- fixtures

    private val pgDb: Database = Database(dbName = "datris", schema = "public", table = "ty_q", usePostgres = true)

    private def schema(fs: Seq[SchemaField]): SchemaProperties = if (fs == null) null else SchemaProperties(null, java.util.Arrays.asList(fs: _*))

    /** A structured CSV pipeline into Postgres; null = no such schema. */
    private def structured(src: Seq[SchemaField], dest: Seq[SchemaField], name: String = "ty_q"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            createdByKeyLabel = "someone",
            source = Source(schemaProperties = schema(src), fileAttributes = FileAttributes(csvAttributes = CsvAttributes())),
            destination = Destination(schemaProperties = schema(dest), database = pgDb)
        )

    /** An unstructured pipeline (object-store destination) carrying a schema. */
    private def unstructured(src: Seq[SchemaField], dest: Seq[SchemaField] = null, name: String = "ty_u"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            createdByKeyLabel = "someone",
            source = Source(
                schemaProperties = schema(src),
                fileAttributes = FileAttributes(unstructuredAttributes = UnstructuredAttributes(fileExtension = "pdf"))
            ),
            destination = Destination(schemaProperties = schema(dest), objectStore = ObjectStore(prefixKey = "docs"))
        )

    private def errorOf(r: ResponseEntity[String]): String =
        JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString

    /** 400 whose error is the pure rule's text and names `field`. */
    private def assertRefused(r: Option[ResponseEntity[String]], candidate: PipelineConfig, stored: PipelineConfig, field: String, clue: String): Unit = {
        assert(r.isDefined, clue + ": not refused")
        assert(r.get.getStatusCode.value == 400, clue + ": " + r)
        val text = errorOf(r.get)
        assert(text.contains("Invalid field type passed"), clue + ": " + text)
        assert(text.contains(field), clue + ": does not name " + field + ": " + text)
        val pure = ColumnTypeText.changedProblem(candidate, stored)
        assert(pure.isDefined && text == pure.get, clue + ": " + text + " vs " + pure)
    }

    private def rule(candidate: PipelineConfig, stored: PipelineConfig): Option[ResponseEntity[String]] =
        PipelineAPIController.columnTypeRefusal(candidate, stored)

    private def restore(
        who: String,
        r: HttpServletRequest,
        snap: PipelineConfig,
        live: PipelineConfig,
        probe: DestinationTargets.Target => Boolean
    ): Seq[Option[ResponseEntity[String]]] = {
        val six = EntityVersionAPIController.pipelineRestoreRefusal(r, snap, live, Nil, env, probe)
        val seven = EntityVersionAPIController.pipelineRestoreRefusal(r, snap, live, Nil, env, probe, probe)
        if (who == "restricted session") Seq(six, seven)
        else Seq(EntityVersionAPIController.pipelineRestoreRefusal(r, snap, live, Nil), six, seven)
    }

    private val evil = "varchar(10)); DROP TABLE public.canary; --"

    /** Types that start with an accepted prefix (so the validator lets them
      * through) or otherwise fail the rule. */
    private val failing: Seq[String] = Seq(evil, "varchar(10) not null", "decimal(10,2) default 0", "char(1) collate \"C\"", "decimal(10,-2)", "numeric(10,2)")

    private val plainSrc = Seq(SchemaField("id", "int"), SchemaField("name", "string"), SchemaField("amount_x", "string"))

    // ------------------------------------------------------------------ save

    test("a new structured pipeline with a failing destination type is 400 for an unrestricted session, a restricted session, an API key and auth off") {
        for (t <- failing) {
            val c = structured(plainSrc, Seq(SchemaField("id", "int"), SchemaField("name", "string"), SchemaField("amount_x", t)))
            // the save rule: no request, so the same for every caller
            assertRefused(rule(c, null), c, null, "amount_x", "[" + t + "] new")
            // and through every restore overload, per caller (a deleted pipeline: no live config)
            val snap = c.copy(version = 2)
            for ((who, r) <- callers; out <- restore(who, r, snap, null, noProbe))
                assertRefused(out, snap, null, "amount_x", "[" + t + "] " + who)
        }
    }

    test("the same type in the source schema is 400") {
        for (t <- failing) {
            val withDest =
                structured(Seq(SchemaField("id", "int"), SchemaField("amount_x", t)), Seq(SchemaField("id", "int"), SchemaField("amount_x", "string")))
            assertRefused(rule(withDest, null), withDest, null, "amount_x", "[" + t + "] source, destination schema present")
            // no destination schema: the source schema is the destination schema at load
            val noDest = structured(Seq(SchemaField("id", "int"), SchemaField("amount_x", t)), null)
            assertRefused(rule(noDest, null), noDest, null, "amount_x", "[" + t + "] source, no destination schema")
            assert(errorOf(rule(noDest, null).get).contains("source"), "names the source schema")
            for ((who, r) <- callers; out <- restore(who, r, noDest.copy(version = 2), null, noProbe))
                assertRefused(out, noDest.copy(version = 2), null, "amount_x", "[" + t + "] source " + who)
        }
    }

    test("an unstructured pipeline with a failing type is 400") {
        for (t <- failing) {
            val src = unstructured(Seq(SchemaField("amount_x", t)))
            assertRefused(rule(src, null), src, null, "amount_x", "[" + t + "] unstructured source")
            val dest = unstructured(Seq(SchemaField("id", "string")), Seq(SchemaField("amount_x", t)))
            assertRefused(rule(dest, null), dest, null, "amount_x", "[" + t + "] unstructured destination")
            for ((who, r) <- callers; out <- restore(who, r, src.copy(version = 2), null, noProbe))
                assertRefused(out, src.copy(version = 2), null, "amount_x", "[" + t + "] unstructured " + who)
        }
    }

    test("a stored config with the identical field and type is not refused") {
        for (t <- failing) {
            val stored = structured(plainSrc, Seq(SchemaField("id", "int"), SchemaField("amount_x", t)))
            assert(rule(stored, stored).isEmpty, "[" + t + "] unchanged")
            assert(rule(stored.copy(), stored).isEmpty, "[" + t + "] equal copy")
            // other fields may change: tags, catalog, version, other safe fields, letter case of the odd one
            val edited = structured(
                plainSrc :+ SchemaField("added", "decimal(12,2)"),
                Seq(SchemaField("id", "bigint"), SchemaField("AMOUNT_X", t.toUpperCase), SchemaField("added", "decimal(12,2)"))
            ).copy(tags = java.util.Arrays.asList("changed"), catalog = "Finance", version = 3)
            assert(rule(edited, stored).isEmpty, "[" + t + "] other fields edited")
            // the same holds in the source schema and on an unstructured pipeline
            val src = structured(Seq(SchemaField("amount_x", t)), null)
            assert(rule(src, src).isEmpty, "[" + t + "] source unchanged")
            val u = unstructured(Seq(SchemaField("amount_x", t)))
            assert(rule(u, u).isEmpty, "[" + t + "] unstructured unchanged")
        }
    }

    test("changing that type to another failing value is refused") {
        val stored = structured(plainSrc, Seq(SchemaField("id", "int"), SchemaField("amount_x", "varchar(10) not null")))
        for (
            t <- Seq(
                "varchar(10) null",
                "varchar(10) not null; DROP TABLE public.canary; --",
                evil,
                "varchar(11) not null",
                "numeric(10,2)"
            )
        ) {
            val c = structured(plainSrc, Seq(SchemaField("id", "int"), SchemaField("amount_x", t)))
            assertRefused(rule(c, stored), c, stored, "amount_x", "[" + t + "]")
        }
        // and changing it to a safe type is accepted
        assert(rule(structured(plainSrc, Seq(SchemaField("id", "int"), SchemaField("amount_x", "varchar(10)"))), stored).isEmpty, "to varchar(10)")
    }

    test("adding a second field with a failing type to such a config is refused") {
        val stored = structured(plainSrc, Seq(SchemaField("id", "int"), SchemaField("amount_x", evil)))
        for (t <- failing) {
            val c = structured(plainSrc, Seq(SchemaField("id", "int"), SchemaField("amount_x", evil), SchemaField("second_y", t)))
            assertRefused(rule(c, stored), c, stored, "second_y", "[" + t + "] second field")
            assert(!errorOf(rule(c, stored).get).contains("amount_x"), "the grandfathered field is not the one named")
        }
    }

    // --------------------------------------------------------------- restore

    test(
        "a version restore of a failing snapshot onto a different or missing live config is refused; onto a live config with the same field and type it is not"
    ) {
        for ((who, r) <- callers; t <- failing) {
            val snap = structured(plainSrc, Seq(SchemaField("id", "int"), SchemaField("amount_x", t))).copy(version = 2)
            val clue = "[" + t + "] " + who
            for (out <- restore(who, r, snap, null, noProbe)) assertRefused(out, snap, null, "amount_x", clue + " missing live")
            val plainLive = structured(plainSrc, Seq(SchemaField("id", "int"), SchemaField("amount_x", "string")))
            for (out <- restore(who, r, snap, plainLive, noProbe)) assertRefused(out, snap, plainLive, "amount_x", clue + " plain live")
            val otherName = structured(plainSrc, Seq(SchemaField("id", "int"), SchemaField("other_z", t)))
            for (out <- restore(who, r, snap, otherName, noProbe))
                assertRefused(out, snap, otherName, "amount_x", clue + " live with the type on another field")
            val otherType = structured(plainSrc, Seq(SchemaField("id", "int"), SchemaField("amount_x", t + " ")))
            for (out <- restore(who, r, snap, otherType, noProbe)) assertRefused(out, snap, otherType, "amount_x", clue + " live with another type")
            val noSchemas = structured(null, null)
            for (out <- restore(who, r, snap, noSchemas, noProbe)) assertRefused(out, snap, noSchemas, "amount_x", clue + " live without schemas")
            // the same field and type live: nothing new, nothing to probe
            val same = structured(plainSrc, Seq(SchemaField("id", "int"), SchemaField("amount_x", t))).copy(version = 3)
            for (out <- restore(who, r, snap, same, _ => false)) assert(out.isEmpty, clue + " same live: " + out)
        }
    }

    // ------------------------------------------------------------ safe types

    test("decimal(12,2), varchar(255) and char(1) are not refused") {
        val ok = Seq(
            SchemaField("id", "int"),
            SchemaField("amount", "decimal(12,2)"),
            SchemaField("note", "varchar(255)"),
            SchemaField("flag", "char(1)"),
            SchemaField("spaced", "decimal(10, 2)"),
            SchemaField("scale0", "decimal(10)"),
            SchemaField("big", "varchar(max)"),
            SchemaField("n", "BIGINT"),
            SchemaField("b", "boolean"),
            SchemaField("d", "date"),
            SchemaField("ts", "timestamp"),
            SchemaField("f", "float"),
            SchemaField("dd", "double"),
            SchemaField("t", "tinyint"),
            SchemaField("s", "smallint"),
            SchemaField("str", "string")
        )
        assert(rule(structured(ok, ok), null).isEmpty, "structured new")
        assert(rule(structured(ok, null), null).isEmpty, "source only")
        assert(rule(unstructured(ok, ok), null).isEmpty, "unstructured")
        // moving an odd stored type to a safe one is accepted
        assert(rule(structured(ok, ok), structured(Seq(SchemaField("amount", evil)), Seq(SchemaField("amount", evil)))).isEmpty, "odd stored, safe now")
        // configs without schemas and null configs
        assert(rule(structured(null, null), null).isEmpty, "no schemas")
        assert(rule(PipelineConfig(name = "x"), null).isEmpty, "no source or destination")
        assert(rule(null, null).isEmpty, "null candidate")
        for ((who, r) <- unlimited; out <- restore(who, r, structured(ok, ok).copy(version = 2), null, noProbe))
            assert(out.isEmpty, "safe snapshot " + who + ": " + out)
    }
}
