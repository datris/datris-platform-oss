package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import org.springframework.util.AntPathMatcher

/** The result of looking up the capability required by an incoming request. */
sealed trait RouteCheck
object RouteCheck {
    /** Route is exempt — handled by user-session auth (login, /me, /users)
      * or genuinely public (health, version). Skip the capability check. */
    case object Skip extends RouteCheck
    /** No mapping found for this method+path combination. In log-only mode
      * this is informational; in enforce mode it should deny by default. */
    case object Unmapped extends RouteCheck
    /** This route requires the named capability. */
    case class Require(resource: String, action: String) extends RouteCheck
}

/** Central declarative mapping from HTTP method + path pattern to the
  * capability a request must hold. Lives in one file so the surface area
  * is auditable at a glance; alternative annotation-per-method approach
  * would require touching every controller. First match wins; patterns use
  * Spring AntPathMatcher syntax (`**` for any path tail). */
object CapabilityRoutes {

    private val matcher = new AntPathMatcher()

    private case class Route(method: String, pattern: String, resource: String, action: String)

    /** Patterns whose requests bypass the capability check entirely. These
      * are handled by the user-session auth path (login, role enforcement)
      * or are genuinely public infrastructure endpoints. */
    private val skipPatterns: Seq[String] = Seq(
        "/api/v1/auth/**",
        "/api/v1/login",
        "/api/v1/logout",
        "/api/v1/change-password",
        "/api/v1/me",
        "/api/v1/users",
        "/api/v1/users/**",
        "/api/v1/keys",
        "/api/v1/keys/**",
        "/api/v1/health/**",
        "/api/v1/version",
        "/api/v1/mcp/activity",
        "/api/v1/assistant/**",
        "/api/v1/ops-chat/**"
    )

    private val routes: Seq[Route] = Seq(
        // Pipelines
        Route("GET",    "/api/v1/pipeline",            "pipeline", "read"),
        Route("GET",    "/api/v1/pipelines",           "pipeline", "read"),
        Route("POST",   "/api/v1/pipeline",            "pipeline", "create"),
        Route("DELETE", "/api/v1/pipeline",            "pipeline", "delete"),
        Route("POST",   "/api/v1/pipeline/generate",   "pipeline", "create"),
        Route("POST",   "/api/v1/pipeline/upload",     "document", "upload"),
        Route("POST",   "/api/v1/pipeline/profile",    "metadata", "read"),
        Route("GET",    "/api/v1/pipeline/status",     "job",      "read"),
        Route("DELETE", "/api/v1/pipeline/status",     "job",      "kill"),

        // Taps
        Route("GET",    "/api/v1/tap",                 "tap",      "read"),
        Route("GET",    "/api/v1/taps",                "tap",      "read"),
        Route("POST",   "/api/v1/tap",                 "tap",      "create"),
        Route("DELETE", "/api/v1/tap",                 "tap",      "delete"),
        Route("POST",   "/api/v1/tap/run",             "tap",      "run"),
        Route("POST",   "/api/v1/tap/cron",            "tap",      "update"),
        Route("POST",   "/api/v1/tap/script",          "tap",      "update"),
        Route("POST",   "/api/v1/tap/test",            "tap",      "run"),
        Route("POST",   "/api/v1/tap/generate",        "tap",      "create"),
        Route("POST",   "/api/v1/tap/fix",             "tap",      "update"),
        Route("POST",   "/api/v1/tap/review",          "tap",      "read"),
        Route("POST",   "/api/v1/tap/optimize",        "tap",      "update"),
        Route("POST",   "/api/v1/tap/brainstorm",      "tap",      "read"),
        Route("GET",    "/api/v1/tap/ledger",          "tap",      "read"),
        Route("DELETE", "/api/v1/tap/ledger",          "tap",      "update"),
        Route("GET",    "/api/v1/tap/logs",            "job",      "read"),

        // Tap prompts (curated tap-building hints)
        Route("GET",    "/api/v1/tap-prompts",         "tap",      "read"),
        Route("GET",    "/api/v1/tap-prompts/**",      "tap",      "read"),
        Route("POST",   "/api/v1/tap-prompts",         "tap",      "update"),
        Route("POST",   "/api/v1/tap-prompts/suggest", "tap",      "read"),
        Route("DELETE", "/api/v1/tap-prompts/**",      "tap",      "update"),

        // Secrets
        Route("GET",    "/api/v1/secrets",             "secret",   "read"),
        Route("GET",    "/api/v1/secrets/**",          "secret",   "read"),
        Route("PUT",    "/api/v1/secrets/**",          "secret",   "write"),
        Route("DELETE", "/api/v1/secrets/**",          "secret",   "write"),

        // Query / job
        Route("POST",   "/api/v1/query/postgres",      "query",    "postgres"),
        Route("POST",   "/api/v1/query/mongodb",       "query",    "mongodb"),
        Route("POST",   "/api/v1/query/objectstore",   "query",    "objectstore"),
        Route("POST",   "/api/v1/query/natural",       "query",    "natural"),
        Route("POST",   "/api/v1/ai/answer",           "query",    "natural"),
        Route("POST",   "/api/v1/job/kill",            "job",      "kill"),

        // Vector search (one capability across all stores; the scope identifies the collection)
        Route("POST",   "/api/v1/search/**",           "search",   "vector"),

        // Metadata
        Route("GET",    "/api/v1/metadata/**",         "metadata", "read"),
        Route("GET",    "/api/v1/vector-stores/available", "metadata", "read"),
        Route("POST",   "/api/v1/config/generate-schema", "metadata", "read"),

        // Config
        Route("POST",   "/api/v1/config/upload",       "config",   "write"),
        Route("GET",    "/api/v1/ai/model-catalog",    "config",   "read")
    )

    def lookup(method: String, path: String): RouteCheck = {
        if (skipPatterns.exists(p => matcher.`match`(p, path))) return RouteCheck.Skip

        routes.find(r => r.method.equalsIgnoreCase(method) && matcher.`match`(r.pattern, path)) match {
            case Some(r) => RouteCheck.Require(r.resource, r.action)
            case None    => RouteCheck.Unmapped
        }
    }
}
