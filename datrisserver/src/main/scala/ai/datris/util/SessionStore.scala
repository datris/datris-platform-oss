package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.Gson
import ai.datris.model.{DatrisEnvironment, UserSession}

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/** UI browser sessions. Documents in {env}-user-session, keyed on `token`.
  * Mongo TTL index on `expiresAt` auto-purges expired sessions. */
object SessionStore {
    private val gson = new Gson
    private val random = new SecureRandom()

    /** Session lifetime: 8 hours (sliding — extended on each authenticated request). */
    val SessionTtlSeconds: Long = 8L * 60L * 60L

    private def tableName: String = DatrisEnvironment.current.userSessionTableName

    /** Idempotent — runs at startup. The TTL index makes Mongo auto-purge expired
      * sessions; without it, expired tokens would accumulate forever. The index is
      * on the BSON Date field `expiresAtDate` (added by insertWithDateField). */
    def ensureIndex(): Unit = {
        NoSQLDbUtil match {
            case m: MongoDBUtil => m.ensureTtlIndex(tableName, "expiresAtDate", 0L)
            case _ => // non-Mongo backend: TTL handled in code on lookup
        }
    }

    def create(username: String): UserSession = {
        val token = newToken()
        val now = Instant.now()
        val expires = now.plusSeconds(SessionTtlSeconds)
        val session = UserSession(
            token = token,
            username = username,
            expiresAt = expires.toString,
            createdAt = now.toString
        )
        NoSQLDbUtil match {
            case m: MongoDBUtil =>
                m.insertWithDateField(tableName, gson.toJson(session), "expiresAtDate", expires.toEpochMilli)
            case other =>
                other.insertJSON(tableName, gson.toJson(session))
        }
        session
    }

    def find(token: String): Option[UserSession] = {
        if (token == null || token.isEmpty) return None
        Option(NoSQLDbUtil.getItemJSON(tableName, "token", token, null).orNull)
            .map(json => gson.fromJson(json, classOf[UserSession]))
            .filter(s => Instant.parse(s.expiresAt).isAfter(Instant.now()))
    }

    /** Slide the expiration on activity. */
    def renew(token: String): Option[UserSession] = {
        find(token).map { current =>
            val now = Instant.now()
            val expires = now.plusSeconds(SessionTtlSeconds)
            val renewed = current.copy(expiresAt = expires.toString)
            NoSQLDbUtil match {
                case m: MongoDBUtil =>
                    m.upsertWithDateField(tableName, "token", token, gson.toJson(renewed), "expiresAtDate", expires.toEpochMilli)
                case other =>
                    other.upsertJSON(tableName, java.util.Collections.singletonList("token"), gson.toJson(renewed))
            }
            renewed
        }
    }

    def delete(token: String): Unit = {
        if (token != null && token.nonEmpty)
            NoSQLDbUtil.deleteItemJSON(tableName, "token", token)
    }

    private def newToken(): String = {
        val bytes = new Array[Byte](32)
        random.nextBytes(bytes)
        Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
    }
}
