package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{ResolvedKey, User}
import ai.datris.util.{APIKeyValidator, UserStore}

/** Who stands behind a tap: the identity its owner label (`createdByKeyLabel`)
  * stands for today (plans/stories/tap-runs-hold-owner-scope.md, Design).
  *
  * Label forms: `session:<username>` (a signed-in user) resolves to that
  * user's session identity ([[APIKeyValidator.resolveFromSession]]); any
  * other label is an API key label and resolves to that key's current
  * capabilities ([[APIKeyValidator.resolveByLabel]]). The platform's own key
  * (`ui`), the recovery key, `anonymous` (API keys off), null and blank are
  * [[Full]] with no store read. A revoked or unknown key and a deleted user
  * are [[Unresolved]]. A store failure throws: it is never [[Full]] and never
  * [[Unresolved]].
  *
  * Used only to report (the `tap.owner_scope` doctor check and the run-time
  * report lines); nothing is decided on it in this release. */
object TapPrincipal {

    sealed trait Resolution

    /** Install-wide: nothing to hold the tap to. */
    case object Full extends Resolution

    /** The identity the label stands for today. */
    final case class Resolved(key: ResolvedKey) extends Resolution

    /** A revoked or unknown key, or a deleted user. */
    case object Unresolved extends Resolution

    /** The platform's own labels: full access, never looked up. */
    val PlatformLabels: Set[String] = Set(ai.datris.audit.AuditActor.UiKeyLabel, ai.datris.incident.RecoveryKey.Label, "anonymous")

    private val SessionPrefix = "session:"

    /** Pure core. `findUser(username)` and `keyByLabel(label)` are the store
      * reads; either may throw, and the throw propagates. */
    def resolveWith(label: String, findUser: String => Option[User], keyByLabel: String => Option[ResolvedKey]): Resolution =
        if (label == null || label.trim.isEmpty || PlatformLabels.contains(label)) Full
        else if (label.startsWith(SessionPrefix)) {
            val username = label.substring(SessionPrefix.length)
            if (username.trim.isEmpty) Unresolved
            else findUser(username).map(u => Resolved(APIKeyValidator.resolveFromSession(u))).getOrElse(Unresolved)
        } else keyByLabel(label).map(Resolved(_)).getOrElse(Unresolved)

    /** Live: users through [[UserStore.find]], keys through
      * [[APIKeyValidator.resolveByLabel]]. */
    def resolve(label: String): Resolution = resolveWith(label, UserStore.find, APIKeyValidator.resolveByLabel)
}
