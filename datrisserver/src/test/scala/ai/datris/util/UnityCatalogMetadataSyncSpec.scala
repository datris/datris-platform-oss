package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.sql.{Connection, SQLException, Statement}
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/** Story: Unity Catalog 1: config + metadata push (Databricks)
  * (plans/stories/unity-catalog-1-metadata-push.md), Acceptance bullet 1.
  *
  * Seams this spec pins (the story names `render` and its arguments; the rest
  * is the smallest shape that lets hash-skip and per-statement failure run
  * without Mongo or a warehouse):
  *
  * {{{
  *   UnityCatalogMetadataSync.render(
  *       pipeline: String, datrisCatalog: String /* nullable */, db: Database,
  *       presentProvenanceColumns: List[String] (or Seq/Iterable), runId: String,
  *       runAt: String, configVersion: Int, dqStatus: String, environment: String,
  *       knobs: UnityCatalogSync, source: String   // tap name or upload filename
  *   ): List[(String, String)]                    // (kind, sql), in issue order
  *
  *   kind ∈ {"comments", "tags", "properties"} — the group a statement belongs
  *   to; the same names as the knobs and the state doc's <kind>Hash fields.
  *
  *   UnityCatalogMetadataSync.execute(
  *       conn: java.sql.Connection, pipeline: String, runId: String,
  *       statements: List[(String, String)], previous: UnityCatalogSyncState /* null = never synced */,
  *       statusUtil: StatusUtil
  *   ): UnityCatalogSyncState   // the doc sync() then hands to UnityCatalogSyncIO.write
  *
  *   case class UnityCatalogSyncState(pipeline: String, lastSyncAt: String, lastRunId: String,
  *       commentsHash: String, tagsHash: String, propertiesHash: String, lastError: String)
  * }}}
  *
  * `execute` hash-skips a group whose SHA-256 matches `previous`, runs every
  * remaining statement in its own `Statement.execute` with its own try/catch,
  * never throws, and returns the updated doc. `sync(conn, jobContext, ...)`
  * wraps it with the kill switch, UnityCatalogSyncIO read/write and
  * AuditLog.system (a no-op here: DatrisEnvironment.values is null in tests).
  *
  * StatusUtil note: the real `send` only accepts states begin/processing/end,
  * so the recorder below does not assert on the state argument, only on the
  * code and description.
  */
class UnityCatalogMetadataSyncSpec extends AnyFunSuite {

    private val PIPELINE = "orders_daily"
    private val RUN_ID = "run-token-0001"
    private val RUN_AT = "2026-09-29T12:00:00Z"
    private val Q = "datris.default.orders"

    private val db = Database(dbName = "datris", schema = "default", table = "orders", useDatabricks = true, warehouse = "abc123", credentialsSecret = "dbx")

    private val allOn = UnityCatalogSync(enabled = true, comments = true, tags = true, properties = true)

    private def render(
        pipeline: String = PIPELINE,
        datrisCatalog: String = "sales",
        database: Database = db,
        present: List[String] = ProvenanceStamper.AllFields,
        runId: String = RUN_ID,
        runAt: String = RUN_AT,
        configVersion: Int = 3,
        dqStatus: String = "pass",
        environment: String = "datris",
        knobs: UnityCatalogSync = allOn,
        source: String = "orders.csv"
    ): List[(String, String)] =
        UnityCatalogMetadataSync.render(
            pipeline = pipeline,
            datrisCatalog = datrisCatalog,
            db = database,
            presentProvenanceColumns = present,
            runId = runId,
            runAt = runAt,
            configVersion = configVersion,
            dqStatus = dqStatus,
            environment = environment,
            knobs = knobs,
            source = source
        )

