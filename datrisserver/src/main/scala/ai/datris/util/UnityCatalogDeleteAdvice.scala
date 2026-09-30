package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{PipelineConfig, UnityCatalogSyncState}

/** What to tell the caller when a pipeline (or its data) is deleted while
  * Unity Catalog still holds its object-store Iceberg table. A table
  * committed through the Iceberg REST catalog (`catalogMode: rest`) is owned
  * by the catalog; Datris never drops it, so an admin has to. Pure apart from
  * the bucket lookup, which only reads the environment when the destination
  * has no `destinationBucketOverride`. */
object UnityCatalogDeleteAdvice {

    /** `s3a://<bucket>/<prefixKey>`: the table root the writer uses
      * (SparkObjectStoreLoader's outputPath), with the prefix normalized. */
    private[util] def tableRoot(config: PipelineConfig): String = {
        val o = config.destination.objectStore
        "s3a://" + ObjectStoreSpark.resolveBucketUnchecked(o) + "/" + ObjectStoreSpark.normalizePrefix(o.prefixKey)
    }

    private def isIceberg(config: PipelineConfig): Boolean =
        config != null && config.destination != null && config.destination.objectStore != null &&
            Option(config.destination.objectStore.fileFormat).exists(_.trim.equalsIgnoreCase("iceberg"))

    /** Whether the last register (story 4, register mode) left a Unity
      * Catalog entry pointing under this table root. */
    private def registeredUnder(previous: UnityCatalogSyncState, root: String): Boolean =
        previous.lastRegisterAt != null && previous.registeredMetadataLocation != null &&
            IcebergCatalogRegistrar.classify(previous.registeredMetadataLocation, null, root) != IcebergCatalogRegistrar.Foreign

    /** None unless the pipeline is an object-store Iceberg pipeline that
      * Unity Catalog still holds at its prefix:
      *  - committed through the catalog (IcebergRestSession.restCommitted):
      *    `dataDeleted=false` (files kept, e.g. a shared prefix) says the
      *    catalog can still read it; `configDeleted=false` (a reset: data
      *    wiped, config kept) says the next run fails until the table is
      *    dropped or the pipeline moves to a new prefix;
      *  - registered (register mode) with its data deleted: the entry points
      *    at files that no longer exist. */
    def forPipeline(
        config: PipelineConfig,
        previous: UnityCatalogSyncState,
        dataDeleted: Boolean = true,
        configDeleted: Boolean = true
    ): Option[String] = {
        if (!isIceberg(config) || previous == null) return None
        val root = tableRoot(config)
        val qualified = IcebergRestSession.qualifiedFor(config)
        if (IcebergRestSession.restCommitted(previous, root))
            Some(
                if (!dataDeleted)
                    "Unity Catalog still holds " + qualified + " and can still read it (the data files were kept); have an admin drop it"
                else if (!configDeleted)
                    "Unity Catalog still holds " + qualified + " at files that were just deleted; the next run of this pipeline will fail " +
                        "until an admin drops it (or the pipeline moves to a new prefix)"
                else "Unity Catalog still holds " + qualified + "; have an admin drop it"
            )
        else if (previous.restCreatedTable != null)
            Some(
                "Unity Catalog holds " + previous.restCreatedTable +
                    ", created by Datris but never written to (the catalog ignored the requested location); have an admin drop it"
            )
        else if (dataDeleted && registeredUnder(previous, root))
            Some("Unity Catalog still has " + qualified + " registered at a location that no longer exists; have an admin drop it")
        else None
    }
}
