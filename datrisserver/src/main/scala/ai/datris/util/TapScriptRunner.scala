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
    private val SCRIPT_TIMEOUT_SECONDS = 300

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
          |    sample = result[0]
          |    is_flat = all(isinstance(v, (str, int, float, bool, type(None))) for v in sample.values())
          |    data_type = "csv" if is_flat else "json"
          |    data = json.dumps(result)
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

            // Step 4: Execute the wrapper
            val (rawOutput, logs) = executeWithTimeout(wrapperFile.toString, scriptFile.toString, SCRIPT_TIMEOUT_SECONDS)
            logger.info("TapScriptRunner: script executed, output length: " + rawOutput.length + " chars")
            if (logs.nonEmpty) logger.info("TapScriptRunner: script logs:\n" + logs)

            // Step 5: Parse envelope to extract data type and records
            val gson = new com.google.gson.Gson
            val envelope = gson.fromJson(rawOutput, classOf[java.util.Map[String, Any]])
            val dataType = Option(envelope.get("type")).map(_.toString).getOrElse("json")
            val data = envelope.get("data")
            val dataJson = gson.toJson(data)
            val recordCount = if (dataType == "json" || dataType == "csv") countRecords(dataJson) else 1

            // Extract columns from first record if csv (list of dicts with column keys)
            val columns: java.util.List[String] = if (dataType == "csv" && recordCount > 0) {
                try {
                    val list = gson.fromJson(dataJson, classOf[java.util.List[java.util.Map[String, Any]]])
                    if (list != null && !list.isEmpty) {
                        new java.util.ArrayList[String](list.get(0).keySet())
                    } else null
                } catch { case _: Exception => null }
            } else null

            logger.info("TapScriptRunner: dataType=" + dataType + ", fetched " + recordCount + " records" +
                (if (columns != null) ", columns=" + columns else ""))

            TapScriptResult(dataJson, recordCount, null, if (logs.nonEmpty) logs else null, dataType, columns)
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

    private def executeWithTimeout(wrapperPath: String, scriptPath: String, timeoutSec: Int): (String, String) = {
        val stdout = new StringBuilder
        val stderr = new StringBuilder
        val processLogger = ProcessLogger(
            line => stdout.append(line).append("\n"),
            line => stderr.append(line).append("\n")
        )

        val process = Process(Seq("python3", wrapperPath, scriptPath))
        val future = Future {
            process.!(processLogger)
        }

        try {
            val exitCode = Await.result(future, timeoutSec.seconds)
            if (exitCode != 0) {
                val errOutput = stderr.toString.take(1000)
                logger.error("Tap script exited with code " + exitCode + ": " + errOutput)
                throw new DatrisException("Tap script failed (exit code " + exitCode + "): " + errOutput)
            }
            (stdout.toString.trim, stderr.toString.trim)
        } catch {
            case _: java.util.concurrent.TimeoutException =>
                throw new DatrisException("Tap script timed out after " + timeoutSec + " seconds")
            case e: DatrisException => throw e
            case e: Exception =>
                throw new DatrisException("Tap script execution error: " + e.getMessage)
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
