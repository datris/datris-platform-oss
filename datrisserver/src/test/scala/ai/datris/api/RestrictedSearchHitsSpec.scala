package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, VectorSearchRegistry}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.Gson
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

/** Story: Catalog-scoped user access: a restricted user's vector search returns
  * only chunks written by pipelines they can see
  * (plans/stories/catalog-scoped-user-access-shared-containers.md), Acceptance,
  * RestrictedSearchHitsSpec.
  *
  * POST /search/{store} reads Mongo and the store, so this spec pins the two
  * companion seams `search` runs on (mock-request fixtures as in
  * VectorContainerRuleSpec). Assumed:
  * {{{
  * object SearchAPIController {
  *   // Right(None) without calling readAll unless the session is restricted; else one
  *   // readAll, Left(containerNotFound(containerParam, container)) when containerVisible
  *   // is false, else Right(Some(visiblePipelines(request, configs).map(_.name).toSet)).
  *   def searchWriters(request: HttpServletRequest, store: String, container: String,
  *                     schema: Option[String],
  *                     readAll: () => Seq[PipelineConfig]): Either[ResponseEntity[String], Option[Set[String]]]
  *   // hits itself for None, else VectorChunkWriter.keepWrittenBy(hits, writers)
  *   def hitsFor(request: HttpServletRequest, hits: java.util.List[java.util.Map[String, Any]],
  *               writers: Option[Set[String]]): java.util.List[java.util.Map[String, Any]]
  * }
  * }}}
  */
class RestrictedSearchHitsSpec extends AnyFunSuite {

