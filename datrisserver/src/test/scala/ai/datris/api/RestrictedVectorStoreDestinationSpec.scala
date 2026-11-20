package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.ScopeDenialWording
import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, DestinationProbe, DestinationTargets, SharedDatasets, VectorContainers}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import scala.collection.JavaConverters._
import scala.concurrent.duration._

/** Story: a restricted user cannot point a pipeline at a vector collection
  * that already exists (plans/stories/vector-store-existing-collections.md),
  * Acceptance, `RestrictedVectorStoreDestinationSpec`. Fixtures copied from
  * `RestrictedPgvectorDestinationSpec`, not shared.
  *
  * Pinned seams (assumed shape, from the story's Files):
  * {{{
  * // PipelineAPIController: the nine-argument form delegates here with DestinationProbe.vectorContainerExists.
  * def existingDestinationRefusal(request, candidate, stored, others, env, exists, catalogCommitted, deadline,
  *     vectorTableExists: DestinationTargets.Target => Boolean,
  *     vectorContainerExists: DestinationTargets.VectorContainerTarget => Boolean): Option[ResponseEntity[String]]
  * // EntityVersionAPIController: the seven-argument form delegates here.
  * def pipelineRestoreRefusal(request, snap, live, others, env, exists, vectorTableExists,
  *     vectorContainerExists: DestinationTargets.VectorContainerTarget => Boolean): Option[ResponseEntity[String]]
  * }}}
  * The refusal on an existing container is `ExistsRefusal(VectorContainers.label(k))`
  * with `k` the caller's own key as written (`VectorContainers.displayKey`: a
  * weaviate class as the caller typed it, the identity key being lowercased). */
class RestrictedVectorStoreDestinationSpec extends AnyFunSuite {

