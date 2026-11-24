package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.CapabilityScopes
import ai.datris.model.{AIConfig, DatrisEnvironment, TapRunLog}
import com.google.gson.{Gson, JsonParser}
import org.slf4j.{Logger, LoggerFactory}

import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._

/** Operational self-check ("datris doctor"). One place for the deterministic
  * probes behind every incident that used to be diagnosed by hand from the
  * logs — Vault token TTL clamped, an AI slot secret missing so the server
  * crash-loops, the embedding model not pulled, a full disk, a mixed-version
  * stack. Each check owns its remediation text.
  *
  * Three surfaces run the same checks: `GET /api/v1/doctor` (UI, MCP, CLI),
  * and the startup subset logged at boot. Host-side checks (Docker volumes,
  * container env drift, compose orphans) can't run in the JVM; the CLI runs
  * those and merges them into the same report shape.
  *
  * Never mutates anything and never spends money by default: the AI
  * reachability probe is opt-in (`?probes=ai`) and never runs at boot.
  */
object DoctorService {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    val DoctorVersion = 1

    val StatusOk = "ok"
    val StatusWarn = "warn"
    val StatusError = "error"
    val StatusSkip = "skip"

    /** One check's outcome. `severity` is the level the check reports at
      * when it fires (informational for `ok`/`skip`). Values never carry
      * secrets — only key names and hashes. */
    case class CheckResult(
        id: String,
        status: String,
        severity: String,
        detail: String,
        remediation: String,
        surface: String = "server",
        ms: Long = 0L,
        /** The detail with no item, user or key named (counts only), for a
          * caller that does not see the whole installation
          * ([[Report.withoutNames]]). Null: the detail names nothing such a
          * caller may not see. Never serialized. */
        namelessDetail: String = null
    )

    case class Report(mode: String, surface: Map[String, String], checks: Seq[CheckResult]) {
        def summary: Map[String, Int] =
            Seq(StatusOk, StatusWarn, StatusError, StatusSkip).map(s => s -> checks.count(_.status == s)).toMap

        /** The report for a caller that does not see the whole installation:
          * every row that has a [[CheckResult.namelessDetail]] shows it in
          * place of its detail. Status, severity and remediation unchanged. */
        def withoutNames: Report =
            copy(checks = checks.map(c => if (c.namelessDetail == null) c else c.copy(detail = c.namelessDetail)))

        def toJson: String = {
            val root = new java.util.LinkedHashMap[String, Any]()
            root.put("doctorVersion", DoctorVersion)
            root.put("ranAt", java.time.Instant.now().toString)
            root.put("mode", mode)
            val surf = new java.util.LinkedHashMap[String, String]()
            surface.foreach { case (k, v) => surf.put(k, v) }
            root.put("surface", surf)
            val sum = new java.util.LinkedHashMap[String, Int]()
            val s = summary
            Seq(StatusOk, StatusWarn, StatusError, StatusSkip).foreach(k => sum.put(k, s(k)))
            root.put("summary", sum)
            val arr = new java.util.ArrayList[java.util.Map[String, Any]]()
            checks.foreach { c =>
                val m = new java.util.LinkedHashMap[String, Any]()
                m.put("id", c.id)
                m.put("status", c.status)
                m.put("severity", c.severity)
                m.put("detail", c.detail)
                m.put("remediation", c.remediation)
                m.put("surface", c.surface)
                m.put("ms", c.ms)
                arr.add(m)
            }
            root.put("checks", arr)
            new Gson().toJson(root)
        }
    }

    /** A single probe. `startupSafe` checks are cheap, spend nothing, and
      * run at boot; `optInGroup` checks only run when the caller names the
      * group (`?probes=ai`). */
    trait Check {
        def id: String
        def startupSafe: Boolean
        def optInGroup: Option[String] = None
        def run(): CheckResult

        /** The row this check adds to a report, or None when it does not apply
          * to this install and adds no row at all. Default: always [[run]]. */
        def runIfApplies(): Option[CheckResult] = Some(run())

        protected def ok(detail: String): CheckResult = CheckResult(id, StatusOk, StatusOk, detail, "")
        protected def warn(detail: String, remediation: String): CheckResult = CheckResult(id, StatusWarn, StatusWarn, detail, remediation)
        protected def error(detail: String, remediation: String): CheckResult = CheckResult(id, StatusError, StatusError, detail, remediation)
        protected def skip(detail: String): CheckResult = CheckResult(id, StatusSkip, StatusOk, detail, "")
    }

    /** The AI slot secret names the server was configured with. */
    case class Slots(aiPrimarySecret: String, codegenSecret: String, embeddingSecret: String)

    /** What the staging-area probe saw (plans/stories/streaming-pipeline-phase5.md,
      * Step 7): the resolved root, whether it exists, whether a write+delete
      * probe succeeded, and the usable bytes on its file store. */
    case class StagingAreaState(root: String, exists: Boolean, writable: Boolean, usableBytes: Long)

    /** Everything a check touches outside the JVM, so specs can fake it. */
    trait Probes {

        /** `auth/token/lookup-self` data fields as strings (ttl, period,
          * creation_ttl, explicit_max_ttl — seconds). None when Vault or
          * the token can't be reached. */
        def vaultLookupSelf(): Option[Map[String, String]]
        def secret(name: String): Option[Map[String, String]]
        def apiKeyResolves(provider: String, rawKey: String): Boolean
        def classPresent(className: String): Boolean

        /** (pipelineName, source.databaseAttributes.type) for database-pull pipelines. */
        def pipelineSources(): List[(String, String)]

        /** (pipelineName, provider, destinationBucketOverride) for objectStore pipelines with an override. */
        def objectStoreBucketOverrides(): List[(String, String, String)]

        /** Plain GET → (status, body). Throws on connect/read failure. */
        def httpGet(url: String, timeoutMs: Int): (Int, String)

        /** (totalBytes, usableBytes) for the file store holding `path`. */
        def diskUsage(path: String): Option[(Long, Long)]
        def envSeen(names: Seq[String]): Map[String, Boolean]

        /** The payload staging root: exists, writable, free space on its file store. */
        def stagingArea(): StagingAreaState

        /** Run directories older than the sweep age, with their total bytes (excluding `_multipart` / `_unscoped`). */
        def stagingOrphans(): (List[String], Long)

        /** Chat slots configured on this server: (label, config). */
        def chatSlots(): Seq[(String, AIConfig)]

        /** Minimal chat request → (status, bodySnippet). Throws on timeout/IO. */
        def probeChat(config: AIConfig, timeoutMs: Int): (Int, String)

        /** One-input embedding request → (status, bodySnippet). Throws on timeout/IO. */
        def probeEmbedding(provider: String, endpoint: String, model: String, apiKey: String, timeoutMs: Int): (Int, String)

        /** (tap, secret, stored `_type`) for every saved tap whose secret exists. */
        def tapSecretRefs(): List[(String, String, Option[String])]

        /** The tap secret scan: refs plus the secret names whose read failed.
          * Throws when the taps themselves cannot be listed. The default has
          * no failed reads; LiveProbes overrides it with one real scan. */
        def tapSecretScan(): TapSecretScopeScan.Result = TapSecretScopeScan.Result(tapSecretRefs(), Nil)

        /** (label, raw capability strings) of every non-revoked API key with
          * metadata ([[nonRevokedKeyCapabilities]] over `{env}/api-key-metadata`).
          * Throws when the metadata cannot be read. */
        def apiKeyCapabilities(): List[(String, Seq[String])] = Nil

        /** The tap owner access scan ([[TapOwnerScopeScan.liveScan]]): taps
          * that will read or feed less once held to their owner's access.
          * Throws when it cannot run. The default has nothing to report. */
        def tapOwnerScopeScan(): TapOwnerScopeScan.Result = TapOwnerScopeScan.Result.empty

        /** The tap script reference, secret and review scan
          * ([[TapReferenceScan.liveScan]]): taps and keys a later release
          * holds to those rules. Throws when it cannot run. The default has
          * nothing to report. */
        def tapReferenceScan(): TapReferenceScan.Result = TapReferenceScan.Result.empty

        /** True when any source field in any pipeline carries `protect`.
          * Throws when the pipeline configs cannot be read. */
        def anyPipelineProtects(): Boolean

        /** Pipelines (names only) with a stored source field name that is not
          * an identifier ([[AiSampleValues.isIdentifier]]). Stored names are
          * always sent to the model. Throws when the configs cannot be read. */
        def pipelinesWithNonIdentifierFields(): List[String] = Nil

        /** The four governance controls, keyed by their `.env` variable
          * ([[GovernanceControlsCheck.Vars]]) -> on/off. The default reports
          * the shipped state (all off); LiveProbes reads DatrisEnvironment. */
        def governanceControls(): Map[String, Boolean] = GovernanceControlsCheck.Vars.map(_ -> false).toMap

        /** AUDIT_LOG_STRICT as configured (DatrisEnvironment.auditLogStrict). */
        def auditLogStrict(): Boolean = false

        /** [[ai.datris.audit.AuditLog.acceptingWrites]]: false only under
          * strict while the audit queue is at its high-water mark. */
        def auditAcceptingWrites(): Boolean = true

        /** PROVENANCE_STRICT (DatrisEnvironment.provenanceStrict). */
        def provenanceStrict(): Boolean = false

        /** Names of pipelines with provenance.stamp on and an XML source.
          * Throws when the pipeline configs cannot be read. */
        def xmlStampingPipelines(): List[String] = Nil

        /** USE_CODEGEN_RUNNER: generated DQ / transformation scripts run in
          * the datris-codegen-runner sidecar. */
        def codegenRunnerEnabled(): Boolean

        /** [[CodeGenRunner.health]]: Left = why the runner cannot run a script
          * (unreachable, older image, token rejected, scratch not writable). */
        def codegenRunnerHealth(): Either[String, Unit]

        /** USE_TAP_RUNNER: taps run in the datris-tap-runner sidecar. */
        def tapRunnerEnabled(): Boolean

        /** GET TAP_RUNNER_URL/health as (active, maxConcurrent). Left = why the
          * counters could not be read (unreachable, or an older runner image). */
        def tapRunnerHealth(): Either[String, (Int, Int)]

        /** Tap runs in the last `hours` that waited for a runner slot, runs turned
          * away after the queue timeout, and the longest wait in ms. */
        def recentRunnerWaits(hours: Int = 24): (Int, Int, Long)

