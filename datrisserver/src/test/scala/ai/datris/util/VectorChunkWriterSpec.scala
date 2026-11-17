package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** Story: Catalog-scoped user access: a restricted user's vector search returns
  * only chunks written by pipelines they can see
  * (plans/stories/catalog-scoped-user-access-shared-containers.md), Acceptance,
  * VectorChunkWriterSpec.
  *
  * Pure helper, no Spring, no store. Assumed:
  * {{{
  * object VectorChunkWriter {
  *   val WriterFields: Seq[String] // Seq("source_pipeline", "source_dataset")
  *   def writerOf(hit: java.util.Map[String, Any]): Option[String]
  *   def keepWrittenBy(hits: java.util.List[java.util.Map[String, Any]],
  *                     writers: Set[String]): java.util.List[java.util.Map[String, Any]]
  *   def staticMetadata(metadata: java.util.Map[String, String]): Seq[(String, String)]
  *   def ignoredKeys(metadata: java.util.Map[String, String]): Seq[String]
  * }
  * }}}
  */
class VectorChunkWriterSpec extends AnyFunSuite {

    private def hit(pairs: (String, Any)*): java.util.Map[String, Any] = {
        val m = new java.util.LinkedHashMap[String, Any]()
        pairs.foreach { case (k, v) => m.put(k, v) }
        m
    }

    private def hits(hs: java.util.Map[String, Any]*): java.util.List[java.util.Map[String, Any]] = {
        val l = new java.util.ArrayList[java.util.Map[String, Any]]()
        hs.foreach(l.add)
        l
    }

    private def meta(pairs: (String, String)*): java.util.Map[String, String] = {
        val m = new java.util.LinkedHashMap[String, String]()
        pairs.foreach { case (k, v) => m.put(k, v) }
        m
    }

    private def ids(l: java.util.List[java.util.Map[String, Any]]): Seq[Any] = {
        val out = Seq.newBuilder[Any]
        val it = l.iterator()
        while (it.hasNext) out += it.next().get("id")
        out.result()
    }

    test("writerOf reads source_pipeline, falls back to source_dataset, and is empty for a missing, null or blank writer") {
        assert(VectorChunkWriter.WriterFields == Seq("source_pipeline", "source_dataset"))

        assert(VectorChunkWriter.writerOf(hit("text" -> "t", "source_pipeline" -> "sales_docs")).contains("sales_docs"))
        // pre-v1.3.0 chunk (D4)
        assert(VectorChunkWriter.writerOf(hit("text" -> "t", "source_dataset" -> "old_docs")).contains("old_docs"))

        assert(VectorChunkWriter.writerOf(hit("text" -> "t")).isEmpty)
        assert(VectorChunkWriter.writerOf(hit()).isEmpty)
        assert(VectorChunkWriter.writerOf(hit("source_pipeline" -> null)).isEmpty)
        assert(VectorChunkWriter.writerOf(hit("source_pipeline" -> "")).isEmpty)
        assert(VectorChunkWriter.writerOf(hit("source_pipeline" -> "   ")).isEmpty)
        assert(VectorChunkWriter.writerOf(hit("source_pipeline" -> null, "source_dataset" -> "")).isEmpty)

        // "first field whose value is non-null and non-blank": a null or blank
        // source_pipeline falls back to source_dataset
        assert(VectorChunkWriter.writerOf(hit("source_pipeline" -> null, "source_dataset" -> "old_docs")).contains("old_docs"))
        assert(VectorChunkWriter.writerOf(hit("source_pipeline" -> " ", "source_dataset" -> "old_docs")).contains("old_docs"))

        // no trim, no case fold
        assert(VectorChunkWriter.writerOf(hit("source_pipeline" -> " Sales_Docs")).contains(" Sales_Docs"))
    }

    test("writerOf prefers source_pipeline when both fields are present") {
        assert(VectorChunkWriter.writerOf(hit("source_dataset" -> "old_name", "source_pipeline" -> "new_name")).contains("new_name"))
        assert(VectorChunkWriter.writerOf(hit("source_pipeline" -> "new_name", "source_dataset" -> "old_name")).contains("new_name"))
    }

