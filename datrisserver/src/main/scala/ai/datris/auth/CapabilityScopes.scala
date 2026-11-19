package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Capability, DatrisException}

/** Which scope keys the platform applies on each capability
  * (plans/stories/api-key-inert-scopes.md). One table, read by issue-time
  * validation (`POST /keys`), the capability catalogue, the Keys list flag,
  * the `keys.inert_scopes` doctor check and key resolution.
  *
  * `Capability.parse` accepts six scope keys on any `resource:action` (its
  * vocabulary); this object says where each one is actually evaluated. A
  * scope the server does not apply on a capability is "inert": at issue it
  * is a 400; on a stored key that capability grants nothing (fail closed)
  * unless `DATRIS_KEY_INERT_SCOPES=allow`. Pure apart from [[refuseInert]],
  * which reads a system property and the environment at call time. */
object CapabilityScopes {

    /** Every resource and its actions: the route table's vocabulary. */
    val Vocabulary: Seq[(String, Seq[String])] = Seq(
        "pipeline" -> Seq("read", "create", "update", "delete", "run"),
        "tap" -> Seq("read", "create", "update", "delete", "run"),
        "secret" -> Seq("read", "write"),
        "document" -> Seq("upload"),
        "search" -> Seq("vector"),
        "query" -> Seq("postgres", "mongodb", "objectstore", "snowflake", "databricks", "sqlserver", "natural"),
        "job" -> Seq("read", "kill"),
        "metadata" -> Seq("read"),
        "config" -> Seq("read", "write"),
        "mcp" -> Seq("tool"),
        "audit" -> Seq("read"),
        "policy" -> Seq("read", "update"),
        "approval" -> Seq("read", "decide"),
        "incident" -> Seq("read", "abandon", "proceed"),
        "protect" -> Seq("reveal", "admin"),
        "code-repo" -> Seq("read", "write")
    )

    private val actionsOf: Map[String, Seq[String]] = Vocabulary.toMap

    /** The scope keys each resource applies, in display order. Every action
      * of a resource applies the same keys except `approval`, where only
      * `approval:read` applies `owner` (and only `owner=self`). */
    private def supportedSeq(resource: String, action: String): Seq[String] = (resource, action) match {
        case ("pipeline" | "tap", _) => Seq("catalog", "owner")
        case ("secret", _) => Seq("_type")
        case ("search", "vector") | ("document", "upload") => Seq("collection")
        case ("approval", "read") => Seq("owner")
        case _ => Nil
    }

    /** Scope keys the platform applies on `resource:action` (empty: none). */
    def supported(resource: String, action: String): Seq[String] = supportedSeq(resource, action)

    /** Scope keys a resource applies on at least one of its actions (the
      * catalogue's `scopeKeys`). */
    def supportedOnResource(resource: String): Seq[String] =
        actionsOf.getOrElse(resource, Nil).flatMap(a => supportedSeq(resource, a)).distinct

    /** The known `(resource, action)` pairs a capability's resource and
      * action (either may be `*`) cover. */
    private def covered(resource: String, action: String): Seq[(String, String)] =
        for {
            (r, actions) <- Vocabulary
            if resource == "*" || resource == r
            a <- actions
            if action == "*" || action == a
        } yield r -> a

    /** True when `pair` applies every scope entry of `scope`. */
    private def appliesAll(pair: (String, String), scope: Map[String, String]): Boolean = {
        val keys = supportedSeq(pair._1, pair._2)
        scope.forall { case (k, v) => keys.contains(k) && valueAccepted(pair, k, v) }
    }

    /** `approval:read` is limited to the caller's own approvals; any other
      * owner value would read as a grant the platform never evaluates. */
    private def valueAccepted(pair: (String, String), key: String, value: String): Boolean =
        !(pair == ("approval" -> "read") && key == "owner") || value == "self"

    private def listOrNone(xs: Seq[String]): String = if (xs.isEmpty) "none" else xs.mkString(", ")

