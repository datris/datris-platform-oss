package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, PipelineConfig, UnityCatalogSyncState}
import com.google.gson.JsonParser
import org.slf4j.{Logger, LoggerFactory}

import scala.util.Try
import scala.util.control.NonFatal

/** Leftover Unity Catalog sync state (`<env>-uc-sync`) that no longer
  * describes a live table: a doc left behind by a pipeline deleted out of
  * band, then a new pipeline created with the same name. Three belts:
  *  - on pipeline create, a leftover doc is cleared unless it records a
  *    catalog commit at the new pipeline's own prefix whose files may still
  *    exist (then it is the fork guard and stays);
  *  - at run time, a "committed" doc is ignored when neither the catalog nor
  *    the prefix has the table (never when either has it);
  *  - on startup, docs of pipelines that no longer exist are deleted, except
  *    catalog-committed ones (kept on purpose when a delete left the files). */
object UnityCatalogStaleState {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    // --- pure decisions ------------------------------------------------------

    /** Stale only when the doc says committed AND the catalog is known to
      * have no table AND the prefix is known to have no metadata. Unknown
      * (None) on either side is never stale. */
    def staleCommitted(committed: Boolean, catalogHasTable: Option[Boolean], prefixHasMetadata: Option[Boolean]): Boolean =
        committed && catalogHasTable.contains(false) && prefixHasMetadata.contains(false)

    /** On create: keep a leftover doc only when it is the fork guard for the
      * new pipeline's own table (committed at its root) and the prefix still
      * has (or may have) the table's metadata. */
    def keepOnCreate(doc: UnityCatalogSyncState, tableRoot: Option[String], prefixHasMetadata: () => Option[Boolean]): Boolean =
        doc != null && tableRoot.exists(r => IcebergRestSession.restCommitted(doc, r)) && !prefixHasMetadata().contains(false)

    /** Docs to delete on startup: no pipeline of that name, and not a
      * catalog-committed doc (those guard files a delete kept). */
    def orphanDocs(docs: Seq[UnityCatalogSyncState], existingPipelines: Set[String]): Seq[String] =
        docs.filter(d => d != null && d.pipeline != null && !existingPipelines.contains(d.pipeline))
            .filterNot(d => d.catalogMode == "rest" && d.restMetadataLocation != null && d.lastRestCommitAt != null)
            .map(_.pipeline)

    /** Top-level `state` of the Unity Catalog state endpoint: `error` when
      * the last run left an error line or was refused the catalog
      * (`register == "refused"`), else `synced`; `never` without a doc. */
    def topLevelState(state: UnityCatalogSyncState, register: String): String =
        if (state == null) "never"
        else if (state.lastError != null || register == "refused") "error"
        else "synced"

    def withoutRestCommit(doc: UnityCatalogSyncState): UnityCatalogSyncState =
        doc.copy(catalogMode = null, restMetadataLocation = null, lastRestCommitAt = null, restRefusedReason = null)

    // --- probes (None = could not tell) ---------------------------------------

    /** Does `<root>/metadata` exist? Uses the session's Hadoop conf (the
      * loader has applied the per-bucket config). */
    def prefixHasMetadata(tableRoot: String): Option[Boolean] =
        try {
            val conf = SparkSessionManager.getOrCreate().sparkContext.hadoopConfiguration
            val p = new org.apache.hadoop.fs.Path(tableRoot.stripSuffix("/") + "/metadata")
            Some(p.getFileSystem(conf).exists(p))
        } catch {
            case NonFatal(e) =>
                logger.warn("could not check " + tableRoot + "/metadata: " + e.getMessage)
                None
        }

    /** Does the catalog have the pipeline's table? None without a usable
      * unityCatalog block, with the sync switched off, or on any failure. */
    def catalogHasTable(config: PipelineConfig): Option[Boolean] = {
        val uc = config.unityCatalog
        def blank(s: String) = s == null || s.trim.isEmpty
        if (uc == null || blank(uc.catalog) || blank(uc.credentialsSecret) || !UnityCatalogMetadataSync.switchedOn) None
        else
            try Some(IcebergRestSession.catalogCurrentMetadata(config).isDefined)
            catch {
                case NonFatal(e) =>
                    logger.warn("could not look up " + IcebergRestSession.qualifiedFor(config) + " in the catalog: " + e.getMessage)
                    None
            }
    }

    // --- side effects -----------------------------------------------------------

    /** Pipeline create (no existing config for the name): clear a leftover
      * doc unless it is the fork guard for this pipeline's own table. Never
      * throws. */
    def clearOnCreate(config: PipelineConfig): Unit =
        try {
            val doc = UnityCatalogSyncIO.read(config.name)
            if (doc == null) return
            val root = tableRootOf(config)
            if (keepOnCreate(doc, root, () => root.flatMap(r => prefixMetadataFor(config, r)))) {
                logger.info(
                    "kept Unity Catalog sync state for " + config.name + ": its catalog-committed table's files may still exist at " + root.orNull
                )
            } else {
                UnityCatalogSyncIO.delete(config.name)
                logger.info("cleared stale Unity Catalog sync state for " + config.name)
            }
        } catch {
            case NonFatal(e) => logger.warn("Unity Catalog sync state check on create failed for " + config.name + ": " + e.getMessage)
        }

    /** Startup: delete docs whose pipeline no longer exists (see
      * [[orphanDocs]]). Returns the number deleted. Never throws. */
    def cleanupOrphans(): Int =
        try {
            val env = DatrisEnvironment.current
            val existing = PipelineConfigIO.readAll(env.pipelineTableName).map(_.name).toSet
            val docs = NoSQLDbUtil.getAllItemsAsJSON(env.ucSyncTableName).flatMap(parseDoc)
            val orphans = orphanDocs(docs, existing)
            orphans.foreach(name => Try(UnityCatalogSyncIO.delete(name)))
            logger.info("Unity Catalog sync state cleanup: deleted " + orphans.size + " doc(s) of pipelines that no longer exist")
            orphans.size
        } catch {
            case NonFatal(e) =>
                logger.warn("Unity Catalog sync state cleanup failed: " + e.getMessage)
                0
        }

    /** `{pipeline, value: {...}}` as stored by UnityCatalogSyncIO. */
    private[util] def parseDoc(json: String): Option[UnityCatalogSyncState] =
        Try {
            val o = JsonParser.parseString(json).getAsJsonObject
            val name = o.get("pipeline").getAsString
            val value = if (o.has("value") && o.get("value").isJsonObject) o.getAsJsonObject("value").toString else "{}"
            new com.google.gson.Gson().fromJson(value, classOf[UnityCatalogSyncState]).copy(pipeline = name)
        }.toOption

    /** Any object-store format: a parquet pipeline at a catalog-committed
      * prefix still needs the guard (its deleteBeforeWrite is refused). */
    private def tableRootOf(config: PipelineConfig): Option[String] =
        if (config.destination != null && config.destination.objectStore != null) Try(UnityCatalogDeleteAdvice.tableRoot(config)).toOption
        else None

    private def prefixMetadataFor(config: PipelineConfig, root: String): Option[Boolean] =
        try {
            val o = config.destination.objectStore
            ObjectStoreSpark.applyPerBucketConfig(SparkSessionManager.getOrCreate(), ObjectStoreSpark.resolveBucket(o), o)
            prefixHasMetadata(root)
        } catch { case NonFatal(_) => None }
}
