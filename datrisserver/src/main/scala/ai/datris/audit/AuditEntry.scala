package ai.datris.audit

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonObject, JsonPrimitive}
import org.bson.Document

import java.time.Instant

/** Who performed an audited action.
  *
  * Agents are identified by their API key (label + stable keyId), humans by
  * their session, and the in-platform Assistant by both — the `ui` key that
  * carried the request plus the user it acted on behalf of. */
case class AuditActorInfo(
    actorType: String, // user | api-key | assistant | tap | system
    label: String, // ResolvedKey.label — the same string EntityVersion.createdBy uses
    keyLabel: Option[String] = None, // the API key that carried the request (api-key / assistant)
    keyId: Option[String] = None, // stable per-issue id; None for pre-id keys, sessions, anonymous
    username: Option[String] = None, // user / assistant only
    role: Option[String] = None, // when a UserContext was present
    legacyFullAccess: Boolean = false
) {
    def toJson: JsonObject = {
        val o = new JsonObject()
        o.addProperty("type", actorType)
        o.addProperty("label", label)
        keyLabel.foreach(o.addProperty("keyLabel", _))
        o.add("keyId", keyId.map(new JsonPrimitive(_)).getOrElse(com.google.gson.JsonNull.INSTANCE))
        username.foreach(o.addProperty("username", _))
        role.foreach(o.addProperty("role", _))
        o.addProperty("legacyFullAccess", legacyFullAccess)
        o
    }
}

object AuditActorInfo {
    val System: AuditActorInfo = AuditActorInfo(actorType = "system", label = "system")
}

/** The HTTP request an entry came from. Absent for system / scheduler events. */
case class AuditRequestInfo(
    method: String,
    path: String,
    query: Option[String], // already redacted
    ip: Option[String],
    userAgent: Option[String]
) {
    def toJson: JsonObject = {
        val o = new JsonObject()
        o.addProperty("method", method)
        o.addProperty("path", path)
        query.foreach(o.addProperty("query", _))
        ip.foreach(o.addProperty("ip", _))
        userAgent.foreach(o.addProperty("userAgent", _))
        o
    }
}

/** One immutable audit record. `metadata` must already be redacted by the
  * caller (see LogRedactUtil.redactJson) — nothing here re-checks it. */
case class AuditEntry(
    ts: Instant,
    actor: AuditActorInfo,
    category: String,
    action: String,
    resourceType: Option[String],
    resourceName: Option[String],
    outcome: String, // success | failure | denied
    httpStatus: Option[Int] = None,
    durationMs: Option[Long] = None,
    errorMessage: Option[String] = None,
    request: Option[AuditRequestInfo] = None,
    metadata: Option[JsonObject] = None,
    /** Collection the entry belongs to — resolved on the request thread so
      * multi-tenant routing (TenantContext) is honored even though the
      * write happens later on the writer thread. */
    tableName: String = null
) {

    /** Key used to fold bursts of the same event into one record. */
    def collapseKey: String =
        actor.label + "|" + category + "|" + action + "|" + resourceName.getOrElse("")

    def toJson: JsonObject = {
        val o = new JsonObject()
        o.addProperty("ts", ts.toString)
        o.add("actor", actor.toJson)
        o.addProperty("category", category)
        o.addProperty("action", action)
        val r = new JsonObject()
        resourceType.foreach(r.addProperty("type", _))
        resourceName.foreach(r.addProperty("name", _))
        if (r.size() > 0) o.add("resource", r)
        o.addProperty("outcome", outcome)
        httpStatus.foreach(s => o.addProperty("httpStatus", s))
        durationMs.foreach(d => o.addProperty("durationMs", d))
        errorMessage.foreach(o.addProperty("errorMessage", _))
        request.foreach(rq => o.add("request", rq.toJson))
        metadata.foreach(m => o.add("metadata", m))
        o
    }

    /** Mongo document: the JSON above plus a BSON Date `tsDate` for the TTL
      * index (strings are ignored by the TTL monitor). */
    def toDocument: Document = {
        val doc = Document.parse(toJson.toString)
        doc.put("tsDate", java.util.Date.from(ts))
        doc
    }
}
