package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{ScopeDenialFixtures, ScopeDenialWording}
import ai.datris.model._
import ai.datris.util.APIKeyValidator
import jakarta.servlet.http.HttpServletRequest
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

/** Story: API keys, a scope the server does not apply can no longer be put on
  * a key, and stored ones stop granting (plans/stories/api-key-inert-scopes.md),
  * Acceptance, `CollectionScopeSpec` (Fix 2, Decision D2).
  *
  * `collection` is applied on `search:vector` (POST /api/v1/search/{store})
  * and on `document:upload` (POST /api/v1/pipeline/upload). Both entry points
  * go on to the vector store, Mongo and the secret store, so this spec pins the
  * pure checks they run first. The refusal is
  * `PipelineAPIController.capabilityDenied` of the `assertScope` exception,
  * so its body and wording are the shared ones in [[ScopeDenialWording]].
  *
  * Pinned seam (does not exist at d3e13515; the implementation adds it):
  * {{{
  * object SearchAPIController {
  *   // In search(), before searchWriters: assertScope(request, "search", "vector",
  *   // Map("collection" -> CapabilityScopes.collectionName(store, container, schema)))
  *   // mapped to PipelineAPIController.capabilityDenied. No config lookup: a
  *   // refusal must not depend on whether the container exists.
  *   def collectionScopeRefusal(request: HttpServletRequest, store: String, container: String,
  *                              schema: Option[String]): Option[ResponseEntity[String]]
  * }
  * object FileUploadAPIController {
  *   // In uploadRawFile(), right after the config load. In scope when the
  *   // pipeline has at least one vector container (VectorContainers.keysOf)
  *   // and every one is granted.
  *   def uploadScopeRefusal(request: HttpServletRequest, config: PipelineConfig): Option[ResponseEntity[String]]
  * }
  * }}}
  * Wiring note for the implementer: `search` catches `DatrisException` as a
  * 400 and `uploadRawFile` maps every exception to a 500 and writes an error
  * status row; `CapabilityDeniedException` is a `DatrisException`, so the 403
  * has to be returned (or caught) before those handlers. */
class CollectionScopeSpec extends AnyFunSuite {

    private def req(rk: ResolvedKey, uri: String): HttpServletRequest = ScopeDenialFixtures.request(rk, "POST", uri)._1

    private def searchReq(rk: ResolvedKey, store: String): HttpServletRequest = req(rk, "/api/v1/search/" + store)

    private def uploadReq(rk: ResolvedKey): HttpServletRequest = req(rk, "/api/v1/pipeline/upload")

    private def assertDenied(
        r: Option[ResponseEntity[String]],
        label: String,
        resource: String,
        action: String,
        caps: Seq[String],
        hidden: Seq[String]
    ): String = {
        assert(r.isDefined, "expected a 403 refusal")
        assert(r.get.getStatusCode.value == 403, r.get.getBody)
        ScopeDenialWording.assertBody(r.get.getBody, ScopeDenialWording.message(label, resource, action, caps), hidden)
        r.get.getBody
    }

    private val chunking = ChunkingConfig()

    private def pipeline(name: String, d: Destination): PipelineConfig =
        PipelineConfig(name = name, catalog = "Sales", createdByKeyLabel = "someone", destination = d)

    private def qdrant(coll: String) = QdrantConfig(coll, chunking, null, null, null)
    private def chroma(coll: String) = ChromaConfig(coll, chunking, null, null, null)
    private def pgvector(table: String, schema: String) = PGVectorConfig(table, schema, chunking, null, null, "oss/pgvector")

    // ------------------------------------------------------------------ search:vector

    private val searchCaps = Seq("search:vector:collection=ik_docs", "job:read")
    private val searchKey = ScopeDenialFixtures.key("ik_rag", searchCaps: _*)

    test("search:vector:collection=docs searches docs and is 403 capability-denied on any other container, existing or not") {
        Seq("qdrant", "weaviate", "milvus", "chroma", "pgvector").foreach { store =>
            assert(SearchAPIController.collectionScopeRefusal(searchReq(searchKey, store), store, "ik_docs", None).isEmpty, store)
        }
        assert(SearchAPIController.collectionScopeRefusal(searchReq(searchKey, "pgvector"), "pgvector", "ik_docs", Some("public")).isEmpty)

        // ik_other names an existing container in the Verify block, ik_nope none: same answer
        val other = assertDenied(
            SearchAPIController.collectionScopeRefusal(searchReq(searchKey, "qdrant"), "qdrant", "ik_other", None),
            "ik_rag",
            "search",
            "vector",
            searchCaps,
            Seq("ik_other")
        )
        val nope = assertDenied(
            SearchAPIController.collectionScopeRefusal(searchReq(searchKey, "qdrant"), "qdrant", "ik_nope", None),
            "ik_rag",
            "search",
            "vector",
            searchCaps,
            Seq("ik_nope")
        )
        assert(other == nope, "no existence oracle: " + other + " vs " + nope)

        // pgvector: collection=<table> is the public schema; another schema is <schema>.<table>
        assertDenied(
            SearchAPIController.collectionScopeRefusal(searchReq(searchKey, "pgvector"), "pgvector", "ik_docs", Some("other")),
            "ik_rag",
            "search",
            "vector",
            searchCaps,
            Seq("other.ik_docs")
        )
        val schemaKey = ScopeDenialFixtures.key("ik_pg", "search:vector:collection=other.ik_docs")
        assert(SearchAPIController.collectionScopeRefusal(searchReq(schemaKey, "pgvector"), "pgvector", "ik_docs", Some("other")).isEmpty)
        assert(SearchAPIController.collectionScopeRefusal(searchReq(schemaKey, "pgvector"), "pgvector", "ik_docs", None).isDefined)
    }

