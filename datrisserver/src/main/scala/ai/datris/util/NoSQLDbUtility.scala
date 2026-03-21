package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

trait NoSQLDbUtility {
    def getItemsKeysByKeyName(tableName: String, keyName: String): List[String]

    def getItemAttribute[T](tableName: String, keyName: String, key: String, attributeName: String): T

    def setItemNameValue(tableName: String, keyName: String, key: String, valueName: String, value: String): Unit

    def getItemJSON(tableName: String, keyName: String, key: String, valueName: String): Option[String]

    def putItemJSON(tableName: String, keyName: String, key: String, valueName: String, value: String, sortKeyName: String = null, sortKeyValue: Number = null): Unit

    def updateItemJSON(tableName: String, keyName: String, key: String, valueName: String, value: String, sortKeyName: String = null, sortKeyValue: Number = null): Unit

    def deleteItemJSON(tableName: String, keyName: String, key: String, sortKeyName: String = null, sortKeyValue: Number = null): Unit

    def queryJSONItemsByKey(tableName: String, keyName: String, key: String): List[String]

    def getAllItemsAsJSON(tableName: String): List[String]

    def getPageOfItemsAsJSON(tableName: String, pageNbr: Int, maxPageSize: Int, sortField: String = null, sortDescending: Boolean = true): List[String]

    def insertJSON(tableName: String, json: String): Unit

    def deleteAll(tableName: String): Long

    def upsertJSON(tableName: String, keyFields: java.util.List[String], json: String): Unit
}
