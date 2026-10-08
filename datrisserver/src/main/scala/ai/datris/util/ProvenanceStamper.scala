package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.{JsonElement, JsonObject, JsonParseException, JsonParser}
import org.slf4j.{Logger, LoggerFactory}

import java.nio.file.Files
import scala.collection.JavaConverters._

/** Appends per-run provenance onto a job's data, once, after transformation and
  * before any destination loader runs — so every destination sees the same
  * fields without per-loader code. Opt-in per pipeline (`provenance.stamp`).
  *
  * All values are constant for the run, so stamping is a cheap append:
  *  - delimited data: fields appended to `data.header`, every row, and the
  *    in-memory source AND destination schemas (both, so a pipeline whose
  *    schemas matched before stamping still takes the loaders' no-projection
  *    fast path). The stored pipeline definition is never touched — provenance
  *    columns must not leak into the config document, its version snapshots,
  *    or the dest-types dialog.
  *  - JSON payloads: keys injected into each object, streamed record by record
  *    through the staged NDJSON file. XML is not stamped.
  *  - unstructured/vector data (rawBytes): keys injected into the in-memory
  *    vector destination `metadata` maps; the vector loaders already write
  *    that map onto every chunk.
  *
  * Rows are never rewritten retroactively: provenance starts at the first run
  * after the toggle is turned on.
  *
  * Failure policy (PROVENANCE_STRICT): by default stamping is best effort, a
  * payload that cannot be stamped loads unstamped (a status line records the
  * miss when stamping threw). Under strict the run fails instead, before any
  * loader starts. XML sources are exempt in both modes: they are never stamped
  * and always load unstamped (under strict the exemption is written to the
  * job status).
  */
object ProvenanceStamper {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    val RunId = "_datris_run_id"
    val IngestedAt = "_datris_ingested_at"
    val ConfigVersion = "_datris_config_version"
    val TapRun = "_datris_tap_run"
    val ScriptSha = "_datris_script_sha"
    val Source = "_datris_source"

    val AllFields: List[String] = List(RunId, IngestedAt, ConfigVersion, TapRun, ScriptSha, Source)

    /** Prefix every stamped field carries; DestTypeInference treats it as
      * never-retyped so provenance columns cannot break the dest-types flow. */
    val Prefix = "_datris_"

    def enabled(config: PipelineConfig): Boolean =
        config != null && config.provenance != null && config.provenance.stamp

    /** What a stamper did with the payload. Only `NotStamped` (and a thrown
      * exception) fails a run, and only under PROVENANCE_STRICT. */
    private[util] sealed trait Outcome
    private[util] case object Stamped extends Outcome
    private[util] case object AlreadyStamped extends Outcome

    /** Nothing to stamp: an empty payload loads no rows. */
    private[util] case object NothingToStamp extends Outcome
    private[util] final case class Exempt(reason: String) extends Outcome
    private[util] final case class NotStamped(reason: String) extends Outcome

    val XmlExemptStatus = "Provenance stamping skipped (XML source is exempt from PROVENANCE_STRICT)"

    private[util] def strictFailureMessage(reason: String): String =
        "Provenance could not be stamped (" + reason + ") and PROVENANCE_STRICT is on; nothing was loaded"

    def stamp(ctx: JobContext): JobContext =
        stamp(ctx, Option(DatrisEnvironment.values).exists(_.provenanceStrict))

