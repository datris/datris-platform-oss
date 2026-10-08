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

    private val GB: Long = 1024L * 1024L * 1024L
    private val MB: Long = 1024L * 1024L

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
        var overrides: List[(String, String, String)] = Nil,
        // Phase 5 (plans/stories/streaming-pipeline-phase5.md): the staging area.
        var staging: StagingAreaState = StagingAreaState("/tmp/datris-staging", exists = true, writable = true, usableBytes = 10L * GB),
        var orphans: (List[String], Long) = (Nil, 0L),
        // Field protection 8: (tap, secret, stored _type) for taps with a secret.
        var tapRefs: List[(String, String, Option[String])] = Nil,
        // Field protection 9: any source field in any pipeline carries `protect`.
        var protects: Boolean = false,
        // Governance controls (plans/stories/governance-controls-production-preset.md):
        // the four flags keyed by their .env variable names.
        var governance: Map[String, Boolean] = Map(
            "USE_USER_AUTH" -> true,
            "USE_API_KEYS" -> true,
            "USE_AUDIT_LOG" -> true,
            "USE_AGENT_POLICY" -> true
        ),
        // CodeGen script isolation (plans/stories/codegen-script-isolation.md):
        // USE_CODEGEN_RUNNER, and CodeGenRunner.health() when it is on.
        var codegenEnabled: Boolean = true,
        var codegenHealth: Either[String, Unit] = Right(())
    ) extends Probes {
        override def codegenRunnerEnabled(): Boolean = codegenEnabled
        override def codegenRunnerHealth(): Either[String, Unit] = codegenHealth
        override def anyPipelineProtects(): Boolean = protects
        override def governanceControls(): Map[String, Boolean] = governance
        def tapSecretRefs(): List[(String, String, Option[String])] = tapRefs
        def stagingArea(): StagingAreaState = staging
        def stagingOrphans(): (List[String], Long) = orphans
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

    // 5b. staging.area / staging.orphans (plans/stories/streaming-pipeline-phase5.md, Steps 7-8)
    //
    // Seams pinned:
    //   case class StagingAreaState(root: String, exists: Boolean, writable: Boolean, usableBytes: Long)   // in object DoctorService
    //   Probes.stagingArea(): StagingAreaState        — root exists? write+delete probe ok? usable bytes on its file store
    //   Probes.stagingOrphans(): (List[String], Long) — run-dir names older than StagingArea.SweepAge (not _multipart/_unscoped), total bytes
    //   class StagingAreaCheck(probes, budgetMB: Int) extends Check   id "staging.area",    startupSafe = true
    //       error: root missing / unwritable / < 1 GB free; warn: free < budgetMB (0 = unlimited never warns); else ok
    //       remediation names DATRIS_TEMP_DIR, the datris-staging volume and PIPELINE_MAX_PAYLOAD_MB
    //   class StagingOrphansCheck(probes) extends Check                id "staging.orphans", startupSafe = true
    //       ok at 0 dirs; warn when any; error when bytes >= 1 GB; detail carries the count; remediation = restart (boot sweep) or remove
    //   StagingArea.MultipartDir = "_multipart"; ensureRoot() creates it; sweepOlderThan skips it; orphanScan(maxAge): (List[Path], Long)

    test("staging.area: ids and startupSafe are pinned") {
        val p = new FakeProbes()
        assert(new StagingAreaCheck(p, 4096).id == "staging.area")
        assert(new StagingAreaCheck(p, 4096).startupSafe)
        assert(new StagingOrphansCheck(p).id == "staging.orphans")
        assert(new StagingOrphansCheck(p).startupSafe)
    }

    test("staging.area: writable with room for one run is ok and reports the free space") {
        val r = new StagingAreaCheck(new FakeProbes(), 4096).run()
        assert(r.status == "ok", r.detail)
        assert(r.detail.contains("/tmp/datris-staging"))
        assert(r.detail.contains("10.0 GB"), "free space is reported: " + r.detail)
    }

    test("staging.area: free space under the resolved payload budget is a warn naming both") {
        val p = new FakeProbes(staging = StagingAreaState("/tmp/datris-staging", exists = true, writable = true, usableBytes = 3L * GB))
        val r = new StagingAreaCheck(p, 4096).run()
        assert(r.status == "warn", r.detail)
        assert(r.detail.contains("4096"), "the budget the run may need: " + r.detail)
        assert(r.remediation.contains("PIPELINE_MAX_PAYLOAD_MB"), r.remediation)
        assert(r.remediation.contains("DATRIS_TEMP_DIR"), r.remediation)
        assert(r.remediation.contains("datris-staging"), r.remediation)
        // An unlimited budget (0) cannot be "under" the free space.
        assert(new StagingAreaCheck(p, 0).run().status == "ok")
        // Exactly the budget is not under it.
        p.staging = p.staging.copy(usableBytes = 4096L * MB)
        assert(new StagingAreaCheck(p, 4096).run().status == "ok")
    }

    test("staging.area: less than 1 GB free is an error even when the budget is small") {
        val p = new FakeProbes(staging = StagingAreaState("/tmp/datris-staging", exists = true, writable = true, usableBytes = 900L * MB))
        val r = new StagingAreaCheck(p, 100).run()
        assert(r.status == "error", r.detail)
        assert(r.remediation.contains("DATRIS_TEMP_DIR"), r.remediation)
    }

    test("staging.area: a missing root or a failed write probe is an error whose remediation names DATRIS_TEMP_DIR and the volume") {
        val missing = new FakeProbes(staging = StagingAreaState("/srv/staging", exists = false, writable = false, usableBytes = 0L))
        val m = new StagingAreaCheck(missing, 4096).run()
        assert(m.status == "error", m.detail)
        assert(m.detail.contains("/srv/staging"), m.detail)
        assert(m.remediation.contains("DATRIS_TEMP_DIR"), m.remediation)
        assert(m.remediation.contains("datris-staging"), m.remediation)

        val readOnly = new FakeProbes(staging = StagingAreaState("/srv/staging", exists = true, writable = false, usableBytes = 50L * GB))
        val u = new StagingAreaCheck(readOnly, 4096).run()
        assert(u.status == "error", "unwritable with plenty of space is still an error: " + u.detail)
        assert(u.remediation.contains("DATRIS_TEMP_DIR"), u.remediation)
    }

    test("staging.orphans: ok at 0, warn at 2 dirs with the count, error at >= 1 GB") {
        assert(new StagingOrphansCheck(new FakeProbes()).run().status == "ok")

        val two = new FakeProbes(orphans = (List("a1b2c3d4-run", "e5f6a7b8-run"), 12L * MB))
        val w = new StagingOrphansCheck(two).run()
        assert(w.status == "warn", w.detail)
        assert(w.detail.contains("2"), "count in detail: " + w.detail)
        assert(w.detail.contains("a1b2c3d4-run"), "the directories are named so an operator can find them: " + w.detail)
        assert(w.remediation.nonEmpty)
        assert(w.remediation.toLowerCase.contains("restart") || w.remediation.toLowerCase.contains("remove"), w.remediation)

        val big = new FakeProbes(orphans = (List("a1b2c3d4-run"), 1L * GB))
        assert(new StagingOrphansCheck(big).run().status == "error")
        val justUnder = new FakeProbes(orphans = (List("a1b2c3d4-run"), 1L * GB - 1L))
        assert(new StagingOrphansCheck(justUnder).run().status == "warn")
    }

    /** A per-thread environment whose staging root is a private temp dir, for the live StagingArea seams. */
    private def stagingEnv(root: java.nio.file.Path): ai.datris.model.DatrisEnvironment = ai.datris.model.DatrisEnvironment(
        initialized = true,
        environment = "test",
        fileNotifierQueue = null,
        ttlFileNotifierQueueMessages = 0,
        pipelineTopic = null,
        pipelineTableName = null,
        archivedMetadataTableName = null,
        pipelineStatusTableName = null,
        fileNotifierMessageTableName = null,
        dataPullTableName = null,
        useApiKeys = false,
        apiKeysSecretName = null,
        postgresSecretName = null,
        mongoDbSecretName = null,
        kafkaProducerSecretName = null,
        kafkaConsumerConfig = null,
        mongoDbConfig = null,
        minIOConfig = null,
        activeMQConfig = null,
        aiConfig = null,
        aiEnabled = false,
        embeddingSecretName = null,
        qdrantSecretName = null,
        weaviateSecretName = null,
        milvusSecretName = null,
        chromaSecretName = null,
        pgvectorSecretName = null,
        multiTenant = false,
        tempDir = root.toString
    )

    private def touchOld(p: java.nio.file.Path, ageHours: Long): Unit = {
        java.nio.file.Files.createDirectories(p.getParent)
        java.nio.file.Files.write(p, Array.fill[Byte](1024)(1))
        val old = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - ageHours * 3600L * 1000L)
        java.nio.file.Files.setLastModifiedTime(p, old)
        java.nio.file.Files.setLastModifiedTime(p.getParent, old)
    }

    test("staging.orphans: orphanScan finds run dirs older than the sweep age with their bytes and skips _multipart and _unscoped") {
        import java.nio.file.Files
        val root = Files.createTempDirectory("doctor-staging-orphans")
        ai.datris.model.TenantContext.set(stagingEnv(root))
        try {
            assert(StagingArea.MultipartDir == "_multipart")
            StagingArea.ensureRoot()
            assert(Files.isDirectory(root.resolve("_multipart")), "ensureRoot creates the multipart spool dir at boot")

            touchOld(root.resolve("_multipart").resolve("upload_123.tmp"), 48)
            touchOld(root.resolve("_unscoped").resolve("data-1.csv"), 48)
            touchOld(root.resolve("old-run-1").resolve("notifier-1.csv"), 48)
            touchOld(root.resolve("old-run-2").resolve("notifier-2.csv"), 30)
            touchOld(root.resolve("fresh-run").resolve("notifier-3.csv"), 1)

            val (dirs, bytes) = StagingArea.orphanScan(StagingArea.SweepAge)
            assert(dirs.map(_.getFileName.toString).sorted == List("old-run-1", "old-run-2"), s"got $dirs")
            assert(bytes == 2048L, s"total bytes of the orphaned run dirs, got $bytes")

            // The boot sweep still reclaims the old run dirs but never touches the multipart spool.
            StagingArea.sweepOlderThan(StagingArea.SweepAge)
            assert(Files.exists(root.resolve("_multipart").resolve("upload_123.tmp")), "the sweep skips _multipart")
            assert(!Files.exists(root.resolve("old-run-1")), "old run dirs are swept")
            assert(Files.exists(root.resolve("fresh-run").resolve("notifier-3.csv")))
            val (after, afterBytes) = StagingArea.orphanScan(StagingArea.SweepAge)
            assert(after.isEmpty && afterBytes == 0L)
        } finally ai.datris.model.TenantContext.clear()
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
        // Phase 5: both staging checks are registered (position not pinned) and
        // run at boot; the pre-existing order is unchanged around them.
        assert(full.checks.map(_.id).contains("staging.area"), full.checks.map(_.id).toString)
        assert(full.checks.map(_.id).contains("staging.orphans"), full.checks.map(_.id).toString)
        val quickIds = DoctorService.run("quick", Set.empty, Map.empty, p, slots, "1.28.2").checks.map(_.id)
        assert(quickIds.contains("staging.area") && quickIds.contains("staging.orphans"), "startup-safe: " + quickIds)
        // codegen-script-isolation: registered and startup-safe; position not pinned.
        assert(full.checks.map(_.id).contains("codegen.isolation"), full.checks.map(_.id).toString)
        assert(quickIds.contains("codegen.isolation"), "startup-safe: " + quickIds)
        assert(full.checks.map(_.id).filterNot(Set("staging.area", "staging.orphans", "tap.secret_scope", "ai.sample_values", "codegen.isolation")) == Seq(
            "vault.token_ttl",
            "vault.ai_slots",
            "jdbc.mssql_driver",
            "objectstore.bucket_overrides",
            "ai.embedding_model",
            "disk.usage",
            "governance.controls",
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

    // env.seen — plans/stories/tap-run-timeout.md: both tap timeout knobs are
    // .env-settable, so doctor has to show whether they reached the container.
    test("env.seen: both tap timeout variables are probed and reported as seen when set") {
        val probes = new FakeProbes() {
            override def envSeen(names: Seq[String]): Map[String, Boolean] =
                names.map(n => n -> (n == "DATRIS_ENV" || n.startsWith("TAP_"))).toMap
        }
        val check = new EnvSeenCheck(probes)
        assert(check.names.contains("TAP_SCRIPT_TIMEOUT_SECONDS"), "doctor must probe the test ceiling variable")
        assert(check.names.contains("TAP_RUN_TIMEOUT_SECONDS"), "doctor must probe the run ceiling variable")
        val r = check.run()
        assert(r.status == "ok")
        assert(r.detail.contains("TAP_SCRIPT_TIMEOUT_SECONDS"), r.detail)
        assert(r.detail.contains("TAP_RUN_TIMEOUT_SECONDS"), r.detail)
        assert(!r.detail.contains("unset: TAP_"), "both were set: " + r.detail)
    }

    // tap.secret_scope — plans/stories/field-protection-8-tap-secret-scope.md.
    // Pinned: check id "tap.secret_scope", startupSafe = true, registered in
    // DoctorService.checks (position not pinned; found by id so the class name
    // is free), reading Probes.tapSecretRefs(): List[(tap, secret, Option[_type])]
    // and SecretNames.tapScopeEnforced (system property `datris.tapSecretScope`,
    // set/cleared here with try/finally).

    private def withTapScope[A](value: Option[String])(body: => A): A = {
        val key = "datris.tapSecretScope"
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

    private def tapScopeCheck(p: FakeProbes): Check = {
        val all = DoctorService.checks(p, slots, "1.28.2", Map.empty)
        all.find(_.id == "tap.secret_scope").getOrElse(fail("no tap.secret_scope check in " + all.map(_.id)))
    }

    private val offendingRefs = List(
        ("weather", "weather-api", Some("tap")),
        ("leaky", "ai-primary", Some("ai-provider")),
        ("legacy", "databricks", None)
    )

    test("tap-secret-scope is ok with no offenders") {
        withTapScope(Some("tap")) {
            val c = tapScopeCheck(new FakeProbes())
            assert(c.startupSafe)
            assert(c.run().status == "ok")
            val onlyTap = new FakeProbes(tapRefs = List(("weather", "weather-api", Some("tap"))))
            assert(tapScopeCheck(onlyTap).run().status == "ok")
            val quickIds = DoctorService.run("quick", Set.empty, Map.empty, new FakeProbes(), slots, "1.28.2").checks.map(_.id)
            assert(quickIds.contains("tap.secret_scope"), "startup-safe: " + quickIds)
        }
    }

    test("tap-secret-scope is an error listing offenders when enforced") {
        withTapScope(Some("tap")) {
            val r = tapScopeCheck(new FakeProbes(tapRefs = offendingRefs)).run()
            assert(r.status == "error", r.detail)
            assert(r.detail.contains("leaky") && r.detail.contains("ai-primary"), r.detail)
            assert(r.detail.contains("legacy") && r.detail.contains("databricks"), r.detail)
            assert(!r.detail.contains("weather"), "a tap on a tap secret is not an offender: " + r.detail)
            assert(r.remediation.contains("tap secret"), r.remediation)
            assert(r.remediation.contains("DATRIS_TAP_SECRET_SCOPE=any"), r.remediation)
        }
    }

    test("tap-secret-scope is a warning when the opt-out is on") {
        withTapScope(Some("any")) {
            val r = tapScopeCheck(new FakeProbes(tapRefs = offendingRefs)).run()
            assert(r.status == "warn", r.detail)
            assert(r.detail.contains("leaky") && r.detail.contains("legacy"), r.detail)
            assert((r.detail + " " + r.remediation).contains("DATRIS_TAP_SECRET_SCOPE=any"), r.detail + " | " + r.remediation)
            assert(tapScopeCheck(new FakeProbes()).run().status == "ok", "no offenders with the opt-out is still ok")
        }
    }

    // ---- review follow-ups: "could not check" is never reported as ok

    private def withScan(refs: List[(String, String, Option[String])], unreadable: List[String]): FakeProbes =
        new FakeProbes(tapRefs = refs) {
            override def tapSecretScan(): TapSecretScopeScan.Result = TapSecretScopeScan.Result(refs, unreadable)
        }

    test("tap.secret_scope warns when secrets could not be read") {
        withTapScope(Some("tap")) {
            val r = tapScopeCheck(withScan(List(("weather", "weather-api", Some("tap"))), List("vault-down"))).run()
            assert(r.status == "warn", r.detail)
            assert(r.detail.contains("could not read 1 secret(s): vault-down"), r.detail)
            assert(r.remediation.contains("Vault"), r.remediation)
        }
    }

    test("tap.secret_scope: confirmed offenders stay an error when some reads also failed") {
        withTapScope(Some("tap")) {
            val r = tapScopeCheck(withScan(offendingRefs, List("vault-down"))).run()
            assert(r.status == "error", r.detail)
            assert(r.detail.contains("leaky") && r.detail.contains("vault-down"), r.detail)
        }
    }

    test("tap.secret_scope is an error row when the taps cannot be listed") {
        val p = new FakeProbes() {
            override def tapSecretScan(): TapSecretScopeScan.Result = throw new RuntimeException("mongo down")
        }
        val r = DoctorService.runOne(tapScopeCheck(p))
        assert(r.id == "tap.secret_scope" && r.status == "error", r.toString)
        assert(r.detail.contains("mongo down"), r.detail)
    }

    // ai.sample_values — plans/stories/field-protection-9-ai-values-switch.md.
    // Pinned: check id "ai.sample_values", startupSafe = true, registered in
    // DoctorService.checks (position not pinned; found by id), reading
    // AiSampleValues.enabled (system property `datris.aiSampleValues`, set and
    // restored here) and Probes.anyPipelineProtects(): Boolean.

    private def withSampleValues[A](value: Option[String])(body: => A): A = {
        val key = "datris.aiSampleValues"
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

    private def sampleValuesCheck(p: FakeProbes): Check = {
        val all = DoctorService.checks(p, slots, "1.28.2", Map.empty)
        all.find(_.id == "ai.sample_values").getOrElse(fail("no ai.sample_values check in " + all.map(_.id)))
    }

    test("ai.sample_values reports the effective value") {
        withSampleValues(Some("true")) {
            val c = sampleValuesCheck(new FakeProbes())
            assert(c.startupSafe)
            val on = c.run()
            assert(on.status == "ok", on.detail)
            assert(on.detail.contains("sent to the model") && on.detail.contains("(default)"), on.detail)
            val quickIds = DoctorService.run("quick", Set.empty, Map.empty, new FakeProbes(), slots, "1.28.2").checks.map(_.id)
            assert(quickIds.contains("ai.sample_values"), "startup-safe: " + quickIds)
        }
        withSampleValues(Some("maybe")) {
            val r = sampleValuesCheck(new FakeProbes()).run()
            assert(r.status == "ok" && r.detail.contains("(default)"), "an unknown value is on: " + r.detail)
        }
        withSampleValues(Some("false")) {
            val off = sampleValuesCheck(new FakeProbes()).run()
            assert(off.status == "ok", off.detail)
            assert(off.detail.contains("withheld"), off.detail)
            assert(!off.detail.contains("(default)"), off.detail)
        }
        if (sys.env.get("DATRIS_AI_SAMPLE_VALUES").isEmpty) withSampleValues(None) {
            val r = sampleValuesCheck(new FakeProbes()).run()
            assert(r.status == "ok" && r.detail.contains("(default)"), "unset is the default: " + r.detail)
        }
    }

    test("warns when fields are protected and values are still sampled") {
        withSampleValues(Some("true")) {
            val r = sampleValuesCheck(new FakeProbes(protects = true)).run()
            assert(r.status == "warn", r.detail)
            assert(r.detail.contains("protects fields"), r.detail)
            assert((r.detail + " " + r.remediation).contains("DATRIS_AI_SAMPLE_VALUES=false"), r.detail + " | " + r.remediation)
        }
        withSampleValues(Some("false")) {
            val r = sampleValuesCheck(new FakeProbes(protects = true)).run()
            assert(r.status == "ok" && r.detail.contains("withheld"), "protected and withheld is fine: " + r.detail)
        }
        withSampleValues(Some("true")) {
            assert(sampleValuesCheck(new FakeProbes(protects = false)).run().status == "ok", "nothing protected: no warning")
        }
    }

    test("ai.sample_values warns 'could not read pipeline configs' when the probe fails") {
        val failing = new FakeProbes() {
            override def anyPipelineProtects(): Boolean = throw new RuntimeException("mongo down")
        }
        withSampleValues(Some("true")) {
            val r = sampleValuesCheck(failing).run()
            assert(r.status == "warn", r.detail)
            assert(r.detail.contains("could not read pipeline configs") && r.detail.contains("mongo down"), r.detail)
        }
        withSampleValues(Some("false")) {
            val r = sampleValuesCheck(failing).run()
            assert(r.status == "ok" && r.detail.contains("withheld"), "no read needed when values are withheld: " + r.detail)
        }
    }

    test("ai.sample_values (off) warns naming up to five pipelines whose stored field names are not column names") {
        val odd = new FakeProbes() {
            override def pipelinesWithNonIdentifierFields(): List[String] = (1 to 7).map("p" + _).toList
        }
        withSampleValues(Some("false")) {
            val r = sampleValuesCheck(odd).run()
            assert(r.status == "warn", r.detail)
            assert(r.detail.contains("withheld") && r.detail.contains("p1, p2, p3, p4, p5 and 2 more") && !r.detail.contains("p6"), r.detail)
            val failing = new FakeProbes() { override def pipelinesWithNonIdentifierFields(): List[String] = throw new RuntimeException("mongo down") }
            val f = sampleValuesCheck(failing).run()
            assert(f.status == "warn" && f.detail.contains("could not read pipeline configs"), f.detail)
        }
        withSampleValues(Some("true")) {
            assert(sampleValuesCheck(odd).run().status == "ok", "only checked when values are withheld")
        }
    }

    // governance.controls — plans/stories/governance-controls-production-preset.md.
    // Pinned: check id "governance.controls", startupSafe = false, registered in
    // DoctorService.checks after ai.sample_values (found by id here), reading
    // Probes.governanceControls(): Map[String, Boolean] keyed by the .env
    // variable names USE_USER_AUTH, USE_API_KEYS, USE_AUDIT_LOG, USE_AGENT_POLICY
    // (LiveProbes fills it from DatrisEnvironment.values). Never errors.

    private val governanceVars = Seq("USE_USER_AUTH", "USE_API_KEYS", "USE_AUDIT_LOG", "USE_AGENT_POLICY")

    private def governanceState(off: Set[String]): Map[String, Boolean] = governanceVars.map(v => v -> !off.contains(v)).toMap

    private def governanceCheck(p: FakeProbes): Check = {
        val all = DoctorService.checks(p, slots, "1.28.2", Map.empty)
        all.find(_.id == "governance.controls").getOrElse(fail("no governance.controls check in " + all.map(_.id)))
    }

    test("governance.controls is ok when user auth, API keys, audit log and agent policy are all on") {
        val r = governanceCheck(new FakeProbes(governance = governanceState(Set.empty))).run()
        assert(r.id == "governance.controls")
        assert(r.status == "ok", r.detail)
        governanceVars.foreach(v => assert(r.detail.contains(v), "ok lists every control that is on (" + v + "): " + r.detail))
        assert(r.remediation.isEmpty, r.remediation)
    }

    test("governance.controls warns and names each control that is off") {
        governanceVars.foreach { off =>
            val r = governanceCheck(new FakeProbes(governance = governanceState(Set(off)))).run()
            assert(r.status == "warn", off + " off: " + r.detail)
            assert(r.detail.contains(off), "detail names the control that is off (" + off + "): " + r.detail)
            governanceVars.filterNot(_ == off).foreach(on => assert(r.detail.contains(on), "detail also names those that are on (" + on + "): " + r.detail))
            // The detail must not claim a control that is on is off.
            val offPart = r.detail.substring(0, r.detail.indexOf("; on:"))
            governanceVars.filterNot(_ == off).foreach(on => assert(!offPart.contains(on), on + " is on but listed as off: " + r.detail))
            val summary = r.detail.substring(r.detail.indexOf(" — ") + 3)
            Map(
                "USE_USER_AUTH" -> "user login",
                "USE_API_KEYS" -> "API keys",
                "USE_AUDIT_LOG" -> "audit log",
                "USE_AGENT_POLICY" -> "agent policy"
            ).foreach { case (v, label) =>
                if (v == off) assert(summary.contains(label), "summary names the control that is off (" + label + "): " + r.detail)
                else assert(!summary.contains(label), "summary claims " + label + " is off though " + v + " is on: " + r.detail)
            }
        }
        val twoOff = governanceCheck(new FakeProbes(governance = governanceState(Set("USE_USER_AUTH", "USE_API_KEYS")))).run()
        assert(twoOff.detail.contains("user login and API keys are off"), twoOff.detail)
        assert(!twoOff.detail.contains("audit log") && !twoOff.detail.contains("agent policy"), twoOff.detail)
        val allOff = governanceCheck(new FakeProbes(governance = governanceState(governanceVars.toSet))).run()
        assert(allOff.status == "warn", allOff.detail)
        governanceVars.foreach(v => assert(allOff.detail.contains(v), "default install names all four (" + v + "): " + allOff.detail))
    }

    test("governance.controls remediation lists only the variables that are off") {
        val off = Set("USE_API_KEYS", "USE_AGENT_POLICY")
        val r = governanceCheck(new FakeProbes(governance = governanceState(off))).run()
        assert(r.status == "warn", r.detail)
        off.foreach(v => assert(r.remediation.contains(v + "=true"), "the exact .env line for " + v + ": " + r.remediation))
        governanceVars.filterNot(off.contains).foreach(on =>
            assert(!r.remediation.contains(on), on + " is already on and must not be in the fix: " + r.remediation)
        )
        assert(r.remediation.contains("docker compose up -d --force-recreate datris mcp-server"), r.remediation)
        val all = governanceCheck(new FakeProbes(governance = governanceState(governanceVars.toSet))).run()
        governanceVars.foreach(v => assert(all.remediation.contains(v + "=true"), v + ": " + all.remediation))
    }

    test("governance.controls never errors and is not in the startup subset") {
        val combos = governanceVars.toSet.subsets().toList
        assert(combos.size == 16)
        combos.foreach { off =>
            val r = governanceCheck(new FakeProbes(governance = governanceState(off))).run()
            assert(r.status == "ok" || r.status == "warn", "off=" + off + " gave " + r.status + ": " + r.detail)
            assert(r.status != "error")
        }
        val c = governanceCheck(new FakeProbes())
        assert(!c.startupSafe, "governance.controls must not run at boot")
        assert(c.optInGroup.isEmpty, "it runs in every full report, not behind ?probes=")
        val offProbes = new FakeProbes(governance = governanceState(governanceVars.toSet))
        val quickIds = DoctorService.run("quick", Set.empty, Map.empty, offProbes, slots, "1.28.2").checks.map(_.id)
        assert(!quickIds.contains("governance.controls"), "not startup-safe: " + quickIds)
        val fullIds = DoctorService.run("full", Set.empty, Map.empty, offProbes, slots, "1.28.2").checks.map(_.id)
        assert(fullIds.contains("governance.controls"), fullIds.toString)
        assert(fullIds.indexOf("governance.controls") == fullIds.indexOf("ai.sample_values") + 1, "registered after ai.sample_values: " + fullIds)
        val startupIds = DoctorService.runStartup(offProbes, slots, "1.28.2").map(_.id)
        assert(!startupIds.contains("governance.controls"), "boot log has no DOCTOR governance.controls line: " + startupIds)
    }

    // codegen.isolation (plans/stories/codegen-script-isolation.md)
    //
    // Pinned seam:
    //   trait Probes {
    //       def codegenRunnerEnabled(): Boolean                 // CodeGenRunner.enabled (USE_CODEGEN_RUNNER)
    //       def codegenRunnerHealth(): Either[String, Unit]     // CodeGenRunner.health(); Left = why it failed
    //   }
    //   class CodeGenIsolationCheck(probes: Probes) extends Check   // id "codegen.isolation", startupSafe = true
    // The check must not call codegenRunnerHealth() when the runner is not enabled.

    test("codegen.isolation is ok when the runner answers") {
        val c = new CodeGenIsolationCheck(new FakeProbes(codegenEnabled = true, codegenHealth = Right(())))
        assert(c.id == "codegen.isolation")
        assert(c.startupSafe)
        val r = c.run()
        assert(r.status == "ok", r)
        assert(r.detail.contains("datris-codegen-runner"), r.detail)
        val all = DoctorService.checks(new FakeProbes(), slots, "1.0.0", Map.empty)
        assert(all.exists(_.id == "codegen.isolation"), "codegen.isolation is part of the report")
    }

    test("warns when the runner is not enabled") {
        var probed = false
        val probes = new FakeProbes(codegenEnabled = false) {
            override def codegenRunnerHealth(): Either[String, Unit] = { probed = true; Left("should not be probed") }
        }
        val r = new CodeGenIsolationCheck(probes).run()
        assert(r.status == "warn", r)
        assert(!probed, "no runner probe when USE_CODEGEN_RUNNER is off")
        assert(r.detail.toLowerCase.contains("in-process") || r.detail.contains("USE_CODEGEN_RUNNER"), r.detail)
        assert(r.remediation.toLowerCase.contains("compose"), "remediation tells the operator to refresh the compose file: " + r.remediation)
        assert(r.remediation.contains("sbt"), "remediation says it is expected under sbt: " + r.remediation)
    }

    test("errors when enabled and the runner is unreachable") {
        val r = new CodeGenIsolationCheck(
            new FakeProbes(codegenEnabled = true, codegenHealth = Left("Connection refused: http://datris-codegen-runner:8090"))
        ).run()
        assert(r.status == "error", r)
        assert(r.detail.contains("Connection refused"), "the probe's reason is shown: " + r.detail)
        assert(r.detail.contains("datris-codegen-runner") || r.remediation.contains("datris-codegen-runner"), r)
        assert(r.remediation.nonEmpty)
    }

    test("codegen.isolation: unreachable runner is a skip at startup, an error on demand") {
        val p = new FakeProbes(codegenEnabled = true, codegenHealth = Left("Connection refused: http://datris-codegen-runner:8090"))
        assert(new CodeGenIsolationCheck(p).run().status == "error")
        val boot = new CodeGenIsolationCheck(p, startup = true).run()
        assert(boot.status == "skip", boot)
        assert(boot.detail.contains("not reachable yet"), boot.detail)
        // Anything other than "not up yet" (old image, token, disk) stays an error at boot.
        val old = new FakeProbes(codegenEnabled = true, codegenHealth = Left("CodeGen runner ... does not support /execute-file (404)"))
        assert(new CodeGenIsolationCheck(old, startup = true).run().status == "error")
    }

    // audit.strict / provenance.strict (plans/stories/strict-evidence-mode.md)
    //
    // Pinned seam (does not exist on main at 3de8be3; the implementation adds it):
    //   trait Probes {
    //       def auditLogStrict(): Boolean = false          // DatrisEnvironment.values.auditLogStrict (AUDIT_LOG_STRICT)
    //       def auditAcceptingWrites(): Boolean = true     // AuditLog.acceptingWrites
    //       def provenanceStrict(): Boolean = false        // DatrisEnvironment.values.provenanceStrict (PROVENANCE_STRICT)
    //       /** Names of pipelines with provenance.stamp on and an XML source.
    //         * Throws when the pipeline configs cannot be read. */
    //       def xmlStampingPipelines(): List[String] = Nil
    //   }
    // USE_AUDIT_LOG is read from the existing governanceControls() map.
    // Check ids "audit.strict" and "provenance.strict", both startupSafe,
    // registered in DoctorService.checks (found by id here).
    // provenance.strict never errors.

    private def strictCheck(id: String, p: Probes): Check = {
        val all = DoctorService.checks(p, slots, "1.28.2", Map.empty)
        all.find(_.id == id).getOrElse(fail("no " + id + " check in " + all.map(_.id)))
    }

    test("audit.strict warns when strict is set and the audit log is off") {
        val off = new FakeProbes(governance =
            Map(
                "USE_USER_AUTH" -> true,
                "USE_API_KEYS" -> true,
                "USE_AUDIT_LOG" -> false,
                "USE_AGENT_POLICY" -> true
            )
        ) {
            override def auditLogStrict(): Boolean = true
        }
        val c = strictCheck("audit.strict", off)
        assert(c.startupSafe)
        val r = c.run()
        assert(r.status == "warn", r.detail)
        assert((r.detail + " " + r.remediation).contains("USE_AUDIT_LOG"), r.detail + " | " + r.remediation)
        assert((r.detail + " " + r.remediation).contains("AUDIT_LOG_STRICT"), r.detail + " | " + r.remediation)

        // Effective mode is ok: strict on with the log on, or strict off.
        val on = new FakeProbes() { override def auditLogStrict(): Boolean = true }
        assert(strictCheck("audit.strict", on).run().status == "ok")
        assert(strictCheck("audit.strict", new FakeProbes()).run().status == "ok")
        // Strict, on, and the queue is past the high-water mark: error.
        val full = new FakeProbes() {
            override def auditLogStrict(): Boolean = true
            override def auditAcceptingWrites(): Boolean = false
        }
        assert(strictCheck("audit.strict", full).run().status == "error")
    }

    test("provenance.strict warns and names stamping pipelines with an XML source as exempt") {
        val p = new FakeProbes() {
            override def provenanceStrict(): Boolean = true
            override def xmlStampingPipelines(): List[String] = List("xml-orders", "xml-feed")
        }
        val c = strictCheck("provenance.strict", p)
        assert(c.startupSafe)
        val r = c.run()
        assert(r.status == "warn", r.detail)
        assert(r.detail.contains("xml-orders") && r.detail.contains("xml-feed"), r.detail)
        assert(r.detail.toLowerCase.contains("exempt"), r.detail)
        val failing = new FakeProbes() {
            override def provenanceStrict(): Boolean = true
            override def xmlStampingPipelines(): List[String] = throw new RuntimeException("mongo down")
        }
        assert(strictCheck("provenance.strict", failing).run().status != "error", "provenance.strict never errors")
    }

    test("provenance.strict is ok when strict is on and no stamping pipeline has an XML source") {
        val p = new FakeProbes() { override def provenanceStrict(): Boolean = true }
        val r = strictCheck("provenance.strict", p).run()
        assert(r.status == "ok", r.detail)
        // Strict off: ok whatever the pipelines are (the XML exemption only matters under strict).
        val offWithXml = new FakeProbes() { override def xmlStampingPipelines(): List[String] = List("xml-orders") }
        assert(strictCheck("provenance.strict", offWithXml).run().status == "ok")
    }
}
