package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.slf4j.{Logger, LoggerFactory}
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

import java.security.SecureRandom

/** BCrypt password hashing. Cost factor 12 — slow enough to be brute-force resistant,
  * fast enough that login isn't perceptibly delayed. */
object PasswordHasher {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    private val encoder = new BCryptPasswordEncoder(12)

    private val secureRandom = new SecureRandom()
    // Unambiguous alphabet — no 0/O/1/l/I — so a bootstrap password copied out
    // of a server log or handed to an invited user doesn't get misread.
    private val TempPasswordAlphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    def hash(plain: String): String = encoder.encode(plain)

    /** Generate a random, high-entropy temporary password. Used to seed the
      * bootstrap admin and to back admin-invited accounts, so no account ever
      * exists with a null/empty hash that login would accept with any password.
      * ~20 chars over a 55-symbol alphabet ≈ 115 bits of entropy. */
    def generateTemporary(length: Int = 20): String = {
        val sb = new StringBuilder(length)
        var i = 0
        while (i < length) {
            sb.append(TempPasswordAlphabet.charAt(secureRandom.nextInt(TempPasswordAlphabet.length)))
            i += 1
        }
        sb.toString
    }

    def verify(plain: String, hash: String): Boolean = {
        if (plain == null || hash == null || hash.isEmpty) false
        else
            try encoder.matches(plain, hash)
            catch {
                case e: Exception =>
                    logger.debug("Password verification failed on a malformed stored hash, treating as not authenticated", e)
                    false
            }
    }
}
