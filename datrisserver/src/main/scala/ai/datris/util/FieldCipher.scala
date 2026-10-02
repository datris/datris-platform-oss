package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

/** The reversible `encrypt` field protection method
  * (plans/stories/field-protection-5-encrypt-reveal.md).
  *
  * AES-256-GCM, a fresh 12-byte IV per value, 128-bit tag. The additional
  * authenticated data is `<pipeline>\u0000<field>`, so a ciphertext moved to
  * another pipeline or column fails authentication. Token format:
  * `enc:v<n>:<base64url(iv || ciphertext || tag)>`; the key version is in the
  * clear so decryption picks the right key with no lookup.
  *
  * Pure: keys come from the caller (FieldProtectionKey). No message ever
  * carries a value or a token. */
object FieldCipher {

    val Prefix = "enc:v"

    private val IvBytes = 12
    private val TagBits = 128
    private val Transformation = "AES/GCM/NoPadding"
    private val NotACiphertext = "Value is not a Datris ciphertext for this pipeline and field"

    private val random = new SecureRandom()
    private val TokenPattern = "^enc:v(\\d{1,9}):([A-Za-z0-9_-]+=*)$".r

    private def aad(pipeline: String, field: String): Array[Byte] =
        (String.valueOf(pipeline) + "\u0000" + String.valueOf(field)).getBytes(StandardCharsets.UTF_8)

    /** `value` as an `enc:v<version>:` token; empty or null passes through unchanged. */
    def encrypt(key: Array[Byte], version: Int, pipeline: String, field: String, value: String): String = {
        if (value == null || value.isEmpty) return value
        if (key == null || key.length != 32) throw new DatrisException("Field protection: no encryption key")
        val iv = new Array[Byte](IvBytes)
        random.nextBytes(iv)
        val c = Cipher.getInstance(Transformation)
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TagBits, iv))
        c.updateAAD(aad(pipeline, field))
        val ct = c.doFinal(value.getBytes(StandardCharsets.UTF_8))
        val payload = new Array[Byte](iv.length + ct.length)
        System.arraycopy(iv, 0, payload, 0, iv.length)
        System.arraycopy(ct, 0, payload, iv.length, ct.length)
        Prefix + version + ":" + Base64.getUrlEncoder.withoutPadding.encodeToString(payload)
    }

    /** The plaintext of a token issued by [[encrypt]] for this pipeline and
      * field. `keyLookup` maps the token's version to its key (throwing or
      * returning null for an unknown version). Empty or null passes through. */
    def decrypt(keyLookup: Int => Array[Byte], pipeline: String, field: String, token: String): String = {
        if (token == null || token.isEmpty) return token
        def refuse = new DatrisException(NotACiphertext)
        token match {
            case TokenPattern(v, b64) =>
                val payload =
                    try Base64.getUrlDecoder.decode(b64)
                    catch { case _: IllegalArgumentException => throw refuse }
                if (payload.length < IvBytes + TagBits / 8) throw refuse
                val key =
                    try keyLookup(v.toInt)
                    catch { case _: Exception => throw refuse }
                if (key == null || key.length != 32) throw refuse
                try {
                    val c = Cipher.getInstance(Transformation)
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TagBits, payload, 0, IvBytes))
                    c.updateAAD(aad(pipeline, field))
                    new String(c.doFinal(payload, IvBytes, payload.length - IvBytes), StandardCharsets.UTF_8)
                } catch { case _: Exception => throw refuse }
            case _ => throw refuse
        }
    }
}
