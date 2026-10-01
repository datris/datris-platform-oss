package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisException, PipelineConfig, UnityCatalogSyncState}
import org.slf4j.{Logger, LoggerFactory}

import scala.util.control.NonFatal

/** Where readers find an object-store Iceberg table's current metadata.
  *
  * A path-based table is found through its `version-hint.text` (HadoopTables).
  * A table committed through the Unity Catalog REST catalog
  * (`catalogMode: rest`) is not: REST commits never update the hint, so a
  * path read would miss every catalog commit (or find no table at all). For
  * such a table the reader loads the catalog's current metadata file: from
  * the catalog itself when the pipeline is in active rest mode and the sync
  * is on, else (or when the catalog is unreachable) from the state doc's last
  * recorded catalog commit.
  *
  * A `catalogMode: managed` table lives where the catalog put it (not under
  * prefixKey): it is resolved whatever the table root, and the catalog's
  * answer is accepted when it is in the pipeline's bucket and is the same
  * table (directory) the managed commit recorded. */
object IcebergTableResolver {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** The metadata file to read (`s3a://` for s3 locations) and whether the
      * table is a managed one (the reader then checks the bucket, not the
      * table root). */
    final case class Resolved(metadataLocation: String, managed: Boolean)

    /** None ⇒ path-based table (read through the version hint). Some ⇒ read
      * the table at that metadata file. `catalogCurrent` is only consulted
      * for a catalog-committed table; a failure, or a location outside the
      * table root (rest) or the pipeline's bucket (managed, the authority of
      * `tableRoot`), falls back to the state doc's `restMetadataLocation`.
      * Pure apart from `catalogCurrent`. */
    def resolve(previous: UnityCatalogSyncState, tableRoot: String, catalogCurrent: () => Option[String]): Option[Resolved] = {
        val managed = IcebergRestSession.managedCommitted(previous)
        if (!managed && !IcebergRestSession.restCommitted(previous, tableRoot)) return None
        val bucket = if (managed) Option(IcebergWriter.bucketOf(tableRoot)).getOrElse("") else null
        def accepted(loc: String): Boolean =
            if (managed) IcebergWriter.inBucket(loc, bucket) && IcebergRestSession.managedRecordedTable(loc, previous)
            else IcebergCatalogRegistrar.classify(loc, null, tableRoot) != IcebergCatalogRegistrar.Foreign
        val fromCatalog =
            try catalogCurrent().filter(loc => loc != null && accepted(loc))
            catch {
                case NonFatal(e) =>
                    logger.warn(
                        "Unity Catalog lookup for the table at " + tableRoot + " failed (" + e.getMessage +
                            "); reading at the last recorded catalog commit " + previous.restMetadataLocation
                    )
                    None
            }
        Some(Resolved(readable(fromCatalog.getOrElse(previous.restMetadataLocation)), managed))
    }

    /** `s3://` / `s3n://` → `s3a://` (the scheme Datris' Hadoop conf serves). */
    def readable(loc: String): String = Option(loc).map(_.trim.replaceFirst("(?i)^s3n?://", "s3a://")).orNull

    /** Resolve for a pipeline: reads the state doc (a failure is an error:
      * without it a catalog-committed table would be read stale) and asks the
      * catalog when the pipeline commits through it (rest or managed) with
      * the sync on. */
    def forPipeline(config: PipelineConfig, tableRoot: String): Option[Resolved] = {
        val previous =
            try UnityCatalogSyncIO.read(config.name)
            catch {
                case NonFatal(e) =>
                    throw new DatrisException(
                        "could not read the Unity Catalog state for pipeline " + config.name + " (" + e.getMessage +
                            "); cannot tell whether the Iceberg table is committed through the catalog"
                    )
            }
        // Block only: the install default (DATRIS_UNITY_CATALOG_DEFAULT) never applies to Iceberg.
        val uc = config.unityCatalog
        val restActive = uc != null && uc.enabled && uc.registerOn && uc.throughCatalog && UnityCatalogMetadataSync.switchedOn
        resolve(previous, tableRoot, () => if (restActive) IcebergRestSession.catalogCurrentMetadata(config) else None)
    }
}
