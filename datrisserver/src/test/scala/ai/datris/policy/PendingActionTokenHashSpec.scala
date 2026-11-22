package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditActorInfo
import ai.datris.config.PolicyInterceptor
import ai.datris.util.TokenHash
import org.bson.Document
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Story: approvals, the one-time token that runs an approved action is
  * stored as a hash and removed once used
  * (plans/stories/approval-replay-token-hashed.md).
  *
  * The record itself: what its stored document and public JSON hold, and
  * which presented values `PolicyInterceptor.replayable` accepts. The store's
  * MongoDB writes are in [[PendingActionIOTokenHashSpec]]. */
class PendingActionTokenHashSpec extends AnyFunSuite {

    private val token = PendingAction.newToken()
    private val hash = TokenHash.sha256Hex(token)
    private val now = Instant.now()

    private def approved(tokenHash: Option[String] = Some(hash)) = PendingAction(
        id = "pa_00000000000000aa",
        action = "tap:delete",
        resourceType = Some("tap"),
        resourceName = Some("prices"),
        resourceVersion = Some(3),
        actor = AuditActorInfo(actorType = "api-key", label = "research-agent", keyLabel = Some("research-agent"), keyId = Some("k_1")),
        reason = Some("replacing with v2"),
        agentSession = Some("sess-1"),
        method = "DELETE",
        path = "/api/v1/tap",
        query = Some("name=prices"),
        contentType = None,
        body = None,
        bodyHash = "abc",
        createdAt = now,
        expiresAt = now.plus(1, ChronoUnit.HOURS),
        state = PendingAction.Approved,
        decidedBy = Some("todd"),
        decidedAt = Some(now),
        replayTokenHash = tokenHash
    )

    /** A record as an earlier version stored it: approved, with the clear
      * token field and no hash, not yet run. */
    private def legacyDocument(): Document = {
        val d = approved(None).toDocument
        d.remove("replayTokenHash")
        d.put("replayToken", token)
        d
    }

    private def source(rel: String): String = {
        val file = Seq(Paths.get(rel), Paths.get(rel.stripPrefix("datrisserver/"))).find(Files.isRegularFile(_)).getOrElse(fail(rel + " not found"))
        new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
    }

    test("an approved record's stored document holds a hash and no clear token") {
        val d = approved().toDocument
        assert(d.getString("replayTokenHash") == hash)
        assert(!d.containsKey("replayToken"), d.toJson)
        assert(!d.toJson.contains(token), "the stored document carries the token in clear")
    }

    test("the stored hash is the SHA-256 hex of the token, and round-trips through the document") {
        assert(hash.matches("[0-9a-f]{64}"))
        val back = PendingAction.fromDocument(approved().toDocument)
        assert(back.replayTokenHash.contains(hash))
        assert(back == approved())
    }

    test("the record type has no field for the clear token") {
        val names = classOf[PendingAction].getDeclaredFields.map(_.getName).toSet
        assert(names.contains("replayTokenHash"), names)
        assert(!names.contains("replayToken"), names)
    }

    test("the token is accepted for its approved record while unused") {
        assert(PolicyInterceptor.replayable(approved(), token))
    }

    test("a value that is not the token is not accepted, and neither is the stored hash itself") {
        val pa = approved()
        assert(!PolicyInterceptor.replayable(pa, hash), "the stored hash was accepted as the token")
        assert(!PolicyInterceptor.replayable(pa, PendingAction.newToken()))
        assert(!PolicyInterceptor.replayable(pa, token.toUpperCase))
        assert(!PolicyInterceptor.replayable(pa, token + "0"))
        assert(!PolicyInterceptor.replayable(pa, token.dropRight(1)))
        assert(!PolicyInterceptor.replayable(pa, ""))
        assert(!PolicyInterceptor.replayable(pa, null))
        // a record with no hash accepts nothing
        assert(!PolicyInterceptor.replayable(approved(None), token))
        assert(!PolicyInterceptor.replayable(approved(None), ""))
    }

    test("a second presentation of the same token is not accepted once the record is marked used") {
        val used = approved().copy(replayConsumedAt = Some(now))
        assert(!PolicyInterceptor.replayable(used, token))
        // nor once the hash is gone after use
        assert(!PolicyInterceptor.replayable(approved(None).copy(replayConsumedAt = Some(now)), token))
    }

    test("the token is accepted only while the record is approved") {
        for (s <- PendingAction.States - PendingAction.Approved)
            assert(!PolicyInterceptor.replayable(approved().copy(state = s), token), s)
    }

    test("a record that holds only a clear token from an earlier version is not accepted") {
        val pa = PendingAction.fromDocument(legacyDocument())
        assert(pa.state == PendingAction.Approved)
        assert(pa.replayTokenHash.isEmpty)
        assert(!PolicyInterceptor.replayable(pa, token))
        // the clear value is not carried forward when the record is written again
        assert(!pa.toDocument.containsKey("replayToken"))
        assert(!pa.toDocument.toJson.contains(token))
    }

    test("the public JSON of a record never holds a token or a hash") {
        for (pa <- Seq(approved(), approved().copy(replayConsumedAt = Some(now)), PendingAction.fromDocument(legacyDocument()))) {
            val json = pa.toPublicJson.toString
            assert(!json.contains(token), json)
            assert(!json.contains(hash), json)
            assert(!json.contains("replayToken"), json)
            assert(!json.contains("replayTokenHash"), json)
        }
    }

    test("hashes are compared in constant time") {
        val src = source("datrisserver/src/main/scala/ai/datris/config/PolicyInterceptor.scala")
        val i = src.indexOf("def replayable(")
        assert(i >= 0)
        val end = src.indexOf("\n    def ", i + 1)
        val body = src.substring(i, if (end < 0) src.length else end)
        assert(body.contains("TokenHash.sha256Hex("), body)
        assert(body.contains("MessageDigest.isEqual("), body)
    }
}
