package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.{Gson, JsonArray, JsonObject}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

/** Deterministic lineage: the graph Source → Tap → Pipeline → Dataset →
  * Catalog built purely from stored configuration — no AI, no inference, no
  * new storage. Freshness per pipeline comes from the status summaries, the
  * tap staleness classification is shared with [[ActivitySignals]] so the
  * catalog and the activity dashboard never disagree, and the incremental
  * cursor comes from [[TapStateIO]].
  *
  * Node ids are `type:name` (`dataset` names are destination coordinates,
  * e.g. `postgres:datris.public.orders`). Column-level lineage and
  * OpenLineage export are additive on top of this graph, not part of it.
  */
object LineageService {

    private val logger = LoggerFactory.getLogger(getClass)
    private val gson = new Gson()

    private val FreshnessWindowMs = 30L * 86400000L
    private val MaxRows = 5000
    private val cacheTtlMs = 60000L

    case class Node(id: String, nodeType: String, name: String, catalog: Option[String] = None) {
        def toJson: JsonObject = {
            val o = new JsonObject()
            o.addProperty("id", id)
            o.addProperty("type", nodeType)
            o.addProperty("name", name)
            catalog.foreach(o.addProperty("catalog", _))
            o
        }
    }

    case class Edge(from: String, to: String) {
        def toJson: JsonObject = {
            val o = new JsonObject()
            o.addProperty("from", from)
            o.addProperty("to", to)
            o
        }
    }

    case class Graph(nodes: List[Node], edges: List[Edge]) {
        def toJson: JsonObject = {
            val o = new JsonObject()
            val n = new JsonArray(); nodes.foreach(x => n.add(x.toJson)); o.add("nodes", n)
            val e = new JsonArray(); edges.foreach(x => e.add(x.toJson)); o.add("edges", e)
            o
        }
    }

    /** One landed dataset: destination kind + its coordinates. */
    case class DatasetRef(kind: String, coords: List[(String, String)]) {
        def name: String = kind + ":" + coords.map(_._2).filter(v => v != null && v.nonEmpty).mkString(".")
        def id: String = "dataset:" + name
        def toJson: JsonObject = {
            val o = new JsonObject()
            o.addProperty("kind", kind)
            coords.foreach { case (k, v) => if (v != null && v.nonEmpty) o.addProperty(k, v) }
            o
        }
    }

    /** The datasets a pipeline lands into, from its destination section alone. */
    def datasets(p: PipelineConfig): List[DatasetRef] = {
        if (p == null || p.destination == null) return Nil
        val d = p.destination
        val out = List.newBuilder[DatasetRef]
        if (d.database != null) {
            val db = d.database
            val coords = List("database" -> db.dbName, "schema" -> db.schema, "table" -> db.table)
            if (db.usePostgres) out += DatasetRef("postgres", coords)
            if (db.useMongoDB) out += DatasetRef("mongodb", List("database" -> db.dbName, "collection" -> db.table))
            if (db.useSnowflake) out += DatasetRef("snowflake", coords)
            if (db.useDatabricks) out += DatasetRef("databricks", coords)
        }
        if (d.objectStore != null) {
            val os = d.objectStore
            out += DatasetRef(
                "objectstore",
                List("bucket" -> Option(os.destinationBucketOverride).getOrElse(""), "prefix" -> Option(os.prefixKey).getOrElse(p.name))
            )
        }
        if (d.kafka != null) out += DatasetRef("kafka", List("topic" -> d.kafka.topic))
        if (d.activeMQ != null) out += DatasetRef("activemq", List("queue" -> d.activeMQ.queueName))
        if (d.qdrant != null) out += DatasetRef("qdrant", List("collection" -> d.qdrant.collectionName))
        if (d.weaviate != null) out += DatasetRef("weaviate", List("collection" -> d.weaviate.className))
        if (d.pgvector != null)
            out += DatasetRef("pgvector", List("schema" -> Option(d.pgvector.schemaName).getOrElse("public"), "table" -> d.pgvector.tableName))
        if (d.milvus != null) out += DatasetRef("milvus", List("collection" -> d.milvus.collectionName))
        if (d.chroma != null) out += DatasetRef("chroma", List("collection" -> d.chroma.collectionName))
        out.result()
    }

    /** Pure graph construction from configs — the unit-testable core. */
    private[datris] def build(taps: List[TapConfig], pipelines: List[PipelineConfig]): Graph = {
        val nodes = scala.collection.mutable.LinkedHashMap[String, Node]()
        val edges = List.newBuilder[Edge]

        def addNode(n: Node): Unit = if (!nodes.contains(n.id)) nodes.put(n.id, n)

        taps.filter(_ != null).foreach { t =>
            val tapId = "tap:" + t.name
            addNode(Node(tapId, "tap", t.name, Option(t.catalog)))
            val source = TapRunner.declaredSource(t)
            addNode(Node("source:" + source, "source", source))
            edges += Edge("source:" + source, tapId)
            if (t.targetPipeline != null && t.targetPipeline.nonEmpty)
                edges += Edge(tapId, "pipeline:" + t.targetPipeline)
        }

        pipelines.filter(_ != null).foreach { p =>
            val pipelineId = "pipeline:" + p.name
            addNode(Node(pipelineId, "pipeline", p.name, Option(p.catalog)))
            datasets(p).foreach { ds =>
                addNode(Node(ds.id, "dataset", ds.name, Option(p.catalog)))
                edges += Edge(pipelineId, ds.id)
                if (p.catalog != null && p.catalog.nonEmpty) {
                    addNode(Node("catalog:" + p.catalog, "catalog", p.catalog))
                    edges += Edge(ds.id, "catalog:" + p.catalog)
                }
            }
        }

        // Drop edges whose target node doesn't exist (e.g. a tap pointing at a
        // deleted pipeline) — the UI should not render dangling references.
        val ids = nodes.keySet
        Graph(nodes.values.toList, edges.result().distinct.filter(e => ids.contains(e.from) && ids.contains(e.to)))
    }