        /** The Postgres secret's separate query login
          * (plans/stories/postgres-query-role.md). None: the secret carries no
          * `queryUsername`/`queryPassword` (or cannot be read). Some(Left(why)):
          * the keys are there but the login could not be checked, or only one
          * of them is set; `why` carries no password and no URL credentials.
          * Some(Right((user, superuser))): it connected, and whether that role
          * has `rolsuper`. */
        def postgresQueryLogin(): Option[Either[String, (String, Boolean)]] = None
    }

    private val Day = 86400L
    private val SevenDays = 7 * Day

    private def humanDuration(seconds: Long): String = {
        if (seconds >= Day) (seconds / Day) + "d"
        else if (seconds >= 3600) (seconds / 3600) + "h"
        else (seconds / 60) + "m"
    }

    private def gb(bytes: Long): String = f"${bytes / 1073741824.0}%.1f GB"

    // ------------------------------------------------------------------ checks

    class VaultTokenTtlCheck(probes: Probes) extends Check {
        val id = "vault.token_ttl"
        val startupSafe = true
        def run(): CheckResult = {
            probes.vaultLookupSelf() match {
                case None => skip("token lookup unavailable (Vault unreachable or no token resolved)")
                case Some(data) =>
                    val ttl = data.get("ttl").flatMap(v => scala.util.Try(v.toDouble.toLong).toOption).getOrElse(0L)
                    val period = data.get("period").flatMap(v => scala.util.Try(v.toDouble.toLong).toOption).getOrElse(0L)
                    val detail = "ttl " + humanDuration(ttl) + ", period " + humanDuration(period)
                    val remediation =
                        "Vault token expires in " + humanDuration(ttl) + " (period " + humanDuration(period) + ", ttl clamped by max_lease_ttl). " +
                            "Check docker/vault.hcl has max_lease_ttl = \"87600h\", then `docker compose up -d --force-recreate vault` " +
                            "and restart datris to re-mint the token."
                    // A periodic token renews to its full period, so a healthy one
                    // sits near the period; one minted under a low max_lease_ttl
                    // (Vault's 768h default) sits at that ceiling instead. "Under a
                    // tenth of the period" catches any ceiling, not just 768h.
                    if (ttl <= 0 && period <= 0) ok("token has no expiry")
                    else if (ttl < SevenDays) error(detail + " — expires in " + humanDuration(ttl), remediation)
                    else if (period > 0 && ttl < period / 10) warn(detail + " — clamped", remediation)
                    else ok(detail)
            }
        }
    }

    class VaultAiSlotsCheck(probes: Probes, slots: Slots) extends Check {
        val id = "vault.ai_slots"
        val startupSafe = true
        private val keyless = Set("ollama", "bedrock", "tei")
        private val keyOptional = keyless + "azure"

        def run(): CheckResult = {
            val slotList = Seq(("ai-primary", slots.aiPrimarySecret), ("codegen", slots.codegenSecret), ("embedding", slots.embeddingSecret))
                .filter { case (_, name) => name != null && name.nonEmpty }
            val problems = scala.collection.mutable.ListBuffer[String]()
            val fixes = scala.collection.mutable.ListBuffer[String]()
            val okDetails = scala.collection.mutable.ListBuffer[String]()
            slotList.foreach { case (label, name) =>
                probes.secret(name) match {
                    case None =>
                        problems += "Vault secret `" + name + "` (" + label + ") missing" +
                            (if (label == "ai-primary") "; the server will refuse to start" else "")
                        fixes += "vault kv put secret/" + name + " provider=<anthropic|openai|azure|bedrock|grok|ollama> endpoint=<url> model=<model> apiKey=<key>"
                    case Some(s) =>
                        val provider = s.getOrElse("provider", "").trim.toLowerCase
                        val endpoint = s.getOrElse("endpoint", "").trim
                        val model = s.getOrElse("model", "").trim
                        val rawKey = s.getOrElse("apiKey", "")
                        val missing = scala.collection.mutable.ListBuffer[String]()
                        if (provider.isEmpty) missing += "provider"
                        if (endpoint.isEmpty && provider != "bedrock") missing += "endpoint"
                        if (model.isEmpty) missing += "model"
                        val keyOk = keyOptional.contains(provider) || rawKey.nonEmpty || probes.apiKeyResolves(provider, rawKey)
                        if (!keyOk) missing += "apiKey"
                        if (missing.nonEmpty) {
                            problems += "secret `" + name + "` (" + label + ") is missing " + missing.map("'" + _ + "'").mkString(", ")
                            fixes += "vault kv patch secret/" + name + " " + missing.map(f => f + "=<value>").mkString(" ")
                        } else
                            okDetails += label + " (" + provider + "/" + model + ")"
                }
            }
            if (problems.nonEmpty) error(problems.mkString("; "), fixes.mkString("; "))
            else if (slotList.isEmpty) skip("no AI slot secrets configured")
            else ok(okDetails.mkString(", "))
        }
    }

    class JdbcMssqlDriverCheck(probes: Probes) extends Check {
        val id = "jdbc.mssql_driver"
        val startupSafe = true
        def run(): CheckResult = {
            val present = probes.classPresent("com.microsoft.sqlserver.jdbc.SQLServerDriver")
            if (present) return ok("MSSQL JDBC driver on the classpath")
            val users = probes.pipelineSources().collect { case (name, t) if t != null && t.equalsIgnoreCase("mssql") => name }
            if (users.isEmpty) ok("MSSQL JDBC driver not bundled; no pipeline uses type: mssql")
            else
                error(
                    "pipeline(s) " + users.mkString(", ") + " use `type: mssql` but the MSSQL JDBC driver is not on the classpath",
                    "Add `com.microsoft.sqlserver % mssql-jdbc` to build.sbt and rebuild (see docs/ingestion/database-pull.mdx), or change the source type."
                )
        }
    }

    /** Pipelines whose `provider=minio` objectStore destination names a bucket
      * other than the environment default. Without
      * DATRIS_OBJECTSTORE_BUCKET_ALLOWLIST any such pipeline can reach any
      * bucket on the shared MinIO with the root credentials; with it set, a
      * config stored before the list existed may now be refused at run time.
      * `provider=s3` overrides are the customer's own bucket and are ignored. */
    class ObjectStoreBucketOverrideCheck(probes: Probes) extends Check {
        val id = "objectstore.bucket_overrides"
        val startupSafe = true
        def run(): CheckResult = {
            val minio = probes.objectStoreBucketOverrides().collect {
                case (name, provider, bucket) if bucket != null && (provider == null || provider.equalsIgnoreCase("minio")) => (name, bucket)
            }
            if (minio.isEmpty) return skip("no pipeline overrides the built-in MinIO bucket")
            ObjectStoreSpark.bucketAllowlist match {
                case None =>
                    warn(
                        "pipeline(s) " + minio.map { case (n, b) => n + " (" + b + ")" }.mkString(", ") +
                            " override the built-in MinIO bucket and no allowlist is set",
                        "Set " + ObjectStoreSpark.BucketAllowlistVariable + " (comma-separated buckets) in .env to restrict which MinIO buckets pipelines may target."
                    )
                case Some(allowed) =>
                    val outside = minio.filterNot { case (_, b) => ObjectStoreSpark.bucketAllowed(b) }
                    if (outside.isEmpty) ok(minio.size + " MinIO bucket override(s), all within " + ObjectStoreSpark.BucketAllowlistVariable)
                    else
                        error(
                            "pipeline(s) " + outside.map { case (n, b) => n + " (" + b + ")" }.mkString(", ") +
                                " target a MinIO bucket outside " + ObjectStoreSpark.BucketAllowlistVariable + " (" + allowed.toSeq.sorted.mkString(", ") +
                                ") and will be refused at run time",
                            "Add the bucket to " + ObjectStoreSpark.BucketAllowlistVariable + " or change the pipeline's destinationBucketOverride."
                        )
            }
        }
    }

    /** Embedding model actually loaded where the embedding slot points.
      * `startup` softens "not reachable" to a skip: at boot the bundled
      * embedding service is often still downloading its model, and that
      * is not a misconfiguration. */
    class AiEmbeddingModelCheck(probes: Probes, slots: Slots, startup: Boolean = false) extends Check {
        val id = "ai.embedding_model"
        val startupSafe = true

        private def baseUrl(endpoint: String): String = {
            val u = new java.net.URI(endpoint)
            val port = if (u.getPort > 0) ":" + u.getPort else ""
            u.getScheme + "://" + u.getHost + port
        }

        private def unreachable(what: String, base: String, why: String): CheckResult =
            if (startup) skip(what + " at " + base + " not reachable yet (" + why + ")")
            else
                error(
                    what + " at " + base + " unreachable: " + why,
                    "Check `docker compose ps` — the embedding service the slot points at is not up. Re-run once it is healthy."
                )

