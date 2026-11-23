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

    /** Every stored tap of the current environment (the tap listing the
      * script ownership rules read). Throws when the listing or any tap
      * cannot be read, so a caller keeping files on failure keeps them. */
    def listing(): Seq[TapConfig] = readAll(DatrisEnvironment.current.tapTableName).filter(_ != null)

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

    /** `toWrite` with `live`'s owner (`createdByKeyLabel`) when `live` is
      * the same tap ([[sameTap]]) and its owner differs; otherwise `toWrite`
      * itself. A tap deleted and created again under the same name while a
      * run held the earlier one is not the same tap: the run's copy is
      * written unchanged. Nothing else is taken from `live`. Pure. */
    def keepLiveOwner(toWrite: TapConfig, live: TapConfig): TapConfig =
        if (toWrite == null || live == null || !sameTap(toWrite, live) || live.createdByKeyLabel == toWrite.createdByKeyLabel) toWrite
        else toWrite.copy(createdByKeyLabel = live.createdByKeyLabel)

    /** True when `a` and `b` are the same stored tap: equal creation times
      * (`createdAt`), a missing or blank time on both sides counting as
      * equal (taps stored before creation times were recorded). */
    def sameTap(a: TapConfig, b: TapConfig): Boolean = {
        def created(t: TapConfig): Option[String] = Option(t.createdAt).map(_.trim).filter(_.nonEmpty)
        a != null && b != null && created(a) == created(b)
    }

    /** A run or test status write. A run or test holds the tap as it was when
      * it started; when an administrator changed the tap's owner meanwhile
      * (`POST /api/v1/tap/owner`), the status write keeps the stored owner
      * rather than putting the previous one back. The tap is re-read by name
      * (`read`), its owner kept ([[keepLiveOwner]]) and the result written
      * (`write`). A re-read that fails, finds no tap, or finds a different
      * tap of the same name, writes `tap` as given, as before. */
    def writeRunStateWith(tap: TapConfig, read: String => TapConfig, write: TapConfig => Unit): Unit = {
        val live =
            if (tap == null || tap.name == null) null
            else
                try read(tap.name)
                catch { case scala.util.control.NonFatal(_) => null }
        write(keepLiveOwner(tap, live))
    }

    /** [[writeRunStateWith]] over the live tap store: used by
      * `TapRunner.ProductionEffects.writeTap` and the test-stamp writes. */
    def writeRunState(tap: TapConfig): Unit =
        writeRunStateWith(tap, n => read(DatrisEnvironment.current.tapTableName, n), t => write(t))

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
            EntityVersionIO.append(
                versionTable,
                EntityVersion(
                    key = EntityVersionIO.docKey(tapConfig.name, seedVersion),
                    entityName = tapConfig.name,
                    version = seedVersion,
                    config = gson.toJson(existing),
                    scriptPath = existing.scriptPath,
                    changeNote = "(seeded from pre-versioning state)",
                    createdAt = if (existing.updatedAt != null) existing.updatedAt else now,
                    createdBy = "system"
                )
            )
            baseVersion = seedVersion
        }

        val nextVersion = baseVersion + 1
        val versioned = tapConfig.copy(version = nextVersion)
        write(versioned)
        // A definition save (secret, packages, type, script, ...) may change what
        // the tap produces: rows held from an earlier test no longer vouch for it.
        // Plain `write` (test stamps, status churn) must NOT drop, or a test
        // followed by propose_pipeline_schema would never reuse.
        TapTestPreviewCache.drop(tapConfig.name)
        EntityVersionIO.append(
            versionTable,
            EntityVersion(
                key = EntityVersionIO.docKey(tapConfig.name, nextVersion),
                entityName = tapConfig.name,
                version = nextVersion,
                config = gson.toJson(versioned),
                scriptPath = versioned.scriptPath,
                changeNote = changeNote,
                createdAt = now,
                createdBy = actor
            )
        )

        // Retention cap: prune old snapshots and GC their now-unreferenced
        // scripts: only script objects no stored tap references that are this
        // tap's own or were written under a name no stored tap has (the live
        // listing is read only when such an object is pruned).
        TapScriptGenerator.deleteTapScripts(
            tapConfig.name,
            EntityVersionIO.prune(versionTable, tapConfig.name, env.versionCap),
            allTaps = () => listing()
        )

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
