package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.slf4j.{Logger, LoggerFactory}
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

/** BCrypt password hashing. Cost factor 12 — slow enough to be brute-force resistant,
  * fast enough that login isn't perceptibly delayed. */
object PasswordHasher {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    private val encoder = new BCryptPasswordEncoder(12)

    def hash(plain: String): String = encoder.encode(plain)

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
