package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

/** Story: a restricted user cannot point a pipeline at a vector collection
  * that already exists (plans/stories/vector-store-existing-collections.md),
  * Acceptance, `DestinationTargetsVectorContainerSpec`. Pure; no I/O.
  *
  * Pinned seam (additive, below the pgvector block of `DestinationTargets`):
  * {{{
  * val ProbedVectorStores: Seq[String]                       // qdrant, weaviate, milvus, chroma
  * case class VectorContainerTarget(key: VectorContainers.Key, exact: String)
  * def vectorContainerTargets(c: PipelineConfig): Seq[VectorContainerTarget]
  * def freshVectorContainers(candidate: PipelineConfig, stored: PipelineConfig): Seq[VectorContainerTarget]
  * def vectorContainersToProbe(candidate: PipelineConfig, stored: PipelineConfig, visibleOthers: Seq[PipelineConfig]): Seq[VectorContainerTarget]
  * def unsafeVectorContainerName(c: PipelineConfig): Boolean
  * def blankVectorContainerName(c: PipelineConfig): Boolean
  * def sameVectorContainerNames(candidate: PipelineConfig, stored: PipelineConfig): Boolean
  * val UnsafeVectorContainerRefusal: String
  * val BlankVectorContainerRefusal: String
  * }}}
  * `exact` is the name as the loader and the delete send it; for weaviate
  * with its first letter upper-cased (as the store stores the class). */
class DestinationTargetsVectorContainerSpec extends AnyFunSuite {

    import DestinationTargets._

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    private def cfg(name: String, d: Destination, catalog: String = "Sales"): PipelineConfig =
        PipelineConfig(name = name, catalog = catalog, createdByKeyLabel = "someone", destination = d)

    private def meta(kv: (String, String)*): java.util.Map[String, String] = {
        val m = new java.util.HashMap[String, String]()
        kv.foreach { case (k, v) => m.put(k, v) }
        m
    }

    /** A pipeline on `store`'s container `container`. */
    private def on(
        store: String,
        name: String,
        container: String,
        chunking: ChunkingConfig = ChunkingConfig(),
        metadata: java.util.Map[String, String] = null,
        secret: String = null
    ): PipelineConfig =
        cfg(
            name,
            store match {
                case "qdrant" => Destination(qdrant = QdrantConfig(container, chunking, metadata, null, secret))
                case "weaviate" => Destination(weaviate = WeaviateConfig(container, chunking, metadata, null, secret))
                case "milvus" => Destination(milvus = MilvusConfig(container, chunking, metadata, null, secret))
                case "chroma" => Destination(chroma = ChromaConfig(container, chunking, metadata, null, secret))
            }
        )

    private val Stores = Seq("qdrant", "weaviate", "milvus", "chroma")

    private def target(store: String, name: String): VectorContainerTarget =
        if (store == "weaviate") VectorContainerTarget(VectorContainers.Key("weaviate", "", name.toLowerCase(java.util.Locale.ROOT)), name.capitalize)
        else VectorContainerTarget(VectorContainers.Key(store, "", name), name)

