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
  * line.
  *
  * `catalogMode: managed` (Databricks) shares the session: the catalog
  * chooses the table's location (it must be in the pipeline's bucket),
  * `prefixKey` holds no data, only a table this pipeline recorded is adopted,
  * and ANY refusal or pre-commit failure fails the run (there is no path
  * write to fall back to). A managed-committed table is never switched to
  * another mode, nor a rest-committed one to managed. */
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
        closeable: Option[Closeable] = None,
        // catalogMode managed: the catalog chooses the location in `bucket`.
        managed: Boolean = false,
        bucket: String = null
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
        previous != null && previous.catalogMode == "rest" && previous.restMetadataLocation != null && previous.lastRestCommitAt != null &&
            IcebergCatalogRegistrar.classify(previous.restMetadataLocation, null, tableRoot) != IcebergCatalogRegistrar.Foreign

    /** An earlier `catalogMode: managed` run committed this pipeline's table
      * through the catalog. Root-independent: a managed table lives where the
      * catalog put it, not under prefixKey, so the guard follows the pipeline
      * to a new prefix. */
    def managedCommitted(previous: UnityCatalogSyncState): Boolean =
        previous != null && previous.catalogMode == "managed" && previous.restMetadataLocation != null && previous.lastRestCommitAt != null

    /** `catalogLoc` (a metadata file) belongs to the table this pipeline's
      * managed commit recorded: same table directory (`<table>/metadata/`),
      * s3/s3a/s3n equal. A managed commit says nothing about any other table. */
    def managedRecordedTable(catalogLoc: String, previous: UnityCatalogSyncState): Boolean =
        managedCommitted(previous) && catalogLoc != null &&
            IcebergWriter.sameRestLocation(tableLocationOf(catalogLoc), tableLocationOf(previous.restMetadataLocation))

    def switchFromManagedMessage(qualified: String): String =
        qualified + " is a Unity Catalog managed table (catalogMode managed) committed by this pipeline; its current metadata and files are " +
            "only known to the catalog, so it cannot be written by path or in another catalogMode. Set catalogMode managed again, or have an " +
            "admin drop " + qualified + " in Unity Catalog first (Datris never converts a managed table)"

    def managedDeleteBeforeWriteMessage(qualified: String): String =
        "deleteBeforeWrite cannot be used on " + qualified + ": it is a Unity Catalog managed table (catalogMode managed) whose files belong to " +
            "the catalog; drop " + qualified + " in Unity Catalog first"

    /** catalogMode managed needs the pipeline's own S3 bucket: Databricks
      * cannot place a managed table in the built-in MinIO store. MinIO is
      * allowed only with a custom `icebergRestPath` on the Unity Catalog
      * secret (a non-Databricks catalog). `provider` absent means MinIO. */
    def managedProviderRefusal(provider: String, secretFields: Map[String, String], secretName: String): Option[String] = {
        val p = Option(provider).map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("minio")
        if (p == "minio" && !IcebergCatalogRegistrar.hasCustomRestPath(secretFields))
            Some(
                "catalogMode managed needs objectStore.provider 's3' with a Databricks secret: Unity Catalog cannot place a managed table in the " +
                    "built-in MinIO store (secret " + secretName + " has no icebergRestPath)"
            )
        else None
    }

    def switchBackMessage(qualified: String): String =
        "this prefix holds a table that was committed through Unity Catalog (catalogMode rest) but this pipeline is not in rest mode; " +
            "writing it by path is not supported because the table's current metadata is only known to the catalog. Set catalogMode rest, " +
            "or start a new prefix and have an admin drop " + qualified + " in Unity Catalog"

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
      * the rest of the config says. `restActive` = this run commits through
      * the catalog (rest OR managed), `managedActive` = it is in managed mode.
      * A managed-committed table (any root) must stay managed and is never
      * deleted; a rest-committed one never switches to managed, unless the
      * pipeline has no usable unityCatalog block (`hasCatalogBlock` false):
      * it cannot reach the managed table, which lives outside its prefix,
      * so there is nothing to protect. None = no objection. */
    def guardFailure(
        previous: UnityCatalogSyncState,
        tableRoot: String,
        iceberg: Boolean,
        deleteBeforeWrite: Boolean,
        restActive: Boolean,
        qualified: String,
        managedActive: Boolean = false,
        hasCatalogBlock: Boolean = true
    ): Option[String] = {
        val committed = restCommitted(previous, tableRoot)
        val managed = managedCommitted(previous) && hasCatalogBlock
        if (managed && deleteBeforeWrite) Some(managedDeleteBeforeWriteMessage(qualified))
        else if (managed && (!iceberg || !restActive || !managedActive)) Some(switchFromManagedMessage(qualified))
        else if (committed && deleteBeforeWrite) Some(deleteBeforeWriteMessage(qualified))
        else if (committed && (!iceberg || !restActive || managedActive)) Some(switchBackMessage(qualified))
        else None
    }

    /** Raised inside `open` when the catalog holds the table and the run
      * would delete it first; rethrown by `prepare` as a run failure. */
    private class DeleteRefused(message: String) extends DatrisException(message)

    /** State after a managed-mode run failed before writing (decision 3): the
      * reason goes to `restRefusedReason` so the state endpoint reads `error`.
      * A managed-committed doc keeps its commit fields (the table is still
      * ours); otherwise the doc says `refused`. `restCreatedTable` is kept. */
    def managedRefusedState(previous: UnityCatalogSyncState, pipeline: String, reason: String): UnityCatalogSyncState = {
        val base = Option(previous).getOrElse(UnityCatalogSyncState(pipeline, null, null, null, null, null, null)).copy(pipeline = pipeline)
        if (managedCommitted(previous)) base.copy(restRefusedReason = reason)
        else base.copy(catalogMode = "refused", restRefusedReason = reason)
    }

    /** Never throws. */
    private def recordManagedRefusal(pipeline: String, previous: UnityCatalogSyncState, reason: String): Unit =
        try UnityCatalogSyncIO.write(managedRefusedState(previous, pipeline, reason))
        catch { case NonFatal(e) => logger.warn("uc-rest state write failed for " + pipeline + ": " + e.getMessage) }

    /** A managed-mode refusal before any write (provider rule): rethrown by
      * `prepare` as a run failure, never turned into a Fallback. */
    private class ManagedRefused(message: String) extends DatrisException(message)

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

    /** State written right after a catalog create or adopt, before the data
      * write: the catalog now holds the table at `metadataLocation`. */
    def interimState(
        previous: UnityCatalogSyncState,
        pipeline: String,
        metadataLocation: String,
        now: String,
        mode: String = "rest"
    ): UnityCatalogSyncState =
        Option(previous)
            .getOrElse(UnityCatalogSyncState(pipeline, null, null, null, null, null, null))
            .copy(
                pipeline = pipeline,
                catalogMode = mode,
                restMetadataLocation = metadataLocation,
                lastRestCommitAt = now,
                restRefusedReason = null,
                restCreatedTable = null
            )

    private def metadataOf(t: org.apache.iceberg.Table): String = t match {
        case h: HasTableOperations => Option(h.operations().current()).map(_.metadataFileLocation()).orNull
        case _ => null
    }

    private def recordInterim(pipeline: String, previous: UnityCatalogSyncState, metadataLocation: String, mode: String = "rest"): Unit =
        if (metadataLocation != null)
            try UnityCatalogSyncIO.write(interimState(previous, pipeline, metadataLocation, Instant.now().toString, mode))
            catch { case NonFatal(e) => logger.warn("uc-rest interim state write failed for " + pipeline + ": " + e.getMessage) }

    /** What to do about a foreign catalog table of our name. After an
      * earlier refused run it is most likely the managed table a catalog
      * that ignores the requested location created for us, and renaming
      * would just create another one. */
    def foreignAdvice(previous: UnityCatalogSyncState, qualified: String, catalogLocation: String = null): String =
        if (
            Option(catalogLocation).exists(_.contains("/__unitystorage/")) ||
            (previous != null && (previous.catalogMode == "refused" || previous.restCreatedTable != null))
        )
            "if this table was created by an earlier Datris run against a catalog that ignores the requested location (Databricks managed tables), " +
                s"renaming will not help: have an admin drop $qualified; for governed Databricks tables use the Databricks destination"
        else "rename the pipeline or choose another schema"

    /** State after a catalog create whose location was refused: the table
      * exists in the catalog (`restCreatedTable`) but was never written to. */
    def createdRefusedState(previous: UnityCatalogSyncState, pipeline: String, qualified: String, reason: String): UnityCatalogSyncState =
        Option(previous)
            .getOrElse(UnityCatalogSyncState(pipeline, null, null, null, null, null, null))
            .copy(pipeline = pipeline, catalogMode = "refused", restRefusedReason = reason, restCreatedTable = qualified)

    /** The warning for a catalog table outside the pipeline's table root. */
    def outsideRootMessage(qualified: String, tableLocation: String, root: String): String =
        s"the catalog placed $qualified at ${IcebergCatalogRegistrar.normalize(tableLocation)}, outside this pipeline's table root " +
            s"${IcebergCatalogRegistrar.normalize(root)} (Databricks creates managed Iceberg tables and ignores the requested location); " +
            s"Datris will not write there. Have an admin drop $qualified; a catalog that honours the requested location is needed for " +
            "catalogMode rest (for governed Databricks tables, use the Databricks destination); falling back to the path-based write"

    /** The writer refused the catalog's table before writing anything
      * (RestLocationRefused): warn + audit, close the catalog, and return a
      * refused plan so the run writes by path. Nothing was committed through
      * the catalog, so no path history can fork. */
    def afterLocationRefused(jobContext: JobContext, plan: Plan, refused: IcebergWriter.RestLocationRefused): Plan = {
        if (plan.managed) failManagedLocation(jobContext, plan, refused)
        val msg = line(outsideRootMessage(plan.qualified, refused.tableLocation, plan.location))
        // Defensive: a table already committed through the catalog must never
        // be written by path (it would fork its history).
        if (restCommitted(plan.previous, plan.location)) {
            audit(jobContext.config.name, msg)
            plan.close()
            throw new DatrisException(committedMessage(plan.previous, "the catalog's table is now outside this pipeline's table root", plan.qualified))
        }
        jobContext.statusUtil.warn("processing", msg)
        logger.warn("uc-rest location refused for pipeline " + jobContext.config.name + ": " + refused.getMessage)
        audit(jobContext.config.name, msg)
        plan.close()
        // The catalog created the table (and still holds it): record that
        // now, so a later failure in this run still leaves it known.
        val previous =
            if (refused.created) {
                val st = createdRefusedState(plan.previous, jobContext.config.name, plan.qualified, msg)
                try UnityCatalogSyncIO.write(st)
                catch { case NonFatal(e) => logger.warn("uc-rest state write failed for " + jobContext.config.name + ": " + e.getMessage) }
                st
            } else plan.previous
        plan.copy(target = None, refused = Some(msg), created = false, closeable = None, previous = previous)
    }

    /** The warning for a managed table the catalog placed outside the
      * pipeline's bucket. */
    def outsideBucketMessage(qualified: String, tableLocation: String, bucket: String): String =
        s"the catalog placed $qualified at ${IcebergCatalogRegistrar.normalize(tableLocation)}, outside the pipeline's bucket s3://$bucket/; " +
            s"catalogMode managed only writes in the pipeline's bucket (its S3 secret covers that bucket). Give the schema a MANAGED LOCATION " +
            s"in s3://$bucket/ (or point the pipeline at the schema's bucket) and have an admin drop $qualified; the run fails, nothing was written"

    /** Managed mode: the writer refused the catalog's table before writing
      * anything. Never a path write: record a catalog-created table, audit,
      * close the catalog, and fail the run. */
    private def failManagedLocation(jobContext: JobContext, plan: Plan, refused: IcebergWriter.RestLocationRefused): Nothing = {
        val msg = line(outsideBucketMessage(plan.qualified, refused.tableLocation, plan.bucket))
        logger.warn("uc-rest managed location refused for pipeline " + jobContext.config.name + ": " + refused.getMessage)
        audit(jobContext.config.name, msg)
        plan.close()
        if (refused.created)
            try UnityCatalogSyncIO.write(createdRefusedState(plan.previous, jobContext.config.name, plan.qualified, msg))
            catch { case NonFatal(e) => logger.warn("uc-rest state write failed for " + jobContext.config.name + ": " + e.getMessage) }
        else recordManagedRefusal(jobContext.config.name, plan.previous, msg)
        val ex = new DatrisException(msg)
        ex.initCause(refused)
        throw ex
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
        if (plan.managed) {
            // Ours only when the state doc now records it (another run of this
            // pipeline created it); anything else fails the run.
            val now = Try(UnityCatalogSyncIO.read(config.name)).getOrElse(plan.previous)
            RestAdoptDecision.decideManaged(catalogHas, now, plan.qualified, plan.bucket) match {
                case RestAdoptDecision.AdoptCatalog =>
                    statusUtil.info("processing", line(s"${plan.qualified} was created concurrently by this pipeline; committing through it"))
                    return plan.copy(created = false, previous = now)
                case _ =>
                    val msg = line(
                        s"${plan.qualified} was created in Unity Catalog by someone else while this run was creating it" +
                            catalogHas.map(c => " (at " + IcebergCatalogRegistrar.normalize(c) + ")").getOrElse("") +
                            "; catalogMode managed never adopts a table Datris did not create; have an admin drop " + plan.qualified +
                            " or choose another schema"
                    )
                    logger.warn("uc-rest managed create conflict for pipeline " + config.name + ": " + oneLine(cause.getMessage))
                    audit(config.name, msg)
                    recordManagedRefusal(config.name, now, msg)
                    plan.close()
                    throw new DatrisException(msg)
            }
        }
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
                        foreignAdvice(plan.previous, plan.qualified, catalogHas.orNull) + "; falling back to the path-based write"
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

        var previous = readState(config.name)
        var forkRisk = restCommitted(previous, outputPath) || managedCommitted(previous)
        val uc = config.unityCatalog
        // A leftover doc (the pipeline was deleted out of band and recreated)
        // says "committed" while neither the catalog nor the prefix has the
        // table: ignore it rather than refuse a brand-new table.
        // The cleared doc is persisted here (under the write lock) so the
        // register hook, which re-reads the doc after the write, and every
        // later run see it; otherwise a register-mode run would write the
        // stale fields back and the next run would refuse.
        UnityCatalogStaleState.clearIfStale(
            previous,
            outputPath,
            () => UnityCatalogStaleState.catalogHasTable(config),
            () => UnityCatalogStaleState.prefixHasMetadata(outputPath),
            UnityCatalogSyncIO.write
        ).foreach { cleared =>
            statusUtil.warn("processing", line(UnityCatalogStaleState.staleWarning(previous, qualifiedFor(config))))
            previous = cleared
            forkRisk = false
        }
        // A managed table lives outside the prefix: a pipeline without a
        // usable unityCatalog block (the block was removed) cannot reach it
        // and a path write cannot fork it. Forget it, say where it is.
        val hasCatalogBlock = UnityCatalogStaleState.hasCatalogBlock(uc)
        if (managedCommitted(previous) && !hasCatalogBlock) {
            statusUtil.warn("processing", line(UnityCatalogStaleState.staleWarning(previous, null)))
            val cleared = UnityCatalogStaleState.withoutRestCommit(previous)
            UnityCatalogSyncIO.write(cleared)
            previous = cleared
            forkRisk = restCommitted(previous, outputPath)
        }
        // Commits through the catalog this run: rest or managed.
        val restActive = uc != null && uc.enabled && uc.registerOn && uc.throughCatalog
        val managedActive = restActive && uc.managedMode
        val qualified = qualifiedFor(config)
        guardFailure(previous, outputPath, iceberg, deleteBeforeWrite, restActive, qualified, managedActive, hasCatalogBlock).foreach { m =>
            if (managedActive || managedCommitted(previous)) audit(config.name, line(m))
            // So the state endpoint reads `error` (not "synced") while the
            // guard refuses runs; commit fields are kept. The next run that
            // commits clears the reason.
            if (previous != null) {
                val refusedDoc =
                    if (managedCommitted(previous)) managedRefusedState(previous, config.name, line(m)) else previous.copy(restRefusedReason = line(m))
                try UnityCatalogSyncIO.write(refusedDoc)
                catch { case NonFatal(e) => logger.warn("uc-rest state write failed for " + config.name + ": " + e.getMessage) }
            }
            throw new DatrisException(m)
        }
        if (!iceberg || !restActive) return Inactive
        val bucket = if (managedActive) ObjectStoreSpark.resolveBucket(objectStore) else null
        val base = Plan(
            active = true,
            target = None,
            refused = None,
            previous = previous,
            qualified = qualified,
            location = outputPath,
            managed = managedActive,
            bucket = bucket
        )

        if (!UnityCatalogMetadataSync.switchedOn) {
            if (managedActive) {
                val msg = "Unity Catalog sync is switched off (DATRIS_UNITY_CATALOG_SYNC=false); catalogMode managed writes only through the catalog, " +
                    "so the run fails (nothing was written)"
                recordManagedRefusal(config.name, previous, line(msg))
                throw new DatrisException(msg)
            }
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
                else {
                    // Before any write: Databricks cannot place a managed table in MinIO.
                    if (managedActive)
                        managedProviderRefusal(objectStore.provider, creds.extra, uc.credentialsSecret).foreach(m => throw new ManagedRefused(m))
                    open(jobContext, outputPath, creds, previous, statusUtil, base, deleteBeforeWrite)
                }
            } catch {
                case d: DeleteRefused => throw d
                case m: ManagedRefused =>
                    audit(config.name, line(m.getMessage))
                    recordManagedRefusal(config.name, previous, line(m.getMessage))
                    throw m
                case t: Throwable if NonFatal(t) || t.isInstanceOf[LinkageError] =>
                    Left(Fallback("Unity Catalog REST catalog could not be opened: " + oneLine(Option(t.getMessage).getOrElse(t.getClass.getSimpleName))))
            }

        opened match {
            case Right(plan) => plan
            case Left(Fallback(reason)) if managedActive =>
                // Decision 3: managed mode never writes by path (prefixKey holds
                // no data and readers follow the catalog), so a refusal fails.
                val msg = line(reason + "; catalogMode managed never writes by path, so the run fails (nothing was written)")
                logger.warn("uc-rest managed refusal for pipeline " + config.name + ": " + reason)
                audit(config.name, msg)
                recordManagedRefusal(config.name, previous, msg)
                throw new DatrisException(msg)
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

            if (base.managed) return openManaged(config.name, catalog, ident, sparkName, props, catalogHas, previous, statusUtil, base)

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

            // Record a catalog-created table before any data is written, so a
            // failure later in the run still leaves it known (delete advice,
            // fork guard). Never throws.
            val onCreated: org.apache.iceberg.Table => Unit = t => recordInterim(config.name, previous, metadataOf(t))
            val target = IcebergWriter.RestTarget(sparkName, ident, catalog, IcebergRestCatalogConfig.sparkConf(sparkName, props), onCreated)
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
                        recordInterim(config.name, previous, n(p))
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
                                "refusing to touch it (never merge, never drop); " + foreignAdvice(previous, qualified, c)
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

    /** catalogMode managed: no path table, no prefix delete guard (the prefix
      * holds nothing). Create through the catalog (it chooses the location in
      * the pipeline's bucket) or adopt a table this pipeline recorded;
      * anything else is a Fallback, which `prepare` turns into a run failure. */
    private def openManaged(
        pipeline: String,
        catalog: Catalog,
        ident: org.apache.iceberg.catalog.TableIdentifier,
        sparkName: String,
        props: Map[String, String],
        catalogHas: Option[String],
        previous: UnityCatalogSyncState,
        statusUtil: StatusUtil,
        base: Plan
    ): Either[Fallback, Plan] = {
        val qualified = base.qualified
        val n = IcebergCatalogRegistrar.normalize _
        val onCreated: org.apache.iceberg.Table => Unit = t => recordInterim(pipeline, previous, metadataOf(t), "managed")
        val target = IcebergWriter.RestTarget(
            sparkName,
            ident,
            catalog,
            IcebergRestCatalogConfig.sparkConf(sparkName, props),
            onCreated,
            managed = true,
            bucket = base.bucket
        )
        val closeable = catalog match {
            case c: Closeable => Some(c)
            case _ => None
        }
        val ok = base.copy(target = Some(target), closeable = closeable)
        RestAdoptDecision.decideManaged(catalogHas, previous, qualified, base.bucket) match {
            case RestAdoptDecision.CreateNew =>
                statusUtil.info(
                    "processing",
                    line(s"$qualified is new; creating it through the catalog (the catalog chooses the location in s3://${base.bucket}/)")
                )
                Right(ok.copy(created = true))
            case RestAdoptDecision.AdoptCatalog =>
                statusUtil.info("processing", line(s"$qualified is in the catalog at ${n(catalogHas.orNull)}; committing through it"))
                Right(ok)
            case other =>
                closeable.foreach(x => Try(x.close()))
                val c = other match {
                    case RestAdoptDecision.RefuseForeign(loc) => loc
                    case _ => catalogHas.orNull
                }
                Left(
                    Fallback(
                        if (c != null && !IcebergWriter.inBucket(c, base.bucket))
                            s"$qualified already exists in Unity Catalog at ${n(c)}, outside the pipeline's bucket s3://${base.bucket}/; " +
                                s"catalogMode managed only writes in the pipeline's bucket; have an admin drop $qualified or choose another schema"
                        else
                            s"$qualified already exists in Unity Catalog at ${n(c)} and this pipeline did not create it; " +
                                s"catalogMode managed never adopts a table Datris did not create; have an admin drop $qualified or choose another schema"
                    )
                )
        }
    }

    /** Where a table lives, from its metadata file (`<table>/metadata/x.json`). */
    def tableLocationOf(metadataLocation: String): String =
        Option(metadataLocation).map { m =>
            val n = IcebergCatalogRegistrar.normalize(m)
            val i = n.lastIndexOf("/metadata/")
            if (i > 0) n.substring(0, i) else n
        }.orNull

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
                        val where = if (plan.managed) tableLocationOf(loc) else plan.location
                        statusUtil.info(
                            "processing",
                            line(s"$verb ${plan.qualified} at $where through the catalog; current metadata ${IcebergCatalogRegistrar.normalize(loc)}")
                        )
                    }
                    base.copy(
                        pipeline = config.name,
                        catalogMode = if (plan.managed) "managed" else "rest",
                        restMetadataLocation = Option(loc).getOrElse(base.restMetadataLocation),
                        lastRestCommitAt = now,
                        restRefusedReason = null,
                        // The identifier now names our own table.
                        restCreatedTable = null,
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
