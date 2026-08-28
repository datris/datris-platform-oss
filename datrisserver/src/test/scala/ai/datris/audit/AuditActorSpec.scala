package ai.datris.audit

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Capability, ResolvedKey, User}
import org.scalatest.funsuite.AnyFunSuite

class AuditActorSpec extends AnyFunSuite {

    private def user(name: String, role: String) = User(name, "hash", role, "t", "t", null)
    private def key(label: String, keyId: Option[String] = None, legacy: Boolean = false) =
        ResolvedKey(None, label, if (legacy) Seq(Capability.FullAccess) else Capability.parseList(Seq("tap:read")), legacy, keyId)

    test("session user → type=user, label session:<name>, role, no key fields") {
        val a = AuditActor.from(None, Some(user("tfearn", "admin")), None, None)
        assert(a.actorType == "user")
        assert(a.label == "session:tfearn")
        assert(a.username.contains("tfearn"))
        assert(a.role.contains("admin"))
        assert(a.keyLabel.isEmpty && a.keyId.isEmpty)
        assert(a.legacyFullAccess)
    }

    test("api key → type=api-key, label == keyLabel, keyId carried, no username/role") {
        val a = AuditActor.from(Some(key("claude-desktop", Some("k_abc123"))), None, None, None)
        assert(a.actorType == "api-key")
        assert(a.label == "claude-desktop")
        assert(a.keyLabel.contains("claude-desktop"))
        assert(a.keyId.contains("k_abc123"))
        assert(a.username.isEmpty && a.role.isEmpty)
        assert(!a.legacyFullAccess)
    }

    test("pre-id legacy key has keyId=None and legacyFullAccess=true") {
        val a = AuditActor.from(Some(key("old-key", legacy = true)), None, None, None)
        assert(a.keyId.isEmpty)
        assert(a.legacyFullAccess)
    }

    test("ui key on behalf of a user → type=assistant with both identities") {
        val relabeled = key("ui").copy(label = "session:tfearn")
        val a = AuditActor.from(Some(relabeled), None, Some(user("tfearn", "editor")), Some("ui"))
        assert(a.actorType == "assistant")
        assert(a.label == "session:tfearn")
        assert(a.username.contains("tfearn"))
        assert(a.role.contains("editor"))
        assert(a.keyLabel.contains("ui"))
    }

    test("session user wins over an on-behalf-of attribute") {
        val a = AuditActor.from(Some(key("ui")), Some(user("alice", "admin")), Some(user("bob", "viewer")), Some("ui"))
        assert(a.actorType == "user")
        assert(a.username.contains("alice"))
    }

    test("tap-run token (label tap:<name>) → type=tap labeled by tap name, no key fields") {
        val rk = ai.datris.auth.TapRunTokens.resolve(ai.datris.auth.TapRunTokens.issue("crypto-prices", None, 60)).get
        val a = AuditActor.from(Some(rk), None, None, None)
        assert(a.actorType == "tap")
        assert(a.label == "crypto-prices")
        assert(a.keyLabel.isEmpty && a.keyId.isEmpty)
        assert(!a.legacyFullAccess)
    }

    test("a plain key cannot become a tap actor by label alone unless it carries the tap: prefix") {
        val a = AuditActor.from(Some(key("crypto-prices")), None, None, None)
        assert(a.actorType == "api-key")
    }

    test("nothing resolved → anonymous api-key, honest about no identity") {
        val a = AuditActor.from(None, None, None, None)
        assert(a.actorType == "api-key")
        assert(a.label == AuditActor.Anonymous)
        assert(a.keyId.isEmpty)
        assert(a.legacyFullAccess)
    }

    test("only the ui key may vouch for a user when API keys are on") {
        assert(AuditActor.trustsOnBehalfOf("ui", useApiKeys = true))
        assert(!AuditActor.trustsOnBehalfOf("claude-desktop", useApiKeys = true))
        assert(!AuditActor.trustsOnBehalfOf("anonymous", useApiKeys = true))
        assert(!AuditActor.trustsOnBehalfOf("session:tfearn", useApiKeys = true))
    }

    test("with API keys off there is no key to distrust") {
        assert(AuditActor.trustsOnBehalfOf("anonymous", useApiKeys = false))
        assert(AuditActor.trustsOnBehalfOf("whatever", useApiKeys = false))
    }

    test("actor JSON never contains a key value, only label and id") {
        val a = AuditActor.from(Some(key("claude-desktop", Some("k_abc123"))), None, None, None)
        val json = a.toJson.toString
        assert(json.contains("\"keyLabel\":\"claude-desktop\""))
        assert(json.contains("\"keyId\":\"k_abc123\""))
        assert(!json.contains("value"))
    }
}
