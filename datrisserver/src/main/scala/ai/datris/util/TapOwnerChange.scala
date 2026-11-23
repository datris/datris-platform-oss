package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.TapAPIController
import ai.datris.auth.TapPrincipal
import ai.datris.auth.TapPrincipal.{Full, Resolved, Unresolved}
import ai.datris.model.{PipelineConfig, ResolvedKey, TapConfig, User}
import ai.datris.util.DoctorService.{TapOwnerScopeCheck, TapReferencesCheck}

/** The rules behind `POST /api/v1/tap/owner`: an administrator hands an
  * existing tap to another user or API key (plans/stories/tap-change-owner.md).
  * Pure: every store read is passed in, and a read that throws propagates.
  *
  * Who may become the owner: a stored user (label `session:<stored
  * username>`) or an API key that resolves today. The platform's own labels
  * other than `ui` may not be named, and `ui` only when it is a stored key.
  *
  * Whether a tap may be handed to that owner: an administrator or full-access
  * key always may. Any other owner is refused when it could not write the
  * tap where it is stored, or may not load into the stored target pipeline.
  * What the `datris doctor` tap rows cannot be exact about (a missing target,
  * narrowed callback reads, a secret outside the owner's tap access) is
  * allowed and returned as warnings in those rows' words. */
object TapOwnerChange {

    sealed trait Decision

    /** The tap may be handed to the new owner; `warnings` in the doctor rows' words. */
    final case class Accepted(warnings: List[String]) extends Decision

    /** The tap may not be handed to the new owner; nothing is written. */
    final case class Refused(reason: String) extends Decision

    private val SessionPrefix = "session:"

    /** Key labels that name no API key a tap may be handed to: the platform's
      * own identities and the version actors' fallbacks. */
    val ReservedLabels: Set[String] = Set("anonymous", ai.datris.incident.RecoveryKey.Label, "system", "agent")

    /** Label prefixes that are not API key labels. */
    val ReservedPrefixes: Seq[String] = Seq(SessionPrefix, "tap:")

    private def given(v: String): Option[String] = Option(v).filter(_.trim.nonEmpty)

    /** The new owner a body names: exactly one of `user`, `key` (null or blank
      * when absent). Left(reason) is a 400. Right((owner label, its
      * resolution)). `findUser` and `keyByLabel` are the store reads; a throw
      * propagates (never an owner). */
    def newOwner(
        user: String,
        key: String,
        findUser: String => Option[User],
        keyByLabel: String => Option[ResolvedKey]
    ): Either[String, (String, TapPrincipal.Resolution)] =
        (given(user), given(key)) match {
            case (Some(_), Some(_)) => Left("Name the new owner with exactly one of 'user' or 'key', not both.")
            case (None, None) => Left("Name the new owner with 'user' (a username) or 'key' (an API key label).")
            case (Some(u), None) =>
                findUser(u.trim) match {
                    case Some(found) if found != null && found.username != null =>
                        Right((SessionPrefix + found.username, Resolved(APIKeyValidator.resolveFromSession(found))))
                    case _ => Left("No user named '" + u.trim + "'.")
                }
            case (None, Some(k)) =>
                if (ReservedLabels.contains(k) || ReservedPrefixes.exists(k.startsWith))
                    Left("'" + k + "' is not an API key a tap can be handed to; name a user with 'user', or an issued API key with 'key'.")
                else
                    keyByLabel(k) match {
                        case Some(rk) if rk != null =>
                            // The platform's own key stands for the whole installation, as
                            // TapPrincipal resolves the stored label.
                            if (TapPrincipal.PlatformLabels.contains(k)) Right((k, Full)) else Right((k, Resolved(rk)))
                        case _ => Left("No API key labelled '" + k + "' (unknown or revoked).")
                    }
        }

    private def hasTarget(t: TapConfig): Boolean = t.targetPipeline != null && t.targetPipeline.trim.nonEmpty

    /** Whether `tap` may be handed to `newLabel` (resolved as `resolution`).
      * `storedTarget`: the tap's stored target pipeline (null: none or not
      * stored). `secretOwner`: a secret's owner as the `tap.references` scan
      * reads it (outer None: no such secret); may throw, and the throw
      * propagates. */
    def decide(
        tap: TapConfig,
        newLabel: String,
        resolution: TapPrincipal.Resolution,
        storedTarget: PipelineConfig,
        secretOwner: String => Option[Option[String]]
    ): Decision =
        resolution match {
            case Full => Accepted(Nil)
            case Resolved(rk) if rk == null || rk.isFullAccess => Accepted(Nil)
            case Unresolved => Refused("The new owner does not exist (an unknown or revoked key, or a deleted user); pick another owner.")
            case Resolved(rk) =>
                val asOwned = tap.copy(createdByKeyLabel = newLabel)
                if (!TapAPIController.identityMayWriteStoredTap(rk, asOwned))
                    Refused(
                        "The new owner could not edit this tap where it is (catalog '" + String.valueOf(tap.catalog) + "'): it cannot see that " +
                            "catalog's taps or may not change taps. Grant it edit on the catalog, or pick another owner; an administrator " +
                            "or a full-access key can always own a tap."
                    )
                else if (TapOwnerScopeScan.feedOutsideOwner(resolution, storedTarget))
                    Refused(
                        "The new owner may not load into the tap's target pipeline '" + String.valueOf(tap.targetPipeline) + "'. Grant it " +
                            "edit on that pipeline's catalog, point the tap at a pipeline it may load into, or pick another owner; an " +
                            "administrator or a full-access key can always own a tap."
                    )
                else Accepted(scopeWarnings(asOwned, resolution, storedTarget) ++ secretWarnings(asOwned, rk, secretOwner))
        }

    /** The `tap.owner_scope` findings the doctor would list for `asOwned`
      * (the tap with its new owner) other than a refusal: a missing target
      * and narrowed callback reads, judged on the new owner alone. */
    private def scopeWarnings(asOwned: TapConfig, resolution: TapPrincipal.Resolution, storedTarget: PipelineConfig): List[String] = {
        val words = TapOwnerScopeCheck.Groups.toMap
        val result = TapOwnerScopeScan.scan(
            List(asOwned),
            n => if (hasTarget(asOwned) && n == asOwned.targetPipeline) storedTarget else null,
            _ => resolution,
            _ => null,
            () => false
        )
        result.findings.collect {
            case f if f.group == TapOwnerScopeScan.TargetMissing => words(TapOwnerScopeScan.TargetMissing)
            case f if f.group == TapOwnerScopeScan.ReadsNarrowed =>
                words(TapOwnerScopeScan.ReadsNarrowed) + Option(f.reason).filter(_.nonEmpty).map(" (" + _ + ")").getOrElse("")
        }
    }

    /** The `tap.references` secret finding for `asOwned`: the new owner's
      * tap read is scoped and does not cover the tap's secret. */
    private def secretWarnings(asOwned: TapConfig, rk: ResolvedKey, secretOwner: String => Option[Option[String]]): List[String] =
        Option(asOwned.secretName).map(_.trim).filter(_.nonEmpty) match {
            case Some(secret) if TapReferenceScan.secretOutsideOwner(rk, asOwned.catalog, () => secretOwner(secret)) =>
                List(TapReferencesCheck.Groups.toMap.apply(TapReferenceScan.SecretOutsideOwner))
            case _ => Nil
        }
}
