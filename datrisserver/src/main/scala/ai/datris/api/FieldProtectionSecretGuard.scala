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
  *
  * The secret itself is server-issued (FieldProtectionKey): a PUT when it does
  * not exist yet and any DELETE are refused with 409. A field name that looks
  * like a key field but is not in canonical form (`key`, `encCurrent`,
  * `enc.v<n>` with n in 1..MaxVersion and no sign or leading zero) is refused
  * with 400 for everyone, before the capability check.
  *
  * [[evaluate]] covers the whole PUT decision from the raw stored secret and
  * the merged body; the controller only maps its result to a response.
  */
object FieldProtectionSecretGuard {

    val Mask = "••••••••"
    val KeyField = "key"
    val EncCurrent = "encCurrent"
    val SecretName = "field-protection"
    private val EncField = ai.datris.util.FieldProtectionKey.EncFieldPattern
    private val VersionNumber = "^[1-9][0-9]*$".r
    val MaxVersion: Int = ai.datris.util.FieldProtectionKey.MaxVersion
    private val Hex64 = "^[0-9a-fA-F]{64}$".r

    val KeyChangeMessage: String =
        "The field-protection hmac key cannot be changed or removed through the API; " +
            "edit the secret in Vault deliberately, knowing every hmac pseudonym changes"

    val NotIssuedMessage: String =
        "The field-protection secret is issued by the server on the first protected run; " +
            "it cannot be created through the API"

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

    /** A canonical `enc.v<n>` name with 1 <= n <= MaxVersion. */
    def isEncField(name: String): Boolean = name match {
        case EncField(n) => validVersion(n)
        case _ => false
    }

    private def validVersion(n: String): Boolean =
        VersionNumber.pattern.matcher(n).matches() && n.length <= 7 && n.toInt <= MaxVersion

    /** Looks like a governed field once trimmed and case-folded. */
    private def looksGoverned(name: String): Boolean = {
        val n = name.trim.toLowerCase(java.util.Locale.ROOT)
        n == "key" || n == "enccurrent" || n.startsWith("enc.")
    }

    /** Some(reason) for the first incoming field name that looks like a key
      * field but is not canonical (`enc.v+5`, `enc.v05`, `Key`, ` encCurrent`). */
    def nonCanonical(incoming: Map[String, String]): Option[String] =
        incoming.keys.toSeq.sorted.find(k => looksGoverned(k) && !isKeyField(k)).map { k =>
            "Field '" + k + "' is not a valid field-protection field; use 'key', 'encCurrent' or 'enc.v<n>' with n from 1 to " + MaxVersion
        }

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
                        if (!validVersion(n)) Some(EncCurrent + " must be a version number from 1 to " + MaxVersion)
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
        val badName = nonCanonical(incoming)
        if (badName.isDefined) Invalid400(badName.get)
        else if (diff.keyChanged || diff.keyRemoved) Reject409(KeyChangeMessage)
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

    /** The map to write: every key field whose merged value is still a mask
      * or blank takes the stored value, or is dropped when nothing is stored
      * (encCurrent is not a sensitive field, so mergeIncoming writes a mask or
      * blank there verbatim). Every other field passes through. */
    def restoreUnchanged(existing: Map[String, String], incoming: Map[String, String]): Map[String, String] =
        incoming.flatMap { case (k, v) =>
            if (isKeyField(k) && isUnchanged(v)) existing.get(k).filter(_.nonEmpty).map(k -> _)
            else Some(k -> v)
        }

    /** The whole PUT decision from the stored secret (empty when absent) and
      * the merged body: the map to write and the Decision. */
    def evaluate(existing: Map[String, String], incoming: Map[String, String], holdsProtectAdmin: Boolean): (Map[String, String], Decision) = {
        val restored = restoreUnchanged(existing, incoming)
        if (existing.isEmpty) (restored, Reject409(NotIssuedMessage))
        else (restored, decide(diff(existing, restored), restored, holdsProtectAdmin))
    }

    /** DELETE of the field-protection secret is refused for everyone: the
      * next protected run would issue a new hmac key and orphan every
      * ciphertext. None for any other secret. */
    def deleteDecision(name: String): Option[Decision] =
        if (name == SecretName) Some(Reject409(KeyChangeMessage)) else None
}
