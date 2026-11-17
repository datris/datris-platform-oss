package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditClassifier
import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, PipelineAliases, PipelineRename}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.{JsonObject, JsonParser}
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Review follow-ups to the pipeline rename, API side: version copy,
  * audit metadata and action, resume evidence at the refusal, and the
  * previous-name rule on search and activity visibility. */
class PipelineRenameFollowUpApiSpec extends AnyFunSuite {

    private def req(rk: ResolvedKey): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/search/pgvector")
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def bob(g: java.util.Map[String, String]): ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = g))

    private val fullKey: ResolvedKey = ResolvedKey(None, "full", Nil, isLegacyFullAccess = true)

    private def pg(name: String, catalog: String = "Sales", prev: Seq[String] = null): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            destination = Destination(database = Database(dbName = "datris", schema = "public", table = "rows", usePostgres = true)),
            previousNames = if (prev == null) null else new java.util.ArrayList[String](prev.asJava)
        )

    private def vec(name: String, table: String, catalog: String = "Sales", prev: Seq[String] = null): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            destination = Destination(pgvector = PGVectorConfig(table, "public", ChunkingConfig(), null, null, "oss/pgvector")),
            previousNames = if (prev == null) null else new java.util.ArrayList[String](prev.asJava)
        )

    test("copyVersionsForRenameWith re-keys every row, skips keys already there, and seeds a pre-versioning pipeline") {
        val store = mutable.ListBuffer[EntityVersion]()
        val list: String => Seq[EntityVersion] = n => store.filter(_.entityName == n).toList
        val old = pg("a").copy(version = 2)
        store += EntityVersion("a|1", "a", 1, new com.google.gson.Gson().toJson(old.copy(version = 1)), changeNote = "created")
        store += EntityVersion("a|2", "a", 2, new com.google.gson.Gson().toJson(old), changeNote = "updated")
        PipelineAPIController.copyVersionsForRenameWith(old, "b", list, store += _)
        assert(list("b").map(_.key) == Seq("b|1", "b|2"))
        assert(list("b").forall(v => JsonParser.parseString(v.config).getAsJsonObject.get("name").getAsString == "b"))
        // resumed: nothing duplicated
        PipelineAPIController.copyVersionsForRenameWith(old, "b", list, store += _)
        assert(list("b").size == 2)

        // no rows (saved before versioning): one seed at the current version
        val legacy = pg("l").copy(version = 4)
        PipelineAPIController.copyVersionsForRenameWith(legacy, "m", list, store += _)
        val seeded = list("m")
        assert(seeded.map(v => (v.key, v.version, v.changeNote)) == Seq(("m|4", 4, PipelineRename.seedNote("l"))))
        // and that seed is evidence for resuming this rename
        assert(PipelineRename.priorAttempt("l", "m", seeded, Nil))
    }

    test("renameAuditMetadata adds operation and newName and keeps metadata already set") {
        val existing = new JsonObject
        existing.addProperty("approvalId", "ap-1")
        val m = PipelineAPIController.renameAuditMetadata(existing, "rn_b")
        assert(m.get("approvalId").getAsString == "ap-1")
        assert(m.get("operation").getAsString == "rename" && m.get("newName").getAsString == "rn_b")
        val fresh = PipelineAPIController.renameAuditMetadata(null, null)
        assert(fresh.get("operation").getAsString == "rename" && !fresh.has("newName"))
        assert(PipelineAPIController.renameAuditMetadata("not json", "x").get("newName").getAsString == "x")
    }

    test("the audit log records the rename route as pipeline / rename, and a pipeline delete still as delete") {
        val rename = AuditClassifier.classify("POST", "/api/v1/pipelines/rn_a/rename", logReads = false)
        assert(rename.map(r => (r.category, r.action, r.resourceType)).contains(("pipeline", "rename", "pipeline")), s"$rename")
        val delete = AuditClassifier.classify("DELETE", "/api/v1/pipeline", logReads = false)
        assert(delete.map(_.action).contains("delete"), s"$delete")
        assert(AuditClassifier.classify("POST", "/api/v1/pipeline", logReads = false).map(_.action).contains("create"))
    }

    test("an equal config under the new name without evidence of an earlier attempt is 409, not merged") {
        val a = pg("a")
        val b = pg("b") // unrelated, identical apart from the name
        val r = PipelineAPIController.renameRefusal(req(fullKey), "a", "b", a, b, Nil, ucStateExists = false, priorAttempt = false)
        assert(r.isDefined && r.get.getStatusCode.value == 409, s"$r")
        assert(JsonParser.parseString(r.get.getBody).getAsJsonObject.get("error").getAsString.contains("'b'"))
        // with evidence it is continued
        val half = PipelineRename.renamedConfig(a, "b").copy(previousNames = PipelineAliases.afterRename(a, "b"))
        assert(PipelineAPIController.renameRefusal(req(fullKey), "a", "b", a, half, Nil, ucStateExists = false, priorAttempt = true).isEmpty)
    }

    test("search: a restricted session keeps a renamed pipeline's chunks, only for the container that pipeline names") {
        val renamed = vec("docs_b", "chunks", prev = Seq("docs_a"))
        val elsewhere = vec("other", "other_chunks", prev = Seq("other_old"))
        val hidden = vec("h", "h_chunks", catalog = "HR", prev = Seq("h_old"))
        val all = Seq(renamed, elsewhere, hidden)
        val w = SearchAPIController.searchWriters(req(bob(grants("Sales" -> "view"))), "pgvector", "chunks", None, () => all)
        val names = w.right.get.get
        assert(names.contains("docs_a") && names.contains("docs_b"), s"$names")
        assert(!names.contains("other_old"), "an old name of a pipeline on another container does not widen results")
        assert(!names.contains("h_old") && !names.contains("h"))

        val hits = new java.util.ArrayList[java.util.Map[String, Any]]()
        def hit(p: String): java.util.Map[String, Any] = { val m = new java.util.HashMap[String, Any](); m.put("source_pipeline", p); m }
        hits.add(hit("docs_a")); hits.add(hit("docs_b")); hits.add(hit("h_old"))
        assert(SearchAPIController.hitsFor(req(bob(grants("Sales" -> "view"))), hits, w.right.get).size == 2)

        // a pipeline later created under the old name (outside the grants) wins its chunks
        val newA = vec("docs_a", "chunks", catalog = "HR")
        val w2 = SearchAPIController.searchWriters(req(bob(grants("Sales" -> "view", "HR" -> "view"))), "pgvector", "chunks", None, () => all :+ newA)
        assert(w2.isRight)
        val w3 = SearchAPIController.searchWriters(req(bob(grants("Sales" -> "view"))), "pgvector", "chunks", None, () => all :+ newA)
        assert(w3.isLeft, "the container is now named by a pipeline outside the grants")
        // unrestricted callers are unchanged
        assert(SearchAPIController.searchWriters(req(fullKey), "pgvector", "chunks", None, () => all) == Right(None))
    }

    test("activity: record catalogs map a renamed pipeline's earlier name to its catalog; a name reused by a live pipeline is that pipeline's") {
        val renamed = pg("b", prev = Seq("a"))
        val cats = ActivityAPIController.recordCatalogs(Seq(renamed, pg("h", catalog = "HR")))
        assert(cats.get("a").contains("Sales") && cats.get("b").contains("Sales") && cats.get("h").contains("HR"))
        val reused = ActivityAPIController.recordCatalogs(Seq(renamed, pg("a", catalog = "HR")))
        assert(reused.get("a").contains("HR"))
    }
}
