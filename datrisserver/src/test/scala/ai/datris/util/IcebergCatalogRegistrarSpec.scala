package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.{JsonObject, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

/** Story: Unity Catalog 4: Iceberg register spike (objectstore → UC)
  * (plans/stories/unity-catalog-4-iceberg-register.md), Acceptance bullet 1.
  *
  * Seams this spec pins (names from the story's Files section):
  *
  * {{{
  *   case class ResolvedDatabricksCredentials(host: String, clientId: Option[String],
  *       clientSecret: Option[String], token: Option[String], extra: Map[String, String])
  *
  *   object IcebergCatalogRegistrar {
  *     val ErrorPrefix = "uc-register:"
  *     def tableName(pipeline: String): String          // ucName(pipeline).toLowerCase
  *     def registerPath(fields: Map[String, String], catalog: String, schema: String): String
  *     def register(client: DatabricksRestClient, fields: Map[String, String], catalog: String,
  *                  schema: String, table: String, metadataLocation: String, tableRoot: String,
  *                  previous: UnityCatalogSyncState /* null = never synced */,
  *                  statusUtil: StatusUtil): UnityCatalogSyncState
  *     def sync(jobContext: JobContext, result: IcebergWriter.WriteResult): Unit   // never throws
  *   }
  *   UnityCatalogSyncState(..., registeredMetadataLocation: String = null, lastRegisterAt: String = null)
  *   IcebergWriter.WriteResult(snapshotId, addedRecords, deletedRecords, totalRecords, metadataLocation: String = null)
  *   UnityCatalogSync(enabled, ..., credentialsSecret: String, catalog: String, schema: String, register: java.lang.Boolean)
  * }}}
  *
  * `register` is called with named arguments only. The REST client here is
  * built with the DEFAULT (lineage-worded) translate on purpose: the
  * register-specific messages (403 → external data access / EXTERNAL USE
  * SCHEMA; external location naming the table root; 404 naming the URL and
  * the icebergRestPath/icebergRestPrefix fields) need the call's context, so
  * `register` must produce them whatever translate the client carries. The
  * failure-message tests accept the text from a status warning, the returned
  * state's `lastError`, or (if `register` lets it escape to `sync`) the
  * exception message.
  *
  * Wire shapes: Iceberg REST spec `RegisterTableRequest {name, metadata-location}`
  * and `LoadTableResult {metadata-location, metadata}`; the fixture's
  * already-exists answer is 409 `{"error":{"type":"AlreadyExistsException",...}}`.
  *
  * StatusUtil only accepts begin/processing/end; every registrar line is
  * under `processing`.
  */
class IcebergCatalogRegistrarSpec extends AnyFunSuite {

    private val R = IcebergCatalogRegistrar

    private val DBX_HOST = "dbc-a1b2c3d4-e5f6.cloud.databricks.com"
    private val FIXTURE = "http://iceberg-rest:8181"
    private val CAT = "unity_cat"
    private val SCH = "sales"
    private val TABLE = "orders_daily"
    private val ROOT = "s3a://datris-lake/orders_daily"
    private val OURS = ROOT + "/metadata/00002-7f1c.metadata.json"
    private val OLDER = ROOT + "/metadata/00001-3a9e.metadata.json"

    // --- fake Iceberg REST catalog ---------------------------------------------

