package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.model.{DatrisException, JobContext, UnityCatalogSyncState}
import org.apache.iceberg.{CatalogUtil, HasTableOperations}
import org.apache.iceberg.catalog.Catalog
import org.apache.iceberg.exceptions.{ForbiddenException, NoSuchNamespaceException, NotAuthorizedException}
import org.apache.iceberg.hadoop.HadoopTables
import org.slf4j.{Logger, LoggerFactory}

import java.io.Closeable
import java.time.Instant
import scala.collection.JavaConverters._
import scala.util.Try
import scala.util.control.NonFatal

/** `unityCatalog.catalogMode: rest` on an object-store Iceberg destination:
  * every commit goes through the Unity Catalog Iceberg REST catalog so its
  * pointer is current after every run.
  *
  * [[prepare]] runs under the write lock before the write. It decides, once
  * per run, whether this run commits through the catalog:
  *  - the first `rest` run adopts a table Datris already wrote at the same
  *    root (registering its current metadata file once) or one the catalog
  *    already holds at our current file; a catalog entry that is behind our
  *    table or points elsewhere is refused (never merge, never drop);
  *  - anything that goes wrong before the commit is one warning, an audit
  *    entry and a path-based write, UNLESS the table was already committed
  *    through the catalog: then a path write would fork its history (REST
  *    commits never update `version-hint.text`), so the run fails;
  *  - switching from `rest` back to `register` is refused for the same reason.
  * A failure during the catalog commit itself is a write failure (the writer
  * throws as usual). [[record]] writes the state doc before the run's `end`
  * line. */
object IcebergRestSession {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    val ErrorPrefix = "uc-rest:"

    /** Why this run writes path-based instead (a refusal or a pre-commit failure). */
    final case class Fallback(reason: String)

    /** What the loader does this run. `inactive`: not a rest-mode pipeline
      * (story-4 behaviour applies); `target`: commit through the catalog;
      * `refused`: path write, recorded as `refused` with the reason. */
    final case class Plan(
        active: Boolean,
        target: Option[IcebergWriter.RestTarget],
        refused: Option[String],
        previous: UnityCatalogSyncState,
        qualified: String,
        location: String = null,
        adopted: Option[String] = None,
        created: Boolean = false,
        closeable: Option[Closeable] = None
    ) {
        def close(): Unit = closeable.foreach(c => Try(c.close()))
    }

    val Inactive: Plan = Plan(active = false, target = None, refused = None, previous = null, qualified = null)

    private def oneLine(s: String): String = Option(s).getOrElse("").replaceAll("[\\r\\n]+", " ")

    private def line(msg: String): String = ErrorPrefix + " " + oneLine(msg)

    private def audit(pipeline: String, message: String): Unit =
        Try(
            AuditLog.system(
                category = "unity-catalog",
                action = "rest-commit",
                resourceType = "pipeline",
                resourceName = pipeline,
                outcome = "failure",
                errorMessage = message
            )
        )

    private def readState(pipeline: String): UnityCatalogSyncState =
        try UnityCatalogSyncIO.read(pipeline)
        catch {
            case NonFatal(e) =>
                logger.warn("uc-rest state read failed for " + pipeline + ": " + e.getMessage)
                null
        }

    /** Committed through the catalog on an earlier run. */
    def restCommitted(previous: UnityCatalogSyncState): Boolean = previous != null && previous.catalogMode == "rest"

    def switchBackMessage: String =
        "switching from catalogMode rest back to register is not supported: the table's current metadata is only known to the catalog; " +
            "keep rest, or start a new prefix"

    private def committedMessage(previous: UnityCatalogSyncState, why: String): String =
        "this table is committed through Unity Catalog (last at " + IcebergCatalogRegistrar.normalize(previous.restMetadataLocation) + "); " +
            why + "; a path-based write would fork its history. Fix the catalog connection, or set deleteBeforeWrite or a new prefix"

