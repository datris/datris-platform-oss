package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditActorInfo
import ai.datris.model.DatrisEnvironment
import ai.datris.util.{LogRedactUtil, MongoDBUtil, NoSQLDbUtil, NoSQLDbUtility, TokenHash}
import com.google.gson.{JsonObject, JsonParser}
import com.mongodb.client.model.{Filters, IndexOptions, Sorts, Updates}
import org.bson.Document
import org.bson.conversions.Bson
import org.slf4j.LoggerFactory

import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.collection.mutable

/** An agent request the policy parked for a human. Holds everything needed
  * to replay it verbatim once approved: method, path, query, content type,
  * body. Once approved it holds the SHA-256 hash of its one-time replay
  * token (`replayTokenHash`, never the token itself), removed again when the
  * token is used. Neither the token nor its hash is in its public JSON. */
case class PendingAction(
    id: String,
    action: String,
    resourceType: Option[String],
    resourceName: Option[String],
    resourceVersion: Option[Int],
    actor: AuditActorInfo,
    reason: Option[String],
    agentSession: Option[String],
    incidentId: Option[String] = None,
    method: String,
    path: String,
    query: Option[String],
    contentType: Option[String],
    body: Option[String],
    bodyHash: String,
    createdAt: Instant,
    expiresAt: Instant,
    state: String, // pending | approved | rejected | expired | executed | failed
    decidedBy: Option[String] = None,
    decidedAt: Option[Instant] = None,
    decisionNote: Option[String] = None,
    replayTokenHash: Option[String] = None,
    replayConsumedAt: Option[Instant] = None,
    executedAt: Option[Instant] = None,
    resultStatus: Option[Int] = None,
    resultBody: Option[String] = None,
    // How `bodyHash` was computed: PendingAction.CurrentHashVersion on
    // actions queued by this server version; absent on documents stored
    // earlier (their hash is recomputed the earlier way to find them).
    hashVersion: Option[Int] = None
) {

    def isPending: Boolean = state == PendingAction.Pending
    def isExpired(now: Instant = Instant.now()): Boolean = isPending && now.isAfter(expiresAt)

    /** The body as parsed JSON with secret-looking values masked — what the
      * approval card shows. Replay uses the raw `body`, never this. */
    def bodyPreview: Option[com.google.gson.JsonElement] =
        body.flatMap { b =>
            try Some(LogRedactUtil.redactJson(JsonParser.parseString(b)))
            catch { case _: Exception => None }
        }

    def toPublicJson: JsonObject = {
        val o = new JsonObject()
        o.addProperty("id", id)
        o.addProperty("action", action)
        resourceType.foreach(o.addProperty("resourceType", _))
        resourceName.foreach(o.addProperty("resource", _))
        resourceVersion.foreach(v => o.addProperty("resourceVersion", v))
        o.add("actor", actor.toJson)
        reason.foreach(o.addProperty("reason", _))
        agentSession.foreach(o.addProperty("agentSession", _))
        incidentId.foreach(o.addProperty("incidentId", _))
        val rq = new JsonObject()
        rq.addProperty("method", method)
        rq.addProperty("path", path)
        query.foreach(q => rq.addProperty("query", LogRedactUtil.redactQueryString(q)))
        contentType.foreach(rq.addProperty("contentType", _))
        bodyPreview.foreach(rq.add("body", _))
        o.add("request", rq)
        o.addProperty("createdAt", createdAt.toString)
        o.addProperty("expiresAt", expiresAt.toString)
        o.addProperty("state", if (isExpired()) PendingAction.Expired else state)
        decidedBy.foreach(o.addProperty("decidedBy", _))
        decidedAt.foreach(d => o.addProperty("decidedAt", d.toString))
        decisionNote.foreach(o.addProperty("decisionNote", _))
        executedAt.foreach(d => o.addProperty("executedAt", d.toString))
        resultStatus.foreach(s => o.addProperty("resultStatus", s))
        resultBody.foreach(o.addProperty("resultBody", _))
        o
    }

    def toDocument: Document = {
        val d = new Document()
        d.put("_id", id)
        d.put("action", action)
        resourceType.foreach(d.put("resourceType", _))
        resourceName.foreach(d.put("resourceName", _))
        resourceVersion.foreach(v => d.put("resourceVersion", v: java.lang.Integer))
        d.put("actor", Document.parse(actor.toJson.toString))
        reason.foreach(d.put("reason", _))
        agentSession.foreach(d.put("agentSession", _))
        incidentId.foreach(d.put("incidentId", _))
        d.put("method", method)
        d.put("path", path)
        query.foreach(d.put("query", _))
        contentType.foreach(d.put("contentType", _))
        body.foreach(d.put("body", _))
        d.put("bodyHash", bodyHash)
        d.put("createdAt", createdAt.toString)
        d.put("createdAtDate", java.util.Date.from(createdAt))
        d.put("expiresAt", expiresAt.toString)
        d.put("expiresAtDate", java.util.Date.from(expiresAt))
        d.put("state", state)
        decidedBy.foreach(d.put("decidedBy", _))
        decidedAt.foreach(x => d.put("decidedAt", x.toString))
        decisionNote.foreach(d.put("decisionNote", _))
        replayTokenHash.foreach(d.put("replayTokenHash", _))
        replayConsumedAt.foreach(x => d.put("replayConsumedAt", x.toString))
        executedAt.foreach(x => d.put("executedAt", x.toString))
        resultStatus.foreach(s => d.put("resultStatus", s: java.lang.Integer))
        resultBody.foreach(d.put("resultBody", _))
        hashVersion.foreach(v => d.put("hashVersion", v: java.lang.Integer))
        d
    }
}

