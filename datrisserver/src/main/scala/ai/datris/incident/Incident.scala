package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonArray, JsonObject, JsonParser}
import org.bson.Document

import java.security.SecureRandom
import java.time.Instant
import scala.collection.JavaConverters._

/** One step of an incident's narrative: what phase did what, when, and —
  * when an action was parked by the agent policy — which approval it waits on. */
case class IncidentStep(
    ts: Instant,
    phase: String, // open | diagnose | propose | gate | execute | verify | close
    summary: String,
    detail: Option[String] = None,
    approvalId: Option[String] = None
) {
    def toJson: JsonObject = {
        val o = new JsonObject()
        o.addProperty("ts", ts.toString)
        o.addProperty("phase", phase)
        o.addProperty("summary", summary)
        detail.foreach(o.addProperty("detail", _))
        approvalId.foreach(o.addProperty("approvalId", _))
        o
    }
}

/** A platform-opened operational incident: one failing / stale / anomalous
  * resource, the recovery agent's diagnosis and actions, and the outcome.
  * The audit log (joined by `metadata.incidentId`) is the ledger; this is
  * the narrative. */
case class Incident(
    id: String,
    kind: String, // tap_failure | pipeline_failure | stale | volume
    resourceType: String, // tap | pipeline
    resourceName: String,
    openedAt: Instant,
    state: String,
    trigger: JsonObject,
    steps: List[IncidentStep] = Nil,
    classification: Option[String] = None, // transient | structural-script | structural-schema | needs-human
    proposal: Option[JsonObject] = None, // the runner's parsed proposal, verbatim
    aiCalls: Int = 0,
    actionsTaken: Int = 0,
    awaitingApprovalIds: List[String] = Nil,
    revertToVersion: Option[Int] = None, // resource version to restore if verification fails
    closedAt: Option[Instant] = None,
    outcome: Option[String] = None
) {

    def isOpen: Boolean = Incident.OpenStates.contains(state)

    def toPublicJson: JsonObject = {
        val o = new JsonObject()
        o.addProperty("id", id)
        o.addProperty("kind", kind)
        o.addProperty("resourceType", resourceType)
        o.addProperty("resource", resourceName)
        o.addProperty("openedAt", openedAt.toString)
        o.addProperty("state", state)
        o.add("trigger", trigger)
        val arr = new JsonArray()
        steps.foreach(s => arr.add(s.toJson))
        o.add("steps", arr)
        classification.foreach(o.addProperty("classification", _))
        proposal.foreach(o.add("proposal", _))
        o.addProperty("aiCalls", aiCalls)
        o.addProperty("actionsTaken", actionsTaken)
        if (awaitingApprovalIds.nonEmpty) {
            val a = new JsonArray()
            awaitingApprovalIds.foreach(a.add)
            o.add("awaitingApprovalIds", a)
        }
        closedAt.foreach(c => o.addProperty("closedAt", c.toString))
        outcome.foreach(o.addProperty("outcome", _))
        o
    }

    def toDocument: Document = {
        val d = Document.parse(toPublicJson.toString)
        d.put("_id", id)
        d.remove("id")
        d.put("openedAtDate", java.util.Date.from(openedAt))
        closedAt.foreach(c => d.put("closedAtDate", java.util.Date.from(c)))
        d.put("revertToVersion", revertToVersion.map(v => v: java.lang.Integer).orNull)
        d
    }
}

object Incident {
    val Open = "open"
    val Diagnosing = "diagnosing"
    val Proposed = "proposed"
    val AwaitingApproval = "awaiting_approval"
    val Executing = "executing"
    val Verifying = "verifying"
    val Resolved = "resolved"
    val Failed = "failed"
    val Abandoned = "abandoned"

    val OpenStates: Set[String] = Set(Open, Diagnosing, Proposed, AwaitingApproval, Executing, Verifying)
    val ClosedStates: Set[String] = Set(Resolved, Failed, Abandoned)
    val States: Set[String] = OpenStates ++ ClosedStates

    val KindTapFailure = "tap_failure"
    val KindPipelineFailure = "pipeline_failure"
    val KindStale = "stale"
    val KindVolume = "volume"

    private val random = new SecureRandom()

    def newId(): String = {
        val b = new Array[Byte](6)
        random.nextBytes(b)
        "inc_" + b.map("%02x".format(_)).mkString
    }

    private def opt(d: Document, k: String): Option[String] = Option(d.getString(k)).filter(_.nonEmpty)

    def fromDocument(d: Document): Incident = {
        def instant(k: String): Option[Instant] =
            opt(d, k).flatMap(s =>
                try Some(Instant.parse(s))
                catch { case _: Exception => None }
            )
        val steps = Option(d.get("steps", classOf[java.util.List[Document]])).map(_.asScala.toList).getOrElse(Nil).map { sd =>
            IncidentStep(
                ts = Option(sd.getString("ts")).flatMap(s =>
                    try Some(Instant.parse(s))
                    catch { case _: Exception => None }
                ).getOrElse(Instant.EPOCH),
                phase = Option(sd.getString("phase")).getOrElse("?"),
                summary = Option(sd.getString("summary")).getOrElse(""),
                detail = Option(sd.getString("detail")),
                approvalId = Option(sd.getString("approvalId"))
            )
        }
        Incident(
            id = d.getString("_id"),
            kind = Option(d.getString("kind")).getOrElse("?"),
            resourceType = Option(d.getString("resourceType")).getOrElse("?"),
            resourceName = Option(d.getString("resource")).getOrElse("?"),
            openedAt = instant("openedAt").getOrElse(Instant.EPOCH),
            state = Option(d.getString("state")).getOrElse(Open),
            trigger = Option(d.get("trigger", classOf[Document])).map(t => JsonParser.parseString(t.toJson).getAsJsonObject).getOrElse(new JsonObject()),
            steps = steps,
            classification = opt(d, "classification"),
            proposal = Option(d.get("proposal", classOf[Document])).map(p => JsonParser.parseString(p.toJson).getAsJsonObject),
            aiCalls = Option(d.getInteger("aiCalls")).map(_.intValue()).getOrElse(0),
            actionsTaken = Option(d.getInteger("actionsTaken")).map(_.intValue()).getOrElse(0),
            awaitingApprovalIds = Option(d.get("awaitingApprovalIds", classOf[java.util.List[String]])).map(_.asScala.toList).getOrElse(Nil),
            revertToVersion = Option(d.getInteger("revertToVersion")).map(_.intValue()),
            closedAt = instant("closedAt"),
            outcome = opt(d, "outcome")
        )
    }
}
