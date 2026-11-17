package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, LineageService, VectorContainers}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite

/** The source-of-authority save refusal names the other pipeline only when
  * the caller can see it (same rule as the vector container refusal); the
  * vector refusal shows a weaviate class as written; a grandfathered pair
  * (one side authoritative=false) re-saves unchanged on both sides. */
class AuthorityRefusalVisibilitySpec extends AnyFunSuite {

    private def req(rk: ResolvedKey): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/pipeline")
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private val bob = req(
        APIKeyValidator.resolveFromSession(
            User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit"))
        )
    )
    private val editor = req(APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null)))
    private val full = req(ResolvedKey(None, "full", Nil, isLegacyFullAccess = true))
    private val salesKey = req(
        ResolvedKey(
            None,
            "sales-builder",
            Capability.parseList(Seq("pipeline:read:catalog=Sales", "pipeline:create:catalog=Sales", "pipeline:update:catalog=Sales")),
            isLegacyFullAccess = false
        )
    )

    private def pgTable(name: String, catalog: String, table: String, authoritative: java.lang.Boolean = null): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            authoritative = authoritative,
            destination = Destination(database = Database(dbName = "datris", schema = "public", table = table, usePostgres = true))
        )

    private def pgv(name: String, catalog: String, table: String, authoritative: java.lang.Boolean = null): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            authoritative = authoritative,
            destination = Destination(pgvector = PGVectorConfig(table, "public", ChunkingConfig(), null, null, null))
        )

    private def errorOf(r: Option[org.springframework.http.ResponseEntity[String]]): String = {
        assert(r.isDefined, "expected a refusal")
        assert(r.get.getStatusCode.value == 400, r.get.getBody)
        JsonParser.parseString(r.get.getBody).getAsJsonObject.get("error").getAsString
    }

    test("a hidden authoritative writer is not named in the authority refusal") {
        val others = Seq(pgTable("hr_orders", "HR", "orders"))
        for (r <- Seq(bob, salesKey)) {
            val res = PipelineAPIController.authorityRefusal(r, pgTable("probe", "Sales", "orders"), others)
            val err = errorOf(res)
            assert(err.startsWith("another pipeline is already the authoritative writer of "), err)
            assert(!res.get.getBody.contains("hr_orders"), res.get.getBody)
            assert(!res.get.getBody.contains("HR"), res.get.getBody)
        }
    }

    test("a visible authoritative writer, and any unrestricted caller, get today's text unchanged") {
        val probe = pgTable("probe", "Sales", "orders")
        val legacy = LineageService.authorityConflict(probe, List(pgTable("sales_orders", "Sales", "orders"))).get
        assert(legacy.startsWith("pipeline 'sales_orders' is already the authoritative writer of "), legacy)
        for (r <- Seq(bob, salesKey, editor, full)) {
            val res = PipelineAPIController.authorityRefusal(r, probe, Seq(pgTable("sales_orders", "Sales", "orders")))
            assert(res.get.getBody == "{\"error\":\"" + legacy.replace("\"", "'") + "\"}", res.get.getBody)
        }
        val hrLegacy = LineageService.authorityConflict(probe, List(pgTable("hr_orders", "HR", "orders"))).get
        for (r <- Seq(editor, full)) {
            val res = PipelineAPIController.authorityRefusal(r, probe, Seq(pgTable("hr_orders", "HR", "orders")))
            assert(res.get.getBody == "{\"error\":\"" + hrLegacy.replace("\"", "'") + "\"}", res.get.getBody)
        }
        // an own-declaration problem names no other pipeline and is unchanged for everyone
        val badDecl = probe.copy(destination = probe.destination.copy(authoritative = "snowflake"))
        val own = LineageService.authorityConflict(badDecl, Nil).get
        assert(errorOf(PipelineAPIController.authorityRefusal(bob, badDecl, Nil)) == own)
        assert(PipelineAPIController.authorityRefusal(full, pgTable("probe", "Sales", "elsewhere"), Seq(pgTable("x", "HR", "orders"))).isEmpty)
    }

    test("a default-authoritative vector collision is refused by the vector rule with no hidden name") {
        val others = Seq(pgv("mkt_pipeline", "Marketing", "mkt_chunks"))
        val probe = pgv("bob_probe", "Sales", "mkt_chunks")
        // both checks object; savePipelineWithBody runs the vector one first
        assert(LineageService.authorityProblem(probe, others.toList).isDefined)
        val err = errorOf(PipelineAPIController.vectorContainerRefusal(bob, probe, null, others))
        assert(err.contains("already in use by another pipeline"), err)
        assert(!err.contains("mkt_pipeline"), err)
        // and the authority text that would follow is name-free too
        assert(!PipelineAPIController.authorityRefusal(bob, probe, others).get.getBody.contains("mkt_pipeline"))
    }

    test("a grandfathered sharer pair with one side authoritative=false re-saves unchanged on both sides") {
        val a = pgv("vc_a", "Sales", "vc_x")
        val seed = pgv("vc_seed", "Sales", "vc_x", authoritative = java.lang.Boolean.FALSE)
        val others = Seq(a, seed)
        for (r <- Seq(full, editor, bob)) {
            assert(PipelineAPIController.vectorContainerRefusal(r, seed, seed, others).isEmpty)
            assert(PipelineAPIController.authorityRefusal(r, seed, others).isEmpty)
            assert(PipelineAPIController.vectorContainerRefusal(r, a, a, others).isEmpty)
            assert(PipelineAPIController.authorityRefusal(r, a, others).isEmpty)
        }
    }

    test("the vector refusal and kept-on-delete warning print a weaviate class as written") {
        def wv(name: String, cls: String) =
            PipelineConfig(
                name = name,
                catalog = "Sales",
                destination = Destination(weaviate = WeaviateConfig(cls, ChunkingConfig(), null, null, null)),
                authoritative = java.lang.Boolean.FALSE
            )
        val err = errorOf(PipelineAPIController.vectorContainerRefusal(full, wv("b", "E2E_Docs"), null, Seq(wv("a", "e2e_docs"))))
        assert(err.contains("weaviate class E2E_Docs"), err)
        val kept = PipelineAPIController.vectorDeleteWarnings(full, wv("a", "MyDocs"), Seq(wv("a", "MyDocs"), wv("b", "mydocs")))
        assert(kept.size == 1 && kept.head.contains("weaviate class MyDocs"), kept)
        // identity is unchanged
        val k = VectorContainers.key("weaviate", "E2E_Docs", None).get
        assert(k.name == "e2e_docs")
        assert(VectorContainers.displayKey(k, wv("b", "E2E_Docs")).name == "E2E_Docs")
        assert(VectorContainers.displayKey(k, wv("b", "Other")) == k)
    }
}