object PendingAction {
    val Pending = "pending"
    val Approved = "approved"
    val Rejected = "rejected"
    val Expired = "expired"
    val Executed = "executed"
    val Failed = "failed"

    val States: Set[String] = Set(Pending, Approved, Rejected, Expired, Executed, Failed)

    private val random = new SecureRandom()

    def newId(): String = "pa_" + hex(8)
    def newToken(): String = hex(24)

    private def hex(bytes: Int): String = {
        val b = new Array[Byte](bytes)
        random.nextBytes(b)
        b.map("%02x".format(_)).mkString
    }

    /** The earlier request hash: the fields joined with line breaks. Kept
      * only to find actions stored without a `hashVersion`; field contents can
      * imitate its separator, so it is never used for new actions. */
    def hashOf(actorLabel: String, method: String, path: String, query: Option[String], body: Option[String]): String = {
        val md = MessageDigest.getInstance("SHA-256")
        val s = actorLabel + "\n" + method + "\n" + path + "\n" + query.getOrElse("") + "\n" + body.getOrElse("")
        md.digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString
    }

    /** The `hashVersion` of actions queued by this server version. */
    val CurrentHashVersion = 2

    /** The request hash of [[CurrentHashVersion]]: each field is written as a
      * presence byte, then (when present) its UTF-8 byte length as four bytes
      * and its bytes, so no field content can imitate a field boundary. An
      * absent query or body differs from an empty one. */
    def delimitedHashOf(actorLabel: String, method: String, path: String, query: Option[String], body: Option[String]): String = {
        val md = MessageDigest.getInstance("SHA-256")
        def field(v: Option[String]): Unit = v match {
            case None => md.update(0.toByte)
            case Some(x) =>
                val b = x.getBytes("UTF-8")
                md.update(1.toByte)
                md.update(java.nio.ByteBuffer.allocate(4).putInt(b.length).array())
                md.update(b)
        }
        Seq(Option(actorLabel), Option(method), Option(path), query, body).foreach(field)
        md.digest().map("%02x".format(_)).mkString
    }

    private def opt(d: Document, k: String): Option[String] = Option(d.getString(k)).filter(_.nonEmpty)
    private def optInt(d: Document, k: String): Option[Int] = Option(d.getInteger(k)).map(_.intValue())
    private def optInstant(d: Document, k: String): Option[Instant] = opt(d, k).flatMap(s =>
        try Some(Instant.parse(s))
        catch { case _: Exception => None }
    )

