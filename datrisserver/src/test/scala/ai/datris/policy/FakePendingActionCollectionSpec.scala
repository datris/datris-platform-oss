package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.MongoDBUtil
import com.mongodb.client.model.{Filters, Sorts, Updates}
import org.bson.Document
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** The in-memory collection the token-hash specs run PendingActionIO
  * against evaluates filters and updates the way MongoDB does for the
  * operators it supports, and refuses the rest. */
class FakePendingActionCollectionSpec extends AnyFunSuite {

    private def fixture() = {
        val f = new FakePendingActionCollection
        val c = new MongoDBUtil(f.database).collection("any")
        c.insertOne(new Document("_id", "a").append("state", "approved").append("h", "x").append("actor", new Document("label", "agent")))
        c.insertOne(new Document("_id", "b").append("state", "pending").append("actor", new Document("label", "other")))
        (f, c)
    }

    test("equality, nested paths, null matching a missing field, $exists, $and, $or, $ne, $in") {
        val (_, c) = fixture()
        def ids(b: org.bson.conversions.Bson) = c.find(b).asScala.map(_.getString("_id")).toList.sorted
        assert(ids(Filters.eq("state", "approved")) == List("a"))
        assert(ids(Filters.eq("actor.label", "other")) == List("b"))
        assert(ids(Filters.eq("h", null)) == List("b"))
        assert(ids(Filters.exists("h")) == List("a"))
        assert(ids(Filters.exists("h", false)) == List("b"))
        assert(ids(Filters.and(Filters.eq("_id", "a"), Filters.eq("h", "y"))) == Nil)
        assert(ids(Filters.or(Filters.eq("_id", "a"), Filters.eq("_id", "b"))) == List("a", "b"))
        assert(ids(Filters.ne("state", "approved")) == List("b"))
        assert(ids(Filters.in("_id", "b", "z")) == List("b"))
        assert(Option(c.find(Filters.eq("_id", "zz")).first()).isEmpty)
        assert(c.countDocuments(Filters.eq("state", "pending")) == 1L)
        assert(c.find(new Document()).sort(Sorts.descending("_id")).limit(1).asScala.map(_.getString("_id")).toList == List("b"))
    }

    test("conditional updates: $set and $unset in one update, matched and modified counts, updateMany") {
        val (f, c) = fixture()
        val r = c.updateOne(Filters.and(Filters.eq("_id", "a"), Filters.eq("h", "x")), Updates.combine(Updates.set("used", "now"), Updates.unset("h")))
        assert(r.getModifiedCount == 1L)
        assert(f.doc("a").exists(d => d.getString("used") == "now" && !d.containsKey("h")))
        assert(c.updateOne(Filters.and(Filters.eq("_id", "a"), Filters.eq("h", "x")), Updates.set("used", "again")).getModifiedCount == 0L)
        val m = c.updateMany(Filters.exists("state"), Updates.unset("state"))
        assert(m.getModifiedCount == 2L)
        assert(c.updateMany(Filters.exists("state"), Updates.unset("state")).getModifiedCount == 0L)
        assert(f.all.forall(!_.containsKey("state")))
    }

    test("an operator the fake does not model is refused, not guessed") {
        val (_, c) = fixture()
        intercept[UnsupportedOperationException](c.find(Filters.regex("state", "^app")).first())
        intercept[UnsupportedOperationException](c.updateOne(Filters.eq("_id", "a"), Updates.inc("n", 1)))
    }
}
