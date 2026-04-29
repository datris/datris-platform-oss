package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

/** BCrypt password hashing. Cost factor 12 — slow enough to be brute-force resistant,
  * fast enough that login isn't perceptibly delayed. */
object PasswordHasher {
    private val encoder = new BCryptPasswordEncoder(12)

    def hash(plain: String): String = encoder.encode(plain)

    def verify(plain: String, hash: String): Boolean = {
        if (plain == null || hash == null || hash.isEmpty) false
        else try encoder.matches(plain, hash) catch { case _: Exception => false }
    }
}