    /** Guards and catalog session for one run. Throws (a run failure) only
      * for the switch-back refusal and when a table already committed
      * through the catalog cannot be committed through it this run. */
    def prepare(jobContext: JobContext, outputPath: String, deleteBeforeWrite: Boolean): Plan = {
        val config = jobContext.config
        val statusUtil = jobContext.statusUtil
        val uc = config.unityCatalog
        if (uc == null || !uc.enabled || !uc.registerOn) return Inactive
        val objectStore = if (config.destination != null) config.destination.objectStore else null
        if (objectStore == null || objectStore.fileFormat == null || !objectStore.fileFormat.trim.equalsIgnoreCase("iceberg")) return Inactive

        val previous = readState(config.name)
        // deleteBeforeWrite wipes the table's history, so there is nothing to fork.
        val forkRisk = restCommitted(previous) && !deleteBeforeWrite
        if (!uc.restMode) {
            if (forkRisk) throw new DatrisException(switchBackMessage)
            return Inactive
        }

        val table = IcebergCatalogRegistrar.tableName(config.name)
        val qualified = uc.catalog + "." + uc.schemaOrDefault + "." + table
        val base = Plan(active = true, target = None, refused = None, previous = previous, qualified = qualified, location = outputPath)

        if (!UnityCatalogMetadataSync.switchedOn) {
            if (forkRisk)
                throw new DatrisException(committedMessage(previous, "Unity Catalog sync is switched off (DATRIS_UNITY_CATALOG_SYNC=false)"))
            val msg = line("Unity Catalog sync is switched off (DATRIS_UNITY_CATALOG_SYNC=false); writing path-based")
            statusUtil.info("processing", msg)
            return base.copy(refused = Some(msg))
        }

        val opened: Either[Fallback, Plan] =
            try {
                val creds = CredentialResolver.resolveDatabricks(uc.credentialsSecret, requireCredentials = false)
                val id = creds.clientId.exists(_.nonEmpty)
                val secret = creds.clientSecret.exists(_.nonEmpty)
                if (id != secret && !creds.token.exists(_.nonEmpty))
                    Left(
                        Fallback(
                            "secret " + uc.credentialsSecret + " has " + (if (id) "clientId without clientSecret" else "clientSecret without clientId") +
                                " and no token"
                        )
                    )
                else open(jobContext, outputPath, creds, previous, statusUtil, base)
            } catch {
                case t: Throwable if NonFatal(t) || t.isInstanceOf[LinkageError] =>
                    Left(Fallback("Unity Catalog REST catalog could not be opened: " + oneLine(Option(t.getMessage).getOrElse(t.getClass.getSimpleName))))
            }

        opened match {
            case Right(plan) => plan
            case Left(Fallback(reason)) =>
                if (forkRisk) {
                    audit(config.name, line(reason))
                    throw new DatrisException(committedMessage(previous, "the catalog is not usable this run (" + oneLine(reason) + ")"))
                }
                val msg = line(reason + "; falling back to the path-based write")
                statusUtil.warn("processing", msg)
                logger.warn("uc-rest fallback for pipeline " + config.name + ": " + reason)
                audit(config.name, msg)
                base.copy(refused = Some(msg))
        }
    }

    /** Build the RESTCatalog, probe both sides, decide, and register a path
      * table once when adopting it. Never throws: failures are a Fallback. */
    def open(
        jobContext: JobContext,
        outputPath: String,
        creds: ResolvedDatabricksCredentials,
        previous: UnityCatalogSyncState,
        statusUtil: StatusUtil,
        base: Plan
    ): Either[Fallback, Plan] = {
        val config = jobContext.config
        val uc = config.unityCatalog
        val schema = uc.schemaOrDefault
        val qualified = base.qualified
        val props = IcebergRestCatalogConfig.catalogProperties(creds, creds.extra, uc.catalog)
        val sparkName = IcebergRestCatalogConfig.sparkCatalogName(config.name)
        val ident = IcebergRestCatalogConfig.identifier(schema, config.name)
        var catalog: Catalog = null
        try {
            val spark = SparkSessionManager.getOrCreate()
            // The per-bucket s3a keys (ObjectStoreSpark.applyPerBucketConfig)
            // live in this conf; SparkCatalog gets the same one.
            val hadoopConf = spark.sparkContext.hadoopConfiguration
            catalog = CatalogUtil.buildIcebergCatalog(sparkName, (props + ("type" -> "rest")).asJava, hadoopConf)

            val (catalogHas, history) =
                if (catalog.tableExists(ident)) {
                    val t = catalog.loadTable(ident)
                    t match {
                        case h: HasTableOperations if h.operations().current() != null =>
                            val cur = h.operations().current()
                            (Option(cur.metadataFileLocation()), cur.previousFiles().asScala.map(_.file()).toSet)
                        case _ => (None, Set.empty[String])
                    }
                } else (None, Set.empty[String])

            val tables = new HadoopTables(spark.sessionState.newHadoopConf())
            val pathCurrent =
                if (tables.exists(outputPath))
                    tables.load(outputPath) match {
                        case h: HasTableOperations => Option(h.operations().current()).map(_.metadataFileLocation())
                        case _ => None
                    }
                else None

            val target = IcebergWriter.RestTarget(sparkName, ident, catalog, IcebergRestCatalogConfig.sparkConf(sparkName, props))
            val closeable = catalog match {
                case c: Closeable => Some(c)
                case _ => None
            }
            val ok = base.copy(target = Some(target), closeable = closeable)
            val n = IcebergCatalogRegistrar.normalize _

            RestAdoptDecision.decide(catalogHas, pathCurrent, outputPath, history) match {
                case RestAdoptDecision.CreateNew =>
                    statusUtil.info("processing", line(s"$qualified is new; creating it through the catalog at $outputPath"))
                    Right(ok.copy(created = true))
                case RestAdoptDecision.AdoptPath(p) =>
                    catalog.registerTable(ident, n(p))
                    statusUtil.info("processing", line(s"adopted $qualified at ${n(p)}"))
                    Right(ok.copy(adopted = Some(p)))
                case RestAdoptDecision.AdoptCatalog =>
                    statusUtil.info("processing", line(s"$qualified is in the catalog at ${n(catalogHas.orNull)}; committing through it"))
                    Right(ok)
                case RestAdoptDecision.RefuseBehind(c, p) =>
                    closeable.foreach(x => Try(x.close()))
                    Left(
                        Fallback(
                            s"Unity Catalog points at an older metadata file of this table (${n(c)}); the table has moved on to ${n(p)}. " +
                                s"Datris never drops a table: an admin drops $qualified in Unity Catalog; the next run re-registers it"
                        )
                    )
                case RestAdoptDecision.RefuseForeign(c) =>
                    closeable.foreach(x => Try(x.close()))
                    Left(
                        Fallback(
                            s"$qualified already exists in Unity Catalog and points at ${n(c)}, outside this pipeline's table ${n(outputPath)}; " +
                                "refusing to touch it (never merge, never drop); rename the pipeline or choose another schema"
                        )
                    )
            }
        } catch {
            case t: Throwable if NonFatal(t) || t.isInstanceOf[LinkageError] =>
                catalog match {
                    case c: Closeable => Try(c.close())
                    case _ =>
                }
                val uri = props.getOrElse("uri", "")
                val root = IcebergCatalogRegistrar.normalize(outputPath)
                val msg = t match {
                    case e: NotAuthorizedException => IcebergCatalogRegistrar.describe(401, oneLine(e.getMessage), uri, uc.catalog, schema, root)
                    case e: ForbiddenException => IcebergCatalogRegistrar.describe(403, oneLine(e.getMessage), uri, uc.catalog, schema, root)
                    case _: NoSuchNamespaceException =>
                        s"schema ${uc.catalog}.$schema does not exist in the catalog at $uri; create it (Datris never creates schemas)"
                    case other =>
                        "Unity Catalog REST catalog at " + uri + " failed for " + qualified + ": " +
                            oneLine(Option(other.getMessage).getOrElse(other.getClass.getSimpleName))
                }
                Left(Fallback(msg))
        }
    }