    test("keepWrittenBy keeps only hits whose writer is allowed, exactly and case-sensitively, in order") {
        val in = hits(
            hit("id" -> 1, "source_pipeline" -> "sales_docs"),
            hit("id" -> 2, "source_pipeline" -> "mkt_docs"),
            hit("id" -> 3, "source_pipeline" -> "Sales_Docs"),
            hit("id" -> 4, "source_dataset" -> "sales_old"),
            hit("id" -> 5, "source_pipeline" -> "sales_docs "),
            hit("id" -> 6, "source_pipeline" -> "sales_docs"),
            hit("id" -> 7, "source_pipeline" -> "sales"),
            hit("id" -> 8, "source_pipeline" -> "mkt_docs", "source_dataset" -> "sales_old")
        )
        val out = VectorChunkWriter.keepWrittenBy(in, Set("sales_docs", "sales_old"))
        assert(ids(out) == Seq(1, 4, 6))
        // the input list is not modified
        assert(in.size == 8)

        assert(VectorChunkWriter.keepWrittenBy(in, Set.empty).isEmpty)
    }

    test("keepWrittenBy drops hits with no writer field") {
        val in = hits(
            hit("id" -> 1, "text" -> "a"),
            hit("id" -> 2, "source_pipeline" -> "sales_docs"),
            hit("id" -> 3, "source_pipeline" -> null),
            hit("id" -> 4, "source_pipeline" -> ""),
            hit("id" -> 5, "source_dataset" -> "  ")
        )
        assert(ids(VectorChunkWriter.keepWrittenBy(in, Set("sales_docs", "", "  "))) == Seq(2))
    }

    test("keepWrittenBy returns the same list instance when every hit is kept") {
        val in = hits(hit("id" -> 1, "source_pipeline" -> "a"), hit("id" -> 2, "source_dataset" -> "b"))
        assert(VectorChunkWriter.keepWrittenBy(in, Set("a", "b")) eq in)

        val empty = hits()
        assert(VectorChunkWriter.keepWrittenBy(empty, Set("a")) eq empty)
        assert(VectorChunkWriter.keepWrittenBy(empty, Set.empty) eq empty)
    }

    test("staticMetadata drops source_pipeline and source_dataset in any letter case and null values, keeps order") {
        val m = meta(
            "team" -> "a",
            "source_pipeline" -> "someone_else",
            "Source_Pipeline" -> "x",
            "region" -> "us",
            "SOURCE_DATASET" -> "y",
            "source_dataset" -> "z",
            "empty_ok" -> "",
            "nothing" -> null,
            "source_pipeline_note" -> "kept",
            "owner" -> "o"
        )
        assert(
            VectorChunkWriter.staticMetadata(m) ==
                Seq("team" -> "a", "region" -> "us", "empty_ok" -> "", "source_pipeline_note" -> "kept", "owner" -> "o")
        )
        assert(VectorChunkWriter.staticMetadata(null) == Nil)
        assert(VectorChunkWriter.staticMetadata(meta()) == Nil)
    }

    test("staticMetadata keeps the provenance stamp keys") {
        val stamps = ProvenanceStamper.AllFields.zipWithIndex.map { case (k, i) => k -> ("v" + i) }
        val m = meta((("team" -> "a") +: stamps :+ ("source_pipeline" -> "spoof")): _*)
        assert(VectorChunkWriter.staticMetadata(m) == (("team" -> "a") +: stamps))
        assert(VectorChunkWriter.ignoredKeys(m) == Seq("source_pipeline"))
    }

    test("ignoredKeys lists the reserved keys present and nothing for a null map") {
        assert(VectorChunkWriter.ignoredKeys(null) == Nil)
        assert(VectorChunkWriter.ignoredKeys(meta()) == Nil)
        assert(VectorChunkWriter.ignoredKeys(meta("team" -> "a", "source_pipeline_note" -> "b")) == Nil)
        assert(
            VectorChunkWriter.ignoredKeys(meta("team" -> "a", "Source_Dataset" -> "x", "region" -> "us", "source_pipeline" -> "y")) ==
                Seq("Source_Dataset", "source_pipeline")
        )
    }
}
