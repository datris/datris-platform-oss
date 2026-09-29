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
  * recorded catalog commit. */
object IcebergTableResolver {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** None ⇒ path-based table (read through the version hint). Some(loc) ⇒
      * read the table at metadata file `loc` (as `s3a://` for s3 locations).
      * `catalogCurrent` is only consulted for a catalog-committed table; a
      * failure or a location outside the table root falls back to the state
      * doc's `restMetadataLocation`. Pure apart from `catalogCurrent`. */
    def resolve(previous: UnityCatalogSyncState, tableRoot: String, catalogCurrent: () => Option[String]): Option[String] = {
        if (!IcebergRestSession.restCommitted(previous, tableRoot)) return None
        val fromCatalog =
            try catalogCurrent().filter(loc => IcebergCatalogRegistrar.classify(loc, null, tableRoot) != IcebergCatalogRegistrar.Foreign)
            catch {
                case NonFatal(e) =>
                    logger.warn(
                        "Unity Catalog lookup for the table at " + tableRoot + " failed (" + e.getMessage +
                            "); reading at the last recorded catalog commit " + previous.restMetadataLocation
                    )
                    None
            }
        Some(readable(fromCatalog.getOrElse(previous.restMetadataLocation)))
    }

    /** `s3://` / `s3n://` → `s3a://` (the scheme Datris' Hadoop conf serves). */
    def readable(loc: String): String = Option(loc).map(_.trim.replaceFirst("(?i)^s3n?://", "s3a://")).orNull

    /** Resolve for a pipeline: reads the state doc (a failure is an error:
      * without it a catalog-committed table would be read stale) and asks the
      * catalog when the pipeline is in active rest mode with the sync on. */
    def forPipeline(config: PipelineConfig, tableRoot: String): Option[String] = {
        val previous =
            try UnityCatalogSyncIO.read(config.name)
            catch {
                case NonFatal(e) =>
                    throw new DatrisException(
                        "could not read the Unity Catalog state for pipeline " + config.name + " (" + e.getMessage +
                            "); cannot tell whether the Iceberg table is committed through the catalog"
                    )
            }
        val uc = config.unityCatalog
        val restActive = uc != null && uc.enabled && uc.registerOn && uc.restMode && UnityCatalogMetadataSync.switchedOn
        resolve(previous, tableRoot, () => if (restActive) IcebergRestSession.catalogCurrentMetadata(config) else None)
    }
}