        def run(): CheckResult = {
            val secret = probes.secret(slots.embeddingSecret).getOrElse(return skip("embedding secret missing (see vault.ai_slots)"))
            val provider = secret.getOrElse("provider", "").trim.toLowerCase
            val endpoint = secret.getOrElse("endpoint", "").trim
            val model = secret.getOrElse("model", "").trim
            if (endpoint.isEmpty || model.isEmpty) return skip("embedding secret has no endpoint/model (see vault.ai_slots)")
            val host = scala.util.Try(new java.net.URI(endpoint).getHost).toOption.getOrElse("").toLowerCase
            val kind =
                if (provider == "ollama" || (provider.isEmpty && (host.contains("ollama") || endpoint.contains(":11434")))) "ollama"
                else if (provider == "tei" || (provider.isEmpty && host.contains("tei"))) "tei"
                else provider
            kind match {
                case "ollama" =>
                    val base = baseUrl(endpoint)
                    val resp =
                        try probes.httpGet(base + "/api/tags", 5000)
                        catch { case e: Exception => return unreachable("Ollama", base, e.getClass.getSimpleName + ": " + e.getMessage) }
                    if (resp._1 != 200) return unreachable("Ollama", base, "HTTP " + resp._1)
                    val names =
                        try {
                            val root = JsonParser.parseString(resp._2).getAsJsonObject
                            val arr = Option(root.getAsJsonArray("models")).map(_.asScala.toList).getOrElse(Nil)
                            arr.map(_.getAsJsonObject.get("name").getAsString)
                        } catch { case _: Exception => Nil }
                    val wanted = model.toLowerCase
                    val found = names.exists(n => n.toLowerCase == wanted || n.toLowerCase == wanted + ":latest" || n.toLowerCase.startsWith(wanted + ":"))
                    if (found) ok("`" + model + "` loaded on Ollama at " + base)
                    else
                        error(
                            "embedding slot expects `" + model + "` on Ollama at " + base + " but it is not pulled" +
                                (if (names.nonEmpty) " (present: " + names.mkString(", ") + ")" else ""),
                            "docker compose exec ollama ollama pull " + model
                        )
                case "tei" =>
                    val base = baseUrl(endpoint)
                    val health =
                        try probes.httpGet(base + "/health", 5000)
                        catch { case e: Exception => return unreachable("TEI", base, e.getClass.getSimpleName + ": " + e.getMessage) }
                    if (health._1 != 200) return unreachable("TEI", base, "health HTTP " + health._1 + " (still loading the model?)")
                    val info =
                        try probes.httpGet(base + "/info", 5000)
                        catch { case e: Exception => return unreachable("TEI", base, e.getClass.getSimpleName + ": " + e.getMessage) }
                    val served =
                        try JsonParser.parseString(info._2).getAsJsonObject.get("model_id").getAsString
                        catch { case _: Exception => "" }
                    val m = model.toLowerCase
                    val s = served.toLowerCase
                    if (s.nonEmpty && (s == m || s.endsWith("/" + m) || m.endsWith("/" + s))) ok("TEI at " + base + " serving " + served)
                    else
                        error(
                            "TEI at " + base + " is serving `" + served + "`, embedding secret says `" + model + "`",
                            "Set the embedding slot's model to the served id (Configuration → AI Providers) or change TEI's --model-id in docker-compose.yml and recreate it."
                        )
                case "openai" | "azure" =>
                    ok("provider " + kind + " — endpoint verified by ai.model_reachable (opt-in)")
                case other =>
                    skip("provider '" + other + "' has no model-presence probe")
            }
        }
    }

    class DiskUsageCheck(probes: Probes, paths: Seq[String]) extends Check {
        val id = "disk.usage"
        val startupSafe = true
        def run(): CheckResult = {
            val rows = paths.distinct.flatMap { p =>
                probes.diskUsage(p).map { case (total, usable) =>
                    val pct = if (total <= 0) 0.0 else (total - usable) * 100.0 / total
                    (p, pct, usable)
                }
            }
            if (rows.isEmpty) return skip("no file store readable")
            val detail = rows.map { case (p, pct, usable) => p + " " + f"$pct%.0f" + "% used (" + gb(usable) + " free)" }.mkString("; ")
            val worst = rows.map(_._2).max
            val remediation = "Free space on the disk holding Docker's data root: `docker image prune`, inspect the pip-cache and object-store volumes, " +
                "and check `docker system df`. `docker compose pull` on a nearly full disk half-writes image layers."
            if (worst >= 95.0) error(detail, remediation)
            else if (worst >= 85.0) warn(detail, remediation)
            else ok(detail)
        }
    }

    private val OneGB: Long = 1024L * 1024L * 1024L

    /** The payload staging area (`DATRIS_TEMP_DIR`, the `datris-staging`
      * volume) must exist, take a write, and have room for one run: error when
      * missing / unwritable / under 1 GB free, warn when the free space is
      * below the per-run payload budget (`budgetMB`; 0 = unlimited never warns). */
    class StagingAreaCheck(probes: Probes, budgetMB: Int) extends Check {
        val id = "staging.area"
        val startupSafe = true
        private val remediation =
            "Point DATRIS_TEMP_DIR at a writable directory with room for one run (it is the datris-staging volume under the bundled compose: " +
                "`docker volume inspect datris-staging`, and free space on the disk holding Docker's data root)."
        def run(): CheckResult = {
            val s = probes.stagingArea()
            if (!s.exists) error("staging area " + s.root + " does not exist", remediation)
            else if (!s.writable) error("staging area " + s.root + " is not writable (" + gb(s.usableBytes) + " free)", remediation)
            else if (s.usableBytes < OneGB)
                error("staging area " + s.root + " has " + gb(s.usableBytes) + " free; a run needs at least 1 GB", remediation)
            else if (budgetMB > 0 && s.usableBytes < budgetMB.toLong * 1024L * 1024L)
                warn(
                    "staging area " + s.root + " has " + gb(s.usableBytes) + " free, under the per-run payload budget of " + budgetMB +
                        " MB (PIPELINE_MAX_PAYLOAD_MB); a run near the budget will fail on disk",
                    remediation + " Or lower PIPELINE_MAX_PAYLOAD_MB so an oversized run stops before the disk fills."
                )
            else ok("staging area " + s.root + " writable, " + gb(s.usableBytes) + " free")
        }
    }

    /** Run directories a crashed process left under the staging root (older
      * than the 24 h sweep age): warn when any, error when they hold >= 1 GB. */
    class StagingOrphansCheck(probes: Probes) extends Check {
        val id = "staging.orphans"
        val startupSafe = true
        def run(): CheckResult = {
            val (dirs, bytes) = probes.stagingOrphans()
            if (dirs.isEmpty) return ok("no orphaned run directories")
            val named = dirs.take(10).mkString(", ") + (if (dirs.size > 10) ", …" else "")
            val detail = dirs.size + " orphaned run director" + (if (dirs.size == 1) "y" else "ies") + " older than " +
                StagingArea.SweepAge.toHours + " h holding " + gb(bytes) + " under the staging area: " + named
            val remediation = "Restart datris (the boot sweep removes staged files older than " + StagingArea.SweepAge.toHours +
                " h), or remove those directories under DATRIS_TEMP_DIR by hand."
            if (bytes >= OneGB) error(detail, remediation) else warn(detail, remediation)
        }
    }

    /** Taps whose secret is not tagged `_type=tap`
      * (plans/stories/field-protection-8-tap-secret-scope.md): error when the
      * scope is enforced (they fail to run), warn under
      * DATRIS_TAP_SECRET_SCOPE=any (they can read platform secrets). */
    class TapSecretScopeCheck(probes: Probes) extends Check {
        val id = "tap.secret_scope"
        val startupSafe = true
        def run(): CheckResult = {
            // A failure to list the taps propagates: runOne makes it an error row.
            val result = probes.tapSecretScan()
            val offenders = result.offenders.map { case (tap, secret) => tap + "→" + secret }
            val unreadable =
                if (result.unreadable.isEmpty) ""
                else "could not read " + result.unreadable.size + " secret(s): " + result.unreadable.mkString(", ")
            val unreadableFix = "Check that Vault is reachable and the server token can read those secrets, then rerun doctor."
            if (offenders.isEmpty && unreadable.isEmpty) return ok("every tap with a secret uses a tap secret")
            if (offenders.isEmpty) return warn(unreadable, unreadableFix)
            val list = offenders.mkString(", ")
            val n = offenders.size + " tap(s) use a platform secret"
            val also = if (unreadable.isEmpty) "" else "; also " + unreadable
            val alsoFix = if (unreadable.isEmpty) "" else " " + unreadableFix
            val fix = "Create a tap secret (Configuration → Secrets → Tap) with the fields each tap needs and select it, " +
                "or add `_type: tap` to a hand-made tap secret."
            if (SecretNames.tapScopeEnforced)
                error(
                    n + " and will fail to run: " + list + also,
                    fix + " Or set " + SecretNames.TapScopeEnv + "=any to allow platform secrets." + alsoFix
                )
            else
                warn(
                    n + ", allowed by " + SecretNames.TapScopeEnv + "=any; these taps can read platform secrets: " + list + also,
                    fix + " Then remove " + SecretNames.TapScopeEnv + "=any." + alsoFix
                )
        }
    }

    /** Taps that will read or feed less once taps are held to their owner's
      * access (plans/stories/tap-runs-hold-owner-scope.md). Report-only
      * release: `warn` at most, never `error`; a scan that throws is a warn
      * saying the check could not run. The detail names up to
      * [[TapOwnerScopeCheck.PerGroup]] taps per group and states each
      * group's total. */
    class TapOwnerScopeCheck(probes: Probes) extends Check {
        val id = "tap.owner_scope"
        val startupSafe = true

        private val unreadableFix = "Check that MongoDB and Vault are reachable and the server can read the users and the API key metadata, then rerun doctor."

        def run(): CheckResult = {
            val result =
                try probes.tapOwnerScopeScan()
                catch {
                    case e: Exception =>
                        return warn("could not check tap owner access: " + e.getMessage, unreadableFix)
                }
            val findings = Option(result).map(_.findings).getOrElse(Nil)
            val unreadable = Option(result).map(_.unreadable).getOrElse(Nil)
            if (findings.isEmpty && unreadable.isEmpty) return ok("no tap reads or feeds beyond its owner's access")
            def unreadableText(names: Boolean) =
                if (unreadable.isEmpty) ""
                else
                    "could not check " + unreadable.size + " tap(s) (an owner, editor or pipeline read failed)" +
                        (if (names) ": " + TapOwnerScopeCheck.named(unreadable) else "")
            if (findings.isEmpty)
                return warn(unreadableText(names = true), unreadableFix).copy(namelessDetail = unreadableText(names = false) + TapOwnerScopeCheck.NamesHidden)
            def detail(names: Boolean): String = {
                val groups = TapOwnerScopeCheck.Groups.flatMap { case (group, what) =>
                    val in = findings.filter(_.group == group)
                    if (in.isEmpty) None
                    else if (!names) Some(what + " (" + in.size + ")")
                    else {
                        val list = in.map { f =>
                            f.tap + " -> " + Option(f.pipeline).filter(_.nonEmpty).getOrElse("(no target)") + " (" + String.valueOf(f.owner) + ")" +
                                Option(f.reason).filter(_.nonEmpty).map(" " + _).getOrElse("")
                        }
                        Some(what + " (" + in.size + "): " + TapOwnerScopeCheck.named(list))
                    }
                }
                val taps = findings.map(_.tap).distinct.size
                val also = unreadableText(names)
                taps + " tap(s) will read or feed less in the next release, which holds each tap to its owner's access " +
                    "(nothing changes in this release; callback reads are known only for taps run since the upgrade). " +
                    groups.mkString("; ") + (if (also.isEmpty) "" else "; also " + also) + (if (names) "" else TapOwnerScopeCheck.NamesHidden)
            }
            val fix =
                "A tap's owner is the user or API key that created it and is kept on every later save until an administrator changes it. " +
                    "Owner may not feed the target: grant the owning user edit on the target pipeline's catalog, or point the tap at a pipeline its owner may load into, " +
                    "or have an administrator change the tap's owner (POST /api/v1/tap/owner) to a user or key that may load into the target. " +
                    "Target pipeline missing: create the pipeline before the tap. " +
                    "Owner no longer exists: have an administrator change the tap's owner (POST /api/v1/tap/owner) to a current user or key. " +
                    "Reads held to the owner's access: give the owning user (and the last editor) access to what the script reads; " +
                    "API keys cannot be edited, so for a key that is limited or lacks a read the script makes, have an administrator change the tap's owner " +
                    "(POST /api/v1/tap/owner) to a user or key that holds those reads." +
                    (if (unreadable.isEmpty) "" else " " + unreadableFix)
            warn(detail(names = true), fix).copy(namelessDetail = detail(names = false))
        }
    }

