package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{PipelineConfig, TapConfig}
import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

/** Dataset discovery for `find_data`: rank the pipelines a caller may see by
  * lexical match against a natural-language query, and return where each one's
  * data lives, how fresh it is, and how to query it — without executing
  * anything on the caller's behalf. The `howToQuery` hint names an EXISTING
  * tool with pre-filled arguments; the agent still makes that call itself,
  * under its own capabilities.
  *
  * Ranking is deterministic (name, description, tags, catalog, destination
  * field names, source host). `ai=true` adds a rerank of the top candidates
  * by the primary AI slot; on any AI failure the lexical order stands.
  */
object CatalogFind {

    private val logger = LoggerFactory.getLogger(getClass)

    private[datris] val DefaultLimit = 5
    private[datris] val MaxLimit = 25
    private val RerankCandidates = 15

    /** One scored candidate with everything needed to render a hit. */
    private[datris] case class Candidate(pipeline: PipelineConfig, tap: Option[TapConfig], score: Double)

    private[datris] def tokenize(s: String): List[String] =
        Option(s).getOrElse("").toLowerCase.split("[^a-z0-9]+").filter(_.length > 1).toList

    /** Deterministic lexical score of one pipeline against the query tokens. */
    private[datris] def score(queryTokens: List[String], p: PipelineConfig, tap: Option[TapConfig]): Double = {
        def fieldTokens(s: String): List[String] = tokenize(s)
        def listTokens(l: java.util.List[String]): List[String] =
            if (l == null) Nil else l.asScala.toList.flatMap(fieldTokens)

        val weighted: List[(List[String], Double)] = List(
            fieldTokens(p.name) -> 3.0,
            listTokens(p.tags) -> 3.0,
            fieldTokens(p.catalog) -> 2.0,
            tap.map(t => fieldTokens(t.description)).getOrElse(Nil) -> 2.0,
            tap.map(t => fieldTokens(t.name)).getOrElse(Nil) -> 1.5,
            tap.map(t => listTokens(t.tags)).getOrElse(Nil) -> 2.0,
            destFieldTokens(p) -> 1.0,
            tap.map(t => fieldTokens(TapRunner.declaredSource(t))).getOrElse(Nil) -> 1.0
        )

        queryTokens.map { q =>
            weighted.map { case (tokens, weight) =>
                if (tokens.contains(q)) weight
                else if (tokens.exists(t => t.contains(q) || q.contains(t))) weight / 2
                else 0.0
            }.max
        }.sum
    }

    private def destFieldTokens(p: PipelineConfig): List[String] = {
        if (p.destination == null || p.destination.schemaProperties == null || p.destination.schemaProperties.fields == null) Nil
        else p.destination.schemaProperties.fields.asScala.toList.flatMap(f => tokenize(f.name))
    }

    /** The pre-filled query hint for a dataset. Null for destinations with no
      * query tool (kafka, activemq, rest). Copy stays neutral — placeholders,
      * no domain examples. */
    private[datris] def howToQuery(pipelineName: String, ds: LineageService.DatasetRef): JsonObject = {
        val coords = ds.coords.toMap
        def obj(tool: String)(args: (String, Any)*): JsonObject = {
            val o = new JsonObject()
            o.addProperty("tool", tool)
            val a = new JsonObject()
            args.foreach {
                case (k, v: String) => if (v != null && v.nonEmpty) a.addProperty(k, v)
                case (k, v: Int) => a.addProperty(k, v)
                case _ => ()
            }
            o.add("args", a)
            o
        }
        ds.kind match {
            case "postgres" =>
                val schema = coords.getOrElse("schema", "public")
                val table = coords.getOrElse("table", "")
                obj("query_postgres")("sql" -> ("SELECT * FROM \"" + schema + "\".\"" + table + "\" LIMIT 100"), "limit" -> 100)
            case "mongodb" => obj("query_mongodb")("collection" -> coords.getOrElse("collection", ""), "limit" -> 20)
            case "snowflake" => obj("query_snowflake")("pipeline" -> pipelineName)
            case "databricks" => obj("query_databricks")("pipeline" -> pipelineName)
            case "objectstore" => obj("query_objectstore")("pipeline" -> pipelineName, "limit" -> 100)
            case "qdrant" => obj("search_qdrant")("query" -> "<your question>", "collection" -> coords.getOrElse("collection", ""))
            case "weaviate" => obj("search_weaviate")("query" -> "<your question>", "class_name" -> coords.getOrElse("collection", ""))
            case "milvus" => obj("search_milvus")("query" -> "<your question>", "collection" -> coords.getOrElse("collection", ""))
            case "pgvector" =>
                obj("search_pgvector")(
                    "query" -> "<your question>",
                    "table" -> coords.getOrElse("table", ""),
                    "schema" -> coords.getOrElse("schema", "public")
                )
            case "chroma" => obj("search_chroma")("query" -> "<your question>", "collection" -> coords.getOrElse("collection", ""))
            case _ => null
        }
    }