    private def sqls(stmts: List[(String, String)]): List[String] = stmts.map(_._2)
    private def ofKind(stmts: List[(String, String)], kind: String): List[String] = stmts.filter(_._1 == kind).map(_._2)
    private def tableComments(stmts: List[(String, String)]) = sqls(stmts).filter(_.startsWith("COMMENT ON TABLE"))
    private def columnComments(stmts: List[(String, String)]) = sqls(stmts).filter(_.startsWith("COMMENT ON COLUMN"))
    private def tagStatements(stmts: List[(String, String)]) = sqls(stmts).filter(s => s.startsWith("ALTER TABLE") && s.contains("SET TAGS"))
    private def propertyStatements(stmts: List[(String, String)]) = sqls(stmts).filter(s => s.startsWith("ALTER TABLE") && s.contains("SET TBLPROPERTIES"))

    /** Tag keys in `SET TAGS ('k' = 'v', ...)`. */
    private def tagKeys(sql: String): List[String] = {
        val inner = sql.substring(sql.indexOf("SET TAGS") + "SET TAGS".length).trim.stripPrefix("(").stripSuffix(")")
        "'([^']+)'\\s*=".r.findAllMatchIn(inner).map(_.group(1)).toList
    }

    // --- JDBC and status fakes ---------------------------------------------------

    private class RecordingStatusUtil extends StatusUtil {
        val messages = new ListBuffer[(String, String, String)]()
        override def overrideProcessName(processName: String): Unit = ()
        override def info(state: String, description: String): Unit = messages += (("info", state, description))
        override def warn(state: String, description: String): Unit = messages += (("warning", state, description))
        override def error(state: String, description: String): Unit = messages += (("error", state, description))
        def warnings: List[String] = messages.filter(_._1 == "warning").map(_._3).toList
        def infos: List[String] = messages.filter(_._1 == "info").map(_._3).toList
    }

    /** A Connection whose statements record every SQL string executed and
      * throw SQLException when `failWhen` matches. Anything else returns a
      * harmless default so the implementation may use execute/executeUpdate
      * and one or many Statement objects. */
    private class FakeWarehouse(failWhen: String => Boolean = _ => false) {
        val executed = new ListBuffer[String]()

        private def default(m: Method): AnyRef = m.getReturnType match {
            case java.lang.Boolean.TYPE => java.lang.Boolean.FALSE
            case java.lang.Integer.TYPE => Integer.valueOf(0)
            case java.lang.Long.TYPE => java.lang.Long.valueOf(0L)
            case _ => null
        }

        private val statementHandler = new InvocationHandler {
            override def invoke(proxy: AnyRef, m: Method, args: Array[AnyRef]): AnyRef = m.getName match {
                case "execute" | "executeUpdate" | "executeLargeUpdate" | "addBatch" if args != null && args.nonEmpty && args(0).isInstanceOf[String] =>
                    val sql = args(0).asInstanceOf[String]
                    executed += sql
                    if (failWhen(sql)) throw new SQLException("[INSUFFICIENT_PERMISSIONS] User does not have APPLY TAG on Schema 'datris.default'")
                    default(m)
                case "isClosed" => java.lang.Boolean.FALSE
                case "hashCode" => Integer.valueOf(System.identityHashCode(proxy))
                case "equals" => java.lang.Boolean.valueOf(proxy eq args(0))
                case "toString" => "FakeStatement"
                case _ => default(m)
            }
        }

        val connection: Connection = Proxy
            .newProxyInstance(
                getClass.getClassLoader,
                Array(classOf[Connection]),
                new InvocationHandler {
                    override def invoke(proxy: AnyRef, m: Method, args: Array[AnyRef]): AnyRef = m.getName match {
                        case "createStatement" =>
                            Proxy.newProxyInstance(getClass.getClassLoader, Array(classOf[Statement]), statementHandler)
                        case "getAutoCommit" => java.lang.Boolean.TRUE
                        case "isClosed" => java.lang.Boolean.FALSE
                        case "hashCode" => Integer.valueOf(System.identityHashCode(proxy))
                        case "equals" => java.lang.Boolean.valueOf(proxy eq args(0))
                        case "toString" => "FakeConnection"
                        case _ => default(m)
                    }
                }
            )
            .asInstanceOf[Connection]
    }

