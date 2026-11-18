package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, DatabaseDestinationNames, DestinationTargets, SharedDatasets}
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

/** Story: Postgres: database, schema, table and column names are checked on
  * every save, escaped in every statement, and never rewrite the connection
  * (plans/stories/postgres-identifier-escaping.md), Decision 2.
  *
  * `PipelineAPIController.databaseNameRefusal(candidate, stored)` takes no
  * request: on every save path (POST /pipeline for every caller, and version
  * restore through `EntityVersionAPIController.pipelineRestoreRefusal`,
  * first) a `destination.database` block that fails
  * `DatabaseDestinationNames.problem` is 400 `{"error": <the validator's
  * message>}`, on every pipeline type, unless the stored config has the
  * identical block (`DatabaseDestinationNames.same`). */
class DatabaseNameRuleSpec extends AnyFunSuite {

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

    /** No refused restore may reach a destination probe: the name rule runs first. */
    private val noProbe: DestinationTargets.Target => Boolean = t => throw new AssertionError("probe called for " + t)

    // --------------------------------------------------------------- fixtures

    private def pgDb(dbName: String = "datris", schema: String = "public", table: String = "orders"): Database =
        Database(dbName = dbName, schema = schema, table = table, usePostgres = true)

    /** An unstructured pipeline: one object-store destination (enough for the
      * unstructured validator) plus the database block under test. */
    private def unstructured(db: Database, name: String = "pg_q"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            createdByKeyLabel = "someone",
            source = Source(fileAttributes = FileAttributes(unstructuredAttributes = UnstructuredAttributes(fileExtension = "pdf"))),
            destination = Destination(objectStore = ObjectStore(prefixKey = "docs"), database = db)
        )

    private def structured(db: Database, name: String = "pg_s"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            createdByKeyLabel = "someone",
            source = Source(
                schemaProperties = SchemaProperties(null, java.util.Arrays.asList(SchemaField("id", "int"), SchemaField("name", "string"))),
                fileAttributes = FileAttributes(csvAttributes = CsvAttributes())
            ),
            destination = Destination(
                schemaProperties = SchemaProperties(null, java.util.Arrays.asList(SchemaField("id", "int"), SchemaField("name", "string"))),
                database = db
            )
        )

    private def errorOf(r: ResponseEntity[String]): String =
        JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString

    private def assertRefused(r: Option[ResponseEntity[String]], text: String, clue: String): Unit = {
        assert(r.isDefined, clue + ": not refused")
        assert(r.get.getStatusCode.value == 400, clue + ": " + r)
        assert(errorOf(r.get) == text, clue + ": " + errorOf(r.get))
    }

    private def rule(candidate: PipelineConfig, stored: PipelineConfig): Option[ResponseEntity[String]] =
        PipelineAPIController.databaseNameRefusal(candidate, stored)

    private def restore(
        who: String,
        r: HttpServletRequest,
        snap: PipelineConfig,
        live: PipelineConfig,
        probe: DestinationTargets.Target => Boolean
    ): Seq[Option[ResponseEntity[String]]] = {
        val six = EntityVersionAPIController.pipelineRestoreRefusal(r, snap, live, Nil, env, probe)
        if (who == "restricted session") Seq(six)
        else Seq(EntityVersionAPIController.pipelineRestoreRefusal(r, snap, live, Nil), six)
    }

    private val invalid = "Valid characters are a-z, A-Z, 0-9 and _"
    private val evil = "x\"; DROP TABLE public.canary; --"

    /** (block, the validator's message for it) */
    private val failing: Seq[(String, Database, String)] = Seq(
        ("table quote", pgDb(table = evil), "'destination.database.table' value '" + evil + "' is invalid. " + invalid),
        ("schema quote", pgDb(schema = evil), "'destination.database.schema' value '" + evil + "' is invalid. " + invalid),
        (
            "dbName parameters",
            pgDb(dbName = "datris?user=nobody"),
            "'destination.database.dbName' value 'datris?user=nobody' is invalid. " + invalid
        ),
        ("empty table", pgDb(table = ""), "'destination.database.table' must not be empty"),
        ("null table", pgDb(table = null), "If the 'destination.database' section is defined, the 'destination.database.table' must be defined"),
        (
            "options semicolon",
            pgDb().copy(options = java.util.Arrays.asList("FORMAT csv); DROP TABLE public.canary; --")),
            "destination.database.options entries must not contain ';'"
        ),
        (
            "two engines",
            pgDb().copy(useMongoDB = true),
            "For the 'destination.database' section, you must select exactly one of usePostgres, useMongoDB, useSnowflake, useDatabricks, or useSqlServer — multiple are currently set"
        )
    )

    // ------------------------------------------------------------------ save

    test("an unstructured pipeline with a failing database block is 400 for an unrestricted session, a restricted session, an API key and auth off") {
        for ((what, db, text) <- failing) {
            // the save rule: no request, so the same for every caller
            assertRefused(rule(unstructured(db), null), text, what + " new")
            assertRefused(rule(unstructured(db), unstructured(null)), text, what + " block added")
            assertRefused(rule(unstructured(db), unstructured(pgDb())), text, what + " changed from plain")
            // and through the restore path, per caller (a deleted pipeline: no live config)
            for ((who, r) <- callers; out <- restore(who, r, unstructured(db).copy(version = 2), null, noProbe))
                assertRefused(out, text, what + " " + who)
        }
        // a 64-byte Postgres table: refused, naming the field and the limit
        val long = rule(unstructured(pgDb(table = "a" * 64)), null)
        assert(long.isDefined && long.get.getStatusCode.value == 400, "64-byte table: " + long)
        assert(errorOf(long.get).contains("destination.database.table") && errorOf(long.get).contains("63"), errorOf(long.get))
        // the refusal text is DatabaseDestinationNames.problem's
        for ((what, db, _) <- failing)
            assert(errorOf(rule(unstructured(db), null).get) == DatabaseDestinationNames.problem(db).get, what)
    }

