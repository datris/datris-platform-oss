package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.model._
import com.google.gson.{JsonArray, JsonElement, JsonObject}
import org.slf4j.{Logger, LoggerFactory}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import scala.collection.JavaConverters._
import scala.util.Try
import scala.util.control.NonFatal

/** Unity Catalog lineage publish for a Databricks destination (opt-in via
  * `unityCatalog: {"enabled": true}`, knob `lineage`, default on). After a
  * successful load it registers three External Metadata objects and two
  * External Lineage relationships over the workspace REST API:
  *
  * {{{
  *   datris-tap-<tap> | datris-upload-<pipeline>  ──source──▶  datris-pipeline-<pipeline>  ──table──▶  <catalog.schema.table>
  * }}}
  *
  * The pipeline → table relationship carries the column mappings. Both
  * relationships carry run properties (`datris.lastRunId`, ...), updated every
  * run. The definitional payload (objects, columns) is hashed; while the hash
  * and the cached relationship ids hold, a run costs exactly two PATCHes of
  * relationship properties. A failure is a warning on the run and an audit
  * entry, never a failed load. */
object UnityCatalogLineagePublisher {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    val MetaPath = "/api/2.0/lineage-tracking/external-metadata"
    val LineagePath = "/api/2.0/lineage-tracking/external-lineage"
    val ErrorPrefix = "uc-lineage:"
    val SourceKey = "source"
    val TableKey = "table"

    private val ObjectMask = "system_type,entity_type,description,url,properties"
    private val FullRelMask = "columns,properties"
    private val PropsRelMask = "properties"
    private val MaxEvidenceChars = 4000
    private val MaxListPages = 20

    // --- pure builders -------------------------------------------------------

    /** UC securable-safe name: dots, slashes, whitespace and backticks → `_`. */
    def ucName(raw: String): String = Option(raw).getOrElse("").replaceAll("[./\\s`]", "_")

    def tapNode(tap: String): String = ucName("datris-tap-" + tap)
    def uploadNode(pipeline: String): String = ucName("datris-upload-" + pipeline)
    def pipelineNode(pipeline: String): String = ucName("datris-pipeline-" + pipeline)

    private def put(o: JsonObject, k: String, v: String): Unit = if (v != null) o.addProperty(k, v)

    private def metadata(name: String, entityType: String, description: String, url: String, props: Seq[(String, String)]): JsonObject = {
        val o = new JsonObject()
        o.addProperty("name", name)
        o.addProperty("system_type", "OTHER")
        o.addProperty("entity_type", entityType)
        put(o, "description", Option(description).filter(_.trim.nonEmpty).orNull)
        put(o, "url", url)
        val p = new JsonObject()
        props.foreach { case (k, v) => put(p, k, v) }
        o.add("properties", p)
        o
    }

    def tapObject(tap: TapConfig, scriptSha: String): JsonObject = {
        val source = Try(TapSourceResolver.resolve(tap)).toOption.orNull
        metadata(
            tapNode(tap.name),
            "tap",
            tap.description,
            if (tap.isHttp) tap.endpointUrl else null,
            Seq("datris.tap" -> tap.name, "datris.scriptSha" -> scriptSha, "datris.source" -> source)
        )
    }

    def uploadObject(pipeline: String): JsonObject =
        metadata(
            uploadNode(pipeline),
            "upload",
            "Files uploaded to Datris pipeline " + pipeline,
            null,
            Seq("datris.pipeline" -> pipeline)
        )

    def pipelineObject(config: PipelineConfig): JsonObject =
        metadata(
            pipelineNode(config.name),
            "pipeline",
            "Datris pipeline " + config.name,
            null,
            Seq(
                "datris.pipeline" -> config.name,
                "datris.catalog" -> Option(config.catalog).filter(_.nonEmpty).orNull,
                "datris.configVersion" -> config.version.toString,
                "datris.lineagePath" -> ("/api/v1/lineage/pipeline/" + config.name)
            )
        )

