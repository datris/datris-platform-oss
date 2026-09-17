package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.AIConfig
import ai.datris.util.DoctorService._
import org.scalatest.funsuite.AnyFunSuite

/** Each doctor check against a fake probe: the rule that fires, the status it
  * fires at, and the remediation text an operator will actually see. */
class DoctorServiceSpec extends AnyFunSuite {

    private val slots = Slots("oss/ai-primary", "oss/codegen", "oss/embedding")

    private val goodPrimary =
        Map("provider" -> "anthropic", "endpoint" -> "https://api.anthropic.com/v1/messages", "model" -> "claude-fable-5-1", "apiKey" -> "sk-ant")
    private val goodCodegen =
        Map("provider" -> "anthropic", "endpoint" -> "https://api.anthropic.com/v1/messages", "model" -> "claude-opus-5", "apiKey" -> "sk-ant")
    private val teiEmbedding = Map("provider" -> "tei", "endpoint" -> "http://tei:80/v1/embeddings", "model" -> "bge-m3")

    /** A probe where everything is healthy; tests override one field. */
    class FakeProbes(
        var lookup: Option[Map[String, String]] = Some(Map("ttl" -> "315000000", "period" -> "315360000")),
        var secrets: Map[String, Map[String, String]] = Map("oss/ai-primary" -> goodPrimary, "oss/codegen" -> goodCodegen, "oss/embedding" -> teiEmbedding),
        var classes: Set[String] = Set.empty,
        var pipelines: List[(String, String)] = Nil,
        var http: Map[String, (Int, String)] = Map(
            "http://tei:80/health" -> (200, "{}"),
            "http://tei:80/info" -> (200, "{\"model_id\":\"BAAI/bge-m3\"}")
        ),
        var disk: Map[String, (Long, Long)] = Map("/srv" -> (100L, 50L)),
        var chat: Seq[(String, AIConfig)] = Nil,
        var chatResponses: Map[String, (Int, String)] = Map.empty,
        var keyStoreResolves: Boolean = false,
        var overrides: List[(String, String, String)] = Nil
    ) extends Probes {
        def vaultLookupSelf(): Option[Map[String, String]] = lookup
        def secret(name: String): Option[Map[String, String]] = secrets.get(name)
        def apiKeyResolves(provider: String, rawKey: String): Boolean = keyStoreResolves
        def classPresent(className: String): Boolean = classes.contains(className)
        def pipelineSources(): List[(String, String)] = pipelines

        /** (pipelineName, provider, destinationBucketOverride) for objectStore pipelines with an override. */
        def objectStoreBucketOverrides(): List[(String, String, String)] = overrides
        def httpGet(url: String, timeoutMs: Int): (Int, String) =
            http.getOrElse(url, throw new java.net.ConnectException("Connection refused: " + url))
        def diskUsage(path: String): Option[(Long, Long)] = disk.get(path)
        def envSeen(names: Seq[String]): Map[String, Boolean] = names.map(n => n -> (n == "DATRIS_ENV")).toMap
        def chatSlots(): Seq[(String, AIConfig)] = chat
        def probeChat(config: AIConfig, timeoutMs: Int): (Int, String) =
            chatResponses.getOrElse(config.model, throw new java.net.SocketTimeoutException("read timed out"))
        def probeEmbedding(provider: String, endpoint: String, model: String, apiKey: String, timeoutMs: Int): (Int, String) = (200, "{}")
    }

    // 1. vault.token_ttl
    test("vault.token_ttl: 10y period with 28d ttl is the clamp — warn") {
        val r = new VaultTokenTtlCheck(new FakeProbes(lookup = Some(Map("ttl" -> "2419200", "period" -> "315360000")))).run()
        assert(r.status == "warn")
        assert(r.detail.contains("clamped"))
        assert(r.remediation.contains("max_lease_ttl"))
        assert(r.remediation.contains("--force-recreate vault"))
    }

    test("vault.token_ttl: a token freshly clamped to Vault's 768h default ceiling is a warn") {
        val r = new VaultTokenTtlCheck(new FakeProbes(lookup = Some(Map("ttl" -> "2764800", "period" -> "315360000")))).run()
        assert(r.status == "warn")
        assert(r.detail.contains("clamped"))
    }