    def fromDocument(d: Document): PendingAction = {
        val actorDoc = Option(d.get("actor", classOf[Document])).getOrElse(new Document())
        val actor = AuditActorInfo(
            actorType = Option(actorDoc.getString("type")).getOrElse("api-key"),
            label = Option(actorDoc.getString("label")).getOrElse("anonymous"),
            keyLabel = Option(actorDoc.getString("keyLabel")),
            keyId = Option(actorDoc.getString("keyId")),
            username = Option(actorDoc.getString("username")),
            role = Option(actorDoc.getString("role")),
            legacyFullAccess = Option(actorDoc.getBoolean("legacyFullAccess")).exists(_.booleanValue())
        )
        PendingAction(
            id = d.getString("_id"),
            action = d.getString("action"),
            resourceType = opt(d, "resourceType"),
            resourceName = opt(d, "resourceName"),
            resourceVersion = optInt(d, "resourceVersion"),
            actor = actor,
            reason = opt(d, "reason"),
            agentSession = opt(d, "agentSession"),
            incidentId = opt(d, "incidentId"),
            method = d.getString("method"),
            path = d.getString("path"),
            query = opt(d, "query"),
            contentType = opt(d, "contentType"),
            body = Option(d.getString("body")),
            bodyHash = Option(d.getString("bodyHash")).getOrElse(""),
            createdAt = optInstant(d, "createdAt").getOrElse(Instant.EPOCH),
            expiresAt = optInstant(d, "expiresAt").getOrElse(Instant.EPOCH),
            state = Option(d.getString("state")).getOrElse(Pending),
            decidedBy = opt(d, "decidedBy"),
            decidedAt = optInstant(d, "decidedAt"),
            decisionNote = opt(d, "decisionNote"),
            replayTokenHash = opt(d, "replayTokenHash"),
            replayConsumedAt = optInstant(d, "replayConsumedAt"),
            executedAt = optInstant(d, "executedAt"),
            resultStatus = optInt(d, "resultStatus"),
            resultBody = opt(d, "resultBody"),
            hashVersion = optInt(d, "hashVersion")
        )
    }
}

/** Mongo-backed store for pending actions: `{env}-pending-action`, TTL on
  * `expiresAtDate` so abandoned requests disappear on their own. */
object PendingActionIO {

    private val logger = LoggerFactory.getLogger(getClass)
    private val indexed = mutable.Set[String]()
    val MaxListLimit = 500

    /** Spec seam: the config store (null = the package NoSQLDbUtil), the same
      * pattern as SessionStore.dbOverride. */
    @volatile private[datris] var dbOverride: NoSQLDbUtility = null

    private def db: NoSQLDbUtility = Option(dbOverride).getOrElse(NoSQLDbUtil)

    private def collection(): com.mongodb.client.MongoCollection[Document] =
        db match {
            case m: MongoDBUtil =>
                val table = DatrisEnvironment.current.pendingActionTableName
                val coll = m.collection(table)
                ensureIndexes(table, coll)
                coll
            case _ => throw new IllegalStateException("Agent policy approvals require the MongoDB config store")
        }

