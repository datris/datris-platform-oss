package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Capability, DatrisException}
import org.scalatest.funsuite.AnyFunSuite

import scala.util.{Failure, Success}

/** Revocation only flags metadata — the key value stays in `oss/api-keys` —
  * so both the controller-side `validate` and the interceptor-side
  * `resolveKey` must consult the revoked flag. */
class APIKeyValidatorSpec extends AnyFunSuite {

    private val keys = Map("reader" -> "val-reader", "legacy" -> "val-legacy", "live" -> "val-live")
    private val metadata = Map(
        "reader" -> """{"capabilities":["pipeline:read"],"revoked":true,"keyId":"k1"}""",
        "live" -> """{"capabilities":["pipeline:read"],"revoked":false,"keyId":"k2"}"""
    )

    test("validate rejects a revoked key whose value is still in oss/api-keys") {
        val e = intercept[DatrisException](APIKeyValidator.validateAgainst("val-reader", keys, metadata))
        assert(e.getMessage == "API key 'reader' is revoked")
        assert(e.isInstanceOf[RevokedKeyException] && e.asInstanceOf[RevokedKeyException].label == "reader")
    }

    test("validate accepts a live scoped key") {
        APIKeyValidator.validateAgainst("val-live", keys, metadata)
    }

    test("validate accepts a legacy key with no metadata entry") {
        APIKeyValidator.validateAgainst("val-legacy", keys, metadata)
    }

    test("validate rejects an unknown value without reading metadata") {
        val e = intercept[DatrisException](
            APIKeyValidator.validateAgainst("nope", keys, fail("metadata must not be read for an unknown key"))
        )
        assert(e.getMessage == "Invalid x-api-key")
        assert(!e.getMessage.contains("nope"), "the presented value must not be echoed")
    }

    test("resolveKey core still throws for a revoked key") {
        val e = intercept[DatrisException](APIKeyValidator.resolveAgainst("val-reader", keys, metadata))
        assert(e.getMessage == "API key 'reader' is revoked")
    }

    test("resolveKey core resolves scoped and legacy keys") {
        val live = APIKeyValidator.resolveAgainst("val-live", keys, metadata)
        assert(live.label == "live" && !live.isLegacyFullAccess && live.keyId.contains("k2"))
        val legacy = APIKeyValidator.resolveAgainst("val-legacy", keys, metadata)
        assert(legacy.isLegacyFullAccess && legacy.capabilities == Seq(Capability.FullAccess))
    }

    test("absent metadata secret means legacy full access") {
        assert(APIKeyValidator.metadataFrom(Success(None)).isEmpty)
        val legacy = APIKeyValidator.resolveAgainst("val-reader", keys, APIKeyValidator.metadataFrom(Success(None)))
        assert(legacy.isLegacyFullAccess)
    }

    test("present metadata secret is returned as a map") {
        val m = new java.util.HashMap[String, String]()
        m.put("reader", metadata("reader"))
        assert(APIKeyValidator.metadataFrom(Success(Some(m))) == Map("reader" -> metadata("reader")))
    }

    test("metadata read failure fails closed for resolve and validate") {
        val failed = Failure(new RuntimeException("vault 503"))
        val e1 = intercept[DatrisException](APIKeyValidator.resolveAgainst("val-reader", keys, APIKeyValidator.metadataFrom(failed)))
        assert(e1.getMessage == APIKeyValidator.MetadataUnavailableMessage)
        val e2 = intercept[DatrisException](APIKeyValidator.validateAgainst("val-legacy", keys, APIKeyValidator.metadataFrom(failed)))
        assert(e2.getMessage == APIKeyValidator.MetadataUnavailableMessage)
    }

    test("corrupt metadata JSON for one key only affects that key") {
        val md = metadata + ("live" -> "{not json")
        intercept[DatrisException](APIKeyValidator.resolveAgainst("val-live", keys, md))
        assert(APIKeyValidator.resolveAgainst("val-legacy", keys, md).isLegacyFullAccess)
        intercept[DatrisException](APIKeyValidator.resolveAgainst("val-reader", keys, md))
    }

