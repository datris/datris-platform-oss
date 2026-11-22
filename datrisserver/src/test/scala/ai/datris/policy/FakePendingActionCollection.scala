package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.mongodb.client.result.UpdateResult
import com.mongodb.client.{FindIterable, MongoCollection, MongoCursor, MongoDatabase}
import org.bson.codecs.{DecoderContext, DocumentCodec}
import org.bson.conversions.Bson
import org.bson.{BsonDocument, BsonDocumentReader, BsonValue, Document}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{mock, when}
import org.mockito.invocation.InvocationOnMock

import scala.collection.JavaConverters._
import scala.collection.mutable

/** An in-memory `MongoCollection[Document]` for [[PendingActionIO]], so the
  * store's own MongoDB code (its Filters / Updates, conditional updates) runs
  * unchanged against it. Filters are evaluated the way MongoDB evaluates
  * them for the operators used here (`$and`, `$or`, field equality incl.
  * null-matches-missing, `$eq`, `$ne`, `$exists`, `$in`); updates apply
  * `$set` and `$unset`. Any other operator throws, so a spec never passes
  * on a filter the fake did not understand. Each update runs atomically
  * (one lock), as a single-document update does in MongoDB.
  *
  * `filters` records every filter the store sent (find, update, count), and
  * `updates` every update document, so a spec can check what was matched
  * on. `database` answers `getCollection(_)` with this collection. */
class FakePendingActionCollection {

    private val lock = new Object
    private val docs = mutable.LinkedHashMap[String, BsonDocument]()
    val filters: mutable.ArrayBuffer[BsonDocument] = mutable.ArrayBuffer()
    val updates: mutable.ArrayBuffer[BsonDocument] = mutable.ArrayBuffer()

    private val codec = new DocumentCodec()

    private def toBson(b: Bson): BsonDocument = b.toBsonDocument()
    private def toDoc(b: BsonDocument): Document = codec.decode(new BsonDocumentReader(b), DecoderContext.builder().build())

    /** Puts a stored document as is (a record written by an earlier version). */
    def put(d: Document): Unit = lock.synchronized {
        val b = toBson(d)
        docs.put(b.getString("_id").getValue, b)
    }

    def all: List[Document] = lock.synchronized(docs.values.map(toDoc).toList)
    def doc(id: String): Option[Document] = lock.synchronized(docs.get(id).map(toDoc))
    def clearRecords(): Unit = lock.synchronized { filters.clear(); updates.clear() }

    // ------------------------------------------------------------ evaluation

    private def lookup(d: BsonDocument, path: String): Option[BsonValue] = {
        val parts = path.split('.').toList
        def go(cur: BsonValue, ps: List[String]): Option[BsonValue] = ps match {
            case Nil => Some(cur)
            case p :: rest =>
                if (cur.isDocument && cur.asDocument().containsKey(p)) go(cur.asDocument().get(p), rest) else None
        }
        go(d, parts)
    }

    private def isOperatorDoc(v: BsonValue): Boolean =
        v.isDocument && !v.asDocument().isEmpty && v.asDocument().keySet().asScala.forall(_.startsWith("$"))

    private def eqValue(found: Option[BsonValue], want: BsonValue): Boolean =
        if (want.isNull) found.forall(_.isNull) else found.contains(want)

    def matches(d: BsonDocument, f: BsonDocument): Boolean =
        f.entrySet().asScala.forall { e =>
            val k = e.getKey
            val v = e.getValue
            k match {
                case "$and" => v.asArray().getValues.asScala.forall(x => matches(d, x.asDocument()))
                case "$or" => v.asArray().getValues.asScala.exists(x => matches(d, x.asDocument()))
                case op if op.startsWith("$") => throw new UnsupportedOperationException("FakePendingActionCollection: filter operator " + op)
                case field =>
                    val found = lookup(d, field)
                    if (isOperatorDoc(v)) v.asDocument().entrySet().asScala.forall { oe =>
                        oe.getKey match {
                            case "$eq" => eqValue(found, oe.getValue)
                            case "$ne" => !eqValue(found, oe.getValue)
                            case "$exists" => found.isDefined == oe.getValue.asBoolean().getValue
                            case "$in" => oe.getValue.asArray().getValues.asScala.exists(w => eqValue(found, w))
                            case other => throw new UnsupportedOperationException("FakePendingActionCollection: field operator " + other)
                        }
                    }
                    else if (v.isRegularExpression) throw new UnsupportedOperationException("FakePendingActionCollection: regular expression on " + field)
                    else eqValue(found, v)
            }
        }

