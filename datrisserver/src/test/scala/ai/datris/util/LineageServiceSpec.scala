package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

class LineageServiceSpec extends AnyFunSuite {

    private def pipeline(name: String, catalog: String = null, dest: Destination): PipelineConfig =
        PipelineConfig(name = name, catalog = catalog, destination = dest)

    private def tap(name: String, target: String, catalog: String = null): TapConfig =
        TapConfig(name = name, description = "d", targetPipeline = target, catalog = catalog)

    private val pgDest = Destination(
        schemaProperties = null,
        database = Database(dbName = "datris", schema = "public", table = "orders", usePostgres = true)
    )

    test("graph chains source → tap → pipeline → dataset → catalog") {
        val g = LineageService.build(List(tap("orders-export", "orders", "commerce")), List(pipeline("orders", "commerce", pgDest)))
        val ids = g.nodes.map(_.id)
        assert(ids.contains("source:tap:orders-export"))
        assert(ids.contains("tap:orders-export"))
        assert(ids.contains("pipeline:orders"))
        assert(ids.contains("dataset:postgres:datris.public.orders"))
        assert(ids.contains("catalog:commerce"))
        val edges = g.edges.map(e => e.from -> e.to)
        assert(edges.contains("source:tap:orders-export" -> "tap:orders-export"))
        assert(edges.contains("tap:orders-export" -> "pipeline:orders"))
        assert(edges.contains("pipeline:orders" -> "dataset:postgres:datris.public.orders"))
        assert(edges.contains("dataset:postgres:datris.public.orders" -> "catalog:commerce"))
    }

    test("a tap pointing at a deleted pipeline leaves no dangling edge") {
        val g = LineageService.build(List(tap("orphan", "gone")), Nil)
        assert(g.nodes.map(_.id).contains("tap:orphan"))
        assert(!g.edges.exists(_.to == "pipeline:gone"))
    }

    test("datasets derive one ref per destination, with coordinates") {
        val multi = Destination(
            database = Database(dbName = "datris", schema = "public", table = "t", usePostgres = true, useMongoDB = true),
            qdrant = QdrantConfig("chunks", null, null, "emb", "qd"),
            kafka = Kafka("events", null, null)
        )
        val refs = LineageService.datasets(pipeline("p", dest = multi))
        val kinds = refs.map(_.kind)
        assert(kinds == List("postgres", "mongodb", "kafka", "qdrant"))
        val pg = refs.find(_.kind == "postgres").get
        assert(pg.name == "postgres:datris.public.t")
        val mongo = refs.find(_.kind == "mongodb").get
        assert(mongo.coords.toMap.get("collection").contains("t"))
    }

    test("an HTTP tap's source node is the endpoint host") {
        val httpTap = TapConfig(
            name = "prices",
            description = "d",
            targetPipeline = "p",
            scriptKind = "http",
            endpointUrl = "https://feeds.example.com/v1/prices"
        )
        val g = LineageService.build(List(httpTap), List(pipeline("p", dest = pgDest)))
        assert(g.nodes.map(_.id).contains("source:feeds.example.com"))
    }

    test("an observed dataset the config no longer lands into becomes a historical node + edge") {
        val observed = List(
            LineageService.ObservedDataset("orders", "dataset:postgres:datris.public.orders_v1"),
            LineageService.ObservedDataset("orders", "dataset:postgres:datris.public.orders") // still current → no-op
        )
        val g = LineageService.build(Nil, List(pipeline("orders", "commerce", pgDest)), observed)
        val hist = g.nodes.find(_.id == "dataset:postgres:datris.public.orders_v1").get
        assert(hist.historical)
        assert(hist.nodeType == "dataset")
        assert(hist.catalog.contains("commerce"))
        val current = g.nodes.find(_.id == "dataset:postgres:datris.public.orders").get
        assert(!current.historical)
        assert(g.edges.exists(e => e.from == "pipeline:orders" && e.to == hist.id && e.historical))
        assert(g.edges.exists(e => e.from == hist.id && e.to == "catalog:commerce" && e.historical))
        assert(g.edges.exists(e => e.from == "pipeline:orders" && e.to == current.id && !e.historical))
    }

    test("observed datasets of a deleted pipeline are dropped, not dangling") {
        val g = LineageService.build(Nil, Nil, List(LineageService.ObservedDataset("gone", "dataset:postgres:x.y.z")))
        assert(g.nodes.isEmpty && g.edges.isEmpty)
    }

    test("tap and pipeline tags ride on their nodes") {
        val t = TapConfig(name = "t", description = "d", targetPipeline = "p", tags = List("sales", " ", null).asJava)
        val p = PipelineConfig(name = "p", destination = pgDest, tags = List("gold").asJava)
        val g = LineageService.build(List(t), List(p))
        assert(g.nodes.find(_.id == "tap:t").get.tags == List("sales"))
        assert(g.nodes.find(_.id == "pipeline:p").get.tags == List("gold"))
        val json = g.nodes.find(_.id == "pipeline:p").get.toJson
        assert(json.getAsJsonArray("tags").size() == 1)
        assert(!g.nodes.find(_.id == "source:tap:t").get.toJson.has("tags"))
    }

    test("runToJson carries per-destination outputs and the input identity") {
        val rl = RunLineage(
            runId = "r1", pipeline = "p", configVersion = 3,
            input = RunLineageInput("tap", tapName = "t", tapRunTime = "2026-09-03T00:00:00Z", source = "feeds.example.com"),
            outputs = List(RunLineageOutput("postgres", "datris.public.t", "dataset:postgres:datris.public.t", "SUCCESS", 10)).asJava,
            recordCount = 10, status = "SUCCESS", completedAt = "2026-09-03T00:01:00Z"
        )
        val j = LineageService.runToJson(rl)
        assert(j.get("runId").getAsString == "r1")
        assert(j.get("configVersion").getAsInt == 3)
        assert(j.getAsJsonObject("input").get("tapName").getAsString == "t")
        val out = j.getAsJsonArray("outputs").get(0).getAsJsonObject
        assert(out.get("datasetId").getAsString == "dataset:postgres:datris.public.t")
        assert(out.get("status").getAsString == "SUCCESS")
    }

    test("pipelines without a catalog produce no catalog node") {
        val g = LineageService.build(Nil, List(pipeline("p", catalog = null, dest = pgDest)))
        assert(!g.nodes.exists(_.nodeType == "catalog"))
    }
}