    /** Why `raw` may not be put on a key, or None when it may. Refuses what
      * `Capability.parse` refuses, a control character, an unknown resource
      * or action, a repeated scope key, and a scope the platform does not
      * apply on every pair the capability covers. */
    def issueProblem(raw: String): Option[String] = {
        if (raw == null || raw.isEmpty) return Some("Empty capability string")
        if (raw.exists(ch => Character.isISOControl(ch)))
            return Some(
                "Invalid capability '" + printable(raw) + "': it contains a control character. " +
                    "Capabilities are 'resource:action' or 'resource:action:key=value' in plain text."
            )
        val cap =
            try Capability.parse(raw)
            catch { case e: DatrisException => return Some(e.getMessage) }

        if (cap.resource != "*" && !actionsOf.contains(cap.resource))
            return Some(
                "Invalid capability '" + raw + "': unknown resource '" + cap.resource + "'. " +
                    "Known resources: " + Vocabulary.map(_._1).mkString(", ") + "."
            )
        if (cap.action != "*") {
            val known =
                if (cap.resource == "*") Vocabulary.flatMap(_._2).distinct
                else actionsOf(cap.resource)
            if (!known.contains(cap.action))
                return Some(
                    if (cap.resource == "*")
                        "Invalid capability '" + raw + "': unknown action '" + cap.action + "'. Known actions: " + known.mkString(", ") + "."
                    else
                        "Invalid capability '" + raw + "': unknown action '" + cap.action + "' on " + cap.resource + ". " +
                            "Actions for " + cap.resource + ": " + known.mkString(", ") + "."
                )
        }

        val scopeKeys = scopeKeysInOrder(raw)
        val repeated = scopeKeys.diff(scopeKeys.distinct).distinct
        if (repeated.nonEmpty)
            return Some(
                "Invalid capability '" + raw + "': scope key '" + repeated.head + "' is given more than once. " +
                    "A capability has one value per scope key; grant one capability per value instead " +
                    "(e.g. '" + cap.resource + ":" + cap.action + ":" + repeated.head + "=A' and '" +
                    cap.resource + ":" + cap.action + ":" + repeated.head + "=B')."
            )
        if (cap.scope.isEmpty) return None

        val pairs = covered(cap.resource, cap.action)
        val wildcard = cap.resource == "*" || cap.action == "*"
        pairs.find(p => !appliesAll(p, cap.scope)) match {
            case None => None
            case Some((r, a)) =>
                val pair = r + ":" + a
                val keys = supportedSeq(r, a)
                val badKey = scopeKeys.distinct.find(k => !keys.contains(k)).orElse(cap.scope.keys.headOption).get
                if (keys.contains(badKey))
                    Some(
                        "Invalid capability '" + raw + "': scope '" + badKey + "' on " + pair + " accepts only 'self' " +
                            "(an agent key reads its own approvals). Grant '" + pair + ":owner=self' or '" + pair + "'."
                    )
                else if (wildcard)
                    Some(
                        "Invalid capability '" + raw + "': scope '" + badKey + "' is not applied on " + pair +
                            ", which this wildcard covers. Supported scopes for " + pair + ": " + listOrNone(keys) + ". " +
                            "A scoped wildcard must cover only actions that apply its scope: " +
                            "'pipeline:*' and 'tap:*' take catalog and owner, 'secret:*' takes _type. " +
                            suggestWildcard(cap, pairs)
                    )
                else
                    Some(
                        "Invalid capability '" + raw + "': scope '" + badKey + "' is not applied on " + pair + ". " +
                            "Supported scopes for " + pair + ": " + listOrNone(keys) + ". " +
                            "Grant '" + pair + "' unscoped or leave it out."
                    )
        }
    }

    /** The narrowed capabilities a refused scoped wildcard could be
      * replaced by, as advice; or the unscoped form when none applies. */
    private def suggestWildcard(cap: Capability, pairs: Seq[(String, String)]): String = {
        val narrowed = narrow(cap, pairs.filter(appliesAll(_, cap.scope))).map("'" + _.raw + "'")
        if (narrowed.isEmpty) "Grant '" + cap.resource + ":" + cap.action + "' unscoped or leave it out."
        else "Grant " + narrowed.mkString(" and ") + " instead."
    }

    private def printable(s: String): String =
        s.map(ch => if (Character.isISOControl(ch)) '?' else ch)

    /** The scope keys of `raw` in order, repeats kept (`Capability.parse`
      * folds them into a map, keeping the last). */
    private def scopeKeysInOrder(raw: String): Seq[String] = {
        val parts = raw.split(":", 3)
        if (parts.length < 3 || parts(2).trim.isEmpty) Nil
        else parts(2).trim.split(",").toSeq.map(kv => kv.substring(0, math.max(kv.indexOf('='), 0)).trim)
    }