    /** Rank + render. `visible` has already been capability-filtered by the
      * controller. */
    def find(query: String, limit: Int, ai: Boolean, visible: List[PipelineConfig], taps: List[TapConfig]): JsonObject = {
        val cappedLimit = math.max(1, math.min(if (limit <= 0) DefaultLimit else limit, MaxLimit))
        val queryTokens = tokenize(query)
        val tapForPipeline: Map[String, TapConfig] =
            taps.filter(t => t != null && t.targetPipeline != null).map(t => t.targetPipeline -> t).toMap

        val scored = visible
            .filter(_ != null)
            .map(p => Candidate(p, tapForPipeline.get(p.name), score(queryTokens, p, tapForPipeline.get(p.name))))
            .filter(c => queryTokens.isEmpty || c.score > 0)
            .sortBy(c => (-c.score, c.pipeline.name))

        val ordered =
            if (ai && scored.size > 1) rerank(query, scored.take(RerankCandidates)) ++ scored.drop(RerankCandidates)
            else scored

        val out = new JsonObject()
        val results = new JsonArray()
        ordered.take(cappedLimit).foreach(c => results.add(renderHit(c, taps)))
        out.add("results", results)
        out.addProperty("count", math.min(ordered.size, cappedLimit))
        out.addProperty("totalMatches", ordered.size)
        out
    }

    private def renderHit(c: Candidate, taps: List[TapConfig]): JsonObject = {
        val p = c.pipeline
        val o = new JsonObject()
        o.addProperty("name", p.name)
        c.tap.flatMap(t => Option(t.description)).foreach(o.addProperty("description", _))
        val tags = new JsonArray()
        if (p.tags != null) p.tags.asScala.foreach(tags.add)
        c.tap.foreach(t => if (t.tags != null) t.tags.asScala.foreach(tags.add))
        o.add("tags", tags)
        if (p.catalog != null) o.addProperty("catalog", p.catalog)
        o.addProperty("score", math.round(c.score * 100.0) / 100.0)

        val freshness = LineageService.freshness(p.name, taps)
        o.add("freshness", freshness)

        val dsets = LineageService.datasets(p)
        dsets.headOption.foreach { primary =>
            o.add("location", primary.toJson)
            val htq = howToQuery(p.name, primary)
            if (htq != null) o.add("howToQuery", htq)
        }
        if (dsets.size > 1) {
            val extra = new JsonArray()
            dsets.tail.foreach { ds =>
                val e = new JsonObject()
                e.add("location", ds.toJson)
                val htq = howToQuery(p.name, ds)
                if (htq != null) e.add("howToQuery", htq)
                extra.add(e)
            }
            o.add("additionalLocations", extra)
        }

        val provenance = new JsonObject()
        if (freshness.has("latestRunId")) provenance.addProperty("latestRunId", freshness.get("latestRunId").getAsString)
        provenance.addProperty("configVersion", if (p.version > 0) p.version else 1)
        c.tap.flatMap(t => Option(t.scriptCommitSha)).foreach(provenance.addProperty("scriptSha", _))
        o.add("provenance", provenance)

        val lineage = new JsonObject()
        val upstream = new JsonArray()
        c.tap.foreach(t => upstream.add("tap:" + t.name))
        lineage.add("upstream", upstream)
        val downstream = new JsonArray()
        dsets.foreach(ds => downstream.add(ds.id))
        lineage.add("downstream", downstream)
        o.add("lineage", lineage)
        o
    }

    /** Optional AI rerank of the lexical top candidates. Best-effort: any
      * failure (call, parse, unknown names) leaves the lexical order intact. */
    private def rerank(query: String, candidates: List[Candidate]): List[Candidate] = {
        try {
            val gson = new Gson
            val listing = candidates.map { c =>
                val desc = c.tap.flatMap(t => Option(t.description)).getOrElse("")
                val tags = Option(c.pipeline.tags).map(_.asScala.mkString(", ")).getOrElse("")
                c.pipeline.name + " — " + desc + (if (tags.nonEmpty) " [" + tags + "]" else "")
            }.mkString("\n")
            val system = "You rank datasets by relevance to a request. " +
                "Reply with ONLY a JSON array of dataset names, best match first. Include every listed name exactly once."
            val user = "Request: " + query + "\n\nDatasets:\n" + listing
            val response = AIUtil.extractText(AIUtil.callAIWithSystem(system, user))
            val start = response.indexOf('[')
            val end = response.lastIndexOf(']')
            if (start < 0 || end <= start) return candidates
            val names = JsonParser.parseString(response.substring(start, end + 1)).getAsJsonArray.asScala
                .map(_.getAsString).toList
            val byName = candidates.map(c => c.pipeline.name -> c).toMap
            val reranked = names.flatMap(byName.get)
            if (reranked.isEmpty) candidates
            else reranked ++ candidates.filterNot(c => names.contains(c.pipeline.name))
        } catch {
            case e: Exception =>
                logger.debug("CatalogFind: AI rerank failed, keeping lexical order: " + e.getMessage)
                candidates
        }
    }
}
