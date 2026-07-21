package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.Gson
import ai.datris.model.TapDocumentLedger

object TapDocumentLedgerIO {

    private def key(tapName: String, uri: String): String = tapName + "|" + uri

    def readByTap(tableName: String, tapName: String): List[TapDocumentLedger] = {
        val prefix = tapName + "|"
        val allKeys = NoSQLDbUtil.getItemsKeysByKeyName(tableName, "key")
        val gson = new Gson
        allKeys.filter(_.startsWith(prefix)).flatMap { k =>
            Option(NoSQLDbUtil.getItemJSON(tableName, "key", k, "value").orNull)
                .map(gson.fromJson(_, classOf[TapDocumentLedger]))
        }
    }

    def read(tableName: String, tapName: String, uri: String): TapDocumentLedger = {
        val json = NoSQLDbUtil.getItemJSON(tableName, "key", key(tapName, uri), "value").orNull
        if (json != null) {
            val gson = new Gson
            gson.fromJson(json, classOf[TapDocumentLedger])
        } else null
    }

    def write(tableName: String, entry: TapDocumentLedger): Unit = {
        val gson = new Gson
        NoSQLDbUtil.putItemJSON(tableName, "key", key(entry.tapName, entry.uri), "value", gson.toJson(entry))
    }

    def delete(tableName: String, tapName: String, uri: String): Unit = {
        NoSQLDbUtil.deleteItemJSON(tableName, "key", key(tapName, uri))
    }

    def deleteByTap(tableName: String, tapName: String): Unit = {
        val prefix = tapName + "|"
        val allKeys = NoSQLDbUtil.getItemsKeysByKeyName(tableName, "key")
        allKeys.filter(_.startsWith(prefix)).foreach { k =>
            NoSQLDbUtil.deleteItemJSON(tableName, "key", k)
        }
    }

    /** Returns a map of uri -> contentHash for all entries owned by a tap. */
    def getKnownHashes(tableName: String, tapName: String): Map[String, String] = {
        readByTap(tableName, tapName).map(e => e.uri -> e.contentHash).toMap
    }
}
