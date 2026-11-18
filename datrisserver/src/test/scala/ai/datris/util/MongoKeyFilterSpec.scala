package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import com.mongodb.MongoClientSettings
import org.bson.{BsonDocument, BsonInt32, BsonString, BsonValue, Document}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: Column names from ingested data: checked once at ingest, quoted at
  * every destination, never able to change what a load does
  * (plans/stories/ingest-column-name-escaping.md), Decision 6.
  *
  * Pinned seam, shared by both `upsertJSON` overloads (pure, no I/O):
  * {{{
  * object MongoDBUtil {
  *     private[util] def keyFilter(doc: Document, keyFields: Seq[String]): org.bson.conversions.Bson
  * }
  * }}}
  * Each key field is matched with an explicit equality operator
  * (`{field: {$eq: value}}`), so a key VALUE is always compared as a value and
  * never read as query operators. Several key fields are ANDed. A key field
  * NAME starting with `$` throws a DatrisException; a key field absent from
  * the document keeps the message "Key field '<k>' not found in document".
  *
  * v1.60.0 built `Filters.eq(keyField, doc.get(keyField))`, which renders
  * `{field: value}`: with a sub-document value whose keys start with `$`
  * that is an operator expression, not a value. */
class MongoKeyFilterSpec extends AnyFunSuite {

    private def render(doc: Document, keys: Seq[String]): BsonDocument =
        MongoDBUtil.keyFilter(doc, keys).toBsonDocument(classOf[BsonDocument], MongoClientSettings.getDefaultCodecRegistry)

    /** The value a rendered `{field: {$eq: v}}` clause compares `field` with. */
    private def eqValue(clause: BsonDocument, field: String): BsonValue = {
        assert(clause.keySet().asScala == Set(field), "one clause on " + field + ": " + clause.toJson)
        val cond = clause.get(field)
        assert(cond.isDocument, field + " is matched through an explicit operator: " + clause.toJson)
        val ops = cond.asDocument()
        assert(ops.keySet().asScala == Set("$eq"), field + " is matched by $eq only: " + clause.toJson)
        ops.get("$eq")
    }

    /** The equality clauses of a rendered filter, `$and` or one field. */
    private def clauses(filter: BsonDocument): List[BsonDocument] =
        if (filter.containsKey("$and")) {
            assert(filter.keySet().asScala == Set("$and"), filter.toJson)
            filter.getArray("$and").getValues.asScala.map(_.asDocument()).toList
        } else List(filter)

    test("a scalar key value matches by equality on that field") {
        val doc = Document.parse("""{"id": 1, "v": "a"}""")
        val f = render(doc, Seq("id"))
        assert(eqValue(f, "id") == new BsonInt32(1), f.toJson)

        val s = render(Document.parse("""{"code": "abc", "v": "a"}"""), Seq("code"))
        assert(eqValue(s, "code") == new BsonString("abc"), s.toJson)

        // dates, booleans and ObjectIds stay the same typed value
        val typed = Document.parse("""{"id": {"$oid": "5f1d7a1c2b3c4d5e6f708192"}, "on": true, "at": {"$date": "2026-11-18T00:00:00Z"}}""")
        assert(eqValue(render(typed, Seq("id")), "id").isObjectId)
        assert(eqValue(render(typed, Seq("on")), "on").isBoolean)
        assert(eqValue(render(typed, Seq("at")), "at").isDateTime)
    }

    test("a sub-document key value with an operator-looking key is compared as a value, not run as an operator") {
        val doc = Document.parse("""{"id": {"$ne": null}, "v": "evil"}""")
        val f = render(doc, Seq("id"))
        val v = eqValue(f, "id")
        assert(v.isDocument, f.toJson)
        assert(v.asDocument().containsKey("$ne"), "the sub-document is the compared value: " + f.toJson)

        val gt = render(Document.parse("""{"id": {"$gt": 0, "$where": "1"}}"""), Seq("id"))
        val gv = eqValue(gt, "id").asDocument()
        assert(gv.keySet().asScala == Set("$gt", "$where"), gt.toJson)
    }

    test("two key fields are ANDed") {
        val doc = Document.parse("""{"id": 1, "region": "east", "v": "a"}""")
        val f = render(doc, Seq("id", "region"))
        val cs = clauses(f)
        assert(cs.size == 2, f.toJson)
        assert(eqValue(cs.head, "id") == new BsonInt32(1), f.toJson)
        assert(eqValue(cs(1), "region") == new BsonString("east"), f.toJson)

        val evil = render(Document.parse("""{"id": {"$ne": null}, "region": {"$exists": true}}"""), Seq("id", "region"))
        val ecs = clauses(evil)
        assert(ecs.size == 2 && ecs.forall(c => c.values().asScala.head.asDocument().keySet().asScala == Set("$eq")), evil.toJson)
    }

    test("a key field name starting with $ throws") {
        val doc = new Document("$where", "1").append("id", 1)
        val e = intercept[DatrisException](MongoDBUtil.keyFilter(doc, Seq("$where")))
        assert(e.getMessage.contains("$where"), e.getMessage)
        val e2 = intercept[DatrisException](MongoDBUtil.keyFilter(doc, Seq("id", "$where")))
        assert(e2.getMessage.contains("$where"), e2.getMessage)
    }

    test("a missing key field keeps the existing 'not found in document' message") {
        val doc = Document.parse("""{"id": 1}""")
        val e = intercept[DatrisException](MongoDBUtil.keyFilter(doc, Seq("missing")))
        assert(e.getMessage == "Key field 'missing' not found in document", e.getMessage)
        val e2 = intercept[DatrisException](MongoDBUtil.keyFilter(doc, Seq("id", "region")))
        assert(e2.getMessage == "Key field 'region' not found in document", e2.getMessage)
    }
}
