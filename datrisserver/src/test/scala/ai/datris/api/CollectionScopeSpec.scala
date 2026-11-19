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

    // ------------------------------------------------------------------ review follow-ups
    // (added: the opt-out and log-only keep their v1.60.0 answer on these two
    // routes; names are checked as the loaders and the search use them)

    private def inMode[A](value: Option[String])(body: => A): A = {
        val prop = ai.datris.auth.CapabilityScopes.InertScopesProperty
        val previous = sys.props.get(prop)
        value match {
            case Some(v) => sys.props(prop) = v
            case None => sys.props -= prop
        }
        try body
        finally previous match {
                case Some(v) => sys.props(prop) = v
                case None => sys.props -= prop
            }
    }

    private def refuseMode[A](body: => A): A = inMode(None) {
        assume(sys.env.get(ai.datris.auth.CapabilityScopes.InertScopesEnv).isEmpty, "DATRIS_KEY_INERT_SCOPES is set in this environment")
        body
    }
    private def allowMode[A](body: => A): A = inMode(Some("allow"))(body)

    /** A stored key as APIKeyValidator.resolveAgainst builds it. */
    private def stored(label: String, raw: String*): ResolvedKey = {
        val (kept, inert) = ai.datris.auth.CapabilityScopes.partition(Capability.parseList(raw))
        ResolvedKey(None, label, kept, isLegacyFullAccess = false, inertCapabilities = inert)
    }

    /** Runs the real CapabilityInterceptor and then the route's refusal helper
      * on the same request, with the audit test sink. Returns (gate passed,
      * gate status, helper refusal, inert-scope-used rows, denied rows). */
    private def route(
        rk: ResolvedKey,
        uri: String
    )(helper: HttpServletRequest => Option[ResponseEntity[String]]): (Boolean, Int, Option[ResponseEntity[String]], Int, Int) = {
        import org.mockito.ArgumentMatchers.anyInt
        import org.mockito.Mockito.{doAnswer, mock, when}
        val saved = DatrisEnvironment.values
        val captured = scala.collection.mutable.ArrayBuffer[ai.datris.audit.AuditEntry]()
        ai.datris.audit.AuditLog.sinkOverride = (e: ai.datris.audit.AuditEntry) => captured.synchronized { captured += e; () }
        ScopeDenialFixtures.install(ScopeDenialFixtures.auditEnv())
        try {
            val r = req(rk, uri)
            val writer = new java.io.PrintWriter(new java.io.StringWriter())
            var status = 200
            val resp = mock(classOf[jakarta.servlet.http.HttpServletResponse])
            when(resp.getWriter).thenReturn(writer)
            doAnswer { i => status = i.getArgument[Int](0); null }.when(resp).setStatus(anyInt())
            val passed = new ai.datris.config.CapabilityInterceptor().preHandle(r, resp, null)
            val refusal = if (passed) helper(r) else None
            val rows = captured.synchronized(captured.toList)
            (
                passed,
                status,
                refusal,
                rows.count(e => e.category == "security" && e.action == "inert-scope-used"),
                rows.count(e => e.category == "security" && e.action == "denied")
            )
        } finally {
            ai.datris.audit.AuditLog.sinkOverride = null
            ScopeDenialFixtures.install(saved)
        }
    }

    private def enforceOn(): Unit =
        assume(!sys.env.get("CAPABILITY_ENFORCEMENT").exists(_.equalsIgnoreCase("log-only")), "CAPABILITY_ENFORCEMENT=log-only is set")

    private val docsPipeline = pipeline("ik_docs_p", Destination(qdrant = qdrant("ik_docs")))
    private val otherPipeline = pipeline("ik_other_p", Destination(qdrant = qdrant("ik_other")))

    test(
        "a key whose only search or upload capability is inert: refused at the gate by default; under the opt-out it passes as in v1.60.0, with one inert-scope-used row and no later refusal"
    ) {
        enforceOn()
        val searchInert = Seq(stored("ik_s1", "search:vector:database=x"), stored("ik_s2", "*:*:catalog=Sales"))
        val uploadInert = Seq(stored("ik_u1", "document:upload:destination_kind=vector"), stored("ik_u2", "*:*:catalog=Sales"))
        def search(rk: ResolvedKey) = route(rk, "/api/v1/search/qdrant")(r => SearchAPIController.collectionScopeRefusal(r, "qdrant", "ik_other", None))
        def upload(rk: ResolvedKey) = route(rk, "/api/v1/pipeline/upload")(r => FileUploadAPIController.uploadScopeRefusal(r, otherPipeline))

        refuseMode {
            for (rk <- searchInert) {
                val (passed, status, _, used, denied) = search(rk)
                assert(!passed && status == 403 && used == 0 && denied == 1, rk.label + " search")
            }
            for (rk <- uploadInert) {
                val (passed, status, _, used, denied) = upload(rk)
                assert(!passed && status == 403 && used == 0 && denied == 1, rk.label + " upload")
            }
            // the helpers defer to the gate: they never add a second refusal
            for (rk <- searchInert) assert(SearchAPIController.collectionScopeRefusal(searchReq(rk, "qdrant"), "qdrant", "ik_other", None).isEmpty)
            for (rk <- uploadInert) assert(FileUploadAPIController.uploadScopeRefusal(uploadReq(rk), otherPipeline).isEmpty)
        }
        allowMode {
            for (rk <- searchInert) {
                val (passed, _, refusal, used, denied) = search(rk)
                assert(passed && refusal.isEmpty && used == 1 && denied == 0, rk.label + " search: " + refusal.map(_.getBody))
            }
            for (rk <- uploadInert) {
                val (passed, _, refusal, used, denied) = upload(rk)
                assert(passed && refusal.isEmpty && used == 1 && denied == 0, rk.label + " upload: " + refusal.map(_.getBody))
            }
        }
    }

    test("an applied collection scope beside an inert capability is enforced in both modes") {
        val sCaps = Seq("search:vector:collection=ik_docs", "search:vector:database=x")
        val uCaps = Seq("document:upload:collection=ik_docs", "document:upload:destination_kind=vector")
        val sKey = stored("ik_mix_s", sCaps: _*)
        val uKey = stored("ik_mix_u", uCaps: _*)
        // The refusal lists the key's capabilities that grant: the applied one
        // by default, both under the opt-out (as in v1.60.0).
        def check(applied: Boolean): Unit = {
            {
                val listed = if (applied) sCaps.take(1) else sCaps
                assert(SearchAPIController.collectionScopeRefusal(searchReq(sKey, "qdrant"), "qdrant", "ik_docs", None).isEmpty)
                assertDenied(
                    SearchAPIController.collectionScopeRefusal(searchReq(sKey, "qdrant"), "qdrant", "ik_other", None),
                    "ik_mix_s",
                    "search",
                    "vector",
                    listed,
                    Seq("ik_other")
                )
                val uListed = if (applied) uCaps.take(1) else uCaps
                assert(FileUploadAPIController.uploadScopeRefusal(uploadReq(uKey), docsPipeline).isEmpty)
                assertDenied(
                    FileUploadAPIController.uploadScopeRefusal(uploadReq(uKey), otherPipeline),
                    "ik_mix_u",
                    "document",
                    "upload",
                    uListed,
                    Seq("ik_other")
                )
            }
        }
        refuseMode(check(applied = true))
        allowMode(check(applied = false))
    }

    test("log-only shape: a key with no applied search or upload capability is left to the gate (no refusal from the helpers)") {
        // Under CAPABILITY_ENFORCEMENT=log-only the gate lets such a key through
        // as would-deny; v1.60.0 then answered 200, and the helpers keep that.
        val noCap = ScopeDenialFixtures.key("ik_jobs", "job:read", "pipeline:read")
        for (c <- Seq("ik_docs", "ik_other"))
            assert(SearchAPIController.collectionScopeRefusal(searchReq(noCap, "qdrant"), "qdrant", c, None).isEmpty)
        for (p <- Seq(docsPipeline, otherPipeline, pipeline("ik_bare_p", null)))
            assert(FileUploadAPIController.uploadScopeRefusal(uploadReq(noCap), p).isEmpty, p.name)
    }

    test("upload: a vector destination with a null or blank name, or a padded pgvector schema, is named by no collection scope") {
        val caps = Seq("document:upload:collection=ik_docs")
        val k = ScopeDenialFixtures.key("ik_rag", caps: _*)
        val unscoped = ScopeDenialFixtures.key("uploader", "document:upload")
        val cases = Seq(
            pipeline("ik_null_t", Destination(qdrant = qdrant("ik_docs"), pgvector = pgvector(null, "public"))),
            pipeline("ik_null_s", Destination(pgvector = pgvector(null, null))),
            pipeline("ik_blank_t", Destination(qdrant = qdrant("ik_docs"), pgvector = pgvector("   ", "public"))),
            pipeline("ik_null_q", Destination(qdrant = qdrant(null))),
            pipeline("ik_pad_s", Destination(pgvector = pgvector("ik_docs", " public "))),
            pipeline("ik_pad_n", Destination(qdrant = qdrant(" ik_docs")))
        )
        for (c <- cases) {
            assertDenied(FileUploadAPIController.uploadScopeRefusal(uploadReq(k), c), "ik_rag", "document", "upload", caps, Nil)
            assert(FileUploadAPIController.uploadScopeRefusal(uploadReq(unscoped), c).isEmpty, "unscoped: " + c.name)
        }
        // a null pgvector schema is public, as PGVectorSql.schemaOf reads it
        assert(FileUploadAPIController.uploadScopeRefusal(uploadReq(k), pipeline("ik_pg_null", Destination(pgvector = pgvector("ik_docs", null)))).isEmpty)
    }

    test("pgvector search: a blank or padded schema and a dotted name are named by no collection scope") {
        val caps = Seq("search:vector:collection=ik_docs", "search:vector:collection=a.b")
        val k = ScopeDenialFixtures.key("ik_pg", caps: _*)
        val unscoped = ScopeDenialFixtures.key("reader", "search:vector")
        val cases = Seq(("ik_docs", Some("  ")), ("ik_docs", Some(" public ")), ("a.b", None), ("a.b", Some("public")), ("c", Some("a.b")))
        for ((t, sc) <- cases) {
            assertDenied(SearchAPIController.collectionScopeRefusal(searchReq(k, "pgvector"), "pgvector", t, sc), "ik_pg", "search", "vector", caps, Nil)
            assert(SearchAPIController.collectionScopeRefusal(searchReq(unscoped, "pgvector"), "pgvector", t, sc).isEmpty, s"unscoped $t $sc")
        }
        assert(SearchAPIController.collectionScopeRefusal(searchReq(k, "pgvector"), "pgvector", "ik_docs", Some("public")).isEmpty)
        // collection=a.b names schema a, table b, and nothing else
        assert(SearchAPIController.collectionScopeRefusal(searchReq(k, "pgvector"), "pgvector", "b", Some("a")).isEmpty)
    }
}
