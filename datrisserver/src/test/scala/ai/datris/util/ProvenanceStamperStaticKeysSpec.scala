package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files
import scala.collection.JavaConverters._

/** With provenance stamping on, only the stamper writes the
  * [[ProvenanceStamper.AllFields]] names: a static vector destination
  * `metadata` key (or a key on a stamped JSON object) under one of those names
  * never reaches stored chunks or rows, whether or not its field is selected
  * or has a value for the run. With stamping off nothing is removed. */
class ProvenanceStamperStaticKeysSpec extends AnyFunSuite {

    private val hrTap =
        PipelineMetadata(
            "orders",
            "tap-t_hr_feed.csv",
            null,
            "pub-1",
            bulkUpload = false,
            tapName = "t_hr_feed",
            tapRunTime = "2026-11-01T00:00:00Z",
            tapScriptSha = "sha-hr",
            tapSource = "tap:t_hr_feed"
        )

    private def list(xs: String*): java.util.List[String] = new java.util.ArrayList[String](xs.asJava)

    private def planted(pairs: (String, String)*): java.util.Map[String, String] = {
        val m = new java.util.LinkedHashMap[String, String]()
        m.put("team", "sales")
        pairs.foreach { case (k, v) => m.put(k, v) }
        m
    }

    private def provenance(stamp: Boolean, fields: java.util.List[String]): ProvenanceConfig =
        if (stamp) ProvenanceConfig(stamp = true, fields = fields) else ProvenanceConfig(stamp = false)

    private def vectorCtx(
        meta: java.util.Map[String, String],
        fields: java.util.List[String] = null,
        stamp: Boolean = true,
        md: PipelineMetadata = hrTap
    ): JobContext = {
        val cfg = PipelineConfig(
            name = "orders",
            destination = Destination(pgvector = PGVectorConfig("orders", "public", null, meta, "emb", "pg")),
            version = 2,
            provenance = provenance(stamp, fields)
        )
        JobContext("run-hr", md, Data(10L, null, null, null, null, Array[Byte](1, 2)), cfg, null, INITIALIZED, null, null)
    }

    private def pg(ctx: JobContext): java.util.Map[String, String] = ctx.config.destination.pgvector.metadata

    test("scenario A: a static _datris_tap_run naming a readable tap is dropped when provenance.fields omits _datris_tap_run") {
        val meta = planted(ProvenanceStamper.TapRun -> "t_sales|x")
        val ctx = vectorCtx(meta, list(ProvenanceStamper.RunId, ProvenanceStamper.Source, ProvenanceStamper.ScriptSha))
        val m = pg(ProvenanceStamper.stamp(ctx, strict = false))
        assert(!m.containsKey(ProvenanceStamper.TapRun), m)
        assert(m.get(ProvenanceStamper.RunId) == "run-hr")
        assert(m.get(ProvenanceStamper.Source) == "tap:t_hr_feed")
        assert(m.get(ProvenanceStamper.ScriptSha) == "sha-hr")
        assert(m.get("team") == "sales", "other static metadata is kept")
        assert(meta.get(ProvenanceStamper.TapRun) == "t_sales|x", "the stored config's map is not modified")
    }

    test("scenario B: a static _datris_run_id is dropped when provenance.fields omits _datris_run_id") {
        val ctx = vectorCtx(planted(ProvenanceStamper.RunId -> "upload-run-7"), list(ProvenanceStamper.Source, ProvenanceStamper.ScriptSha))
        val m = pg(ProvenanceStamper.stamp(ctx, strict = false))
        assert(!m.containsKey(ProvenanceStamper.RunId), m)
        assert(m.get(ProvenanceStamper.Source) == "tap:t_hr_feed" && m.get(ProvenanceStamper.ScriptSha) == "sha-hr")
    }

    test("a static key under a selected field is replaced by the stamped value; all fields selected") {
        val ctx = vectorCtx(planted(ProvenanceStamper.TapRun -> "t_sales|x", ProvenanceStamper.ConfigVersion -> "99"))
        val m = pg(ProvenanceStamper.stamp(ctx, strict = false))
        assert(m.get(ProvenanceStamper.TapRun) == "t_hr_feed|2026-11-01T00:00:00Z")
        assert(m.get(ProvenanceStamper.ConfigVersion) == "2")
    }

    test("an upload run (no tap fields to stamp) carries none of the static tap stamp keys") {
        val meta = planted(ProvenanceStamper.TapRun -> "t_sales|x", ProvenanceStamper.Source -> "crm", ProvenanceStamper.ScriptSha -> "s")
        val m = pg(ProvenanceStamper.stamp(vectorCtx(meta, md = null), strict = false))
        assert(m.get(ProvenanceStamper.RunId) == "run-hr")
        Seq(ProvenanceStamper.TapRun, ProvenanceStamper.Source, ProvenanceStamper.ScriptSha).foreach(k => assert(!m.containsKey(k), k + ": " + m))
    }

