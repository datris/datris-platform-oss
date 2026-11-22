package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, TenantContext}
import com.google.gson.JsonObject
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/** Story: sign-in session tokens are stored as hashes
  * (plans/stories/session-tokens-hashed.md), review follow-up.
  *
  * The branch SessionStore takes against MongoDB (the [[SessionDateFieldWrites]]
  * writes: insert and replace with the expiry Date field, replace never
  * inserting, the session indexes), run against [[FakeMongoSessionDb]]. */
class SessionStoreMongoBranchSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var savedValues: DatrisEnvironment = _
    private var db: FakeMongoSessionDb = _
    private val Table = "test-user-session"

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        val e = InMemoryUserDb.env().copy(userSessionTableName = Table)
        DatrisEnvironment.init(e)
        TenantContext.set(e)
        db = new FakeMongoSessionDb
        SessionStore.dbOverride = db
    }

    override def afterEach(): Unit = {
        SessionStore.dbOverride = null
        TenantContext.clear()
        DatrisEnvironment.values = savedValues
    }

    private def sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString

    private def only: JsonObject = {
        assert(db.docs.size == 1, s"expected exactly one stored session, found: ${db.docs.mkString("\n")}")
        db.docs.head
    }

    private def str(o: JsonObject, field: String): Option[String] =
        Option(o.get(field)).filter(_.isJsonPrimitive).map(_.getAsString)

    private def putLegacy(token: String, username: String): Unit = {
        val o = new JsonObject
        o.addProperty("token", token)
        o.addProperty("username", username)
        o.addProperty("expiresAt", Instant.now().plusSeconds(3600).toString)
        o.addProperty("createdAt", Instant.now().minusSeconds(60).toString)
        db.putJson(o.toString)
    }

    test("create stores tokenHash and the expiresAtDate field, and no token") {
        val (session, raw) = SessionStore.create("alice")
        val stored = only
        assert(!stored.has("token"), s"$stored")
        assert(str(stored, "tokenHash").contains(sha256Hex(raw)))
        assert(str(stored, "expiresAtDate").map(_.toLong).contains(Instant.parse(session.expiresAt).toEpochMilli))
    }

    test("renew replaces the session matched on tokenHash and slides its expiry") {
        val (_, raw) = SessionStore.create("alice")
        val before = Instant.parse(str(only, "expiresAt").get)
        Thread.sleep(5)
        assert(SessionStore.renew(raw).map(_.username).contains("alice"))
        assert(db.replaces.toList == List("tokenHash" -> sha256Hex(raw)), s"${db.replaces}")
        val stored = only
        assert(!stored.has("token"))
        assert(Instant.parse(str(stored, "expiresAt").get).isAfter(before))
        assert(str(stored, "expiresAtDate").map(_.toLong).contains(Instant.parse(str(stored, "expiresAt").get).toEpochMilli))
    }

    test("renewing a session that has been removed does not bring it back") {
        val (_, raw) = SessionStore.create("alice")
        // signed out between renew's read and its write
        db.beforeReplace = () => db.docs.clear()
        SessionStore.renew(raw)
        assert(db.docs.isEmpty, s"renew wrote the session back: ${db.docs}")
        db.beforeReplace = () => ()
        assert(SessionStore.find(raw).isEmpty)
    }

    test("the transition rewrite replaces the document matched on the token field, leaving one hashed document") {
        val raw = "legacy-token-value-mongo-branch-0123456789"
        putLegacy(raw, "carol")
        assert(SessionStore.find(raw).map(_.username).contains("carol"))
        assert(db.replaces.toList == List("token" -> raw), s"${db.replaces}")
        val stored = only
        assert(!stored.has("token"), s"$stored")
        assert(str(stored, "tokenHash").contains(sha256Hex(raw)))
        assert(str(stored, "username").contains("carol"))
        assert(str(stored, "expiresAtDate").isDefined, "the rewritten session carries the TTL Date field")

        db.replaces.clear()
        assert(SessionStore.find(raw).isDefined)
        assert(db.replaces.isEmpty, "a hashed session is not rewritten again")
    }

    test("signing out removes a session stored before the change, with no find first") {
        val raw = "legacy-token-value-mongo-logout-0123456789"
        putLegacy(raw, "carol")
        SessionStore.delete(raw)
        assert(db.docs.isEmpty, s"left behind: ${db.docs}")
    }

    test("ensureIndex asks for the TTL index on expiresAtDate and the unique index on tokenHash where it exists") {
        SessionStore.ensureIndex()
        assert(db.indexes.toList == List(s"ttl:$Table:expiresAtDate:0", s"uniqueWhereExists:$Table:tokenHash"), s"${db.indexes}")
    }

    test("a failed tokenHash index does not fail ensureIndex, and sessions still work") {
        db.failUniqueIndex = Some(new com.mongodb.MongoException(85, "index options conflict"))
        SessionStore.ensureIndex()
        assert(db.indexes.toList == List(s"ttl:$Table:expiresAtDate:0"))
        val (_, raw) = SessionStore.create("alice")
        assert(SessionStore.find(raw).isDefined)
    }
}