    /** Splits a stored key's capabilities into (applied, inert). A capability
      * with no scope, or whose every scope entry is applied on every pair it
      * covers, is kept as the same instance. A scoped wildcard that covers
      * some pairs without that scope is narrowed to the pairs that apply it
      * (`*:*:catalog=Sales` keeps `pipeline:*` and `tap:*` under that scope)
      * and the original moves to inert, as does any other capability with a
      * scope the platform does not apply. A scoped capability on an unknown
      * resource or action grants nothing either way and is kept. */
    def partition(caps: Seq[Capability]): (Seq[Capability], Seq[Capability]) = {
        val kept = Seq.newBuilder[Capability]
        val inert = Seq.newBuilder[Capability]
        caps.foreach { c =>
            if (c.scope.isEmpty) kept += c
            else {
                val pairs = covered(c.resource, c.action)
                if (pairs.isEmpty || pairs.forall(appliesAll(_, c.scope))) kept += c
                else {
                    inert += c
                    kept ++= narrow(c, pairs.filter(appliesAll(_, c.scope)))
                }
            }
        }
        (kept.result(), inert.result())
    }

    /** The narrowed replacements for a scoped wildcard: per resource, one
      * `resource:*` capability when the wildcard covered every action of the
      * resource and all of them apply the scope, else one per applied pair. */
    private def narrow(c: Capability, good: Seq[(String, String)]): Seq[Capability] = {
        val scopeStr = c.raw.split(":", 3).lift(2).getOrElse("").trim
        def mk(r: String, a: String) = Capability(r, a, c.scope, r + ":" + a + ":" + scopeStr)
        good.map(_._1).distinct.flatMap { r =>
            val actions = good.filter(_._1 == r).map(_._2)
            if (c.action == "*" && actions.size == actionsOf(r).size) Seq(mk(r, "*"))
            else actions.map(a => mk(r, a))
        }
    }

    /** The raw strings among `rawCaps` that carry a scope the platform does
      * not apply (in order, distinct). Unparseable strings are skipped: they
      * stop the key from resolving at all, which is not this question. */
    def inertOf(rawCaps: Seq[String]): Seq[String] = {
        val parsed = rawCaps.flatMap(r =>
            try Some(Capability.parse(r))
            catch { case _: DatrisException => None }
        )
        partition(parsed)._2.map(_.raw).distinct
    }

    /** The scope keys of `c` that are not applied on some pair it covers. */
    def inertScopeKeys(c: Capability): Seq[String] = {
        val pairs = covered(c.resource, c.action)
        c.scope.keys.toSeq.sorted.filter(k => pairs.exists(p => !appliesAll(p, Map(k -> c.scope(k)))))
    }

    /** Why `first` does not grant `required`, for the route gate's 403
      * message and audit reason: the scope keys of `first` not applied on
      * `required` ("scope 'k' is" / "scopes 'a', 'b' are"), the scopes
      * `required` applies, and whether every such key IS applied on
      * `required` but with a value it does not accept (`approval:read`
      * takes `owner=self` only), in which case the text must say so rather
      * than call the key not applied. */
    private case class InertText(pair: (String, String), applied: Seq[String], notApplied: Seq[String], badValue: Seq[String]) {

        /** Every offending key is applied on the route, only its value is not accepted. */
        def valueOnly: Boolean = notApplied.isEmpty && badValue.nonEmpty
    }

    private def inertScopeText(first: Capability, required: String): InertText = {
        val pair = required.takeWhile(_ != ':') -> required.dropWhile(_ != ':').drop(1)
        val applied = supportedSeq(pair._1, pair._2)
        val onRoute = first.scope.toSeq.sortBy(_._1).collect { case (k, v) if !applied.contains(k) || !valueAccepted(pair, k, v) => k }
        if (onRoute.isEmpty) InertText(pair, applied, inertScopeKeys(first), Nil)
        else InertText(pair, applied, onRoute.filterNot(applied.contains), onRoute.filter(applied.contains))
    }

    private def quoted(keys: Seq[String]): String = keys.map("'" + _ + "'").mkString(", ")

    /** Each offending scope key described as it is: "scope 'k' is not
      * applied on <required>" for a key the route does not apply, and
      * "scope 'owner' on <required> accepts only 'self'" for a key it applies
      * with a value it does not accept; both, joined, when the capability
      * carries both kinds. */
    private def whyNot(t: InertText, required: String): String = {
        val notApplied =
            if (t.notApplied.isEmpty) Nil
            else if (t.notApplied.size == 1) Seq("scope '" + t.notApplied.head + "' is not applied on " + required)
            else Seq("scopes " + quoted(t.notApplied) + " are not applied on " + required)
        val badValue =
            if (t.badValue.isEmpty) Nil
            else Seq((if (t.badValue.size == 1) "scope '" + t.badValue.head + "'"
                      else "scopes " + quoted(t.badValue)) + " on " + required + " accepts only 'self'")
        (notApplied ++ badValue).mkString(" and ")
    }

