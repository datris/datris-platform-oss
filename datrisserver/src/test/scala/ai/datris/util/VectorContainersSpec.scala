package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

/** Story: One vector container per pipeline
  * (plans/stories/vector-container-one-pipeline.md), Acceptance, VectorContainersSpec.
  *
  * Pure identity rules for vector containers. Signatures assumed:
  * {{{
  * object VectorContainers {
  *   case class Key(store: String, schema: String, name: String)
  *   def key(store: String, name: String, schema: Option[String]): Option[Key]
  *   def keysOf(c: PipelineConfig): Seq[Key]
  *   def namers(k: Key, configs: Seq[PipelineConfig]): Seq[PipelineConfig]
  *   // keys in keysOf(candidate) not in keysOf(stored) that a config with another name holds
  *   def saveConflicts(candidate: PipelineConfig, stored: PipelineConfig, others: Seq[PipelineConfig]): Seq[(Key, Seq[PipelineConfig])]
  *   // shared keys of `config` with the other (differently named) pipelines naming them
  *   def sharedOnDelete(config: PipelineConfig, others: Seq[PipelineConfig]): Seq[(Key, Seq[PipelineConfig])]
  *   def refusalMessage(k: Key, visibleNames: Option[Seq[String]]): String
  *   def keptOnDeleteMessage(k: Key, visibleNames: Option[Seq[String]]): String
  *   def label(k: Key): String
  * }
  * }}}
  */
class VectorContainersSpec extends AnyFunSuite {
    import VectorContainers.Key

    private val chunking = ChunkingConfig()

    private def qdrant(n: String) = QdrantConfig(n, chunking, null, null, null)
    private def weaviate(n: String) = WeaviateConfig(n, chunking, null, null, null)
    private def milvus(n: String) = MilvusConfig(n, chunking, null, null, null)
    private def chroma(n: String) = ChromaConfig(n, chunking, null, null, null)
    private def pgvector(t: String, s: String) = PGVectorConfig(t, s, chunking, null, null, null)

    private def p(name: String, d: Destination, catalog: String = "Sales"): PipelineConfig =
        PipelineConfig(name = name, destination = d, catalog = catalog)

    private def q(name: String, coll: String) = p(name, Destination(qdrant = qdrant(coll)))
    private def pg(name: String, table: String, schema: String) = p(name, Destination(pgvector = pgvector(table, schema)))

    private def names(cs: Seq[PipelineConfig]): Set[String] = cs.map(_.name).toSet

    test("keysOf lists one key per vector destination and none for a null or blank name") {
        val all = p(
            "all",
            Destination(
                qdrant = qdrant("q1"),
                weaviate = weaviate("W1"),
                pgvector = pgvector("t1", "s1"),
                milvus = milvus("m1"),
                chroma = chroma("c1")
            )
        )
        assert(VectorContainers.keysOf(all).toSet == Set(
            Key("qdrant", "", "q1"),
            Key("weaviate", "", "w1"),
            Key("pgvector", "s1", "t1"),
            Key("milvus", "", "m1"),
            Key("chroma", "", "c1")
        ))
        assert(VectorContainers.keysOf(all).size == 5)

        val blanks = p(
            "blanks",
            Destination(
                qdrant = qdrant(null),
                weaviate = weaviate(""),
                pgvector = pgvector("   ", "public"),
                milvus = milvus(null),
                chroma = chroma(" ")
            )
        )
        assert(VectorContainers.keysOf(blanks).isEmpty)
        assert(VectorContainers.keysOf(p("none", Destination())).isEmpty)
        assert(VectorContainers.keysOf(p("nodest", null)).isEmpty)

        assert(VectorContainers.key("qdrant", null, None).isEmpty)
        assert(VectorContainers.key("chroma", "", None).isEmpty)
        assert(VectorContainers.key("pgvector", "  ", Some("public")).isEmpty)
    }

    test("qdrant, milvus and chroma names compare exactly and case-sensitively") {
        for (store <- Seq("qdrant", "milvus", "chroma")) {
            assert(VectorContainers.key(store, "Docs", None) == Some(Key(store, "", "Docs")), store)
            assert(VectorContainers.key(store, "Docs", None) != VectorContainers.key(store, "docs", None), store)
            // verbatim: no trim
            assert(VectorContainers.key(store, " docs", None) != VectorContainers.key(store, "docs", None), store)
            // schema only applies to pgvector
            assert(VectorContainers.key(store, "docs", Some("other")) == Some(Key(store, "", "docs")), store)
        }
        val configs = Seq(q("a", "Docs"), q("b", "docs"), q("c", "Docs"))
        assert(names(VectorContainers.namers(Key("qdrant", "", "Docs"), configs)) == Set("a", "c"))
    }

