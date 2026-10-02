package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

/** Reversible `encrypt` field protection (plans/stories/field-protection-5-encrypt-reveal.md).
  *
  * Symbols this spec calls (all new in story 5):
  *
  * {{{
  * object ai.datris.util.FieldCipher {
  *     def encrypt(key: Array[Byte], version: Int, pipeline: String, field: String, value: String): String
  *     def decrypt(keyLookup: Int => Array[Byte], pipeline: String, field: String, token: String): String
  * }
  * }}}
  *
  * Token: `enc:v<n>:<base64url(iv || ciphertext || tag)>`, AES-256-GCM, 12-byte
  * IV, 128-bit tag, AAD = UTF-8 of `pipeline + "\u0000" + field`. Empty/null in
  * → unchanged out. A token without the prefix, an unknown version (the lookup
  * throws or returns null) or a failed tag → DatrisException with the fixed
  * message below, never carrying the value or the token. The spec decodes the
  * payload itself with JCE so the wire format, not just the round trip, is pinned.
  */
class FieldCipherSpec extends AnyFunSuite {

    private val Message = "Value is not a Datris ciphertext for this pipeline and field"

    private val K1: Array[Byte] = Array.tabulate[Byte](32)(i => i.toByte)
    private val K2: Array[Byte] = Array.tabulate[Byte](32)(i => (255 - i).toByte)
    private val keys: Map[Int, Array[Byte]] = Map(1 -> K1, 2 -> K2)
    private val lookup: Int => Array[Byte] = keys.apply // unknown version throws NoSuchElementException
    private val nullLookup: Int => Array[Byte] = v => keys.getOrElse(v, null)

    private val Value = "jane.doe@example.com"

    private def payloadOf(token: String): (Int, Array[Byte]) = {
        val m = "^enc:v(\\d+):([A-Za-z0-9_=-]+)$".r
        token match {
            case m(v, b64) => (v.toInt, Base64.getUrlDecoder.decode(b64))
            case _ => fail(s"not an enc:v<n>:<base64url> token: $token")
        }
    }

    private def tokenOf(version: Int, payload: Array[Byte]): String =
        "enc:v" + version + ":" + Base64.getUrlEncoder.withoutPadding.encodeToString(payload)

    private def assertRefused(f: => String, secrets: String*): Unit = {
        val e = intercept[DatrisException](f)
        assert(e.getMessage == Message, s"got: ${e.getMessage}")
        (Value +: secrets).foreach(s => assert(!e.getMessage.contains(s), "no value or token in the message"))
    }

    test("encrypt then decrypt returns the input") {
        Seq(Value, "x", "Ünïcødé 名前 ✓", "a,b;\"c\"\n" * 20).foreach { v =>
            val t = FieldCipher.encrypt(K1, 1, "patients", "email", v)
            assert(t != v)
            // Only for longer values: a 1-char value appears in ~half of random base64url payloads.
            if (v.length >= 8) assert(!t.contains(v), "ciphertext never carries the plaintext")
            assert(FieldCipher.decrypt(lookup, "patients", "email", t) == v)
        }
        // Independent JCE decrypt: AES/GCM/NoPadding, 12-byte IV prefix, 128-bit tag, AAD pipeline\u0000field.
        val (ver, payload) = payloadOf(FieldCipher.encrypt(K1, 1, "patients", "email", Value))
        assert(ver == 1)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(K1, "AES"), new GCMParameterSpec(128, payload, 0, 12))
        c.updateAAD(("patients" + "\u0000" + "email").getBytes(StandardCharsets.UTF_8))
        val plain = c.doFinal(payload, 12, payload.length - 12)
        assert(new String(plain, StandardCharsets.UTF_8) == Value)
        assert(payload.length == 12 + Value.getBytes(StandardCharsets.UTF_8).length + 16, "iv || ciphertext || 16-byte tag")
    }

    test("two encryptions of the same value differ") {
        val a = FieldCipher.encrypt(K1, 1, "patients", "email", Value)
        val b = FieldCipher.encrypt(K1, 1, "patients", "email", Value)
        assert(a != b, "fresh IV per value")
        assert(!(payloadOf(a)._2.take(12) sameElements payloadOf(b)._2.take(12)), "IVs differ")
        assert(FieldCipher.decrypt(lookup, "patients", "email", a) == Value)
        assert(FieldCipher.decrypt(lookup, "patients", "email", b) == Value)
    }

    test("decrypt with another pipeline or field name fails") {
        val t = FieldCipher.encrypt(K1, 1, "patients", "email", Value)
        assertRefused(FieldCipher.decrypt(lookup, "other", "email", t), t)
        assertRefused(FieldCipher.decrypt(lookup, "patients", "phone", t), t)
        // The separator matters: ("patients", "email") is not ("patient", "semail") or ("patientse", "mail").
        assertRefused(FieldCipher.decrypt(lookup, "patient", "semail", t), t)
        assertRefused(FieldCipher.decrypt(lookup, "patientse", "mail", t), t)
    }

    test("decrypt with an unknown version fails") {
        val t1 = FieldCipher.encrypt(K1, 1, "patients", "email", Value)
        // The same payload claimed as v2: the right lookup, the wrong key.
        assertRefused(FieldCipher.decrypt(lookup, "patients", "email", tokenOf(2, payloadOf(t1)._2)), t1)
        // A version the lookup does not know: it throws, or it returns null.
        val t7 = tokenOf(7, payloadOf(t1)._2)
        assertRefused(FieldCipher.decrypt(lookup, "patients", "email", t7), t7)
        assertRefused(FieldCipher.decrypt(nullLookup, "patients", "email", t7), t7)
        // The version in the token picks the key.
        val t2 = FieldCipher.encrypt(K2, 2, "patients", "email", Value)
        assert(t2.startsWith("enc:v2:"))
        assert(FieldCipher.decrypt(lookup, "patients", "email", t2) == Value)
    }

    test("a flipped byte fails authentication") {
        val t = FieldCipher.encrypt(K1, 1, "patients", "email", Value)
        val (_, payload) = payloadOf(t)
        Seq(0, 12, payload.length - 1).foreach { i => // iv, ciphertext, tag
            val p = payload.clone()
            p(i) = (p(i) ^ 0x01).toByte
            val bad = tokenOf(1, p)
            assertRefused(FieldCipher.decrypt(lookup, "patients", "email", bad), bad)
        }
        // Not a Datris token at all.
        Seq(Value, "enc:v1", "enc:vx:AAAA", "enc:v1:!!!not-base64!!!", "enc:v1:AAAA").foreach { bad =>
            assertRefused(FieldCipher.decrypt(lookup, "patients", "email", bad), bad)
        }
    }

    test("empty values pass through") {
        assert(FieldCipher.encrypt(K1, 1, "patients", "email", "") == "")
        assert(FieldCipher.encrypt(K1, 1, "patients", "email", null) == null)
        assert(FieldCipher.decrypt(lookup, "patients", "email", "") == "")
        assert(FieldCipher.decrypt(lookup, "patients", "email", null) == null)
    }

    test("tokens start with enc:v1:") {
        val t = FieldCipher.encrypt(K1, 1, "patients", "email", Value)
        assert(t.startsWith("enc:v1:"), t)
        assert(t.stripPrefix("enc:v1:").matches("[A-Za-z0-9_-]+=*"), s"base64url payload (no + or /): $t")
        assert(FieldCipher.encrypt(K2, 12, "patients", "email", Value).startsWith("enc:v12:"))
    }
}