    private def runSync(
        wh: FakeWarehouse,
        statements: List[(String, String)],
        previous: UnityCatalogSyncState,
        status: RecordingStatusUtil,
        runId: String = RUN_ID,
        tableCreated: Boolean = false
    ): UnityCatalogSyncState =
        UnityCatalogMetadataSync.execute(
            conn = wh.connection,
            pipeline = PIPELINE,
            runId = runId,
            statements = statements,
            previous = previous,
            statusUtil = status,
            tableCreated = tableCreated
        )

    // --- rendering ---------------------------------------------------------------

    test("renders table comment, provenance column comments, tags and properties for a fully enabled config") {
        val stmts = render()
        val all = sqls(stmts)

        assert(tableComments(stmts).size == 1, all.mkString("\n"))
        val tc = tableComments(stmts).head
        assert(tc.startsWith(s"COMMENT ON TABLE $Q IS '"), tc)
        assert(tc.contains(PIPELINE) && tc.contains("orders.csv"), s"table comment must name the pipeline and source: $tc")
        assert(!tc.contains(RUN_ID) && !tc.contains(RUN_AT), s"table comment must carry no run id or timestamp (hash stability): $tc")

        val cc = columnComments(stmts)
        assert(cc.size == ProvenanceStamper.AllFields.size, cc.mkString("\n"))
        ProvenanceStamper.AllFields.foreach { col =>
            assert(cc.exists(_.startsWith(s"COMMENT ON COLUMN $Q.$col IS '")), s"missing column comment for $col in:\n${cc.mkString("\n")}")
        }

        assert(tagStatements(stmts).size == 1, all.mkString("\n"))
        assert(tagStatements(stmts).head.startsWith(s"ALTER TABLE $Q SET TAGS ("), tagStatements(stmts).head)

        assert(propertyStatements(stmts).size == 1, all.mkString("\n"))
        val props = propertyStatements(stmts).head
        assert(props.startsWith(s"ALTER TABLE $Q SET TBLPROPERTIES ("), props)
        List("datris.pipeline", "datris.configVersion", "datris.lastRunId", "datris.lastRunAt", "datris.environment", "datris.lineagePath").foreach { k =>
            assert(props.contains(s"'$k'"), s"TBLPROPERTIES missing $k: $props")
        }
        assert(props.contains("'datris.configVersion' = '3'"), props)
        assert(props.contains(s"'datris.lastRunAt' = '$RUN_AT'"), props)
        assert(props.contains("'datris.environment' = 'datris'"), props)
        assert(props.contains(s"'datris.lineagePath' = '/api/v1/lineage/pipeline/$PIPELINE'"), props)

        // Order: table comment, column comments, tags, properties.
        val firstCol = all.indexWhere(_.startsWith("COMMENT ON COLUMN"))
        assert(all.indexWhere(_.startsWith("COMMENT ON TABLE")) == 0, all.mkString("\n"))
        assert(firstCol > 0 && firstCol < all.indexWhere(_.contains("SET TAGS")), all.mkString("\n"))
        assert(all.indexWhere(_.contains("SET TAGS")) < all.indexWhere(_.contains("SET TBLPROPERTIES")), all.mkString("\n"))

        // Kinds group the statements the way the knobs and hashes do.
        assert(stmts.map(_._1).toSet == Set("comments", "tags", "properties"), stmts.map(_._1))
        assert(ofKind(stmts, "comments").size == 1 + ProvenanceStamper.AllFields.size)
        assert(ofKind(stmts, "tags") == tagStatements(stmts))
        assert(ofKind(stmts, "properties") == propertyStatements(stmts))
    }

    test("comments=false drops COMMENT statements") {
        val stmts = render(knobs = allOn.copy(comments = false))
        assert(!sqls(stmts).exists(_.startsWith("COMMENT ON")), sqls(stmts).mkString("\n"))
        assert(tagStatements(stmts).size == 1 && propertyStatements(stmts).size == 1)
    }