    test("unscoped search:vector, a session and a legacy key are never refused") {
        val unscoped = ScopeDenialFixtures.key("reader", "search:vector")
        val editor = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
        val viewer = APIKeyValidator.resolveFromSession(User("vi", "h", User.RoleViewer, null, null, null))
        val legacy = ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)
        val wildcard = ScopeDenialFixtures.key("full", "*:*")
        for (rk <- Seq(unscoped, editor, viewer, legacy, wildcard); store <- Seq("qdrant", "pgvector"); c <- Seq("ik_docs", "ik_other", "ik_nope")) {
            assert(SearchAPIController.collectionScopeRefusal(searchReq(rk, store), store, c, Some("other")).isEmpty, s"${rk.label} $store $c")
        }
        // a key holding both the scoped and the unscoped capability is not limited
        val both = ScopeDenialFixtures.key("both", "search:vector:collection=ik_docs", "search:vector")
        assert(SearchAPIController.collectionScopeRefusal(searchReq(both, "qdrant"), "qdrant", "ik_other", None).isEmpty)
        // no identity on the request (auth off probe paths): no-op, as assertScope
        assert(SearchAPIController.collectionScopeRefusal(searchReq(null, "qdrant"), "qdrant", "ik_other", None).isEmpty)
    }

    // ------------------------------------------------------------------ document:upload

    private val uploadCaps = Seq("document:upload:collection=ik_docs", "job:read")
    private val uploadKey = ScopeDenialFixtures.key("ik_rag", uploadCaps: _*)

    test(
        "document:upload:collection=docs uploads into a pipeline whose only vector destination is docs; 403 for another collection, for two destinations when one is not granted, and for a pipeline with no vector destination"
    ) {
        val docs = pipeline("ik_docs_p", Destination(qdrant = qdrant("ik_docs")))
        assert(FileUploadAPIController.uploadScopeRefusal(uploadReq(uploadKey), docs).isEmpty)
        val docsPg = pipeline("ik_docs_pg", Destination(pgvector = pgvector("ik_docs", "public")))
        assert(FileUploadAPIController.uploadScopeRefusal(uploadReq(uploadKey), docsPg).isEmpty)

        val other = pipeline("ik_other_p", Destination(qdrant = qdrant("ik_other")))
        assertDenied(FileUploadAPIController.uploadScopeRefusal(uploadReq(uploadKey), other), "ik_rag", "document", "upload", uploadCaps, Seq("ik_other"))

        val pgOtherSchema = pipeline("ik_pg_s", Destination(pgvector = pgvector("ik_docs", "other")))
        assertDenied(
            FileUploadAPIController.uploadScopeRefusal(uploadReq(uploadKey), pgOtherSchema),
            "ik_rag",
            "document",
            "upload",
            uploadCaps,
            Seq("other.ik_docs")
        )

        val two = pipeline("ik_two_p", Destination(qdrant = qdrant("ik_docs"), chroma = chroma("ik_other")))
        assertDenied(FileUploadAPIController.uploadScopeRefusal(uploadReq(uploadKey), two), "ik_rag", "document", "upload", uploadCaps, Seq("ik_other"))
        // both granted (by two capabilities): in scope
        val bothKey = ScopeDenialFixtures.key("ik_rag2", "document:upload:collection=ik_docs", "document:upload:collection=ik_other")
        assert(FileUploadAPIController.uploadScopeRefusal(uploadReq(bothKey), two).isEmpty)

        val noVector = pipeline("ik_csv_p", Destination(objectStore = null))
        assertDenied(FileUploadAPIController.uploadScopeRefusal(uploadReq(uploadKey), noVector), "ik_rag", "document", "upload", uploadCaps, Nil)
        val noDestination = pipeline("ik_bare_p", null)
        assertDenied(FileUploadAPIController.uploadScopeRefusal(uploadReq(uploadKey), noDestination), "ik_rag", "document", "upload", uploadCaps, Nil)
    }

    test("unscoped document:upload is never refused") {
        val unscoped = ScopeDenialFixtures.key("uploader", "document:upload")
        val both = ScopeDenialFixtures.key("both", "document:upload:collection=ik_docs", "document:upload")
        val legacy = ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)
        val editor = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
        val configs = Seq(
            pipeline("ik_docs_p", Destination(qdrant = qdrant("ik_docs"))),
            pipeline("ik_other_p", Destination(qdrant = qdrant("ik_other"))),
            pipeline("ik_two_p", Destination(qdrant = qdrant("ik_docs"), chroma = chroma("ik_other"))),
            pipeline("ik_csv_p", Destination()),
            pipeline("ik_bare_p", null)
        )
        for (rk <- Seq(unscoped, both, legacy, editor); c <- configs)
            assert(FileUploadAPIController.uploadScopeRefusal(uploadReq(rk), c).isEmpty, s"${rk.label} -> ${c.name}")
        for (c <- configs) assert(FileUploadAPIController.uploadScopeRefusal(uploadReq(null), c).isEmpty, "no identity: " + c.name)
    }
}
