package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.JsonObject

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._

/** One-shot confirmation tokens for the Configuration chat's mutating tools.
  *
  * A token is bound to (scope, tool, hash of the input without its
  * `confirmation_token` key) and expires after [[ConfirmationRegistry.TtlMs]].
  * It is spent by the first `consume`, whether that call matched or not, so a
  * model cannot probe for a token that fits different arguments. In-memory
  * only: a restart forgets outstanding tokens and the user confirms again. */
class ConfirmationRegistry(clock: () => Long = () => System.currentTimeMillis()) {
    import ConfirmationRegistry._

    private case class Entry(scope: String, tool: String, inputHash: String, expiresAt: Long)

    private val entries = new ConcurrentHashMap[String, Entry]()
    private val random = new SecureRandom()

    def issue(scope: String, tool: String, input: JsonObject): String = {
        val now = clock()
        sweep(now)
        val bytes = new Array[Byte](16)
        random.nextBytes(bytes)
        val token = hex(bytes)
        entries.put(token, Entry(scope, tool, hashInput(input), now + TtlMs))
        token
    }

    def consume(scope: String, tool: String, input: JsonObject, token: String): Either[String, Unit] = {
        if (token == null || token.isEmpty) return Left(UnknownOrExpired)
        val e = entries.remove(token)
        if (e == null || e.expiresAt <= clock()) Left(UnknownOrExpired)
        else if (e.scope != scope || e.tool != tool || e.inputHash != hashInput(input)) Left(Mismatch)
        else Right(())
    }

    private def sweep(now: Long): Unit =
        entries.entrySet().asScala.toList.foreach { en =>
            if (en.getValue.expiresAt <= now) entries.remove(en.getKey, en.getValue)
        }
}

object ConfirmationRegistry {
    val TtlMs: Long = 10L * 60L * 1000L
    val UnknownOrExpired = "unknown or expired confirmation token"
    val Mismatch = "confirmation token does not match this request"
    val TokenKey = "confirmation_token"

    /** Process-wide registry the Configuration chat controller uses, so a
      * token issued in one POST is consumable in the next. */
    lazy val shared: ConfirmationRegistry = new ConfirmationRegistry()

    private[util] def hashInput(input: JsonObject): String = {
        val copy = if (input == null) new JsonObject() else input.deepCopy()
        copy.remove(TokenKey)
        hex(MessageDigest.getInstance("SHA-256").digest(copy.toString.getBytes(StandardCharsets.UTF_8)))
    }

    private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString
}