    object TapOwnerScopeCheck {

        /** Taps named per group (and unreadable taps named) before "and N more". */
        val PerGroup = 10

        /** Ends the counts-only detail shown to a caller that does not see the whole installation. */
        val NamesHidden = " (tap, pipeline and owner names are shown only to a caller with access to the whole installation)"

        /** Report order, with what stops for each group in the next release. */
        val Groups: Seq[(String, String)] = Seq(
            TapOwnerScopeScan.FeedOutsideOwner -> "scheduled runs will stop feeding a pipeline the owner may not load into",
            TapOwnerScopeScan.TargetMissing ->
                "the target pipeline does not exist; a save by a key whose loads are checked will be refused until it does, and scheduled runs will feed it only if the owner may load into it",
            TapOwnerScopeScan.OwnerUnresolved -> "the owner no longer exists; scheduled runs will feed nothing and callback reads will stay in the tap's own catalog",
            TapOwnerScopeScan.ReadsNarrowed ->
                "the script reads through the callback while its owner or last editor is limited or lacks a read the callback offers; its reads will be held to the owner's access narrowed by the last editor's, and reads outside it refused"
        )

        private[util] def named(items: List[String]): String =
            items.take(PerGroup).mkString(", ") + (if (items.size > PerGroup) " and " + (items.size - PerGroup) + " more" else "")
    }

    /** Taps and keys a later release holds to the tap script reference,
      * secret and review rules (plans/stories/tap-save-references-in-scope.md,
      * rules 4-8), and repository taps whose script file is not their own
      * (plans/stories/tap-script-store-writes-and-deletes-in-scope.md). Report-only release: `warn` at most, never `error`; a
      * scan that throws is a warn saying the check could not run. The detail
      * names up to [[TapReferencesCheck.PerGroup]] items per group and states
      * each group's total; the counts-only detail names none. */
    class TapReferencesCheck(probes: Probes) extends Check {
        val id = "tap.references"
        val startupSafe = true

        private val unreadableFix =
            "Check that MongoDB and Vault are reachable and the server can read the users, the API key metadata, the tap secrets and the code repository settings, then rerun doctor."

        def run(): CheckResult = {
            val result =
                try probes.tapReferenceScan()
                catch {
                    case e: Exception =>
                        return warn("could not check tap script references, secrets and review use: " + e.getMessage, unreadableFix)
                }
            val findings = Option(result).map(_.findings).getOrElse(Nil)
            val unreadable = Option(result).map(_.unreadable).getOrElse(Nil)
            if (findings.isEmpty && unreadable.isEmpty)
                return ok("no tap script reference, tap secret or review use that a later release refuses")
            def unreadableText(names: Boolean) =
                if (unreadable.isEmpty) ""
                else
                    "could not check " + unreadable.size + " tap(s) (an owner, secret or code repository settings read failed)" +
                        (if (names) ": " + TapOwnerScopeCheck.named(unreadable) else "")
            if (findings.isEmpty)
                return warn(unreadableText(names = true), unreadableFix).copy(namelessDetail = unreadableText(names = false) + TapReferencesCheck.NamesHidden)
            def detail(names: Boolean): String = {
                val groups = TapReferencesCheck.Groups.flatMap { case (group, what) =>
                    val in = findings.filter(_.group == group)
                    val label =
                        if (group == TapReferenceScan.ReviewWithoutUpdate) what + " (recorded since " + TapReviewAccessLog.since + ")" else what
                    if (in.isEmpty) None
                    else if (!names) Some(label + " (" + in.size + ")")
                    else Some(label + " (" + in.size + "): " + TapOwnerScopeCheck.named(in.map(_.name)))
                }
                val also = unreadableText(names)
                val now =
                    if (findings.exists(_.group == TapReferenceScan.NotOwnScript))
                        " (in this release only a script upload by a user or key limited to a catalog or to what it created, for a tap whose script file is not its own, is refused)"
                    else " (nothing is refused in this release)"
                findings.size + " finding(s) that the next release refuses or changes" + now + ": " +
                    groups.mkString("; ") + (if (also.isEmpty) "" else "; also " + also) + (if (names) "" else TapReferencesCheck.NamesHidden)
            }
            val fix =
                "Script outside the tap script area: on built-in storage, store the script again from the tap editor or through the script upload, " +
                    "which writes it under tap-scripts/, then save the tap; in a code repository, move the tap's script to built-in storage and back, " +
                    "which stores it at the tap's own path under the current path prefix. Script file not the tap's own: if the tap was stored under a " +
                    "path prefix the code repository used before, record that earlier prefix on the code repository settings (earlierPathPrefixes); " +
                    "otherwise move the tap's script to built-in storage and back. Script shared across catalogs or owners: give each tap its own copy, " +
                    "on built-in storage by storing the script again under that tap's name, in a code repository by moving the tap's script to built-in " +
                    "storage and back. Secret outside the owner's access: use a tap secret the owner may read, or have an administrator change the " +
                    "tap's owner (POST /api/v1/tap/owner) to a user or key whose tap access covers the secret. Review without tap update: use a user or key that holds tap update " +
                    "on the tap (API keys cannot be edited; issue a new key)." +
                    (if (unreadable.isEmpty) "" else " " + unreadableFix)
            warn(detail(names = true), fix).copy(namelessDetail = detail(names = false))
        }
    }

    object TapReferencesCheck {

        /** Items named per group before "and N more". */
        val PerGroup: Int = TapOwnerScopeCheck.PerGroup

        /** Ends the counts-only detail shown to a caller that does not see the whole installation. */
        val NamesHidden = " (tap and key names are shown only to a caller with access to the whole installation)"

        /** Report order, with what stops for each group in the next release. */
        val Groups: Seq[(String, String)] = Seq(
            TapReferenceScan.OutsideArea ->
                "taps whose script sits outside the tap script area; in the next release the script store reads only scripts in that area, so they will not run, test or save until the script is stored again",
            TapReferenceScan.NotOwnScript ->
                "taps stored in a code repository whose script file is not their own (<path prefix><tap name>.py under the current or a recorded earlier path prefix); deleting such a tap now leaves the file in place, a script upload for it by a user or key limited to a catalog or to what it created is now refused, and in the next release the tap's next script upload is stored at its own path and the earlier file is left",
            TapReferenceScan.SharedScript ->
                "taps sharing one script across catalogs or owners; in the next release a user or key limited to a catalog or to what it created may point a tap only at its own script or the script of a tap it may read",
            TapReferenceScan.SecretOutsideOwner ->
                "taps whose owner is limited and whose secret the owner's tap access does not cover; in the next release a save, generate or run of such a tap by a limited user or key with that secret is refused",
            TapReferenceScan.ReviewWithoutUpdate ->
                "users and keys that used tap review without tap update; in the next release review needs tap update"
        )
    }

    /** (label, raw capability strings) for each entry of an
      * `{env}/api-key-metadata` map (label -> metadata JSON) that is not
      * revoked, sorted by label. A malformed entry is skipped (that key does
      * not resolve at all). */
    def nonRevokedKeyCapabilities(metadata: Map[String, String]): List[(String, Seq[String])] =
        metadata.toList.sortBy(_._1).flatMap { case (label, json) =>
            try {
                val o = JsonParser.parseString(json).getAsJsonObject
                val revoked = o.has("revoked") && !o.get("revoked").isJsonNull && o.get("revoked").getAsBoolean
                if (revoked) None
                else {
                    val caps =
                        if (o.has("capabilities") && o.get("capabilities").isJsonArray)
                            o.getAsJsonArray("capabilities").asScala.filter(_.isJsonPrimitive).map(_.getAsString).toList
                        else Nil
                    Some(label -> caps)
                }
            } catch { case _: Exception => None }
        }

    /** API keys holding a capability whose scope the platform does not apply
      * (plans/stories/api-key-inert-scopes.md): error when such capabilities
      * grant nothing (the default), warn under DATRIS_KEY_INERT_SCOPES=allow
      * (they still grant as before). Revoked keys are not listed. */
    class KeyInertScopesCheck(probes: Probes) extends Check {
        val id = "keys.inert_scopes"
        val startupSafe = true
        def run(): CheckResult = {
            val keys =
                try probes.apiKeyCapabilities()
                catch {
                    case e: Exception =>
                        return error(
                            "could not read the API key metadata: " + e.getMessage,
                            "Check that Vault is reachable and the server token can read the key metadata, then rerun doctor."
                        )
                }
            val offenders = keys.flatMap { case (label, caps) =>
                val inert = CapabilityScopes.inertOf(caps)
                if (inert.isEmpty) None else Some(label -> inert)
            }
            if (offenders.isEmpty) return ok("no API key carries a scope the platform does not apply")
            val list = offenders.map { case (l, caps) => l + " (" + caps.mkString(", ") + ")" }.mkString("; ")
            val n = offenders.size + " API key(s) hold a capability with a scope the platform does not apply"
            val fix = "Issue a replacement key under Configuration → API Keys with those capabilities unscoped or with a supported scope, " +
                "switch the caller to it, and revoke the old key (keys cannot be edited)."
            if (CapabilityScopes.refuseInert)
                error(
                    n + "; those capabilities grant nothing and their requests are refused: " + list,
                    fix + " To restore the earlier behaviour while you do, set " + CapabilityScopes.InertScopesEnv + "=allow."
                )
            else
                warn(
                    n + ", still granting under " + CapabilityScopes.InertScopesEnv + "=allow (wider than the scope reads): " + list,
                    fix + " Then remove " + CapabilityScopes.InertScopesEnv + "=allow."
                )
        }
    }