    /** `strict` = PROVENANCE_STRICT; the public overload reads the environment. */
    private[datris] def stamp(ctx: JobContext, strict: Boolean): JobContext = {
        if (!enabled(ctx.config)) return ctx
        val attempt: Either[Exception, (JobContext, Outcome, List[(String, String)])] =
            try Right(stampOnce(ctx))
            catch { case e: Exception => Left(e) }
        attempt match {
            case Left(e) if strict =>
                logger.error(
                    "ProvenanceStamper: stamping failed for pipeline: " + ctx.config.name + " — PROVENANCE_STRICT is on, failing the run: " + e.getMessage
                )
                throw new DatrisException(strictFailureMessage(String.valueOf(e.getMessage)))
            case Left(e) =>
                // Default (best effort): stamping never fails a job that would
                // otherwise load. Record the miss and continue unstamped.
                logger.warn("ProvenanceStamper: stamping failed for pipeline: " + ctx.config.name + " — loading unstamped: " + e.getMessage)
                if (ctx.statusUtil != null)
                    ctx.statusUtil.info("processing", "Provenance stamping skipped (error): " + e.getMessage)
                ctx
            case Right((stamped, outcome, values)) =>
                outcome match {
                    case NotStamped(reason) if strict =>
                        logger.error(
                            "ProvenanceStamper: pipeline " + ctx.config.name + " could not be stamped (" + reason + ") — PROVENANCE_STRICT is on, failing the run"
                        )
                        throw new DatrisException(strictFailureMessage(reason))
                    case Exempt(_) if strict =>
                        if (ctx.statusUtil != null) ctx.statusUtil.info("processing", XmlExemptStatus)
                    case _ =>
                }
                try {
                    if ((stamped ne ctx) && ctx.statusUtil != null)
                        ctx.statusUtil.info("processing", "Provenance stamped: " + values.map(_._1).mkString(", "))
                } catch {
                    case e: Exception => logger.warn("ProvenanceStamper: could not record the stamp status line: " + e.getMessage)
                }
                stamped
        }
    }

    /** One stamping attempt: the resulting context (unchanged when nothing was
      * stamped, exactly as best-effort mode loads it), what happened, and the
      * (field, value) pairs. Exceptions propagate to [[stamp]]. */
    private def stampOnce(ctx: JobContext): (JobContext, Outcome, List[(String, String)]) = {
        val values = stampValues(ctx, java.time.Instant.now().toString)
        if (values.isEmpty) return (ctx, NotStamped("provenance.fields selects no known field"), values)
        // Dispatch on the staged format: never touch the deprecated
        // `rows` / `rawData` accessors, which materialize the payload.
        val (stamped, outcome) =
            if (ctx.data == null) (ctx, NotStamped("the job has no data"))
            else if (ctx.data.isDelimited && ctx.data.header != null) stampDelimited(ctx, values)
            else if (ctx.data.isDocument) stampRaw(ctx, values)
            else if (ctx.data.rawBytes != null) stampVector(ctx, values)
            else (ctx, NotStamped("the data format cannot be stamped"))
        (stamped, outcome, values)
    }

    /** The (field, value) pairs for this run. Null values are kept (they stamp
      * as empty/absent) so column order is stable across runs of one pipeline. */
    private[util] def stampValues(ctx: JobContext, nowIso: String): List[(String, String)] = {
        val md = ctx.metadata
        val tapRun =
            if (md != null && md.tapName != null && md.tapRunTime != null) md.tapName + "|" + md.tapRunTime else null
        val all = List(
            RunId -> ctx.pipelineToken,
            IngestedAt -> nowIso,
            ConfigVersion -> String.valueOf(if (ctx.config.version > 0) ctx.config.version else 1),
            TapRun -> tapRun,
            ScriptSha -> (if (md != null) md.tapScriptSha else null),
            Source -> (if (md != null) md.tapSource else null)
        )
        val selected = Option(ctx.config.provenance.fields).map(_.asScala.toSet).filter(_.nonEmpty)
        selected match {
            case Some(s) => all.filter(p => s.contains(p._1))
            case None => all
        }
    }

    private def stampDelimited(ctx: JobContext, values: List[(String, String)]): (JobContext, Outcome) = {
        val data = ctx.data
        // Idempotence guard: never double-stamp (e.g. a replayed context).
        if (data.header.exists(_.startsWith(Prefix))) return (ctx, AlreadyStamped)

        val delimiter = CsvAttributes.delimiterOf(ctx.config)

        val names = values.map(_._1)
        val suffix = values.map(v => csvEncode(Option(v._2).getOrElse(""), delimiter)).mkString(delimiter)

        val newHeader = data.header ++ names
        // Row by row from the staged file into a new staged file: the payload
        // never lives in heap, whatever its size.
        val rows = data.rowIterator()
        val newStaged =
            try PayloadStager.stageRowIterator("stamp", rows.map(row => row + delimiter + suffix), delimiter)
            finally rows.close()
        val newHeaderWithSchema =
            if (data.headerWithSchema != null) data.headerWithSchema ++ names.map(n => SchemaField(n, "string"))
            else data.headerWithSchema

        // Append to BOTH in-memory schemas so loaders that compare them (fast
        // path) or read them positionally (object store) stay aligned. The
        // stored config is never written back.
        val newSource =
            if (ctx.config.source != null)
                ctx.config.source.copy(schemaProperties = appendSchemaFields(ctx.config.source.schemaProperties, names))
            else ctx.config.source
        val newDestination =
            if (ctx.config.destination != null)
                ctx.config.destination.copy(schemaProperties = appendSchemaFields(ctx.config.destination.schemaProperties, names))
            else ctx.config.destination

        (
            ctx.copy(
                data = data.withStaged(newStaged).copy(header = newHeader, headerWithSchema = newHeaderWithSchema),
                config = ctx.config.copy(source = newSource, destination = newDestination)
            ),
            Stamped
        )
    }