    test("vault.token_ttl: under seven days is an error") {
        val r = new VaultTokenTtlCheck(new FakeProbes(lookup = Some(Map("ttl" -> "300000", "period" -> "315360000")))).run()
        assert(r.status == "error")
        assert(r.detail.contains("expires in 3d"))
    }

    test("vault.token_ttl: ttl spanning the period is ok; no lookup is a skip; root token has no expiry") {
        assert(new VaultTokenTtlCheck(new FakeProbes()).run().status == "ok")
        assert(new VaultTokenTtlCheck(new FakeProbes(lookup = None)).run().status == "skip")
        val root = new VaultTokenTtlCheck(new FakeProbes(lookup = Some(Map("ttl" -> "0", "period" -> "0")))).run()
        assert(root.status == "ok" && root.detail.contains("no expiry"))
    }

    // 2. vault.ai_slots
    test("vault.ai_slots: missing oss/codegen is an error naming the vault kv put fix") {
        val p = new FakeProbes()
        p.secrets = p.secrets - "oss/codegen"
        val r = new VaultAiSlotsCheck(p, slots).run()
        assert(r.status == "error")
        assert(r.detail.contains("oss/codegen"))
        assert(r.remediation.contains("vault kv put secret/oss/codegen"))
    }

    test("vault.ai_slots: present but empty model names the field") {
        val p = new FakeProbes()
        p.secrets = p.secrets + ("oss/codegen" -> (goodCodegen + ("model" -> "")))
        val r = new VaultAiSlotsCheck(p, slots).run()
        assert(r.status == "error")
        assert(r.detail.contains("'model'"))
        assert(r.detail.contains("oss/codegen"))
    }

    test("vault.ai_slots: keyless providers and a key resolved from the shared store are fine") {
        val p = new FakeProbes()
        p.secrets = p.secrets + ("oss/ai-primary" -> (goodPrimary - "apiKey"))
        assert(new VaultAiSlotsCheck(p, slots).run().status == "error")
        p.keyStoreResolves = true
        val r = new VaultAiSlotsCheck(p, slots).run()
        assert(r.status == "ok", r.detail)
        assert(r.detail.contains("embedding (tei/bge-m3)"))
    }

    // 3. jdbc.mssql_driver
    test("jdbc.mssql_driver: absent driver is ok until a pipeline uses type: mssql") {
        val p = new FakeProbes()
        assert(new JdbcMssqlDriverCheck(p).run().status == "ok")
        p.pipelines = List(("orders", "postgres"), ("legacy_erp", "mssql"))
        val r = new JdbcMssqlDriverCheck(p).run()
        assert(r.status == "error")
        assert(r.detail.contains("legacy_erp"))
        assert(!r.detail.contains("orders"))
        p.classes = Set("com.microsoft.sqlserver.jdbc.SQLServerDriver")
        assert(new JdbcMssqlDriverCheck(p).run().status == "ok")
    }

    // 3b. objectstore.bucket_overrides (story: objectstore-bucket-allowlist)
    // Seams pinned: Probes.objectStoreBucketOverrides(): List[(pipeline, provider, bucket)],
    // class ObjectStoreBucketOverrideCheck(probes) with id "objectstore.bucket_overrides"
    // and startupSafe = true, reading the same allowlist as the validator via the
    // `datris.objectStoreBucketAllowlist` system property (twin of
    // DATRIS_OBJECTSTORE_BUCKET_ALLOWLIST), set/cleared here with try/finally.

    private val allowlistVariable = "DATRIS_OBJECTSTORE_BUCKET_ALLOWLIST"

    private def withBucketAllowlist[A](value: Option[String])(body: => A): A = {
        val key = "datris.objectStoreBucketAllowlist"
        val previous = sys.props.get(key)
        value match {
            case Some(v) => sys.props(key) = v
            case None => sys.props -= key
        }
        try body
        finally previous match {
                case Some(v) => sys.props(key) = v
                case None => sys.props -= key
            }
    }

    test("objectstore.bucket_overrides: id and startupSafe are pinned") {
        val c = new ObjectStoreBucketOverrideCheck(new FakeProbes())
        assert(c.id == "objectstore.bucket_overrides")
        assert(c.startupSafe)
    }