    /** DATRIS_AI_SAMPLE_VALUES (plans/stories/field-protection-9-ai-values-switch.md):
      * reports the effective value; warns when the install protects fields
      * while the helpers still send row values to the model. */
    class AiSampleValuesCheck(probes: Probes) extends Check {
        val id = "ai.sample_values"
        val startupSafe = true
        private val sampled = "row values are sent to the model by schema, profile and CodeGen helpers (default)"
        def run(): CheckResult = {
            if (!AiSampleValues.enabled) {
                val withheld = "row values are withheld from the model by schema, profile, attachment and CodeGen helpers (" + AiSampleValues.EnvVar + "=false)"
                val odd =
                    try Right(probes.pipelinesWithNonIdentifierFields())
                    catch { case e: Exception => Left(e) }
                return odd match {
                    case Right(Nil) => ok(withheld)
                    case Right(names) =>
                        val shown = names.take(5).mkString(", ") + (if (names.size > 5) " and " + (names.size - 5) + " more" else "")
                        warn(
                            withheld + "; stored schema field names are always sent, and " + names.size +
                                " pipeline(s) have field names that do not read as column names: " + shown,
                            "Rename those source fields to plain column names (letters, digits, _ . - and spaces), " +
                                "so no value-like name reaches the model."
                        )
                    case Left(e) =>
                        warn(
                            withheld + "; could not read pipeline configs to check stored field names: " + e.getMessage,
                            "Check that the config database is reachable, then rerun doctor."
                        )
                }
            }
            val protects =
                try Right(probes.anyPipelineProtects())
                catch { case e: Exception => Left(e) }
            protects match {
                case Left(e) =>
                    warn(
                        sampled + "; could not read pipeline configs to check for protected fields: " + e.getMessage,
                        "Check that the config database is reachable, then rerun doctor. If any pipeline protects fields, set " +
                            AiSampleValues.EnvVar + "=false."
                    )
                case Right(true) =>
                    warn(
                        "this install protects fields but still samples values in helpers; set " + AiSampleValues.EnvVar + "=false",
                        "Set " + AiSampleValues.EnvVar + "=false on the datris service and recreate it, so schema generation, profiling, " +
                            "Assistant attachments, CodeGen rules and transformations, and pipeline fix suggestions work without row values."
                    )
                case Right(false) => ok(sampled)
            }
        }
    }

    object GovernanceControlsCheck {
        val Vars: Seq[String] = Seq("USE_USER_AUTH", "USE_API_KEYS", "USE_AUDIT_LOG", "USE_AGENT_POLICY")
        val Labels: Map[String, String] = Map(
            "USE_USER_AUTH" -> "user login",
            "USE_API_KEYS" -> "API keys",
            "USE_AUDIT_LOG" -> "the audit log",
            "USE_AGENT_POLICY" -> "the agent policy"
        )

        /** "a", "a and b", "a, b and c". */
        def humanList(items: Seq[String]): String =
            if (items.size <= 1) items.mkString
            else items.init.mkString(", ") + " and " + items.last
        val DocsLink = "https://docs.datris.ai/quick-start#2-switch-on-the-governance-controls"
    }

    /** User login, API keys, the audit log and the agent policy
      * (plans/stories/governance-controls-production-preset.md): ok when all
      * four are on, warn naming each that is off. Never errors, and not
      * startup-safe, so a default install logs nothing at boot. */
    class GovernanceControlsCheck(probes: Probes) extends Check {
        import GovernanceControlsCheck._
        val id = "governance.controls"
        val startupSafe = false
        def run(): CheckResult = {
            val state = probes.governanceControls()
            val on = Vars.filter(v => state.getOrElse(v, false))
            val off = Vars.filterNot(on.contains)
            if (off.isEmpty) ok("on: " + on.mkString(", "))
            else
                warn(
                    "off: " + off.mkString(", ") + (if (on.nonEmpty) "; on: " + on.mkString(", ") else "") +
                        " — " + humanList(off.map(Labels)) + (if (off.size == 1) " is" else " are") +
                        " off until switched on for production",
                    "Add " + off.map(_ + "=true").mkString(", ") + " to .env, then run " +
                        "`docker compose up -d --force-recreate datris mcp-server`. See " + DocsLink + "."
                )
        }
    }

    /** Where generated data-quality and transformation scripts run
      * (plans/stories/codegen-script-isolation.md). ok: in the
      * datris-codegen-runner sidecar and it answers; warn: in-process
      * (USE_CODEGEN_RUNNER off); error: the runner is required but cannot run
      * a script. The runner is not probed when it is not enabled. */
    class CodeGenIsolationCheck(probes: Probes, startup: Boolean = false) extends Check {
        val id = "codegen.isolation"
        val startupSafe = true
        def run(): CheckResult =
            if (!probes.codegenRunnerEnabled())
                warn(
                    "generated data-quality and transformation scripts run in-process (USE_CODEGEN_RUNNER is not true): " +
                        "they share the server container's files and network",
                    "On a compose install, refresh docker-compose.yml (git pull, or re-download docker-compose.standalone.yml) " +
                        "and run `docker compose up -d` to add the datris-codegen-runner sidecar. Expected under sbt / an IDE."
                )
            else
                probes.codegenRunnerHealth() match {
                    case Right(_) => ok("generated scripts run in datris-codegen-runner")
                    // At boot the sidecar may simply not be listening yet: a skip, not an error.
                    // On demand the same failure is an error.
                    case Left(why) if startup && CodeGenIsolationCheck.notUpYet(why) =>
                        skip("datris-codegen-runner not reachable yet (" + why + ")")
                    case Left(why) =>
                        error(
                            "USE_CODEGEN_RUNNER is on but datris-codegen-runner cannot run a script: " + why +
                                " — AI data-quality rules and AI transformations fail until it can",
                            "Run `docker compose pull datris-codegen-runner && docker compose up -d datris-codegen-runner`, then " +
                                "`docker compose logs datris-codegen-runner`. A 404 means an older datrisai/datris-tap-runner image; " +
                                "a token error means recreate datris and datris-codegen-runner together; a disk error means the " +
                                "codegen-scratch volume's disk is full."
                        )
                }
    }

    /** Runner overload protection (plans/stories/runner-overload-protection.md).
      * skip: taps run in-process; ok: the concurrency setting and slot use, no run
      * waited in the last 24 h; warn: runs waited for a slot (or slot use could
      * not be read); error: a run was turned away after the queue timeout. Not
      * startup-safe: it reads the tap run log. */
    class RunnerConcurrencyCheck(probes: Probes) extends Check {
        val id = "runner.concurrency"
        val startupSafe = false
        private val Hours = 24
        private val Remediation =
            "Raise TAP_RUNNER_MAX_CONCURRENT in .env (if the host has room; the runner's TAP_RUNNER_CPUS / TAP_RUNNER_MEMORY " +
                "may need raising with it) or stagger tap schedules, then `docker compose up -d datris-tap-runner`. " +
                "TAP_RUNNER_QUEUE_TIMEOUT_SECONDS sets how long a run waits for a slot."
        def run(): CheckResult = {
            if (!probes.tapRunnerEnabled()) return skip("taps run in-process (USE_TAP_RUNNER is not true)")
            val slotUse = probes.tapRunnerHealth() match {
                case Right((active, max)) => Right("TAP_RUNNER_MAX_CONCURRENT=" + max + ", " + active + " of " + max + " slots in use")
                case Left(why) => Left("slot use unknown (" + why + ")")
            }
            val (waited, rejected, maxQueuedMs) = probes.recentRunnerWaits(Hours)
            val prefix = slotUse.merge
            def runs(n: Int) = n + (if (n == 1) " run" else " runs")
            val waitText = runs(waited) + " waited (longest " + ((maxQueuedMs + 500L) / 1000L) + " s)"
            if (rejected > 0)
                error(
                    prefix + "; " + runs(rejected) + " turned away after waiting for a slot and " + waitText + " in the last " + Hours + " h",
                    Remediation
                )
            else if (waited > 0) warn(prefix + "; " + waitText + " in the last " + Hours + " h", Remediation)
            else
                slotUse match {
                    case Right(text) => ok(text + ", no run waited in the last " + Hours + " h")
                    case Left(text) =>
                        warn(
                            text + ", no run waited in the last " + Hours + " h",
                            "Check `docker compose logs datris-tap-runner`; an image without slot counters is older than this server: " +
                                "`docker compose pull datris-tap-runner && docker compose up -d datris-tap-runner`."
                        )
                }
        }
    }

    object CodeGenIsolationCheck {
        private[util] def notUpYet(why: String): Boolean =
            why != null && (why.contains("Connection refused") || why.contains("Unknown host"))
    }

    /** Compares the server's version with whatever versions the calling
      * clients report (`?cli=`, `?mcp=`, `?ui=`). Major.minor must match. */
    class VersionSkewCheck(serverVersion: String, clients: Map[String, String]) extends Check {
        val id = "version.skew"
        val startupSafe = true
        private def majorMinor(v: String): String = v.split("\\.").take(2).mkString(".")
        def run(): CheckResult = {
            val reported = clients.filter { case (_, v) => v != null && v.nonEmpty }
            val listing = ("server " + serverVersion) +: reported.toSeq.map { case (k, v) => k + " " + v }
            val remediation = "Bring every component to the same release: `docker compose pull && docker compose up -d --remove-orphans`; " +
                "`pip install -U datris-mcp-server` or `brew upgrade datris` for the CLI."
            if (reported.isEmpty) return ok("server " + serverVersion + "; no client reported a version")
            val unknown = reported.collect { case (k, v) if v.equalsIgnoreCase("unknown") => k }
            val skewed = reported.collect { case (k, v) if !v.equalsIgnoreCase("unknown") && majorMinor(v) != majorMinor(serverVersion) => k }
            if (skewed.nonEmpty) warn(listing.mkString(", ") + " — " + skewed.mkString(", ") + " differ from the server in major.minor", remediation)
            else if (unknown.nonEmpty)
                warn(
                    listing.mkString(", ") + " — " + unknown.mkString(", ") + " version unknown (image built without a version stamp)",
                    "Pull the published image (`docker compose pull`), or pass APP_VERSION when building the UI image from source."
                )
            else ok(listing.mkString(", "))
        }
    }

