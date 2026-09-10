package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{AIConfig, DatrisEnvironment}
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
        ms: Long = 0L
    )

    case class Report(mode: String, surface: Map[String, String], checks: Seq[CheckResult]) {
        def summary: Map[String, Int] =
            Seq(StatusOk, StatusWarn, StatusError, StatusSkip).map(s => s -> checks.count(_.status == s)).toMap

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

        protected def ok(detail: String): CheckResult = CheckResult(id, StatusOk, StatusOk, detail, "")
        protected def warn(detail: String, remediation: String): CheckResult = CheckResult(id, StatusWarn, StatusWarn, detail, remediation)
        protected def error(detail: String, remediation: String): CheckResult = CheckResult(id, StatusError, StatusError, detail, remediation)
        protected def skip(detail: String): CheckResult = CheckResult(id, StatusSkip, StatusOk, detail, "")
    }

    /** The AI slot secret names the server was configured with. */
    case class Slots(aiPrimarySecret: String, codegenSecret: String, embeddingSecret: String)

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

        /** Plain GET → (status, body). Throws on connect/read failure. */
        def httpGet(url: String, timeoutMs: Int): (Int, String)

        /** (totalBytes, usableBytes) for the file store holding `path`. */
        def diskUsage(path: String): Option[(Long, Long)]
        def envSeen(names: Seq[String]): Map[String, Boolean]

        /** Chat slots configured on this server: (label, config). */
        def chatSlots(): Seq[(String, AIConfig)]

        /** Minimal chat request → (status, bodySnippet). Throws on timeout/IO. */
        def probeChat(config: AIConfig, timeoutMs: Int): (Int, String)

        /** One-input embedding request → (status, bodySnippet). Throws on timeout/IO. */
        def probeEmbedding(provider: String, endpoint: String, model: String, apiKey: String, timeoutMs: Int): (Int, String)
    }

    private val Day = 86400L
    private val SevenDays = 7 * Day
    private val Hours720 = 720L * 3600L
    private val Hours87600 = 87600L * 3600L

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
                    if (ttl <= 0 && period <= 0) ok("token has no expiry")
                    else if (ttl < SevenDays) error(detail + " — expires in " + humanDuration(ttl), remediation)
                    else if (period >= Hours87600 && ttl < Hours720) warn(detail + " — clamped", remediation)
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

    /** Which hardening env vars reached the JVM. Always ok — evidence for the
      * CLI's `env.not_forwarded` check, and a line in the report so a
      * documented-but-unforwarded var is visible without shelling in. */
    class EnvSeenCheck(probes: Probes) extends Check {
        val id = "env.seen"
        val startupSafe = true
        val names = Seq("DATRIS_ENV", "DATRIS_ALLOW_PLAINTEXT_DB", "DATRIS_ALLOW_PRIVATE_EGRESS", "TAPMAXOUTPUTMB", "VAULT_TOKEN_FILE")
        def run(): CheckResult = {
            val seen = probes.envSeen(names)
            val present = names.filter(n => seen.getOrElse(n, false))
            val absent = names.filterNot(n => seen.getOrElse(n, false))
            ok("seen: " + (if (present.isEmpty) "none" else present.mkString(", ")) +
                (if (absent.nonEmpty) "; unset: " + absent.mkString(", ") else ""))
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

    // ------------------------------------------------------------ assembly

    /** Every server-side check, in report order. */
    def checks(probes: Probes, slots: Slots, serverVersion: String, clients: Map[String, String], startup: Boolean = false): Seq[Check] =
        Seq(
            new VaultTokenTtlCheck(probes),
            new VaultAiSlotsCheck(probes, slots),
            new JdbcMssqlDriverCheck(probes),
            new AiEmbeddingModelCheck(probes, slots, startup),
            new DiskUsageCheck(probes, Seq(System.getProperty("user.dir"), System.getProperty("java.io.tmpdir"))),
            new VersionSkewCheck(serverVersion, clients),
            new EnvSeenCheck(probes),
            new AiModelReachableCheck(probes, slots)
        )

    /** Run one check, timing it and turning a thrown exception into an
      * error row rather than a failed report. */
    def runOne(check: Check): CheckResult = {
        val start = System.currentTimeMillis()
        val r =
            try check.run()
            catch {
                case e: Exception =>
                    CheckResult(
                        check.id,
                        StatusError,
                        StatusError,
                        "check failed: " + e.getClass.getSimpleName + ": " + e.getMessage,
                        "This is a doctor bug — report it with the server log."
                    )
            }
        r.copy(ms = System.currentTimeMillis() - start)
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
        val results = selected.map(runOne)
        Report(if (quick) "quick" else "full", Map("server" -> serverVersion) ++ clients, results)
    }

    /** Boot-time subset: one warn line per non-ok check. Anything that
      * throws is caught here — a doctor bug must never block boot. */
    def runStartup(probes: Probes, slots: Slots, serverVersion: String): Seq[CheckResult] = {
        try {
            val results = checks(probes, slots, serverVersion, Map.empty, startup = true).filter(c => c.startupSafe && c.optInGroup.isEmpty).map(runOne)
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

        def chatSlots(): Seq[(String, AIConfig)] = {
            val env = DatrisEnvironment.values
            val primary = Option(env.aiConfig).map(c => ("ai-primary", c)).toSeq
            val codegen = env.codegenAiConfig.map(c => ("codegen", c)).toSeq
            primary ++ codegen
        }

        def probeChat(config: AIConfig, timeoutMs: Int): (Int, String) =
            ai.datris.util.aiutil.AIHttp.probeModel(config, timeoutMs)

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