    test(
        "vectorContainerTargets: one target per qdrant, weaviate, milvus and chroma destination, the name as the loader and the delete send it; nothing for pgvector, a blank name or an empty destination"
    ) {
        assert(ProbedVectorStores == Seq("qdrant", "weaviate", "milvus", "chroma"))

        assert(vectorContainerTargets(on("qdrant", "p", "Docs-1")) == Seq(VectorContainerTarget(VectorContainers.Key("qdrant", "", "Docs-1"), "Docs-1")))
        assert(vectorContainerTargets(on("milvus", "p", "docs_1")) == Seq(VectorContainerTarget(VectorContainers.Key("milvus", "", "docs_1"), "docs_1")))
        assert(vectorContainerTargets(on("chroma", "p", "docs.v2")) == Seq(VectorContainerTarget(VectorContainers.Key("chroma", "", "docs.v2"), "docs.v2")))
        // Weaviate: the identity key is lowercased, the exact name has its first letter upper-cased.
        assert(vectorContainerTargets(on("weaviate", "p", "docs")) == Seq(VectorContainerTarget(VectorContainers.Key("weaviate", "", "docs"), "Docs")))
        assert(vectorContainerTargets(on("weaviate", "p", "Docs")) == Seq(VectorContainerTarget(VectorContainers.Key("weaviate", "", "docs"), "Docs")))
        assert(vectorContainerTargets(on("weaviate", "p", "myDocs")) == Seq(VectorContainerTarget(VectorContainers.Key("weaviate", "", "mydocs"), "MyDocs")))

        // Several stores on one destination: one target each, store order.
        val all = cfg(
            "p",
            Destination(
                qdrant = QdrantConfig("a", ChunkingConfig(), null, null, null),
                weaviate = WeaviateConfig("b", ChunkingConfig(), null, null, null),
                milvus = MilvusConfig("c", ChunkingConfig(), null, null, null),
                chroma = ChromaConfig("d", ChunkingConfig(), null, null, null)
            )
        )
        assert(vectorContainerTargets(all) == Seq(target("qdrant", "a"), target("weaviate", "b"), target("milvus", "c"), target("chroma", "d")))

        // Nothing for pgvector (its own path), blank names, empty or missing destinations.
        assert(vectorContainerTargets(cfg("p", Destination(pgvector = PGVectorConfig("docs", "public", ChunkingConfig(), null, null, null)))).isEmpty)
        for (store <- Stores; blank <- Seq(null, "", "   "))
            assert(vectorContainerTargets(on(store, "p", blank)).isEmpty, s"$store '$blank'")
        assert(vectorContainerTargets(cfg("p", Destination())).isEmpty)
        assert(vectorContainerTargets(cfg("p", null)).isEmpty)
        assert(vectorContainerTargets(null).isEmpty)
    }

    test("targetsOf, ProbedFamilies and vectorTableTargets list nothing for the four stores") {
        for (store <- Stores) {
            val c = on(store, "p", "docs")
            assert(targetsOf(c, env).isEmpty, store)
            assert(vectorTableTargets(c, env).isEmpty, store)
            assert(!ProbedFamilies.contains(store), store)
        }
        assert(ProbedFamilies == Set("snowflake", "sqlserver", "databricks", "objectstore", "pgtable", "mongodb"))
    }

    test(
        "freshVectorContainers: a new pipeline's container is fresh; an unchanged one is not; a changed name or store is; a config-only change (chunking, metadata, secret name) is not"
    ) {
        for (store <- Stores) {
            assert(freshVectorContainers(on(store, "p", "docs"), null) == Seq(target(store, "docs")), store)
            assert(freshVectorContainers(on(store, "p", "docs"), on(store, "p", "docs")).isEmpty, store)
            assert(freshVectorContainers(on(store, "p", "docs2"), on(store, "p", "docs")) == Seq(target(store, "docs2")), store)
            assert(
                freshVectorContainers(
                    on(store, "p", "docs", chunking = ChunkingConfig(chunkSize = 900), metadata = meta("team" -> "x"), secret = "other-secret"),
                    on(store, "p", "docs")
                ).isEmpty,
                store
            )
        }
        // A changed store, same name.
        assert(freshVectorContainers(on("milvus", "p", "docs"), on("qdrant", "p", "docs")) == Seq(target("milvus", "docs")))
        assert(freshVectorContainers(on("qdrant", "p", "docs"), on("chroma", "p", "docs")) == Seq(target("qdrant", "docs")))
        // A stored config without a vector destination keeps nothing.
        assert(freshVectorContainers(on("chroma", "p", "docs"), cfg("p", Destination())) == Seq(target("chroma", "docs")))
    }

    test("weaviate: a first-letter case change is not fresh; any other letter-case change is") {
        assert(freshVectorContainers(on("weaviate", "p", "Docs"), on("weaviate", "p", "docs")).isEmpty)
        assert(freshVectorContainers(on("weaviate", "p", "docs"), on("weaviate", "p", "Docs")).isEmpty)
        assert(freshVectorContainers(on("weaviate", "p", "dOcs"), on("weaviate", "p", "docs")) == Seq(target("weaviate", "dOcs")))
        assert(freshVectorContainers(on("weaviate", "p", "MyDocs"), on("weaviate", "p", "Mydocs")) == Seq(target("weaviate", "MyDocs")))
        // Other stores compare exactly: any case change is fresh.
        for (store <- Seq("qdrant", "milvus", "chroma"))
            assert(freshVectorContainers(on(store, "p", "Docs"), on(store, "p", "docs")) == Seq(target(store, "Docs")), store)
    }

