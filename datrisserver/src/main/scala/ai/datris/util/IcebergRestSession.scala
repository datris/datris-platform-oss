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

    /** A state-read failure propagates: without the doc the run cannot tell
      * whether a path write would fork a catalog-committed table. */
    private def readState(pipeline: String): UnityCatalogSyncState =
        try UnityCatalogSyncIO.read(pipeline)
        catch {
            case NonFatal(e) =>
                val ex = new DatrisException(
                    "could not read the Unity Catalog state for pipeline " + pipeline + " (" + oneLine(e.getMessage) +
                        "); refusing to write so a table committed through the catalog is never written by path"
                )
                ex.initCause(e)
                throw ex
        }

    /** The table at `tableRoot` was committed through the catalog on an
      * earlier run (the last catalog commit lives under its metadata/). A new
      * prefix is a new table, so the guard does not follow the pipeline there. */
    def restCommitted(previous: UnityCatalogSyncState, tableRoot: String): Boolean =
        previous != null && previous.catalogMode == "rest" && previous.restMetadataLocation != null &&
            IcebergCatalogRegistrar.classify(previous.restMetadataLocation, null, tableRoot) != IcebergCatalogRegistrar.Foreign

    def switchBackMessage(qualified: String): String =
        "switching from catalogMode rest back to register (or turning Unity Catalog off for this pipeline) is not supported: " +
            "the table's current metadata is only known to the catalog; keep rest, or start a new prefix and have an admin drop " + qualified +
            " in Unity Catalog"

    def deleteBeforeWriteMessage(qualified: String): String =
        "deleteBeforeWrite cannot be used on a table Unity Catalog holds at this prefix (committed through the catalog, or registered at it); drop " +
            qualified +
            " in Unity Catalog (or use a new prefix) first"

    private def committedMessage(previous: UnityCatalogSyncState, why: String, qualified: String): String =
        "this table is committed through Unity Catalog (last at " + IcebergCatalogRegistrar.normalize(previous.restMetadataLocation) + "); " +
            why + "; a path-based write would fork its history. Fix the catalog connection, or point the pipeline at a new prefix " +
            "and have an admin drop " + qualified + " in Unity Catalog"

    /** `<catalog>.<schema>.<table>` for messages; placeholders when the
      * unityCatalog block is gone. */
    private[util] def qualifiedFor(config: ai.datris.model.PipelineConfig): String = {
        val uc = config.unityCatalog
        val cat = Option(uc).flatMap(u => Option(u.catalog)).map(_.trim).filter(_.nonEmpty).getOrElse("<catalog>")
        val sch = Option(uc).map(_.schemaOrDefault).getOrElse("<schema>")
        cat + "." + sch + "." + Option(config.name).map(IcebergCatalogRegistrar.tableName).getOrElse("<table>")
    }

    /** Run-failing guard, pure: a table committed through the catalog at
      * `tableRoot` must not be deleted (deleteBeforeWrite) nor written by any
      * path-based writer (rest not active, or a non-Iceberg format), whatever
      * the rest of the config says. None = no objection. */
    def guardFailure(
        previous: UnityCatalogSyncState,
        tableRoot: String,
        iceberg: Boolean,
        deleteBeforeWrite: Boolean,
        restActive: Boolean,
        qualified: String
    ): Option[String] = {
        val committed = restCommitted(previous, tableRoot)
        if (committed && deleteBeforeWrite) Some(deleteBeforeWriteMessage(qualified))
        else if (committed && (!iceberg || !restActive)) Some(switchBackMessage(qualified))
        else None
    }

    /** Raised inside `open` when the catalog holds the table and the run
      * would delete it first; rethrown by `prepare` as a run failure. */
    private class DeleteRefused(message: String) extends DatrisException(message)

    /** deleteBeforeWrite is refused when the catalog's table lives under our
      * root (deleting the prefix would leave it pointing at deleted
      * metadata). A foreign catalog table is not ours: deleting our own
      * prefix is fine, and the run is then refused and writes by path. */
    def deleteRefused(deleteBeforeWrite: Boolean, catalogHas: Option[String], tableRoot: String): Boolean =
        deleteBeforeWrite && catalogHas.exists(c => IcebergCatalogRegistrar.classify(c, null, tableRoot) != IcebergCatalogRegistrar.Foreign)

    val RegisterUnsupportedMessage =
        "the catalog cannot register an existing path table; start from a new prefix (or set deleteBeforeWrite once — allowed because " +
            "this table was never committed through the catalog) so Datris can create it through the catalog"

    /** The catalog has no `register` verb: Databricks answers 404
      * ENDPOINT_NOT_FOUND ("No API found for 'POST .../register'"), and
      * Iceberg's REST client raises UnsupportedOperationException when the
      * server does not advertise the endpoint. Walks the cause chain. */
    def registerUnsupported(t: Throwable): Boolean = {
        var cur = t
        var depth = 0
        while (cur != null && depth < 10) {
            if (cur.isInstanceOf[UnsupportedOperationException]) return true
            val m = Option(cur.getMessage).getOrElse("").toLowerCase
            if (m.contains("endpoint_not_found") || m.contains("no api found") || m.contains("does not support endpoint")) return true
            cur = cur.getCause
            depth += 1
        }
        false
    }

    /** Build the pipeline's RESTCatalog (caller closes it). */
    private[util] def buildCatalog(config: ai.datris.model.PipelineConfig, creds: ResolvedDatabricksCredentials): (Catalog, Map[String, String], String) = {
        val uc = config.unityCatalog
        val props = IcebergRestCatalogConfig.catalogProperties(creds, creds.extra, uc.catalog)
        val sparkName = IcebergRestCatalogConfig.sparkCatalogName(config.name, props)
        val hadoopConf = SparkSessionManager.getOrCreate().sparkContext.hadoopConfiguration
        (CatalogUtil.buildIcebergCatalog(sparkName, (props + ("type" -> "rest")).asJava, hadoopConf), props, sparkName)
    }

    private def closeQuietly(c: Catalog): Unit = c match {
        case x: Closeable => Try(x.close())
        case _ =>
    }

    /** The catalog's current metadata file for this pipeline's table (None
      * when the catalog has no such table). Throws on any catalog failure.
      * Used by readers of a catalog-committed table. */
    def catalogCurrentMetadata(config: ai.datris.model.PipelineConfig): Option[String] = {
        val uc = config.unityCatalog
        val creds = CredentialResolver.resolveDatabricks(uc.credentialsSecret, requireCredentials = false)
        val (catalog, _, _) = buildCatalog(config, creds)
        try {
            val ident = IcebergRestCatalogConfig.identifier(uc.schemaOrDefault, config.name)
            if (!catalog.tableExists(ident)) None
            else
                catalog.loadTable(ident) match {
                    case h: HasTableOperations => Option(h.operations().current()).map(_.metadataFileLocation())
                    case _ => None
                }
        } finally closeQuietly(catalog)
    }

    /** The writer's catalog create lost to another create of the same
      * identifier (AlreadyExists). Re-decide against the now-existing
      * catalog table: under our root ⇒ retry through the catalog; anywhere
      * else ⇒ refused (warning + audit) and the run writes by path. The plan
      * is new-table only, so no path history can fork. */
    def afterCreateConflict(jobContext: JobContext, plan: Plan, cause: Throwable): Plan = {
        val statusUtil = jobContext.statusUtil
        val config = jobContext.config
        val target = plan.target.getOrElse(throw cause)
        val catalogHas =
            try
                target.catalog.loadTable(target.ident) match {
                    case h: HasTableOperations => Option(h.operations().current()).map(_.metadataFileLocation())
                    case _ => None
                }
            catch { case NonFatal(_) => None }
        RestAdoptDecision.decide(catalogHas, None, plan.location) match {
            case RestAdoptDecision.AdoptCatalog =>
                statusUtil.info("processing", line(s"${plan.qualified} was created concurrently at this table's location; committing through it"))
                plan.copy(created = false)
            case other =>
                val where = other match {
                    case RestAdoptDecision.RefuseForeign(c) => IcebergCatalogRegistrar.normalize(c)
                    case _ => "an unknown location"
                }
                val msg = line(
                    s"${plan.qualified} was created in Unity Catalog by someone else while this run was creating it, and points at $where, " +
                        s"outside this pipeline's table ${IcebergCatalogRegistrar.normalize(plan.location)}; refusing to touch it (never merge, never drop); " +
                        "rename the pipeline or choose another schema; falling back to the path-based write"
                )
                statusUtil.warn("processing", msg)
                logger.warn("uc-rest create conflict for pipeline " + config.name + ": " + oneLine(cause.getMessage))
                audit(config.name, msg)
                plan.close()
                plan.copy(target = None, refused = Some(msg), created = false, closeable = None)
        }
    }

    /** Guards and catalog session for one run, called under the write lock
      * BEFORE the loader's deleteBeforeWrite. Throws (a run failure) for the
      * switch-back refusal, deleteBeforeWrite on a table the catalog holds,
      * an unreadable state doc, and when a table already committed through
      * the catalog cannot be committed through it this run. */
    def prepare(jobContext: JobContext, outputPath: String, deleteBeforeWrite: Boolean): Plan = {
        val config = jobContext.config
        val statusUtil = jobContext.statusUtil
        val objectStore = if (config.destination != null) config.destination.objectStore else null
        if (objectStore == null) return Inactive
        val iceberg = objectStore.fileFormat != null && objectStore.fileFormat.trim.equalsIgnoreCase("iceberg")
        // The loader calls this for every Iceberg write and every
        // deleteBeforeWrite: the fork/delete guard holds even when the
        // unityCatalog block was switched off or removed, or the format changed.
        if (!iceberg && !deleteBeforeWrite) return Inactive

        val previous = readState(config.name)
        val forkRisk = restCommitted(previous, outputPath)
        val uc = config.unityCatalog
        val restActive = uc != null && uc.enabled && uc.registerOn && uc.restMode
        val qualified = qualifiedFor(config)
        guardFailure(previous, outputPath, iceberg, deleteBeforeWrite, restActive, qualified).foreach(m => throw new DatrisException(m))
        if (!iceberg || !restActive) return Inactive
        val base = Plan(active = true, target = None, refused = None, previous = previous, qualified = qualified, location = outputPath)

        if (!UnityCatalogMetadataSync.switchedOn) {
            if (forkRisk)
                throw new DatrisException(committedMessage(previous, "Unity Catalog sync is switched off (DATRIS_UNITY_CATALOG_SYNC=false)", qualified))
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
                else open(jobContext, outputPath, creds, previous, statusUtil, base, deleteBeforeWrite)
            } catch {
                case d: DeleteRefused => throw d
                case t: Throwable if NonFatal(t) || t.isInstanceOf[LinkageError] =>
                    Left(Fallback("Unity Catalog REST catalog could not be opened: " + oneLine(Option(t.getMessage).getOrElse(t.getClass.getSimpleName))))
            }

        opened match {
            case Right(plan) => plan
            case Left(Fallback(reason)) =>
                if (forkRisk) {
                    audit(config.name, line(reason))
                    throw new DatrisException(committedMessage(previous, "the catalog is not usable this run (" + oneLine(reason) + ")", qualified))
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
        base: Plan,
        deleteBeforeWrite: Boolean = false
    ): Either[Fallback, Plan] = {
        val config = jobContext.config
        val uc = config.unityCatalog
        val schema = uc.schemaOrDefault
        val qualified = base.qualified
        var props: Map[String, String] = Map.empty
        var sparkName: String = null
        val ident = IcebergRestCatalogConfig.identifier(schema, config.name)
        var catalog: Catalog = null
        try {
            val spark = SparkSessionManager.getOrCreate()
            // The per-bucket s3a keys (ObjectStoreSpark.applyPerBucketConfig)
            // live in the session's Hadoop conf; SparkCatalog gets the same one.
            val built = buildCatalog(config, creds)
            catalog = built._1
            props = built._2
            sparkName = built._3

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
            // The loader deletes the prefix after this call: never leave the
            // catalog pointing at deleted metadata, never adopt what is about
            // to be deleted.
            if (deleteRefused(deleteBeforeWrite, catalogHas, outputPath)) throw new DeleteRefused(deleteBeforeWriteMessage(qualified))

            val tables = new HadoopTables(spark.sessionState.newHadoopConf())
            val pathCurrent =
                if (!deleteBeforeWrite && tables.exists(outputPath))
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

            RestAdoptDecision.decide(catalogHas, pathCurrent, outputPath, history, restCommitted(previous, outputPath)) match {
                case RestAdoptDecision.CreateNew =>
                    statusUtil.info("processing", line(s"$qualified is new; creating it through the catalog at $outputPath"))
                    Right(ok.copy(created = true))
                case RestAdoptDecision.AdoptPath(p) =>
                    val registered =
                        try { catalog.registerTable(ident, n(p)); true }
                        catch { case e: Exception if registerUnsupported(e) => false }
                    if (registered) {
                        statusUtil.info("processing", line(s"adopted $qualified at ${n(p)}"))
                        Right(ok.copy(adopted = Some(p)))
                    } else {
                        // No register verb (Databricks): adopting is impossible;
                        // say how to get a catalog-created table instead.
                        closeable.foreach(x => Try(x.close()))
                        Left(Fallback(RegisterUnsupportedMessage))
                    }
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
            case d: DeleteRefused =>
                catalog match {
                    case c: Closeable => Try(c.close())
                    case _ =>
                }
                throw d
            case t: Throwable if NonFatal(t) || t.isInstanceOf[LinkageError] =>
                catalog match {
                    case c: Closeable => Try(c.close())
                    case _ =>
                }
                val uri = Try(IcebergRestCatalogConfig.catalogProperties(creds, creds.extra, uc.catalog).getOrElse("uri", "")).getOrElse("")
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
            catch {
                case NonFatal(e) =>
                    logger.warn("uc-rest state write failed for " + config.name + ": " + e.getMessage)
                    Try(statusUtil.warn(
                        "processing",
                        line("could not record the catalog commit in the state doc: " + Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
                    ))
            }
        } catch {
            case NonFatal(e) => logger.warn("uc-rest record failed for " + config.name + ": " + e.getMessage)
        }
    }
}
