package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

/** Column-level lineage for one pipeline definition version (plan L3).
  *
  * Two tiers:
  *  - **Deterministic** (always, never cached — it is a pure function of the
  *    config): source `schemaProperties.fields` vs destination
  *    `schemaProperties.fields`. Name-identical fields are `passthrough`
  *    edges with confidence `exact`; when the pipeline has no transformation
  *    the leftovers are `drop` (source-only) or `unresolved` (destination-only).
  *    Stamped `_datris_*` columns are `system` edges.
  *  - **Inferred** (opt-in per request, cached per `pipeline|version`): when
  *    the pipeline has an AI transformation, the codegen model reads the
  *    instruction, the field lists and — when a run has happened — the last
  *    generated script, and returns only mappings the evidence supports.
  *    Never blocks a run: computed on request, out of band.
  */
object ColumnLineageService {

    private val logger = LoggerFactory.getLogger(getClass)
    private val gson = new Gson()

    private val SystemPrompt =
        """You extract column lineage from a data transformation. You are given the input fields,
          |the output fields, the plain-English transformation instruction, and — when available —
          |the Python script that was generated to implement it.
          |
          |Return ONLY a JSON array, no prose, no markdown fences. Each element:
          |  {"from": ["<input field>", ...], "to": "<output field>", "op": "rename|derive|passthrough|drop", "evidence": "<short quote or phrase from the instruction/script>"}
          |
          |Rules:
          |- Include ONLY mappings the instruction or script actually evidences. If you cannot point to
          |  evidence for a mapping, leave it out. Return [] when nothing is evidenced.
          |- Never invent fields that are not in the input or output lists.
          |- "drop" has an empty "to". "derive" lists every input field the output depends on.
          |- Do not repeat mappings already marked exact in the given list.""".stripMargin

    // ---------------------------------------------------------------- shape

    case class Result(
        pipeline: String,
        version: Int,
        versionSource: String,
        sourceFields: List[String],
        destinationFields: List[String],
        destinationSchema: String, // declared | inherited | none
        transformation: JsonObject,
        edges: List[ColumnEdge],
        unresolved: List[String],
        inferred: JsonObject
    ) {
        def toJson: JsonObject = {
            val o = new JsonObject()
            o.addProperty("pipeline", pipeline)
            o.addProperty("version", version)
            o.addProperty("versionSource", versionSource)
            val sf = new JsonArray(); sourceFields.foreach(sf.add); o.add("sourceFields", sf)
            val df = new JsonArray(); destinationFields.foreach(df.add); o.add("destinationFields", df)
            o.addProperty("destinationSchema", destinationSchema)
            o.add("transformation", transformation)
            val e = new JsonArray(); edges.foreach(x => e.add(gson.toJsonTree(x))); o.add("edges", e)
            val u = new JsonArray(); unresolved.foreach(u.add); o.add("unresolved", u)
            o.add("inferred", inferred)
            o
        }
    }

    // --------------------------------------------------------------- config

    /** The config for `version` (a snapshot) or the current one when null. */
    private def configFor(pipeline: String, version: Option[Int]): Option[(PipelineConfig, String)] = {
        val env = DatrisEnvironment.current
        val current = PipelineConfigIO.read(env.pipelineTableName, pipeline)
        version match {
            case Some(v) if current == null || v != math.max(current.version, 1) =>
                EntityVersionIO.get(env.pipelineVersionTableName, pipeline, v).flatMap { snap =>
                    try Option(gson.fromJson(snap.config, classOf[PipelineConfig])).map(c => (c, "snapshot"))
                    catch { case _: Exception => None }
                }
            case _ => Option(current).map(c => (c, "current"))
        }
    }

    private def fieldNames(sp: SchemaProperties): List[String] =
        if (sp == null || sp.fields == null) Nil
        else sp.fields.asScala.toList.filter(f => f != null && f.name != null && f.name.nonEmpty).map(_.name)

    private[datris] def transformationInfo(c: PipelineConfig): JsonObject = {
        val o = new JsonObject()
        val t = c.transformation
        if (t != null && t.aiTransformation != null && t.aiTransformation.instruction != null) {
            o.addProperty("kind", "ai")
            o.addProperty("instruction", t.aiTransformation.instruction)
        } else if (t != null && t.rowFunctions != null && !t.rowFunctions.isEmpty) {
            o.addProperty("kind", "rowFunctions")
        } else if (c.preprocessor != null) {
            o.addProperty("kind", "preprocessor")
        } else {
            o.addProperty("kind", "none")
        }
        o
    }

