package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.Gson
import ai.datris.model.{TapConfig, DatrisEnvironment, EntityVersion}

object TapConfigIO {
    def readAll(tableName: String): List[TapConfig] = {
        val tapNames = NoSQLDbUtil.getItemsKeysByKeyName(tableName, "name")
        tapNames.map(name => read(tableName, name))
    }

    def read(tableName: String, tapName: String): TapConfig = {
        val json = NoSQLDbUtil.getItemJSON(tableName, "name", tapName, "value").orNull
        if (json != null) {
            val gson = new Gson
            val config = gson.fromJson(json, classOf[TapConfig])
            // Gson does NOT honor Scala case-class default values: a pre-versioning
            // doc with no `version` field deserializes to the primitive zero (0),
            // not the `= 1` default. Normalize so untouched taps read as version 1,
            // consistent with the v1 the lazy seed records on first edit.
            if (config != null && config.version <= 0) config.copy(version = 1) else config
        } else
            null
    }

    def write(tapConfig: TapConfig): Unit = {
        val gson = new Gson
        val json = gson.toJson(tapConfig)
        NoSQLDbUtil.putItemJSON(DatrisEnvironment.current.tapTableName, "name", tapConfig.name, "value", json)
    }

    /** Definition-edit write: persists the live config AND appends an immutable
      * snapshot to `<env>-tap-version`. Use ONLY from create/update controller
      * paths — NOT from TapRunner status churn (that must stay on plain `write`,
      * or every cron tick would mint a bogus version).
      *
      * On the first versioned write for a tap that predates versioning, the
      * pre-edit config is lazily seeded as version 1 so the "before" state is
      * preserved. Returns the persisted config carrying its new `version`. */
    def writeVersioned(tapConfig: TapConfig, changeNote: String, actor: String): TapConfig = {
        val env = DatrisEnvironment.current
        val versionTable = env.tapVersionTableName
        val gson = new Gson
        val now = timestamp(env)

        val existing = read(env.tapTableName, tapConfig.name)
        var baseVersion = EntityVersionIO.latestVersion(versionTable, tapConfig.name)

        // Lazy seed: no snapshots yet but a pre-edit config exists → snapshot it.
        if (baseVersion == 0 && existing != null) {
            val seedVersion = if (existing.version > 0) existing.version else 1
            EntityVersionIO.append(versionTable, EntityVersion(
                key = EntityVersionIO.docKey(tapConfig.name, seedVersion),
                entityName = tapConfig.name,
                version = seedVersion,
                config = gson.toJson(existing),
                scriptPath = existing.scriptPath,
                changeNote = "(seeded from pre-versioning state)",
                createdAt = if (existing.updatedAt != null) existing.updatedAt else now,
                createdBy = "system"
            ))
            baseVersion = seedVersion
        }

        val nextVersion = baseVersion + 1
        val versioned = tapConfig.copy(version = nextVersion)
        write(versioned)
        EntityVersionIO.append(versionTable, EntityVersion(
            key = EntityVersionIO.docKey(tapConfig.name, nextVersion),
            entityName = tapConfig.name,
            version = nextVersion,
            config = gson.toJson(versioned),
            scriptPath = versioned.scriptPath,
            changeNote = changeNote,
            createdAt = now,
            createdBy = actor
        ))

        // Retention cap: prune old snapshots and GC their now-unreferenced scripts.
        EntityVersionIO.prune(versionTable, tapConfig.name, env.versionCap)
            .foreach(TapScriptGenerator.deleteScript)

        versioned
    }

    def delete(tableName: String, tapName: String): Unit = {
        NoSQLDbUtil.deleteItemJSON(tableName, "name", tapName)
    }

    private def timestamp(env: DatrisEnvironment): String = {
        val sdf = new java.text.SimpleDateFormat(env.dateFormat)
        sdf.setTimeZone(java.util.TimeZone.getTimeZone(env.dateTimezone))
        sdf.format(new java.util.Date())
    }
}