    test("tags=false drops the SET TAGS statement") {
        val stmts = render(knobs = allOn.copy(tags = false))
        assert(tagStatements(stmts).isEmpty, sqls(stmts).mkString("\n"))
        assert(tableComments(stmts).size == 1 && propertyStatements(stmts).size == 1)
    }

    test("properties=false drops the SET TBLPROPERTIES statement") {
        val stmts = render(knobs = allOn.copy(properties = false))
        assert(propertyStatements(stmts).isEmpty, sqls(stmts).mkString("\n"))
        assert(tableComments(stmts).size == 1 && tagStatements(stmts).size == 1)
    }

    test("only present provenance columns get comments") {
        val present = List(ProvenanceStamper.RunId, ProvenanceStamper.IngestedAt)
        val cc = columnComments(render(present = present))
        assert(cc.size == 2, cc.mkString("\n"))
        assert(cc.exists(_.contains(s"$Q.${ProvenanceStamper.RunId} IS")))
        assert(cc.exists(_.contains(s"$Q.${ProvenanceStamper.IngestedAt} IS")))
        (ProvenanceStamper.AllFields.toSet -- present).foreach(col => assert(!cc.exists(_.contains(col)), s"$col is not present but got a comment"))

        val none = render(present = Nil)
        assert(columnComments(none).isEmpty, sqls(none).mkString("\n"))
        assert(tableComments(none).size == 1, "the table comment ships without provenance columns")
    }

    test("datris_run_id is never a tag and appears in TBLPROPERTIES as datris.lastRunId") {
        val stmts = render()
        tagStatements(stmts).foreach { t =>
            assert(!t.contains("datris_run_id"), s"datris_run_id must not be a tag: $t")
            assert(!t.contains(RUN_ID), s"the run id must not appear in tags: $t")
        }
        assert(propertyStatements(stmts).head.contains(s"'datris.lastRunId' = '$RUN_ID'"), propertyStatements(stmts).head)
    }

    test("tags are exactly datris_pipeline, datris_catalog, datris_dq_status, managed_by") {
        val t = tagStatements(render(dqStatus = "warn")).head
        assert(tagKeys(t).sorted == List("datris_catalog", "datris_dq_status", "datris_pipeline", "managed_by"), t)
        assert(t.contains(s"'datris_pipeline' = '$PIPELINE'"), t)
        assert(t.contains("'datris_catalog' = 'sales'"), t)
        assert(t.contains("'datris_dq_status' = 'warn'"), t)
        assert(t.contains("'managed_by' = 'datris'"), t)

        // datris_catalog omitted when the pipeline has no catalog.
        val noCatalog = tagStatements(render(datrisCatalog = null)).head
        assert(tagKeys(noCatalog).sorted == List("datris_dq_status", "datris_pipeline", "managed_by"), noCatalog)
    }

    test("identifiers with hyphens and spaces are backtick-quoted via DatabricksConnectionUtil.ident") {
        val odd = db.copy(dbName = "my-catalog", schema = "raw data", table = "orders-v2")
        val q = DatabricksConnectionUtil.qualifiedTable(odd)
        assert(q == "`my-catalog`.`raw data`.`orders-v2`", q)
        val stmts = render(database = odd)
        assert(tableComments(stmts).head.startsWith(s"COMMENT ON TABLE $q IS "), tableComments(stmts).head)
        assert(columnComments(stmts).forall(_.startsWith(s"COMMENT ON COLUMN $q.")), columnComments(stmts).mkString("\n"))
        assert(tagStatements(stmts).head.startsWith(s"ALTER TABLE $q SET TAGS"), tagStatements(stmts).head)
        assert(propertyStatements(stmts).head.startsWith(s"ALTER TABLE $q SET TBLPROPERTIES"), propertyStatements(stmts).head)
        assert(!sqls(stmts).exists(_.contains("datris.default.orders")))
    }

