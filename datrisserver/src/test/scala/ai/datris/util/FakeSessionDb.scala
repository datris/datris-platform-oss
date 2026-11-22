package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonObject, JsonParser}

import scala.collection.JavaConverters._
import scala.collection.mutable

/** In-memory `{env}-user-session` collection for specs that drive the real
  * [[SessionStore]] through `SessionStore.dbOverride`. Unlike [[InMemoryUserDb]]
  * it honours the key field a caller names, the way the Mongo filters do
  * (`getItemJSON`, `deleteItemJSON` and `upsertJSON` match on that field's
  * value), so a lookup on one field never finds a document by another.
  *
  * `lookups` records every (keyName, key) read, so a spec can tell which
  * field a lookup used. `putJson` stores a document as given, standing in for
  * one written by an earlier server version (upgrade path). */
class FakeSessionDb extends NoSQLDbUtility {
    val docs: mutable.ArrayBuffer[JsonObject] = mutable.ArrayBuffer()
    val lookups: mutable.ArrayBuffer[(String, String)] = mutable.ArrayBuffer()

    def putJson(json: String): Unit = docs += JsonParser.parseString(json).getAsJsonObject

    private def matches(o: JsonObject, keyName: String, key: String): Boolean =
        keyName != null && key != null && o.has(keyName) && o.get(keyName).isJsonPrimitive && o.get(keyName).getAsString == key

    private def first(keyName: String, key: String): Option[JsonObject] = docs.find(matches(_, keyName, key))

    override def getItemJSON(tableName: String, keyName: String, key: String, valueName: String): Option[String] = {
        lookups += (keyName -> key)
        first(keyName, key).map(_.toString)
    }

    override def queryJSONItemsByKey(tableName: String, keyName: String, key: String): List[String] = {
        lookups += (keyName -> key)
        docs.filter(matches(_, keyName, key)).map(_.toString).toList
    }

    override def insertJSON(tableName: String, json: String): Unit = putJson(json)

    /** Whole-document replace on the key fields, upsert when none matches;
      * a key field missing from the document is refused, as in MongoDBUtil.keyFilter. */
    override def upsertJSON(tableName: String, keyFields: java.util.List[String], json: String): Unit = {
        val o = JsonParser.parseString(json).getAsJsonObject
        val keys = keyFields.asScala.toList
        keys.foreach(k => if (!o.has(k) || o.get(k).isJsonNull) throw new IllegalArgumentException("Key field '" + k + "' not found in document"))
        val i = docs.indexWhere(d => keys.forall(k => matches(d, k, o.get(k).getAsString)))
        if (i >= 0) docs(i) = o else docs += o
    }

    override def deleteItemJSON(tableName: String, keyName: String, key: String, sortKeyName: String, sortKeyValue: Number): Unit = {
        val i = docs.indexWhere(matches(_, keyName, key))
        if (i >= 0) docs.remove(i)
    }

    override def setItemNameValue(tableName: String, keyName: String, key: String, valueName: String, value: String): Unit =
        first(keyName, key).foreach(_.addProperty(valueName, value))

    override def updateItemJSON(
        tableName: String,
        keyName: String,
        key: String,
        valueName: String,
        value: String,
        sortKeyName: String,
        sortKeyValue: Number
    ): Unit = first(keyName, key).foreach(_.add(valueName, JsonParser.parseString(value)))

    override def putItemJSON(
        tableName: String,
        keyName: String,
        key: String,
        valueName: String,
        value: String,
        sortKeyName: String,
        sortKeyValue: Number,
        extraFields: java.util.Map[String, AnyRef]
    ): Unit = {
        val o = new JsonObject
        o.addProperty(keyName, key)
        if (valueName != null) o.add(valueName, JsonParser.parseString(value))
        docs += o
    }

    override def getAllItemsAsJSON(tableName: String): List[String] = docs.map(_.toString).toList
    override def deleteAll(tableName: String): Long = { val n = docs.size; docs.clear(); n.toLong }
    override def getItemsKeysByKeyName(tableName: String, keyName: String): List[String] =
        docs.filter(d => d.has(keyName) && d.get(keyName).isJsonPrimitive).map(_.get(keyName).getAsString).toList
    override def getItemAttribute[T](tableName: String, keyName: String, key: String, attributeName: String): T = null.asInstanceOf[T]
    override def getPageOfItemsAsJSON(tableName: String, pageNbr: Int, maxPageSize: Int, sortField: String, sortDescending: Boolean): List[String] =
        getAllItemsAsJSON(tableName)
    override def getItemsSinceAsJSON(tableName: String, sortField: String, sinceEpochMs: Long, maxItems: Int): List[String] = Nil
}
