package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

class ProvenanceStamperSpec extends AnyFunSuite {

    /** Rows of a staged payload, read through the streaming iterator (never `Data.rows`). */
    private def rowsOf(data: Data): List[String] = {
        val it = data.rowIterator()
        try it.toList
        finally it.close()
    }

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
        val cols = rowsOf(stamped.data).head.split(",", -1)
        assert(cols.length == 2 + names.size)
        assert(cols(2) == "run-123") // _datris_run_id
        assert(cols(4) == "7") // _datris_config_version
        assert(cols(5) == "prices|2026-09-02 06:00:00 UTC") // _datris_tap_run
        assert(cols(6) == "a1b2c3d") // _datris_script_sha
        assert(cols(7) == "example-host") // _datris_source
        // All rows carry the identical constant suffix.
        assert(rowsOf(stamped.data).map(_.split(",", -1).drop(2).toList).distinct.size == 1)
    }

    test("stamping is idempotent — an already-stamped header is left alone") {
        val once = ProvenanceStamper.stamp(delimitedCtx(config(stamp = true), tapMetadata))
        val twice = ProvenanceStamper.stamp(once)
        assert(twice.data.header == once.data.header)
        assert(rowsOf(twice.data) == rowsOf(once.data))
    }

    test("direct uploads stamp empty tap fields, not nulls that shift columns") {
        val stamped = ProvenanceStamper.stamp(delimitedCtx(config(stamp = true), metadata = null))
        val cols = rowsOf(stamped.data).head.split(",", -1)
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

    // Phase 5 (plans/stories/streaming-pipeline-phase5.md, Step 10): the NDJSON
    // stamp serializes with the same Gson as PayloadStager — explicit nulls kept,
    // no HTML escaping — so stamping never alters a record's values.
    test("stamping an NDJSON record with a null member and a '<' keeps the null and does not escape the '<'") {
        val jsonCfg = config(stamp = true).copy(
            source = Source(
                schemaProperties = SchemaProperties("db", fields("id", "amount")),
                fileAttributes = FileAttributes(jsonAttributes = JsonAttributes())
            )
        )
        val raw = "[{\"id\":1,\"note\":null,\"html\":\"<b>a & b</b>\"},{\"id\":2,\"note\":\"x\",\"html\":\">\"}]"
        val ctx = JobContext("run-123", tapMetadata, Data(10L, null, null, null, raw), jsonCfg, null, INITIALIZED, null, null)
        assert(ctx.data.isNdJson, "fixture stages as NDJSON")
        val before = rowsOf(ctx.data)
        assert(before.head.contains("\"note\":null") && before.head.contains("<b>a & b</b>"), "PayloadStager already keeps nulls and '<': " + before.head)

        val stamped = ProvenanceStamper.stamp(ctx)
        val lines = rowsOf(stamped.data)
        assert(lines.size == 2)
        assert(lines.head.contains("\"" + ProvenanceStamper.RunId + "\":\"run-123\""), "stamped: " + lines.head)
        assert(lines.head.contains("\"note\":null"), "the explicit null survives stamping: " + lines.head)
        assert(lines.head.contains("<b>a & b</b>"), "'<', '>' and '&' are not HTML-escaped: " + lines.head)
        assert(!lines.head.contains("\\u003c") && !lines.head.contains("\\u0026"), "no \\u003c / \\u0026 escapes: " + lines.head)
        assert(lines(1).contains("\"html\":\">\""), lines(1))
        val obj = com.google.gson.JsonParser.parseString(lines.head).getAsJsonObject
        assert(obj.has("note") && obj.get("note").isJsonNull)
        assert(obj.get("html").getAsString == "<b>a & b</b>")
    }

    // Phase 3 (plans/stories/streaming-pipeline-phase3.md): stampDelimited
    // rewrites the staged file row by row and never reads `Data.rows`.
    test("a delimited payload stamps with no materialization warning") {
        val base = delimitedCtx(config(stamp = true), tapMetadata)
        val materialized = new ListBuffer[String]()
        val spy = new Data(base.data.size, base.data.header, base.data.headerWithSchema, base.data.staged, base.data.rawBytes) {
            override def rows: List[String] = { materialized += "rows"; super.rows }
            override def rawData: String = { materialized += "rawData"; super.rawData }
        }
        val stamped = ProvenanceStamper.stamp(base.copy(data = spy))
        assert(stamped.data.header == List("id", "amount") ++ ProvenanceStamper.AllFields, "stamping happened")
        assert(stamped.data.rowCount == 2)
        assert(rowsOf(stamped.data).forall(_.split(",", -1).length == 2 + ProvenanceStamper.AllFields.size))
        assert(materialized.isEmpty, s"ProvenanceStamper materialized the payload via $materialized (this is the 'staged payload materialized by' warning)")
    }

    // ------------------------------------------------------------------
    // Strict evidence mode (plans/stories/strict-evidence-mode.md)
    //
    // Pinned seam (does not exist on main at 3de8be3; the implementation adds it):
    // {{{
    // // ai.datris.util.ProvenanceStamper — the public stamp(ctx) reads
    // // DatrisEnvironment.values.provenanceStrict (PROVENANCE_STRICT) and
    // // delegates here.
    // private[datris] def stamp(ctx: JobContext, strict: Boolean): JobContext
    // }}}
    // Under strict, a NotStamped outcome or a caught exception throws
    //   DatrisException("Provenance could not be stamped (<reason>) and PROVENANCE_STRICT is on; nothing was loaded")
    // An XML source is Exempt: never throws, loads unstamped, and under strict
    // only writes ctx.statusUtil.info("processing",
    //   "Provenance stamping skipped (XML source is exempt from PROVENANCE_STRICT)").
    // Default mode is silent for XML, as today.
    // ------------------------------------------------------------------

    private class RecordingStatusUtil extends StatusUtil {
        val messages = new ListBuffer[(String, String)]()
        override def overrideProcessName(processName: String): Unit = ()
        override def info(state: String, description: String): Unit = messages += ((state, description))
        override def warn(state: String, description: String): Unit = messages += ((state, description))
        override def error(state: String, description: String): Unit = messages += ((state, description))
    }

    private val XmlExemptLine = "Provenance stamping skipped (XML source is exempt from PROVENANCE_STRICT)"

    /** Fields that are constant for a run: excludes _datris_ingested_at, which
      * is Instant.now() and would differ between two stamp calls. */
    private def constantFields: java.util.List[String] =
        new java.util.ArrayList[String](ProvenanceStamper.AllFields.filterNot(_ == ProvenanceStamper.IngestedAt).asJava)

    private def jsonConfig(selected: java.util.List[String] = null): PipelineConfig =
        config(stamp = true, selected).copy(
            source = Source(
                schemaProperties = SchemaProperties("db", fields("id", "amount")),
                fileAttributes = FileAttributes(jsonAttributes = JsonAttributes())
            )
        )

    private def xmlConfig: PipelineConfig =
        config(stamp = true).copy(
            source = Source(
                schemaProperties = SchemaProperties("db", fields("id", "amount")),
                fileAttributes = FileAttributes(xmlAttributes = XmlAttributes())
            )
        )

    /** A staged NDJSON payload written line by line, exactly as given (a
      * broken line stays broken; PayloadStager would refuse to stage it as NDJSON). */
    private def stagedNdJson(lines: String*): Data = {
        val (path, writer) = StagingArea.newWriter("spec", StagedFormat.NdJson)
        try writer.write(lines.mkString("\n"))
        finally writer.close()
        new Data(10L, null, null, StagedPayload(path.toString, StagedFormat.NdJson, lines.size.toLong, Files.size(path)), null)
    }

    private def ctxOf(cfg: PipelineConfig, data: Data, status: StatusUtil): JobContext =
        JobContext("run-123", tapMetadata, data, cfg, null, INITIALIZED, null, status)

    /** A delimited payload whose staged rows cannot be read: stampDelimited
      * throws inside stamp(), the path the catch at ProvenanceStamper.scala:70-78 covers. */
    private def throwingDelimitedCtx(status: StatusUtil): JobContext = {
        val base = delimitedCtx(config(stamp = true), tapMetadata)
        val broken = new Data(base.data.size, base.data.header, base.data.headerWithSchema, base.data.staged, base.data.rawBytes) {
            override def rowIterator(): CloseableIterator[String] = throw new java.io.IOException("disk full")
        }
        base.copy(data = broken, statusUtil = status)
    }

    test("default mode loads unstamped when stamping throws") {
        val status = new RecordingStatusUtil
        val ctx = throwingDelimitedCtx(status)
        val out = ProvenanceStamper.stamp(ctx, strict = false)
        assert(out eq ctx, "the job continues with the unstamped context")
        assert(out.data.header == List("id", "amount"), "no provenance columns: " + out.data.header)
        assert(
            status.messages.exists { case (s, d) => s == "processing" && d.startsWith("Provenance stamping skipped (error)") && d.contains("disk full") },
            "the miss is on the job status: " + status.messages
        )
    }

    test("strict mode fails when stamping throws") {
        val status = new RecordingStatusUtil
        val e = intercept[DatrisException] { ProvenanceStamper.stamp(throwingDelimitedCtx(status), strict = true) }
        assert(e.getMessage.startsWith("Provenance could not be stamped ("), e.getMessage)
        assert(e.getMessage.contains("disk full"), "the reason is named: " + e.getMessage)
        assert(e.getMessage.contains("PROVENANCE_STRICT is on; nothing was loaded"), e.getMessage)
    }

    test("strict mode loads an XML source unstamped and records the exemption on the job status") {
        val status = new RecordingStatusUtil
        val ctx = ctxOf(xmlConfig, Data(10L, null, null, null, "<rows><row><id>1</id></row></rows>"), status)
        assert(ctx.data.isVerbatimDocument, "fixture stages as an XML document")
        val out = ProvenanceStamper.stamp(ctx, strict = true) // must not throw
        assert(out.data eq ctx.data, "the XML payload is loaded unstamped")
        assert(out.config eq ctx.config, "no schema or destination change")
        assert(status.messages.contains(("processing", XmlExemptLine)), "exemption recorded: " + status.messages)
        assert(!status.messages.exists(_._2.startsWith("Provenance stamped")), status.messages)
    }

    test("default mode loads an XML source unstamped with no status line") {
        val status = new RecordingStatusUtil
        val ctx = ctxOf(xmlConfig, Data(10L, null, null, null, "<rows><row><id>1</id></row></rows>"), status)
        val out = ProvenanceStamper.stamp(ctx, strict = false)
        assert(out.data eq ctx.data)
        assert(!status.messages.exists(_._2.contains("Provenance")), "default mode stays silent for XML: " + status.messages)
    }

    test("strict mode fails NDJSON with an unparseable line") {
        val lines = Seq("{\"id\":1}", "{\"id\":2", "{\"id\":3}")
        val strictCtx = ctxOf(jsonConfig(), stagedNdJson(lines: _*), new RecordingStatusUtil)
        assert(strictCtx.data.isNdJson, "fixture is a staged NDJSON payload")
        val e = intercept[DatrisException] { ProvenanceStamper.stamp(strictCtx, strict = true) }
        assert(e.getMessage.startsWith("Provenance could not be stamped ("), e.getMessage)
        assert(e.getMessage.contains("PROVENANCE_STRICT is on; nothing was loaded"), e.getMessage)
        // Default mode, same payload: loads unstamped, as today.
        val defaultCtx = ctxOf(jsonConfig(), stagedNdJson(lines: _*), new RecordingStatusUtil)
        val out = ProvenanceStamper.stamp(defaultCtx, strict = false)
        assert(rowsOf(out.data) == lines.toList, "default leaves the payload untouched")
    }

    test("strict mode accepts an already stamped payload") {
        // Delimited: the idempotence guard (ProvenanceStamper.scala:105).
        val once = ProvenanceStamper.stamp(delimitedCtx(config(stamp = true), tapMetadata), strict = false)
        assert(once.data.header.exists(_.startsWith(ProvenanceStamper.Prefix)), "fixture is stamped")
        val again = ProvenanceStamper.stamp(once, strict = true) // must not throw
        assert(again.data.header == once.data.header)
        assert(rowsOf(again.data) == rowsOf(once.data))

        // NDJSON: every object already carries _datris_run_id (nothing changed).
        val stampedJson = ProvenanceStamper.stamp(ctxOf(jsonConfig(), stagedNdJson("{\"id\":1}", "{\"id\":2}"), new RecordingStatusUtil), strict = false)
        assert(rowsOf(stampedJson.data).forall(_.contains(ProvenanceStamper.RunId)), "fixture is stamped")
        val jsonAgain = ProvenanceStamper.stamp(stampedJson, strict = true) // must not throw
        assert(rowsOf(jsonAgain.data) == rowsOf(stampedJson.data))
    }

    test("strict mode stamps delimited, NDJSON and vector data exactly as default mode does") {
        // Delimited
        val d0 = ProvenanceStamper.stamp(delimitedCtx(config(stamp = true, constantFields), tapMetadata), strict = false)
        val d1 = ProvenanceStamper.stamp(delimitedCtx(config(stamp = true, constantFields), tapMetadata), strict = true)
        assert(d1.data.header == d0.data.header && d1.data.header.contains(ProvenanceStamper.RunId), d1.data.header)
        assert(d1.data.headerWithSchema == d0.data.headerWithSchema)
        assert(rowsOf(d1.data) == rowsOf(d0.data))
        assert(d1.config.source.schemaProperties.fields == d0.config.source.schemaProperties.fields)
        assert(d1.config.destination.schemaProperties.fields == d0.config.destination.schemaProperties.fields)

        // NDJSON
        val lines = Seq("{\"id\":1,\"amount\":5}", "{\"id\":2,\"amount\":9}")
        val j0 = ProvenanceStamper.stamp(ctxOf(jsonConfig(constantFields), stagedNdJson(lines: _*), new RecordingStatusUtil), strict = false)
        val j1 = ProvenanceStamper.stamp(ctxOf(jsonConfig(constantFields), stagedNdJson(lines: _*), new RecordingStatusUtil), strict = true)
        assert(rowsOf(j1.data) == rowsOf(j0.data))
        assert(rowsOf(j1.data).forall(_.contains("\"" + ProvenanceStamper.RunId + "\":\"run-123\"")), rowsOf(j1.data).toString)

        // Vector (unstructured bytes into a vector destination's metadata map)
        def vectorCtx: JobContext = {
            val meta = new java.util.LinkedHashMap[String, String]()
            meta.put("team", "x")
            val cfg = config(stamp = true, constantFields).copy(destination = Destination(qdrant = QdrantConfig("docs", null, meta, "emb", "qd")))
            JobContext("run-123", tapMetadata, Data(10L, null, null, null, null, Array[Byte](1, 2)), cfg, null, INITIALIZED, null, null)
        }
        val v0 = ProvenanceStamper.stamp(vectorCtx, strict = false)
        val v1 = ProvenanceStamper.stamp(vectorCtx, strict = true)
        assert(v1.config.destination.qdrant.metadata == v0.config.destination.qdrant.metadata)
        assert(v1.config.destination.qdrant.metadata.get(ProvenanceStamper.RunId) == "run-123")
    }

    test("strict mode fails NDJSON that mixes objects with other JSON values; default stamps the objects") {
        val lines = Seq("{\"id\":1}", "42", "{\"id\":3}")
        val e = intercept[DatrisException] {
            ProvenanceStamper.stamp(ctxOf(jsonConfig(), stagedNdJson(lines: _*), new RecordingStatusUtil), strict = true)
        }
        assert(e.getMessage.contains("not JSON objects"), e.getMessage)
        val out = ProvenanceStamper.stamp(ctxOf(jsonConfig(), stagedNdJson(lines: _*), new RecordingStatusUtil), strict = false)
        val rows = rowsOf(out.data)
        assert(rows(1) == "42" && rows.head.contains(ProvenanceStamper.RunId) && rows(2).contains(ProvenanceStamper.RunId), rows.toString)
    }

    test("strict mode fails unstructured data with no vector destination; default proceeds") {
        def unstructuredCtx: JobContext =
            JobContext("run-123", tapMetadata, Data(10L, null, null, null, null, Array[Byte](1, 2)), config(stamp = true), null, INITIALIZED, null, null)
        val e = intercept[DatrisException] { ProvenanceStamper.stamp(unstructuredCtx, strict = true) }
        assert(e.getMessage.contains("no vector destination"), e.getMessage)
        ProvenanceStamper.stamp(unstructuredCtx, strict = false) // must not throw
    }

    test("strict mode accepts an empty NDJSON payload (nothing to stamp, nothing loaded)") {
        val ctx = ctxOf(jsonConfig(), stagedNdJson(), new RecordingStatusUtil)
        assert(ctx.data.isNdJson && ctx.data.staged.rowCount == 0)
        val out = ProvenanceStamper.stamp(ctx, strict = true) // must not throw
        assert(out eq ctx)
    }
}
