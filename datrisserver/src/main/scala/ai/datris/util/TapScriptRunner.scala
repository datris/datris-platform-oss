package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{DatrisEnvironment, DatrisException, TapConfig}
import org.slf4j.{Logger, LoggerFactory}

import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.sys.process._

case class TapScriptResult(records: String, recordCount: Int, error: String, logs: String = null, dataType: String = "json", columns: java.util.List[String] = null)

object TapScriptRunner {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private def scriptTimeoutSeconds: Int = DatrisEnvironment.current.tapScriptTimeoutSeconds

    private val WRAPPER_TEMPLATE =
        """import json, sys, os, importlib.util
          |# Redirect script's print() output to stderr so only JSON goes to stdout
          |_real_stdout = sys.stdout
          |sys.stdout = sys.stderr
          |spec = importlib.util.spec_from_file_location("tap", sys.argv[1])
          |mod = importlib.util.module_from_spec(spec)
          |spec.loader.exec_module(mod)
          |result = mod.fetch()
          |sys.stdout = _real_stdout
          |# Detect data type from result
          |if isinstance(result, list) and len(result) > 0 and isinstance(result[0], dict):
          |    # Normalize dict keys to strings (handles Timestamp, numpy keys)
          |    result = [{str(k): v for k, v in row.items()} for row in result if isinstance(row, dict)]
          |    data = json.dumps(result, default=str)
          |    data_type = "json"
          |elif isinstance(result, list) and len(result) > 0 and isinstance(result[0], (list, tuple)):
          |    data_type = "csv"
          |    data = json.dumps(result)
          |elif isinstance(result, str):
          |    trimmed = result.strip()
          |    if trimmed.startswith("<?xml") or trimmed.startswith("<"):
          |        data_type = "xml"
          |    elif trimmed.startswith("{") or trimmed.startswith("["):
          |        data_type = "json"
          |    else:
          |        data_type = "text"
          |    data = json.dumps(result)
          |elif isinstance(result, list):
          |    data_type = "json"
          |    data = json.dumps(result)
          |else:
          |    data_type = "json"
          |    data = json.dumps(result)
          |envelope = {"type": data_type, "data": json.loads(data) if data_type in ("json", "csv") else data}
          |print(json.dumps(envelope))
          |""".stripMargin

