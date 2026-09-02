package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

class ProvenanceStamperSpec extends AnyFunSuite {

    private def fields(names: String*): java.util.List[SchemaField] =
        new java.util.ArrayList[SchemaField](names.map(n => SchemaField(n, "string")).asJava)

    private def config(stamp: Boolean, selected: java.util.List[String] = null): PipelineConfig =
        PipelineConfig(
            name = "orders",
            source = Source(
                schemaProperties = SchemaProperties("db", fields("id", "amount")),
                fileAttributes = FileAttributes(csvAttributes = CsvAttributes())
            ),
            destination = Destination(schemaProperties = SchemaProperties("db", fields("id", "amount"))),
            version = 7,
            provenance = if (stamp) ProvenanceConfig(stamp = true, fields = selected) else null
        )

    private def delimitedCtx(cfg: PipelineConfig, metadata: PipelineMetadata = null): JobContext =
        JobContext(
            "run-123",
            metadata,
            Data(10L, List("id", "amount"), List(SchemaField("id", "string"), SchemaField("amount", "string")), List("1,5", "2,9"), null),
            cfg,
            null,
            INITIALIZED,
            null,
            null
        )

    private val tapMetadata =
        PipelineMetadata(
            "orders",
            "f.csv",
            null,
            "pub-1",
            bulkUpload = false,
            tapName = "prices",
            tapRunTime = "2026-09-02 06:00:00 UTC",
            tapScriptSha = "a1b2c3d",
            tapSource = "example-host"
        )

    test("stamping is a no-op when provenance is absent or off") {
        val ctx = delimitedCtx(config(stamp = false))
        assert(ProvenanceStamper.stamp(ctx) eq ctx)
        val off = delimitedCtx(config(stamp = false).copy(provenance = ProvenanceConfig(stamp = false)))
        assert(ProvenanceStamper.stamp(off) eq off)
    }

    test("delimited stamping appends header, rows, and BOTH in-memory schemas in lockstep") {
        val stamped = ProvenanceStamper.stamp(delimitedCtx(config(stamp = true), tapMetadata))
        val names = ProvenanceStamper.AllFields
        assert(stamped.data.header == List("id", "amount") ++ names)
        assert(stamped.data.headerWithSchema.map(_.name) == List("id", "amount") ++ names)
        assert(stamped.config.source.schemaProperties.fields.asScala.map(_.name).toList == List("id", "amount") ++ names)
        assert(stamped.config.destination.schemaProperties.fields.asScala.map(_.name).toList == List("id", "amount") ++ names)
        // Every row gains exactly the appended columns, same order.
        val cols = stamped.data.rows.head.split(",", -1)
        assert(cols.length == 2 + names.size)
        assert(cols(2) == "run-123") // _datris_run_id
        assert(cols(4) == "7") // _datris_config_version
        assert(cols(5) == "prices|2026-09-02 06:00:00 UTC") // _datris_tap_run
        assert(cols(6) == "a1b2c3d") // _datris_script_sha
        assert(cols(7) == "example-host") // _datris_source
        // All rows carry the identical constant suffix.
        assert(stamped.data.rows.map(_.split(",", -1).drop(2).toList).distinct.size == 1)
    }

    test("stamping is idempotent — an already-stamped header is left alone") {
        val once = ProvenanceStamper.stamp(delimitedCtx(config(stamp = true), tapMetadata))
        val twice = ProvenanceStamper.stamp(once)
        assert(twice.data.header == once.data.header)
        assert(twice.data.rows == once.data.rows)
    }

    test("direct uploads stamp empty tap fields, not nulls that shift columns") {
        val stamped = ProvenanceStamper.stamp(delimitedCtx(config(stamp = true), metadata = null))
        val cols = stamped.data.rows.head.split(",", -1)
        assert(cols.length == 2 + ProvenanceStamper.AllFields.size)
        assert(cols(5) == "") // _datris_tap_run empty for non-tap jobs
        assert(cols(6) == "")
        assert(cols(7) == "")
    }

    test("provenance.fields selects a subset, order preserved") {
        val selected = new java.util.ArrayList[String](List(ProvenanceStamper.RunId, ProvenanceStamper.IngestedAt).asJava)
        val stamped = ProvenanceStamper.stamp(delimitedCtx(config(stamp = true, selected), tapMetadata))
        assert(stamped.data.header == List("id", "amount", ProvenanceStamper.RunId, ProvenanceStamper.IngestedAt))
    }

    test("csvEncode applies the ingest quoting rule") {
        assert(ProvenanceStamper.csvEncode("plain", ",") == "plain")
        assert(ProvenanceStamper.csvEncode("a,b", ",") == "\"a,b\"")
        assert(ProvenanceStamper.csvEncode("say \"hi\"", ",") == "\"say \"\"hi\"\"\"")
        assert(ProvenanceStamper.csvEncode("tap|run", "|") == "\"tap|run\"")
    }

    test("injectJson stamps arrays, single objects, and NDJSON; leaves primitives alone") {
        val values = List(ProvenanceStamper.RunId -> "run-123", ProvenanceStamper.ConfigVersion -> "7")
        val arr = ProvenanceStamper.injectJson("[{\"a\":1},{\"a\":2}]", values)
        assert(arr != null && arr.contains("run-123"))
        assert(com.google.gson.JsonParser.parseString(arr).getAsJsonArray.size() == 2)

        val obj = ProvenanceStamper.injectJson("{\"a\":1}", values)
        assert(obj != null && com.google.gson.JsonParser.parseString(obj).getAsJsonObject.get(ProvenanceStamper.RunId).getAsString == "run-123")

        val ndjson = ProvenanceStamper.injectJson("{\"a\":1}\n{\"a\":2}", values)
        assert(ndjson != null && ndjson.split("\n").forall(_.contains("run-123")))

        assert(ProvenanceStamper.injectJson("42", values) == null)
        assert(ProvenanceStamper.injectJson("not json at [all", values) == null)
    }

    test("vector destinations get provenance injected into their metadata maps") {
        val meta = new java.util.LinkedHashMap[String, String]()
        meta.put("team", "x")
        val cfg = config(stamp = true).copy(
            destination = Destination(qdrant = QdrantConfig("docs", null, meta, "emb", "qd"))
        )
        val ctx = JobContext("run-123", tapMetadata, Data(10L, null, null, null, null, Array[Byte](1, 2)), cfg, null, INITIALIZED, null, null)
        val stamped = ProvenanceStamper.stamp(ctx)
        val m = stamped.config.destination.qdrant.metadata
        assert(m.get("team") == "x") // existing metadata preserved
        assert(m.get(ProvenanceStamper.RunId) == "run-123")
        assert(m.get(ProvenanceStamper.TapRun) == "prices|2026-09-02 06:00:00 UTC")
        // Original config object untouched (stamp copies, never mutates).
        assert(!cfg.destination.qdrant.metadata.containsKey(ProvenanceStamper.RunId))
    }

    test("stampValues reports config version 1 for pre-versioning configs") {
        val ctx = delimitedCtx(config(stamp = true).copy(version = 0), tapMetadata)
        val values = ProvenanceStamper.stampValues(ctx, "2026-09-02T00:00:00Z").toMap
        assert(values(ProvenanceStamper.ConfigVersion) == "1")
        assert(values(ProvenanceStamper.IngestedAt) == "2026-09-02T00:00:00Z")
    }
}