    test("single quotes in pipeline name and source are doubled") {
        val stmts = render(pipeline = "bob's_orders", source = "o'neil.csv")
        val tc = tableComments(stmts).head
        assert(tc.contains("bob''s_orders"), tc)
        assert(tc.contains("o''neil.csv"), tc)
        assert(!tc.contains("bob's") && !tc.contains("o'neil"), s"unescaped quote in: $tc")
        assert(tagStatements(stmts).head.contains("'datris_pipeline' = 'bob''s_orders'"), tagStatements(stmts).head)
        assert(propertyStatements(stmts).head.contains("'datris.pipeline' = 'bob''s_orders'"), propertyStatements(stmts).head)
        // The shared quoting helper the loader and the sync both use.
        assert(DatabricksConnectionUtil.sqlLiteral("it's") == "it''s")
    }

    test("backslash in pipeline name and source is escaped") {
        val stmts = render(pipeline = "orders\\", source = "c:\\in\\it\\'s.csv")
        val tc = tableComments(stmts).head
        // Databricks processes backslash escapes in literals: each \ is doubled
        // before quotes are doubled, so neither can terminate the literal.
        assert(tc.contains("orders\\\\"), tc)
        assert(tc.contains("c:\\\\in\\\\it\\\\''s.csv"), tc)
        assert(tagStatements(stmts).head.contains("'datris_pipeline' = 'orders\\\\'"), tagStatements(stmts).head)
        assert(propertyStatements(stmts).head.contains("'datris.pipeline' = 'orders\\\\'"), propertyStatements(stmts).head)
    }

    test("knobs left unset (null, as Spring's Jackson leaves them) keep every group") {
        val stmts = render(knobs = UnityCatalogSync(enabled = true))
        assert(stmts.map(_._1).toSet == Set("comments", "tags", "properties"), stmts.map(_._1))
    }

    // --- hash-skip and execution ------------------------------------------------

    test("unchanged comment and tag groups are skipped on the second render with the same state doc") {
        val first = new FakeWarehouse()
        val status1 = new RecordingStatusUtil
        val stmts1 = render()
        val state1 = runSync(first, stmts1, previous = null, status = status1)
        assert(first.executed.toList == sqls(stmts1), "first sync must issue every rendered statement in order")
        assert(state1 != null && state1.commentsHash != null && state1.tagsHash != null && state1.propertiesHash != null, s"$state1")
        assert(state1.lastRunId == RUN_ID && state1.lastError == null && state1.lastSyncAt != null, s"$state1")
        assert(status1.warnings.isEmpty, status1.messages.mkString("\n"))

        val second = new FakeWarehouse()
        val status2 = new RecordingStatusUtil
        val stmts2 = render(runId = "run-token-0002", runAt = "2026-09-30T12:00:00Z")
        val state2 = runSync(second, stmts2, previous = state1, status = status2, runId = "run-token-0002")
        assert(!second.executed.exists(_.startsWith("COMMENT ON")), s"comments were re-issued:\n${second.executed.mkString("\n")}")
        assert(!second.executed.exists(_.contains("SET TAGS")), s"tags were re-issued:\n${second.executed.mkString("\n")}")
        assert(status2.infos.exists(m => m.contains("unchanged") && m.contains("skipped")), status2.messages.mkString("\n"))
        assert(state2.commentsHash == state1.commentsHash && state2.tagsHash == state1.tagsHash)
    }

    test("properties group is re-issued when lastRunId changes") {
        val state1 = runSync(new FakeWarehouse(), render(), previous = null, status = new RecordingStatusUtil)
        val second = new FakeWarehouse()
        val state2 = runSync(second, render(runId = "run-token-0002"), previous = state1, status = new RecordingStatusUtil, runId = "run-token-0002")
        val issued = second.executed.filter(_.contains("SET TBLPROPERTIES"))
        assert(issued.size == 1, second.executed.mkString("\n"))
        assert(issued.head.contains("'datris.lastRunId' = 'run-token-0002'"), issued.head)
        assert(state2.lastRunId == "run-token-0002")
        assert(state2.propertiesHash != state1.propertiesHash)
    }

