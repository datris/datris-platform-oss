package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.Gson
import ai.datris.model.EntityVersion

/** Append-only store for tap/pipeline definition snapshots. Backs both
  * `<env>-tap-version` and `<env>-pipeline-version` — the caller passes the
  * table name. Records are flat docs with a top-level `key`
  * (`entityName|version`) and `entityName`, mirroring the `<env>-tap-log`
  * idiom so they can be queried by entity and deleted/pruned by key.
  *
  * See plans/tap-pipeline-versioning.md. */
object EntityVersionIO {

    private def gson = new Gson

    def docKey(entityName: String, version: Int): String = entityName + "|" + version

    /** All snapshots for an entity, ascending by version. */
    def listVersions(tableName: String, entityName: String): List[EntityVersion] = {
        NoSQLDbUtil.queryJSONItemsByKey(tableName, "entityName", entityName)
            .flatMap { json =>
                try Some(gson.fromJson(json, classOf[EntityVersion]))
                catch { case _: Exception => None }
            }
            .sortBy(_.version)
    }

    /** Highest version number recorded for an entity, or 0 if none. */
    def latestVersion(tableName: String, entityName: String): Int = {
        val versions = listVersions(tableName, entityName)
        if (versions.isEmpty) 0 else versions.map(_.version).max
    }

    def get(tableName: String, entityName: String, version: Int): Option[EntityVersion] =
        listVersions(tableName, entityName).find(_.version == version)

    /** Append a snapshot. Inserts a flat doc so `entityName`/`key` stay top-level. */
    def append(tableName: String, record: EntityVersion): Unit = {
        NoSQLDbUtil.insertJSON(tableName, gson.toJson(record))
    }

    /** Hard-delete every snapshot for an entity (Phase 4 — delete cleanup).
      * Returns the distinct, non-empty `scriptPath`s the deleted records pinned,
      * so the caller can GC the now-unreferenced script objects. */
    def deleteAllForEntity(tableName: String, entityName: String): List[String] = {
        val records = listVersions(tableName, entityName)
        records.foreach { r =>
            try NoSQLDbUtil.deleteItemJSON(tableName, "key", r.key)
            catch { case _: Exception => () }
        }
        records.map(_.scriptPath).filter(p => p != null && p.nonEmpty).distinct
    }

    /** Retention cap (Phase 4): keep the newest `keep` versions for an entity,
      * delete older snapshots. Returns the `scriptPath`s of pruned records that
      * are no longer referenced by any surviving snapshot (safe to GC). */
    def prune(tableName: String, entityName: String, keep: Int): List[String] = {
        if (keep <= 0) return Nil
        val all = listVersions(tableName, entityName)
        if (all.size <= keep) return Nil
        val survivors = all.takeRight(keep)
        val pruned = all.dropRight(keep)
        pruned.foreach { r =>
            try NoSQLDbUtil.deleteItemJSON(tableName, "key", r.key)
            catch { case _: Exception => () }
        }
        val survivingPaths =
            survivors.map(_.scriptPath).filter(p => p != null && p.nonEmpty).toSet
        pruned.map(_.scriptPath)
            .filter(p => p != null && p.nonEmpty && !survivingPaths.contains(p))
            .distinct
    }
}
