package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{CapabilityRoutes, RouteCheck}
import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.policy.PolicyRoutes
import ai.datris.util.{APIKeyValidator, PipelineRename}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.{JsonObject, JsonParser}
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import scala.collection.JavaConverters._

/** Story: Pipeline rename keeps its data
  * (plans/stories/pipeline-rename-keeps-data.md), Acceptance, PipelineRenameApiSpec.
  * Grant level per the story's D1 (LEAD OVERRIDE): rename needs OWN on the
  * pipeline's catalog (capability `pipeline:delete`), not edit.
  *
  * The handler reads and writes Mongo, so this spec pins the pure seams it
  * runs on the loaded data (mock-request fixtures as in
  * RestrictedSessionVisibilitySpec / VectorContainerRuleSpec). Assumed:
  * {{{
  * object PipelineAPIController {
  *   // POST /pipelines/{name}/rename, Steps 2 (before anything is written), in this order:
  *   // old == null -> 404 pipelineNotFound(name); scope: refusalFor(request, name, old, "delete")
  *   // (hidden -> the same 404, visible without own -> 403) and, for API keys,
  *   // CapabilityCheck.assertVisible(request, "pipeline", "delete", scopeContext(...)) -> 403;
  *   // PipelineRename.nameProblem -> 400; Unity Catalog (D4) -> 400; running job (D5) -> 409;
  *   // existingNew != null && !PipelineRename.resumable(old, existingNew) -> 409 {"error"}.
  *   // None = go ahead. Never consults the vector-container or authority rules.
  *   def renameRefusal(request: HttpServletRequest, name: String, newName: String,
  *                     old: PipelineConfig, existingNew: PipelineConfig,
  *                     jobs: Seq[(String, JobState)], ucStateExists: Boolean): Option[ResponseEntity[String]]
  *   // Step 5 body {name, previousName, version, repointedTaps, failed, warnings};
  *   // repointedTaps names only taps the caller can read (tap:read on catalog/owner);
  *   // failed is (tap name, error), passed through as given.
  *   def renameResponseBody(request: HttpServletRequest, renamed: PipelineConfig, previousName: String,
  *                          repointed: Seq[TapConfig], failed: Seq[(String, String)],
  *                          warnings: Seq[String]): JsonObject
  * }
  * }}}
  */
class PipelineRenameApiSpec extends AnyFunSuite {