    /** Strict evidence mode for the audit log (AUDIT_LOG_STRICT). ok with the
      * effective mode; warn when strict is set but the audit log is off (the
      * switch does nothing); error when strict and audited requests are being
      * refused because the config store is not taking entries. */
    class AuditStrictCheck(probes: Probes) extends Check {
        val id = "audit.strict"
        val startupSafe = true
        def run(): CheckResult = {
            val strict = probes.auditLogStrict()
            val auditOn = probes.governanceControls().getOrElse("USE_AUDIT_LOG", false)
            if (!strict)
                ok(
                    if (auditOn) "audit log best effort (AUDIT_LOG_STRICT off): entries dropped under backpressure are counted in datris_audit_dropped_total"
                    else "audit log off"
                )
            else if (!auditOn)
                warn(
                    "AUDIT_LOG_STRICT is true but USE_AUDIT_LOG is off: nothing is audited, so strict mode has no effect",
                    "Set USE_AUDIT_LOG=true in .env and recreate datris (`docker compose up -d datris`), or unset AUDIT_LOG_STRICT."
                )
            else if (!probes.auditAcceptingWrites())
                error(
                    "AUDIT_LOG_STRICT is on and the audit log is not accepting entries: audited requests are refused with 503",
                    "The config store (MongoDB) is not taking audit writes. Check `docker compose ps mongodb` and `docker compose logs datris`; " +
                        "queued entries are written in order once it recovers."
                )
            else ok("audit log strict (AUDIT_LOG_STRICT): an audited request is refused rather than its entry dropped")
        }
    }

    /** Strict evidence mode for provenance (PROVENANCE_STRICT). ok with the
      * effective mode; warn (never error) when strict is on and a stamping
      * pipeline has an XML source: XML is exempt and loads unstamped, so those
      * pipelines are outside the guarantee. */
    class ProvenanceStrictCheck(probes: Probes) extends Check {
        val id = "provenance.strict"
        val startupSafe = true
        def run(): CheckResult = {
            if (!probes.provenanceStrict())
                return ok("provenance stamping best effort (PROVENANCE_STRICT off): a run that cannot be stamped loads unstamped")
            val xml =
                try Right(probes.xmlStampingPipelines())
                catch { case e: Exception => Left(String.valueOf(e.getMessage)) }
            xml match {
                case Left(why) =>
                    warn(
                        "provenance strict (PROVENANCE_STRICT); could not read pipeline configs to list XML exemptions: " + why,
                        "Re-run `datris doctor` once the config store is reachable."
                    )
                case Right(Nil) =>
                    ok("provenance strict (PROVENANCE_STRICT): a stamping pipeline fails its run rather than loading unstamped rows")
                case Right(names) =>
                    warn(
                        "provenance strict (PROVENANCE_STRICT), but these stamping pipelines have an XML source, which is exempt and loads unstamped: " +
                            names.mkString(", "),
                        "XML cannot be stamped. Those pipelines are outside the strict guarantee; convert the source to JSON or CSV if every row must carry provenance."
                    )
            }
        }
    }

    /** Which hardening env vars reached the JVM. Always ok — evidence for the
      * CLI's `env.not_forwarded` check, and a line in the report so a
      * documented-but-unforwarded var is visible without shelling in. */
    class EnvSeenCheck(probes: Probes) extends Check {
        val id = "env.seen"
        val startupSafe = true
        val names =
            Seq(
                "DATRIS_ENV",
                "DATRIS_ALLOW_PLAINTEXT_DB",
                "DATRIS_ALLOW_PRIVATE_EGRESS",
                "PIPELINE_MAX_PAYLOAD_MB",
                "TAPMAXOUTPUTMB",
                "VAULT_TOKEN_FILE",
                "TAP_SCRIPT_TIMEOUT_SECONDS",
                "TAP_RUN_TIMEOUT_SECONDS"
            )
        def run(): CheckResult = {
            val seen = probes.envSeen(names)
            val present = names.filter(n => seen.getOrElse(n, false))
            val absent = names.filterNot(n => seen.getOrElse(n, false))
            ok("seen: " + (if (present.isEmpty) "none" else present.mkString(", ")) +
                (if (absent.nonEmpty) "; unset: " + absent.mkString(", ") else ""))
        }
    }

    /** The operator's environment label (`DATRIS_ENV`). Always ok — it is a
      * label; only `production` hardens, and that is enforced elsewhere. */
    class EnvLabelCheck extends Check {
        val id = "env.label"
        val startupSafe = true
        def run(): CheckResult =
            EnvironmentLabel.current match {
                case Some(label) => ok("environment label: " + label + " (DATRIS_ENV)")
                case None => ok("environment label: unset")
            }
    }

    /** Opt-in (`?probes=ai`): a minimal request through each configured chat
      * slot, and a one-input embed for hosted embedding providers. Costs a
      * few tokens; never at startup. */
    class AiModelReachableCheck(probes: Probes, slots: Slots) extends Check {
        val id = "ai.model_reachable"
        val startupSafe = false
        override val optInGroup: Option[String] = Some("ai")
        private val timeoutMs = 10000

        private def classify(label: String, provider: String, model: String, status: Int, body: String): Option[(String, String, String)] = {
            val b = if (body == null) "" else body.toLowerCase
            val head = label + " (" + provider + ", model " + model + ")"
            if (status >= 200 && status < 300) None
            else if (status == 401 || status == 403)
                Some((
                    StatusError,
                    head + ": HTTP " + status + " — the provider rejected the API key",
                    "Re-enter the " + provider + " key in Configuration → AI Providers and confirm it has access to this model."
                ))
            else if (
                status == 404 || (status == 400 && (b.contains("model") && (b.contains("not found") || b.contains("does not exist") || b.contains("unknown"))))
            ) {
                Some((
                    StatusError,
                    head + ": HTTP " + status + " model not found",
                    "Pick a current model in Configuration → AI Providers (or check /api/v1/ai/model-catalog)."
                ))
            } else if (status >= 400 && status < 500)
                Some((
                    StatusError,
                    head + ": HTTP " + status + " " + body.take(200).replaceAll("\\s+", " "),
                    "Check the slot's endpoint, model and key in Configuration → AI Providers."
                ))
            else
                Some((StatusWarn, head + ": HTTP " + status + " from the endpoint", "The provider is reachable but failing server-side; retry shortly."))
        }

        def run(): CheckResult = {
            val findings = scala.collection.mutable.ListBuffer[(String, String, String)]()
            val fine = scala.collection.mutable.ListBuffer[String]()
            probes.chatSlots().foreach { case (label, cfg) =>
                try {
                    val (status, body) = probes.probeChat(cfg, timeoutMs)
                    classify(label, cfg.provider, cfg.model, status, body) match {
                        case None => fine += label + " (" + cfg.provider + "/" + cfg.model + ")"
                        case Some(f) => findings += f
                    }
                } catch {
                    case e: Exception =>
                        findings += ((
                            StatusWarn,
                            label + " (" + cfg.provider + ", model " + cfg.model + "): no response within " + (timeoutMs / 1000) + "s (" + e.getClass
                                .getSimpleName + ")",
                            "Check the slot's endpoint URL and outbound network access from the datris container."
                        ))
                }
            }
            probes.secret(slots.embeddingSecret).foreach { s =>
                val provider = s.getOrElse("provider", "").trim.toLowerCase
                if (provider == "openai" || provider == "azure") {
                    val endpoint = s.getOrElse("endpoint", "")
                    val model = s.getOrElse("model", "")
                    try {
                        val (status, body) = probes.probeEmbedding(provider, endpoint, model, s.getOrElse("apiKey", ""), timeoutMs)
                        classify("embedding", provider, model, status, body) match {
                            case None => fine += "embedding (" + provider + "/" + model + ")"
                            case Some(f) => findings += f
                        }
                    } catch {
                        case e: Exception =>
                            findings += ((
                                StatusWarn,
                                "embedding (" + provider + ", model " + model + "): no response within " + (timeoutMs / 1000) + "s (" + e.getClass
                                    .getSimpleName + ")",
                                "Check the embedding endpoint URL and outbound network access from the datris container."
                            ))
                    }
                }
            }
            if (findings.isEmpty) {
                if (fine.isEmpty) skip("no AI slots to probe") else ok(fine.mkString(", "))
            } else {
                val status = if (findings.exists(_._1 == StatusError)) StatusError else StatusWarn
                CheckResult(id, status, status, findings.map(_._2).mkString("; "), findings.map(_._3).distinct.mkString(" "))
            }
        }
    }

    /** One run of `body` per key at a time, on its own daemon thread named
      * `threadName`, shared by every caller that asks while it runs: each
      * caller waits at most `timeoutMs` (from its own call) for the shared
      * result, then gets an IllegalStateException saying the run did not
      * finish in time (the run itself goes on, and a call after it ends
      * starts a new one). A failure of `body` is rethrown to every waiting
      * caller as an IllegalStateException carrying its message. `onWait` is
      * called just before a caller starts waiting (a spec seam). */
    final class SharedRun[T](threadName: String, timeoutMs: Long, onWait: () => Unit = () => ()) {
        private val inflight = new java.util.concurrent.ConcurrentHashMap[String, java.util.concurrent.FutureTask[T]]()

        private final class Run(key: String, body: () => T) extends java.util.concurrent.FutureTask[T](() => body()) {
            override protected def done(): Unit = { inflight.remove(key, this); () }
        }

        def get(key: String, body: () => T): T = {
            val mine = new Run(key, body)
            val running = inflight.putIfAbsent(key, mine)
            val task: java.util.concurrent.FutureTask[T] =
                if (running != null) running
                else {
                    val thread = new Thread(mine, threadName)
                    thread.setDaemon(true)
                    try thread.start()
                    catch { case e: Throwable => inflight.remove(key, mine); throw e }
                    mine
                }
            onWait()
            try task.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            catch {
                case _: java.util.concurrent.TimeoutException =>
                    throw new IllegalStateException("the scan did not finish within " + SharedRun.limitText(timeoutMs))
                case e: java.util.concurrent.ExecutionException if e.getCause != null =>
                    throw new IllegalStateException(e.getCause.getMessage, e.getCause)
            }
        }
    }

