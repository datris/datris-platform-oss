package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.{Gson, JsonElement, JsonObject, JsonParser}
import org.slf4j.{Logger, LoggerFactory}

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
  *  - JSON rawData: keys injected into each object (array, single object, or
  *    NDJSON lines). XML is not stamped.
  *  - unstructured/vector data (rawBytes): keys injected into the in-memory
  *    vector destination `metadata` maps; the vector loaders already write
  *    that map onto every chunk.
  *
  * Rows are never rewritten retroactively: provenance starts at the first run
  * after the toggle is turned on.
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

    def stamp(ctx: JobContext): JobContext = {
        if (!enabled(ctx.config)) return ctx
        try {
            val values = stampValues(ctx, java.time.Instant.now().toString)
            if (values.isEmpty) return ctx
            val stamped =
                if (ctx.data == null) ctx
                else if (ctx.data.rows != null && ctx.data.header != null) stampDelimited(ctx, values)
                else if (ctx.data.rawData != null) stampRaw(ctx, values)
                else if (ctx.data.rawBytes != null) stampVector(ctx, values)
                else ctx
            if ((stamped ne ctx) && ctx.statusUtil != null)
                ctx.statusUtil.info("processing", "Provenance stamped: " + values.map(_._1).mkString(", "))
            stamped
        } catch {
            // Stamping must never fail a job that would otherwise load. Record
            // the miss and continue unstamped.
            case e: Exception =>
                logger.warn("ProvenanceStamper: stamping failed for pipeline: " + ctx.config.name + " — loading unstamped: " + e.getMessage)
                if (ctx.statusUtil != null)
                    ctx.statusUtil.info("processing", "Provenance stamping skipped (error): " + e.getMessage)
                ctx
        }
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

    private def stampDelimited(ctx: JobContext, values: List[(String, String)]): JobContext = {
        val data = ctx.data
        // Idempotence guard: never double-stamp (e.g. a replayed context).
        if (data.header.exists(_.startsWith(Prefix))) return ctx

        val delimiter =
            if (ctx.config.source != null && ctx.config.source.fileAttributes != null && ctx.config.source.fileAttributes.csvAttributes != null)
                ctx.config.source.fileAttributes.csvAttributes.delimiter
            else ","

        val names = values.map(_._1)
        val suffix = values.map(v => csvEncode(Option(v._2).getOrElse(""), delimiter)).mkString(delimiter)

        val newHeader = data.header ++ names
        val newRows = data.rows.map(row => row + delimiter + suffix)
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

        ctx.copy(
            data = data.copy(header = newHeader, headerWithSchema = newHeaderWithSchema, rows = newRows),
            config = ctx.config.copy(source = newSource, destination = newDestination)
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
    private[util] def csvEncode(value: String, delimiter: String): String = {
        if (value.contains(delimiter) || value.contains("\"") || value.contains("\n"))
            "\"" + value.replace("\"", "\"\"") + "\""
        else value
    }

    private def stampRaw(ctx: JobContext, values: List[(String, String)]): JobContext = {
        // XML documents are not stamped — no safe generic injection point.
        if (
            ctx.config.source != null && ctx.config.source.fileAttributes != null &&
            ctx.config.source.fileAttributes.xmlAttributes != null
        ) return ctx
        val injected = injectJson(ctx.data.rawData, values)
        if (injected == null) ctx
        else ctx.copy(data = ctx.data.copy(rawData = injected))
    }

    /** Inject the stamp keys into a JSON payload: array of objects, a single
      * object, or NDJSON lines. Returns null when the payload is not stampable
      * (unparseable, primitives, already stamped). */
    private[util] def injectJson(rawData: String, values: List[(String, String)]): String = {
        if (rawData == null || rawData.trim.isEmpty) return null
        val nonNull = values.filter(_._2 != null)
        if (nonNull.isEmpty) return null
        val gson = new Gson

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

    private def stampVector(ctx: JobContext, values: List[(String, String)]): JobContext = {
        val nonNull = values.filter(_._2 != null)
        if (nonNull.isEmpty) return ctx
        val dest = ctx.config.destination
        if (dest == null) return ctx

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
        ctx.copy(config = ctx.config.copy(destination = newDest))
    }
}