    test("vectorContainersToProbe: a visible pipeline naming the same container covers it; another name or the same name in another store does not") {
        for (store <- Stores) {
            val c = on(store, "bob_p", "docs")
            assert(vectorContainersToProbe(c, null, Seq(on(store, "sales_p", "docs"))).isEmpty, store)
            assert(vectorContainersToProbe(c, null, Seq(on(store, "sales_p", "other"))) == Seq(target(store, "docs")), store)
            val elsewhere = Stores.filterNot(_ == store).head
            assert(vectorContainersToProbe(c, null, Seq(on(elsewhere, "sales_p", "docs"))) == Seq(target(store, "docs")), store)
            assert(vectorContainersToProbe(c, null, Nil) == Seq(target(store, "docs")), store)
            // The candidate's own name in the list is the stored config, not a cover.
            assert(vectorContainersToProbe(c, null, Seq(on(store, "bob_p", "docs"))) == Seq(target(store, "docs")), store)
            // An unchanged container is never probed.
            assert(vectorContainersToProbe(c, on(store, "bob_p", "docs"), Nil).isEmpty, store)
        }
        // Weaviate: another pipeline's class differing only in its first letter's case is the same class.
        assert(vectorContainersToProbe(on("weaviate", "bob_p", "docs"), null, Seq(on("weaviate", "sales_p", "Docs"))).isEmpty)
    }

    test("unsafeVectorContainerName: slash, question mark, percent, double quote, space, NUL, '.', '..'; plain names pass") {
        val unsafe = Seq("a/b", "a?b", "50%", "a\"b", "a b", "a\u0000b", ".", "..", "a#b", "a+b", "café", " docs", "docs ")
        val plain = Seq("docs", "Docs-1", "docs_1.v2", "a.b", "A", "x-y_z.9")
        for (store <- Stores) {
            unsafe.foreach(n => assert(unsafeVectorContainerName(on(store, "p", n)), s"$store '$n' should be unsafe"))
            plain.foreach(n => assert(!unsafeVectorContainerName(on(store, "p", n)), s"$store '$n' should pass"))
        }
        assert(!unsafeVectorContainerName(cfg("p", Destination())))
        assert(!unsafeVectorContainerName(null))
        assert(UnsafeVectorContainerRefusal.nonEmpty)
    }

    test("blankVectorContainerName: null or blank, per store") {
        for (store <- Stores) {
            Seq(null, "", "   ").foreach(n => assert(blankVectorContainerName(on(store, "p", n)), s"$store '$n'"))
            assert(!blankVectorContainerName(on(store, "p", "docs")), store)

            // Carried only when the stored config has exactly the same raw name.
            assert(sameVectorContainerNames(on(store, "p", null), on(store, "p", null)), store)
            assert(sameVectorContainerNames(on(store, "p", ""), on(store, "p", "")), store)
            assert(!sameVectorContainerNames(on(store, "p", null), on(store, "p", "")), store)
            assert(!sameVectorContainerNames(on(store, "p", ""), on(store, "p", "docs")), store)
            assert(!sameVectorContainerNames(on(store, "p", null), null), store)
            assert(!sameVectorContainerNames(on(store, "p", null), on(Stores.filterNot(_ == store).head, "p", null)), store)
        }
        assert(!blankVectorContainerName(cfg("p", Destination())))
        assert(!blankVectorContainerName(null))
        assert(BlankVectorContainerRefusal.nonEmpty)
        assert(BlankVectorContainerRefusal != UnsafeVectorContainerRefusal)
    }

    test("the two name refusals name nothing: no pipeline or catalog name") {
        for (text <- Seq(UnsafeVectorContainerRefusal, BlankVectorContainerRefusal))
            ai.datris.auth.ScopeDenialWording.assertHides(text, Seq("bob_p", "sales_p", "Sales", "Finance"))
    }
}