    private def ensureIndexes(table: String, coll: com.mongodb.client.MongoCollection[Document]): Unit = synchronized {
        if (indexed.contains(table)) return
        try {
            // expireAfter 0 → delete once expiresAtDate is in the past. Only
            // documents still pending matter; decided ones keep the same date
            // so history is trimmed on the same schedule.
            coll.createIndex(new Document("expiresAtDate", 1), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS))
            coll.createIndex(new Document("state", 1))
            coll.createIndex(new Document("actor.label", 1))
            coll.createIndex(new Document("bodyHash", 1))
            coll.createIndex(new Document("createdAtDate", -1))
            // Serves the earlier-hash branch of `pendingCandidates`: an actor's
            // pending actions stored without a hashVersion.
            coll.createIndex(new Document("actor.label", 1).append("state", 1).append("hashVersion", 1))
            indexed += table
        } catch {
            case e: Exception => logger.warn("Could not ensure pending-action indexes on " + table + ": " + e.getMessage)
        }
    }

    def insert(pa: PendingAction): Unit = collection().insertOne(pa.toDocument)

    def get(id: String): Option[PendingAction] =
        Option(collection().find(Filters.eq("_id", id)).first()).map(PendingAction.fromDocument)

    /** A still-pending action with the same actor + request bytes, so an
      * agent that retries gets the existing approval id back. */
    def findPendingDuplicate(hash: String, actorLabel: String): Option[PendingAction] =
        Option(collection().find(Filters.and(
            Filters.eq("bodyHash", hash),
            Filters.eq("actor.label", actorLabel),
            Filters.eq("state", PendingAction.Pending)
        )).first()).map(PendingAction.fromDocument).filter(!_.isExpired())

    /** The filter of [[pendingCandidates]]: the actor's pending actions whose
      * stored hash is `hash`, or that were stored without a `hashVersion`
      * (hashed the earlier way). Two indexed branches: `bodyHash`, and
      * (`actor.label`, `state`, `hashVersion`). */
    def candidatesFilter(hash: String, actorLabel: String): Bson =
        Filters.or(
            Filters.and(Filters.eq("bodyHash", hash), Filters.eq("actor.label", actorLabel), Filters.eq("state", PendingAction.Pending)),
            Filters.and(Filters.eq("actor.label", actorLabel), Filters.eq("state", PendingAction.Pending), Filters.eq("hashVersion", null))
        )

    /** One query, no limit: the actions [[candidatesFilter]] selects. Once no
      * document of the actor lacks a hashVersion, it returns at most the
      * duplicate itself. Expiry is decided by the caller. */
    def pendingCandidates(hash: String, actorLabel: String): List[PendingAction] =
        collection().find(candidatesFilter(hash, actorLabel)).asScala.map(PendingAction.fromDocument).toList

    def countPending(actorLabel: String): Long =
        collection().countDocuments(Filters.and(Filters.eq("actor.label", actorLabel), Filters.eq("state", PendingAction.Pending)))

    def countPendingAll(): Long =
        try collection().countDocuments(Filters.eq("state", PendingAction.Pending))
        catch { case _: Exception => 0L }

    def list(state: Option[String], actorLabel: Option[String], limit: Int): List[PendingAction] = {
        val parts = List.newBuilder[Bson]
        state.foreach(s => parts += Filters.eq("state", s))
        actorLabel.foreach(a => parts += Filters.eq("actor.label", a))
        val all = parts.result()
        val filter: Bson = if (all.isEmpty) new Document() else Filters.and(all.asJava)
        collection().find(filter)
            .sort(Sorts.descending("createdAtDate"))
            .limit(math.max(1, math.min(limit, MaxListLimit)))
            .asScala.map(PendingAction.fromDocument).toList
    }

    /** Conditional state transition: only applies when the document is still
      * in `from`. Returns true when this call made the change. */
    def transition(id: String, from: String, to: String, sets: (String, Any)*): Boolean = {
        val updates = (Updates.set("state", to) +: sets.map { case (k, v) => Updates.set(k, v) }).asJava
        val r = collection().updateOne(Filters.and(Filters.eq("_id", id), Filters.eq("state", from)), Updates.combine(updates))
        r.getModifiedCount == 1
    }

    /** Single-use consumption of an approval's replay token: succeeds only
      * for an approved action that holds the token's hash and has not been
      * used. Matches on the hash, never on the presented value, and in the
      * same update marks the action used and removes the hash. Returns the
      * action so the interceptor can attribute the replay. A record that holds
      * no hash (one approved by an earlier version, which stored the token
      * itself) is not accepted. */
    def consumeReplay(id: String, token: String): Option[PendingAction] = {
        if (id == null || id.isEmpty || token == null || token.isEmpty) return None
        val coll = collection()
        val r = coll.updateOne(
            Filters.and(
                Filters.eq("_id", id),
                Filters.eq("state", PendingAction.Approved),
                Filters.eq("replayTokenHash", TokenHash.sha256Hex(token)),
                Filters.exists("replayConsumedAt", false)
            ),
            Updates.combine(
                Updates.set("replayConsumedAt", Instant.now().toString),
                Updates.unset("replayTokenHash")
            )
        )
        if (r.getModifiedCount == 1) Option(coll.find(Filters.eq("_id", id)).first()).map(PendingAction.fromDocument) else None
    }

    /** Field in which earlier versions stored the replay token itself. */
    private val ClearReplayTokenField = "replayToken"

    /** Startup cleanup: removes the clear replay token field that earlier
      * versions stored from every record, and changes nothing else. Such a
      * token is never accepted (see [[consumeReplay]]). Idempotent; returns
      * the number of records changed. Without the MongoDB config store there
      * are no approvals and it does nothing. Does not create the collection. */
    def removeClearReplayTokens(): Long =
        db match {
            case m: MongoDBUtil =>
                val coll = m.collection(DatrisEnvironment.current.pendingActionTableName)
                val n = coll.updateMany(Filters.exists(ClearReplayTokenField), Updates.unset(ClearReplayTokenField)).getModifiedCount
                if (n > 0) logger.info("Removed the stored one-time approval token from " + n + " approval record(s) written by an earlier version")
                n
            case _ => 0L
        }

    def set(id: String, sets: (String, Any)*): Unit = {
        if (sets.isEmpty) return
        collection().updateOne(Filters.eq("_id", id), Updates.combine(sets.map { case (k, v) => Updates.set(k, v) }.asJava))
    }
}
