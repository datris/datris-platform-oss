package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.Gson
import ai.datris.model.{TapConfig, DatrisEnvironment}

object TapConfigIO {
    def readAll(tableName: String): List[TapConfig] = {
        val tapNames = NoSQLDbUtil.getItemsKeysByKeyName(tableName, "name")
        tapNames.map(name => read(tableName, name))
    }

    def read(tableName: String, tapName: String): TapConfig = {
        val json = NoSQLDbUtil.getItemJSON(tableName, "name", tapName, "value").orNull
        if (json != null) {
            val gson = new Gson
            gson.fromJson(json, classOf[TapConfig])
        } else
            null
    }

    def write(tapConfig: TapConfig): Unit = {
        val gson = new Gson
        val json = gson.toJson(tapConfig)
        NoSQLDbUtil.putItemJSON(DatrisEnvironment.current.tapTableName, "name", tapConfig.name, "value", json)
    }

    def delete(tableName: String, tapName: String): Unit = {
        NoSQLDbUtil.deleteItemJSON(tableName, "name", tapName)
    }
}