    test("weaviate class names compare case-insensitively") {
        val k = VectorContainers.key("weaviate", "Chunks", None)
        assert(k == Some(Key("weaviate", "", "chunks")))
        assert(VectorContainers.key("weaviate", "CHUNKS", None) == k)
        assert(VectorContainers.key("weaviate", "chunks", None) == k)
        val configs = Seq(
            p("a", Destination(weaviate = weaviate("Chunks"))),
            p("b", Destination(weaviate = weaviate("chunks"))),
            p("c", Destination(weaviate = weaviate("Other")))
        )
        assert(names(VectorContainers.namers(k.get, configs)) == Set("a", "b"))
    }

    test("pgvector keys include the schema, defaulting null and blank to public, and compare table and schema case-sensitively") {
        assert(VectorContainers.key("pgvector", "vc_x", None) == Some(Key("pgvector", "public", "vc_x")))
        assert(VectorContainers.key("pgvector", "vc_x", Some(null)) == Some(Key("pgvector", "public", "vc_x")))
        assert(VectorContainers.key("pgvector", "vc_x", Some("")) == Some(Key("pgvector", "public", "vc_x")))
        assert(VectorContainers.key("pgvector", "vc_x", Some("  ")) == Some(Key("pgvector", "public", "vc_x")))
        assert(VectorContainers.key("pgvector", "vc_x", Some("other")) == Some(Key("pgvector", "other", "vc_x")))
        assert(VectorContainers.key("pgvector", "vc_x", Some("other")) != VectorContainers.key("pgvector", "vc_x", None))
        // case-sensitive table and schema (identifiers are double-quoted)
        assert(VectorContainers.key("pgvector", "VC_X", None) != VectorContainers.key("pgvector", "vc_x", None))
        assert(VectorContainers.key("pgvector", "vc_x", Some("Other")) != VectorContainers.key("pgvector", "vc_x", Some("other")))

        assert(VectorContainers.keysOf(pg("a", "vc_x", null)) == Seq(Key("pgvector", "public", "vc_x")))
        assert(VectorContainers.keysOf(pg("a", "vc_x", "")) == Seq(Key("pgvector", "public", "vc_x")))

        val configs = Seq(pg("a", "vc_x", null), pg("b", "vc_x", "public"), pg("c", "vc_x", "other"), pg("d", "VC_X", "public"))
        assert(names(VectorContainers.namers(Key("pgvector", "public", "vc_x"), configs)) == Set("a", "b"))
        assert(names(VectorContainers.namers(Key("pgvector", "other", "vc_x"), configs)) == Set("c"))
    }

    test("the same name in two different stores does not collide") {
        val keys = Seq("qdrant", "weaviate", "milvus", "chroma", "pgvector").map(s => VectorContainers.key(s, "docs", None))
        assert(keys.forall(_.isDefined))
        assert(keys.distinct.size == 5)

        val candidate = p("b", Destination(chroma = chroma("docs")))
        val others = Seq(
            q("q", "docs"),
            p("w", Destination(weaviate = weaviate("docs"))),
            p("m", Destination(milvus = milvus("docs"))),
            pg("g", "docs", "public")
        )
        assert(VectorContainers.saveConflicts(candidate, null, others).isEmpty)
        assert(VectorContainers.namers(Key("chroma", "", "docs"), others).isEmpty)
    }

    test("saveConflicts is empty for a stored pipeline whose container is unchanged even when another pipeline shares it") {
        val stored = pg("vc_seed", "vc_x", "public")
        // an unrelated edit (chunk size) to a grandfathered sharer
        val candidate = p("vc_seed", Destination(pgvector = PGVectorConfig("vc_x", "public", ChunkingConfig(chunkSize = 900), null, null, null)))
        val others = Seq(pg("vc_a", "vc_x", "public"), stored)
        assert(VectorContainers.saveConflicts(candidate, stored, others).isEmpty)
        // null schema in the stored config equals "public" in the candidate
        assert(VectorContainers.saveConflicts(candidate, pg("vc_seed", "vc_x", null), others).isEmpty)
    }