    test("objectstore.bucket_overrides: no minio overrides is a skip, allowlist set or not") {
        withBucketAllowlist(None) {
            assert(new ObjectStoreBucketOverrideCheck(new FakeProbes()).run().status == "skip")
            val s3Only = new FakeProbes(overrides = List(("customer", "s3", "customer-owned-bucket")))
            assert(new ObjectStoreBucketOverrideCheck(s3Only).run().status == "skip", "provider=s3 overrides are exempt")
        }
        withBucketAllowlist(Some("team-a")) {
            assert(new ObjectStoreBucketOverrideCheck(new FakeProbes()).run().status == "skip")
        }
    }

    test("objectstore.bucket_overrides: allowlist unset with minio overrides warns, naming the pipelines and the variable") {
        withBucketAllowlist(None) {
            val p = new FakeProbes(overrides = List(("orders", "minio", "team-a"), ("events", "minio", "team-b"), ("customer", "s3", "customer-owned-bucket")))
            val r = new ObjectStoreBucketOverrideCheck(p).run()
            assert(r.status == "warn", r.detail)
            assert(r.detail.contains("orders") && r.detail.contains("events"), r.detail)
            assert(!r.detail.contains("customer"), "provider=s3 pipelines must not be listed: " + r.detail)
            assert(r.remediation.contains(allowlistVariable), r.remediation)
        }
    }

    test("objectstore.bucket_overrides: allowlist set and every minio override listed is ok") {
        withBucketAllowlist(Some("team-a,team-b")) {
            val p = new FakeProbes(overrides = List(("orders", "minio", "team-a"), ("events", "minio", "team-b"), ("customer", "s3", "not-listed")))
            val r = new ObjectStoreBucketOverrideCheck(p).run()
            assert(r.status == "ok", r.detail)
        }
    }

    test("objectstore.bucket_overrides: allowlist set and a stored minio override outside it is an error naming the pipeline and bucket") {
        withBucketAllowlist(Some("team-a")) {
            val p = new FakeProbes(overrides = List(("orders", "minio", "team-a"), ("rogue", "minio", "other-env-data")))
            val r = new ObjectStoreBucketOverrideCheck(p).run()
            assert(r.status == "error", r.detail)
            assert(r.detail.contains("rogue") && r.detail.contains("other-env-data"), r.detail)
            assert(!r.detail.contains("orders"), "compliant pipelines must not be flagged: " + r.detail)
            assert(r.remediation.contains(allowlistVariable), r.remediation)
        }
    }

    // 4. ai.embedding_model
    test("ai.embedding_model: TEI serving the configured model is ok; a different model is an error") {
        val p = new FakeProbes()
        assert(new AiEmbeddingModelCheck(p, slots).run().status == "ok")
        p.http = p.http + ("http://tei:80/info" -> (200, "{\"model_id\":\"BAAI/bge-large-en\"}"))
        val r = new AiEmbeddingModelCheck(p, slots).run()
        assert(r.status == "error")
        assert(r.detail.contains("serving `BAAI/bge-large-en`"))
        assert(r.detail.contains("`bge-m3`"))
    }

    test("ai.embedding_model: Ollama without the model pulled is an error with the pull command") {
        val p = new FakeProbes()
        p.secrets = p.secrets + ("oss/embedding" -> Map("provider" -> "ollama", "endpoint" -> "http://ollama:11434/v1/embeddings", "model" -> "bge-m3"))
        p.http = Map("http://ollama:11434/api/tags" -> (200, "{\"models\":[{\"name\":\"llama3:latest\"}]}"))
        val r = new AiEmbeddingModelCheck(p, slots).run()
        assert(r.status == "error")
        assert(r.remediation == "docker compose exec ollama ollama pull bge-m3")
        p.http = Map("http://ollama:11434/api/tags" -> (200, "{\"models\":[{\"name\":\"bge-m3:latest\"}]}"))
        assert(new AiEmbeddingModelCheck(p, slots).run().status == "ok")
    }

    test("ai.embedding_model: unreachable service is an error on demand but a skip at startup") {
        val p = new FakeProbes(http = Map.empty)
        assert(new AiEmbeddingModelCheck(p, slots).run().status == "error")
        assert(new AiEmbeddingModelCheck(p, slots, startup = true).run().status == "skip")
    }

    // 5. disk.usage
    test("disk.usage: 84% ok, 85% warn, 95% error") {
        def at(pct: Long) = new DiskUsageCheck(new FakeProbes(disk = Map("/srv" -> (100L, 100L - pct))), Seq("/srv")).run()
        assert(at(84).status == "ok")
        assert(at(85).status == "warn")
        assert(at(95).status == "error")
        assert(at(85).detail.contains("85% used"))
    }

