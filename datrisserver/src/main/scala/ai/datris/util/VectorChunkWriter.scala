package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import scala.collection.JavaConverters._

/** The pipeline that wrote a vector chunk, and the destination `metadata`
  * keys that may not overwrite it. Pure, no I/O.
  *
  * Every Datris vector loader stamps each chunk with the writing pipeline's
  * name in `source_pipeline` (pre-v1.3.0 chunks carry `source_dataset`). A
  * restricted user's search keeps only chunks whose writer is a pipeline it
  * can read ([[keepWrittenBy]]); the loaders write static metadata through
  * [[staticMetadata]] so a config key cannot replace the writer name.
  */
object VectorChunkWriter {

    /** Writer fields in precedence order: `source_pipeline` wins. */
    val WriterFields: Seq[String] = Seq("source_pipeline", "source_dataset")

    private val reservedLower: Set[String] = WriterFields.map(_.toLowerCase).toSet

    /** The first writer field whose value is non-null and non-blank, as
      * stored (no trim, no case fold). None when no field has a writer. */
    def writerOf(hit: java.util.Map[String, Any]): Option[String] =
        if (hit == null) None
        else
            WriterFields.iterator
                .map(f => hit.get(f))
                .collectFirst {
                    case v if v != null && String.valueOf(v).trim.nonEmpty => String.valueOf(v)
                }

    /** The hits whose writer is in `writers` (exact match), in order. A hit
      * with no writer is dropped. The input is never modified; when every
      * hit is kept the input instance itself is returned. */
    def keepWrittenBy(
        hits: java.util.List[java.util.Map[String, Any]],
        writers: Set[String]
    ): java.util.List[java.util.Map[String, Any]] = {
        if (hits == null || hits.isEmpty) return hits
        val kept = hits.asScala.filter(h => writerOf(h).exists(writers.contains))
        if (kept.size == hits.size) hits
        else new java.util.ArrayList[java.util.Map[String, Any]](kept.asJava)
    }

    private def isReserved(key: String): Boolean =
        key != null && reservedLower.contains(key.toLowerCase)

    /** The destination `metadata` entries a loader writes onto each chunk:
      * null values and keys equal to a writer field (ignoring case) dropped,
      * map order kept. A null map is Nil. */
    def staticMetadata(metadata: java.util.Map[String, String]): Seq[(String, String)] =
        if (metadata == null) Nil
        else
            metadata.asScala.toSeq.filter { case (k, v) => k != null && v != null && !isReserved(k) }

    /** The reserved keys present in `metadata`, original spelling, map order. */
    def ignoredKeys(metadata: java.util.Map[String, String]): Seq[String] =
        if (metadata == null) Nil
        else metadata.asScala.keys.filter(isReserved).toSeq
}
