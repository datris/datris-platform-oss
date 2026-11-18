package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.PipelineConfig

/** DELETE /pipeline flags, read once. Pure (no I/O).
  *
  * By default the data is deleted with the configuration (no pipeline is
  * left to own it). Only an explicit `deleteData=false` (any letter case,
  * surrounding whitespace ignored) together with the configuration delete
  * keeps the destination data: that caller has accepted data no pipeline
  * manages. Any other `deleteData` text keeps its old meaning. A
  * `deleteConfig` other than `true` is the data-only reset (or, with
  * `deleteData=false`, deletes nothing). */
object PipelineDeletePlan {

    /** `keepData` is true only for the configuration delete with an explicit
      * `deleteData=false`; `deleteData` is then false. */
    case class Plan(deleteConfig: Boolean, deleteData: Boolean, keepData: Boolean)

    def of(deleteData: String, deleteConfig: String): Plan = {
        // `true` is matched as before (letter case only); only the new
        // explicit `false` is trimmed.
        val configDeleted = "true".equalsIgnoreCase(deleteConfig)
        val explicitFalse = deleteData != null && deleteData.trim.equalsIgnoreCase("false")
        val dataTrue = "true".equalsIgnoreCase(deleteData)
        if (configDeleted)
            if (explicitFalse) Plan(deleteConfig = true, deleteData = false, keepData = true)
            else Plan(deleteConfig = true, deleteData = true, keepData = false)
        else Plan(deleteConfig = false, deleteData = dataTrue, keepData = false)
    }

    /** 400 text for keeping the data of a pipeline that writes to a vector
      * store. Names no pipeline, catalog or container. */
    val KeepDataVectorRefusal: String =
        "This pipeline writes to a vector store; its configuration cannot be deleted while keeping that data. " +
            "Delete it with its data, or keep the pipeline."

    /** Some(refusal) for a keep plan on a pipeline with any vector container
      * (chunks stamped with the deleted name would stay readable to a
      * pipeline re-created under it); None otherwise. */
    def refusal(plan: Plan, config: PipelineConfig): Option[String] =
        if (plan.keepData && VectorContainers.keysOf(config).nonEmpty) Some(KeepDataVectorRefusal)
        else None

    /** Destination kinds a keep-data delete leaves in place. */
    private val KeptKinds: Set[String] = Set("postgres", "mongodb", "snowflake", "databricks", "sqlserver", "objectstore")

    /** Labels of the tables, collections and object-store prefixes a
      * keep-data delete leaves in place, in `SharedDatasets.keysOf` order.
      * Nothing for scratch (platform working storage, still deleted),
      * messaging, REST or vector destinations. */
    def keptLabels(config: PipelineConfig, env: SharedDatasets.Env): Seq[String] =
        SharedDatasets.keysOf(config, env).filter(k => KeptKinds.contains(k.kind)).map(SharedDatasets.label)
}
