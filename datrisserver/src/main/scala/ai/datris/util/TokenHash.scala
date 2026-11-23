package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** The stored form of a random bearer token (a sign-in session token, a
  * one-time approval token): lower-case hex SHA-256 of its UTF-8 bytes.
  * The store keeps only this value and looks a presented token up by its
  * hash, so the stored value is not itself a working token. A plain hash is
  * enough because the tokens are long random values, not passwords. */
object TokenHash {

    def sha256Hex(token: String): String = {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))
        val sb = new java.lang.StringBuilder(digest.length * 2)
        digest.foreach { b =>
            sb.append(Character.forDigit((b >> 4) & 0xf, 16))
            sb.append(Character.forDigit(b & 0xf, 16))
        }
        sb.toString
    }
}