    test("a failing statement does not stop later statements and sets lastError") {
        val wh = new FakeWarehouse(failWhen = _.contains("SET TAGS"))
        val status = new RecordingStatusUtil
        val stmts = render()
        val state = runSync(wh, stmts, previous = null, status = status) // must not throw
        assert(wh.executed.toList == sqls(stmts), "every statement must be attempted, in order, despite the tag failure")
        assert(wh.executed.last.contains("SET TBLPROPERTIES"), "the properties statement after the failed tag statement must still run")
        assert(status.warnings.size == 1, status.messages.mkString("\n"))
        assert(status.warnings.head.contains("tags") && status.warnings.head.contains("APPLY TAG"), status.warnings.head)
        assert(status.messages.forall(_._1 != "error"), "a sync failure is a warning, never an error")
        assert(state != null && state.lastError != null && state.lastError.contains("APPLY TAG"), s"$state")
        // Tags failed, so their hash must not be recorded: the next run retries them.
        assert(state.tagsHash == null, s"a failed group must not record its hash: $state")
        assert(state.commentsHash != null && state.propertiesHash != null, s"$state")
    }

    test("a recreated table ignores stored hashes and re-applies every group") {
        val state1 = runSync(new FakeWarehouse(), render(), previous = null, status = new RecordingStatusUtil)
        val second = new FakeWarehouse()
        val stmts = render()
        val state2 = runSync(second, stmts, previous = state1, status = new RecordingStatusUtil, tableCreated = true)
        assert(second.executed.toList == sqls(stmts), s"every group must be re-issued on a new table:\n${second.executed.mkString("\n")}")
        assert(state2.commentsHash == state1.commentsHash && state2.tagsHash == state1.tagsHash)
    }

    test("upload runs get a stable 'file upload' comment label; the filename goes to datris.lastSource") {
        val md1 = PipelineMetadata("orders_daily", "orders_20260929_0001.csv", "/tmp/a", "pub-1", bulkUpload = false)
        val md2 = md1.copy(dataFileName = "orders_20260930_0002.csv")
        val (c1, l1) = UnityCatalogMetadataSync.sourceLabels(md1)
        val (c2, l2) = UnityCatalogMetadataSync.sourceLabels(md2)
        assert(c1 == "file upload" && c2 == "file upload")
        assert(l1 == "orders_20260929_0001.csv" && l2 == "orders_20260930_0002.csv")

        val s1 = UnityCatalogMetadataSync.render(PIPELINE, "sales", db, ProvenanceStamper.AllFields, RUN_ID, RUN_AT, 3, "pass", "datris", allOn, c1, l1)
        val s2 = UnityCatalogMetadataSync.render(PIPELINE, "sales", db, ProvenanceStamper.AllFields, RUN_ID, RUN_AT, 3, "pass", "datris", allOn, c2, l2)
        assert(ofKind(s1, "comments") == ofKind(s2, "comments"), "per-run filenames must not change the comment group")
        assert(!ofKind(s1, "comments").exists(_.contains("orders_2026")), ofKind(s1, "comments").mkString("\n"))
        assert(propertyStatements(s1).head.contains("'datris.lastSource' = 'orders_20260929_0001.csv'"), propertyStatements(s1).head)
        assert(propertyStatements(s2).head.contains("'datris.lastSource' = 'orders_20260930_0002.csv'"), propertyStatements(s2).head)

        val tap = md1.copy(tapName = "orders_tap")
        assert(UnityCatalogMetadataSync.sourceLabels(tap) == ("tap orders_tap", "tap orders_tap"))
        assert(UnityCatalogMetadataSync.sourceLabels(null) == ("file upload", null))
    }