    test("the same block on a structured pipeline is 400 with the same text") {
        for ((what, db, text) <- failing) {
            assertRefused(rule(structured(db), null), text, what + " structured new")
            assertRefused(rule(structured(db), structured(pgDb())), text, what + " structured changed")
            assert(errorOf(rule(structured(db), null).get) == errorOf(rule(unstructured(db), null).get), what)
            for ((who, r) <- callers; out <- restore(who, r, structured(db).copy(version = 2), null, noProbe))
                assertRefused(out, text, what + " structured " + who)
        }
        val long = rule(structured(pgDb(schema = "s" * 64)), null)
        assert(long.isDefined && errorOf(long.get) == errorOf(rule(unstructured(pgDb(schema = "s" * 64)), null).get))
    }

    test("a stored config with the identical block is not refused") {
        for ((what, db, _) <- failing) {
            val stored = unstructured(db)
            // other fields may change: tags, catalog, version, other destinations, key fields
            val edited = stored.copy(
                tags = java.util.Arrays.asList("changed"),
                catalog = "Finance",
                version = 3,
                destination =
                    stored.destination.copy(objectStore = ObjectStore(prefixKey = "docs2"), database = db.copy(keyFields = java.util.Arrays.asList("id")))
            )
            assert(rule(stored, stored).isEmpty, what + " unchanged")
            assert(rule(unstructured(db.copy()), stored).isEmpty, what + " equal copy")
            assert(rule(edited, stored).isEmpty, what + " other fields edited")
            assert(rule(structured(db), structured(db)).isEmpty, what + " structured unchanged")
        }
        // an over-long stored name keeps working when re-saved unchanged
        val long = unstructured(pgDb(table = "a" * 64))
        assert(rule(long, long).isEmpty, "64-byte table unchanged")
    }

    test("changing only the table of such a config to another failing value is refused") {
        val stored = unstructured(pgDb(table = evil))
        val others = Seq(
            "x\"; DROP TABLE public.other; --" -> ("'destination.database.table' value 'x\"; DROP TABLE public.other; --' is invalid. " + invalid),
            "a b" -> ("'destination.database.table' value 'a b' is invalid. " + invalid),
            "" -> "'destination.database.table' must not be empty"
        )
        for ((t, text) <- others)
            assertRefused(rule(unstructured(pgDb(table = t)), stored), text, "[" + t + "]")
        val long = rule(unstructured(pgDb(table = "b" * 64)), unstructured(pgDb(table = "a" * 64)))
        assert(long.isDefined && long.get.getStatusCode.value == 400, "another 64-byte table")
        // and the stored failing name cannot be carried onto another schema either
        assert(rule(unstructured(pgDb(schema = "other", table = evil)), stored).isDefined, "schema changed")
    }

    test("an unstructured pipeline with no database block, and a valid structured pipeline, are not refused") {
        assert(rule(unstructured(null), null).isEmpty, "no block")
        assert(rule(unstructured(null), unstructured(pgDb(table = evil))).isEmpty, "block removed")
        assert(rule(structured(pgDb()), null).isEmpty, "valid structured")
        assert(rule(structured(pgDb(table = "a" * 63)), null).isEmpty, "63-byte table")
        assert(rule(structured(pgDb(table = "orders_v2")), structured(pgDb(table = evil))).isEmpty, "moved to a valid name")
        assert(rule(unstructured(pgDb()), null).isEmpty, "valid block on unstructured")
        assert(rule(PipelineConfig(name = "x"), null).isEmpty, "no destination")
        assert(rule(null, null).isEmpty, "null candidate")
        for ((who, r) <- unlimited; out <- restore(who, r, unstructured(null).copy(version = 2), null, noProbe))
            assert(out.isEmpty, "no block " + who)
    }

    // --------------------------------------------------------------- restore

    test("a version restore of a failing snapshot onto a different or missing live config is refused; onto an identical live block it is not") {
        for ((who, r) <- callers; (what, db, text) <- failing) {
            val snap = unstructured(db).copy(version = 2)
            val clue = what + " " + who
            for (out <- restore(who, r, snap, null, noProbe)) assertRefused(out, text, clue + " missing live")
            for (out <- restore(who, r, snap, unstructured(pgDb()), noProbe)) assertRefused(out, text, clue + " plain live")
            for (out <- restore(who, r, snap, unstructured(null), noProbe)) assertRefused(out, text, clue + " live without block")
            for (out <- restore(who, r, snap, unstructured(db.copy(dbName = "datris2")), noProbe))
                assertRefused(out, text, clue + " live with other dbName")
            // identical live block: nothing new, nothing to probe
            for (out <- restore(who, r, snap, unstructured(db).copy(version = 3), _ => false)) assert(out.isEmpty, clue + " identical live: " + out)
        }
    }
}