    private case class Call(method: String, url: String, headers: Map[String, String], body: String) {
        def header(name: String): Option[String] = headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }
        def json: JsonObject = JsonParser.parseString(body).getAsJsonObject
    }

    /** POST .../register answers `registerResp` (or throws `registerThrows`);
      * GET .../tables/<t> answers `loadResp`; anything else is a 599 so a
      * stray call is loud. */
    private class FakeCatalog(
        registerResp: (Int, String) = (200, "{}"),
        loadResp: (Int, String) = (404, """{"error":{"message":"not found","type":"NoSuchTableException","code":404}}"""),
        registerThrows: Throwable = null
    ) {
        val calls = new ListBuffer[Call]()
        val transport: HttpTransport = (method: String, url: String, headers: Map[String, String], body: String) => {
            calls += Call(method.toUpperCase, url, headers, body)
            if (method.equalsIgnoreCase("POST") && url.endsWith("/register")) {
                if (registerThrows != null) throw registerThrows
                registerResp
            } else if (method.equalsIgnoreCase("GET") && url.contains("/tables/")) loadResp
            else (599, """{"error":"unexpected call"}""")
        }
        def registerCalls: List[Call] = calls.filter(c => c.method == "POST" && c.url.endsWith("/register")).toList
    }

    private class RecordingStatusUtil extends StatusUtil {
        val messages = new ListBuffer[(String, String, String)]()
        override def overrideProcessName(processName: String): Unit = ()
        override def info(state: String, description: String): Unit = messages += (("info", state, description))
        override def warn(state: String, description: String): Unit = messages += (("warning", state, description))
        override def error(state: String, description: String): Unit = messages += (("error", state, description))
        def warnings: List[String] = messages.filter(_._1 == "warning").map(_._3).toList
        def infos: List[String] = messages.filter(_._1 == "info").map(_._3).toList
    }

    private val dbxFields = Map("host" -> DBX_HOST, "token" -> "dapi-x")
    private val fixtureFields = Map("host" -> FIXTURE, "icebergRestPath" -> "/", "icebergRestPrefix" -> "")

    private def dbxClient(fake: FakeCatalog): DatabricksRestClient =
        new DatabricksRestClient(
            ResolvedDatabricksCredentials(host = DBX_HOST, clientId = None, clientSecret = None, token = Some("dapi-x"), extra = dbxFields),
            fake.transport,
            () => 1790000000000L
        )

    private def fixtureClient(fake: FakeCatalog): DatabricksRestClient =
        new DatabricksRestClient(
            ResolvedDatabricksCredentials(host = FIXTURE, clientId = None, clientSecret = None, token = None, extra = fixtureFields),
            fake.transport,
            () => 1790000000000L
        )

    private def register(
        fake: FakeCatalog,
        status: RecordingStatusUtil = new RecordingStatusUtil,
        previous: UnityCatalogSyncState = null,
        fields: Map[String, String] = dbxFields,
        client: FakeCatalog => DatabricksRestClient = dbxClient,
        catalog: String = CAT,
        schema: String = SCH,
        ours: String = OURS
    ): UnityCatalogSyncState =
        R.register(
            client = client(fake),
            fields = fields,
            catalog = catalog,
            schema = schema,
            table = TABLE,
            metadataLocation = ours,
            tableRoot = ROOT,
            previous = previous,
            statusUtil = status
        )

    /** Every piece of text a failure could surface through. */
    private def failureText(fake: FakeCatalog, fields: Map[String, String] = dbxFields, client: FakeCatalog => DatabricksRestClient = dbxClient): String = {
        val status = new RecordingStatusUtil
        val out =
            try {
                val st = register(fake, status, fields = fields, client = client)
                Option(st).flatMap(s => Option(s.lastError)).getOrElse("")
            } catch { case NonFatal(e) => "EXCEPTION: " + e.getMessage }
        (status.messages.map(_._3) :+ out).mkString("\n")
    }

    private def norm(loc: String): String = Option(loc).map(_.replaceFirst("^s3[an]://", "s3://")).orNull

    // --- path shapes -----------------------------------------------------------

    test(
        "Databricks-shaped secret POSTs {name, metadata-location} to https://<host>/api/2.1/unity-catalog/iceberg-rest/v1/catalogs/<cat>/namespaces/<sch>/register with a Bearer header"
    ) {
        val fake = new FakeCatalog()
        register(fake)
        assert(fake.registerCalls.size == 1, fake.calls.mkString("\n"))
        val c = fake.registerCalls.head
        assert(c.url == s"https://$DBX_HOST/api/2.1/unity-catalog/iceberg-rest/v1/catalogs/$CAT/namespaces/$SCH/register", c.url)
        assert(c.header("Authorization").contains("Bearer dapi-x"), c.headers)
        assert(c.header("Content-Type").exists(_.toLowerCase.startsWith("application/json")), c.headers)
        val body = c.json
        assert(body.keySet().asScala == Set("name", "metadata-location"), s"RegisterTableRequest is exactly {name, metadata-location}: $body")
        assert(body.get("name").getAsString == TABLE, body)
        assert(norm(body.get("metadata-location").getAsString) == norm(OURS), body)
    }

    test(
        "secret with icebergRestPath=/ and empty icebergRestPrefix on http://host:8181 POSTs to http://host:8181/v1/namespaces/<sch>/register with no Authorization header"
    ) {
        val fake = new FakeCatalog()
        register(fake, fields = fixtureFields, client = fixtureClient, catalog = "unity", schema = "default")
        assert(fake.registerCalls.size == 1, fake.calls.mkString("\n"))
        val c = fake.registerCalls.head
        assert(c.url == s"$FIXTURE/v1/namespaces/default/register", c.url)
        assert(c.header("Authorization").isEmpty, s"the fixture has no auth; no Authorization header expected: ${c.headers}")
        assert(c.json.get("name").getAsString == TABLE, c.body)
    }

    test("literal - prefix means no prefix; absent prefix means catalogs/<catalog>") {
        val dbxBase = "/api/2.1/unity-catalog/iceberg-rest"
        assert(R.registerPath(Map("host" -> DBX_HOST), CAT, SCH) == s"$dbxBase/v1/catalogs/$CAT/namespaces/$SCH/register")
        assert(R.registerPath(Map("host" -> DBX_HOST, "icebergRestPrefix" -> "-"), CAT, SCH) == s"$dbxBase/v1/namespaces/$SCH/register")
        assert(R.registerPath(Map("host" -> DBX_HOST, "icebergRestPrefix" -> ""), CAT, SCH) == s"$dbxBase/v1/namespaces/$SCH/register")
        assert(R.registerPath(Map("icebergRestPath" -> "/", "icebergRestPrefix" -> "-"), CAT, "default") == "/v1/namespaces/default/register")
        assert(R.registerPath(Map("icebergRestPath" -> "/"), CAT, SCH) == s"/v1/catalogs/$CAT/namespaces/$SCH/register")
        assert(R.registerPath(Map("icebergRestPath" -> "/iceberg", "icebergRestPrefix" -> "wh1"), CAT, SCH) == s"/iceberg/v1/wh1/namespaces/$SCH/register")
    }

    test("table name is the lowercased ucName of the pipeline") {
        assert(R.tableName("Orders.Daily v2") == "orders_daily_v2")
        assert(R.tableName("orders/EU") == "orders_eu")
        assert(R.tableName("orders_daily") == "orders_daily")
        assert(R.tableName("Orders.Daily v2") == UnityCatalogLineagePublisher.ucName("Orders.Daily v2").toLowerCase)
    }

    // --- outcomes --------------------------------------------------------------

    test("2xx → registeredMetadataLocation and lastRegisterAt set, info line") {
        val fake = new FakeCatalog(registerResp = (200, s"""{"metadata-location":"$OURS","metadata":{}}"""))
        val status = new RecordingStatusUtil
        val before = Instant.now().minusSeconds(5)
        val st = register(fake, status)
        assert(st != null)
        assert(st.registeredMetadataLocation == OURS, s"$st")
        assert(st.lastRegisterAt != null && !Instant.parse(st.lastRegisterAt).isBefore(before), s"lastRegisterAt must be an ISO-8601 instant from now: $st")
        assert(st.lastError == null || !st.lastError.contains(R.ErrorPrefix), s"$st")
        assert(status.warnings.isEmpty, status.messages.mkString("\n"))
        val line = status.infos.find(_.startsWith(R.ErrorPrefix))
        assert(line.exists(l => l.contains("registered") && l.contains(s"$CAT.$SCH.$TABLE") && l.contains(OURS)), status.messages.mkString("\n"))
        assert(status.messages.forall(_._2 == "processing"), status.messages.mkString("\n"))
        assert(fake.calls.size == 1, fake.calls.mkString("\n"))
    }

    test("already-exists with UC at our current file (s3 vs s3a scheme) → no warning") {
        Seq(
            (409, """{"error":{"message":"Table already exists: sales.orders_daily","type":"AlreadyExistsException","code":409}}"""),
            (400, """{"error_code":"TABLE_ALREADY_EXISTS","message":"Table 'unity_cat.sales.orders_daily' already exists"}""")
        ).foreach { resp =>
            val fake = new FakeCatalog(
                registerResp = resp,
                loadResp = (200, s"""{"metadata-location":"${norm(OURS)}","metadata":{"format-version":2}}""")
            )
            val status = new RecordingStatusUtil
            val st = register(fake, status)
            assert(status.warnings.isEmpty, s"$resp: ${status.messages.mkString("\n")}")
            assert(status.messages.forall(_._1 != "error"), status.messages.mkString("\n"))
            assert(st == null || st.lastError == null || !st.lastError.contains(R.ErrorPrefix), s"$resp: $st")
            assert(fake.calls.map(_.method).toList == List("POST", "GET"), fake.calls.mkString("\n"))
            assert(
                fake.calls(1).url == s"https://$DBX_HOST/api/2.1/unity-catalog/iceberg-rest/v1/catalogs/$CAT/namespaces/$SCH/tables/$TABLE",
                fake.calls(1).url
            )
        }
    }

    private val alreadyExists = (409, """{"error":{"message":"Table already exists","type":"AlreadyExistsException","code":409}}""")

    test(
        "already-exists with UC at an older file under our metadata/ → warning saying a later release keeps it current, registeredMetadataLocation updated, no further POST/DELETE"
    ) {
        // UC reports the s3:// spelling of our s3a:// root: still ours, just older.
        val fake = new FakeCatalog(registerResp = alreadyExists, loadResp = (200, s"""{"metadata-location":"${norm(OLDER)}","metadata":{}}"""))
        val status = new RecordingStatusUtil
        val st = register(fake, status)
        assert(status.warnings.size == 1, status.messages.mkString("\n"))
        val w = status.warnings.head
        assert(w.startsWith(R.ErrorPrefix), w)
        assert(w.contains("Unity Catalog still points at"), w)
        assert(norm(w).contains(norm(OLDER)) && norm(w).contains(norm(OURS)), w)
        assert(w.contains("Datris will keep it current in a later release"), w)
        assert(!w.contains("story 5") && !w.contains("RESTCatalog"), s"no internal plan names in user-facing text: $w")
        assert(st.registeredMetadataLocation == OURS, s"$st")
        assert(
            st.lastError != null && st.lastError.contains(R.ErrorPrefix) && st.lastError.contains("still points at"),
            s"stale is derived from lastError: $st"
        )
        assert(fake.calls.map(_.method).toList == List("POST", "GET"), s"no commit, no DELETE, no second register: ${fake.calls.mkString("\n")}")
    }

    test("already-exists with UC outside our table root → refusal warning, lastError uc-register: line, no further calls") {
        Seq(
            "s3://datris-lake/somebody_else/metadata/00001-aa.metadata.json",
            // Shares the string prefix of our root but is a sibling table.
            "s3://datris-lake/orders_daily_v2/metadata/00001-bb.metadata.json"
        ).foreach { foreign =>
            val fake = new FakeCatalog(registerResp = alreadyExists, loadResp = (200, s"""{"metadata-location":"$foreign","metadata":{}}"""))
            val status = new RecordingStatusUtil
            val prev = UnityCatalogSyncState(TABLE, "2026-09-28T10:00:00Z", "run-0", null, null, null, null)
            val st = register(fake, status, previous = prev)
            assert(status.warnings.size == 1, s"$foreign: ${status.messages.mkString("\n")}")
            val w = status.warnings.head
            assert(w.startsWith(R.ErrorPrefix) && w.contains(foreign) && w.toLowerCase.contains("refus"), w)
            assert(w.contains(s"$CAT.$SCH.$TABLE"), w)
            assert(st.lastError != null && st.lastError.split("\n").exists(_.startsWith(R.ErrorPrefix)), s"$st")
            assert(st.registeredMetadataLocation == null, s"a refused table is not ours: $st")
            assert(fake.calls.map(_.method).toList == List("POST", "GET"), s"never drop, never merge: ${fake.calls.mkString("\n")}")
        }
    }

    // --- error translation -----------------------------------------------------

    test("403 → external data access / EXTERNAL USE SCHEMA message") {
        val text = failureText(new FakeCatalog(registerResp =
            (403, """{"error_code":"PERMISSION_DENIED","message":"User does not have EXTERNAL USE SCHEMA on Schema 'unity_cat.sales'"}""")
        ))
        assert(text.toLowerCase.contains("external data access"), text)
        assert(text.contains("EXTERNAL USE SCHEMA"), text)
        assert(text.contains(R.ErrorPrefix), text)
        assert(!text.contains("CREATE EXTERNAL METADATA"), s"the lineage wording must not leak into register: $text")
    }

    test("EXTERNAL_LOCATION_DOES_NOT_EXIST → external location message naming the table root") {
        val t1 = failureText(new FakeCatalog(registerResp =
            (400, """{"error_code":"EXTERNAL_LOCATION_DOES_NOT_EXIST","message":"No external location covers s3://datris-lake/orders_daily"}""")
        ))
        assert(t1.toLowerCase.contains("external location"), t1)
        assert(norm(t1).contains(norm(ROOT)), t1)
        // The body rule wins over the 403 rule: PERMISSION_DENIED on an external location.
        val t2 = failureText(new FakeCatalog(registerResp =
            (403, """{"error_code":"PERMISSION_DENIED","message":"User does not have READ FILES on external location 'lake'"}""")
        ))
        assert(t2.toLowerCase.contains("no unity catalog external location covers"), t2)
        assert(norm(t2).contains(norm(ROOT)), t2)
    }

    test("404 → message naming the URL and the path/prefix fields") {
        val text = failureText(
            new FakeCatalog(registerResp = (404, """{"error":{"message":"Not found","code":404}}""")),
            fields = fixtureFields,
            client = fixtureClient
        )
        assert(text.contains(s"$FIXTURE/v1/namespaces/$SCH/register"), text)
        assert(text.contains("icebergRestPath") && text.contains("icebergRestPrefix"), text)
        assert(!text.contains("External Metadata API"), s"the lineage wording must not leak into register: $text")
    }

    test("transport exception → warning + audit failure, sync returns normally") {
        // register level: the cause surfaces with the uc-register: prefix.
        val text = failureText(new FakeCatalog(registerThrows = new java.io.IOException("Connection refused: iceberg-rest:8181")))
        assert(text.contains("Connection refused"), text)

        // sync level: a failure anywhere (here the secret cannot be resolved:
        // no DatrisEnvironment in unit tests) is one warning and no throw.
        // AuditLog.system is a no-op here (DatrisEnvironment.values is null).
        val status = new RecordingStatusUtil
        R.sync(jobContext(status), writeResult)
        assert(status.warnings.size == 1, status.messages.mkString("\n"))
        assert(status.warnings.head.startsWith(R.ErrorPrefix), status.warnings.head)
        assert(status.messages.forall(_._1 != "error"), "a registration failure never errors the run")
        assert(status.messages.forall(_._2 == "processing"), status.messages.mkString("\n"))
    }

    test("kill switch false → one info line, no calls") {
        val status = new RecordingStatusUtil
        val prop = "datris.unityCatalogSync"
        val old = System.getProperty(prop)
        System.setProperty(prop, "false")
        try R.sync(jobContext(status), writeResult)
        finally if (old == null) System.clearProperty(prop) else System.setProperty(prop, old)
        assert(status.messages.size == 1, status.messages.mkString("\n"))
        val (code, state, text) = status.messages.head
        assert(code == "info" && state == "processing", status.messages.head)
        assert(text.contains("switched off") && text.contains("DATRIS_UNITY_CATALOG_SYNC"), text)
    }

    test("other state fields and foreign lastError lines survive a register") {
        val ids = new java.util.HashMap[String, String]()
        ids.put("source", "rel-src-1")
        ids.put("table", "rel-tbl-1")
        val prev = UnityCatalogSyncState(
            pipeline = TABLE,
            lastSyncAt = "2026-09-28T10:00:00Z",
            lastRunId = "run-0",
            commentsHash = "c-hash",
            tagsHash = "t-hash",
            propertiesHash = "p-hash",
            lastError = "uc-lineage: lineage publish failed (status 403)\nuc-register: Unity Catalog still points at an older file",
            lineageHash = "l-hash",
            lastLineageAt = "2026-09-28T10:00:01Z",
            lineageRelationshipIds = ids
        )
        val st = register(new FakeCatalog(), previous = prev)
        assert(st.pipeline == TABLE, s"$st")
        assert(st.lastSyncAt == "2026-09-28T10:00:00Z" && st.lastRunId == "run-0", s"$st")
        assert(st.commentsHash == "c-hash" && st.tagsHash == "t-hash" && st.propertiesHash == "p-hash", s"$st")
        assert(st.lineageHash == "l-hash" && st.lastLineageAt == "2026-09-28T10:00:01Z", s"$st")
        assert(st.lineageRelationshipIds != null && st.lineageRelationshipIds.asScala == Map("source" -> "rel-src-1", "table" -> "rel-tbl-1"), s"$st")
        assert(st.lastError != null && st.lastError.contains("uc-lineage: lineage publish failed (status 403)"), s"foreign lines survive: $st")
        assert(!st.lastError.contains("uc-register:"), s"a clean register clears only its own lines: $st")
        assert(st.registeredMetadataLocation == OURS, s"$st")
    }

    // --- review follow-ups -----------------------------------------------------

    test("409 without an already-exists marker is a failure, not an existing table (no GET)") {
        val fake = new FakeCatalog(registerResp = (409, """{"error":{"message":"Commit conflict","type":"CommitFailedException","code":409}}"""))
        val status = new RecordingStatusUtil
        val st = register(fake, status)
        assert(status.warnings.size == 1, status.messages.mkString("\n"))
        assert(status.warnings.head.startsWith(R.ErrorPrefix) && status.warnings.head.contains("409"), status.warnings.head)
        assert(st.registeredMetadataLocation == null, s"$st")
        assert(fake.calls.map(_.method).toList == List("POST"), fake.calls.mkString("\n"))
    }

    test("already-exists then 404 on GET → 'not readable' warning naming the table URL, nothing else called") {
        val fake = new FakeCatalog(registerResp = alreadyExists)
        val status = new RecordingStatusUtil
        val st = register(fake, status)
        assert(status.warnings.size == 1, status.messages.mkString("\n"))
        val w = status.warnings.head
        assert(w.startsWith(R.ErrorPrefix), w)
        assert(w.contains("reported") && w.contains("as existing but it is not readable at"), w)
        assert(w.contains(s"https://$DBX_HOST/api/2.1/unity-catalog/iceberg-rest/v1/catalogs/$CAT/namespaces/$SCH/tables/$TABLE"), w)
        assert(st.lastError != null && st.lastError.contains("not readable"), s"$st")
        assert(fake.calls.map(_.method).toList == List("POST", "GET"), fake.calls.mkString("\n"))
    }

    test("credentialShape flags half an OAuth pair without a token") {
        def creds(id: Option[String], secret: Option[String], token: Option[String]) =
            ResolvedDatabricksCredentials(host = DBX_HOST, clientId = id, clientSecret = secret, token = token)
        val idOnly = R.credentialShape(creds(Some("sp"), None, None), "uc_sec")
        assert(idOnly.exists(m => m.startsWith(R.ErrorPrefix) && m.contains("uc_sec") && m.contains("clientId without clientSecret")), idOnly)
        val secretOnly = R.credentialShape(creds(None, Some("s"), None), "uc_sec")
        assert(secretOnly.exists(_.contains("clientSecret without clientId")), secretOnly)
        assert(R.credentialShape(creds(Some("sp"), None, Some("dapi")), "uc_sec").isEmpty, "a token covers the missing half")
        assert(R.credentialShape(creds(Some("sp"), Some("s"), None), "uc_sec").isEmpty)
        assert(R.credentialShape(creds(None, None, Some("dapi")), "uc_sec").isEmpty)
        assert(R.credentialShape(creds(None, None, None), "uc_sec").isEmpty, "host-only (anonymous) is allowed for the register")
    }

    // --- sync fixtures ---------------------------------------------------------

    private val writeResult = IcebergWriter.WriteResult(
        snapshotId = 42L,
        addedRecords = 2L,
        deletedRecords = 0L,
        totalRecords = 2L,
        metadataLocation = OURS
    )

    private def jobContext(status: StatusUtil): JobContext = {
        val cfg = PipelineConfig(
            name = TABLE,
            destination = Destination(objectStore = ObjectStore(prefixKey = TABLE, fileFormat = "iceberg")),
            unityCatalog = UnityCatalogSync(enabled = true, credentialsSecret = "uc_fixture", catalog = "unity", schema = "default")
        )
        JobContext(
            pipelineToken = "run-token-0001",
            metadata = PipelineMetadata(TABLE, "orders.csv", "/tmp/orders.csv", "pub-1", bulkUpload = false),
            data = null,
            config = cfg,
            pipelineProperties = null,
            state = null,
            thread = null,
            statusUtil = status
        )
    }
}