    // ------------------------------------------------------------------

    private case class CacheEntry(atMs: Long, graph: Graph, taps: List[TapConfig], pipelines: List[PipelineConfig])
    private val cache = new java.util.concurrent.ConcurrentHashMap[String, CacheEntry]()

    private def loadCached(): CacheEntry = {
        val env = DatrisEnvironment.current
        val now = System.currentTimeMillis()
        val cached = cache.get(env.environment)
        if (cached != null && now - cached.atMs < cacheTtlMs) return cached
        val taps =
            try TapConfigIO.readAll(env.tapTableName)
            catch { case _: Exception => Nil }
        val pipelines =
            try PipelineConfigIO.readAll(env.pipelineTableName)
            catch { case _: Exception => Nil }
        val entry = CacheEntry(now, build(taps, pipelines), taps, pipelines)
        cache.put(env.environment, entry)
        entry
    }

    /** The whole graph (cached ~1 minute). */
    def graph(): Graph = loadCached().graph

    /** Per-pipeline freshness: last successful landing, its record count, the
      * incremental cursor when the feeding tap keeps one, and the stale/fresh
      * classification shared with ActivitySignals. */
    def freshness(pipelineName: String, taps: List[TapConfig]): JsonObject = {
        val env = DatrisEnvironment.current
        val now = System.currentTimeMillis()
        val o = new JsonObject()

        val summaries: List[PipelineStatusSummaryTable] =
            try NoSQLDbUtil.getItemsSinceAsJSON(env.pipelineStatusTableName + "-summary", "created_at", now - FreshnessWindowMs, MaxRows).flatMap {
                    json =>
                        try Some(gson.fromJson(json, classOf[PipelineStatusSummaryTable]))
                        catch { case _: Exception => None }
                }
            catch { case e: Exception => logger.debug("lineage: summary read failed: " + e.getMessage); Nil }

        val landed = summaries
            .filter(s =>
                s.json != null && pipelineName.equals(s.json.pipeline) &&
                    Option(s.json.status).exists(st => st.equalsIgnoreCase("success") || st.equalsIgnoreCase("warning"))
            )
            .sortBy(s => Option(s.created_at).map(_.longValue()).getOrElse(0L))

        val feedingTap = taps.find(t => t != null && pipelineName.equals(t.targetPipeline))
        val staleNames = ActivitySignals.computeStale(now, taps).map(_.name).toSet

        landed.lastOption match {
            case Some(last) =>
                o.addProperty("lastLandedAt", java.time.Instant.ofEpochMilli(last.created_at.longValue()).toString)
                o.addProperty("recordCount", last.json.recordCount)
                if (last.json.pipelineToken != null) o.addProperty("latestRunId", last.json.pipelineToken)
                val state = if (feedingTap.exists(t => staleNames.contains(t.name))) "stale" else "fresh"
                o.addProperty("state", state)
            case None =>
                o.addProperty("state", if (feedingTap.exists(t => staleNames.contains(t.name))) "stale" else "unknown")
        }

        feedingTap.foreach { t =>
            try {
                val st = TapStateIO.read(t.name)
                if (st != null && st.updatedAt != null) o.addProperty("cursorUpdatedAt", st.updatedAt)
            } catch { case _: Exception => () }
        }
        o
    }

    /** Neighborhood of one node: the node, everything transitively upstream,
      * and everything transitively downstream. 404-style null when unknown. */
    def neighborhood(nodeType: String, name: String): JsonObject = {
        val entry = loadCached()
        val g = entry.graph
        val id = nodeType + ":" + name
        val node = g.nodes.find(_.id == id).orNull
        if (node == null) return null

        val forward = g.edges.groupBy(_.from).mapValues(_.map(_.to))
        val backward = g.edges.groupBy(_.to).mapValues(_.map(_.from))

        def walk(start: String, next: String => List[String]): List[String] = {
            val seen = scala.collection.mutable.LinkedHashSet[String]()
            var frontier = next(start)
            while (frontier.nonEmpty) {
                val fresh = frontier.filterNot(seen.contains)
                fresh.foreach(seen.add)
                frontier = fresh.flatMap(next)
            }
            seen.toList
        }

        val upstream = walk(id, i => backward.getOrElse(i, Nil))
        val downstream = walk(id, i => forward.getOrElse(i, Nil))
        val byId = g.nodes.map(n => n.id -> n).toMap

        val o = new JsonObject()
        o.add("node", node.toJson)
        val up = new JsonArray(); upstream.flatMap(byId.get).foreach(n => up.add(n.toJson)); o.add("upstream", up)
        val down = new JsonArray(); downstream.flatMap(byId.get).foreach(n => down.add(n.toJson)); o.add("downstream", down)
        val relevant = (upstream ++ downstream :+ id).toSet
        val e = new JsonArray()
        g.edges.filter(x => relevant.contains(x.from) && relevant.contains(x.to)).foreach(x => e.add(x.toJson))
        o.add("edges", e)

        // Freshness for the pipeline this node is (or feeds).
        val pipelineName: Option[String] =
            if (nodeType == "pipeline") Some(name)
            else (upstream ++ downstream).find(_.startsWith("pipeline:")).map(_.stripPrefix("pipeline:"))
        pipelineName.foreach(p => o.add("freshness", freshness(p, entry.taps)))
        o
    }
}
