package ai.datris.audit

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisEnvironment
import ai.datris.util.{MongoDBUtil, NoSQLDbUtil}
import com.google.gson.{JsonArray, JsonObject}
import com.mongodb.client.model.{Filters, Sorts}
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.json.{JsonMode, JsonWriterSettings}
import org.bson.types.ObjectId

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters._

/** Read side of the audit log: filtered, cursor-paginated queries over
  * `{env}-audit-log`, plus the facet lists the UI's filter dropdowns use. */
object AuditLogIO {

    val DefaultLimit = 100
    val MaxLimit = 1000
    val ExportMaxRows = 50000

    private val jsonSettings = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build()

    case class Query(
        since: Option[Instant] = None,
        until: Option[Instant] = None,
        category: Option[String] = None,
        action: Option[String] = None,
        actor: Option[String] = None, // actor.label
        actorType: Option[String] = None,
        outcome: Option[String] = None,
        resource: Option[String] = None, // substring, case-insensitive, on resource.name
        limit: Int = DefaultLimit,
        cursor: Option[String] = None // last _id from the previous page
    )

    case class Page(entries: List[Document], nextCursor: Option[String])

    private def collection(): Option[com.mongodb.client.MongoCollection[Document]] =
        NoSQLDbUtil match {
            case m: MongoDBUtil => Some(m.collection(DatrisEnvironment.current.auditLogTableName))
            case _ => None
        }

    private def filterFor(q: Query): Bson = {
        val parts = List.newBuilder[Bson]
        q.since.foreach(s => parts += Filters.gte("tsDate", java.util.Date.from(s)))
        q.until.foreach(u => parts += Filters.lte("tsDate", java.util.Date.from(u)))
        q.category.foreach(c => parts += Filters.eq("category", c))
        q.action.foreach(a => parts += Filters.eq("action", a))
        q.actor.foreach(a => parts += Filters.eq("actor.label", a))
        q.actorType.foreach(t => parts += Filters.eq("actor.type", t))
        q.outcome.foreach(o => parts += Filters.eq("outcome", o))
        q.resource.foreach(r => parts += Filters.regex("resource.name", java.util.regex.Pattern.quote(r), "i"))
        q.cursor.filter(ObjectId.isValid).foreach(c => parts += Filters.lt("_id", new ObjectId(c)))
        val all = parts.result()
        if (all.isEmpty) new Document() else Filters.and(all.asJava)
    }

    /** Newest first. `_id` order equals insertion order because a single
      * writer thread mints them, so the cursor is just the last id seen. */
    def query(q: Query): Page = {
        val coll = collection().getOrElse(return Page(Nil, None))
        val limit = math.max(1, math.min(q.limit, MaxLimit))
        val docs = coll.find(filterFor(q))
            .sort(Sorts.descending("_id"))
            .limit(limit + 1)
            .asScala
            .toList
        val page = docs.take(limit)
        val next = if (docs.size > limit) page.lastOption.map(_.getObjectId("_id").toHexString) else None
        Page(page, next)
    }

    /** Stream every matching row (bounded) to a callback — the CSV export. */
    def foreachMatching(q: Query, max: Int)(f: Document => Unit): Int = {
        val coll = collection().getOrElse(return 0)
        var n = 0
        val it = coll.find(filterFor(q)).sort(Sorts.descending("_id")).limit(max).iterator()
        try {
            while (it.hasNext) { f(it.next()); n += 1 }
        } finally it.close()
        n
    }

    /** Public JSON shape for one entry: the stored document with `_id`
      * surfaced as `id` and the BSON date dropped (ts is the ISO string). */
    def toPublicJson(doc: Document): JsonObject = {
        val copy = new Document(doc)
        val id = Option(copy.getObjectId("_id")).map(_.toHexString)
        copy.remove("_id")
        copy.remove("tsDate")
        val obj = com.google.gson.JsonParser.parseString(copy.toJson(jsonSettings)).getAsJsonObject
        id.foreach(obj.addProperty("id", _))
        obj
    }

    // ------------------------------------------------------------------
    // Facets (filter dropdown source) — cached 60s
    // ------------------------------------------------------------------

    private case class CachedFacets(json: JsonObject, expiresAt: Long, table: String)
    private val facetCache = new AtomicReference[CachedFacets](null)
    private val FacetTtlMs = 60000L
    private val FacetWindowDays = 30L

    def facets(): JsonObject = {
        val table = DatrisEnvironment.current.auditLogTableName
        val cached = facetCache.get()
        val now = System.currentTimeMillis()
        if (cached != null && cached.expiresAt > now && cached.table == table) return cached.json

        val out = new JsonObject()
        collection() match {
            case None =>
                out.add("categories", new JsonArray()); out.add("actions", new JsonArray())
                out.add("actors", new JsonArray()); out.add("actorTypes", new JsonArray())
            case Some(coll) =>
                val window = Filters.gte("tsDate", java.util.Date.from(Instant.now().minusSeconds(FacetWindowDays * 86400L)))
                def distinct(field: String): JsonArray = {
                    val arr = new JsonArray()
                    coll.distinct(field, window, classOf[String]).asScala.toList.filter(_ != null).sorted.foreach(arr.add)
                    arr
                }
                out.add("categories", distinct("category"))
                out.add("actions", distinct("action"))
                out.add("actors", distinct("actor.label"))
                out.add("actorTypes", distinct("actor.type"))
        }
        out.addProperty("windowDays", FacetWindowDays)
        facetCache.set(CachedFacets(out, now + FacetTtlMs, table))
        out
    }
}
