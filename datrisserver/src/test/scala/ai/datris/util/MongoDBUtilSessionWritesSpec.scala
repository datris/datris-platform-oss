package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.mongodb.client.model.{Filters, IndexOptions}
import com.mongodb.client.result.UpdateResult
import com.mongodb.client.{MongoCollection, MongoDatabase}
import org.bson.Document
import org.bson.conversions.Bson
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{mock, verify, verifyNoMoreInteractions, when}
import org.scalatest.funsuite.AnyFunSuite

/** Story: sign-in session tokens are stored as hashes
  * (plans/stories/session-tokens-hashed.md), review follow-up.
  *
  * The two MongoDBUtil writes SessionStore relies on, against a mocked
  * collection: `replaceWithDateField` filters on the named field and replaces
  * without inserting; `ensureUniqueIndexWhereExists` creates a unique index
  * partial on the field existing. */
class MongoDBUtilSessionWritesSpec extends AnyFunSuite {

    private def fixture(): (MongoDBUtil, MongoCollection[Document]) = {
        val database = mock(classOf[MongoDatabase])
        val coll = mock(classOf[MongoCollection[_]]).asInstanceOf[MongoCollection[Document]]
        when(database.getCollection("sessions")).thenReturn(coll)
        (new MongoDBUtil(database), coll)
    }

    test("replaceWithDateField filters on the named field, replaces without upsert, and adds the BSON Date") {
        val (util, coll) = fixture()
        when(coll.replaceOne(any(classOf[Bson]), any(classOf[Document]))).thenReturn(UpdateResult.acknowledged(1L, 1L, null))

        assert(util.replaceWithDateField("sessions", "token", "abc", """{"tokenHash":"h","username":"u"}""", "expiresAtDate", 1234L))

        val filter = ArgumentCaptor.forClass(classOf[Bson])
        val doc = ArgumentCaptor.forClass(classOf[Document])
        verify(coll).replaceOne(filter.capture(), doc.capture())
        // the two-argument replace is the only call: no ReplaceOptions, so no upsert
        verifyNoMoreInteractions(coll)
        assert(filter.getValue.toBsonDocument() == Filters.eq("token", "abc").toBsonDocument())
        assert(doc.getValue.get("expiresAtDate") == new java.util.Date(1234L))
        assert(doc.getValue.getString("tokenHash") == "h")
        assert(!doc.getValue.containsKey("token"))
    }

    test("replaceWithDateField is false when no document matched") {
        val (util, coll) = fixture()
        when(coll.replaceOne(any(classOf[Bson]), any(classOf[Document]))).thenReturn(UpdateResult.acknowledged(0L, 0L, null))
        assert(!util.replaceWithDateField("sessions", "tokenHash", "h", """{"tokenHash":"h"}""", "expiresAtDate", 1L))
    }

    test("ensureUniqueIndexWhereExists creates a unique index on the field, partial on the field existing") {
        val (util, coll) = fixture()
        util.ensureUniqueIndexWhereExists("sessions", "tokenHash")

        val keys = ArgumentCaptor.forClass(classOf[Bson])
        val options = ArgumentCaptor.forClass(classOf[IndexOptions])
        verify(coll).createIndex(keys.capture(), options.capture())
        verifyNoMoreInteractions(coll)
        assert(keys.getValue.toBsonDocument() == (new Document("tokenHash", 1): Bson).toBsonDocument())
        assert(options.getValue.isUnique)
        assert(options.getValue.getPartialFilterExpression != null, "the index covers only documents that have the field")
        assert(options.getValue.getPartialFilterExpression.toBsonDocument() == Filters.exists("tokenHash", true).toBsonDocument())
    }
}