    /** The Postgres secret's separate query login
      * (plans/stories/postgres-query-role.md, Decision 6). No row at all when
      * the secret carries no `queryUsername`/`queryPassword`. ok when the
      * query login connects and is not a superuser; warn when it is a
      * superuser (`rolsuper`), when only one of the two keys is set, or when
      * it cannot connect. Not startup-safe: it opens a database connection. */
    class PostgresQueryLoginCheck(probes: Probes) extends Check {
        val id = "postgres.query_login"
        val startupSafe = false
        private val Docs = "See \"Separate query login\" in the PostgreSQL destination docs."
        override def runIfApplies(): Option[CheckResult] =
            probes.postgresQueryLogin().map {
                case Right((user, false)) =>
                    ok("read-back queries connect as the query login '" + user + "', which is not a superuser")
                        .copy(namelessDetail = "read-back queries connect as the query login, which is not a superuser")
                case Right((user, true)) =>
                    warn(
                        "the query login '" + user + "' is a superuser (rolsuper): read-back queries keep superuser reach " +
                            "behind the read-only transaction",
                        "Give the query login no superuser: `ALTER ROLE <query login> NOSUPERUSER;`, or create a separate " +
                            "read-only login and set it as queryUsername / queryPassword in the Postgres secret. " + Docs
                    ).copy(namelessDetail =
                        "the query login is a superuser (rolsuper): read-back queries keep superuser reach behind the read-only transaction"
                    )
                case Left(why) =>
                    warn(
                        "the Postgres secret names a query login that could not be checked: " + why,
                        "Set both queryUsername and queryPassword in the Postgres secret (or neither, to query with the loading login), " +
                            "and check that the login exists, its password matches and it may connect to the database. " +
                            "Until then every read-back query fails. " + Docs
                    ).copy(namelessDetail = "the Postgres secret names a query login that could not be checked")
            }
        def run(): CheckResult = runIfApplies().getOrElse(skip("the Postgres secret names no query login; queries use the loading login"))
    }

    object SharedRun {
        private[util] def limitText(ms: Long): String = if (ms >= 1000L && ms % 1000L == 0L) (ms / 1000L) + " s" else ms + " ms"
    }

    // ------------------------------------------------------------ assembly

    /** Every server-side check, in report order. */
    def checks(probes: Probes, slots: Slots, serverVersion: String, clients: Map[String, String], startup: Boolean = false): Seq[Check] =
        Seq(
            new VaultTokenTtlCheck(probes),
            new VaultAiSlotsCheck(probes, slots),
            new JdbcMssqlDriverCheck(probes),
            new ObjectStoreBucketOverrideCheck(probes),
            new AiEmbeddingModelCheck(probes, slots, startup),
            new DiskUsageCheck(probes, Seq(System.getProperty("user.dir"), System.getProperty("java.io.tmpdir"))),
            new StagingAreaCheck(probes, StagingArea.payloadBudgetMB),
            new StagingOrphansCheck(probes),
            new TapSecretScopeCheck(probes),
            new TapOwnerScopeCheck(probes),
            new TapReferencesCheck(probes),
            new KeyInertScopesCheck(probes),
            new CodeGenIsolationCheck(probes, startup),
            new RunnerConcurrencyCheck(probes),
            new PostgresQueryLoginCheck(probes),
            new AiSampleValuesCheck(probes),
            new GovernanceControlsCheck(probes),
            new AuditStrictCheck(probes),
            new ProvenanceStrictCheck(probes),
            new VersionSkewCheck(serverVersion, clients),
            new EnvSeenCheck(probes),
            new EnvLabelCheck,
            new AiModelReachableCheck(probes, slots)
        )

    /** Run one check, timing it and turning a thrown exception into an
      * error row rather than a failed report. */
    def runOne(check: Check): CheckResult = timed(check)(Some(check.run())).get

    /** [[runOne]] for a report: None when the check does not apply to this
      * install ([[Check.runIfApplies]]), so it adds no row. */
    def runIfApplies(check: Check): Option[CheckResult] = timed(check)(check.runIfApplies())

    private def timed(check: Check)(body: => Option[CheckResult]): Option[CheckResult] = {
        val start = System.currentTimeMillis()
        val r =
            try body
            catch {
                case e: Exception =>
                    Some(
                        CheckResult(
                            check.id,
                            StatusError,
                            StatusError,
                            "check failed: " + e.getClass.getSimpleName + ": " + e.getMessage,
                            "This is a doctor bug — report it with the server log."
                        )
                    )
            }
        r.map(_.copy(ms = System.currentTimeMillis() - start))
    }

    /** Assemble a report. `mode` = `quick` (startup-safe subset) or `full`
      * (everything except opt-in groups); `probeGroups` names the opt-in
      * groups to include (`ai`). */
    def run(
        mode: String,
        probeGroups: Set[String],
        clients: Map[String, String],
        probes: Probes,
        slots: Slots,
        serverVersion: String
    ): Report = {
        val quick = mode != null && mode.equalsIgnoreCase("quick")
        val selected = checks(probes, slots, serverVersion, clients).filter { c =>
            c.optInGroup match {
                case Some(g) => probeGroups.contains(g)
                case None => !quick || c.startupSafe
            }
        }
        val results = selected.flatMap(c => runIfApplies(c))
        Report(if (quick) "quick" else "full", Map("server" -> serverVersion) ++ clients, results)
    }

    /** Boot-time subset: one warn line per non-ok check. Anything that
      * throws is caught here — a doctor bug must never block boot. */
    def runStartup(probes: Probes, slots: Slots, serverVersion: String): Seq[CheckResult] = {
        try {
            val results =
                checks(probes, slots, serverVersion, Map.empty, startup = true).filter(c => c.startupSafe && c.optInGroup.isEmpty).flatMap(c => runIfApplies(c))
            results.foreach { r =>
                if (r.status == StatusWarn || r.status == StatusError)
                    logger.warn("DOCTOR " + r.id + ": " + r.detail + (if (r.remediation.nonEmpty) " — " + r.remediation else ""))
                else
                    logger.info("DOCTOR " + r.id + ": " + r.status + " — " + r.detail)
            }
            results
        } catch {
            case e: Exception =>
                logger.warn("DOCTOR startup checks failed to run (continuing): " + e.getMessage)
                Nil
        }
    }

    // --------------------------------------------------------- live wiring

    @volatile private var configuredSlots: Slots = Slots("", "", "")

    /** Called once from StartupRunner with the configured AI slot secret names. */
    def configure(slots: Slots): Unit = configuredSlots = slots

    def slots: Slots = configuredSlots

    /** Real probes against this server's environment. */
    object LiveProbes extends Probes {
        private def readAll(conn: java.net.HttpURLConnection): String = {
            val stream = if (conn.getResponseCode >= 400) conn.getErrorStream else conn.getInputStream
            if (stream == null) ""
            else
                try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                finally stream.close()
        }

        def httpGet(url: String, timeoutMs: Int): (Int, String) = {
            val conn = new java.net.URL(url).openConnection().asInstanceOf[java.net.HttpURLConnection]
            conn.setConnectTimeout(timeoutMs)
            conn.setReadTimeout(timeoutMs)
            conn.setRequestMethod("GET")
            try { (conn.getResponseCode, readAll(conn)) }
            finally conn.disconnect()
        }

        def vaultLookupSelf(): Option[Map[String, String]] = {
            try {
                val token = VaultSecretsUtilBuilder.resolveToken()
                val conn = new java.net.URL(VaultSecretsUtilBuilder.vaultAddress + "/v1/auth/token/lookup-self")
                    .openConnection().asInstanceOf[java.net.HttpURLConnection]
                conn.setConnectTimeout(5000)
                conn.setReadTimeout(5000)
                conn.setRequestProperty("X-Vault-Token", token)
                val (status, body) =
                    try { (conn.getResponseCode, readAll(conn)) }
                    finally conn.disconnect()
                if (status != 200) {
                    logger.debug("Vault lookup-self returned HTTP " + status)
                    return None
                }
                val data = JsonParser.parseString(body).getAsJsonObject.getAsJsonObject("data")
                Some(data.entrySet().asScala.map(e => e.getKey -> (if (e.getValue.isJsonPrimitive) e.getValue.getAsString else e.getValue.toString)).toMap)
            } catch {
                case e: Exception =>
                    logger.debug("Vault lookup-self failed: " + e.getMessage)
                    None
            }
        }

        def secret(name: String): Option[Map[String, String]] =
            if (name == null || name.isEmpty) None
            else SecretsUtil.getSecretMap(name).map(_.asScala.toMap)

        def apiKeyResolves(provider: String, rawKey: String): Boolean =
            try {
                val env = DatrisEnvironment.values
                AIUtil.resolveApiKey(rawKey, provider, env.multiTenant, env.environment).nonEmpty
            } catch { case _: Exception => false }

        def classPresent(className: String): Boolean =
            try { Class.forName(className); true }
            catch { case _: Throwable => false }

        def pipelineSources(): List[(String, String)] =
            try {
                PipelineConfigIO.readAll(DatrisEnvironment.values.pipelineTableName)
                    .filter(c => c.source != null && c.source.databaseAttributes != null)
                    .map(c => (c.name, c.source.databaseAttributes.`type`))
            } catch {
                case e: Exception =>
                    logger.debug("pipeline scan for doctor failed: " + e.getMessage)
                    Nil
            }

        def objectStoreBucketOverrides(): List[(String, String, String)] =
            try {
                PipelineConfigIO.readAll(DatrisEnvironment.values.pipelineTableName)
                    .filter(c => c.destination != null && c.destination.objectStore != null && c.destination.objectStore.destinationBucketOverride != null)
                    .map(c => (c.name, c.destination.objectStore.provider, c.destination.objectStore.destinationBucketOverride))
            } catch {
                case e: Exception =>
                    logger.debug("objectStore override scan for doctor failed: " + e.getMessage)
                    Nil
            }

        def diskUsage(path: String): Option[(Long, Long)] =
            try {
                if (path == null) return None
                val p = java.nio.file.Paths.get(path)
                if (!java.nio.file.Files.exists(p)) return None
                val store = java.nio.file.Files.getFileStore(p)
                Some((store.getTotalSpace, store.getUsableSpace))
            } catch { case _: Exception => None }

        def envSeen(names: Seq[String]): Map[String, Boolean] =
            names.map(n => n -> sys.env.get(n).exists(_.nonEmpty)).toMap

        def tapSecretRefs(): List[(String, String, Option[String])] = tapSecretScan().refs

        override def tapSecretScan(): TapSecretScopeScan.Result = TapSecretScopeScan.liveScan()

        /** Longest a doctor run (startup included) waits for the tap owner scan. */
        val TapOwnerScanTimeoutMs = 15000L