    test("a run that loads unstamped (the selected fields have no value) still drops the static stamp keys") {
        val meta = planted(ProvenanceStamper.Source -> "crm")
        val ctx = vectorCtx(meta, list(ProvenanceStamper.TapRun), md = null)
        val out = ProvenanceStamper.stamp(ctx, strict = false)
        assert(!pg(out).containsKey(ProvenanceStamper.Source), pg(out))
        assert(pg(out).get("team") == "sales")
        assert(!pg(out).containsKey(ProvenanceStamper.TapRun))
    }

    test("every vector destination's metadata map is cleaned") {
        def m: java.util.Map[String, String] = planted(ProvenanceStamper.TapRun -> "t_sales|x", ProvenanceStamper.RunId -> "r")
        val cfg = PipelineConfig(
            name = "orders",
            destination = Destination(
                qdrant = QdrantConfig("c", null, m, "e", "q"),
                weaviate = WeaviateConfig("C", null, m, "e", "w"),
                pgvector = PGVectorConfig("t", "public", null, m, "e", "p"),
                milvus = MilvusConfig("c", null, m, "e", "m"),
                chroma = ChromaConfig("c", null, m, "e", "ch")
            ),
            provenance = ProvenanceConfig(stamp = true, fields = list(ProvenanceStamper.Source))
        )
        val ctx = JobContext("run-hr", hrTap, Data(10L, null, null, null, null, Array[Byte](1)), cfg, null, INITIALIZED, null, null)
        val d = ProvenanceStamper.stamp(ctx, strict = false).config.destination
        Seq(d.qdrant.metadata, d.weaviate.metadata, d.pgvector.metadata, d.milvus.metadata, d.chroma.metadata).foreach { x =>
            assert(!x.containsKey(ProvenanceStamper.TapRun) && !x.containsKey(ProvenanceStamper.RunId), x)
            assert(x.get(ProvenanceStamper.Source) == "tap:t_hr_feed" && x.get("team") == "sales", x)
        }
    }

    test("with stamping off, static _datris_* metadata is left as configured") {
        val ctx = vectorCtx(planted(ProvenanceStamper.TapRun -> "t_sales|x", ProvenanceStamper.RunId -> "r"), stamp = false)
        val out = ProvenanceStamper.stamp(ctx, strict = false)
        assert(out eq ctx)
        assert(pg(out).get(ProvenanceStamper.TapRun) == "t_sales|x" && pg(out).get(ProvenanceStamper.RunId) == "r")
        val absent = ctx.copy(config = ctx.config.copy(provenance = null))
        assert(ProvenanceStamper.stamp(absent, strict = false) eq absent)
    }

    test("withoutStaticStamps returns the same context when no map holds a stamp key") {
        val ctx = vectorCtx(planted())
        assert(ProvenanceStamper.withoutStaticStamps(ctx) eq ctx)
        val noMeta = vectorCtx(null)
        assert(ProvenanceStamper.withoutStaticStamps(noMeta) eq noMeta)
    }

    private def stagedNdJson(lines: String*): Data = {
        val (path, writer) = StagingArea.newWriter("spec", StagedFormat.NdJson)
        try writer.write(lines.mkString("\n"))
        finally writer.close()
        new Data(10L, null, null, StagedPayload(path.toString, StagedFormat.NdJson, lines.size.toLong, Files.size(path)), null)
    }

    private def rowsOf(data: Data): List[String] = {
        val it = data.rowIterator()
        try it.toList
        finally it.close()
    }

    test("a JSON object the stamper stamps loses keys under unselected stamp names") {
        val cfg = PipelineConfig(
            name = "orders",
            source = Source(fileAttributes = FileAttributes(jsonAttributes = JsonAttributes())),
            destination = Destination(),
            provenance = ProvenanceConfig(stamp = true, fields = list(ProvenanceStamper.RunId, ProvenanceStamper.Source))
        )
        val data = stagedNdJson("{\"id\":1,\"_datris_tap_run\":\"t_sales|x\",\"_datris_script_sha\":\"fake\"}", "{\"id\":2}")
        val out = ProvenanceStamper.stamp(JobContext("run-hr", hrTap, data, cfg, null, INITIALIZED, null, null), strict = false)
        val rows = rowsOf(out.data).map(l => JsonParser.parseString(l).getAsJsonObject)
        rows.foreach { o =>
            assert(!o.has(ProvenanceStamper.TapRun) && !o.has(ProvenanceStamper.ScriptSha), o)
            assert(o.get(ProvenanceStamper.RunId).getAsString == "run-hr" && o.get(ProvenanceStamper.Source).getAsString == "tap:t_hr_feed", o)
        }

        val injected = ProvenanceStamper.injectJson(
            "[{\"id\":1,\"_datris_tap_run\":\"t_sales|x\"}]",
            List(ProvenanceStamper.RunId -> "run-hr", ProvenanceStamper.Source -> "tap:t_hr_feed")
        )
        val o = JsonParser.parseString(injected).getAsJsonArray.get(0).getAsJsonObject
        assert(!o.has(ProvenanceStamper.TapRun) && o.get(ProvenanceStamper.Source).getAsString == "tap:t_hr_feed", o)
    }
}