    test("a failing table-existence probe warns and assumes the table existed") {
        val status = new RecordingStatusUtil
        val existed = UnityCatalogMetadataSync.probeTableExisted(status)(throw new SQLException("[INSUFFICIENT_PERMISSIONS] information_schema"))
        assert(existed, "probe failure must degrade to 'assume existed'")
        assert(status.warnings.size == 1 && status.warnings.head.contains("uc-sync"), status.messages.mkString("\n"))
        assert(status.messages.forall(_._1 != "error"))

        val ok = new RecordingStatusUtil
        assert(!UnityCatalogMetadataSync.probeTableExisted(ok)(false))
        assert(ok.messages.isEmpty)
    }

    // Story: Unity Catalog 3: lineage publish (plans/stories/unity-catalog-3-lineage-publish.md),
    // Acceptance bullet 3. The lineage publisher shares this state doc; the
    // metadata sync runs first on every load and must not wipe its fields.
    test("execute keeps lineageHash, lastLineageAt and relationship ids from the previous state") {
        val ids = new java.util.HashMap[String, String]()
        ids.put("source", "rel-src-1")
        ids.put("table", "rel-tbl-1")
        val synced = runSync(new FakeWarehouse(), render(), previous = null, status = new RecordingStatusUtil)
        val prevState = synced.copy(lineageHash = "lineage-hash-1", lastLineageAt = "2026-09-28T10:00:00Z", lineageRelationshipIds = ids)

        val next =
            runSync(new FakeWarehouse(), render(runId = "run-token-0002"), previous = prevState, status = new RecordingStatusUtil, runId = "run-token-0002")
        assert(next.lineageHash == "lineage-hash-1", s"$next")
        assert(next.lastLineageAt == "2026-09-28T10:00:00Z", s"$next")
        assert(next.lineageRelationshipIds != null && next.lineageRelationshipIds.asScala == Map("source" -> "rel-src-1", "table" -> "rel-tbl-1"), s"$next")

        // Also on a failing metadata group and on a recreated table.
        val failing =
            runSync(new FakeWarehouse(failWhen = _.contains("SET TAGS")), render(), previous = prevState, status = new RecordingStatusUtil, tableCreated = true)
        assert(failing.lineageHash == "lineage-hash-1" && failing.lastLineageAt == "2026-09-28T10:00:00Z", s"$failing")
        assert(failing.lineageRelationshipIds != null && failing.lineageRelationshipIds.get("table") == "rel-tbl-1", s"$failing")

        // A pre-story-3 doc (fields absent → null) stays null: "lineage never published".
        assert(synced.lineageHash == null && synced.lastLineageAt == null && synced.lineageRelationshipIds == null, s"$synced")
    }

    // Story: Unity Catalog 4: Iceberg register spike (plans/stories/unity-catalog-4-iceberg-register.md),
    // Acceptance bullet 3. The registrar shares this state doc; execute must not wipe its fields.
    test("execute keeps registeredMetadataLocation and lastRegisterAt from the previous state") {
        val loc = "s3a://datris-lake/orders_daily/metadata/00002-7f1c.metadata.json"
        val synced = runSync(new FakeWarehouse(), render(), previous = null, status = new RecordingStatusUtil)
        val prevState = synced.copy(registeredMetadataLocation = loc, lastRegisterAt = "2026-09-28T10:00:02Z")

        val next =
            runSync(new FakeWarehouse(), render(runId = "run-token-0002"), previous = prevState, status = new RecordingStatusUtil, runId = "run-token-0002")
        assert(next.registeredMetadataLocation == loc && next.lastRegisterAt == "2026-09-28T10:00:02Z", s"$next")

        val failing =
            runSync(new FakeWarehouse(failWhen = _.contains("SET TAGS")), render(), previous = prevState, status = new RecordingStatusUtil, tableCreated = true)
        assert(failing.registeredMetadataLocation == loc && failing.lastRegisterAt == "2026-09-28T10:00:02Z", s"$failing")

        // A pre-story-4 doc stays null: "never registered".
        assert(synced.registeredMetadataLocation == null && synced.lastRegisterAt == null, s"$synced")
    }
}
