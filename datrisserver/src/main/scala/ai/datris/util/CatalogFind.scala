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

    /** Pipeline tags then tap tags, deduplicated — a label shared by both
      * shows once in the hit. */
    private[datris] def mergedTags(p: PipelineConfig, tap: Option[TapConfig]): List[String] =
        (Option(p.tags).map(_.asScala.toList).getOrElse(Nil) ++
            tap.flatMap(t => Option(t.tags)).map(_.asScala.toList).getOrElse(Nil)).distinct

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
            // Scratch results expire and are never queryable later: no hint.
            case "scratch" => null
            case _ => null
        }
    }

    /** Rank + render. `visible` has already been capability-filtered by the
      * controller. `unityCatalog`, when present, appends federated Unity
      * Catalog tables after the Datris hits (and tags every hit with its
      * `source`); when absent the output is exactly the Datris-only shape. */
    def find(
        query: String,
        limit: Int,
        ai: Boolean,
        visible: List[PipelineConfig],
        taps: List[TapConfig],
        unityCatalog: Option[UnityCatalogResults] = None
    ): JsonObject = {
        val cappedLimit = math.max(1, math.min(if (limit <= 0) DefaultLimit else limit, MaxLimit))
        val queryTokens = tokenize(query)
        val tapForPipeline: Map[String, TapConfig] =
            taps.filter(t => t != null && t.targetPipeline != null).map(t => t.targetPipeline -> t).toMap

        val scored = visible
            .filter(_ != null)
            // Scratch pipelines land no dataset anyone can query later
            // (LineageService.datasets yields nothing for them), so they are
            // never a findable dataset.
            .filter(p => p.destination == null || p.destination.scratch == null)
            .map(p => Candidate(p, tapForPipeline.get(p.name), score(queryTokens, p, tapForPipeline.get(p.name))))
            .filter(c => queryTokens.isEmpty || c.score > 0)
            // Ties: pipelines whose primary dataset is the system of record first.
            .sortBy(c =>
                (
                    -c.score,
                    LineageService.datasets(c.pipeline).headOption.flatMap(d => LineageService.authorityOfDataset(d.id)) match {
                        case Some(LineageService.AuthorityAuthoritative) => 0
                        case Some(LineageService.AuthorityUndeclared) => 1
                        case _ => 2
                    },
                    c.pipeline.name
                )
            )

        val ordered =
            if (ai && scored.size > 1) rerank(query, scored.take(RerankCandidates)) ++ scored.drop(RerankCandidates)
            else scored

        val out = new JsonObject()
        val results = new JsonArray()
        val shown = ordered.take(cappedLimit)
        val withSource = unityCatalog.isDefined
        shown.foreach(c => results.add(renderHit(c, taps, withSource)))
        unityCatalog match {
            case None =>
                out.add("results", results)
                out.addProperty("count", math.min(ordered.size, cappedLimit))
                out.addProperty("totalMatches", ordered.size)
            case Some(uc) =>
                val ucRanked = rankUnityCatalog(uc.hits, visible, shown.map(_.pipeline.name).toSet)
                ucRanked.take(cappedLimit).foreach { case (h, owner) => results.add(renderUcHit(h, owner)) }
                out.add("results", results)
                out.addProperty("count", results.size())
                out.addProperty("totalMatches", ordered.size + ucRanked.size)
                val block = new JsonObject()
                val searched = new JsonArray()
                uc.searched.foreach(searched.add)
                block.add("searched", searched)
                val skipped = new JsonArray()
                uc.skipped.foreach { case (secret, reason) =>
                    val sk = new JsonObject()
                    sk.addProperty("secret", secret)
                    sk.addProperty("reason", reason)
                    skipped.add(sk)
                }
                block.add("skipped", skipped)
                out.add("unityCatalog", block)
        }
        out
    }

    /** Owner of a UC table among the visible pipelines: a Databricks pipeline
      * whose catalog/schema/table equal the table's (compared the way Unity
      * Catalog stores names), else the pipeline the `datris_pipeline` tag
      * names — only when the caller can see that pipeline. */
    private[datris] def ucOwner(h: UcHit, visible: List[PipelineConfig]): Option[String] = {
        def norm(s: String): String = Option(s).map(DatabricksConnectionUtil.effectiveName).getOrElse("")
        val key = (norm(h.catalog), norm(h.schema), norm(h.table))
        val byCoords = visible.find { p =>
            p != null && p.destination != null && p.destination.database != null && p.destination.database.useDatabricks && {
                val db = p.destination.database
                (norm(db.dbName), norm(db.schema), norm(db.table)) == key
            }
        }.map(_.name)
        byCoords.orElse(h.tags.get("datris_pipeline").filter(n => visible.exists(p => p != null && p.name == n)))
    }

    /** Owned hits first, then unowned; within each by descending column-match
      * count (stable). Owned tables whose pipeline is already a Datris hit are
      * dropped — the pipeline hit already covers them. */
    private def rankUnityCatalog(hits: List[UcHit], visible: List[PipelineConfig], datrisHits: Set[String]): List[(UcHit, Option[String])] = {
        val withOwner = hits.map(h => h -> ucOwner(h, visible)).filterNot { case (_, o) => o.exists(datrisHits.contains) }
        val (owned, unowned) = withOwner.partition(_._2.isDefined)
        owned.sortBy(-_._1.matchedColumns.size) ++ unowned.sortBy(-_._1.matchedColumns.size)
    }

    private def renderUcHit(h: UcHit, owner: Option[String]): JsonObject = {
        val qualified = h.catalog + "." + h.schema + "." + h.table
        val o = new JsonObject()
        o.addProperty("name", qualified)
        o.addProperty("source", "unity-catalog")
        o.addProperty("secret", h.secret)
        if (h.comment != null && h.comment.nonEmpty) o.addProperty("comment", h.comment)
        val matched = new JsonArray()
        h.matchedColumns.foreach(matched.add)
        o.add("matchedColumns", matched)
        val tags = new JsonObject()
        h.tags.toList.sortBy(_._1).foreach { case (k, v) => tags.addProperty(k, v) }
        o.add("tags", tags)
        owner.foreach(o.addProperty("ownedByPipeline", _))
        val loc = LineageService.DatasetRef("databricks", List("database" -> h.catalog, "schema" -> h.schema, "table" -> h.table)).toJson
        o.add("location", loc)
        val htq = new JsonObject()
        val args = new JsonObject()
        owner match {
            case Some(p) =>
                htq.addProperty("tool", "query_databricks")
                args.addProperty("pipeline", p)
                htq.add("args", args)
            case None =>
                val sqlName =
                    DatabricksConnectionUtil.ident(h.catalog) + "." + DatabricksConnectionUtil.ident(h.schema) + "." + DatabricksConnectionUtil.ident(h.table)
                htq.addProperty("tool", "sql")
                args.addProperty("sql", "SELECT * FROM " + sqlName + " LIMIT 100")
                htq.add("args", args)
                htq.addProperty("note", "no Datris pipeline owns this table; run it in your warehouse")
        }
        o.add("howToQuery", htq)
        o
    }

    private def renderHit(c: Candidate, taps: List[TapConfig], withSource: Boolean = false): JsonObject = {
        val p = c.pipeline
        val o = new JsonObject()
        o.addProperty("name", p.name)
        if (withSource) o.addProperty("source", "datris")
        c.tap.flatMap(t => Option(t.description)).foreach(o.addProperty("description", _))
        val tags = new JsonArray()
        mergedTags(p, c.tap).foreach(tags.add)
        o.add("tags", tags)
        if (p.catalog != null) o.addProperty("catalog", p.catalog)
        o.addProperty("score", math.round(c.score * 100.0) / 100.0)

        val freshness = LineageService.freshness(p.name, taps)
        o.add("freshness", freshness)

        // Locations: the authoritative dataset first (L5b), each with its
        // authority label and the evidence of what the pipeline wrote there (L5a).
        val dsets = LineageService.datasets(p).sortBy(ds =>
            LineageService.authorityOfDataset(ds.id) match {
                case Some(LineageService.AuthorityAuthoritative) => 0
                case Some(LineageService.AuthorityUndeclared) => 1
                case _ => 2
            }
        )
        def locationJson(ds: LineageService.DatasetRef): JsonObject = {
            val l = ds.toJson
            LineageService.authorityOfDataset(ds.id).foreach(l.addProperty("authority", _))
            LineageService.evidenceFor("pipeline:" + p.name, ds.id).foreach { ev =>
                val e = new JsonObject()
                e.addProperty("runs", ev.runs)
                e.addProperty("records", ev.records)
                if (ev.lastRunAt != null) e.addProperty("lastRunAt", ev.lastRunAt)
                l.add("evidence", e)
            }
            l
        }
        dsets.headOption.foreach { primary =>
            o.add("location", locationJson(primary))
            val htq = howToQuery(p.name, primary)
            if (htq != null) o.add("howToQuery", htq)
        }
        if (dsets.size > 1) {
            val extra = new JsonArray()
            dsets.tail.foreach { ds =>
                val e = new JsonObject()
                e.add("location", locationJson(ds))
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