    private def req(rk: ResolvedKey, store: String = "qdrant"): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/search/" + store)
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def restrictedSession(g: java.util.Map[String, String]): ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = g))

    private val editorSession: ResolvedKey = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))

    private val fullKey: ResolvedKey = ResolvedKey(None, "full", Nil, isLegacyFullAccess = true)

    private val salesKey: ResolvedKey = ResolvedKey(
        None,
        "sales-builder",
        Capability.parseList(Seq(
            "pipeline:read:catalog=Sales",
            "pipeline:create:catalog=Sales",
            "pipeline:update:catalog=Sales",
            "pipeline:delete:catalog=Sales"
        )),
        isLegacyFullAccess = false
    )

    private val chunking = ChunkingConfig()

    private def pg(name: String, catalog: String, table: String, schema: String = "public"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            destination = Destination(pgvector = PGVectorConfig(table, schema, chunking, null, null, "oss/pgvector"))
        )

    private def qd(name: String, catalog: String, coll: String): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            destination = Destination(qdrant = QdrantConfig(coll, chunking, null, null, null))
        )

    private def bob: HttpServletRequest = req(restrictedSession(grants("Sales" -> "edit")))

    private val boom: () => Seq[PipelineConfig] =
        () => throw new AssertionError("configs must not be read for an unrestricted caller")

    private def counting(configs: Seq[PipelineConfig]): (() => Seq[PipelineConfig], () => Int) = {
        var calls = 0
        (() => { calls += 1; configs }, () => calls)
    }

    private def hit(id: Int, pairs: (String, Any)*): java.util.Map[String, Any] = {
        val m = new java.util.LinkedHashMap[String, Any]()
        m.put("id", id)
        m.put("text", "chunk " + id)
        m.put("score", 0.9)
        pairs.foreach { case (k, v) => m.put(k, v) }
        m
    }

    private def hits(hs: java.util.Map[String, Any]*): java.util.List[java.util.Map[String, Any]] = {
        val l = new java.util.ArrayList[java.util.Map[String, Any]]()
        hs.foreach(l.add)
        l
    }

    private def ids(l: java.util.List[java.util.Map[String, Any]]): Seq[Any] = {
        val out = Seq.newBuilder[Any]
        val it = l.iterator()
        while (it.hasNext) out += it.next().get("id")
        out.result()
    }

    /** The search path: searchWriters, then hitsFor on the store's list. */
    private def searchHits(
        r: HttpServletRequest,
        store: String,
        container: String,
        schema: Option[String],
        configs: Seq[PipelineConfig],
        storeHits: java.util.List[java.util.Map[String, Any]]
    ): java.util.List[java.util.Map[String, Any]] =
        SearchAPIController.searchWriters(r, store, container, schema, () => configs) match {
            case Right(writers) => SearchAPIController.hitsFor(r, storeHits, writers)
            case Left(resp) => fail("expected the search to pass the container check, got " + resp.getStatusCode + " " + resp.getBody)
        }

    test("searchWriters is None for an unrestricted session, a full key and a catalog-scoped key without reading configs") {
        for (rk <- Seq(editorSession, fullKey, salesKey)) {
            assert(SearchAPIController.searchWriters(req(rk), "qdrant", "docs", None, boom) == Right(None), rk.label)
            assert(SearchAPIController.searchWriters(req(rk, "pgvector"), "pgvector", "orphan_x", Some("other"), boom) == Right(None), rk.label)
        }
    }

    test("searchWriters is the existing 404 for a restricted session when no pipeline names the container or a namer is hidden") {
        def assert404(store: String, container: String, schema: Option[String], configs: Seq[PipelineConfig]): Unit = {
            val (readAll, calls) = counting(configs)
            val got = SearchAPIController.searchWriters(req(restrictedSession(grants("Sales" -> "edit")), store), store, container, schema, readAll)
            assert(got.isLeft, s"$store/$container: expected 404, got $got")
            val resp = got.left.toOption.get
            val expected = SearchAPIController.containerNotFound(VectorSearchRegistry.forStore(store).get.containerParam, container)
            assert(resp.getStatusCode.value == 404, resp.getBody)
            assert(resp.getBody == expected.getBody, resp.getBody)
            assert(!resp.getBody.contains("mkt"), resp.getBody)
            assert(!resp.getBody.contains("Marketing"), resp.getBody)
            assert(calls() == 1)
        }

        // no pipeline names the container (orphan, before bob saves onto it)
        assert404("qdrant", "orphan_x", None, Seq(qd("mkt_other", "Marketing", "elsewhere"), qd("sales_a", "Sales", "sales_x")))
        assert404("qdrant", "orphan_x", None, Nil)
        // one namer hidden (grandfathered sharer in another catalog)
        assert404("qdrant", "shared_x", None, Seq(qd("sales_a", "Sales", "shared_x"), qd("mkt_b", "Marketing", "shared_x")))
        // pgvector: the schema is part of the identity
        assert404("pgvector", "orphan_x", None, Seq(pg("sales_a", "Sales", "orphan_x", schema = "other")))
    }

    test("searchWriters for a restricted session is the names of every pipeline it can read, from one config read") {
        val configs = Seq(
            qd("bob_probe", "Sales", "orphan_x"),
            null,
            qd("sales_other", "Sales", "elsewhere"),
            pg("sales_pg", "Sales", "t1"),
            qd("mkt_docs", "Marketing", "mkt_x"),
            qd("hr_docs", "HR", "orphan_y")
        )
        val (readAll, calls) = counting(configs)
        val got = SearchAPIController.searchWriters(bob, "qdrant", "orphan_x", None, readAll)
        assert(got == Right(Some(Set("bob_probe", "sales_other", "sales_pg"))), got)
        assert(calls() == 1, "configs must be read exactly once")

        // a view grant reads too
        val viewer = req(restrictedSession(grants("Sales" -> "view")))
        val (readAll2, calls2) = counting(configs)
        assert(SearchAPIController.searchWriters(viewer, "qdrant", "orphan_x", None, readAll2) == Right(Some(Set("bob_probe", "sales_other", "sales_pg"))))
        assert(calls2() == 1)
    }

    test("hitsFor returns the store's list instance for an unrestricted session and for API keys") {
        val storeHits = hits(
            hit(1, "source_pipeline" -> "mkt_docs"),
            hit(2, "text" -> "no writer"),
            hit(3, "source_pipeline" -> "bob_probe")
        )
        for (rk <- Seq(editorSession, fullKey, salesKey)) {
            val r = req(rk)
            val writers = SearchAPIController.searchWriters(r, "qdrant", "orphan_x", None, boom).toOption.get
            assert(SearchAPIController.hitsFor(r, storeHits, writers) eq storeHits, rk.label)
            assert(SearchAPIController.hitsFor(r, storeHits, None) eq storeHits, rk.label)
        }
        assert(storeHits.size == 3)
    }

    test("a restricted session's pipeline on an orphaned container gets no chunk written by a hidden pipeline, and that pipeline's name is absent") {
        // repoint: mkt2 (Marketing) wrote into mkt2_a and was repointed to mkt2_b;
        // bob then saved bob_probe2 onto the now-unnamed mkt2_a
        val configs = Seq(pg("mkt2", "Marketing", "mkt2_b"), pg("bob_probe2", "Sales", "mkt2_a"))
        val storeHits = hits(
            hit(1, "source_pipeline" -> "mkt2", "filename" -> "a.txt"),
            hit(2, "source_pipeline" -> "mkt2", "filename" -> "b.txt"),
            hit(3, "source_dataset" -> "mkt2")
        )
        val out = searchHits(req(restrictedSession(grants("Sales" -> "edit")), "pgvector"), "pgvector", "mkt2_a", None, configs, storeHits)
        assert(out.isEmpty, out)
        val body = new Gson().toJson(out)
        assert(!body.contains("mkt2"), body)
        assert(!body.contains("Marketing"), body)

        // qdrant: orphan with chunks from a hidden pipeline that still exists elsewhere
        val qConfigs = Seq(qd("mkt_docs", "Marketing", "mkt_new"), qd("bob_probe", "Sales", "orphan_x"))
        val qOut = searchHits(bob, "qdrant", "orphan_x", None, qConfigs, hits(hit(1, "source_pipeline" -> "mkt_docs")))
        assert(qOut.isEmpty)
        assert(!new Gson().toJson(qOut).contains("mkt_docs"))
    }

    test("a restricted session keeps its own pipeline's chunks and loses chunks with no writer") {
        val configs = Seq(qd("bob_probe", "Sales", "orphan_x"), qd("mkt_docs", "Marketing", "mkt_new"))
        val storeHits = hits(
            hit(1, "source_pipeline" -> "bob_probe"),
            hit(2, "text" -> "no writer"),
            hit(3, "source_pipeline" -> "mkt_docs"),
            hit(4, "source_pipeline" -> "bob_probe"),
            hit(5, "source_pipeline" -> null),
            hit(6, "source_pipeline" -> ""),
            hit(7, "source_dataset" -> "bob_probe")
        )
        val out = searchHits(bob, "qdrant", "orphan_x", None, configs, storeHits)
        assert(ids(out) == Seq(1, 4, 7))

        // every hit kept: the store's list comes back as is
        val own = hits(hit(1, "source_pipeline" -> "bob_probe"), hit(2, "source_pipeline" -> "bob_probe"))
        assert(searchHits(bob, "qdrant", "orphan_x", None, configs, own) eq own)
    }

    test("a chunk whose writer no longer exists is dropped for a restricted session") {
        // mkt_docs was a Marketing pipeline; its config is gone, its chunks are not.
        // Even a writer name that looks like it belongs in Sales is dropped when no such pipeline exists.
        val configs = Seq(qd("bob_probe", "Sales", "orphan_x"))
        val storeHits = hits(
            hit(1, "source_pipeline" -> "mkt_docs"),
            hit(2, "source_pipeline" -> "sales_deleted"),
            hit(3, "source_pipeline" -> "bob_probe"),
            hit(4, "source_pipeline" -> "Bob_Probe")
        )
        val out = searchHits(bob, "qdrant", "orphan_x", None, configs, storeHits)
        assert(ids(out) == Seq(3))
        assert(!new Gson().toJson(out).contains("mkt_docs"))
    }
}
