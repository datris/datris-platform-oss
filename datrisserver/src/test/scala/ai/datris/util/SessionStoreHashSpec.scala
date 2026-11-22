package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, TenantContext, UserSession}
import com.google.gson.JsonObject
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/** Story: sign-in session tokens are stored as hashes
  * (plans/stories/session-tokens-hashed.md).
  *
  * A stored session holds only `tokenHash` (lower-case hex SHA-256 of the
  * token the browser holds) and no `token` field. The browser's token is
  * returned once by `create` and is never read back from the store; `find`,
  * `renew` and `delete` hash what they are given and key on `tokenHash`, so
  * the stored value itself is not a working cookie. For one release, a
  * session written by the previous version (`{token: ...}`) is found once by
  * its token and rewritten in the hashed form (decision D1a).
  *
  * No Mongo: SessionStore runs its real logic against [[FakeSessionDb]]
  * through `SessionStore.dbOverride`, for reads AND writes.
  *
  * Pinned seam (does not exist at v1.61.0; the implementation adds it):
  * {{{
  * // ai.datris.util.SessionStore
  * def hash(token: String): String                 // lower-case hex SHA-256 (UTF-8)
  * def create(username: String): (UserSession, String) // (stored session, raw token for the cookie)
  * // create / find / renew / delete all go through dbOverride when it is set
  * }}}
  */
class SessionStoreHashSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var savedValues: DatrisEnvironment = _
    private var db: FakeSessionDb = _

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        val e = InMemoryUserDb.env().copy(userSessionTableName = "test-user-session")
        DatrisEnvironment.init(e)
        TenantContext.set(e)
        db = new FakeSessionDb
        SessionStore.dbOverride = db
    }

    override def afterEach(): Unit = {
        SessionStore.dbOverride = null
        TenantContext.clear()
        DatrisEnvironment.values = savedValues
    }

    private def sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString

    private val Hex64 = "^[0-9a-f]{64}$"

    private def only: JsonObject = {
        assert(db.docs.size == 1, s"expected exactly one stored session, found: ${db.docs.mkString("\n")}")
        db.docs.head
    }

    private def str(o: JsonObject, field: String): Option[String] =
        Option(o.get(field)).filter(_.isJsonPrimitive).map(_.getAsString)

    /** A session as the previous server version stored it: the token in the clear. */
    private def putLegacy(token: String, username: String, expiresAt: Instant = Instant.now().plusSeconds(3600)): Unit = {
        val o = new JsonObject
        o.addProperty("token", token)
        o.addProperty("username", username)
        o.addProperty("expiresAt", expiresAt.toString)
        o.addProperty("createdAt", Instant.now().minusSeconds(60).toString)
        db.putJson(o.toString)
    }

    // ---- Acceptance 1: the stored shape and the hashed key -------------------------

    test("hash is the lower-case hex SHA-256 of the token") {
        val t = "abc-DEF_123"
        assert(SessionStore.hash(t) == sha256Hex(t))
        assert(SessionStore.hash(t).matches(Hex64))
        assert(SessionStore.hash(t) != SessionStore.hash(t + "x"))
    }

    test("after create, the stored session holds tokenHash (64 hex chars, the hash of the returned token) and no token field") {
        val (session, raw) = SessionStore.create("alice")
        assert(raw != null && raw.nonEmpty)
        assert(session.username == "alice")

        val stored = only
        assert(!stored.has("token"), s"the stored session must not carry the token: $stored")
        val hash = str(stored, "tokenHash").getOrElse(fail(s"no tokenHash in the stored session: $stored"))
        assert(hash.matches(Hex64), hash)
        assert(hash == sha256Hex(raw))
        assert(!stored.toString.contains(raw), "the browser's token appears nowhere in the stored document")
        assert(str(stored, "username").contains("alice"))
    }

    test("two sessions get different tokens and different hashes") {
        val (_, a) = SessionStore.create("alice")
        val (_, b) = SessionStore.create("alice")
        assert(a != b)
        assert(db.docs.flatMap(str(_, "tokenHash")).distinct.size == 2)
    }

    test("find with the token from create returns the session, keyed on tokenHash") {
        val (_, raw) = SessionStore.create("alice")
        db.lookups.clear()
        val found = SessionStore.find(raw)
        assert(found.map(_.username).contains("alice"))
        assert(db.lookups.toList == List("tokenHash" -> sha256Hex(raw)), s"lookups: ${db.lookups}")
    }

    test("the stored tokenHash presented as the cookie value finds no session") {
        val (_, raw) = SessionStore.create("alice")
        val stored = str(only, "tokenHash").get
        assert(SessionStore.find(stored).isEmpty)
        assert(SessionStore.renew(stored).isEmpty)
        assert(SessionStore.find(raw).isDefined, "the real token still works")
    }

    test("find with an empty or null value returns None without a lookup") {
        SessionStore.create("alice")
        db.lookups.clear()
        assert(SessionStore.find("").isEmpty)
        assert(SessionStore.find(null).isEmpty)
        assert(db.lookups.isEmpty)
    }

    test("renew slides the expiry and keeps the hashed shape: one document, tokenHash, no token") {
        val (_, raw) = SessionStore.create("alice")
        val before = Instant.parse(str(only, "expiresAt").get)
        Thread.sleep(5)
        val renewed = SessionStore.renew(raw)
        assert(renewed.map(_.username).contains("alice"))

        val stored = only
        assert(!stored.has("token"), s"renew must not write the token back: $stored")
        assert(str(stored, "tokenHash").contains(sha256Hex(raw)))
        assert(Instant.parse(str(stored, "expiresAt").get).isAfter(before), "renew slides the stored expiry")
        assert(SessionStore.find(raw).isDefined)
    }

    test("renew with the stored hash writes nothing") {
        val (_, raw) = SessionStore.create("alice")
        val stored = str(only, "tokenHash").get
        val snapshot = only.toString
        assert(SessionStore.renew(stored).isEmpty)
        assert(only.toString == snapshot)
        assert(SessionStore.find(raw).isDefined)
    }

    test("delete with the token removes the session; find afterwards is None") {
        val (_, raw) = SessionStore.create("alice")
        val (_, other) = SessionStore.create("bob")
        SessionStore.delete(raw)
        assert(SessionStore.find(raw).isEmpty)
        assert(db.docs.flatMap(str(_, "tokenHash")) == Seq(sha256Hex(other)), "only alice's session is removed")
        assert(SessionStore.find(other).map(_.username).contains("bob"))
    }

    test("delete with the stored hash removes nothing") {
        val (_, raw) = SessionStore.create("alice")
        SessionStore.delete(str(only, "tokenHash").get)
        assert(db.docs.size == 1)
        assert(SessionStore.find(raw).isDefined)
    }

    test("unchanged: an expired hashed session is not found") {
        val (_, raw) = SessionStore.create("alice")
        only.addProperty("expiresAt", Instant.now().minusSeconds(1).toString)
        assert(SessionStore.find(raw).isEmpty)
        assert(SessionStore.renew(raw).isEmpty)
    }

    // ---- Acceptance 2: transition read for sessions from before the change (D1a) ---

    test("a session stored before the change is found by its token and rewritten with tokenHash and no token") {
        val raw = "legacy-token-value-0123456789abcdefghijklmnop"
        putLegacy(raw, "carol")

        val found = SessionStore.find(raw)
        assert(found.map(_.username).contains("carol"), "a signed-in user stays signed in across the upgrade")

        val stored = only
        assert(!stored.has("token"), s"the rewritten session must not carry the token: $stored")
        assert(str(stored, "tokenHash").contains(sha256Hex(raw)), s"$stored")
        assert(!stored.toString.contains(raw))
        assert(str(stored, "username").contains("carol"))
    }

    test("after the rewrite, a second find succeeds through tokenHash with no lookup on token") {
        val raw = "legacy-token-value-second-find-0123456789"
        putLegacy(raw, "carol")
        assert(SessionStore.find(raw).isDefined)

        db.lookups.clear()
        assert(SessionStore.find(raw).map(_.username).contains("carol"))
        assert(db.lookups.toList == List("tokenHash" -> sha256Hex(raw)), s"lookups: ${db.lookups}")
    }

    test("a session found by tokenHash makes no lookup on token") {
        val (_, raw) = SessionStore.create("alice")
        db.lookups.clear()
        SessionStore.find(raw)
        SessionStore.renew(raw)
        assert(!db.lookups.exists(_._1 == "token"), s"lookups: ${db.lookups}")
    }

    test("the transition keeps the session's user, creation time and expiry") {
        val raw = "legacy-token-value-fields-0123456789abcd"
        val expires = Instant.now().plusSeconds(1234)
        putLegacy(raw, "carol", expires)
        val createdAt = str(only, "createdAt").get
        SessionStore.find(raw)
        val stored = only
        assert(str(stored, "createdAt").contains(createdAt))
        assert(str(stored, "expiresAt").map(Instant.parse).contains(expires))
    }

    test("a session stored before the change signs in and out through the request path (renew, then delete)") {
        val raw = "legacy-token-value-renew-delete-012345678"
        putLegacy(raw, "carol")
        assert(SessionStore.renew(raw).map(_.username).contains("carol"))
        val stored = only
        assert(!stored.has("token") && str(stored, "tokenHash").contains(sha256Hex(raw)), s"$stored")

        SessionStore.delete(raw)
        assert(db.docs.isEmpty, s"left behind: ${db.docs}")
        assert(SessionStore.find(raw).isEmpty)
    }

    test("an expired session stored before the change is not found") {
        val raw = "legacy-token-value-expired-0123456789abcd"
        putLegacy(raw, "carol", Instant.now().minusSeconds(1))
        assert(SessionStore.find(raw).isEmpty)
        assert(SessionStore.renew(raw).isEmpty)
    }

    test("a legacy session is not found by the hash of some other value") {
        putLegacy("legacy-token-value-other-0123456789abcdef", "carol")
        assert(SessionStore.find("not-the-token").isEmpty)
        assert(only.has("token"), "an unpresented legacy session is left for the TTL index")
    }

    test("the stored model carries tokenHash, not token") {
        val fields = classOf[UserSession].getDeclaredFields.map(_.getName).toSet
        assert(fields.contains("tokenHash"), fields)
        assert(!fields.contains("token"), fields)
    }
}
