package ai.datris.audit

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

class AuditClassifierSpec extends AnyFunSuite {

    private def c(method: String, path: String, logReads: Boolean = false) =
        AuditClassifier.classify(method, path, logReads)

    test("capability-mapped writes classify as resource/action") {
        assert(c("POST", "/api/v1/pipeline").contains(AuditRoute("pipeline", "create", "pipeline")))
        assert(c("DELETE", "/api/v1/pipeline").contains(AuditRoute("pipeline", "delete", "pipeline")))
        assert(c("POST", "/api/v1/tap/run").contains(AuditRoute("tap", "run", "tap")))
        assert(c("PUT", "/api/v1/secrets/my-secret").contains(AuditRoute("secret", "write", "secret")))
        assert(c("POST", "/api/v1/job/kill").contains(AuditRoute("job", "kill", "job")))
        assert(c("POST", "/api/v1/config/upload").contains(AuditRoute("config", "write", "config")))
    }

    test("reads are dropped by default and kept when logReads is on") {
        assert(c("GET", "/api/v1/pipelines").isEmpty)
        assert(c("POST", "/api/v1/query/postgres").isEmpty)
        assert(c("POST", "/api/v1/search/anything").isEmpty)
        assert(c("GET", "/api/v1/metadata/x").isEmpty)
        assert(c("GET", "/api/v1/pipelines", logReads = true).contains(AuditRoute("pipeline", "read", "pipeline")))
        assert(c("POST", "/api/v1/query/postgres", logReads = true).contains(AuditRoute("query", "postgres", "query")))
    }

    test("secret reads are the one read always logged") {
        assert(c("GET", "/api/v1/secrets").contains(AuditRoute("secret", "read", "secret")))
        assert(c("GET", "/api/v1/secrets/prod-db").contains(AuditRoute("secret", "read", "secret")))
    }

    test("reading the audit log itself is not audited") {
        assert(c("GET", "/api/v1/audit-log").isEmpty)
        assert(c("GET", "/api/v1/audit-log/facets").isEmpty)
    }

    test("supplemental table covers auth, users and keys") {
        assert(c("POST", "/api/v1/auth/login").contains(AuditRoute("auth", "login", "user")))
        assert(c("POST", "/api/v1/auth/logout").contains(AuditRoute("auth", "logout", "user")))
        assert(c("POST", "/api/v1/auth/change-password").contains(AuditRoute("auth", "change-password", "user")))
        assert(c("POST", "/api/v1/auth/users").contains(AuditRoute("user", "create", "user")))
        assert(c("PATCH", "/api/v1/auth/users/bob").contains(AuditRoute("user", "update", "user")))
        assert(c("DELETE", "/api/v1/auth/users/bob").contains(AuditRoute("user", "delete", "user")))
        assert(c("POST", "/api/v1/keys").contains(AuditRoute("key", "issue", "key")))
        assert(c("DELETE", "/api/v1/keys/claude-desktop").contains(AuditRoute("key", "revoke", "key")))
        assert(c("POST", "/api/v1/keys/claude-desktop/rotate").contains(AuditRoute("key", "rotate", "key")))
    }

    test("skip-listed reads and chat streams are not audited") {
        assert(c("GET", "/api/v1/auth/me").isEmpty)
        assert(c("GET", "/api/v1/auth/users").isEmpty)
        assert(c("GET", "/api/v1/keys").isEmpty)
        assert(c("GET", "/api/v1/version").isEmpty)
        assert(c("GET", "/api/v1/health/ready").isEmpty)
        assert(c("GET", "/api/v1/mcp/activity").isEmpty)
        assert(c("POST", "/api/v1/assistant/chat").isEmpty)
        assert(c("POST", "/api/v1/ops-chat/chat").isEmpty)
    }

    test("HEAD and OPTIONS are never audited") {
        assert(c("HEAD", "/api/v1/pipeline").isEmpty)
        assert(c("OPTIONS", "/api/v1/pipeline").isEmpty)
    }

    test("unmapped non-GET routes surface as category=unmapped, unmapped GETs are dropped") {
        assert(c("POST", "/api/v1/does-not-exist").contains(AuditRoute("unmapped", "post", "route")))
        assert(c("DELETE", "/api/v1/does-not-exist").contains(AuditRoute("unmapped", "delete", "route")))
        assert(c("GET", "/api/v1/does-not-exist").isEmpty)
    }

    test("method matching is case-insensitive") {
        assert(c("post", "/api/v1/pipeline").contains(AuditRoute("pipeline", "create", "pipeline")))
        assert(c("delete", "/api/v1/keys/x").contains(AuditRoute("key", "revoke", "key")))
    }
}
