package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.Gson
import ai.datris.model.{PipelineConfig, DatrisEnvironment, DatrisException, EntityVersion}

object PipelineConfigIO {
    def readAll(tableName: String): List[PipelineConfig] = {
        val pipelineNames = NoSQLDbUtil.getItemsKeysByKeyName(tableName, "name")
        pipelineNames.map(name => {
            read(DatrisEnvironment.current.pipelineTableName, name)
        })
    }

    def read(tableName: String, pipelineName: String): PipelineConfig = {
        val json = NoSQLDbUtil.getItemJSON(tableName, "name", pipelineName, "value").orNull
        if (json != null) {
            val gson = new Gson
            val parsed = gson.fromJson(json, classOf[PipelineConfig])
            // Gson ignores Scala case-class default values, so a pre-versioning doc
            // with no `version` field deserializes to 0, not the `= 1` default.
            // Normalize to 1 so untouched pipelines read consistently with history.
            val config = if (parsed != null && parsed.version <= 0) parsed.copy(version = 1) else parsed

            // If there are no destination schema properties, use the source schema as the destination schema
            if (config.destination.schemaProperties == null) {
                val destination = config.destination.copy(schemaProperties = config.source.schemaProperties)
                config.copy(destination = destination)
            } else
                config
        } else
            null
    }

    def write(datasetConfig: PipelineConfig): Unit = {
        val gson = new Gson
        val json = gson.toJson(datasetConfig)
        NoSQLDbUtil.putItemJSON(DatrisEnvironment.current.pipelineTableName, "name", datasetConfig.name, "value", json)
    }

    /** Definition-edit write: persists the live config AND appends an immutable
      * snapshot to `<env>-pipeline-version`. Pipelines have no script to pin, so
      * `scriptPath` is null on every snapshot. Lazily seeds the pre-edit config
      * as version 1 on first versioned write. Returns the config with its new
      * `version`. See [[TapConfigIO.writeVersioned]] for the tap analogue. */
    def writeVersioned(config: PipelineConfig, changeNote: String, actor: String): PipelineConfig = {
        val env = DatrisEnvironment.current
        val versionTable = env.pipelineVersionTableName
        val gson = new Gson
        val now = timestamp(env)

        val existing = read(env.pipelineTableName, config.name)
        var baseVersion = EntityVersionIO.latestVersion(versionTable, config.name)

        if (baseVersion == 0 && existing != null) {
            val seedVersion = if (existing.version > 0) existing.version else 1
            EntityVersionIO.append(
                versionTable,
                EntityVersion(
                    key = EntityVersionIO.docKey(config.name, seedVersion),
                    entityName = config.name,
                    version = seedVersion,
                    config = gson.toJson(existing),
                    scriptPath = null,
                    changeNote = "(seeded from pre-versioning state)",
                    createdAt = now,
                    createdBy = "system"
                )
            )
            baseVersion = seedVersion
        }

        val nextVersion = baseVersion + 1
        val versioned = config.copy(version = nextVersion)
        write(versioned)
        EntityVersionIO.append(
            versionTable,
            EntityVersion(
                key = EntityVersionIO.docKey(config.name, nextVersion),
                entityName = config.name,
                version = nextVersion,
                config = gson.toJson(versioned),
                scriptPath = null,
                changeNote = changeNote,
                createdAt = now,
                createdBy = actor
            )
        )

        EntityVersionIO.prune(versionTable, config.name, env.versionCap)
        versioned
    }

    private def timestamp(env: DatrisEnvironment): String = {
        val sdf = new java.text.SimpleDateFormat(env.dateFormat)
        sdf.setTimeZone(java.util.TimeZone.getTimeZone(env.dateTimezone))
        sdf.format(new java.util.Date())
    }

    def getSourceFileExtension(config: PipelineConfig): String = {
        val fileAttributes = config.source.fileAttributes
        if (fileAttributes.csvAttributes != null)
            "csv"
        else if (fileAttributes.jsonAttributes != null)
            "json"
        else if (fileAttributes.xmlAttributes != null)
            "xml"
        else if (fileAttributes.unstructuredAttributes != null)
            fileAttributes.unstructuredAttributes.fileExtension
        else
            throw new DatrisException("The pipeline configuration fileAttributes are not configured properly")
    }
}
