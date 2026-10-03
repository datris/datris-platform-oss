package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** Guards writes to the `field-protection` key secret through the secrets API
  * (plans/stories/field-protection-7-secret-write-guard.md).
  *
  * Pure: `SecretsAPIController.putSecret` computes a [[FieldProtectionSecretGuard.Diff]]
  * between the stored secret and the incoming body, asks [[FieldProtectionSecretGuard.decide]]
  * what to do, and only maps the [[FieldProtectionSecretGuard.Decision]] to a
  * response and an audit entry.
  *
  *  - the hmac `key` can never be changed or removed through the API (409);
  *  - adding, changing or removing an `enc.v<n>`, or changing `encCurrent`,
  *    needs `protect:admin` (403 otherwise), is validated (400) and is audited
  *    as `key / rotate`, `key / retire` or `key / set-current`;
  *  - a write that leaves every key field as it is (masks, blanks, or an edit
  *    of a non-key field) is allowed and not audited here.
  *
  * A mask or blank incoming value counts as unchanged whether or not
  * mergeIncoming already restored the stored value; an absent incoming key
  * field counts as removed.
  */
object FieldProtectionSecretGuard {

    val Mask = "••••••••"
    val KeyField = "key"
    val EncCurrent = "encCurrent"
    private val EncField = "^enc\\.v(\\d+)$".r
    private val Hex64 = "^[0-9a-fA-F]{64}$".r

    val KeyChangeMessage: String =
        "The field-protection hmac key cannot be changed or removed through the API; " +
            "edit the secret in Vault deliberately, knowing every hmac pseudonym changes"

    case class Diff(
        keyChanged: Boolean,
        keyRemoved: Boolean,
        encChanged: Set[String],
        encRemoved: Set[String],
        currentChanged: Boolean
    ) {
        def touchesKeyMaterial: Boolean = encChanged.nonEmpty || encRemoved.nonEmpty || currentChanged
    }

    sealed trait Decision
    case class Reject409(message: String) extends Decision
    case object Deny403 extends Decision
    case class Invalid400(message: String) extends Decision
    case class Allow(auditAction: Option[String], fields: Set[String]) extends Decision

    def isEncField(name: String): Boolean = EncField.pattern.matcher(name).matches()

    /** True for the fields the guard governs: `key`, every `enc.v<n>`, `encCurrent`. */
    def isKeyField(name: String): Boolean = name == KeyField || name == EncCurrent || isEncField(name)

    /** A mask or blank value means "leave the stored value as it is". */
    def isUnchanged(value: String): Boolean = value == null || value == Mask || value.trim.isEmpty

    private def changed(existing: Map[String, String], incoming: Map[String, String], field: String): Boolean =
        incoming.get(field) match {
            case Some(v) if !isUnchanged(v) => !existing.get(field).contains(v)
            case _ => false
        }

    private def removed(existing: Map[String, String], incoming: Map[String, String], field: String): Boolean =
        existing.get(field).exists(_.nonEmpty) && !incoming.contains(field)

    def diff(existing: Map[String, String], incoming: Map[String, String]): Diff = {
        val encNames = (existing.keySet ++ incoming.keySet).filter(isEncField)
        Diff(
            keyChanged = changed(existing, incoming, KeyField),
            keyRemoved = removed(existing, incoming, KeyField),
            encChanged = encNames.filter(changed(existing, incoming, _)),
            encRemoved = encNames.filter(removed(existing, incoming, _)),
            currentChanged = changed(existing, incoming, EncCurrent) || removed(existing, incoming, EncCurrent)
        )
    }

    /** Some(reason) when an `enc.v<n>` value is not 64 hex characters or
      * `encCurrent` does not name a version present in `incoming`. Masked or
      * blank values are not re-checked (they stand for the stored value). */
    def validate(incoming: Map[String, String]): Option[String] = {
        val encFields = incoming.keySet.filter(isEncField)
        val badHex = encFields.toSeq.sorted.find { f =>
            val v = incoming(f)
            !isUnchanged(v) && !Hex64.pattern.matcher(v).matches()
        }
        badHex match {
            case Some(f) => Some(f + " must be 64 hex characters (a 32-byte AES-256 key)")
            case None =>
                incoming.get(EncCurrent) match {
                    case Some(v) if !isUnchanged(v) =>
                        val n = v.trim
                        if (!n.forall(_.isDigit)) Some(EncCurrent + " must be a version number")
                        else if (!incoming.contains("enc.v" + n)) Some(EncCurrent + " names version " + n + " but enc.v" + n + " is not stored")
                        else None
                    case Some(_) => None
                    case None =>
                        if (encFields.nonEmpty) Some(EncCurrent + " is required while any enc.v<n> is stored")
                        else None
                }
        }
    }

    /** The branch logic putSecret applies to the `field-protection` secret. */
    def decide(diff: Diff, incoming: Map[String, String], holdsProtectAdmin: Boolean): Decision = {
        if (diff.keyChanged || diff.keyRemoved) Reject409(KeyChangeMessage)
        else if (!diff.touchesKeyMaterial) Allow(None, Set.empty)
        else if (!holdsProtectAdmin) Deny403
        else
            validate(incoming) match {
                case Some(reason) => Invalid400(reason)
                case None =>
                    val action =
                        if (diff.encChanged.nonEmpty) "rotate"
                        else if (diff.encRemoved.nonEmpty) "retire"
                        else "set-current"
                    val fields = diff.encChanged ++ diff.encRemoved ++ (if (diff.currentChanged) Set(EncCurrent) else Set.empty[String])
                    Allow(Some(action), fields)
            }
    }
}