    /** Write the state doc for this run (before the `end` line). Never throws. */
    def record(jobContext: JobContext, result: Option[IcebergWriter.WriteResult], plan: Plan): Unit = {
        if (!plan.active) return
        val config = jobContext.config
        val statusUtil = jobContext.statusUtil
        try {
            val now = Instant.now().toString
            val base = Option(plan.previous).getOrElse(
                UnityCatalogSyncState(
                    pipeline = config.name,
                    lastSyncAt = null,
                    lastRunId = null,
                    commentsHash = null,
                    tagsHash = null,
                    propertiesHash = null,
                    lastError = null
                )
            )
            // uc-register: lines (story-4 stale warning and failures) no longer apply.
            val others = Option(base.lastError).toSeq.flatMap(_.split("\n"))
                .filter(l => l.nonEmpty && !l.startsWith(IcebergCatalogRegistrar.ErrorPrefix) && !l.startsWith(ErrorPrefix))
            val keptErrors = if (others.isEmpty) null else others.mkString("\n")
            val state = plan.target match {
                case Some(_) =>
                    val loc = result.map(_.metadataLocation).orNull
                    if (loc != null) {
                        val verb = if (plan.created) "created" else "committed"
                        statusUtil.info(
                            "processing",
                            line(s"$verb ${plan.qualified} at ${plan.location} through the catalog; current metadata ${IcebergCatalogRegistrar.normalize(loc)}")
                        )
                    }
                    base.copy(
                        pipeline = config.name,
                        catalogMode = "rest",
                        restMetadataLocation = Option(loc).getOrElse(base.restMetadataLocation),
                        lastRestCommitAt = now,
                        restRefusedReason = null,
                        registeredMetadataLocation = Option(loc).getOrElse(base.registeredMetadataLocation),
                        lastRegisterAt = Option(base.lastRegisterAt).getOrElse(now),
                        lastError = keptErrors
                    )
                case None =>
                    base.copy(pipeline = config.name, catalogMode = "refused", restRefusedReason = plan.refused.orNull, lastError = keptErrors)
            }
            try UnityCatalogSyncIO.write(state)
            catch { case NonFatal(e) => logger.warn("uc-rest state write failed for " + config.name + ": " + e.getMessage) }
        } catch {
            case NonFatal(e) => logger.warn("uc-rest record failed for " + config.name + ": " + e.getMessage)
        }
    }
}