    // Story: Field protection 5 (plans/stories/field-protection-5-encrypt-reveal.md).
    // Calls APIKeyValidator.resolveFromSession(User(...)) only. Reveal and key
    // rotation must be granted explicitly; no role bundle carries protect:*.
    test("editor and viewer bundles do not include protect capabilities") {
        Seq(ai.datris.model.User.RoleEditor, ai.datris.model.User.RoleViewer).foreach { role =>
            val resolved = APIKeyValidator.resolveFromSession(ai.datris.model.User("u", "h", role, null, null, null))
            val raw = resolved.capabilities.map(_.raw)
            assert(!raw.exists(c => c.startsWith("protect:") || c.startsWith("*:")), s"$role: $raw")
            assert(!resolved.matchesResourceAction("protect", "reveal"), s"$role must not reveal")
            assert(!resolved.matchesResourceAction("protect", "admin"), s"$role must not rotate")
        }
        // Admins hold FullAccess and therefore can reveal and rotate.
        val admin = APIKeyValidator.resolveFromSession(ai.datris.model.User("u", "h", ai.datris.model.User.RoleAdmin, null, null, null))
        assert(admin.matchesResourceAction("protect", "reveal") && admin.matchesResourceAction("protect", "admin"))
    }

    // Story: Catalog-scoped user access (plans/stories/catalog-scoped-user-access.md),
    // Acceptance bullets 2 and 7. Pins ResolvedKey.restrictedSession and the
    // User(restricted, catalogGrants) fields.

    /** The editor bundle as served before the story; unrestricted users must keep it exactly. */
    private val EditorBundleToday = Seq(
        "pipeline:read",
        "pipeline:create",
        "pipeline:update",
        "pipeline:delete",
        "pipeline:run",
        "tap:read",
        "tap:create",
        "tap:update",
        "tap:delete",
        "tap:run",
        "document:upload",
        "search:vector",
        "query:postgres",
        "query:mongodb",
        "query:objectstore",
        "query:snowflake",
        "query:databricks",
        "query:sqlserver",
        "query:natural",
        "job:read",
        "job:kill",
        "metadata:read",
        "config:read",
        "mcp:tool"
    )

    test("resolveFromSession of an unrestricted editor is unchanged") {
        val rk = APIKeyValidator.resolveFromSession(ai.datris.model.User("ed", "h", ai.datris.model.User.RoleEditor, null, null, null))
        assert(rk.label == "session:ed")
        assert(rk.capabilities.map(_.raw) == EditorBundleToday)
        assert(!rk.isLegacyFullAccess)
        assert(!rk.restrictedSession)
        assert(rk.tenantEnvironment.isEmpty && rk.keyId.isEmpty)
    }

    test("an existing user document without restricted/catalogGrants resolves exactly as before upgrade") {
        // Shape of a user row written before the story (no new fields).
        val legacyJson = """{"username":"ed","passwordHash":"h","role":"editor","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}"""
        val legacy = new com.google.gson.Gson().fromJson(legacyJson, classOf[ai.datris.model.User])
        assert(!legacy.restricted && legacy.catalogGrants == null)
        val rk = APIKeyValidator.resolveFromSession(legacy)
        assert(rk.capabilities.map(_.raw) == EditorBundleToday && !rk.restrictedSession && !rk.isLegacyFullAccess)
        val viewerJson = legacyJson.replace("\"editor\"", "\"viewer\"")
        val viewer = new com.google.gson.Gson().fromJson(viewerJson, classOf[ai.datris.model.User])
        val vrk = APIKeyValidator.resolveFromSession(viewer)
        assert(!vrk.restrictedSession && vrk.grants("pipeline", "read", Map.empty) && vrk.grants("query", "postgres", Map.empty))
    }

