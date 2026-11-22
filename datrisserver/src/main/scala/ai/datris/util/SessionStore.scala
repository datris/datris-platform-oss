package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.Gson
import ai.datris.model.{DatrisEnvironment, UserSession}
import org.slf4j.{Logger, LoggerFactory}

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/** The session writes that carry a BSON Date field (for the TTL index) and
  * the session indexes: what [[SessionStore]] needs beyond [[NoSQLDbUtility]].
  * Implemented by [[MongoDBUtil]]; a spec fake implements it to run the same
  * branch. `replaceWithDateField` replaces the one matching document and
  * never inserts; it is true when a document matched. */
trait SessionDateFieldWrites {
    def ensureTtlIndex(tableName: String, fieldName: String, expireAfterSeconds: Long): Unit
    def ensureUniqueIndexWhereExists(tableName: String, fieldName: String): Unit
    def insertWithDateField(tableName: String, json: String, dateFieldName: String, dateValueEpochMillis: Long): Unit
    def replaceWithDateField(tableName: String, keyName: String, key: String, json: String, dateFieldName: String, dateValueEpochMillis: Long): Boolean
}

/** UI browser sessions. Documents in {env}-user-session, keyed on
  * `tokenHash`: the hex SHA-256 of the browser's `datris-session` cookie
  * value. The cookie value is returned once by [[create]] and never stored;
  * [[find]], [[renew]] and [[delete]] hash the value they are given, so the
  * stored hash is not itself a working cookie. Mongo TTL index on
  * `expiresAtDate` auto-purges expired sessions. */
object SessionStore {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val gson = new Gson
    private val random = new SecureRandom()

    /** Session lifetime: 8 hours (sliding — extended on each authenticated request). */
    val SessionTtlSeconds: Long = 8L * 60L * 60L

    private val KeyField = "tokenHash"
    private val DateField = "expiresAtDate"

    private def tableName: String = DatrisEnvironment.current.userSessionTableName

    /** Test override for the session collection (null = the package
      * NoSQLDbUtil), the same pattern as UserStore.dbOverride. Every read and
      * write below goes through it when set. */
    @volatile private[datris] var dbOverride: NoSQLDbUtility = null

    private def db: NoSQLDbUtility = Option(dbOverride).getOrElse(NoSQLDbUtil)

    /** The stored form of a session token: lower-case hex SHA-256 (UTF-8). */
    def hash(token: String): String = TokenHash.sha256Hex(token)

    /** Idempotent — runs at startup. The TTL index makes Mongo auto-purge expired
      * sessions; without it, expired tokens would accumulate forever. The index is
      * on the BSON Date field `expiresAtDate` (added by insertWithDateField). The
      * unique index on `tokenHash` covers only documents that have the field. */
    def ensureIndex(): Unit = {
        db match {
            case m: SessionDateFieldWrites =>
                m.ensureTtlIndex(tableName, DateField, 0L)
                // sign-in works without it (lookups are equality reads either
                // way), so a failure here must not stop the startup step after it
                try m.ensureUniqueIndexWhereExists(tableName, KeyField)
                catch {
                    case e: Exception =>
                        logger.warn("Could not create the session tokenHash index (continuing): " + e.getClass.getSimpleName + mongoErrorCode(e))
                }
            case _ => // non-Mongo backend: TTL handled in code on lookup
        }
    }

    /** Start a session for `username`: the stored session, and the token to
      * set as the browser's cookie (returned here only, never stored). */
    def create(username: String): (UserSession, String) = {
        val token = newToken()
        val now = Instant.now()
        val expires = now.plusSeconds(SessionTtlSeconds)
        val session = UserSession(
            tokenHash = hash(token),
            username = username,
            expiresAt = expires.toString,
            createdAt = now.toString
        )
        db match {
            case m: SessionDateFieldWrites =>
                m.insertWithDateField(tableName, gson.toJson(session), DateField, expires.toEpochMilli)
            case other =>
                other.insertJSON(tableName, gson.toJson(session))
        }
        (session, token)
    }