    /** The scopes `required` applies, a value-restricted one marked. */
    private def supportedText(t: InertText): String =
        if (t.applied.isEmpty) "none"
        else t.applied.map(k => if (!valueAccepted(t.pair, k, "")) k + " (only 'self')" else k).mkString(", ")

    /** The audit reason for a request that passed the route gate only
      * through an inert capability under `DATRIS_KEY_INERT_SCOPES=allow`. It
      * records the gate decision only (a later check may still refuse the
      * request); the request's own outcome is in its own audit entry. */
    def inertUsedMessage(label: String, required: String, inert: Seq[Capability]): String = {
        val t = inertScopeText(inert.head, required)
        "API key '" + label + "' passed the capability gate for " + required + " only through '" +
            inert.map(_.raw).distinct.mkString("', '") + "', whose " + whyNot(t, required) +
            "; " + InertScopesEnv + "=allow let it through the capability gate."
    }

    def inertDenialMessage(label: String, required: String, inert: Seq[Capability]): String = {
        val first = inert.head
        val t = inertScopeText(first, required)
        val others = inert.tail.map(_.raw).distinct
        val replacement =
            if (t.badValue.nonEmpty) "'" + required + ":" + t.badValue.head + "=self' or '" + required + "'"
            else if (t.applied.isEmpty) "'" + required + "' unscoped"
            else "'" + required + "' unscoped or with a supported scope"
        "API key '" + label + "' holds '" + first.raw + "', but " + whyNot(t, required) +
            ", so that capability grants nothing" +
            (if (others.isEmpty) "" else " (nor does " + others.mkString(", ") + ")") + ". " +
            (if (t.valueOnly) "" else "Supported scopes for " + required + ": " + supportedText(t) + ". ") +
            "The key needs a replacement holding " + replacement + ", or the operator can set " + InertScopesEnv +
            "=allow to restore the earlier behaviour while keys are replaced."
    }

    /** The plain-name container a `collection=` scope names: the store's
      * container name as written, and for pgvector `<schema>.<table>`
      * outside the `public` schema (an absent or empty schema, or exactly
      * `public`, is public; any other schema is used untrimmed). For the
      * refusal checks use [[collectionNameOpt]], which also says when no
      * scope value may name the container. */
    def collectionName(store: String, name: String, schema: Option[String]): String =
        store match {
            case "pgvector" =>
                schema.filter(s => s.nonEmpty && s != "public") match {
                    case Some(s) => s + "." + name
                    case None => name
                }
            case _ => name
        }

    /** The name a `collection=` scope must hold to cover this container, or
      * None when no scope value may name it, so only an unscoped capability
      * covers it:
      *   - a null or blank name (or a pgvector schema that is present but
      *     blank): the check would not see the container the store uses;
      *   - pgvector with a `.` in the table name, or in a non-public schema:
      *     `<schema>.<table>` could then name two different tables (schema
      *     `a` table `b` and public table `a.b`), so neither is named.
      * Names are compared exactly as written (no trimming, no case folding). */
    def collectionNameOpt(store: String, name: String, schema: Option[String]): Option[String] =
        if (name == null || name.trim.isEmpty) None
        else
            store match {
                case "pgvector" =>
                    schema match {
                        case None | Some("public") => if (name.contains('.')) None else Some(name)
                        case Some(s) if s.trim.isEmpty => None
                        case Some(s) => if (name.contains('.') || s.contains('.')) None else Some(s + "." + name)
                    }
                case _ => Some(name)
            }

    // ---- The stored-key switch (Decision D1)

    val InertScopesEnv: String = "DATRIS_KEY_INERT_SCOPES"
    val InertScopesProperty: String = "datris.keyInertScopes"
    val InertScopesAllow: String = "allow"

    /** True (fail closed) unless the `datris.keyInertScopes` system property,
      * else `DATRIS_KEY_INERT_SCOPES`, is exactly `allow` (trimmed,
      * case-insensitive). Read at call time. */
    def refuseInert: Boolean = {
        val raw = sys.props.get(InertScopesProperty).orElse(sys.env.get(InertScopesEnv))
        !raw.exists(_.trim.equalsIgnoreCase(InertScopesAllow))
    }
}