    private def extRef(name: String): JsonObject = {
        val inner = new JsonObject(); inner.addProperty("name", name)
        val o = new JsonObject(); o.add("external_metadata", inner); o
    }

    private def tableRef(qualified: String): JsonObject = {
        val inner = new JsonObject(); inner.addProperty("name", qualified)
        val o = new JsonObject(); o.add("table", inner); o
    }

    /** Tap/upload → pipeline relationship (no column mappings). */
    def sourceEdge(sourceNode: String, pipeNode: String): JsonObject = {
        val o = new JsonObject()
        o.add("source", extRef(sourceNode))
        o.add("target", extRef(pipeNode))
        o
    }

    /** Pipeline → table relationship. One `{source, target}` mapping per `from`
      * field of every edge with a target; edges with no `from` (stamped system
      * columns) and drops (no target) map nothing. Inferred edges are included
      * and flag the whole relationship (properties are per relationship):
      * `datris.confidence=inferred` plus their evidence. `qualified` is the
      * unquoted `catalog.schema.table`. */
    def tableEdge(edges: List[ColumnEdge], qualified: String, pipeNode: String = null): JsonObject = {
        val o = new JsonObject()
        if (pipeNode != null) o.add("source", extRef(pipeNode))
        o.add("target", tableRef(qualified))
        val usable = Option(edges).getOrElse(Nil).filter(e => e != null && e.to != null && e.to.nonEmpty && e.from != null)
        val pairs = usable.flatMap(e => e.from.asScala.filter(f => f != null && f.nonEmpty).map(_ -> e.to)).distinct
        val cols = new JsonArray()
        pairs.foreach { case (s, t) =>
            val c = new JsonObject(); c.addProperty("source", s); c.addProperty("target", t); cols.add(c)
        }
        o.add("columns", cols)
        val inferred = usable.filter(e => "inferred".equalsIgnoreCase(e.confidence))
        if (inferred.nonEmpty) {
            val p = new JsonObject()
            p.addProperty("datris.confidence", "inferred")
            val evidence = inferred.flatMap(e => Option(e.evidence)).map(_.trim).filter(_.nonEmpty).distinct.mkString("; ")
            p.addProperty("datris.evidence", evidence.take(MaxEvidenceChars))
            o.add("properties", p)
        }
        o
    }

    /** Run-level relationship properties (a string map). */
    def runProperties(runId: String, recordCount: Int, status: String, runAt: String): JsonObject = {
        val p = new JsonObject()
        put(p, "datris.lastRunId", runId)
        p.addProperty("datris.recordCount", recordCount.toString)
        put(p, "datris.status", status)
        put(p, "datris.lastRunAt", runAt)
        p
    }