    private[util] def appendSchemaFields(sp: SchemaProperties, names: List[String]): SchemaProperties = {
        if (sp == null || sp.fields == null) return sp
        val existing = sp.fields.asScala.map(_.name.toLowerCase).toSet
        val list = new java.util.ArrayList[SchemaField](sp.fields)
        names.filterNot(existing.contains).foreach(n => list.add(SchemaField(n, "string")))
        sp.copy(fields = list)
    }

    /** Same quoting rule CSVReader applies at ingest. */
    private[datris] def csvEncode(value: String, delimiter: String): String = {
        if (value.contains(delimiter) || value.contains("\"") || value.contains("\n"))
            "\"" + value.replace("\"", "\"\"") + "\""
        else value
    }

    private def stampRaw(ctx: JobContext, values: List[(String, String)]): (JobContext, Outcome) = {
        // XML documents are not stamped — no safe generic injection point.
        // Exempt in both modes (PROVENANCE_STRICT included).
        if (
            ctx.config.source != null && ctx.config.source.fileAttributes != null &&
            ctx.config.source.fileAttributes.xmlAttributes != null
        ) return (ctx, Exempt("XML source"))
        // Only NDJSON is stampable; an opaque text document is left alone (as
        // `injectJson` did for anything that is not JSON).
        if (!ctx.data.isNdJson) return (ctx, NotStamped("the payload is not NDJSON"))
        stampNdJson(ctx, values) match {
            case (Some(staged), outcome) => (ctx.copy(data = ctx.data.withStaged(staged)), outcome)
            case (None, outcome) => (ctx, outcome)
        }
    }

    /** Stream the staged NDJSON through the stamp, one record per line, into a
      * new staged file. Same rules as [[injectJson]]: every line must parse, only
      * objects are stamped, an already-stamped payload (no object changed) is
      * left untouched. Returns the new staged payload (None when nothing was
      * stamped: the payload then loads as it was) and the outcome. A payload
      * that mixes objects with other JSON values is stamped as before, but its
      * outcome is NotStamped: the non-object records load without a stamp. */
    private def stampNdJson(ctx: JobContext, values: List[(String, String)]): (Option[StagedPayload], Outcome) = {
        val nonNull = values.filter(_._2 != null)
        if (nonNull.isEmpty) return (None, NotStamped("the selected provenance fields have no value for this run"))
        val source = ctx.data.staged
        if (source.isEmpty || source.rowCount == 0) return (None, NothingToStamp)
        val gson = PayloadStager.gson
        val format = StagedFormat.NdJson
        val (path, writer) = StagingArea.newWriter("stamp", format)
        var changed = false
        var failed = false
        var failedLine = 0L
        var nonObject = 0L
        var count = 0L
        val records = ctx.data.recordIterator()
        try {
            while (!failed && records.hasNext) {
                val line = records.next()
                val out =
                    if (line.trim.isEmpty) line
                    else {
                        val el = JsonParser.parseString(line)
                        if (el.isJsonObject) {
                            val obj = el.getAsJsonObject
                            if (!obj.has(RunId)) {
                                nonNull.foreach { case (k, v) => obj.addProperty(k, v) }
                                changed = true
                            }
                            gson.toJson(el)
                        } else { nonObject += 1; line }
                    }
                if (count > 0) writer.write("\n")
                writer.write(out)
                count += 1
            }
        } catch {
            // A line that does not parse: leave the payload untouched. Anything
            // else (an I/O failure writing the new file) propagates to stamp()'s
            // catch, which records the miss on the job status.
            case _: JsonParseException => failed = true; failedLine = count + 1
        } finally {
            records.close()
            writer.close()
        }
        if (failed || !changed) {
            Files.deleteIfExists(path)
            val outcome =
                if (failed) NotStamped("NDJSON line " + failedLine + " does not parse")
                else if (nonObject > 0) NotStamped(nonObject + " NDJSON record(s) are not JSON objects")
                else AlreadyStamped
            (None, outcome)
        } else {
            val outcome = if (nonObject > 0) NotStamped(nonObject + " NDJSON record(s) are not JSON objects") else Stamped
            (Some(StagedPayload(path.toString, format, count, Files.size(path), source.arraySource)), outcome)
        }
    }

