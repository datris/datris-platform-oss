package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.Gson
import ai.datris.model.DatrisEnvironment

/** One row per tap in `{env}-tap-state`.
  *
  * @param name         tap name (row key)
  * @param state        the opaque state blob the tap's script emitted, as raw JSON.
  *                     The platform stores, injects, and displays it — never interprets it.
  * @param updatedAt    platform-format timestamp of the committing run/edit
  * @param updatedBy    what wrote it: the committing run's time key, or "manual" for
  *                     API/MCP/UI overrides
  */
case class TapState(
    name: String,
    state: String,
    updatedAt: String = null,
    updatedBy: String = null
)

object TapStateIO {

    def read(tapName: String): TapState = {
        val json = NoSQLDbUtil.getItemJSON(DatrisEnvironment.current.tapStateTableName, "name", tapName, "value").orNull
        if (json != null) new Gson().fromJson(json, classOf[TapState]) else null
    }

    /** Just the state blob (raw JSON) for env-var injection; null when none exists. */
    def readStateJson(tapName: String): String = {
        val row = read(tapName)
        if (row != null) row.state else null
    }

    def write(tapState: TapState): Unit = {
        val gson = new Gson
        NoSQLDbUtil.putItemJSON(DatrisEnvironment.current.tapStateTableName, "name", tapState.name, "value", gson.toJson(tapState))
    }

    def delete(tapName: String): Unit = {
        NoSQLDbUtil.deleteItemJSON(DatrisEnvironment.current.tapStateTableName, "name", tapName)
    }
}