    def run(tapConfig: TapConfig): TapScriptResult = {
        logger.info("TapScriptRunner: executing tap: " + tapConfig.name)

        // Step 1: Install extra packages if specified
        installPackages(tapConfig)

        // Step 2: Read script from MinIO
        val env = DatrisEnvironment.current.environment
        val bucketName = env + "-config"
        val scriptContent = ObjectStoreUtil.readBucketObject(bucketName, tapConfig.scriptPath).getOrElse(
            throw new DatrisException("Tap script not found in object store: " + tapConfig.scriptPath)
        )

        // Step 3: Write script and wrapper to temp files
        val scriptFile: Path = Files.createTempFile("tap_script_", ".py")
        val wrapperFile: Path = Files.createTempFile("tap_wrapper_", ".py")

        try {
            Files.write(scriptFile, scriptContent.getBytes("UTF-8"))
            Files.write(wrapperFile, WRAPPER_TEMPLATE.getBytes("UTF-8"))

            // Step 4: Load secrets as env vars if configured
            val secretEnvVars: Seq[(String, String)] = if (tapConfig.secretName != null && tapConfig.secretName.nonEmpty) {
                val secretPath = DatrisEnvironment.current.environment + "/" + tapConfig.secretName
                logger.info("TapScriptRunner: loading secrets from: " + secretPath)
                SecretsUtil.getSecretMap(secretPath).map(_.asScala.filterNot(_._1 == "_type").toSeq).getOrElse(Seq.empty)
            } else Seq.empty

            // Always inject Datris platform env vars so scripts can call back into the platform.
            // Mongo db follows the same multi-tenant rule as MetadataAPIController.scala (line 226):
            // in multi-tenant mode the tenant name IS the mongo database name.
            val mongoDatabase = if (DatrisEnvironment.current.multiTenant)
                DatrisEnvironment.current.environment
            else
                DatrisEnvironment.current.mongoDbConfig.database
            val platformEnvVars = Seq(
                "DATRIS_POSTGRES_DATABASE" -> DatrisEnvironment.current.postgresDatabase,
                "DATRIS_MONGODB_DATABASE" -> mongoDatabase,
                "DATRIS_PLATFORM_HOST" -> "localhost",
                "DATRIS_PLATFORM_PORT" -> "8080"
            )
            val allEnvVars = platformEnvVars ++ secretEnvVars

            // Step 5: Execute the wrapper
            val secretValues = secretEnvVars.map(_._2)
            val (rawOutput, logs) = executeWithTimeout(wrapperFile.toString, scriptFile.toString, scriptTimeoutSeconds, allEnvVars, secretValues)
            logger.info("TapScriptRunner: script executed, output length: " + rawOutput.length + " chars")
            if (logs.nonEmpty) logger.info("TapScriptRunner: script logs:\n" + maskSecrets(logs, secretValues))

            // Step 5: Parse envelope to extract data type and records
            val gson = new com.google.gson.Gson
            val envelope = gson.fromJson(rawOutput, classOf[java.util.Map[String, Any]])
            val dataType = Option(envelope.get("type")).map(_.toString).getOrElse("json")
            val data = envelope.get("data")
            val dataJson = gson.toJson(data)
            val recordCount = if (dataType == "json" || dataType == "csv") countRecords(dataJson) else 1

            // For CSV-shaped data: normalize column names so they pass PipelineValidatorUtil
            // (which only allows [A-Za-z0-9_]+) and so downstream SQL doesn't need quoting.
            // Rewrites BOTH the records (key by key) and the extracted columns array.
            // No-op for json/xml/text — those go to mongo destinations as raw blobs.
            val (normalizedDataJson, columns): (String, java.util.List[String]) = if (dataType == "csv" && recordCount > 0) {
                try {
                    val list = gson.fromJson(dataJson, classOf[java.util.List[java.util.Map[String, Any]]])
                    if (list != null && !list.isEmpty) {
                        // Compute the union of keys across ALL records (first-seen order).
                        // Some sources emit variable-shape rows; using only the first
                        // record's keys silently drops columns that appear in later records and
                        // breaks downstream pipeline schemas built from this list.
                        val seen = scala.collection.mutable.LinkedHashSet[String]()
                        list.asScala.foreach(row => row.keySet().asScala.foreach(seen.add))
                        val allKeys = seen.toList
                        val keyMap: Map[String, String] = allKeys.map(k => k -> normalizeColumnName(k)).toMap
                        val normalizedCols = new java.util.ArrayList[String](allKeys.map(keyMap).asJava)
                        val rewrittenList = new java.util.ArrayList[java.util.Map[String, Any]](list.size())
                        list.asScala.foreach { row =>
                            val newRow = new java.util.LinkedHashMap[String, Any]()
                            // Insert in union order so every record has the same key order;
                            // missing keys become null.
                            allKeys.foreach { k =>
                                val normalized = keyMap(k)
                                if (row.containsKey(k)) newRow.put(normalized, row.get(k))
                                else newRow.put(normalized, null)
                            }
                            rewrittenList.add(newRow)
                        }
                        (gson.toJson(rewrittenList), normalizedCols)
                    } else (dataJson, null)
                } catch { case _: Exception => (dataJson, null) }
            } else (dataJson, null)

            logger.info("TapScriptRunner: dataType=" + dataType + ", fetched " + recordCount + " records" +
                (if (columns != null) ", columns=" + columns else ""))

            TapScriptResult(normalizedDataJson, recordCount, null, if (logs.nonEmpty) logs else null, dataType, columns)
        } catch {
            case e: DatrisException =>
                logger.error("TapScriptRunner failed: " + e.getMessage)
                TapScriptResult(null, 0, e.getMessage)
            case e: Exception =>
                logger.error("TapScriptRunner failed", e)
                TapScriptResult(null, 0, e.getMessage)
        } finally {
            Files.deleteIfExists(scriptFile)
            Files.deleteIfExists(wrapperFile)
        }
    }

    private def installPackages(tapConfig: TapConfig): Unit = {
        if (tapConfig.packages != null && !tapConfig.packages.isEmpty) {
            val packageList = tapConfig.packages.asScala.mkString(" ")
            logger.info("TapScriptRunner: installing packages: " + packageList)

            val stdout = new StringBuilder
            val stderr = new StringBuilder
            val processLogger = ProcessLogger(
                line => stdout.append(line).append("\n"),
                line => stderr.append(line).append("\n")
            )

            val exitCode = Process(Seq("pip3", "install", "--quiet", "--break-system-packages") ++ tapConfig.packages.asScala).!(processLogger)
            if (exitCode != 0) {
                throw new DatrisException("Failed to install pip packages: " + stderr.toString.take(500))
            }
        }
    }