    // version.skew
    test("version.skew: same major.minor ok, patch drift ok, minor drift warn, unknown warn") {
        assert(new VersionSkewCheck("1.28.2", Map.empty).run().status == "ok")
        assert(new VersionSkewCheck("1.28.2", Map("cli" -> "1.28.1")).run().status == "ok")
        val r = new VersionSkewCheck("1.28.2", Map("cli" -> "1.28.2", "mcp" -> "1.27.0")).run()
        assert(r.status == "warn")
        assert(r.detail.contains("mcp differ"))
        val u = new VersionSkewCheck("1.28.2", Map("ui" -> "unknown")).run()
        assert(u.status == "warn")
        assert(u.detail.contains("ui version unknown"))
    }

    // ai.model_reachable (opt-in)
    test("ai.model_reachable: 404 is a model error, 401 a key error, timeout a warn, 200 ok") {
        val cfg = AIConfig("anthropic", "https://api.anthropic.com/v1/messages", "claude-fable-5-1", "k")
        val p = new FakeProbes(chat = Seq(("ai-primary", cfg)), chatResponses = Map("claude-fable-5-1" -> (404, "model not found")))
        val notFound = new AiModelReachableCheck(p, slots).run()
        assert(notFound.status == "error")
        assert(notFound.detail.contains("model not found"))
        p.chatResponses = Map("claude-fable-5-1" -> (401, "invalid x-api-key"))
        assert(new AiModelReachableCheck(p, slots).run().detail.contains("rejected the API key"))
        p.chatResponses = Map.empty
        val timeout = new AiModelReachableCheck(p, slots).run()
        assert(timeout.status == "warn")
        assert(timeout.detail.contains("no response within 10s"))
        p.chatResponses = Map("claude-fable-5-1" -> (200, "{}"))
        assert(new AiModelReachableCheck(p, slots).run().status == "ok")
    }

    // 6. runStartup with a throwing check
    test("runOne: a check that throws becomes an error row, not a failed report") {
        val boom = new Check {
            val id = "test.boom"
            val startupSafe = true
            def run(): CheckResult = throw new IllegalStateException("kaboom")
        }
        val r = DoctorService.runOne(boom)
        assert(r.status == "error")
        assert(r.detail.contains("kaboom"))
    }

    test("runStartup: never runs the opt-in AI probe and survives a probe that throws") {
        val p = new FakeProbes(chat = Seq(("ai-primary", AIConfig("anthropic", "e", "m", "k")))) {
            override def diskUsage(path: String): Option[(Long, Long)] = throw new RuntimeException("disk exploded")
        }
        val results = DoctorService.runStartup(p, slots, "1.28.2")
        assert(results.nonEmpty)
        assert(!results.exists(_.id == "ai.model_reachable"))
        assert(results.find(_.id == "disk.usage").exists(_.status == "error"))
    }

    // 7. report shape
    test("run: full mode includes every non-opt-in check, quick mode only the startup subset, ?probes=ai adds the AI probe") {
        val p = new FakeProbes()
        val full = DoctorService.run("full", Set.empty, Map("cli" -> "1.28.2"), p, slots, "1.28.2")
        assert(full.checks.map(_.id).contains("objectstore.bucket_overrides"), "ObjectStoreBucketOverrideCheck must be registered in checks(...)")
        assert(full.checks.map(_.id).filterNot(_ == "objectstore.bucket_overrides") == Seq(
            "vault.token_ttl",
            "vault.ai_slots",
            "jdbc.mssql_driver",
            "ai.embedding_model",
            "disk.usage",
            "version.skew",
            "env.seen"
        ))
        val withAi = DoctorService.run("full", Set("ai"), Map.empty, p, slots, "1.28.2")
        assert(withAi.checks.map(_.id).contains("ai.model_reachable"))
        val quick = DoctorService.run("quick", Set.empty, Map.empty, p, slots, "1.28.2")
        assert(quick.mode == "quick")
        assert(quick.checks.forall(_.id != "ai.model_reachable"))
        val json = full.toJson
        assert(json.contains("\"doctorVersion\":1"))
        assert(json.contains("\"surface\":{\"server\":\"1.28.2\",\"cli\":\"1.28.2\"}"))
        assert(json.contains("\"summary\":{\"ok\":"))
        assert(!json.contains("sk-ant"), "secret values must never appear in the report")
    }
}
