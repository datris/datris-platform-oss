package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditActorInfo
import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import java.time.temporal.ChronoUnit

class PendingActionSpec extends AnyFunSuite {

    private val now = Instant.parse("2026-08-30T10:00:00Z")

    private def sample(state: String = PendingAction.Pending, expiresAt: Instant = now.plus(1, ChronoUnit.DAYS)) = PendingAction(
        id = "pa_0123456789abcdef",
        action = "tap:delete",
        resourceType = Some("tap"),
        resourceName = Some("prices"),
        resourceVersion = Some(7),
        actor = AuditActorInfo(actorType = "api-key", label = "research-agent", keyLabel = Some("research-agent"), keyId = Some("k_1")),
        reason = Some("replacing with v2"),
        agentSession = Some("sess-1"),
        method = "DELETE",
        path = "/api/v1/tap",
        query = Some("name=prices&apiKey=SECRETVALUE"),
        contentType = None,
        body = Some("""{"name":"prices","password":"hunter2"}"""),
        bodyHash = "abc",
        createdAt = now,
        expiresAt = expiresAt,
        state = state,
        replayToken = Some("topsecret")
    )

    test("document round-trips every field, including the replay token") {
        val pa = sample().copy(decidedBy = Some("todd"), decidedAt = Some(now), resultStatus = Some(200), resultBody = Some("ok"))
        val back = PendingAction.fromDocument(pa.toDocument)
        assert(back == pa)
    }

    test("public JSON never exposes the replay token and redacts secret-looking values") {
        val json = sample().toPublicJson.toString
        assert(!json.contains("topsecret"))
        assert(!json.contains("hunter2"))
        assert(!json.contains("SECRETVALUE"))
        assert(json.contains("\"id\":\"pa_0123456789abcdef\""))
        assert(json.contains("\"reason\":\"replacing with v2\""))
        assert(json.contains("\"resource\":\"prices\""))
    }

    test("a pending action past its expiry reads as expired") {
        assert(!sample().isExpired(now))
        val old = sample(expiresAt = now.minus(1, ChronoUnit.MINUTES))
        assert(old.isExpired(now))
        assert(old.toPublicJson.get("state").getAsString == PendingAction.Expired)
        // decided ones never flip to expired
        assert(!sample(state = PendingAction.Executed, expiresAt = now.minus(1, ChronoUnit.DAYS)).isExpired(now))
    }

    test("hash is stable for the same request and differs by actor, body and query") {
        val a = PendingAction.hashOf("agent", "DELETE", "/api/v1/tap", Some("name=x"), None)
        assert(a == PendingAction.hashOf("agent", "DELETE", "/api/v1/tap", Some("name=x"), None))
        assert(a != PendingAction.hashOf("other", "DELETE", "/api/v1/tap", Some("name=x"), None))
        assert(a != PendingAction.hashOf("agent", "DELETE", "/api/v1/tap", Some("name=y"), None))
        assert(a != PendingAction.hashOf("agent", "DELETE", "/api/v1/tap", Some("name=x"), Some("{}")))
    }

    test("ids and tokens are distinct and well-formed") {
        val ids = (1 to 50).map(_ => PendingAction.newId())
        assert(ids.distinct.size == 50)
        ids.foreach(id => assert(id.matches("pa_[0-9a-f]{16}"), id))
        assert(PendingAction.newToken().matches("[0-9a-f]{48}"))
    }
}