    // --------------------------------------------------------- deterministic

    /** Pure: the exact tier from a config alone. Returns (edges, unresolved
      * destination fields, destinationSchema label). */
    private[datris] def deterministic(c: PipelineConfig): (List[ColumnEdge], List[String], String) = {
        val src = fieldNames(if (c.source != null) c.source.schemaProperties else null)
        val declaredDst = fieldNames(if (c.destination != null) c.destination.schemaProperties else null)
        val stamped: List[String] =
            if (c.provenance != null && c.provenance.stamp) {
                val sel = Option(c.provenance.fields).map(_.asScala.toSet).filter(_.nonEmpty)
                ProvenanceStamper.AllFields.filter(f => sel.forall(_.contains(f)))
            } else Nil
        val hasTransformation = transformationInfo(c).get("kind").getAsString != "none"

        val (dst, schemaLabel) =
            if (declaredDst.nonEmpty) (declaredDst.filterNot(_.startsWith(ProvenanceStamper.Prefix)), "declared")
            else if (src.nonEmpty && !hasTransformation) (src, "inherited")
            else (Nil, "none")

        val edges = List.newBuilder[ColumnEdge]
        val unresolved = List.newBuilder[String]
        val srcSet = src.toSet
        dst.foreach { d =>
            if (srcSet.contains(d)) edges += ColumnEdge(List(d).asJava, d, "passthrough", "exact")
            else unresolved += d
        }
        if (!hasTransformation && dst.nonEmpty) {
            val dstSet = dst.toSet
            src.filterNot(dstSet.contains).foreach(s => edges += ColumnEdge(List(s).asJava, "", "drop", "exact"))
        }
        stamped.foreach(f => edges += ColumnEdge(new java.util.ArrayList[String](), f, "system", "system", "provenance stamp"))
        (edges.result(), unresolved.result(), schemaLabel)
    }

    // -------------------------------------------------------------- inferred

    private def cacheKey(pipeline: String, version: Int) = pipeline + "|" + version

    private def readCache(pipeline: String, version: Int): Option[InferredColumnLineage] =
        try NoSQLDbUtil.getItemJSON(DatrisEnvironment.current.columnLineageTableName, "key", cacheKey(pipeline, version), "value")
                .map(gson.fromJson(_, classOf[InferredColumnLineage]))
        catch { case _: Exception => None }

    private def writeCache(rec: InferredColumnLineage): Unit =
        try NoSQLDbUtil.putItemJSON(DatrisEnvironment.current.columnLineageTableName, "key", cacheKey(rec.pipeline, rec.version), "value", gson.toJson(rec))
        catch { case e: Exception => logger.warn("column-lineage cache write failed: " + e.getMessage) }