    private def executeWithTimeout(wrapperPath: String, scriptPath: String, timeoutSec: Int, envVars: Seq[(String, String)] = Seq.empty, secretValues: Seq[String] = Seq.empty): (String, String) = {
        val stdout = new StringBuilder
        val stderr = new StringBuilder
        val processLogger = ProcessLogger(
            line => stdout.append(line).append("\n"),
            line => stderr.append(line).append("\n")
        )

        val process = Process(Seq("python3", wrapperPath, scriptPath), None, envVars: _*)
        val future = Future {
            process.!(processLogger)
        }

        try {
            val exitCode = Await.result(future, timeoutSec.seconds)
            if (exitCode != 0) {
                val errOutput = maskSecrets(stderr.toString.take(1000), secretValues)
                logger.error("Tap script exited with code " + exitCode + ": " + errOutput)
                throw new DatrisException("Tap script failed (exit code " + exitCode + "): " + errOutput)
            }
            (stdout.toString.trim, stderr.toString.trim)
        } catch {
            case _: java.util.concurrent.TimeoutException =>
                throw new DatrisException("Tap script timed out after " + timeoutSec + " seconds")
            case e: DatrisException => throw e
            case e: Exception =>
                throw new DatrisException("Tap script execution error: " + maskSecrets(e.getMessage, secretValues))
        }
    }

    /** Spell-out mapping for special characters that have semantic meaning in
      * column names. Each spell-out is wrapped in underscores so word
      * boundaries are preserved regardless of surrounding context (e.g.
      * `Surprise%` and `Surprise(%)` both yield `surprise_percent`). The
      * collapse + strip steps in `normalizeColumnName` clean up duplicates.
      *
      * Order doesn't matter functionally, but is grouped here by semantic theme.
      */
    private val SPELL_OUT_CHARS: Seq[(String, String)] = Seq(
        // Math / comparison
        "%" -> "_percent_",
        "+" -> "_plus_",
        "*" -> "_star_",
        "^" -> "_pow_",
        "=" -> "_equals_",
        "<" -> "_lt_",
        ">" -> "_gt_",
        "/" -> "_per_",
        // Currency / units
        "$" -> "_dollars_",
        "#" -> "_num_",
        // Logical / connector
        "&" -> "_and_",
        "@" -> "_at_",
        "~" -> "_approx_",
        "!" -> "_bang_"
    )

    /** Convert a source column name into a SQL-safe identifier matching
      * PipelineValidatorUtil's `[A-Za-z0-9_]+` regex.
      *
      * Steps:
      *   1. Spell out semantically meaningful special characters (see
      *      SPELL_OUT_CHARS) into `_word_` tokens.
      *   2. Replace any remaining run of non-`[A-Za-z0-9_]` characters with `_`.
      *   3. Collapse runs of `_` into a single `_`.
      *   4. Strip leading/trailing `_`.
      *   5. Lowercase.
      *   6. Fall back to `"col"` if the result is empty (e.g. input was `___`).
      *
      * Examples:
      *   "EPS Estimate"  -> "eps_estimate"
      *   "Reported EPS"  -> "reported_eps"
      *   "Surprise(%)"   -> "surprise_percent"
      *   "Surprise%"     -> "surprise_percent"   (no-paren form)
      *   "Order#"        -> "order_num"
      *   "miles/hour"    -> "miles_per_hour"
      *   "R&D Spending"  -> "r_and_d_spending"
      *   "ticker"        -> "ticker"             (no-op for already-clean names)
      */
    private[util] def normalizeColumnName(name: String): String = {
        if (name == null || name.isEmpty) return name
        var s = name
        SPELL_OUT_CHARS.foreach { case (ch, word) => s = s.replace(ch, word) }
        s = s.replaceAll("[^A-Za-z0-9_]+", "_")
        s = s.replaceAll("_+", "_")
        s = s.stripPrefix("_").stripSuffix("_")
        s = s.toLowerCase
        if (s.isEmpty) "col" else s
    }

    private def maskSecrets(text: String, secretValues: Seq[String]): String = {
        if (text == null || text.isEmpty) return text
        secretValues.foldLeft(text) { (acc, secret) =>
            if (secret == null || secret.length < 4) acc
            else acc.replace(secret, "••••••••")
        }
    }

    private def countRecords(json: String): Int = {
        if (json == null || json.isEmpty) return 0
        val trimmed = json.trim
        if (!trimmed.startsWith("[")) return 0
        val gson = new com.google.gson.Gson
        val list = gson.fromJson(trimmed, classOf[java.util.List[Any]])
        if (list == null) 0 else list.size()
    }
}
