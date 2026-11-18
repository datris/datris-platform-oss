package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, DestinationTargets, SharedDatasets}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import scala.concurrent.duration._

/** Review follow-up: a null or blank pgvector `tableName` has no target, so
  * nothing is probed, yet the delete drops `"public"."null"` or the blank
  * name. For restricted sessions such a save is refused (400) unless the
  * visible stored config carries the identical schema and table. */
class RestrictedPgvectorBlankTableSpec extends AnyFunSuite {

    private def req(rk: ResolvedKey): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/pipeline")
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def bob: ResolvedKey =
        APIKeyValidator.resolveFromSession(
            User("blank_bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "own"))
        )
    private def editor: ResolvedKey = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
    private val fullKey: ResolvedKey = ResolvedKey(None, "full", Nil, isLegacyFullAccess = true)

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    private def pgv(name: String, table: String, catalog: String = "Sales", schema: String = "public"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            destination = Destination(pgvector = PGVectorConfig(table, schema, ChunkingConfig(), null, null, null))
        )

    private class Probe extends (DestinationTargets.Target => Boolean) {
        var calls = 0
        def apply(t: DestinationTargets.Target): Boolean = { calls += 1; throw new AssertionError("probe called for " + t) }
    }

    private def refuse(r: HttpServletRequest, c: PipelineConfig, stored: PipelineConfig, probe: Probe): Option[ResponseEntity[String]] =
        PipelineAPIController.existingDestinationRefusal(r, c, stored, Nil, env, probe, _ => false, 5.seconds, probe)

    private def errorOf(r: ResponseEntity[String]): String =
        JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString

    private val blanks: Seq[String] = Seq(null, " ", "", "   ")

    test("a restricted save of a pgvector pipeline with a null or blank tableName is refused, nothing probed") {
        val probe = new Probe
        for (t <- blanks) {
            val r = refuse(req(bob), pgv("bob_v", t), null, probe)
            assert(r.map(_.getStatusCode.value).contains(400), String.valueOf(t) + ": " + r)
            assert(errorOf(r.get) == DestinationTargets.BlankVectorTableRefusal)
            // also when the stored config had a real table, another schema, or another blank
            assert(refuse(req(bob), pgv("bob_v", t), pgv("bob_v", "chunks"), probe).map(_.getStatusCode.value).contains(400))
            assert(refuse(req(bob), pgv("bob_v", t), pgv("bob_v", t, schema = "vec"), probe).map(_.getStatusCode.value).contains(400))
        }
        assert(refuse(req(bob), pgv("bob_v", null), pgv("bob_v", " "), probe).map(_.getStatusCode.value).contains(400))
        assert(refuse(req(bob), pgv("bob_v", " "), pgv("bob_v", null), probe).map(_.getStatusCode.value).contains(400))
        assert(probe.calls == 0)
    }

    test("a stored visible config with the identical schema and blank table is not refused; a hidden one grandfathers nothing") {
        val probe = new Probe
        for (t <- blanks) {
            assert(refuse(req(bob), pgv("bob_v", t), pgv("bob_v", t), probe).isEmpty, String.valueOf(t))
            assert(refuse(req(bob), pgv("bob_v", t, schema = null), pgv("bob_v", t, schema = null), probe).isEmpty)
            assert(refuse(req(bob), pgv("bob_v", t), pgv("bob_v", t, catalog = "Finance"), probe).map(_.getStatusCode.value).contains(400))
        }
        assert(probe.calls == 0)
    }

    test("the pure checks") {
        assert(DestinationTargets.blankVectorTableName(pgv("v", null)))
        assert(DestinationTargets.blankVectorTableName(pgv("v", " ")))
        assert(!DestinationTargets.blankVectorTableName(pgv("v", "chunks")))
        assert(!DestinationTargets.blankVectorTableName(PipelineConfig(name = "x")))
        assert(!DestinationTargets.blankVectorTableName(null))
        assert(DestinationTargets.sameVectorTableNames(pgv("v", null), pgv("v", null)))
        assert(!DestinationTargets.sameVectorTableNames(pgv("v", null), pgv("v", " ")))
        assert(!DestinationTargets.sameVectorTableNames(pgv("v", null), null))
        assert(!DestinationTargets.sameVectorTableNames(PipelineConfig(name = "x"), PipelineConfig(name = "x")))
    }

    test("unrestricted users, API keys and auth off are not refused") {
        val probe = new Probe
        val unauth = mock(classOf[HttpServletRequest])
        for (r <- Seq(req(editor), req(fullKey), unauth); t <- blanks)
            assert(refuse(r, pgv("x", t), null, probe).isEmpty)
        assert(probe.calls == 0)
    }
}
