package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** Field protection 7: the key secret cannot be rewritten through the secrets
  * API without protect:admin (plans/stories/field-protection-7-secret-write-guard.md).
  *
  * Pins the pure guard `SecretsAPIController.putSecret` consults for the
  * `field-protection` secret:
  *
  * {{{
  * object FieldProtectionSecretGuard {
  *     case class Diff(keyChanged: Boolean, keyRemoved: Boolean,
  *                     encChanged: Set[String], encRemoved: Set[String],
  *                     currentChanged: Boolean)
  *     def diff(existing: Map[String, String], incoming: Map[String, String]): Diff
  *     def validate(incoming: Map[String, String]): Option[String]
  * }
  * }}}
  *
  * A mask or blank incoming value counts as unchanged; an absent incoming key
  * field counts as removed. `validate` returns Some(reason) when an enc.v<n> is
  * not 64 lowercase hex or encCurrent names a version that is not stored.
  *
  * The controller branches (409 / 403 + security/denied / 200 + key/rotate,
  * key/retire, key/delete) are not driven here: `putSecret` and `deleteSecret`
  * read `ai.datris.util.SecretsUtil`, a package-level lazy val built straight
  * from Vault, so they cannot be called with a stubbed store. The story's
  * Manual block covers them.
  */
class FieldProtectionSecretGuardSpec extends AnyFunSuite {

    private val Mask = "••••••••"

    private val hmac = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    private val v1 = "4ccbadf2aa11bb22cc33dd44ee55ff6600112233445566778899aabbccb5e0a7"
    private val v2 = "af479a6b00112233445566778899aabbccddeeff0011223344556677ff31a789"
    private val v3 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private val stored = Map("key" -> hmac, "enc.v1" -> v1, "enc.v2" -> v2, "encCurrent" -> "2")

    private def assertUnchanged(d: FieldProtectionSecretGuard.Diff): Unit = {
        assert(!d.keyChanged && !d.keyRemoved, d.toString)
        assert(d.encChanged.isEmpty && d.encRemoved.isEmpty, d.toString)
        assert(!d.currentChanged, d.toString)
    }

    test("masked or blank key fields are unchanged") {
        // Every key field sent back as the mask (the Secrets tab round-trip).
        assertUnchanged(FieldProtectionSecretGuard.diff(
            stored, Map("key" -> Mask, "enc.v1" -> Mask, "enc.v2" -> Mask, "encCurrent" -> "2")))
        // Blank boxes never count as a change either.
        assertUnchanged(FieldProtectionSecretGuard.diff(
            stored, Map("key" -> "", "enc.v1" -> "  ", "enc.v2" -> Mask, "encCurrent" -> "2")))
        // The values mergeIncoming restored, plus an edit of a non-key field.
        assertUnchanged(FieldProtectionSecretGuard.diff(stored, stored + ("createdByKeyLabel" -> "ops")))
    }

    test("a different key value is keyChanged") {
        val other = "0" * 64
        val d = FieldProtectionSecretGuard.diff(stored, stored + ("key" -> other))
        assert(d.keyChanged, d.toString)
        assert(!d.keyRemoved, d.toString)
        assert(d.encChanged.isEmpty && d.encRemoved.isEmpty && !d.currentChanged, d.toString)
    }

    test("an omitted key is keyRemoved") {
        val d = FieldProtectionSecretGuard.diff(stored, stored - "key")
        assert(d.keyRemoved, d.toString)
        assert(d.encChanged.isEmpty && d.encRemoved.isEmpty && !d.currentChanged, d.toString)
    }

    test("a new enc.v3 is encChanged") {
        val d = FieldProtectionSecretGuard.diff(stored, stored + ("enc.v3" -> v3))
        assert(d.encChanged == Set("enc.v3"), d.toString)
        assert(d.encRemoved.isEmpty, d.toString)
        assert(!d.keyChanged && !d.keyRemoved && !d.currentChanged, d.toString)
        // Overwriting an existing version is a change too.
        val d2 = FieldProtectionSecretGuard.diff(stored, stored + ("enc.v1" -> v3))
        assert(d2.encChanged == Set("enc.v1"), d2.toString)
    }

    test("an omitted enc.v1 is encRemoved") {
        val d = FieldProtectionSecretGuard.diff(stored, stored - "enc.v1")
        assert(d.encRemoved == Set("enc.v1"), d.toString)
        assert(d.encChanged.isEmpty, d.toString)
        assert(!d.keyChanged && !d.keyRemoved && !d.currentChanged, d.toString)
    }

    test("a different encCurrent is currentChanged") {
        val d = FieldProtectionSecretGuard.diff(stored, stored + ("enc.v3" -> v3, "encCurrent" -> "3"))
        assert(d.currentChanged, d.toString)
        assert(d.encChanged == Set("enc.v3"), d.toString)
        val d2 = FieldProtectionSecretGuard.diff(stored, stored + ("encCurrent" -> "1"))
        assert(d2.currentChanged, d2.toString)
        assert(d2.encChanged.isEmpty && d2.encRemoved.isEmpty, d2.toString)
    }

    test("validate rejects a non-hex enc value") {
        val nonHex = "zz" + v3.drop(2) // 64 chars, not hex
        assert(FieldProtectionSecretGuard.validate(stored + ("enc.v3" -> nonHex, "encCurrent" -> "3")).isDefined)
        // Too short to be a 32-byte key.
        assert(FieldProtectionSecretGuard.validate(stored + ("enc.v3" -> "abcd", "encCurrent" -> "3")).isDefined)
    }

    test("validate rejects encCurrent naming a missing version") {
        val r = FieldProtectionSecretGuard.validate(stored + ("encCurrent" -> "9"))
        assert(r.isDefined, "encCurrent=9 with no enc.v9 must be rejected")
        // Retiring the current version without moving encCurrent is the same mistake.
        assert(FieldProtectionSecretGuard.validate(stored - "enc.v2").isDefined)
    }

    test("validate accepts a well-formed set") {
        assert(FieldProtectionSecretGuard.validate(stored).isEmpty)
        assert(FieldProtectionSecretGuard.validate(stored + ("enc.v3" -> v3, "encCurrent" -> "3")).isEmpty)
        assert(FieldProtectionSecretGuard.validate(stored - "enc.v1").isEmpty)
    }
}