    private def req(rk: ResolvedKey, path: String = "/api/v1/pipelines/rn_a/rename"): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn(path)
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def restrictedSession(g: java.util.Map[String, String]): ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = g))

    private val editorSession: ResolvedKey = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))

    private val fullKey: ResolvedKey = ResolvedKey(None, "full", Nil, isLegacyFullAccess = true)

    private def salesKey(actions: String*): ResolvedKey =
        ResolvedKey(None, "sales-key", Capability.parseList(actions.map(a => "pipeline:" + a + ":catalog=Sales")), isLegacyFullAccess = false)

    private val chunking = ChunkingConfig()

    private def postgres(name: String, catalog: String = "Sales", table: String = "rn_rows"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            destination = Destination(database = Database(dbName = "datris", schema = "public", table = table, usePostgres = true))
        )

    private def vectors(name: String): Seq[PipelineConfig] = {
        def p(d: Destination) = PipelineConfig(name = name, catalog = "Sales", createdByKeyLabel = "someone", destination = d)
        Seq(
            p(Destination(pgvector = PGVectorConfig("rn_chunks", "public", chunking, null, null, "oss/pgvector"))),
            p(Destination(qdrant = QdrantConfig("rn_docs", chunking, null, null, null))),
            p(Destination(weaviate = WeaviateConfig("RnDocs", chunking, null, null, null))),
            p(Destination(milvus = MilvusConfig("rn_docs", chunking, null, null, null))),
            p(Destination(chroma = ChromaConfig("rn_docs", chunking, null, null, null)))
        )
    }

    private def tap(name: String, target: String, catalog: String): TapConfig =
        TapConfig(name = name, description = "t", targetPipeline = target, catalog = catalog, enabled = false, createdByKeyLabel = "someone")

    private def refusal(rk: ResolvedKey, old: PipelineConfig, newName: String = "rn_b", existingNew: PipelineConfig = null, name: String = null) =
        PipelineAPIController.renameRefusal(
            req(rk),
            if (name != null) name else old.name,
            newName,
            old,
            existingNew,
            Nil,
            ucStateExists = false
        )

    private def status(r: Option[ResponseEntity[String]]): Int = {
        assert(r.isDefined, "expected a refusal")
        r.get.getStatusCode.value
    }

    private def errorOf(r: ResponseEntity[String]): String =
        JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString

    // ------------------------------------------------------------------

    test("a renamed vector pipeline is not refused by the one-container rule against its own old name") {
        for (old <- vectors("rn_v")) {
            val renamed = PipelineRename.renamedConfig(old, "rn_w")
            // today's create-then-delete: the POST under the new name collides with the old name's container
            assert(
                PipelineAPIController.vectorContainerRefusal(req(fullKey), renamed, null, Seq(old)).isDefined,
                "precondition: the save path refuses the new name"
            )
            for (rk <- Seq(fullKey, editorSession, salesKey("read", "update", "delete"), restrictedSession(grants("Sales" -> "own")))) {
                val r = PipelineAPIController.renameRefusal(req(rk), "rn_v", "rn_w", old, null, Nil, ucStateExists = false)
                assert(r.isEmpty, rk.label + " " + r.map(_.getBody))
            }
        }
    }

    test("a renamed default-authoritative pipeline is not refused by the authority check against its own old name") {
        val old = postgres("rn_a") // one destination, authoritative unset: the authoritative writer
        val renamed = PipelineRename.renamedConfig(old, "rn_b")
        assert(PipelineAPIController.authorityRefusal(req(fullKey), renamed, Seq(old)).isDefined, "precondition: the save path refuses the new name")
        for (rk <- Seq(fullKey, editorSession, salesKey("read", "update", "delete"), restrictedSession(grants("Sales" -> "own")))) {
            val r = refusal(rk, old)
            assert(r.isEmpty, rk.label + " " + r.map(_.getBody))
        }
        // a multi-destination pipeline that declares its authoritative kind
        val multi = old.copy(destination =
            old.destination.copy(objectStore = ObjectStore(prefixKey = "rn"), authoritative = "postgres")
        )
        assert(refusal(fullKey, multi).isEmpty)
    }

    test("a re-save under the new name after the rename passes both checks") {
        val unrelated = postgres("someone_else", table = "elsewhere")
        val structured = PipelineRename.renamedConfig(postgres("rn_a"), "rn_b")
        val vector = PipelineRename.renamedConfig(vectors("rn_v").head, "rn_w")
        for (renamed <- Seq(structured, vector) ++ vectors("rn_v").tail.map(PipelineRename.renamedConfig(_, "rn_w"))) {
            // after the rename the stored config is the renamed one and the old row is gone
            val others = Seq(renamed, unrelated)
            for (rk <- Seq(fullKey, editorSession, restrictedSession(grants("Sales" -> "own")))) {
                assert(PipelineAPIController.vectorContainerRefusal(req(rk, "/api/v1/pipeline"), renamed, renamed, others).isEmpty, renamed.toString)
                assert(PipelineAPIController.authorityRefusal(req(rk, "/api/v1/pipeline"), renamed, others).isEmpty, renamed.toString)
            }
        }
    }

    test("an existing new name is 409 and nothing is written") {
        val old = postgres("rn_b")
        val taken = postgres("rn_c", table = "rn_other")
        val before = (old, taken)
        val r = refusal(fullKey, old, newName = "rn_c", existingNew = taken)
        assert(status(r) == 409, r.get.getBody)
        assert(errorOf(r.get).contains("rn_c"), r.get.getBody)
        assert((old, taken) == before)

        // the same for every caller allowed to rename
        for (rk <- Seq(editorSession, restrictedSession(grants("Sales" -> "own")), salesKey("read", "update", "delete")))
            assert(status(refusal(rk, old, newName = "rn_c", existingNew = taken)) == 409, rk.label)

        // a rename that stopped half way (the new row is the old config under the new name) is continued
        val halfWay = PipelineRename.renamedConfig(old, "rn_c").copy(version = old.version + 1)
        assert(refusal(fullKey, old, newName = "rn_c", existingNew = halfWay).isEmpty)
    }

    test("a restricted session with own renames; edit-only and view-only are 403; a pipeline outside its grants is 404 with the missing-pipeline body") {
        val sales = postgres("rn_b")
        val mkt = postgres("mkt_p", catalog = "Marketing", table = "mkt_rows")

        assert(refusal(restrictedSession(grants("Sales" -> "own")), sales, newName = "rn_d").isEmpty)

        for (level <- Seq("edit", "view")) {
            val r = refusal(restrictedSession(grants("Sales" -> level)), sales, newName = "rn_d")
            assert(status(r) == 403, level + ": " + r.get.getBody)
            // scope is checked before the name: a bad name still answers 403
            assert(status(refusal(restrictedSession(grants("Sales" -> level)), sales, newName = "rn_b")) == 403, level)
        }

        val missingBody = PipelineAPIController.pipelineNotFound("mkt_p").getBody
        for (g <- Seq(grants("Sales" -> "own"), grants("Sales" -> "edit"), grants("Sales" -> "own", "HR" -> "view"))) {
            val hidden = refusal(restrictedSession(g), mkt, newName = "x")
            assert(status(hidden) == 404, hidden.get.getBody)
            assert(hidden.get.getBody == missingBody, "hidden must be indistinguishable from missing: " + hidden.get.getBody)
            // hidden wins over a taken name and a bad name
            assert(refusal(restrictedSession(g), mkt, newName = "rn_b", existingNew = sales).get.getBody == missingBody)
            assert(refusal(restrictedSession(g), mkt, newName = "mkt_p").get.getBody == missingBody)
        }

        // a missing pipeline is the same 404
        val missing = PipelineAPIController.renameRefusal(req(fullKey), "mkt_p", "x", null, null, Nil, ucStateExists = false)
        assert(status(missing) == 404)
        assert(missing.get.getBody == missingBody)

        // an Uncataloged pipeline is hidden from a restricted session
        assert(status(refusal(restrictedSession(grants("Sales" -> "own")), postgres("loose", catalog = null))) == 404)

        // API keys need pipeline:delete in scope; a scope miss is 403, not 404
        assert(status(refusal(salesKey("read", "update"), sales, newName = "rn_d")) == 403)
        assert(refusal(salesKey("read", "update", "delete"), sales, newName = "rn_d").isEmpty)
        assert(status(refusal(salesKey("read", "update", "delete"), mkt, newName = "x")) == 403)

        // unrestricted sessions and full keys are unchanged
        assert(refusal(editorSession, mkt, newName = "x").isEmpty)
        assert(refusal(fullKey, mkt, newName = "x").isEmpty)
    }

    test("a tap the caller cannot see is re-pointed and not named in the response") {
        val old = postgres("rn_a")
        val renamed = PipelineRename.renamedConfig(old, "rn_b").copy(version = old.version + 1)
        val taps = Seq(tap("sales_tap", "rn_a", "Sales"), tap("hr_tap", "rn_a", "HR"), tap("other_tap", "rn_ab", "Sales"))

        val toMove = PipelineRename.tapsToRepoint(taps, "rn_a")
        assert(toMove.map(_.name).toSet == Set("sales_tap", "hr_tap"), "every tap feeding the pipeline follows it, visible or not")
        assert(toMove.map(PipelineRename.repointed(_, "rn_b")).forall(_.targetPipeline == "rn_b"))
        assert(PipelineRename.repointed(toMove.find(_.name == "hr_tap").get, "rn_b").catalog == "HR")

        val bob = req(restrictedSession(grants("Sales" -> "own")))
        val body = PipelineAPIController.renameResponseBody(bob, renamed, "rn_a", toMove, Nil, Nil)
        assert(body.get("name").getAsString == "rn_b")
        assert(body.get("previousName").getAsString == "rn_a")
        assert(body.get("version").getAsInt == renamed.version)
        assert(body.getAsJsonArray("repointedTaps").asScala.map(_.getAsString).toSeq == Seq("sales_tap"), body.toString)
        assert(body.getAsJsonArray("failed").size == 0)
        assert(body.getAsJsonArray("warnings").size == 0)
        assert(!body.toString.contains("hr_tap"), body.toString)
        assert(!body.toString.contains("HR"), body.toString)

        // an admin sees both
        val admin = PipelineAPIController.renameResponseBody(req(fullKey), renamed, "rn_a", toMove, Nil, Seq("w1"))
        assert(admin.getAsJsonArray("repointedTaps").asScala.map(_.getAsString).toSet == Set("sales_tap", "hr_tap"))
        assert(admin.getAsJsonArray("warnings").asScala.map(_.getAsString).toSeq == Seq("w1"))
    }

    test("POST /api/v1/pipelines/x/rename maps to pipeline:delete and the policy key pipeline:delete:rename") {
        assert(CapabilityRoutes.lookup("POST", "/api/v1/pipelines/x/rename") == RouteCheck.Require("pipeline", "delete"))
        assert(PolicyRoutes.actionKey("POST", "/api/v1/pipelines/x/rename").contains("pipeline:delete:rename"))
        assert(PolicyRoutes.isKnownActionKey("pipeline:delete:rename"))
        assert(PolicyRoutes.isGateable("pipeline:delete:rename"))
        // neighbours unchanged
        assert(CapabilityRoutes.lookup("POST", "/api/v1/pipeline") == RouteCheck.Require("pipeline", "create"))
        assert(CapabilityRoutes.lookup("DELETE", "/api/v1/pipeline") == RouteCheck.Require("pipeline", "delete"))
        assert(PolicyRoutes.actionKey("DELETE", "/api/v1/pipeline").contains("pipeline:delete"))
    }
}
