package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.model._
import org.slf4j.{Logger, LoggerFactory}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import scala.util.Try
import scala.util.control.NonFatal

/** Unity Catalog metadata push for a Databricks destination (opt-in via
  * `unityCatalog: {"enabled": true}`). After a successful load the loader
  * calls [[sync]] on the same warehouse connection; it annotates the Delta
  * table with:
  *
  *  - "comments":   a table comment naming the pipeline and source, and a
  *                  fixed comment on every `_datris_*` provenance column the
  *                  load carries;
  *  - "tags":       exactly `datris_pipeline`, `datris_catalog` (only when the
  *                  pipeline has a catalog), `datris_dq_status`, `managed_by`;
  *  - "properties": run-level TBLPROPERTIES (`datris.lastRunId`, ...).
  *
  * The comment and tag groups carry no run id or timestamp, so they hash the
  * same across runs and are skipped when unchanged; properties change every
  * run by design. A statement failure (missing `APPLY TAG`, warehouse hiccup)
  * is one warning line on the run plus an audit entry, never a failed load. */
object UnityCatalogMetadataSync {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    val Comments = "comments"
    val Tags = "tags"
    val Properties = "properties"

    /** Fixed comment text per provenance column. */
    private val ColumnComments: Map[String, String] = Map(
        ProvenanceStamper.RunId -> "Datris run id (pipeline token) of the run that loaded this row",
        ProvenanceStamper.IngestedAt -> "UTC time Datris ingested this row",
        ProvenanceStamper.ConfigVersion -> "Datris pipeline definition version that loaded this row",
        ProvenanceStamper.TapRun -> "Datris tap run that produced this row (tap-fed runs only)",
        ProvenanceStamper.ScriptSha -> "Identity (SHA) of the tap script that produced this row (tap-fed runs only)",
        ProvenanceStamper.Source -> "Declared source of the tap that produced this row (tap-fed runs only)"
    )

    /** Kill switch: `DATRIS_UNITY_CATALOG_SYNC` (or the `datris.unityCatalogSync`
      * system property). Unset ⇒ on; only an explicit `false` turns it off. */
    def switchedOn: Boolean =
        !sys.props.get("datris.unityCatalogSync").orElse(sys.env.get("DATRIS_UNITY_CATALOG_SYNC"))
            .exists(_.trim.equalsIgnoreCase("false"))

    /** Single-quoted Databricks string literal. Databricks SQL processes
      * backslash escapes inside literals, so backslashes are doubled before
      * quotes; otherwise a trailing `\` would un-terminate the literal. */
    private[util] def lit(value: String): String =
        "'" + Option(value).getOrElse("").replace("\\", "\\\\").replace("'", "''") + "'"

    private def pairs(kv: Seq[(String, String)]): String =
        kv.map { case (k, v) => lit(k) + " = " + lit(v) }.mkString(", ")

    /** Render the statements for one sync, in issue order, each labelled with
      * its group. Pure. */
    def render(
        pipeline: String,
        datrisCatalog: String,
        db: Database,
        presentProvenanceColumns: Seq[String],
        runId: String,
        runAt: String,
        configVersion: Int,
        dqStatus: String,
        environment: String,
        knobs: UnityCatalogSync,
        source: String,
        lastSource: String = null
    ): List[(String, String)] = {
        val q = DatabricksConnectionUtil.qualifiedTable(db)
        val out = List.newBuilder[(String, String)]

        if (knobs == null || knobs.commentsOn) {
            val src = Option(source).filter(_.trim.nonEmpty).getOrElse("unknown")
            val tableComment = s"Loaded by Datris pipeline $pipeline (source: $src). " +
                "The last run id and lineage path are in the table properties datris.lastRunId and datris.lineagePath."
            out += Comments -> s"COMMENT ON TABLE $q IS ${lit(tableComment)}"
            val present = Option(presentProvenanceColumns).getOrElse(Nil).map(_.toLowerCase).toSet
            ProvenanceStamper.AllFields.filter(present.contains).foreach { col =>
                out += Comments -> s"COMMENT ON COLUMN $q.${DatabricksConnectionUtil.ident(col)} IS ${lit(ColumnComments(col))}"
            }
        }

        if (knobs == null || knobs.tagsOn) {
            val tags = Seq("datris_pipeline" -> pipeline) ++
                Option(datrisCatalog).filter(_.nonEmpty).map("datris_catalog" -> _).toSeq ++
                Seq("datris_dq_status" -> dqStatus, "managed_by" -> "datris")
            out += Tags -> s"ALTER TABLE $q SET TAGS (${pairs(tags)})"
        }

        if (knobs == null || knobs.propertiesOn) {
            val props = Seq(
                "datris.pipeline" -> pipeline,
                "datris.configVersion" -> configVersion.toString,
                "datris.lastRunId" -> runId,
                "datris.lastRunAt" -> runAt,
                "datris.environment" -> environment,
                "datris.lineagePath" -> ("/api/v1/lineage/pipeline/" + pipeline)
            ) ++ Option(lastSource).filter(_.nonEmpty).map("datris.lastSource" -> _).toSeq
            out += Properties -> s"ALTER TABLE $q SET TBLPROPERTIES (${pairs(props)})"
        }
        out.result()
    }

    /** (table-comment source, `datris.lastSource`). The comment label must be
      * stable across runs so the comment group hash-skips: tap-fed runs name
      * the tap; every other run says "file upload", and the per-run filename
      * goes to the `datris.lastSource` property (re-issued every run anyway). */
    def sourceLabels(md: PipelineMetadata): (String, String) =
        if (md != null && md.tapName != null) ("tap " + md.tapName, "tap " + md.tapName)
        else ("file upload", if (md != null) md.dataFileName else null)

