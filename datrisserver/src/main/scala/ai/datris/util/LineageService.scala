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

    /** How far back run-lineage docs are scanned for datasets a pipeline
      * landed into under an earlier destination config ("historical") and for
      * edge evidence counts. */
    private val EvidenceWindowDays = 90
    private val ObservedWindowMs = EvidenceWindowDays.toLong * 86400000L
    private val ObservedMaxRows = 5000
    private val MaxRecentRuns = 50

    /** What actually traversed an edge inside the evidence window (plan L5a):
      * aggregated from recorded runs (pipeline edges) or the tap run log
      * (source → tap). An edge without evidence is a configuration claim. */
    case class EdgeEvidence(runs: Int, records: Long, lastRunAt: String, lastStatus: String, failedRuns: Int) {
        def toJson: JsonObject = {
            val o = new JsonObject()
            o.addProperty("runs", runs)
            o.addProperty("records", records)
            if (lastRunAt != null) o.addProperty("lastRunAt", lastRunAt)
            if (lastStatus != null) o.addProperty("lastStatus", lastStatus)
            o.addProperty("failedRuns", failedRuns)
            o.addProperty("windowDays", EvidenceWindowDays)
            o
        }
    }

    /** Authority of a dataset node (plan L5b): `authoritative` (the system of
      * record), `derived` (a copy — rollup, replica, index, or historical), or
      * `undeclared` (more than one candidate and no declaration). Declared,
      * never inferred. */
    val AuthorityAuthoritative = "authoritative"
    val AuthorityDerived = "derived"
    val AuthorityUndeclared = "undeclared"

    /** `historical` marks a dataset (or the edge into it) that no current
      * config lands into but a recorded run did — the destination changed
      * since. `tags` come straight from the tap/pipeline definition. */
    case class Node(
        id: String,
        nodeType: String,
        name: String,
        catalog: Option[String] = None,
        tags: List[String] = Nil,
        historical: Boolean = false,
        authority: Option[String] = None
    ) {
        def toJson: JsonObject = {
            val o = new JsonObject()
            o.addProperty("id", id)
            o.addProperty("type", nodeType)
            o.addProperty("name", name)
            catalog.foreach(o.addProperty("catalog", _))
            if (tags.nonEmpty) { val t = new JsonArray(); tags.foreach(t.add); o.add("tags", t) }
            if (historical) o.addProperty("historical", true)
            authority.foreach(o.addProperty("authority", _))
            o
        }
    }

    case class Edge(from: String, to: String, historical: Boolean = false, evidence: Option[EdgeEvidence] = None) {
        def toJson: JsonObject = {
            val o = new JsonObject()
            o.addProperty("from", from)
            o.addProperty("to", to)
            if (historical) o.addProperty("historical", true)
            evidence.foreach(e => o.add("evidence", e.toJson))
            o
        }
    }

    /** A dataset a recorded run wrote: (pipeline name, dataset node id). */
    case class ObservedDataset(pipeline: String, datasetId: String)

    private def tagsOf(l: java.util.List[String]): List[String] =
        if (l == null) Nil else l.asScala.toList.filter(t => t != null && t.trim.nonEmpty).map(_.trim)

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
    private[datris] def build(taps: List[TapConfig], pipelines: List[PipelineConfig]): Graph = build(taps, pipelines, Nil, Map.empty)

    private[datris] def build(taps: List[TapConfig], pipelines: List[PipelineConfig], observed: List[ObservedDataset]): Graph =
        build(taps, pipelines, observed, Map.empty)

    /** Authority of a dataset from every pipeline's own claim on it (L5b
      * rules 1–4): exactly one authoritative claim ⇒ authoritative; any
      * undeclared claim or two authoritative claims ⇒ undeclared; otherwise
      * (every writer says derived) ⇒ derived. */
    private[datris] def resolveAuthority(claims: List[String]): String = {
        val auth = claims.count(_ == AuthorityAuthoritative)
        if (claims.contains(AuthorityUndeclared) || auth > 1) AuthorityUndeclared
        else if (auth == 1) AuthorityAuthoritative
        else AuthorityDerived
    }

    /** What this pipeline alone says about one of its datasets. */
    private[datris] def ownClaim(p: PipelineConfig, ds: DatasetRef): String = {
        if (p.authoritative != null && !p.authoritative.booleanValue()) return AuthorityDerived
        val all = datasets(p)
        if (all.size <= 1) return AuthorityAuthoritative
        val declared = Option(p.destination).flatMap(d => Option(d.authoritative)).map(_.trim.toLowerCase).filter(_.nonEmpty)
        declared match {
            case Some(kind) => if (kind == ds.kind) AuthorityAuthoritative else AuthorityDerived
            case None => AuthorityUndeclared
        }
    }

    /** Save-time check for a pipeline definition: a declared authoritative kind
      * must be one of its destinations, and no other pipeline may already be
      * the authoritative writer of the same dataset. Returns the problem. */
    def authorityConflict(p: PipelineConfig, others: List[PipelineConfig]): Option[String] = {
        val own = datasets(p)
        val declared = Option(p.destination).flatMap(d => Option(d.authoritative)).map(_.trim.toLowerCase).filter(_.nonEmpty)
        declared.filterNot(k => own.exists(_.kind == k)).foreach { k =>
            return Some("destination.authoritative names '" + k + "' but the pipeline has no such destination (" + own.map(_.kind).mkString(", ") + ")")
        }
        own.filter(ds => ownClaim(p, ds) == AuthorityAuthoritative).foreach { ds =>
            others.filter(o => o != null && o.name != p.name).foreach { o =>
                datasets(o).find(_.id == ds.id).filter(od => ownClaim(o, od) == AuthorityAuthoritative).foreach { _ =>
                    return Some("pipeline '" + o.name + "' is already the authoritative writer of " + ds.name +
                        "; set authoritative=false on one of them (or destination.authoritative on the other)")
                }
            }
        }
        None
    }

    /** Graph from configs plus datasets observed in recorded runs. An observed
      * dataset the current config still lands into is a no-op; one it no
      * longer lands into becomes a `historical` node with a `historical` edge
      * from its pipeline (and into the pipeline's catalog, so it stays
      * browsable by catalog). Observed datasets of deleted pipelines are
      * dropped — there is no node to hang them from. */
    private[datris] def build(
        taps: List[TapConfig],
        pipelines: List[PipelineConfig],
        observed: List[ObservedDataset],
        evidence: Map[(String, String), EdgeEvidence]
    ): Graph = {
        val nodes = scala.collection.mutable.LinkedHashMap[String, Node]()
        val edges = List.newBuilder[Edge]

        def addNode(n: Node): Unit = if (!nodes.contains(n.id)) nodes.put(n.id, n)
        def edge(from: String, to: String, historical: Boolean = false): Edge = Edge(from, to, historical, evidence.get((from, to)))

        taps.filter(_ != null).foreach { t =>
            val tapId = "tap:" + t.name
            addNode(Node(tapId, "tap", t.name, Option(t.catalog), tagsOf(t.tags)))
            val source = TapRunner.declaredSource(t)
            addNode(Node("source:" + source, "source", source))
            edges += edge("source:" + source, tapId)
            if (t.targetPipeline != null && t.targetPipeline.nonEmpty)
                edges += edge(tapId, "pipeline:" + t.targetPipeline)
        }

        // Every pipeline's own claim on each dataset it lands into (rule 4).
        val live = pipelines.filter(_ != null)
        val authority: Map[String, String] =
            live.flatMap(p => datasets(p).map(ds => ds.id -> ownClaim(p, ds))).groupBy(_._1).mapValues(cs => resolveAuthority(cs.map(_._2)))

        live.foreach { p =>
            val pipelineId = "pipeline:" + p.name
            addNode(Node(pipelineId, "pipeline", p.name, Option(p.catalog), tagsOf(p.tags)))
            datasets(p).foreach { ds =>
                addNode(Node(ds.id, "dataset", ds.name, Option(p.catalog), authority = authority.get(ds.id)))
                edges += edge(pipelineId, ds.id)
                if (p.catalog != null && p.catalog.nonEmpty) {
                    addNode(Node("catalog:" + p.catalog, "catalog", p.catalog))
                    edges += edge(ds.id, "catalog:" + p.catalog)
                }
            }
        }

        val byName = pipelines.filter(_ != null).map(p => p.name -> p).toMap
        observed.filter(o => o != null && o.datasetId != null && o.datasetId.startsWith("dataset:")).distinct.foreach { o =>
            byName.get(o.pipeline).foreach { p =>
                val pipelineId = "pipeline:" + p.name
                if (!nodes.contains(o.datasetId))
                    addNode(Node(
                        o.datasetId,
                        "dataset",
                        o.datasetId.stripPrefix("dataset:"),
                        Option(p.catalog),
                        historical = true,
                        authority = Some(AuthorityDerived)
                    ))
                if (nodes(o.datasetId).historical) {
                    edges += edge(pipelineId, o.datasetId, historical = true)
                    if (p.catalog != null && p.catalog.nonEmpty) {
                        addNode(Node("catalog:" + p.catalog, "catalog", p.catalog))
                        edges += edge(o.datasetId, "catalog:" + p.catalog, historical = true)
                    }
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
        val runs =
            try RunLineageIO.since(now - ObservedWindowMs, ObservedMaxRows)
            catch { case _: Exception => Nil }
        val observed = runs.flatMap { r =>
            Option(r.outputs).map(_.asScala.toList).getOrElse(Nil)
                .filter(o => o.datasetId != null)
                .map(o => ObservedDataset(r.pipeline, o.datasetId))
        }.distinct
        val tapLogs =
            try NoSQLDbUtil.getItemsSinceAsJSON(env.tapLogTableName, "created_at", now - ObservedWindowMs, ObservedMaxRows).flatMap { json =>
                    try {
                        val el = com.google.gson.JsonParser.parseString(json)
                        if (el.isJsonObject && el.getAsJsonObject.has("value")) Option(gson.fromJson(el.getAsJsonObject.get("value"), classOf[TapRunLog]))
                        else None
                    } catch { case _: Exception => None }
                }
            catch { case _: Exception => Nil }
        val entry = CacheEntry(now, build(taps, pipelines, observed, edgeEvidence(runs, tapLogs, taps)), taps, pipelines)
        cache.put(env.environment, entry)
        entry
    }

    /** Aggregate recorded runs and tap-run logs into per-edge evidence. Pure.
      *  - `pipeline → dataset`: one count per run output (SUCCESS records summed)
      *  - `tap → pipeline`: runs whose input names the tap
      *  - `source → tap`: non-test tap runs, resolved through the tap's source */
    private[datris] def edgeEvidence(runs: List[RunLineage], tapLogs: List[TapRunLog], taps: List[TapConfig]): Map[(String, String), EdgeEvidence] = {
        case class Acc(var runs: Int = 0, var records: Long = 0L, var lastAt: String = null, var lastStatus: String = null, var failed: Int = 0) {
            def add(at: String, status: String, count: Long): Unit = {
                runs += 1
                val ok = status != null && (status.equalsIgnoreCase("SUCCESS") || status.equalsIgnoreCase("WARNING"))
                if (ok) records += count else failed += 1
                if (at != null && (lastAt == null || at.compareTo(lastAt) > 0)) { lastAt = at; lastStatus = status }
            }
            def result: EdgeEvidence = EdgeEvidence(runs, records, lastAt, lastStatus, failed)
        }
        val acc = scala.collection.mutable.LinkedHashMap[(String, String), Acc]()
        def at(k: (String, String)): Acc = acc.getOrElseUpdate(k, Acc())

        runs.filter(r => r != null && r.pipeline != null).foreach { r =>
            val pipelineId = "pipeline:" + r.pipeline
            Option(r.outputs).map(_.asScala.toList).getOrElse(Nil).filter(_.datasetId != null).foreach { o =>
                at((pipelineId, o.datasetId)).add(r.completedAt, o.status, o.recordCount.toLong)
            }
            if (r.input != null && r.input.tapName != null)
                at(("tap:" + r.input.tapName, pipelineId)).add(r.completedAt, r.status, r.recordCount.toLong)
        }
        val sourceOf = taps.filter(_ != null).map(t => t.name -> ("source:" + TapRunner.declaredSource(t))).toMap
        tapLogs.filter(l => l != null && l.tapName != null && l.mode != "test").foreach { l =>
            sourceOf.get(l.tapName).foreach { src =>
                at((src, "tap:" + l.tapName)).add(l.runTime, l.status, l.recordCount.toLong)
            }
        }
        acc.map { case (k, a) => k -> a.result }.toMap
    }

    /** Evidence for one edge of the cached graph, if any. */
    def evidenceFor(from: String, to: String): Option[EdgeEvidence] =
        loadCached().graph.edges.find(e => e.from == from && e.to == to).flatMap(_.evidence)

    /** Authority label of one dataset node of the cached graph, if known. */
    def authorityOfDataset(datasetId: String): Option[String] =
        loadCached().graph.nodes.find(_.id == datasetId).flatMap(_.authority)

    /** Drop the cached graph so the next read rebuilds (tests / after writes). */
    private[datris] def invalidate(): Unit = cache.clear()

    /** Compact JSON for one recorded run, for neighborhood `runs` lists. */
    private[datris] def runToJson(r: RunLineage): JsonObject = {
        val o = new JsonObject()
        o.addProperty("runId", r.runId)
        if (r.pipeline != null) o.addProperty("pipeline", r.pipeline)
        o.addProperty("configVersion", r.configVersion)
        if (r.status != null) o.addProperty("status", r.status)
        if (r.startedAt != null) o.addProperty("startedAt", r.startedAt)
        if (r.completedAt != null) o.addProperty("completedAt", r.completedAt)
        o.addProperty("durationMs", r.durationMs)
        o.addProperty("recordCount", r.recordCount)
        if (r.input != null) {
            val in = new JsonObject()
            in.addProperty("kind", r.input.kind)
            if (r.input.tapName != null) in.addProperty("tapName", r.input.tapName)
            if (r.input.tapRunTime != null) in.addProperty("tapRunTime", r.input.tapRunTime)
            if (r.input.scriptSha != null) in.addProperty("scriptSha", r.input.scriptSha)
            if (r.input.source != null) in.addProperty("source", r.input.source)
            if (r.input.filename != null) in.addProperty("filename", r.input.filename)
            o.add("input", in)
        }
        val outs = new JsonArray()
        Option(r.outputs).map(_.asScala.toList).getOrElse(Nil).foreach { x =>
            val oo = new JsonObject()
            oo.addProperty("kind", x.kind)
            if (x.coords != null) oo.addProperty("coords", x.coords)
            if (x.datasetId != null) oo.addProperty("datasetId", x.datasetId)
            oo.addProperty("status", x.status)
            oo.addProperty("recordCount", x.recordCount)
            if (x.error != null) oo.addProperty("error", x.error)
            outs.add(oo)
        }
        o.add("outputs", outs)
        o
    }

    /** Recent recorded runs touching one node, newest first. Catalog nodes
      * have no runs of their own. */
    def recentRuns(nodeType: String, name: String, max: Int): List[RunLineage] = {
        val n = math.max(0, math.min(max, MaxRecentRuns))
        if (n == 0) return Nil
        nodeType match {
            case "pipeline" => RunLineageIO.recentBy("pipeline", name, n)
            case "tap" => RunLineageIO.recentBy("tap", name, n)
            case "source" => RunLineageIO.recentBy("source", name, n)
            case "dataset" => RunLineageIO.recentBy("datasets", "dataset:" + name, n)
            case _ => Nil
        }
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
    def neighborhood(nodeType: String, name: String): JsonObject = neighborhood(nodeType, name, "both", 0, 0, columns = false)

    def neighborhood(nodeType: String, name: String, direction: String, depth: Int, runs: Int): JsonObject =
        neighborhood(nodeType, name, direction, depth, runs, columns = false)

    /** Neighborhood with traversal controls. `direction` is up, down or both;
      * `depth` bounds the hop count (0 = unbounded); `runs` > 0 appends that
      * many recent recorded runs for the node (capped at 50). */
    /** `columns` true ⇒ embed column lineage (plan L3): for a pipeline node its
      * current definition; for a dataset node its feeding pipeline's current
      * definition, or — when the dataset is historical — the definition version
      * of the last recorded run that wrote it. Deterministic tier only; the
      * inferred tier is fetched on demand via /lineage/columns. */
    def neighborhood(nodeType: String, name: String, direction: String, depth: Int, runs: Int, columns: Boolean): JsonObject = {
        val entry = loadCached()
        val g = entry.graph
        val id = nodeType + ":" + name
        val node = g.nodes.find(_.id == id).orNull
        if (node == null) return null

        val forward = g.edges.groupBy(_.from).mapValues(_.map(_.to))
        val backward = g.edges.groupBy(_.to).mapValues(_.map(_.from))
        val dir = Option(direction).map(_.trim.toLowerCase).filter(d => d == "up" || d == "down").getOrElse("both")
        val maxHops = if (depth <= 0) Int.MaxValue else depth

        def walk(start: String, next: String => List[String]): List[String] = {
            val seen = scala.collection.mutable.LinkedHashSet[String]()
            var frontier = next(start)
            var hops = 1
            while (frontier.nonEmpty && hops <= maxHops) {
                val fresh = frontier.filterNot(seen.contains)
                fresh.foreach(seen.add)
                frontier = fresh.flatMap(next)
                hops += 1
            }
            seen.toList
        }

        val upstream = if (dir == "down") Nil else walk(id, i => backward.getOrElse(i, Nil))
        val downstream = if (dir == "up") Nil else walk(id, i => forward.getOrElse(i, Nil))
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

        if (runs > 0) {
            val arr = new JsonArray()
            recentRuns(nodeType, name, runs).foreach(r => arr.add(runToJson(r)))
            o.add("runs", arr)
        }

        if (columns && (nodeType == "pipeline" || nodeType == "dataset")) {
            try {
                val target: Option[(String, Option[Int])] =
                    if (nodeType == "pipeline") Some((name, None))
                    else {
                        // The pipeline that lands into this dataset (first upstream pipeline).
                        upstream.find(_.startsWith("pipeline:")).map(_.stripPrefix("pipeline:")).map { p =>
                            val version =
                                if (node.historical) recentRuns("dataset", name, 1).headOption.map(_.configVersion).filter(_ > 0)
                                else None
                            (p, version)
                        }
                    }
                target.flatMap { case (p, v) => Option(ColumnLineageService.forPipeline(p, v, runInference = false)) }
                    .foreach(r => o.add("columns", r.toJson))
            } catch {
                case e: Exception => logger.debug("column lineage embed skipped for " + id + ": " + e.getMessage)
            }
        }
        o
    }
}