    test("restricted editor sets restrictedSession and catalog scopes") {
        val g = new java.util.HashMap[String, String]()
        g.put("Sales", "edit")
        val bob = ai.datris.model.User("bob", "h", ai.datris.model.User.RoleEditor, null, null, null, restricted = true, catalogGrants = g)
        val rk = APIKeyValidator.resolveFromSession(bob)
        assert(rk.label == "session:bob")
        assert(rk.restrictedSession)
        assert(!rk.isLegacyFullAccess)
        val raw = rk.capabilities.map(_.raw).toSet
        assert(raw.contains("pipeline:read:catalog=Sales") && raw.contains("tap:update:catalog=Sales"), raw)
        assert(!raw.contains("pipeline:read") && !raw.contains("tap:read"), s"no unscoped pipeline/tap read: $raw")
        assert(rk.grants("pipeline", "update", Map("catalog" -> "Sales")))
        assert(!rk.grants("pipeline", "read", Map("catalog" -> "HR")))
        assert(!rk.grants("pipeline", "read", Map.empty), "an Uncataloged pipeline is outside every grant")
        assert(!rk.grants("pipeline", "delete", Map("catalog" -> "Sales")), "edit does not include delete")
    }

    test("an admin is never restricted, whatever the stored flag says") {
        val g = new java.util.HashMap[String, String]()
        g.put("Sales", "view")
        val admin = ai.datris.model.User("root", "h", ai.datris.model.User.RoleAdmin, null, null, null, restricted = true, catalogGrants = g)
        val rk = APIKeyValidator.resolveFromSession(admin)
        assert(rk.isLegacyFullAccess && !rk.restrictedSession)
        assert(rk.capabilities == Seq(Capability.FullAccess))
    }

    // Story: API keys, a scope the server does not apply can no longer be put
    // on a key, and stored ones stop granting (plans/stories/api-key-inert-scopes.md),
    // Acceptance, InertScopeGateSpec and APIKeyValidatorSpec. Pins the new
    // ResolvedKey.inertCapabilities field and that resolveAgainst splits a
    // stored key with CapabilityScopes.partition (Capability.parse stays lenient,
    // so the key still resolves).
    test("a stored key with job:read:owner=self resolves without it and with it under inertCapabilities") {
        val ks = Map("ik_old" -> "val-ik-old", "ik_ok" -> "val-ik-ok")
        val md = Map(
            "ik_old" -> """{"capabilities":["job:read:owner=self","pipeline:read"],"revoked":false,"keyId":"k9"}""",
            "ik_ok" -> """{"capabilities":["pipeline:read:catalog=Sales","search:vector:collection=docs"],"revoked":false}"""
        )
        val rk = APIKeyValidator.resolveAgainst("val-ik-old", ks, md)
        assert(rk.label == "ik_old" && !rk.isLegacyFullAccess && rk.keyId.contains("k9"))
        assert(rk.capabilities.map(_.raw) == Seq("pipeline:read"), rk.capabilities.map(_.raw))
        assert(rk.inertCapabilities.map(_.raw) == Seq("job:read:owner=self"), rk.inertCapabilities.map(_.raw))
        assert(!rk.matchesResourceAction("job", "read"), "the inert capability grants nothing")
        assert(!rk.grants("job", "read", Map("owner" -> "ik_old")))
        assert(rk.grants("pipeline", "read", Map.empty))

        val ok = APIKeyValidator.resolveAgainst("val-ik-ok", ks, md)
        assert(ok.inertCapabilities.isEmpty, ok.inertCapabilities.map(_.raw))
        assert(ok.capabilities.map(_.raw) == Seq("pipeline:read:catalog=Sales", "search:vector:collection=docs"))
        // legacy keys and sessions carry none
        assert(APIKeyValidator.resolveAgainst("val-legacy", keys, metadata).inertCapabilities.isEmpty)
        assert(APIKeyValidator.resolveFromSession(ai.datris.model.User("ed", "h", ai.datris.model.User.RoleEditor, null, null, null)).inertCapabilities.isEmpty)
    }
}