    /** Inject the stamp keys into a JSON payload: array of objects, a single
      * object, or NDJSON lines. Returns null when the payload is not stampable
      * (unparseable, primitives, already stamped). */
    private[util] def injectJson(rawData: String, values: List[(String, String)]): String = {
        if (rawData == null || rawData.trim.isEmpty) return null
        val nonNull = values.filter(_._2 != null)
        if (nonNull.isEmpty) return null
        val gson = PayloadStager.gson

        def injectObject(obj: JsonObject): Boolean = {
            if (obj.has(RunId)) return false
            nonNull.foreach { case (k, v) => obj.addProperty(k, v) }
            true
        }

        try {
            val el: JsonElement = JsonParser.parseString(rawData)
            if (el.isJsonArray) {
                val arr = el.getAsJsonArray
                var changed = false
                val it = arr.iterator()
                while (it.hasNext) {
                    val e = it.next()
                    if (e.isJsonObject && injectObject(e.getAsJsonObject)) changed = true
                }
                if (changed) gson.toJson(arr) else null
            } else if (el.isJsonObject) {
                if (injectObject(el.getAsJsonObject)) gson.toJson(el) else null
            } else null
        } catch {
            case _: Exception =>
                // NDJSON: one JSON object per line. All lines must parse or we
                // leave the payload untouched.
                try {
                    val lines = rawData.split("\n")
                    val out = lines.map { line =>
                        if (line.trim.isEmpty) line
                        else {
                            val el = JsonParser.parseString(line)
                            if (el.isJsonObject) { injectObject(el.getAsJsonObject); gson.toJson(el) }
                            else line
                        }
                    }
                    out.mkString("\n")
                } catch {
                    case _: Exception => null
                }
        }
    }

    private def stampVector(ctx: JobContext, values: List[(String, String)]): (JobContext, Outcome) = {
        val nonNull = values.filter(_._2 != null)
        if (nonNull.isEmpty) return (ctx, NotStamped("the selected provenance fields have no value for this run"))
        val dest = ctx.config.destination
        if (dest == null) return (ctx, NotStamped("unstructured data with no vector destination"))
        val hasVector = dest.qdrant != null || dest.weaviate != null || dest.pgvector != null || dest.milvus != null || dest.chroma != null

        def extend(metadata: java.util.Map[String, String]): java.util.Map[String, String] = {
            val m = new java.util.LinkedHashMap[String, String]()
            if (metadata != null) m.putAll(metadata)
            nonNull.foreach { case (k, v) => m.put(k, v) }
            m
        }

        val newDest = dest.copy(
            qdrant = if (dest.qdrant != null) dest.qdrant.copy(metadata = extend(dest.qdrant.metadata)) else null,
            weaviate = if (dest.weaviate != null) dest.weaviate.copy(metadata = extend(dest.weaviate.metadata)) else null,
            pgvector = if (dest.pgvector != null) dest.pgvector.copy(metadata = extend(dest.pgvector.metadata)) else null,
            milvus = if (dest.milvus != null) dest.milvus.copy(metadata = extend(dest.milvus.metadata)) else null,
            chroma = if (dest.chroma != null) dest.chroma.copy(metadata = extend(dest.chroma.metadata)) else null
        )
        // Without a vector destination there is no metadata map to carry the
        // stamp: best effort proceeds exactly as before, strict refuses.
        (
            ctx.copy(config = ctx.config.copy(destination = newDest)),
            if (hasVector) Stamped else NotStamped("unstructured data with no vector destination")
        )
    }
}