    test("saveConflicts names the other pipelines for a new pipeline and for a changed container") {
        val others = Seq(q("vc_a", "docs"), q("vc_seed", "docs"), q("unrelated", "else"), pg("pg", "docs", "public"))
        val fresh = VectorContainers.saveConflicts(q("vc_b", "docs"), null, others)
        assert(fresh.size == 1, fresh)
        assert(fresh.head._1 == Key("qdrant", "", "docs"))
        assert(names(fresh.head._2) == Set("vc_a", "vc_seed"))

        val stored = q("vc_b", "mine")
        val moved = VectorContainers.saveConflicts(q("vc_b", "docs"), stored, others :+ stored)
        assert(moved.size == 1, moved)
        assert(moved.head._1 == Key("qdrant", "", "docs"))
        assert(names(moved.head._2) == Set("vc_a", "vc_seed"))

        // a pipeline with two vector destinations: only the new, taken key conflicts
        val twoStored = p("vc_c", Destination(qdrant = qdrant("own"), pgvector = pgvector("t", null)))
        val twoCandidate = p("vc_c", Destination(qdrant = qdrant("own"), pgvector = pgvector("docs", "public")))
        val two = VectorContainers.saveConflicts(twoCandidate, twoStored, others :+ twoStored)
        assert(two.map(_._1) == Seq(Key("pgvector", "public", "docs")))
        assert(names(two.head._2) == Set("pg"))
    }

    test("saveConflicts ignores the config with the candidate's own name") {
        // readAll includes the stored row of the same name
        val own = q("vc_a", "docs")
        assert(VectorContainers.saveConflicts(q("vc_a", "docs"), null, Seq(own)).isEmpty)
        assert(VectorContainers.saveConflicts(q("vc_a", "docs"), q("vc_a", "old"), Seq(own, q("x", "else"))).isEmpty)
        val withSharer = VectorContainers.saveConflicts(q("vc_a", "docs"), q("vc_a", "old"), Seq(own, q("vc_b", "docs")))
        assert(withSharer.size == 1)
        assert(names(withSharer.head._2) == Set("vc_b"))
    }

    test("sharedOnDelete lists each shared key with the other pipelines and nothing for an unshared container") {
        val config = p("vc_a", Destination(qdrant = qdrant("docs"), pgvector = pgvector("solo", "public")))
        val others = Seq(config, q("vc_seed", "docs"), q("vc_b", "docs"), q("x", "Docs"), pg("y", "solo", "other"))
        val shared = VectorContainers.sharedOnDelete(config, others)
        assert(shared.size == 1, shared)
        assert(shared.head._1 == Key("qdrant", "", "docs"))
        assert(names(shared.head._2) == Set("vc_seed", "vc_b"))

        assert(VectorContainers.sharedOnDelete(q("lonely", "only-mine"), Seq(q("lonely", "only-mine"), q("z", "zz"))).isEmpty)
        assert(VectorContainers.sharedOnDelete(p("nov", Destination()), others).isEmpty)
    }

    test("refusalMessage and keptOnDeleteMessage name pipelines only when given names") {
        val k = Key("pgvector", "public", "vc_x")
        assert(VectorContainers.label(k).contains("public.vc_x"))
        assert(VectorContainers.label(Key("qdrant", "", "docs")).contains("docs"))

        val named = VectorContainers.refusalMessage(k, Some(Seq("vc_a", "vc_seed")))
        assert(named.contains("public.vc_x"), named)
        assert(named.contains("vc_a") && named.contains("vc_seed"), named)

        val anon = VectorContainers.refusalMessage(k, None)
        assert(anon.contains("public.vc_x"), anon)
        assert(anon.contains("already in use by another pipeline"), anon)
        assert(!anon.contains("vc_a") && !anon.contains("vc_seed"), anon)

        val kept = VectorContainers.keptOnDeleteMessage(k, Some(Seq("vc_a")))
        assert(kept.contains("Kept"), kept)
        assert(kept.contains("public.vc_x"), kept)
        assert(kept.contains("vc_a"), kept)

        val keptAnon = VectorContainers.keptOnDeleteMessage(k, None)
        assert(keptAnon.contains("Kept"), keptAnon)
        assert(keptAnon.contains("public.vc_x"), keptAnon)
        assert(!keptAnon.contains("vc_a"), keptAnon)
    }

