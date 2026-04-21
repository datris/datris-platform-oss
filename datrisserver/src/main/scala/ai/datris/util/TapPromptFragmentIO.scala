package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.Gson
import ai.datris.model.{TapPromptFragment, DatrisEnvironment}

object TapPromptFragmentIO {
    def readAll(tableName: String): List[TapPromptFragment] = {
        val keys = NoSQLDbUtil.getItemsKeysByKeyName(tableName, "key")
        keys.map(k => read(tableName, k)).filter(_ != null)
    }

    def read(tableName: String, key: String): TapPromptFragment = {
        val json = NoSQLDbUtil.getItemJSON(tableName, "key", key, "value").orNull
        if (json != null) {
            val gson = new Gson
            gson.fromJson(json, classOf[TapPromptFragment])
        } else
            null
    }

    def write(fragment: TapPromptFragment): Unit = {
        val gson = new Gson
        val json = gson.toJson(fragment)
        NoSQLDbUtil.putItemJSON(DatrisEnvironment.current.tapPromptTableName, "key", fragment.key, "value", json)
    }

    def delete(tableName: String, key: String): Unit = {
        NoSQLDbUtil.deleteItemJSON(tableName, "key", key)
    }
}