    def find(token: String): Option[UserSession] = {
        if (token == null || token.isEmpty) return None
        val h = hash(token)
        val stored = readByHash(h) match {
            case some @ Some(_) => some
            case None =>
                findStoredBeforeHashing(token, h) match {
                    case some @ Some(_) => some
                    // a concurrent request may have just rewritten the same session
                    case None => readByHash(h)
                }
        }
        stored.filter(live)
    }

    /** Slide the expiration on activity. */
    def renew(token: String): Option[UserSession] = {
        find(token).map { current =>
            val now = Instant.now()
            val expires = now.plusSeconds(SessionTtlSeconds)
            val renewed = current.copy(expiresAt = expires.toString)
            db match {
                case m: SessionDateFieldWrites =>
                    m.replaceWithDateField(tableName, KeyField, renewed.tokenHash, gson.toJson(renewed), DateField, expires.toEpochMilli)
                case other =>
                    other.upsertJSON(tableName, java.util.Collections.singletonList(KeyField), gson.toJson(renewed))
            }
            renewed
        }
    }

    def delete(token: String): Unit = {
        if (token != null && token.nonEmpty) {
            db.deleteItemJSON(tableName, KeyField, hash(token))
            deleteStoredBeforeHashing(token)
        }
    }

    /** The Mongo error code (and code name, when the server sent one) of a
      * failure, for the log; never the driver's message text. */
    private def mongoErrorCode(e: Exception): String = e match {
        case c: com.mongodb.MongoCommandException =>
            " (code " + c.getErrorCode + Option(c.getErrorCodeName).filter(_.nonEmpty).map(" " + _).getOrElse("") + ")"
        case m: com.mongodb.MongoException => " (code " + m.getCode + ")"
        case _ => ""
    }

    private def readByHash(h: String): Option[UserSession] =
        Option(db.getItemJSON(tableName, KeyField, h, null).orNull)
            .map(json => gson.fromJson(json, classOf[UserSession]))

    private def live(s: UserSession): Boolean =
        s.expiresAt != null && Instant.parse(s.expiresAt).isAfter(Instant.now())

    /** Transition read, for one release: a session written before tokens were
      * hashed holds the token itself in a `token` field. Found by the token,
      * a live one is rewritten in place in the hashed form (same document,
      * `tokenHash` added, `token` removed, user, creation time and expiry
      * kept), so the browser stays signed in. Removing this method and
      * [[deleteStoredBeforeHashing]] (and their calls in [[find]] and
      * [[delete]]) ends the transition. */
    private def findStoredBeforeHashing(token: String, h: String): Option[UserSession] = {
        val LegacyField = "token"
        val legacy = Option(db.getItemJSON(tableName, LegacyField, token, null).orNull)
            .map(json => gson.fromJson(json, classOf[UserSession]).copy(tokenHash = h))
        legacy.filter(live).map { s =>
            try {
                db match {
                    case m: SessionDateFieldWrites =>
                        // replace by the legacy field: exactly one document results; a
                        // concurrent rewrite of the same session matches nothing here
                        m.replaceWithDateField(tableName, LegacyField, token, gson.toJson(s), DateField, Instant.parse(s.expiresAt).toEpochMilli)
                    case other =>
                        other.upsertJSON(tableName, java.util.Collections.singletonList(KeyField), gson.toJson(s))
                        other.deleteItemJSON(tableName, LegacyField, token)
                }
            } catch {
                // the session stays usable; the next request tries again. The
                // exception text is not logged: a store error can quote the key.
                case e: Exception =>
                    logger.warn("Could not rewrite a stored session in the hashed form (" + e.getClass.getSimpleName + "); it remains valid")
            }
            s
        }
    }

    /** Transition delete, for one release: signing out also removes a
      * session still stored in the form from before tokens were hashed. */
    private def deleteStoredBeforeHashing(token: String): Unit =
        db.deleteItemJSON(tableName, "token", token)

    private def newToken(): String = {
        val bytes = new Array[Byte](32)
        random.nextBytes(bytes)
        Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
    }
}
