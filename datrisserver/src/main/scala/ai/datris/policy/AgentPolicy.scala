package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonObject, JsonParser}

import scala.collection.JavaConverters._

/** What the policy says about an agent-initiated action. Ordered: a
  * resource-level override may only move an action to the right. */
sealed abstract class PolicyMode(val name: String, val rank: Int)
object PolicyMode {
    case object Auto extends PolicyMode("auto", 0)
    case object Approve extends PolicyMode("approve", 1)
    case object Deny extends PolicyMode("deny", 2)

    val All: Seq[PolicyMode] = Seq(Auto, Approve, Deny)

    def parse(s: String): Option[PolicyMode] = All.find(_.name == Option(s).map(_.trim.toLowerCase).orNull)

    def stricter(a: PolicyMode, b: PolicyMode): PolicyMode = if (b.rank > a.rank) b else a
}

case class PolicyLimits(pendingTtlHours: Int = 24, maxPendingPerActor: Int = 50)

/** The agent policy: for every `resource:action[:sub]` key the capability
  * layer already classifies, whether an agent-initiated request runs
  * (`auto`), is parked for a human (`approve`), or is refused (`deny`).
  *
  * Keys are matched most-specific first: `pipeline:update:dest-types` →
  * `pipeline:update` → `pipeline:*`. Anything unmatched is `auto`, so an
  * empty policy changes nothing.
  *
  * `overrides` are keyed by the target resource (`pipeline:<name>` /
  * `tap:<name>`) and hold the same action map; an override can only
  * tighten the default for that resource, never loosen it. */
case class AgentPolicy(
    version: Int = 0,
    actions: Map[String, PolicyMode] = Map.empty,
    overrides: Map[String, Map[String, PolicyMode]] = Map.empty,
    limits: PolicyLimits = PolicyLimits(),
    updatedAt: Option[String] = None,
    updatedBy: Option[String] = None
) {

    def isEmpty: Boolean = actions.isEmpty && overrides.isEmpty

    /** The mode for `actionKey` against a specific resource. */
    def decide(actionKey: String, resourceType: Option[String], resourceName: Option[String]): PolicyMode = {
        val base = AgentPolicy.lookup(actions, actionKey).getOrElse(PolicyMode.Auto)
        val override_ = for {
            t <- resourceType
            n <- resourceName
            m <- overrides.get(t + ":" + n)
            mode <- AgentPolicy.lookup(m, actionKey)
        } yield mode
        override_.map(PolicyMode.stricter(base, _)).getOrElse(base)
    }

    def toJson: JsonObject = {
        val o = new JsonObject()
        o.addProperty("version", version)
        val a = new JsonObject()
        actions.toSeq.sortBy(_._1).foreach { case (k, v) => a.addProperty(k, v.name) }
        o.add("actions", a)
        val ov = new JsonObject()
        overrides.toSeq.sortBy(_._1).foreach { case (res, m) =>
            val inner = new JsonObject()
            m.toSeq.sortBy(_._1).foreach { case (k, v) => inner.addProperty(k, v.name) }
            ov.add(res, inner)
        }
        o.add("overrides", ov)
        val l = new JsonObject()
        l.addProperty("pendingTtlHours", limits.pendingTtlHours)
        l.addProperty("maxPendingPerActor", limits.maxPendingPerActor)
        o.add("limits", l)
        updatedAt.foreach(o.addProperty("updatedAt", _))
        updatedBy.foreach(o.addProperty("updatedBy", _))
        o
    }
}

object AgentPolicy {

    val Empty: AgentPolicy = AgentPolicy()

    /** One-click starting point the UI offers: pause before anything
      * destructive or table-rewriting, refuse secret writes outright. */
    val Recommended: AgentPolicy = AgentPolicy(
        actions = Map(
            "pipeline:delete" -> PolicyMode.Approve,
            "tap:delete" -> PolicyMode.Approve,
            "job:kill" -> PolicyMode.Approve,
            "pipeline:update:dest-types" -> PolicyMode.Approve,
            "secret:write" -> PolicyMode.Deny,
            "code-repo:write" -> PolicyMode.Deny,
            "config:write" -> PolicyMode.Deny
        )
    )