    /** Did the table exist before this load's CREATE TABLE IF NOT EXISTS?
      * A failing probe (warehouse hiccup, no visibility into
      * information_schema) must never fail the load: it warns and assumes the
      * table existed, i.e. hash-skip behaves as if nothing was recreated. */
    def probeTableExisted(statusUtil: StatusUtil)(probe: => Boolean): Boolean =
        try probe
        catch {
            case NonFatal(e) =>
                statusUtil.warn(
                    "processing",
                    "uc-sync: could not check whether the table already existed; assuming it did: " +
                        Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
                )
                true
        }

    private[util] def sha256(sqls: Seq[String]): String = {
        val digest = MessageDigest.getInstance("SHA-256").digest(sqls.mkString("\n").getBytes(StandardCharsets.UTF_8))
        digest.map(b => f"$b%02x").mkString
    }

    /** Issue `statements` group by group. A group whose hash matches
      * `previous` (null = never synced) is skipped; every other statement
      * runs in its own `execute` with its own try/catch. A group records its
      * hash only when all of its statements succeeded, so a failed group is
      * retried on the next run. `tableCreated` (the loader just issued
      * CREATE TABLE, e.g. after a drop) ignores the stored hashes so every
      * group is re-applied to the new table. Never throws. */
    def execute(
        conn: Connection,
        pipeline: String,
        runId: String,
        statements: List[(String, String)],
        previous: UnityCatalogSyncState,
        statusUtil: StatusUtil,
        tableCreated: Boolean = false
    ): UnityCatalogSyncState = {
        val prevHash: Map[String, String] =
            if (previous == null || tableCreated) Map.empty
            else Map(Comments -> previous.commentsHash, Tags -> previous.tagsHash, Properties -> previous.propertiesHash)
        // Groups not rendered this run (knob off) keep whatever was stored.
        val hashes = scala.collection.mutable.Map[String, String]() ++= prevHash
        val errors = List.newBuilder[String]

        val kinds = statements.map(_._1).distinct
        kinds.foreach { kind =>
            val sqls = statements.filter(_._1 == kind).map(_._2)
            val hash = sha256(sqls)
            if (prevHash.get(kind).contains(hash)) {
                statusUtil.info("processing", s"uc-sync: $kind unchanged, skipped")
            } else {
                var failed = false
                sqls.foreach { sql =>
                    try {
                        val st = conn.createStatement()
                        try st.execute(sql)
                        finally Try(st.close())
                    } catch {
                        case NonFatal(e) =>
                            failed = true
                            val msg = s"$kind: " + Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
                            errors += msg
                            statusUtil.warn("processing", "uc-sync: Unity Catalog " + msg)
                            logger.warn("uc-sync failed for pipeline " + pipeline + ": " + msg)
                    }
                }
                if (failed) hashes.remove(kind)
                else {
                    hashes(kind) = hash
                    statusUtil.info("processing", s"uc-sync: $kind applied (${sqls.size} statement${if (sqls.size == 1) "" else "s"})")
                }
            }
        }

        val errs = errors.result()
        UnityCatalogSyncState(
            pipeline = pipeline,
            lastSyncAt = Instant.now().toString,
            lastRunId = runId,
            commentsHash = hashes.get(Comments).orNull,
            tagsHash = hashes.get(Tags).orNull,
            propertiesHash = hashes.get(Properties).orNull,
            lastError = if (errs.isEmpty) null else errs.mkString("\n")
        )
    }

    /** Loader hook: runs after a successful Databricks load on the same
      * connection. No-op unless the pipeline opted in; one info line when the
      * kill switch is off. Never throws. */
    def sync(conn: Connection, jobContext: JobContext, presentProvenanceColumns: Seq[String], tableCreated: Boolean = false): Unit = {
        val config = jobContext.config
        if (config == null || config.unityCatalog == null || !config.unityCatalog.enabled) return
        val statusUtil = jobContext.statusUtil
        if (!switchedOn) {
            statusUtil.info("processing", "uc-sync: Unity Catalog sync is switched off (DATRIS_UNITY_CATALOG_SYNC=false); skipped")
            return
        }
        try {
            val db = config.destination.database
            val (source, lastSource) = sourceLabels(jobContext.metadata)
            val env = Option(DatrisEnvironment.current).map(_.environment).orNull
            val previous =
                try UnityCatalogSyncIO.read(config.name)
                catch {
                    case NonFatal(e) =>
                        logger.warn("uc-sync state read failed for " + config.name + ": " + e.getMessage)
                        null
                }
            val statements = render(
                pipeline = config.name,
                datrisCatalog = config.catalog,
                db = db,
                presentProvenanceColumns = presentProvenanceColumns,
                runId = jobContext.pipelineToken,
                runAt = Instant.now().toString,
                configVersion = config.version,
                dqStatus = if (statusUtil.hasWarning) "warn" else "pass",
                environment = env,
                knobs = config.unityCatalog,
                source = source,
                lastSource = lastSource
            )
            val state = execute(conn, config.name, jobContext.pipelineToken, statements, previous, statusUtil, tableCreated)
            try UnityCatalogSyncIO.write(state)
            catch { case NonFatal(e) => logger.warn("uc-sync state write failed for " + config.name + ": " + e.getMessage) }
            if (state.lastError != null)
                AuditLog.system(
                    category = "unity-catalog",
                    action = "sync",
                    resourceType = "pipeline",
                    resourceName = config.name,
                    outcome = "failure",
                    errorMessage = state.lastError
                )
        } catch {
            case NonFatal(e) =>
                statusUtil.warn("processing", "uc-sync: Unity Catalog sync failed: " + Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
                logger.warn("uc-sync failed for " + config.name, e)
        }
    }
}