    // ------------------------------------------------------------------------
    // Story: pgvector: two table names that PostgreSQL cuts to the same 63
    // bytes are treated as one table
    // (plans/stories/pgvector-long-names-one-table.md), Acceptance,
    // VectorContainersSpec (extended). The pgvector key compares the schema
    // and the table as PostgreSQL keeps them: the first 63 UTF-8 bytes, cut
    // on a character boundary. Other stores are unchanged.

    private val p63 = "v" * 63
    private val p62 = "v" * 62
    private val eAcute = "é"
    private val eGrave = "è"

    private def pgKey(table: String, schema: Option[String] = None) = VectorContainers.key("pgvector", table, schema)

    test("two pgvector names equal in their first 63 bytes have the same key; names that differ within 63 bytes do not") {
        // differ only after byte 63
        assert(pgKey(p63 + "_one").isDefined)
        assert(pgKey(p63 + "_one") == pgKey(p63 + "_two"))
        assert(pgKey(p63 + "_one") == pgKey(p63 + "_one_and_much_longer_still"))
        // a name of exactly 63 bytes is the table a longer name with that prefix lands in
        assert(pgKey(p63) == pgKey(p63 + "x"))
        // a two-byte letter straddling byte 63: PostgreSQL keeps 62 bytes, so
        // these three are one table
        assert(pgKey(p62 + eAcute) == pgKey(p62))
        assert(pgKey(p62 + eAcute) == pgKey(p62 + eGrave))
        assert(pgKey(p62 + eAcute + "_one") == pgKey(p62 + eGrave + "_two"))
        // differ within 63 bytes: two tables
        assert(pgKey(p63) != pgKey(p62 + "w"))
        assert(pgKey(p62 + "a_one") != pgKey(p62 + "b_one"))
        assert(pgKey(p62 + "a") != pgKey(p62 + eAcute)) // 63 bytes vs. 62 kept
        assert(pgKey("docs_one") != pgKey("docs_two"))
        // letter case still counts after the cut
        assert(pgKey("V" * 63 + "_one") != pgKey(p63 + "_one"))
        // the default schema is the same either way
        assert(pgKey(p63 + "_one", Some("public")) == pgKey(p63 + "_two", None))
        assert(pgKey(p63 + "_one", Some("public")) != pgKey(p63 + "_two", Some("other")))
        // short names keep the key they had
        assert(pgKey("vc_x") == Some(Key("pgvector", "public", "vc_x")))
        assert(pgKey("vc_x", Some("other")) == Some(Key("pgvector", "other", "vc_x")))
        assert(pgKey(p63) == Some(Key("pgvector", "public", p63)))
        // keysOf and namers use the same identity
        val a = pg("long_a", p63 + "_one", "public")
        val b = pg("long_b", p63 + "_two", null)
        val c = pg("long_c", p62 + "w_three", "public")
        assert(VectorContainers.keysOf(a) == VectorContainers.keysOf(b))
        assert(VectorContainers.keysOf(a) != VectorContainers.keysOf(c))
        assert(names(VectorContainers.namers(VectorContainers.keysOf(a).head, Seq(a, b, c))) == Set("long_a", "long_b"))
    }