    /** `pipeline:update:dest-types` → `pipeline:update` → `pipeline:*`. */
    private[policy] def lookup(m: Map[String, PolicyMode], actionKey: String): Option[PolicyMode] = {
        if (m.isEmpty) return None
        val parts = actionKey.split(":").toList
        val candidates = (parts.length to 2 by -1).map(n => parts.take(n).mkString(":")) :+ (parts.head + ":*")
        candidates.iterator.map(m.get).collectFirst { case Some(v) => v }
    }

    private val OverrideKey = "^(pipeline|tap):[A-Za-z0-9_.-]{1,128}$".r

    /** Parse + validate. Every action key must be one the capability layer
      * knows (or `resource:*` for a known resource), every mode one of
      * auto/approve/deny, every override key `pipeline:<name>` / `tap:<name>`. */
    def fromJson(json: String): Either[String, AgentPolicy] = {
        val root =
            try {
                val el = JsonParser.parseString(json)
                if (!el.isJsonObject) return Left("policy must be a JSON object")
                el.getAsJsonObject
            } catch {
                case e: Exception => return Left("policy is not valid JSON: " + e.getMessage)
            }
        val errors = List.newBuilder[String]

        def parseActions(obj: JsonObject, where: String): Map[String, PolicyMode] =
            obj.entrySet().asScala.flatMap { e =>
                val key = e.getKey.trim
                if (!PolicyRoutes.isKnownActionKey(key)) {
                    errors += where + ": unknown action '" + key + "'"
                    None
                } else if (!e.getValue.isJsonPrimitive) {
                    errors += where + ": mode for '" + key + "' must be a string"
                    None
                } else PolicyMode.parse(e.getValue.getAsString) match {
                    case Some(mode) => Some(key -> mode)
                    case None =>
                        errors += where + ": mode for '" + key + "' must be auto, approve or deny (was '" + e.getValue.getAsString + "')"
                        None
                }
            }.toMap

        val actions =
            if (!root.has("actions") || root.get("actions").isJsonNull) Map.empty[String, PolicyMode]
            else if (!root.get("actions").isJsonObject) { errors += "actions must be an object"; Map.empty[String, PolicyMode] }
            else parseActions(root.getAsJsonObject("actions"), "actions")

        val overrides =
            if (!root.has("overrides") || root.get("overrides").isJsonNull) Map.empty[String, Map[String, PolicyMode]]
            else if (!root.get("overrides").isJsonObject) { errors += "overrides must be an object"; Map.empty[String, Map[String, PolicyMode]] }
            else root.getAsJsonObject("overrides").entrySet().asScala.flatMap { e =>
                val key = e.getKey.trim
                if (OverrideKey.findFirstIn(key).isEmpty) {
                    errors += "overrides: key '" + key + "' must be pipeline:<name> or tap:<name>"
                    None
                } else if (!e.getValue.isJsonObject) {
                    errors += "overrides: value for '" + key + "' must be an object"
                    None
                } else Some(key -> parseActions(e.getValue.getAsJsonObject, "overrides." + key))
            }.toMap

        var limits = PolicyLimits()
        if (root.has("limits") && root.get("limits").isJsonObject) {
            val l = root.getAsJsonObject("limits")
            def intField(name: String, default: Int, min: Int, max: Int): Int =
                if (!l.has(name) || l.get(name).isJsonNull) default
                else
                    try {
                        val v = l.get(name).getAsInt
                        if (v < min || v > max) { errors += "limits." + name + " must be between " + min + " and " + max; default }
                        else v
                    } catch {
                        case _: Exception => errors += "limits." + name + " must be an integer"; default
                    }
            limits = PolicyLimits(
                pendingTtlHours = intField("pendingTtlHours", 24, 1, 24 * 30),
                maxPendingPerActor = intField("maxPendingPerActor", 50, 1, 1000)
            )
        }

        val version =
            if (root.has("version") && root.get("version").isJsonPrimitive)
                try root.get("version").getAsInt
                catch { case _: Exception => 0 }
            else 0

        val errs = errors.result()
        if (errs.nonEmpty) Left(errs.mkString("; "))
        else Right(AgentPolicy(version = version, actions = actions, overrides = overrides, limits = limits))
    }
}
