package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.JsonParser

import scala.collection.mutable

/** [[FakeSessionDb]] that also implements [[SessionDateFieldWrites]], so
  * [[SessionStore]] takes the branch it takes against MongoDBUtil. The writes
  * follow the Mongo semantics: `replaceWithDateField` replaces the first
  * document matching on the named field and never inserts.
  *
  * `replaces` records each replace's (keyName, key); `indexes` records each
  * index request; `beforeReplace` runs just before a replace (a spec uses it
  * to remove the session in between). `failUniqueIndex` makes the unique
  * index request throw. */
class FakeMongoSessionDb extends FakeSessionDb with SessionDateFieldWrites {
    val replaces: mutable.ArrayBuffer[(String, String)] = mutable.ArrayBuffer()
    val indexes: mutable.ArrayBuffer[String] = mutable.ArrayBuffer()
    var beforeReplace: () => Unit = () => ()
    var failUniqueIndex: Option[Exception] = None

    override def ensureTtlIndex(tableName: String, fieldName: String, expireAfterSeconds: Long): Unit =
        indexes += s"ttl:$tableName:$fieldName:$expireAfterSeconds"

    override def ensureUniqueIndexWhereExists(tableName: String, fieldName: String): Unit = {
        failUniqueIndex.foreach(e => throw e)
        indexes += s"uniqueWhereExists:$tableName:$fieldName"
    }

    override def insertWithDateField(tableName: String, json: String, dateFieldName: String, dateValueEpochMillis: Long): Unit = {
        val o = JsonParser.parseString(json).getAsJsonObject
        o.addProperty(dateFieldName, dateValueEpochMillis)
        docs += o
    }

    override def replaceWithDateField(
        tableName: String,
        keyName: String,
        key: String,
        json: String,
        dateFieldName: String,
        dateValueEpochMillis: Long
    ): Boolean = {
        beforeReplace()
        replaces += (keyName -> key)
        val i = docs.indexWhere(d => d.has(keyName) && d.get(keyName).isJsonPrimitive && d.get(keyName).getAsString == key)
        if (i < 0) false
        else {
            val o = JsonParser.parseString(json).getAsJsonObject
            o.addProperty(dateFieldName, dateValueEpochMillis)
            docs(i) = o
            true
        }
    }
}