    test("a long schema is cut the same way; qdrant, weaviate, milvus and chroma keys are unchanged") {
        val s63 = "s" * 63
        assert(pgKey("docs", Some(s63 + "_one")) == pgKey("docs", Some(s63 + "_two")))
        assert(pgKey("docs", Some(s63)) == pgKey("docs", Some(s63 + "x")))
        assert(pgKey("docs", Some("s" * 62 + eAcute)) == pgKey("docs", Some("s" * 62)))
        assert(pgKey("docs", Some("s" * 62 + "a_one")) != pgKey("docs", Some("s" * 62 + "b_one")))
        // schema and table are each cut on their own
        assert(pgKey(p63 + "_one", Some(s63 + "_one")) == pgKey(p63 + "_two", Some(s63 + "_two")))
        assert(pgKey(p63 + "_one", Some(s63 + "_one")) != pgKey(p63 + "_two", Some("s" * 62 + "t")))
        assert(pgKey(p63, Some("public")) != pgKey(p63, Some(s63)))
        // the other stores compare the full name as before (they refuse, not cut)
        for (store <- Seq("qdrant", "milvus", "chroma")) {
            assert(VectorContainers.key(store, p63 + "_one", None) == Some(Key(store, "", p63 + "_one")), store)
            assert(VectorContainers.key(store, p63 + "_one", None) != VectorContainers.key(store, p63 + "_two", None), store)
            assert(VectorContainers.key(store, p62 + eAcute, None) != VectorContainers.key(store, p62, None), store)
        }
        val w = VectorContainers.key("weaviate", "V" * 63 + "_One", None)
        assert(w == Some(Key("weaviate", "", ("v" * 63 + "_one"))))
        assert(w != VectorContainers.key("weaviate", "V" * 63 + "_Two", None))
        // a long pgvector name still never collides with another store
        assert(pgKey(p63 + "_one") != VectorContainers.key("qdrant", p63 + "_one", None))
    }

    test("saveConflicts reports a new pipeline whose long name collides with a stored one; a stored pair saved unchanged is not a conflict") {
        val a = pg("long_a", p63 + "_one", "public")
        val b = pg("long_b", p63 + "_two", "public")
        val unrelated = pg("short", "docs", "public")

        // a new pipeline whose table PostgreSQL cuts to the stored one's
        val fresh = VectorContainers.saveConflicts(b, null, Seq(a, unrelated))
        assert(fresh.size == 1, fresh)
        assert(names(fresh.head._2) == Set("long_a"))
        assert(fresh.head._1 == VectorContainers.keysOf(a).head)
        // the same through a two-byte letter straddling byte 63
        val straddle = pg("long_s", p62 + eAcute + "_three", null)
        assert(names(VectorContainers.saveConflicts(straddle, null, Seq(pg("short_62", p62, "public"))).flatMap(_._2)) == Set("short_62"))
        // a changed container onto the cut table
        val movedFrom = pg("long_b", "mine", "public")
        val moved = VectorContainers.saveConflicts(b, movedFrom, Seq(a, movedFrom))
        assert(moved.size == 1 && names(moved.head._2) == Set("long_a"), moved)
        // a long schema too
        val sa = pg("schema_a", "docs", "s" * 63 + "_one")
        val sb = pg("schema_b", "docs", "s" * 63 + "_two")
        assert(names(VectorContainers.saveConflicts(sb, null, Seq(sa)).flatMap(_._2)) == Set("schema_a"))

        // the stored pair (both already saved before this change) saved unchanged
        val others = Seq(a, b, unrelated)
        assert(VectorContainers.saveConflicts(b, b, others).isEmpty)
        assert(VectorContainers.saveConflicts(a, a, others).isEmpty)
        val edited = p("long_b", Destination(pgvector = PGVectorConfig(p63 + "_two", "public", ChunkingConfig(chunkSize = 900), null, null, null)))
        assert(VectorContainers.saveConflicts(edited, b, others).isEmpty)
        // stored with a null schema, candidate says public: still unchanged
        assert(VectorContainers.saveConflicts(b, pg("long_b", p63 + "_two", null), others).isEmpty)

        // names that differ within 63 bytes do not conflict
        assert(VectorContainers.saveConflicts(pg("long_c", p62 + "w_x", "public"), null, others).isEmpty)
    }

    test("search visibility sees every pipeline whose pgvector name PostgreSQL cuts to the searched table") {
        // Goal / Step 3 of the story (VectorSearchUtility goes through VectorContainers.key)
        val a = pg("long_a", p63 + "_one", "public")
        val b = pg("long_b", p63 + "_two", "public")
        val c = pg("long_c", p62 + "w_three", "public")
        val configs = Seq(a, b, c)
        for (t <- Seq(p63 + "_one", p63 + "_two", p63, p63 + "_anything"))
            assert(names(VectorSearchRegistry.pipelinesForContainer("pgvector", t, configs)) == Set("long_a", "long_b"), t)
        assert(names(VectorSearchRegistry.pipelinesForContainer("pgvector", p62 + "w_three", configs)) == Set("long_c"))
        assert(names(VectorSearchRegistry.pipelinesForContainer("pgvector", p63 + "_one", configs, schema = Some("other"))).isEmpty)
    }
}
