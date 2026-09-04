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
            runId = "r1",
            pipeline = "p",
            configVersion = 3,
            input = RunLineageInput("tap", tapName = "t", tapRunTime = "2026-09-03T00:00:00Z", source = "feeds.example.com"),
            outputs = List(RunLineageOutput("postgres", "datris.public.t", "dataset:postgres:datris.public.t", "SUCCESS", 10)).asJava,
            recordCount = 10,
            status = "SUCCESS",
            completedAt = "2026-09-03T00:01:00Z"
        )
        val j = LineageService.runToJson(rl)
        assert(j.get("runId").getAsString == "r1")
        assert(j.get("configVersion").getAsInt == 3)
        assert(j.getAsJsonObject("input").get("tapName").getAsString == "t")
        val out = j.getAsJsonArray("outputs").get(0).getAsJsonObject
        assert(out.get("datasetId").getAsString == "dataset:postgres:datris.public.t")
        assert(out.get("status").getAsString == "SUCCESS")
    }

    test("authority: a single destination is authoritative by default; false makes it derived") {
        val single = pipeline("orders", "commerce", pgDest)
        val g = LineageService.build(Nil, List(single))
        assert(g.nodes.find(_.id == "dataset:postgres:datris.public.orders").get.authority.contains("authoritative"))
        val derived = LineageService.build(Nil, List(single.copy(authoritative = java.lang.Boolean.FALSE)))
        assert(derived.nodes.find(_.id == "dataset:postgres:datris.public.orders").get.authority.contains("derived"))
    }

    test("authority: several destinations are undeclared until Destination.authoritative names one") {
        val multi = Destination(database = Database(dbName = "datris", schema = "public", table = "t", usePostgres = true, useMongoDB = true))
        val g = LineageService.build(Nil, List(pipeline("p", dest = multi)))
        assert(g.nodes.filter(_.nodeType == "dataset").forall(_.authority.contains("undeclared")))
        val declared = LineageService.build(Nil, List(pipeline("p", dest = multi.copy(authoritative = "postgres"))))
        assert(declared.nodes.find(_.id == "dataset:postgres:datris.public.t").get.authority.contains("authoritative"))
        assert(declared.nodes.find(_.id == "dataset:mongodb:datris.t").get.authority.contains("derived"))
    }

    test("authority: a dataset landed by two pipelines is undeclared unless exactly one claims it") {
        val a = pipeline("a", dest = pgDest); val b = pipeline("b", dest = pgDest)
        val both = LineageService.build(Nil, List(a, b))
        assert(both.nodes.find(_.id == "dataset:postgres:datris.public.orders").get.authority.contains("undeclared"))
        val oneClaims = LineageService.build(Nil, List(a, b.copy(authoritative = java.lang.Boolean.FALSE)))
        assert(oneClaims.nodes.find(_.id == "dataset:postgres:datris.public.orders").get.authority.contains("authoritative"))
        assert(LineageService.authorityConflict(a, List(b)).exists(_.contains("already the authoritative writer")))
        assert(LineageService.authorityConflict(a, List(b.copy(authoritative = java.lang.Boolean.FALSE))).isEmpty)
        assert(LineageService.authorityConflict(pipeline("p", dest = pgDest.copy(authoritative = "snowflake")), Nil).exists(_.contains("no such destination")))
    }

    test("historical datasets are derived and edges carry evidence when present") {
        val ev =
            Map(("pipeline:orders", "dataset:postgres:datris.public.orders") -> LineageService.EdgeEvidence(8, 12400L, "2026-08-31T00:00:00Z", "SUCCESS", 1))
        val g = LineageService.build(
            Nil,
            List(pipeline("orders", "commerce", pgDest)),
            List(LineageService.ObservedDataset("orders", "dataset:postgres:datris.public.old")),
            ev
        )
        assert(g.nodes.find(_.id == "dataset:postgres:datris.public.old").get.authority.contains("derived"))
        val e = g.edges.find(x => x.from == "pipeline:orders" && x.to == "dataset:postgres:datris.public.orders").get
        assert(e.evidence.exists(_.records == 12400L))
        val j = e.toJson.getAsJsonObject("evidence")
        assert(j.get("runs").getAsInt == 8 && j.get("failedRuns").getAsInt == 1 && j.get("windowDays").getAsInt == 90)
        assert(!g.edges.find(_.to == "catalog:commerce").get.toJson.has("evidence"))
    }

    test("edgeEvidence aggregates runs per hop and tap logs per source, skipping test runs") {
        val runs = List(
            RunLineage(
                runId = "r1",
                pipeline = "orders",
                recordCount = 100,
                status = "SUCCESS",
                completedAt = "2026-09-01T00:00:00Z",
                input = RunLineageInput("tap", tapName = "t"),
                outputs = List(RunLineageOutput("postgres", datasetId = "dataset:postgres:x", status = "SUCCESS", recordCount = 100)).asJava
            ),
            RunLineage(
                runId = "r2",
                pipeline = "orders",
                recordCount = 50,
                status = "ERROR",
                completedAt = "2026-09-02T00:00:00Z",
                input = RunLineageInput("tap", tapName = "t"),
                outputs = List(RunLineageOutput("postgres", datasetId = "dataset:postgres:x", status = "ERROR", recordCount = 0)).asJava
            )
        )
        val t = TapConfig(name = "t", description = "d", targetPipeline = "orders")
        val logs = List(
            TapRunLog("t", "2026-09-01T00:00:00Z", "success", 100, mode = "run"),
            TapRunLog("t", "2026-09-02T00:00:00Z", "success", 7, mode = "test")
        )
        val ev = LineageService.edgeEvidence(runs, logs, List(t))
        val pd = ev(("pipeline:orders", "dataset:postgres:x"))
        assert(pd.runs == 2 && pd.records == 100L && pd.failedRuns == 1 && pd.lastStatus == "ERROR" && pd.lastRunAt == "2026-09-02T00:00:00Z")
        val tp = ev(("tap:t", "pipeline:orders"))
        assert(tp.runs == 2 && tp.records == 100L)
        val st = ev(("source:tap:t", "tap:t"))
        assert(st.runs == 1 && st.records == 100L)
    }

    test("pipelines without a catalog produce no catalog node") {
        val g = LineageService.build(Nil, List(pipeline("p", catalog = null, dest = pgDest)))
        assert(!g.nodes.exists(_.nodeType == "catalog"))
    }
}
