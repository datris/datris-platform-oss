package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisEnvironment
import ai.datris.util.{MongoDBUtil, NoSQLDbUtil}
import com.mongodb.client.model.{Filters, Sorts, Updates}
import org.bson.Document
import org.bson.conversions.Bson
import org.slf4j.LoggerFactory

import java.time.Instant
import scala.collection.JavaConverters._
import scala.collection.mutable

/** Mongo store for incidents: `{env}-incident`. Incidents are few (bounded
  * by maxOpenIncidents and closed by outcome), so no TTL — they are the
  * operational history the Ops chat and Activity view read. */
object IncidentIO {

    private val logger = LoggerFactory.getLogger(getClass)
    private val indexed = mutable.Set[String]()
    val MaxListLimit = 200

    /** The persistence behind every IncidentIO call, so IncidentRunnerSpec can
      * run the loop without Mongo. `list(Some("open"), ...)` means OpenStates.
      * `raw` is the stored document (fields outside the case class, e.g.
      * nextActionIndex / scratchVerdict). */
    trait Store {
        def insert(i: Incident): Unit
        def get(id: String): Option[Incident]
        def list(state: Option[String], limit: Int): List[Incident]
        def transition(id: String, from: Set[String], to: String, sets: Seq[(String, Any)]): Boolean
        def appendStep(id: String, step: IncidentStep): Unit
        def set(id: String, sets: Seq[(String, Any)]): Unit
        def incrementCounters(id: String, aiCalls: Int, actions: Int): Unit
        def raw(id: String): Option[Document]
    }

    /** Spec seam: when non-null, served instead of Mongo. */
    @volatile private[incident] var store: Store = null

    private def collection(): com.mongodb.client.MongoCollection[Document] =
        NoSQLDbUtil match {
            case m: MongoDBUtil =>
                val table = DatrisEnvironment.current.incidentTableName
                val coll = m.collection(table)
                ensureIndexes(table, coll)
                coll
            case _ => throw new IllegalStateException("Incidents require the MongoDB config store")
        }

    private def ensureIndexes(table: String, coll: com.mongodb.client.MongoCollection[Document]): Unit = synchronized {
        if (indexed.contains(table)) return
        try {
            coll.createIndex(new Document("state", 1))
            coll.createIndex(new Document("resourceType", 1).append("resource", 1))
            coll.createIndex(new Document("openedAtDate", -1))
            indexed += table
        } catch {
            case e: Exception => logger.warn("Could not ensure incident indexes on " + table + ": " + e.getMessage)
        }
    }

    def insert(i: Incident): Unit = {
        val st = store
        if (st != null) st.insert(i) else collection().insertOne(i.toDocument)
        ()
    }

    def get(id: String): Option[Incident] = {
        val st = store
        if (st != null) st.get(id)
        else Option(collection().find(Filters.eq("_id", id)).first()).map(Incident.fromDocument)
    }

    /** The stored document as-is. */
    def raw(id: String): Option[Document] = {
        val st = store
        if (st != null) st.raw(id)
        else Option(collection().find(Filters.eq("_id", id)).first())
    }

    def openFor(resourceType: String, resourceName: String): Option[Incident] = {
        val st = store
        if (st != null) return st.list(Some("open"), MaxListLimit).find(i => i.resourceType == resourceType && i.resourceName == resourceName)
        Option(collection().find(Filters.and(
            Filters.eq("resourceType", resourceType),
            Filters.eq("resource", resourceName),
            Filters.in("state", Incident.OpenStates.toSeq.asJava)
        )).first()).map(Incident.fromDocument)
    }

    def countOpen(): Long = {
        val st = store
        if (st != null) st.list(Some("open"), MaxListLimit).size.toLong
        else collection().countDocuments(Filters.in("state", Incident.OpenStates.toSeq.asJava))
    }

    /** Most recent close time for a resource — the cooldown anchor. */
    def lastClosedAt(resourceType: String, resourceName: String): Option[Instant] = {
        val st = store
        if (st != null)
            return st.list(None, MaxListLimit)
                .filter(i => i.resourceType == resourceType && i.resourceName == resourceName && Incident.ClosedStates.contains(i.state))
                .flatMap(_.closedAt).sorted.lastOption
        Option(collection().find(Filters.and(
            Filters.eq("resourceType", resourceType),
            Filters.eq("resource", resourceName),
            Filters.in("state", Incident.ClosedStates.toSeq.asJava)
        )).sort(Sorts.descending("closedAtDate")).first())
            .map(Incident.fromDocument)
            .flatMap(_.closedAt)
    }

    def list(state: Option[String], limit: Int): List[Incident] = {
        val st = store
        if (st != null) return st.list(state, math.max(1, math.min(limit, MaxListLimit)))
        val filter: Bson = state match {
            case Some("open") => Filters.in("state", Incident.OpenStates.toSeq.asJava)
            case Some(s) => Filters.eq("state", s)
            case None => new Document()
        }
        collection().find(filter)
            .sort(Sorts.descending("openedAtDate"))
            .limit(math.max(1, math.min(limit, MaxListLimit)))
            .asScala.map(Incident.fromDocument).toList
    }

    def listOpen(): List[Incident] = list(Some("open"), MaxListLimit)

    /** Conditional state transition — only from the expected state. */
    def transition(id: String, from: Set[String], to: String, sets: (String, Any)*): Boolean = {
        val st = store
        if (st != null) return st.transition(id, from, to, sets)
        val base: Seq[Bson] = Seq(Updates.set("state", to)) ++ sets.map { case (k, v) => Updates.set(k, v) }
        val withClose =
            if (Incident.ClosedStates.contains(to))
                base ++ Seq(
                    Updates.set("closedAt", Instant.now().toString),
                    Updates.set("closedAtDate", java.util.Date.from(Instant.now()))
                )
            else base
        val r = collection().updateOne(
            Filters.and(Filters.eq("_id", id), Filters.in("state", from.toSeq.asJava)),
            Updates.combine(withClose.asJava)
        )
        r.getModifiedCount == 1
    }

    def appendStep(id: String, step: IncidentStep): Unit = {
        val st = store
        if (st != null) st.appendStep(id, step)
        else collection().updateOne(Filters.eq("_id", id), Updates.push("steps", Document.parse(step.toJson.toString)))
        ()
    }

    def set(id: String, sets: (String, Any)*): Unit = {
        if (sets.isEmpty) return
        val st = store
        if (st != null) return st.set(id, sets)
        collection().updateOne(Filters.eq("_id", id), Updates.combine(sets.map { case (k, v) => Updates.set(k, v) }.asJava))
    }

    def incrementCounters(id: String, aiCalls: Int = 0, actions: Int = 0): Unit = {
        val st = store
        if (st != null) return st.incrementCounters(id, aiCalls, actions)
        collection().updateOne(
            Filters.eq("_id", id),
            Updates.combine(
                Updates.inc("aiCalls", aiCalls),
                Updates.inc("actionsTaken", actions)
            )
        )
        ()
    }
}