    /** Hash of the definitional payload; run properties are excluded so a
      * steady-state run hashes the same. */
    def lineageHash(configVersion: Int, columnLineageVersion: Int, scriptSha: String, edges: List[ColumnEdge]): String = {
        val edgeLines = Option(edges).getOrElse(Nil).filter(_ != null).map { e =>
            Option(e.from).map(_.asScala.mkString(",")).getOrElse("") + ">" + e.to + "|" + e.op + "|" + e.confidence + "|" + e.evidence
        }.sorted
        val text = (Seq(configVersion.toString, columnLineageVersion.toString, String.valueOf(scriptSha)) ++ edgeLines).mkString("\n")
        MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)).map(b => f"$b%02x").mkString
    }

    /** Unquoted `catalog.schema.table` as Unity Catalog stores it. */
    def qualifiedName(db: Database): String =
        Seq(db.dbName, db.schema, db.table).map(n => DatabricksConnectionUtil.effectiveName(Option(n).getOrElse(""))).mkString(".")

    private def withProps(rel: JsonObject, run: JsonObject): JsonObject = {
        val o = rel.deepCopy()
        val p = if (o.has("properties") && o.get("properties").isJsonObject) o.getAsJsonObject("properties") else new JsonObject()
        run.entrySet().asScala.foreach(e => p.add(e.getKey, e.getValue))
        o.add("properties", p)
        o
    }

    // --- REST orchestration --------------------------------------------------

    private def enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private def str(o: JsonObject, k: String): String =
        if (o == null || !o.has(k) || o.get(k).isJsonNull || !o.get(k).isJsonPrimitive) null else o.get(k).getAsString
    private def obj(o: JsonObject, k: String): JsonObject =
        if (o == null || !o.has(k) || !o.get(k).isJsonObject) null else o.getAsJsonObject(k)

    /** GET the object by name; 200 → PATCH it, 404 → POST it. */
    private def upsertObject(client: DatabricksRestClient, body: JsonObject): Unit = {
        val name = str(body, "name")
        val exists =
            try { client.get(MetaPath + "/" + enc(name)); true }
            catch { case e: DatabricksHttpException if e.status == 404 => false }
        if (exists) client.patch(MetaPath + "/" + enc(name) + "?update_mask=" + enc(ObjectMask), body)
        else client.post(MetaPath, body)
    }

    /** Relationship infos touching `pipeNode` in `direction`, across pages. */
    private def listRelationships(client: DatabricksRestClient, pipeNode: String, direction: String): List[JsonObject] = {
        val out = List.newBuilder[JsonObject]
        var pageToken: String = null
        var pages = 0
        do {
            val q = "?object_info.external_metadata.name=" + enc(pipeNode) + "&lineage_direction=" + direction +
                Option(pageToken).map(t => "&page_token=" + enc(t)).getOrElse("")
            val res = client.get(LineagePath + q)
            val arr = if (res.has("external_lineage_relationships") && res.get("external_lineage_relationships").isJsonArray)
                res.getAsJsonArray("external_lineage_relationships").asScala.toList
            else Nil
            arr.foreach { (el: JsonElement) =>
                if (el.isJsonObject) {
                    val w = el.getAsJsonObject
                    out += Option(obj(w, "external_lineage_info")).getOrElse(w)
                }
            }
            pageToken = Option(str(res, "next_page_token")).filter(_.nonEmpty).orNull
            pages += 1
        } while (pageToken != null && pages < MaxListPages)
        out.result()
    }

    private def patchRelationship(client: DatabricksRestClient, id: String, body: JsonObject, mask: String): Unit = {
        val b = body.deepCopy()
        b.addProperty("id", id)
        client.patch(LineagePath + "?update_mask=" + enc(mask), b)
    }

    /** List-then-create-or-PATCH one relationship; returns its id. */
    private def upsertRelationship(
        client: DatabricksRestClient,
        pipeNode: String,
        direction: String,
        matches: JsonObject => Boolean,
        body: JsonObject
    ): String = {
        val existing = listRelationships(client, pipeNode, direction).find(matches).flatMap(r => Option(str(r, "id")))
        existing match {
            case Some(id) =>
                patchRelationship(client, id, body, FullRelMask)
                id
            case None =>
                str(client.post(LineagePath, body), "id")
        }
    }

    private def nodeName(ref: JsonObject, kind: String): String = str(obj(ref, kind), "name")

    private def otherErrors(lastError: String): String =
        Option(lastError).map(_.split("\n").filterNot(_.startsWith(ErrorPrefix)).mkString("\n")).filter(_.nonEmpty).orNull

    /** Publish (or refresh) the lineage for one run; returns the new state doc
      * (`previous` with the lineage fields updated). Never throws: a failed
      * call is one `uc-lineage:` warning and a `uc-lineage:` line at the head
      * of `lastError`, with `lineageHash` left null so the next run retries. */
    def publish(
        client: DatabricksRestClient,
        config: PipelineConfig,
        md: PipelineMetadata,
        columnLineage: ColumnLineageService.Result,
        previous: UnityCatalogSyncState,
        tableCreated: Boolean,
        runId: String,
        recordCount: Int,
        dqStatus: String,
        statusUtil: StatusUtil,
        tap: TapConfig
    ): UnityCatalogSyncState = {
        val pipeline = config.name
        val base =
            if (previous != null) previous
            else UnityCatalogSyncState(
                pipeline = pipeline,
                lastSyncAt = null,
                lastRunId = runId,
                commentsHash = null,
                tagsHash = null,
                propertiesHash = null,
                lastError = null
            )
        val priorErrors = otherErrors(base.lastError)
        val cachedIds: Map[String, String] = Option(base.lineageRelationshipIds).map(_.asScala.toMap).getOrElse(Map.empty)
        val ids = scala.collection.mutable.Map[String, String]() ++= cachedIds

        try {
            val scriptSha = if (md != null) md.tapScriptSha else null
            val tapFed = md != null && md.tapName != null
            val sourceObj =
                if (tapFed) tapObject(
                    if (tap != null) tap else TapConfig(name = md.tapName, description = null, targetPipeline = pipeline, source = md.tapSource),
                    scriptSha
                )
                else uploadObject(pipeline)
            val pipeObj = pipelineObject(config)
            val sourceNode = str(sourceObj, "name")
            val pipeNode = str(pipeObj, "name")
            val qualified = qualifiedName(config.destination.database)
            val edges = if (columnLineage != null) Option(columnLineage.edges).getOrElse(Nil) else Nil
            val clVersion = if (columnLineage != null) columnLineage.version else 0
            // The source node and target table are part of the definition too.
            val hash = lineageHash(config.version, clVersion, sourceNode + "|" + qualified + "|" + scriptSha, edges)
            val runAt = Instant.now().toString
            val run = runProperties(runId, recordCount, dqStatus, runAt)
            val srcRel = withProps(sourceEdge(sourceNode, pipeNode), run)
            val tblRel = withProps(tableEdge(edges, qualified, pipeNode), run)

            val steady = previous != null && !tableCreated && previous.lastLineageAt != null &&
                hash == previous.lineageHash && cachedIds.contains(SourceKey) && cachedIds.contains(TableKey)

            if (steady) {
                patchRelationship(client, cachedIds(SourceKey), srcRel, PropsRelMask)
                patchRelationship(client, cachedIds(TableKey), tblRel, PropsRelMask)
                statusUtil.info("processing", "uc-lineage: lineage unchanged; run properties updated")
            } else {
                upsertObject(client, sourceObj)
                upsertObject(client, pipeObj)
                val srcId = upsertRelationship(
                    client,
                    pipeNode,
                    "UPSTREAM",
                    r => nodeName(obj(r, "source"), "external_metadata") == sourceNode && nodeName(obj(r, "target"), "external_metadata") == pipeNode,
                    srcRel
                )
                if (srcId != null) ids(SourceKey) = srcId else ids.remove(SourceKey)
                val tblId = upsertRelationship(
                    client,
                    pipeNode,
                    "DOWNSTREAM",
                    r => Option(nodeName(obj(r, "target"), "table")).exists(_.equalsIgnoreCase(qualified)),
                    tblRel
                )
                if (tblId != null) ids(TableKey) = tblId else ids.remove(TableKey)
                val n = tblRel.getAsJsonArray("columns").size()
                statusUtil.info(
                    "processing",
                    s"uc-lineage: published $sourceNode → $pipeNode → $qualified ($n column mapping${if (n == 1) "" else "s"})"
                )
            }
            base.copy(
                lineageHash = hash,
                lastLineageAt = runAt,
                lineageRelationshipIds = if (ids.isEmpty) null else new java.util.HashMap[String, String](ids.asJava),
                lastError = priorErrors
            )
        } catch {
            case NonFatal(e) =>
                val msg = ErrorPrefix + " " + Option(e.getMessage).getOrElse(e.getClass.getSimpleName).replaceAll("[\\r\\n]+", " ")
                statusUtil.warn("processing", "uc-lineage: Unity Catalog lineage publish failed: " + msg.stripPrefix(ErrorPrefix).trim)
                logger.warn("uc-lineage failed for pipeline " + pipeline + ": " + msg)
                base.copy(
                    lineageHash = null,
                    lineageRelationshipIds = if (ids.isEmpty) null else new java.util.HashMap[String, String](ids.asJava),
                    lastError = (Seq(msg) ++ Option(priorErrors).toSeq).mkString("\n")
                )
        }
    }

    /** Loader hook: runs after the metadata sync on a successful Databricks
      * load. No-op unless the pipeline opted in with `lineage` on; one info
      * line when the kill switch is off. Never throws. */
    def sync(jobContext: JobContext, tableCreated: Boolean = false): Unit = {
        val config = jobContext.config
        if (config == null || config.unityCatalog == null || !config.unityCatalog.enabled || !config.unityCatalog.lineageOn) return
        val statusUtil = jobContext.statusUtil
        if (!UnityCatalogMetadataSync.switchedOn) {
            statusUtil.info("processing", "uc-lineage: Unity Catalog sync is switched off (DATRIS_UNITY_CATALOG_SYNC=false); skipped")
            return
        }
        val stored: UnityCatalogSyncState =
            try UnityCatalogSyncIO.read(config.name)
            catch {
                case NonFatal(e) =>
                    logger.warn("uc-lineage state read failed for " + config.name + ": " + e.getMessage)
                    null
            }
        try {
            val md = jobContext.metadata
            val tapConfig =
                if (md != null && md.tapName != null)
                    try TapConfigIO.read(DatrisEnvironment.current.tapTableName, md.tapName)
                    catch { case NonFatal(e) => logger.warn("uc-lineage tap read failed for " + md.tapName + ": " + e.getMessage); null }
                else null
            val lineage =
                try ColumnLineageService.forPipeline(config.name, None, runInference = false)
                catch { case NonFatal(e) => logger.warn("uc-lineage column lineage failed for " + config.name + ": " + e.getMessage); null }
            val client = new DatabricksRestClient(CredentialResolver.resolveDatabricks(config.destination.database.credentialsSecret))
            val state = publish(
                client = client,
                config = config,
                md = md,
                columnLineage = lineage,
                previous = stored,
                tableCreated = tableCreated,
                runId = jobContext.pipelineToken,
                recordCount = statusUtil.recordCount,
                dqStatus = if (statusUtil.hasWarning) "warn" else "pass",
                statusUtil = statusUtil,
                tap = tapConfig
            )
            try UnityCatalogSyncIO.write(state)
            catch { case NonFatal(e) => logger.warn("uc-lineage state write failed for " + config.name + ": " + e.getMessage) }
            if (state.lastError != null && state.lastError.startsWith(ErrorPrefix))
                AuditLog.system(
                    category = "unity-catalog",
                    action = "lineage",
                    resourceType = "pipeline",
                    resourceName = config.name,
                    outcome = "failure",
                    errorMessage = state.lastError.split("\n").head
                )
        } catch {
            case NonFatal(e) =>
                val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
                statusUtil.warn("processing", "uc-lineage: Unity Catalog lineage publish failed: " + msg)
                logger.warn("uc-lineage failed for " + config.name, e)
                val line = ErrorPrefix + " " + msg.replaceAll("[\\r\\n]+", " ")
                if (stored != null)
                    Try(UnityCatalogSyncIO.write(stored.copy(
                        lineageHash = null,
                        lastError = (Seq(line) ++ Option(otherErrors(stored.lastError)).toSeq).mkString("\n")
                    )))
                Try(
                    AuditLog.system(
                        category = "unity-catalog",
                        action = "lineage",
                        resourceType = "pipeline",
                        resourceName = config.name,
                        outcome = "failure",
                        errorMessage = line
                    )
                )
        }
    }
}
