package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.{ChatIdentity, InstallWideDenial, ScopeDenialFixtures}
import ai.datris.model.{Capability, DatrisEnvironment, ResolvedKey, User}
import ai.datris.util.APIKeyValidator
import ai.datris.util.InMemoryUserDb.grants
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.security.MessageDigest

/** Story: Scoped API keys (plans/stories/scoped-key-read-routes.md), Part C
  * review, finding 4: the warehouse handlers refuse a scope-limited key's
  * caller `sql` before anything is read (behaviour through the handlers, and
  * the order pinned in the source), with the pinned install-wide message;
  * and the Search chat prompt for a limited own-key caller. */
class ScopedKeyInstallWideSqlReviewSpec extends AnyFunSuite with BeforeAndAfterEach {

    private var saved: DatrisEnvironment = _

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        AuditLog.sinkOverride = (_: AuditEntry) => ()
        // Keys off for APIKeyValidator.validate (the identity is the request
        // attribute); no pipeline table: a pipeline lookup would fail, so a
        // 403 here shows none happened.
        ScopeDenialFixtures.install(ScopeDenialFixtures.auditEnv().copy(useApiKeys = false))
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        ScopeDenialFixtures.install(saved)
    }

    private def key(label: String, caps: String*): ResolvedKey = ResolvedKey(None, label, Capability.parseList(caps), isLegacyFullAccess = false)

    private val limited = key("cat-sales", "pipeline:read:catalog=Sales", "query:snowflake", "query:sqlserver", "query:databricks")

    private def body(sql: Any): java.util.Map[String, Any] = {
        val m = new java.util.LinkedHashMap[String, Any]()
        m.put("pipeline", "hr_payroll")
        m.put("sql", sql)
        m
    }

    private val handlers: Seq[(
        String,
        (QueryAPIController, java.util.Map[String, Any], jakarta.servlet.http.HttpServletRequest) => org.springframework.http.ResponseEntity[String]
    )] =
        Seq(
            "/api/v1/query/snowflake" -> ((c, b, r) => c.querySnowflake(null, b, r)),
            "/api/v1/query/sqlserver" -> ((c, b, r) => c.querySqlServer(null, b, r)),
            "/api/v1/query/databricks" -> ((c, b, r) => c.queryDatabricks(null, b, r))
        )

    test("each warehouse handler refuses a scope-limited key's caller sql with the install-wide 403, before the pipeline is looked up") {
        val c = new QueryAPIController()
        for ((path, call) <- handlers; sql <- Seq("select * from HR.PAYROLL", " select 1", java.lang.Double.valueOf(1.0))) {
            val r = ScopeDenialFixtures.request(limited, "POST", path)._1
            val resp = call(c, body(sql), r)
            assert(resp.getStatusCode.value == 403, s"$path $sql -> ${resp.getStatusCode} ${resp.getBody}")
            InstallWideDenial.assertControllerBody(resp.getBody, Seq("hr_payroll", "HR.PAYROLL", "PAYROLL"))
        }
    }

    test("the handlers call keySqlRefusal before the pipeline refusal and before the warehouse") {
        val src = new String(
            Files.readAllBytes(Seq(
                Paths.get("src/main/scala/ai/datris/api/QueryAPIController.scala"),
                Paths.get("datrisserver/src/main/scala/ai/datris/api/QueryAPIController.scala")
            ).find(Files.exists(_)).get),
            StandardCharsets.UTF_8
        )
        for (
            (name, util) <- Seq(
                "querySnowflake" -> "SnowflakeQueryUtil.query(",
                "querySqlServer" -> "SqlServerQueryUtil.query(",
                "queryDatabricks" -> "DatabricksQueryUtil.query("
            )
        ) {
            val start = src.indexOf("def " + name + "(")
            assert(start > 0, name)
            val end = src.indexOf("    @PostMapping", start + 1) match { case -1 => src.length; case i => i }
            val m = src.substring(start, end)
            val k = m.indexOf("QueryAPIController.keySqlRefusal(request, sql)")
            val p = m.indexOf("QueryAPIController.restrictedPipelineRefusal(request, pipelineName)")
            val s = m.indexOf("QueryAPIController.restrictedSqlRefusal(request, sql)")
            val w = m.indexOf(util)
            assert(k > 0 && p > k && s > p && w > s, s"$name: keySqlRefusal=$k pipelineRefusal=$p sqlRefusal=$s warehouse=$w")
        }
    }

    test("keySqlRefusal is the install-wide 403 for a limited key, and None for sessions, unscoped keys, blank sql and no identity") {
        val r = QueryAPIController.keySqlRefusal(ScopeDenialFixtures.request(limited, "POST", "/api/v1/query/snowflake")._1, Some("select 1"))
        assert(r.exists(_.getStatusCode.value == 403))
        InstallWideDenial.assertControllerBody(r.get.getBody, Nil)
        val bob = APIKeyValidator.resolveFromSession(User(
            "bob",
            "h",
            User.RoleEditor,
            null,
            null,
            null,
            restricted = true,
            catalogGrants = grants("Sales" -> "view")
        ))
        assert(QueryAPIController.keySqlRefusal(ScopeDenialFixtures.request(bob, "POST", "/api/v1/query/snowflake")._1, Some("select 1")).isEmpty)
        assert(QueryAPIController.keySqlRefusal(
            ScopeDenialFixtures.request(key("u", "pipeline:read", "tap:read", "query:snowflake"), "POST", "/x")._1,
            Some("select 1")
        ).isEmpty)
        assert(QueryAPIController.keySqlRefusal(ScopeDenialFixtures.request(limited, "POST", "/x")._1, Some(" \t")).isEmpty)
        assert(QueryAPIController.keySqlRefusal(ScopeDenialFixtures.request(limited, "POST", "/x")._1, None).isEmpty)
        assert(QueryAPIController.keySqlRefusal(ScopeDenialFixtures.request(null, "POST", "/x")._1, Some("select 1")).isEmpty)
        assert(QueryAPIController.keySqlRefusal(null, Some("select 1")).isEmpty)
    }

    // ------------------------------------------------------------ Search chat

    private val Hidden = Seq(
        "list_postgres_databases",
        "list_postgres_schemas",
        "list_postgres_tables",
        "list_postgres_columns",
        "list_mongodb_databases",
        "list_mongodb_collections",
        "list_qdrant_collections",
        "list_weaviate_classes",
        "list_milvus_collections",
        "list_chroma_collections",
        "list_pgvector_collections",
        "browse_unity_catalog",
        "query_postgres",
        "query_mongodb",
        "query_natural"
    )

    private def sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString

    test("the Search chat prompt for a limited own-key caller names none of the hidden tools and says not to pass sql") {
        val prompt = new SearchChatAPIController().buildSearchSystemPrompt("test", None, keyLimited = true)
        Hidden.foreach(t => assert(!prompt.contains(t), t))
        assert(prompt.contains("do not pass `sql`"))
        assert(prompt.contains("find_data"))
    }

    test("the Search chat prompt for every other caller is unchanged") {
        val c = new SearchChatAPIController()
        val unrestricted = c.buildSearchSystemPrompt("test", None, keyLimited = false)
        assert(unrestricted == c.buildSearchSystemPrompt("test", None))
        assert(sha(unrestricted) == SearchChatPromptSha, "the unrestricted Search chat prompt changed")
        assert(unrestricted.contains("list_postgres_tables") && unrestricted.contains("query_postgres"))
        val block = Some("## Catalog access\n\nSales")
        assert(c.buildSearchSystemPrompt("test", block, keyLimited = true) == c.buildSearchSystemPrompt("test", block, keyLimited = false))
    }

    test("only an own-key caller with a limited key gets the limited prompt") {
        val full = ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)
        val restrictedUser = User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "view"))
        assert(SearchChatAPIController.keyLimited(ChatIdentity(None, Some(limited), ownKey = true)))
        assert(!SearchChatAPIController.keyLimited(ChatIdentity(None, Some(key("u", "pipeline:read", "tap:read")), ownKey = true)))
        assert(!SearchChatAPIController.keyLimited(ChatIdentity(None, Some(full), ownKey = false)))
        assert(!SearchChatAPIController.keyLimited(ChatIdentity(
            Some(restrictedUser),
            Some(APIKeyValidator.resolveFromSession(restrictedUser)),
            ownKey = false
        )))
        assert(!SearchChatAPIController.keyLimited(ChatIdentity(None, None, ownKey = false)))
        assert(!SearchChatAPIController.keyLimited(null))
    }

    test("the unrestricted Search chat prompt keeps its unrestricted guidance and differs from the limited one") {
        val c = new SearchChatAPIController()
        val unrestricted = c.buildSearchSystemPrompt("test", None, keyLimited = false)
        assert(unrestricted == c.buildSearchSystemPrompt("test", None), "keyLimited = false must be the call every other caller gets")
        Seq(
            "list_postgres_schemas",
            "list_postgres_tables",
            "list_postgres_columns",
            "list_mongodb_collections",
            "query_natural",
            "query_postgres",
            "query_mongodb"
        )
            .foreach(t => assert(unrestricted.contains(t), s"unrestricted prompt lost $t"))
        assert(unrestricted.contains("Treat cataloged and uncataloged sources equally"))
        assert(!unrestricted.contains("do not pass `sql`"))
        assert(unrestricted != c.buildSearchSystemPrompt("test", None, keyLimited = true))
    }

    /** SHA-256 of the unrestricted Search chat prompt for tenant `test`.
      * Why: Part C added a limited branch to the Search chat prompt and the
      * prompt every other caller gets had to stay byte-identical; this pin
      * catches any change to it. If you change the unrestricted prompt on
      * purpose, re-pin: run this spec, copy the hash it reports (the left
      * side of the failed comparison) into this value, and check that the
      * test above still passes. */
    private val SearchChatPromptSha = "e4da013393eea082afb8c85df397437063179d14107567349e4292301b58c909"
}