    /** Parse the model's JSON array into edges, dropping anything that names
      * fields outside the known lists. Pure — unit-tested. */
    private[datris] def parseInferred(text: String, src: List[String], dst: List[String]): List[ColumnEdge] = {
        if (text == null) return Nil
        val cleaned = text.trim.stripPrefix("```json").stripPrefix("```").stripSuffix("```").trim
        val start = cleaned.indexOf('['); val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) return Nil
        val el =
            try JsonParser.parseString(cleaned.substring(start, end + 1))
            catch { case _: Exception => return Nil }
        if (!el.isJsonArray) return Nil
        val srcSet = src.toSet; val dstSet = dst.toSet
        val ops = Set("rename", "derive", "passthrough", "drop")
        el.getAsJsonArray.asScala.toList.flatMap { e =>
            try {
                val o = e.getAsJsonObject
                val from = if (o.has("from") && o.get("from").isJsonArray) o.getAsJsonArray("from").asScala.map(_.getAsString).toList else Nil
                val to = if (o.has("to") && !o.get("to").isJsonNull) o.get("to").getAsString else ""
                val op = if (o.has("op")) o.get("op").getAsString.toLowerCase else ""
                val evidence = if (o.has("evidence") && !o.get("evidence").isJsonNull) o.get("evidence").getAsString.take(200) else null
                val fromOk = from.nonEmpty && from.forall(srcSet.contains)
                val toOk = if (op == "drop") to.isEmpty else dstSet.contains(to)
                if (ops.contains(op) && fromOk && toOk) Some(ColumnEdge(from.asJava, to, op, "inferred", evidence)) else None
            } catch { case _: Exception => None }
        }
    }

    private def infer(c: PipelineConfig, version: Int, src: List[String], dst: List[String], exact: List[ColumnEdge]): InferredColumnLineage = {
        val instruction = c.transformation.aiTransformation.instruction
        val script = CodeGenScriptIO.read(c.name, "transformation").filter(_.instruction == instruction)
        val user = new StringBuilder()
        user.append("Input fields: ").append(src.mkString(", ")).append("\n")
        user.append("Output fields: ").append(dst.mkString(", ")).append("\n")
        user.append("Already exact (passthrough): ").append(exact.filter(_.op == "passthrough").map(_.to).mkString(", ")).append("\n\n")
        user.append("Transformation instruction: \"").append(instruction).append("\"\n")
        script.foreach(s => user.append("\nGenerated script implementing it:\n").append(s.script.take(12000)).append("\n"))
        val cfg = DatrisEnvironment.aiConfigForCodegen
        val text = AIUtil.extractText(AIUtil.callAIWithSystem(SystemPrompt, user.toString, cfg), cfg)
        val edges = parseInferred(text, src, dst)
        InferredColumnLineage(
            c.name,
            version,
            edges.asJava,
            cfg.model,
            java.time.Instant.now().toString,
            if (edges.isEmpty) "No mappings evidenced by the instruction" + (if (script.isDefined) " or script" else "") else null
        )
    }

    // ---------------------------------------------------------------- entry

    /** Column lineage for a pipeline. `version` None ⇒ current definition.
      * `runInference` true ⇒ compute (and cache) the inferred tier when the
      * pipeline has an AI transformation and no cache exists yet. Null when
      * the pipeline (or that version) does not exist. */
    def forPipeline(pipeline: String, version: Option[Int], runInference: Boolean): Result = {
        val (c, versionSource) = configFor(pipeline, version).getOrElse(return null)
        val v = if (version.isDefined) version.get else math.max(c.version, 1)
        val src = fieldNames(if (c.source != null) c.source.schemaProperties else null)
        val (exact, unresolved, schemaLabel) = deterministic(c)
        // Destination fields in declared order (stamped columns excluded); when
        // the schema is inherited this is the source order.
        val declaredDst = fieldNames(if (c.destination != null) c.destination.schemaProperties else null).filterNot(_.startsWith(ProvenanceStamper.Prefix))
        val dst = if (declaredDst.nonEmpty) declaredDst else exact.filter(_.op == "passthrough").map(_.to) ++ unresolved
        val tx = transformationInfo(c)
        val canInfer = tx.get("kind").getAsString == "ai" && (src.nonEmpty || dst.nonEmpty)

        val inferredMeta = new JsonObject()
        inferredMeta.addProperty("available", canInfer)
        var inferredEdges: List[ColumnEdge] = Nil
        if (canInfer) {
            val cached = readCache(pipeline, v)
            val rec: Option[InferredColumnLineage] =
                if (cached.isDefined) cached
                else if (runInference && DatrisEnvironment.current.aiEnabled) {
                    try { val r = infer(c, v, src, dst, exact); writeCache(r); Some(r) }
                    catch {
                        case e: Exception =>
                            logger.warn("column-lineage inference failed for " + pipeline + " v" + v + ": " + e.getMessage)
                            inferredMeta.addProperty("error", Option(e.getMessage).getOrElse("inference failed").take(300))
                            None
                    }
                } else None
            rec.foreach { r =>
                inferredEdges = Option(r.edges).map(_.asScala.toList).getOrElse(Nil)
                inferredMeta.addProperty("computed", true)
                if (r.computedAt != null) inferredMeta.addProperty("computedAt", r.computedAt)
                if (r.model != null) inferredMeta.addProperty("model", r.model)
                if (r.note != null) inferredMeta.addProperty("note", r.note)
            }
            if (rec.isEmpty) inferredMeta.addProperty("computed", false)
            if (rec.isEmpty && runInference && !DatrisEnvironment.current.aiEnabled) inferredMeta.addProperty("error", "AI is not enabled on this server")
        }

        val resolvedByInference = inferredEdges.map(_.to).toSet
        Result(
            pipeline,
            v,
            versionSource,
            src,
            dst,
            schemaLabel,
            tx,
            exact ++ inferredEdges,
            unresolved.filterNot(resolvedByInference.contains),
            inferredMeta
        )
    }
}