        private val tapOwnerScan = new SharedRun[TapOwnerScopeScan.Result]("doctor-tap-owner-scan", TapOwnerScanTimeoutMs)

        /** [[TapOwnerScopeScan.liveScan]] through [[SharedRun]]: on its own
          * daemon thread, one scan per environment at a time, every caller
          * waiting at most [[TapOwnerScanTimeoutMs]] for it. A call made while
          * a scan is running waits on that scan's result. Past the limit (a
          * slow store) it throws and the check says it could not check. So a
          * slow key or user store never holds startup or a doctor call longer
          * than that, and scans never pile up. */
        override def tapOwnerScopeScan(): TapOwnerScopeScan.Result = {
            val env = DatrisEnvironment.current
            tapOwnerScan.get(
                String.valueOf(env.environment),
                () => {
                    ai.datris.model.TenantContext.set(env)
                    try TapOwnerScopeScan.liveScan()
                    finally ai.datris.model.TenantContext.clear()
                }
            )
        }

        private val tapReferenceScanRun = new SharedRun[TapReferenceScan.Result]("doctor-tap-reference-scan", TapOwnerScanTimeoutMs)

        /** [[TapReferenceScan.liveScan]] through [[SharedRun]], bounded like
          * [[tapOwnerScopeScan]]: one scan per environment at a time, every
          * caller waiting at most [[TapOwnerScanTimeoutMs]]; past that the
          * check says it could not check. */
        override def tapReferenceScan(): TapReferenceScan.Result = {
            val env = DatrisEnvironment.current
            tapReferenceScanRun.get(
                String.valueOf(env.environment),
                () => {
                    ai.datris.model.TenantContext.set(env)
                    try TapReferenceScan.liveScan()
                    finally ai.datris.model.TenantContext.clear()
                }
            )
        }

        override def apiKeyCapabilities(): List[(String, Seq[String])] = {
            val name = DatrisEnvironment.values.environment + "/api-key-metadata"
            SecretsUtil.tryGetSecretMap(name) match {
                case scala.util.Success(Some(m)) => nonRevokedKeyCapabilities(m.asScala.toMap)
                case scala.util.Success(None) => Nil
                case scala.util.Failure(e) => throw new RuntimeException("could not read " + name + ": " + e.getMessage, e)
            }
        }

        // A read failure propagates: the check reports "could not read pipeline configs".
        def anyPipelineProtects(): Boolean =
            PipelineConfigIO.readAll(DatrisEnvironment.values.pipelineTableName).exists(c => FieldProtection.protectedFields(c).nonEmpty)

        override def governanceControls(): Map[String, Boolean] = {
            val env = DatrisEnvironment.values
            Map(
                "USE_USER_AUTH" -> env.useUserAuth,
                "USE_API_KEYS" -> env.useApiKeys,
                "USE_AUDIT_LOG" -> env.useAuditLog,
                "USE_AGENT_POLICY" -> env.useAgentPolicy
            )
        }

        override def auditLogStrict(): Boolean = DatrisEnvironment.values.auditLogStrict

        override def auditAcceptingWrites(): Boolean = ai.datris.audit.AuditLog.acceptingWrites

        override def provenanceStrict(): Boolean = DatrisEnvironment.values.provenanceStrict

        override def xmlStampingPipelines(): List[String] =
            PipelineConfigIO.readAll(DatrisEnvironment.values.pipelineTableName)
                .filter { c =>
                    ProvenanceStamper.enabled(c) && c.source != null && c.source.fileAttributes != null && c.source.fileAttributes.xmlAttributes != null
                }
                .map(_.name)

        override def pipelinesWithNonIdentifierFields(): List[String] =
            PipelineConfigIO.readAll(DatrisEnvironment.values.pipelineTableName)
                .filter { c =>
                    c.source != null && c.source.schemaProperties != null && c.source.schemaProperties.fields != null &&
                    c.source.schemaProperties.fields.asScala.exists(f => f != null && f.name != null && !AiSampleValues.isIdentifier(f.name))
                }
                .map(_.name)

        def stagingArea(): StagingAreaState = {
            val root = StagingArea.root
            val exists = java.nio.file.Files.isDirectory(root)
            val writable = exists && {
                try {
                    val probe = java.nio.file.Files.createTempFile(root, ".doctor-", ".probe")
                    try { java.nio.file.Files.write(probe, "ok".getBytes(StandardCharsets.UTF_8)); true }
                    finally java.nio.file.Files.deleteIfExists(probe)
                } catch { case _: Exception => false }
            }
            val usable =
                if (!exists) 0L
                else
                    try java.nio.file.Files.getFileStore(root).getUsableSpace
                    catch { case _: Exception => 0L }
            StagingAreaState(root.toString, exists, writable, usable)
        }

        def stagingOrphans(): (List[String], Long) =
            try {
                val (dirs, bytes) = StagingArea.orphanScan(StagingArea.SweepAge)
                (dirs.map(_.getFileName.toString), bytes)
            } catch {
                case e: Exception =>
                    logger.debug("staging orphan scan for doctor failed: " + e.getMessage)
                    (Nil, 0L)
            }

        def chatSlots(): Seq[(String, AIConfig)] = {
            val env = DatrisEnvironment.values
            val primary = Option(env.aiConfig).map(c => ("ai-primary", c)).toSeq
            val codegen = env.codegenAiConfig.map(c => ("codegen", c)).toSeq
            primary ++ codegen
        }

        def probeChat(config: AIConfig, timeoutMs: Int): (Int, String) =
            ai.datris.util.aiutil.AIHttp.probeModel(config, timeoutMs)

        def codegenRunnerEnabled(): Boolean = CodeGenRunner.enabled

        def codegenRunnerHealth(): Either[String, Unit] = CodeGenRunner.health()

        def tapRunnerEnabled(): Boolean = TapScriptRunner.useTapRunner

        def tapRunnerHealth(): Either[String, (Int, Int)] = {
            val url = TapScriptRunner.tapRunnerUrl.stripSuffix("/") + "/health"
            try {
                val (status, body) = httpGet(url, 3000)
                if (status != 200) Left("GET " + url + " answered " + status)
                else {
                    val o = com.google.gson.JsonParser.parseString(body).getAsJsonObject
                    if (o.has("active") && o.has("maxConcurrent")) Right((o.get("active").getAsInt, o.get("maxConcurrent").getAsInt))
                    else Left("datris-tap-runner does not report slot use: its image is older than this server")
                }
            } catch {
                case e: Exception => Left(e.getClass.getSimpleName + Option(e.getMessage).map(": " + _).getOrElse("") + " (" + url + ")")
            }
        }

        def recentRunnerWaits(hours: Int): (Int, Int, Long) = {
            val env = DatrisEnvironment.current
            val since = System.currentTimeMillis() - hours * 3600L * 1000L
            val gson = new Gson()
            val logs: List[TapRunLog] =
                try NoSQLDbUtil.getItemsSinceAsJSON(env.tapLogTableName, "created_at", since, 10000).flatMap { json =>
                        try {
                            val row = com.google.gson.JsonParser.parseString(json).getAsJsonObject
                            if (row.has("value")) Some(gson.fromJson(row.get("value"), classOf[TapRunLog])) else None
                        } catch { case _: Exception => None }
                    }
                catch { case e: Exception => logger.debug("runner wait scan for doctor failed: " + e.getMessage); Nil }
            def turnedAway(l: TapRunLog) = l.error != null && l.error.startsWith("Tap runner busy")
            // Disjoint: a turned-away run also has queuedMs > 0 but counts only as rejected.
            val waited = logs.filter(l => l.queuedMs > 0 && !turnedAway(l))
            val rejected = logs.count(turnedAway)
            (waited.size, rejected, if (waited.isEmpty) 0L else waited.map(_.queuedMs).max)
        }

        override def postgresQueryLogin(): Option[Either[String, (String, Boolean)]] = {
            val secrets =
                try SecretsRetrieverUtil.postgresSecrets()
                catch {
                    case e: Exception =>
                        logger.debug("Postgres query login check: the Postgres secret could not be read: " + e.getMessage)
                        return None
                }
            if (!SecretsRetrieverUtil.hasQueryLogin(secrets)) return None
            val login =
                try SecretsRetrieverUtil.postgresQueryLogin(secrets)
                catch { case e: ai.datris.model.DatrisException => return Some(Left(e.getMessage)) }
            Some(
                try {
                    Class.forName("org.postgresql.Driver")
                    val props = new java.util.Properties()
                    props.setProperty("user", login._1)
                    props.setProperty("password", login._2)
                    props.setProperty("connectTimeout", "5")
                    props.setProperty("loginTimeout", "5")
                    props.setProperty("socketTimeout", "5")
                    val url = PostgresSql.jdbcUrl(secrets.jdbcUrl, DatrisEnvironment.current.postgresDatabase)
                    val conn = java.sql.DriverManager.getConnection(url, props)
                    try {
                        conn.setReadOnly(true)
                        val stmt = conn.createStatement()
                        try {
                            stmt.setQueryTimeout(5)
                            val rs = stmt.executeQuery("SELECT current_user, rolsuper FROM pg_roles WHERE rolname = current_user")
                            try {
                                if (rs.next()) Right((rs.getString(1), rs.getBoolean(2)))
                                else Left("connected, but the role's rolsuper flag could not be read")
                            } finally rs.close()
                        } finally stmt.close()
                    } finally conn.close()
                } catch {
                    case e: Exception =>
                        Left("could not connect as the query login: " + LogRedactUtil.redactJdbcUrl(String.valueOf(e.getMessage)))
                }
            )
        }

        def probeEmbedding(provider: String, endpoint: String, model: String, apiKey: String, timeoutMs: Int): (Int, String) = {
            val env = DatrisEnvironment.values
            val key = AIUtil.resolveApiKey(apiKey, provider, env.multiTenant, env.environment)
            ai.datris.util.aiutil.AIHttp.probeEmbedding(provider, endpoint, model, key, timeoutMs)
        }
    }

    def runLive(mode: String, probeGroups: Set[String], clients: Map[String, String]): Report =
        run(mode, probeGroups, clients, LiveProbes, configuredSlots, ai.datris.build.sbt.BuildInfo.version)

    def runStartupLive(): Seq[CheckResult] =
        runStartup(LiveProbes, configuredSlots, ai.datris.build.sbt.BuildInfo.version)
}
