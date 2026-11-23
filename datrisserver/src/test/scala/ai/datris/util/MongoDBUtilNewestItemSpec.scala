package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.mongodb.client.model.Filters
import com.mongodb.client.{FindIterable, MongoCollection, MongoDatabase}
import org.bson.Document
import org.bson.conversions.Bson
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, anyInt}
import org.mockito.Mockito.{mock, verify, verifyNoMoreInteractions, when}
import org.scalatest.funsuite.AnyFunSuite

/** Tap runs and owner access, report-only release, review follow-up: the
  * last-editor lookup reads one document, the newest version's author, not
  * the version list. `getNewestItemByKeyAsJSON` against a mocked collection:
  * filter on the key, sort descending on the version, limit 1, project only
  * the field needed. */
class MongoDBUtilNewestItemSpec extends AnyFunSuite {

    private def fixture(answer: Document): (MongoDBUtil, MongoCollection[Document], FindIterable[Document]) = {
        val database = mock(classOf[MongoDatabase])
        val coll = mock(classOf[MongoCollection[_]]).asInstanceOf[MongoCollection[Document]]
        val found = mock(classOf[FindIterable[_]]).asInstanceOf[FindIterable[Document]]
        when(database.getCollection("datris-tap-version")).thenReturn(coll)
        when(coll.find(any(classOf[Bson]))).thenReturn(found)
        when(found.sort(any(classOf[Bson]))).thenReturn(found)
        when(found.limit(anyInt())).thenReturn(found)
        when(found.projection(any(classOf[Bson]))).thenReturn(found)
        when(found.first()).thenReturn(answer)
        (new MongoDBUtil(database), coll, found)
    }

    test("getNewestItemByKeyAsJSON filters on the key, sorts descending, takes one document and projects only the fields asked for") {
        val (util, coll, found) = fixture(new Document("createdBy", "rita"))
        val out = util.getNewestItemByKeyAsJSON("datris-tap-version", "entityName", "weather", "version", Seq("createdBy"))
        assert(out.map(com.google.gson.JsonParser.parseString(_).getAsJsonObject.get("createdBy").getAsString).contains("rita"), out.toString)

        val filter = ArgumentCaptor.forClass(classOf[Bson])
        verify(coll).find(filter.capture())
        verifyNoMoreInteractions(coll)
        assert(filter.getValue.toBsonDocument() == Filters.eq("entityName", "weather").toBsonDocument())

        val sort = ArgumentCaptor.forClass(classOf[Bson])
        val projection = ArgumentCaptor.forClass(classOf[Bson])
        verify(found).sort(sort.capture())
        verify(found).limit(1)
        verify(found).projection(projection.capture())
        verify(found).first()
        verifyNoMoreInteractions(found)
        assert(sort.getValue.toBsonDocument() == (new Document("version", -1): Bson).toBsonDocument())
        assert(projection.getValue.toBsonDocument() == (new Document("_id", 0).append("createdBy", 1): Bson).toBsonDocument())
    }

    test("getNewestItemByKeyAsJSON is None when no document matches") {
        val (util, _, _) = fixture(null)
        assert(util.getNewestItemByKeyAsJSON("datris-tap-version", "entityName", "weather", "version", Seq("createdBy")).isEmpty)
    }

    test("the trait's default picks the highest numeric sort field from the matching items and keeps only the fields asked for") {
        val db = new NoSQLDbUtility {
            def getItemsKeysByKeyName(tableName: String, keyName: String): List[String] = Nil
            def getItemAttribute[T](tableName: String, keyName: String, key: String, attributeName: String): T = null.asInstanceOf[T]
            def setItemNameValue(tableName: String, keyName: String, key: String, valueName: String, value: String): Unit = ()
            def getItemJSON(tableName: String, keyName: String, key: String, valueName: String): Option[String] = None
            def putItemJSON(t: String, k: String, key: String, v: String, value: String, s: String, sv: Number, e: java.util.Map[String, AnyRef]): Unit = ()
            def updateItemJSON(t: String, k: String, key: String, v: String, value: String, s: String, sv: Number): Unit = ()
            def deleteItemJSON(t: String, k: String, key: String, s: String, sv: Number): Unit = ()
            def queryJSONItemsByKey(tableName: String, keyName: String, key: String): List[String] =
                List(
                    """{"entityName":"w","version":2,"createdBy":"ed","config":"big"}""",
                    """{"entityName":"w","version":10,"createdBy":"rita","config":"big"}""",
                    """{"entityName":"w","version":3,"createdBy":"ada","config":"big"}"""
                )
            def getAllItemsAsJSON(tableName: String): List[String] = Nil
            def getPageOfItemsAsJSON(tableName: String, pageNbr: Int, maxPageSize: Int, sortField: String, sortDescending: Boolean): List[String] = Nil
            def getItemsSinceAsJSON(tableName: String, sortField: String, sinceEpochMs: Long, maxItems: Int): List[String] = Nil
            def insertJSON(tableName: String, json: String): Unit = ()
            def deleteAll(tableName: String): Long = 0L
            def upsertJSON(tableName: String, keyFields: java.util.List[String], json: String): Unit = ()
        }
        assert(db.getNewestItemByKeyAsJSON("t", "entityName", "w", "version", Seq("createdBy")).contains("""{"createdBy":"rita"}"""))
    }
}