    private def applyUpdate(d: BsonDocument, u: BsonDocument): BsonDocument = {
        val out = d.clone()
        u.entrySet().asScala.foreach { e =>
            e.getKey match {
                case "$set" => e.getValue.asDocument().entrySet().asScala.foreach(s => out.put(s.getKey, s.getValue))
                case "$unset" => e.getValue.asDocument().keySet().asScala.foreach(out.remove(_))
                case other => throw new UnsupportedOperationException("FakePendingActionCollection: update operator " + other)
            }
        }
        out
    }

    private def update(filter: Bson, upd: Bson, many: Boolean): UpdateResult = lock.synchronized {
        val f = toBson(filter)
        val u = toBson(upd)
        filters += f
        updates += u
        val hits = docs.values.filter(matches(_, f)).toList
        val targets = if (many) hits else hits.take(1)
        var modified = 0L
        targets.foreach { d =>
            val next = applyUpdate(d, u)
            if (next != d) modified += 1
            docs.put(d.getString("_id").getValue, next)
        }
        UpdateResult.acknowledged(targets.size.toLong, modified, null)
    }

    private def find(filter: Bson): FindIterable[Document] = {
        val f = lock.synchronized {
            val b = Option(filter).map(toBson).getOrElse(new BsonDocument())
            filters += b
            b
        }
        var limit = 0
        var sort: Option[BsonDocument] = None
        def results(): List[Document] = lock.synchronized {
            var hits = docs.values.filter(matches(_, f)).toList
            sort.foreach { s =>
                val (field, dir) = (s.getFirstKey, s.get(s.getFirstKey).asNumber().intValue())
                hits = hits.sortBy(d => lookup(d, field).map(_.toString).getOrElse(""))
                if (dir < 0) hits = hits.reverse
            }
            (if (limit > 0) hits.take(limit) else hits).map(toDoc)
        }
        val it = mock(classOf[FindIterable[_]]).asInstanceOf[FindIterable[Document]]
        when(it.first()).thenAnswer((_: InvocationOnMock) => results().headOption.orNull)
        when(it.limit(org.mockito.ArgumentMatchers.anyInt())).thenAnswer { (i: InvocationOnMock) =>
            limit = i.getArgument[Int](0); it
        }
        when(it.sort(any(classOf[Bson]))).thenAnswer { (i: InvocationOnMock) =>
            sort = Option(i.getArgument[Bson](0)).map(toBson); it
        }
        when(it.iterator()).thenAnswer { (_: InvocationOnMock) =>
            val rows = results().iterator
            val c = mock(classOf[MongoCursor[_]]).asInstanceOf[MongoCursor[Document]]
            when(c.hasNext).thenAnswer((_: InvocationOnMock) => rows.hasNext)
            when(c.next()).thenAnswer((_: InvocationOnMock) => rows.next())
            c
        }
        it
    }

    val collection: MongoCollection[Document] = {
        val c = mock(classOf[MongoCollection[_]]).asInstanceOf[MongoCollection[Document]]
        when(c.insertOne(any(classOf[Document]))).thenAnswer { (i: InvocationOnMock) =>
            val d = i.getArgument[Document](0)
            lock.synchronized {
                val b = toBson(d)
                val id = b.getString("_id").getValue
                if (docs.contains(id)) throw new IllegalStateException("duplicate _id " + id)
                docs.put(id, b)
            }
            null
        }
        when(c.find(any(classOf[Bson]))).thenAnswer((i: InvocationOnMock) => find(i.getArgument[Bson](0)))
        when(c.find()).thenAnswer((_: InvocationOnMock) => find(new BsonDocument()))
        when(c.updateOne(any(classOf[Bson]), any(classOf[Bson]))).thenAnswer((i: InvocationOnMock) =>
            update(i.getArgument[Bson](0), i.getArgument[Bson](1), many = false)
        )
        when(c.updateMany(any(classOf[Bson]), any(classOf[Bson]))).thenAnswer((i: InvocationOnMock) =>
            update(i.getArgument[Bson](0), i.getArgument[Bson](1), many = true)
        )
        when(c.countDocuments(any(classOf[Bson]))).thenAnswer { (i: InvocationOnMock) =>
            lock.synchronized {
                val f = toBson(i.getArgument[Bson](0))
                filters += f
                docs.values.count(matches(_, f)).toLong: java.lang.Long
            }
        }
        c
    }

    val database: MongoDatabase = {
        val db = mock(classOf[MongoDatabase])
        when(db.getCollection(anyString())).thenReturn(collection)
        db
    }
}
