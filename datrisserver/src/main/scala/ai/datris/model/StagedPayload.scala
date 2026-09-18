package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** On-disk shape of a staged pipeline payload (plans/streaming-pipeline.md, Phase 1). */
sealed trait StagedFormat {

    /** File extension used by the staging area for this format. */
    def extension: String
}

object StagedFormat {

    /** Delimited rows, one per line, header already stripped and columns already
      * projected to schema order (the exact lines `Data.rows` used to hold). */
    case class Delimited(delimiter: String) extends StagedFormat { val extension = "csv" }

    /** One JSON value per line. A top-level array is exploded into its elements;
      * a single object is one line; an NDJSON payload is kept line-per-record. */
    case object NdJson extends StagedFormat { val extension = "ndjson" }

    /** XML document, verbatim. */
    case object Xml extends StagedFormat { val extension = "xml" }

    /** Opaque text payload, verbatim (stream messages that are neither JSON nor XML). */
    case object Text extends StagedFormat { val extension = "txt" }

    /** Unstructured bytes (PDF, images, ...), verbatim. `Data.rawBytes` still
      * carries the same bytes in this phase — see the story's out-of-scope list. */
    case object Binary extends StagedFormat { val extension = "bin" }
}

/** Where a run's payload lives on local disk and what it looks like.
  *
  *  @param path        absolute path of the staged file, or null for an empty payload
  *  @param format      how the file is laid out
  *  @param rowCount    records in the file: delimited rows, NDJSON lines, 1 for a
  *                     non-empty XML/text/binary payload, 0 for an empty one
  *  @param bytes       size of the staged file
  *  @param arraySource JSON only: the payload arrived as one top-level array.
  *                     The deprecated `Data.rawData` re-wraps the NDJSON lines in
  *                     `[...]` so array-expecting readers see the shape they did before.
  */
case class StagedPayload(
    path: String,
    format: StagedFormat,
    rowCount: Long,
    bytes: Long,
    arraySource: Boolean = false
) {
    def isEmpty: Boolean = path == null
}

object StagedPayload {
    val empty: StagedPayload = StagedPayload(null, StagedFormat.Text, 0L, 0L)
}
