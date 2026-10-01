package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.{JsonArray, JsonObject, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

import java.net.{URI, URLDecoder}
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/** Story: Unity Catalog 3: lineage publish
  * (plans/stories/unity-catalog-3-lineage-publish.md), Acceptance bullet 2.
  *
  * Seams this spec pins. Builders and names come from the story's Files
  * section; payload shapes are the databricks-sdk-py `service/catalog.py`
  * dataclasses (ExternalMetadata, ExternalLineageRelationship,
  * ExternalLineageObject, ColumnRelationship, ListExternalLineageRelationshipsResponse):
  *
  * {{{
  *   object UnityCatalogLineagePublisher {
  *     def ucName(raw: String): String
  *     def tapObject(tap: TapConfig, scriptSha: String): JsonObject      // ExternalMetadata body
  *     def uploadObject(pipeline: String): JsonObject                     // ExternalMetadata body
  *     def pipelineObject(config: PipelineConfig): JsonObject             // ExternalMetadata body
  *     def tableEdge(edges: List[ColumnEdge], qualified: String): JsonObject  // relationship; target {table:{name}}
  *     def runProperties(runId: String, recordCount: Int, status: String, runAt: String): JsonObject // string map
  *     def lineageHash(configVersion: Int, columnLineageVersion: Int, scriptSha: String, edges: List[ColumnEdge]): String
  *     def publish(client: DatabricksRestClient, config: PipelineConfig, md: PipelineMetadata,
  *                 columnLineage: ColumnLineageService.Result, previous: UnityCatalogSyncState,
  *                 tableCreated: Boolean, runId: String, recordCount: Int, dqStatus: String,
  *                 statusUtil: StatusUtil, tap: TapConfig): UnityCatalogSyncState
  *   }
  * }}}
  *
  * `publish` is called with named arguments only. The `tap` parameter is NOT in
  * the story's publish signature: `sync` resolves it via TapConfigIO.read, and
  * publish needs it to build the tap object without touching the config DB
  * (DatrisEnvironment is null in unit tests). The run's script SHA is
  * `md.tapScriptSha`.
  *
  * The fake workspace below speaks the REST paths confirmed from the SDK:
  * `GET/PATCH /api/2.0/lineage-tracking/external-metadata/{name}`,
  * `POST /api/2.0/lineage-tracking/external-metadata`,
  * `GET /api/2.0/lineage-tracking/external-lineage?object_info.external_metadata.name=..&lineage_direction=..`,
  * `POST|PATCH /api/2.0/lineage-tracking/external-lineage` (PATCH carries the
  * relationship `id` in the body and `?update_mask=`). PAT credentials, so no
  * token requests are recorded.
  */
class UnityCatalogLineagePublisherSpec extends AnyFunSuite {

    private val P = UnityCatalogLineagePublisher

    private val PIPELINE = "orders_daily"
    private val TAP = "orders_tap"
    private val QUALIFIED = "datris.default.orders"
    private val PIPE_NODE = "datris-pipeline-" + PIPELINE
    private val TAP_NODE = "datris-tap-" + TAP
    private val META = "/api/2.0/lineage-tracking/external-metadata"
    private val LINEAGE = "/api/2.0/lineage-tracking/external-lineage"

    private val db = Database(dbName = "datris", schema = "default", table = "orders", useDatabricks = true, warehouse = "abc123", credentialsSecret = "dbx")
    private val config = PipelineConfig(
        name = PIPELINE,
        catalog = "sales",
        version = 3,
        destination = Destination(database = db),
        unityCatalog = UnityCatalogSync(enabled = true)
    )
    private val scriptTap = TapConfig(name = TAP, description = "Daily orders feed", targetPipeline = PIPELINE, source = "SEC EDGAR")
    private val httpTap = scriptTap.copy(scriptKind = "http", endpointUrl = "https://feeds.example.org/orders")
    private val tapMd = PipelineMetadata(
        PIPELINE,
        "orders.json",
        "/tmp/orders.json",
        "pub-1",
        bulkUpload = false,
        tapName = TAP,
        tapRunTime = "2026-09-29T12:00:00Z",
        tapScriptSha = "sha-aaa",
        tapSource = "SEC EDGAR"
    )
    private val uploadMd = PipelineMetadata(PIPELINE, "orders_20260929.csv", "/tmp/orders.csv", "pub-2", bulkUpload = false)

    private def edge(from: List[String], to: String, op: String, confidence: String, evidence: String = null) =
        ColumnEdge(from.asJava, to, op, confidence, evidence)

    private val exactEdges = List(
        edge(List("id"), "id", "passthrough", "exact"),
        edge(List("amt"), "amount", "rename", "exact"),
        edge(List("first", "last"), "full_name", "derive", "exact"),
        edge(List("received"), "_datris_ingested_at", "system", "system"),
        edge(Nil, "_datris_run_id", "system", "system"),
        edge(List("junk"), "", "drop", "exact")
    )
    private val inferredEdges = List(
        edge(List("price", "qty"), "total", "derive", "inferred", "total = price * qty"),
        edge(List("ts"), "order_date", "derive", "inferred", "to_date(ts)")
    )

    private def lineageResult(edges: List[ColumnEdge] = exactEdges, version: Int = 3) =
        ColumnLineageService.Result(
            pipeline = PIPELINE,
            version = version,
            versionSource = "current",
            sourceFields = List("id", "amt", "first", "last", "received", "junk"),
            destinationFields = List("id", "amount", "full_name"),
            destinationSchema = "declared",
            transformation = new JsonObject(),
            edges = edges,
            unresolved = Nil,
            inferred = new JsonObject()
        )

    // --- JSON helpers --------------------------------------------------------

    private def str(o: JsonObject, k: String): String =
        if (o == null || !o.has(k) || o.get(k).isJsonNull) null else o.get(k).getAsString
    private def obj(o: JsonObject, k: String): JsonObject =
        if (o == null || !o.has(k) || o.get(k).isJsonNull) null else o.getAsJsonObject(k)
    private def props(o: JsonObject): Map[String, String] = {
        val p = obj(o, "properties")
        assert(p != null, s"no properties in $o")
        p.entrySet().asScala.map { e =>
            assert(e.getValue.isJsonPrimitive && e.getValue.getAsJsonPrimitive.isString, s"properties is a string map; ${e.getKey} = ${e.getValue}")
            e.getKey -> e.getValue.getAsString
        }.toMap
    }
    private def columns(rel: JsonObject): List[(String, String)] = {
        val arr = rel.getAsJsonArray("columns")
        assert(arr != null, s"no columns in $rel")
        arr.asScala.map { c =>
            val o = c.getAsJsonObject
            assert(o.keySet().asScala == Set("source", "target"), s"column mapping must be exactly {source,target}: $o")
            (str(o, "source"), str(o, "target"))
        }.toList
    }
    // --- fake workspace --------------------------------------------------------

    private case class Call(method: String, path: String, query: Map[String, String], body: JsonObject) {
        def isMetaWrite: Boolean = (method == "POST" && path == META) || (method == "PATCH" && path.startsWith(META + "/"))
        def metaName: String = if (method == "POST") str(body, "name") else URLDecoder.decode(path.stripPrefix(META + "/"), "UTF-8")
        def isLineageWrite: Boolean = path == LINEAGE && (method == "POST" || method == "PATCH")
        def mask: Set[String] = query.get("update_mask").map(_.split(",").map(_.trim).filter(_.nonEmpty).toSet).getOrElse(Set.empty)
    }

    private class FakeWorkspace(
        existingObjects: Set[String] = Set.empty,
        existingRelationships: List[JsonObject] = Nil,
        failWhen: Call => Option[(Int, String)] = _ => None
    ) {
        val calls = new ListBuffer[Call]()
        val objects: mutable.Set[String] = mutable.Set(existingObjects.toSeq: _*)
        val relationships: ListBuffer[JsonObject] = ListBuffer(existingRelationships: _*)
        private var nextId = 0

        private def parseQuery(raw: String): Map[String, String] =
            Option(raw).filter(_.nonEmpty).map(_.split("&").toList.map { kv =>
                val i = kv.indexOf('=')
                val (k, v) = if (i < 0) (kv, "") else (kv.substring(0, i), kv.substring(i + 1))
                URLDecoder.decode(k, "UTF-8") -> URLDecoder.decode(v, "UTF-8")
            }.toMap).getOrElse(Map.empty)

        val transport: HttpTransport = (method: String, url: String, headers: Map[String, String], body: String) => {
            val uri = URI.create(url)
            val call = Call(
                method.toUpperCase,
                uri.getRawPath,
                parseQuery(uri.getRawQuery),
                if (body == null || body.trim.isEmpty) null else JsonParser.parseString(body).getAsJsonObject
            )
            calls += call
            failWhen(call).getOrElse(respond(call))
        }

        private def respond(c: Call): (Int, String) = (c.method, c.path) match {
            case ("GET", p) if p.startsWith(META + "/") =>
                val name = URLDecoder.decode(p.stripPrefix(META + "/"), "UTF-8")
                if (objects.contains(name)) (200, s"""{"name":"$name","system_type":"OTHER","entity_type":"x","id":"obj-$name"}""")
                else (404, s"""{"error_code":"RESOURCE_DOES_NOT_EXIST","message":"External metadata '$name' does not exist."}""")
            case ("POST", META) =>
                objects += str(c.body, "name")
                (200, c.body.toString)
            case ("PATCH", p) if p.startsWith(META + "/") =>
                (200, c.body.toString)
            case ("GET", LINEAGE) =>
                val name = c.query.getOrElse("object_info.external_metadata.name", "")
                val dir = c.query.getOrElse("lineage_direction", "")
                val matching = relationships.filter { r =>
                    val src = Option(obj(obj(r, "source"), "external_metadata")).map(str(_, "name")).orNull
                    val tgt = Option(obj(obj(r, "target"), "external_metadata")).map(str(_, "name")).orNull
                    if (dir == "DOWNSTREAM") src == name else if (dir == "UPSTREAM") tgt == name else src == name || tgt == name
                }
                val arr = new JsonArray()
                matching.foreach { r =>
                    val w = new JsonObject(); w.add("external_lineage_info", r); arr.add(w)
                }
                val out = new JsonObject(); out.add("external_lineage_relationships", arr)
                (200, out.toString)
            case ("POST", LINEAGE) =>
                nextId += 1
                val r = c.body.deepCopy()
                r.addProperty("id", s"rel-new-$nextId")
                relationships += r
                (200, r.toString)
            case ("PATCH", LINEAGE) =>
                (200, c.body.toString)
            case _ =>
                (404, """{"error_code":"ENDPOINT_NOT_FOUND"}""")
        }

        def reset(): Unit = calls.clear()
        def metaWrites: List[Call] = calls.filter(_.isMetaWrite).toList
        def lineageWrites: List[Call] = calls.filter(_.isLineageWrite).toList
        def lineagePosts: List[Call] = lineageWrites.filter(_.method == "POST")
        def lineagePatches: List[Call] = lineageWrites.filter(_.method == "PATCH")
        def client: DatabricksRestClient =
            new DatabricksRestClient(
                ResolvedDatabricksCredentials(host = "dbc-1.cloud.databricks.com", clientId = None, clientSecret = None, token = Some("dapi-x")),
                transport,
                () => 1790000000000L
            )
    }

    private class RecordingStatusUtil extends StatusUtil {
        val messages = new ListBuffer[(String, String, String)]()
        override def overrideProcessName(processName: String): Unit = ()
        override def info(state: String, description: String): Unit = messages += (("info", state, description))
        override def warn(state: String, description: String): Unit = messages += (("warning", state, description))
        override def error(state: String, description: String): Unit = messages += (("error", state, description))
    }

    private def publish(
        ws: FakeWorkspace,
        previous: UnityCatalogSyncState,
        cfg: PipelineConfig = config,
        md: PipelineMetadata = tapMd,
        tap: TapConfig = scriptTap,
        lineage: ColumnLineageService.Result = lineageResult(),
        tableCreated: Boolean = false,
        runId: String = "run-0001",
        recordCount: Int = 42,
        status: StatusUtil = new RecordingStatusUtil
    ): UnityCatalogSyncState =
        P.publish(
            client = ws.client,
            config = cfg,
            md = md,
            columnLineage = lineage,
            previous = previous,
            tableCreated = tableCreated,
            runId = runId,
            recordCount = recordCount,
            dqStatus = "pass",
            statusUtil = status,
            tap = tap
        )

    private def relWithId(id: String, sourceNode: JsonObject, targetNode: JsonObject): JsonObject = {
        val r = new JsonObject()
        r.addProperty("id", id)
        r.add("source", sourceNode)
        r.add("target", targetNode)
        r
    }
    private def extNode(name: String): JsonObject = {
        val inner = new JsonObject(); inner.addProperty("name", name)
        val o = new JsonObject(); o.add("external_metadata", inner); o
    }
    private def tableNode(name: String): JsonObject = {
        val inner = new JsonObject(); inner.addProperty("name", name)
        val o = new JsonObject(); o.add("table", inner); o
    }
    private def relTarget(c: Call): String = {
        val t = obj(c.body, "target")
        Option(obj(t, "table")).map(x => "table:" + str(x, "name"))
            .orElse(Option(obj(t, "external_metadata")).map(str(_, "name"))).orNull
    }

    // --- pure builders -----------------------------------------------------------

    test("tap object has name datris-tap-<name>, system_type OTHER, entity_type tap, datris.tap/scriptSha/source properties, url only for http taps") {
        val o = P.tapObject(scriptTap, "sha-aaa")
        assert(str(o, "name") == TAP_NODE, o)
        assert(str(o, "system_type") == "OTHER", o)
        assert(str(o, "entity_type") == "tap", o)
        assert(str(o, "description") == "Daily orders feed", o)
        val p = props(o)
        assert(p.get("datris.tap").contains(TAP), p)
        assert(p.get("datris.scriptSha").contains("sha-aaa"), p)
        assert(p.get("datris.source").contains("SEC EDGAR"), p)
        assert(str(o, "url") == null, s"script taps carry no url: $o")

        val h = P.tapObject(httpTap, "http:https://feeds.example.org/orders")
        assert(str(h, "url") == "https://feeds.example.org/orders", h)
        assert(str(h, "entity_type") == "tap" && str(h, "name") == TAP_NODE, h)
    }

    test("upload object is datris-upload-<pipeline>") {
        val o = P.uploadObject(PIPELINE)
        assert(str(o, "name") == "datris-upload-" + PIPELINE, o)
        assert(str(o, "entity_type") == "upload", o)
        assert(str(o, "system_type") == "OTHER", o)
    }

    test("pipeline object carries datris.catalog, datris.configVersion, datris.lineagePath") {
        val o = P.pipelineObject(config)
        assert(str(o, "name") == PIPE_NODE, o)
        assert(str(o, "entity_type") == "pipeline", o)
        assert(str(o, "system_type") == "OTHER", o)
        val p = props(o)
        assert(p.get("datris.catalog").contains("sales"), p)
        assert(p.get("datris.configVersion").contains("3"), p)
        assert(p.get("datris.lineagePath").contains("/api/v1/lineage/pipeline/" + PIPELINE), p)
    }

    test("ucName replaces dots, slashes and spaces") {
        assert(P.ucName("a.b/c d") == "a_b_c_d")
        assert(P.ucName("tab\there`x`") == "tab_here_x_")
        assert(P.ucName("datris-tap-orders_tap") == "datris-tap-orders_tap", "hyphens and underscores are kept")
        // Builders route names through ucName.
        assert(str(P.pipelineObject(config.copy(name = "orders.v2 eu")), "name") == "datris-pipeline-orders_v2_eu")
        assert(str(P.uploadObject("in/bound"), "name") == "datris-upload-in_bound")
        assert(str(P.tapObject(scriptTap.copy(name = "sec.filings"), "s"), "name") == "datris-tap-sec_filings")
    }

    test("table edge columns include exact and system edges as {source,target}, one per from field") {
        val rel = P.tableEdge(exactEdges, QUALIFIED)
        assert(str(obj(obj(rel, "target"), "table"), "name") == QUALIFIED, rel)
        val cols = columns(rel).toSet
        assert(
            cols == Set(
                "id" -> "id",
                "amt" -> "amount",
                "first" -> "full_name",
                "last" -> "full_name",
                "received" -> "_datris_ingested_at"
            ),
            cols
        )
        assert(columns(rel).size == 5, "one mapping per from field, no duplicates")
        assert(!columns(rel).exists(c => c._2 == null || c._2.isEmpty), "drop edges and empty targets are not mappings")
        val p = Option(obj(rel, "properties")).map(_ => props(rel)).getOrElse(Map.empty)
        assert(!p.contains("datris.confidence"), s"no inferred edges ⇒ no confidence property: $p")
    }

    test("inferred edges add datris.confidence=inferred and datris.evidence to the relationship properties") {
        val rel = P.tableEdge(exactEdges ++ inferredEdges, QUALIFIED)
        val cols = columns(rel).toSet
        assert(cols.contains("price" -> "total") && cols.contains("qty" -> "total") && cols.contains("ts" -> "order_date"), cols)
        assert(cols.contains("id" -> "id"), cols)
        val p = props(rel)
        assert(p.get("datris.confidence").contains("inferred"), p)
        val ev = p.getOrElse("datris.evidence", "")
        assert(ev.contains("total = price * qty") && ev.contains("to_date(ts)"), p)
    }

    test("run properties carry datris.lastRunId, recordCount, status, lastRunAt") {
        val o = P.runProperties("run-0042", 1234, "warn", "2026-09-29T12:00:00Z")
        val p = o.entrySet().asScala.map { e =>
            assert(e.getValue.getAsJsonPrimitive.isString, s"properties is a string map; ${e.getKey}")
            e.getKey -> e.getValue.getAsString
        }.toMap
        assert(p.get("datris.lastRunId").contains("run-0042"), p)
        assert(p.get("datris.recordCount").contains("1234"), p)
        assert(p.get("datris.status").contains("warn"), p)
        assert(p.get("datris.lastRunAt").contains("2026-09-29T12:00:00Z"), p)
    }

    // --- publish orchestration ---------------------------------------------------

    test("unchanged hash → only two PATCH calls with update_mask=properties") {
        val ws = new FakeWorkspace()
        val s1 = publish(ws, previous = null)
        assert(s1 != null && s1.lastError == null, s"$s1")
        assert(s1.lineageHash != null && s1.lastLineageAt != null, s"$s1")
        val ids = Option(s1.lineageRelationshipIds).map(_.asScala.toMap).getOrElse(Map.empty)
        assert(ids.keySet == Set("source", "table"), s"relationship ids cached under source/table: $ids")

        ws.reset()
        val s2 = publish(ws, previous = s1, runId = "run-0002", recordCount = 7)
        assert(ws.calls.size == 2, s"steady state is exactly two calls:\n${ws.calls.mkString("\n")}")
        assert(ws.calls.forall(c => c.method == "PATCH" && c.path == LINEAGE), ws.calls.mkString("\n"))
        assert(ws.calls.forall(_.mask == Set("properties")), ws.calls.map(_.query).mkString("\n"))
        assert(ws.calls.map(c => str(c.body, "id")).toSet == ids.values.toSet, ws.calls.mkString("\n"))
        ws.calls.foreach { c =>
            val p = props(c.body)
            assert(p.get("datris.lastRunId").contains("run-0002"), p)
            assert(p.get("datris.recordCount").contains("7"), p)
        }
        assert(s2.lineageHash == s1.lineageHash && s2.lastError == null, s"$s2")
    }

    test("changed configVersion or scriptSha → objects and relationships re-sent") {
        val ws = new FakeWorkspace()
        val s1 = publish(ws, previous = null)

        ws.reset()
        val s2 = publish(ws, previous = s1, cfg = config.copy(version = 4), lineage = lineageResult(version = 4), runId = "run-0002")
        assert(ws.metaWrites.map(_.metaName).toSet == Set(TAP_NODE, PIPE_NODE), ws.calls.mkString("\n"))
        assert(ws.lineageWrites.size == 2, ws.calls.mkString("\n"))
        ws.lineagePatches.foreach(c => assert(c.mask.contains("columns") && c.mask.contains("properties"), c.query))
        assert(ws.lineageWrites.exists(c => relTarget(c) == "table:" + QUALIFIED && columns(c.body).nonEmpty), ws.calls.mkString("\n"))
        assert(s2.lineageHash != null && s2.lineageHash != s1.lineageHash, s"$s1\n$s2")

        ws.reset()
        val s3 = publish(
            ws,
            previous = s2,
            cfg = config.copy(version = 4),
            lineage = lineageResult(version = 4),
            md = tapMd.copy(tapScriptSha = "sha-bbb"),
            runId = "run-0003"
        )
        assert(ws.metaWrites.map(_.metaName).toSet == Set(TAP_NODE, PIPE_NODE), ws.calls.mkString("\n"))
        val tapWrite = ws.metaWrites.find(_.metaName == TAP_NODE).get
        assert(props(tapWrite.body).get("datris.scriptSha").contains("sha-bbb"), tapWrite)
        assert(ws.lineageWrites.size == 2, ws.calls.mkString("\n"))
        assert(s3.lineageHash != s2.lineageHash, s"$s2\n$s3")
    }

    test("tableCreated forces republish with an equal hash") {
        val ws = new FakeWorkspace()
        val s1 = publish(ws, previous = null)
        ws.reset()
        val s2 = publish(ws, previous = s1, tableCreated = true, runId = "run-0002")
        assert(ws.metaWrites.map(_.metaName).toSet == Set(TAP_NODE, PIPE_NODE), ws.calls.mkString("\n"))
        assert(ws.lineageWrites.size == 2, ws.calls.mkString("\n"))
        val tableWrite = ws.lineageWrites.find(c => relTarget(c) == "table:" + QUALIFIED)
        assert(tableWrite.exists(c => columns(c.body).nonEmpty), ws.calls.mkString("\n"))
        tableWrite.filter(_.method == "PATCH").foreach(c => assert(c.mask.contains("columns"), c.query))
        assert(s2.lineageHash == s1.lineageHash, s"$s1\n$s2")
    }

    test("existing object (GET 200) is PATCHed, missing (404) is POSTed") {
        val ws = new FakeWorkspace(existingObjects = Set(PIPE_NODE))
        val s = publish(ws, previous = null)
        assert(s.lastError == null, s"$s")
        val pipeWrites = ws.metaWrites.filter(_.metaName == PIPE_NODE)
        assert(pipeWrites.nonEmpty && pipeWrites.forall(_.method == "PATCH"), ws.calls.mkString("\n"))
        assert(pipeWrites.head.path == META + "/" + PIPE_NODE, pipeWrites.head)
        assert(pipeWrites.head.mask.nonEmpty, s"PATCH needs an update_mask: ${pipeWrites.head.query}")
        val tapWrites = ws.metaWrites.filter(_.metaName == TAP_NODE)
        assert(tapWrites.nonEmpty && tapWrites.forall(_.method == "POST"), ws.calls.mkString("\n"))
        assert(str(tapWrites.head.body, "system_type") == "OTHER" && str(tapWrites.head.body, "entity_type") == "tap", tapWrites.head)

        // Upload runs publish the upload object instead of a tap object.
        val ws2 = new FakeWorkspace()
        publish(ws2, previous = null, md = uploadMd, tap = null)
        assert(ws2.metaWrites.filter(_.method == "POST").map(_.metaName).toSet == Set("datris-upload-" + PIPELINE, PIPE_NODE), ws2.calls.mkString("\n"))
    }

    test("relationship ids from the list call are reused for PATCH") {
        val ws = new FakeWorkspace(
            existingObjects = Set(TAP_NODE, PIPE_NODE),
            existingRelationships = List(
                relWithId("rel-src-1", extNode(TAP_NODE), extNode(PIPE_NODE)),
                relWithId("rel-tbl-1", extNode(PIPE_NODE), tableNode(QUALIFIED))
            )
        )
        val s = publish(ws, previous = null)
        assert(ws.lineagePosts.isEmpty, s"existing relationships must not be re-created:\n${ws.calls.mkString("\n")}")
        assert(ws.lineagePatches.map(c => str(c.body, "id")).toSet == Set("rel-src-1", "rel-tbl-1"), ws.calls.mkString("\n"))
        val ids = Option(s.lineageRelationshipIds).map(_.asScala.toMap).getOrElse(Map.empty)
        assert(ids == Map("source" -> "rel-src-1", "table" -> "rel-tbl-1"), ids)
    }

    test("a failed call sets lastError prefixed uc-lineage: and leaves lineageHash null") {
        val denied = """{"error_code":"PERMISSION_DENIED","message":"User does not have CREATE EXTERNAL METADATA on Metastore"}"""
        val ws = new FakeWorkspace(failWhen = c => if (c.method == "POST" && c.path == META) Some(403 -> denied) else None)
        val status = new RecordingStatusUtil
        val s = publish(ws, previous = null, status = status) // must not throw
        assert(s != null, "publish returns a state even on failure")
        assert(s.lastError != null && s.lastError.startsWith("uc-lineage:"), s"$s")
        assert(s.lastError.contains("CREATE EXTERNAL METADATA"), s"$s")
        assert(s.lineageHash == null, s"a failed publish must not record the hash: $s")
        assert(status.messages.forall(_._1 != "error"), s"a lineage failure is never an error line: ${status.messages}")
    }

    // Review follow-ups.

    test("missing column lineage skips the publish with no REST calls and keeps the published mappings") {
        val ws = new FakeWorkspace()
        val s1 = publish(ws, previous = null)
        ws.reset()
        val status = new RecordingStatusUtil
        val s2 = P.publish(
            client = ws.client,
            config = config,
            md = tapMd,
            columnLineage = null,
            previous = s1,
            tableCreated = false,
            runId = "run-0002",
            recordCount = 1,
            dqStatus = "pass",
            statusUtil = status,
            tap = scriptTap
        )
        assert(ws.calls.isEmpty, s"no REST calls without column lineage:\n${ws.calls.mkString("\n")}")
        assert(s2.lastError != null && s2.lastError.startsWith("uc-lineage:") && s2.lastError.contains("column lineage"), s"$s2")
        assert(s2.lineageHash == null, s"$s2")
        assert(s2.lastLineageAt == s1.lastLineageAt, s"$s2")
        assert(s2.lineageRelationshipIds != null && s2.lineageRelationshipIds.asScala == s1.lineageRelationshipIds.asScala, s"$s2")
        assert(status.messages.exists(m => m._1 == "warning" && m._3.startsWith("uc-lineage:")), status.messages)
    }

    test("a deleted relationship (cached-id PATCH 404) falls through to list and re-create") {
        val ws0 = new FakeWorkspace()
        val gone = new java.util.HashMap[String, String]()
        gone.put("source", "rel-gone-1")
        gone.put("table", "rel-gone-2")
        val s1 = publish(ws0, previous = null).copy(lineageRelationshipIds = gone)
        val staleIds = Set("rel-gone-1", "rel-gone-2")
        // Same workspace state minus the relationships (an admin deleted them).
        val ws = new FakeWorkspace(
            existingObjects = Set(TAP_NODE, PIPE_NODE),
            failWhen = c =>
                if (c.method == "PATCH" && c.path == LINEAGE && staleIds.contains(str(c.body, "id")))
                    Some(404 -> """{"error_code":"RESOURCE_DOES_NOT_EXIST","message":"relationship not found"}""")
                else None
        )
        val status = new RecordingStatusUtil
        val s2 = publish(ws, previous = s1, runId = "run-0002", status = status)
        assert(s2.lastError == null, s"$s2")
        assert(ws.calls.exists(c => c.method == "GET" && c.path == LINEAGE), ws.calls.mkString("\n"))
        assert(ws.lineagePosts.size == 2, ws.calls.mkString("\n"))
        val ids = s2.lineageRelationshipIds.asScala.toMap
        assert(ids.keySet == Set("source", "table") && ids.values.toSet.intersect(staleIds).isEmpty, ids)
        assert(s2.lineageHash == s1.lineageHash && s2.lastLineageAt != null, s"$s2")
        assert(!status.messages.exists(_._1 == "warning"), status.messages)
    }
}
