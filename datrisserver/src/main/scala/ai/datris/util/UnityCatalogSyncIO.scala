package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, UnityCatalogSyncState}
import com.google.gson.Gson

/** Persistence for [[UnityCatalogSyncState]]: one doc per pipeline in
  * `<env>-uc-sync`, keyed by pipeline name. Kept out of the pipeline config
  * document so a racing config save never clobbers sync state and definition
  * versions never snapshot runtime state. */
object UnityCatalogSyncIO {
    private val gson = new Gson()

    private def table: String = DatrisEnvironment.current.ucSyncTableName

    def read(pipeline: String): UnityCatalogSyncState =
        NoSQLDbUtil
            .getItemJSON(table, "pipeline", pipeline, "value")
            .map(gson.fromJson(_, classOf[UnityCatalogSyncState]))
            .orNull

    def delete(pipeline: String): Unit =
        NoSQLDbUtil.deleteItemJSON(table, "pipeline", pipeline)

    def write(doc: UnityCatalogSyncState): Unit =
        NoSQLDbUtil.putItemJSON(table, "pipeline", doc.pipeline, "value", gson.toJson(doc))
}
