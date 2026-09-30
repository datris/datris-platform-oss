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

    /** None unless the pipeline is an object-store Iceberg pipeline whose
      * table at its prefix was committed through the catalog
      * (IcebergRestSession.restCommitted). `dataDeleted=false` (the files
      * were kept, e.g. a shared prefix) says the catalog can still read it. */
    def forPipeline(config: PipelineConfig, previous: UnityCatalogSyncState, dataDeleted: Boolean = true): Option[String] = {
        if (!isIceberg(config) || previous == null) return None
        if (!IcebergRestSession.restCommitted(previous, tableRoot(config))) return None
        val qualified = IcebergRestSession.qualifiedFor(config)
        Some(
            if (dataDeleted) "Unity Catalog still holds " + qualified + "; have an admin drop it"
            else
                "Unity Catalog still holds " + qualified + " and can still read it (the data files were kept); have an admin drop it"
        )
    }
}