    private def req(rk: ResolvedKey): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/pipeline")
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def bob: ResolvedKey =
        APIKeyValidator.resolveFromSession(User("vs_bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit")))
    private def editor: ResolvedKey = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
    private val fullKey: ResolvedKey = ResolvedKey(None, "full", Nil, isLegacyFullAccess = true)
    private val salesKey: ResolvedKey =
        ResolvedKey(
            None,
            "sales-key",
            Capability.parseList(Seq("pipeline:read:catalog=Sales", "pipeline:create:catalog=Sales", "pipeline:update:catalog=Sales")),
            isLegacyFullAccess = false
        )

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    private val Stores = Seq("qdrant", "weaviate", "milvus", "chroma")

    private def on(store: String, name: String, container: String, catalog: String = "Sales", chunking: ChunkingConfig = ChunkingConfig()): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            destination = store match {
                case "qdrant" => Destination(qdrant = QdrantConfig(container, chunking, null, null, null))
                case "weaviate" => Destination(weaviate = WeaviateConfig(container, chunking, null, null, null))
                case "milvus" => Destination(milvus = MilvusConfig(container, chunking, null, null, null))
                case "chroma" => Destination(chroma = ChromaConfig(container, chunking, null, null, null))
            }
        )

    /** The orphan's name per store, as a caller would type it (weaviate: first letter lower). */
    private def orphan(store: String): String = if (store == "weaviate") "vsOrphan" else "vs_orphan"

    /** The container probe: `existing` holds (store, lowercased name). */
    private class VecProbe(existing: Set[(String, String)], fail: Boolean = false, sleepMs: Long = 0)
        extends (DestinationTargets.VectorContainerTarget => Boolean) {
        @volatile var calls = 0
        val seen = scala.collection.mutable.ArrayBuffer.empty[DestinationTargets.VectorContainerTarget]
        def apply(t: DestinationTargets.VectorContainerTarget): Boolean = synchronized {
            calls += 1
            seen += t
            if (sleepMs > 0) Thread.sleep(sleepMs)
            if (fail) throw new RuntimeException("connection refused: qdrant.internal:6333 api-key=qk-secret")
            existing.contains(t.key.store -> t.exact.toLowerCase(java.util.Locale.ROOT))
        }
    }

    private def orphans: Set[(String, String)] = Stores.map(s => s -> orphan(s).toLowerCase(java.util.Locale.ROOT)).toSet

    /** The other-families probe and the pgvector table probe: never called here. */
    private class NoOther extends (DestinationTargets.Target => Boolean) {
        def apply(t: DestinationTargets.Target): Boolean = throw new AssertionError("other-family probe called for " + t)
    }
    private class NoTable extends (DestinationTargets.Target => Boolean) {
        def apply(t: DestinationTargets.Target): Boolean = throw new AssertionError("pgvector table probe called for " + t)
    }

    private def refuse(
        r: HttpServletRequest,
        c: PipelineConfig,
        stored: PipelineConfig,
        others: Seq[PipelineConfig],
        vec: DestinationTargets.VectorContainerTarget => Boolean,
        deadline: FiniteDuration = 5.seconds
    ): Option[ResponseEntity[String]] =
        PipelineAPIController.existingDestinationRefusal(r, c, stored, others, env, new NoOther, (_: String) => false, deadline, new NoTable, vec)

    private def status(r: Option[ResponseEntity[String]]): Option[Int] = r.map(_.getStatusCode.value)

    private def errorOf(r: ResponseEntity[String]): String =
        JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString

    private def onlyError(r: ResponseEntity[String]): Unit =
        assert(JsonParser.parseString(r.getBody).getAsJsonObject.keySet.asScala == Set("error"), r.getBody)

    private def existsText(store: String, c: PipelineConfig): String = {
        val k = VectorContainers.keysOf(c).find(_.store == store).get
        DestinationTargets.ExistsRefusal(VectorContainers.label(VectorContainers.displayKey(k, c)))
    }

    test(
        "the finding: a restricted save onto an existing collection no config names is refused with the ExistsRefusal, naming only the caller's own store and name"
    ) {
        val hidden = Seq(on("qdrant", "payroll_live", "payroll_chunks", catalog = "Finance"), on("weaviate", "hr_secret", "HrDocs", catalog = "Finance"))
        for (store <- Stores) {
            val probe = new VecProbe(orphans)
            val c = on(store, "bob_v", orphan(store))
            val r = refuse(req(bob), c, null, hidden, probe)
            assert(status(r).contains(400), s"$store: $r")
            assert(errorOf(r.get) == existsText(store, c), store)
            onlyError(r.get)
            assert(errorOf(r.get).contains(orphan(store)), s"$store: the caller's own name, as typed: ${errorOf(r.get)}")
            ScopeDenialWording.assertHides(r.get.getBody, Seq("payroll_live", "payroll_chunks", "hr_secret", "HrDocs", "Finance"))
            assert(probe.calls == 1, store)
            val t = probe.seen.head
            assert(t.key == VectorContainers.keysOf(c).head, store)
            assert(t.exact == (if (store == "weaviate") "VsOrphan" else "vs_orphan"), store)
        }
    }

    test("a brand-new collection saves (probed, absent)") {
        for (store <- Stores) {
            val probe = new VecProbe(orphans)
            assert(refuse(req(bob), on(store, "bob_v", "vs_new"), null, Nil, probe).isEmpty, store)
            assert(probe.calls == 1, store)
        }
    }

    test("re-saving one's own pipeline after its first load created the collection is never probed") {
        for (store <- Stores) {
            val probe = new VecProbe(Set(store -> "vs_new"), fail = true)
            val stored = on(store, "bob_v", "vs_new")
            assert(refuse(req(bob), on(store, "bob_v", "vs_new"), stored, Nil, probe).isEmpty, store)
            assert(refuse(req(bob), on(store, "bob_v", "vs_new", chunking = ChunkingConfig(chunkSize = 900)), stored, Nil, probe).isEmpty, store)
            assert(probe.calls == 0, store)
        }
        // Weaviate: a first-letter case change is the same class.
        val probe = new VecProbe(Set("weaviate" -> "vs_new"), fail = true)
        assert(refuse(req(bob), on("weaviate", "bob_v", "Vs_new"), on("weaviate", "bob_v", "vs_new"), Nil, probe).isEmpty)
        assert(probe.calls == 0)
        // ...any other change is probed.
        val p2 = new VecProbe(orphans)
        assert(status(refuse(req(bob), on("qdrant", "bob_v", "vs_orphan"), on("qdrant", "bob_v", "vs_new"), Nil, p2)).contains(400))
        assert(status(refuse(req(bob), on("milvus", "bob_v", "vs_orphan"), on("qdrant", "bob_v", "vs_orphan"), Nil, p2)).contains(400))
        assert(p2.calls == 2)
    }

    test("a stored config the caller cannot see grandfathers nothing") {
        for (store <- Stores) {
            val probe = new VecProbe(orphans)
            val hiddenStored = on(store, "bob_v", orphan(store), catalog = "Finance")
            val r = refuse(req(bob), on(store, "bob_v", orphan(store)), hiddenStored, Nil, probe)
            assert(status(r).contains(400), s"$store: $r")
            ScopeDenialWording.assertHides(r.get.getBody, Seq("Finance"))
            assert(probe.calls == 1, store)
        }
    }

    test("a visible pipeline on the same container exempts the probe; a hidden one does not") {
        for (store <- Stores) {
            val quiet = new VecProbe(orphans, fail = true)
            assert(refuse(req(bob), on(store, "bob_v", orphan(store)), null, Seq(on(store, "sales_v", orphan(store))), quiet).isEmpty, store)
            assert(quiet.calls == 0, store)
            val probe = new VecProbe(orphans)
            val r = refuse(req(bob), on(store, "bob_v", orphan(store)), null, Seq(on(store, "fin_v", orphan(store), catalog = "Finance")), probe)
            assert(status(r).contains(400), s"$store: $r")
            ScopeDenialWording.assertHides(r.get.getBody, Seq("fin_v", "Finance"))
            assert(probe.calls == 1, store)
        }
    }

    test("the one-container rule still answers first for a container a visible pipeline names") {
        for (store <- Stores) {
            val others = Seq(on(store, "sales_v", orphan(store)))
            val r = PipelineAPIController.vectorContainerRefusal(req(bob), on(store, "bob_v", orphan(store)), null, others)
            assert(status(r).contains(400), s"$store: $r")
            // Through the restore path, which runs both rules in order: the probe is never reached.
            val probe = new VecProbe(orphans, fail = true)
            val rr = EntityVersionAPIController.pipelineRestoreRefusal(
                req(bob),
                on(store, "bob_v", orphan(store)).copy(version = 2),
                on(store, "bob_v", "vs_live"),
                others,
                env,
                new NoOther,
                new NoTable,
                probe
            )
            assert(status(rr).contains(400), s"$store: $rr")
            assert(errorOf(rr.get) == errorOf(r.get), store)
            assert(probe.calls == 0, store)
        }
    }

    test("any probe failure is the 500 ProbeUnavailable; nothing else in the body") {
        for (store <- Stores) {
            val r = refuse(req(bob), on(store, "bob_v", "vs_new"), null, Nil, new VecProbe(Set.empty, fail = true))
            assert(status(r).contains(500), s"$store: $r")
            assert(errorOf(r.get) == DestinationTargets.ProbeUnavailable, store)
            onlyError(r.get)
            ScopeDenialWording.assertHides(r.get.getBody, Seq("qdrant.internal", "6333", "qk-secret", "connection refused"))
        }
    }

    test("the probe runs under the save's deadline") {
        val t0 = System.nanoTime()
        val r = refuse(req(bob), on("qdrant", "bob_v", "vs_new"), null, Nil, new VecProbe(Set.empty, sleepMs = 3000), deadline = 200.millis)
        assert(status(r).contains(500), r)
        assert(errorOf(r.get) == DestinationTargets.ProbeUnavailable)
        assert((System.nanoTime() - t0) / 1000000L < 2500L, "the save waited for the probe past its deadline")
    }

    test("it takes one of the caller's slots: over the limit it is the 429 and the probe is never called") {
        val owner = PipelineAPIController.probeOwner(req(bob))
        val release = new java.util.concurrent.CountDownLatch(1)
        val started = new java.util.concurrent.CountDownLatch(DestinationProbe.MaxPerOwner)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(DestinationProbe.MaxPerOwner)
        try {
            for (_ <- 1 to DestinationProbe.MaxPerOwner)
                pool.submit(new Runnable {
                    def run(): Unit =
                        DestinationProbe.withDeadline(60.seconds, "parked", owner) {
                            started.countDown(); release.await(60, java.util.concurrent.TimeUnit.SECONDS)
                        }
                })
            assert(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val probe = new VecProbe(Set.empty)
            val r = refuse(req(bob), on("chroma", "bob_v", "vs_new"), null, Nil, probe)
            assert(status(r).contains(429), r)
            assert(errorOf(r.get) == DestinationTargets.TooManyChecksRefusal)
            assert(probe.calls == 0)
        } finally {
            release.countDown()
            pool.shutdown()
            pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    test("a changed name that is not plain, or a blank name the stored config does not carry, is refused before any probe") {
        for (store <- Stores) {
            val probe = new VecProbe(Set.empty, fail = true)
            for (bad <- Seq("a/b", "a?b", "50%", "a\"b", "a b", "a\u0000b", ".", "..")) {
                val r = refuse(req(bob), on(store, "bob_v", bad), on(store, "bob_v", "vs_new"), Nil, probe)
                assert(status(r).contains(400), s"$store '$bad': $r")
                assert(errorOf(r.get) == DestinationTargets.UnsafeVectorContainerRefusal, s"$store '$bad'")
                val fresh = refuse(req(bob), on(store, "bob_v", bad), null, Nil, probe)
                assert(status(fresh).contains(400) && errorOf(fresh.get) == DestinationTargets.UnsafeVectorContainerRefusal, s"$store '$bad'")
            }
            for (blank <- Seq(null, "", "   ")) {
                for (stored <- Seq(null, on(store, "bob_v", "vs_new"))) {
                    val r = refuse(req(bob), on(store, "bob_v", blank), stored, Nil, probe)
                    assert(status(r).contains(400), s"$store '$blank' over $stored: $r")
                    assert(errorOf(r.get) == DestinationTargets.BlankVectorContainerRefusal, s"$store '$blank'")
                }
                // A stored config the caller cannot see carries nothing.
                val hidden = refuse(req(bob), on(store, "bob_v", blank), on(store, "bob_v", blank, catalog = "Finance"), Nil, probe)
                assert(status(hidden).contains(400) && errorOf(hidden.get) == DestinationTargets.BlankVectorContainerRefusal, s"$store '$blank'")
                // The visible stored config already carries the identical blank name: not refused here.
                assert(refuse(req(bob), on(store, "bob_v", blank), on(store, "bob_v", blank), Nil, probe).isEmpty, s"$store '$blank'")
            }
            // An unchanged name that is not plain is grandfathered: no refusal, no probe.
            assert(refuse(req(bob), on(store, "bob_v", "a b"), on(store, "bob_v", "a b"), Nil, probe).isEmpty, store)
            assert(probe.calls == 0, store)
        }
    }

    test("unrestricted users, API keys (full and catalog-scoped) and auth off start no probe") {
        val probe = new VecProbe(orphans, fail = true)
        val unauth = mock(classOf[HttpServletRequest])
        for (r <- Seq(req(editor), req(fullKey), req(salesKey), unauth); store <- Stores) {
            assert(refuse(r, on(store, "x", orphan(store)), null, Nil, probe).isEmpty, store)
            assert(refuse(r, on(store, "x", "vs_new"), null, Nil, probe).isEmpty, store)
            // Names they save today keep saving (byte-identical): no name refusal either.
            assert(refuse(r, on(store, "x", "a/b"), null, Nil, probe).isEmpty, store)
            assert(refuse(r, on(store, "x", null), null, Nil, probe).isEmpty, store)
        }
        assert(probe.calls == 0)
        // The same through the restore path.
        for (r <- Seq(req(editor), req(fullKey), req(salesKey), unauth))
            assert(
                EntityVersionAPIController
                    .pipelineRestoreRefusal(
                        r,
                        on("qdrant", "x", "vs_orphan").copy(version = 2),
                        on("qdrant", "x", "vs_live"),
                        Nil,
                        env,
                        new NoOther,
                        new NoTable,
                        probe
                    )
                    .isEmpty
            )
        assert(probe.calls == 0)
    }

    test("a version restore onto an existing collection is refused the same way; a restore that keeps the live container is not probed") {
        for (store <- Stores) {
            val probe = new VecProbe(orphans)
            val snap = on(store, "bob_v", orphan(store)).copy(version = 2)
            val r = EntityVersionAPIController.pipelineRestoreRefusal(req(bob), snap, on(store, "bob_v", "vs_live"), Nil, env, new NoOther, new NoTable, probe)
            assert(status(r).contains(400), s"$store: $r")
            assert(errorOf(r.get) == existsText(store, snap), store)
            assert(probe.calls == 1, store)
            // A deleted pipeline's snapshot (no live config) is probed too.
            val p2 = new VecProbe(orphans)
            val deleted = EntityVersionAPIController.pipelineRestoreRefusal(req(bob), snap, null, Nil, env, new NoOther, new NoTable, p2)
            assert(status(deleted).contains(400), s"$store: $deleted")
            assert(p2.calls == 1, store)
            // Restoring onto the live container: unchanged, not probed.
            val quiet = new VecProbe(Set(store -> "vs_live"), fail = true)
            assert(
                EntityVersionAPIController
                    .pipelineRestoreRefusal(
                        req(bob),
                        on(store, "bob_v", "vs_live", chunking = ChunkingConfig(chunkSize = 900)).copy(version = 2),
                        on(store, "bob_v", "vs_live"),
                        Nil,
                        env,
                        new NoOther,
                        new NoTable,
                        quiet
                    )
                    .isEmpty,
                store
            )
            assert(quiet.calls == 0, store)
            // A name refusal on restore too.
            val unsafe = EntityVersionAPIController
                .pipelineRestoreRefusal(
                    req(bob),
                    on(store, "bob_v", "a/b").copy(version = 2),
                    on(store, "bob_v", "vs_live"),
                    Nil,
                    env,
                    new NoOther,
                    new NoTable,
                    quiet
                )
            assert(status(unsafe).contains(400) && errorOf(unsafe.get) == DestinationTargets.UnsafeVectorContainerRefusal, s"$store: $unsafe")
            assert(quiet.calls == 0, store)
        }
    }
}
