package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{CapabilityRoutes, RouteCheck}
import ai.datris.model._
import ai.datris.util._
import com.google.gson.{JsonObject, JsonParser}
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: CodeGen scripts 1 (plans/stories/codegen-script-pinning.md), the
  * API surface: the save response, the read endpoint, the regenerate
  * endpoint and their capability routes. No Spring and no Mongo: response
  * bodies are built by pure helpers from a PipelineScripts instance with
  * in-memory records and store (see PipelineScriptsSpec for that seam).
  *
  * Pinned seam:
  * {{{
  * object PipelineAPIController {
  *     /** POST /api/v1/pipeline body: `warnings` (the given ones plus one per
  *       * pending script, naming its reason) and `codegenScripts`
  *       * ([{kind, status, pendingReason?, generatedAt?, model?}]). */
  *     def saveResponseBody(warnings: List[String], outcomes: List[PipelineScripts.Outcome]): JsonObject
  *     /** GET /api/v1/pipelines/{name}/codegen-scripts body: `scripts`, one entry per
  *       * AI kind the pipeline has, fields kind, instruction, script, generatedAt,
  *       * model, modelIsCurrent, status, pendingReason, origin, storage. */
  *     def codegenScriptsBody(config: PipelineConfig, scripts: PipelineScripts): JsonObject
  * }
  * class PipelineAPIController {
  *     // POST /api/v1/pipelines/{name}/codegen-scripts/{kind}/regenerate
  *     // An unknown kind is rejected with 400 before any config read.
  *     def regenerateCodegenScript(apiKey: String, name: String, kind: String, request: HttpServletRequest): ResponseEntity[String]
  * }
  * }}}
  */
class CodeGenScriptsApiSpec extends AnyFunSuite {

    private class MemRecords extends CodeGenScriptRecords {
        val rows = mutable.LinkedHashMap[(String, String), CodeGenScript]()
        override def read(pipeline: String, kind: String): Option[CodeGenScript] = rows.get((pipeline, kind))
        override def write(record: CodeGenScript): Unit = rows((record.pipeline, record.kind)) = record
        override def delete(pipeline: String, kind: String): Unit = rows.remove((pipeline, kind))
    }

    private class MemStore extends CodeStore {
        val objects = mutable.LinkedHashMap[String, String]()
        override def storage: String = "minio"
        override def readScript(ref: ScriptRef): Option[String] = Option(ref.scriptPath).flatMap(objects.get)
        override def storeScript(name: String, script: String, prior: ScriptRef, actor: String): StoredScript = {
            val path = "pipeline-scripts/orders/" + name + "_" + (objects.size + 1) + ".py"
            objects(path) = script
            StoredScript("minio", scriptPath = path)
        }
        override def deleteScript(ref: ScriptRef): Unit = Option(ref.scriptPath).foreach(objects.remove)
        override def scriptExists(ref: ScriptRef): Boolean = readScript(ref).isDefined
    }

    private val rule = "age must be a positive whole number"
    private val instruction = "add a column age_band that buckets age by decade"

    private val cfg: PipelineConfig =
        PipelineConfig(
            name = "orders",
            source = Source(
                schemaProperties = SchemaProperties(null, List(SchemaField("full_name", "string"), SchemaField("age", "int")).asJava),
                fileAttributes = FileAttributes(csvAttributes = CsvAttributes(delimiter = ","))
            ),
            dataQuality = ai.datris.model.DataQuality(aiRule = AIRule(rule)),
            transformation = ai.datris.model.Transformation(aiTransformation = AITransformation(instruction)),
            destination = Destination(database = Database(dbName = "d", schema = "public", table = "t", usePostgres = true))
        )

    private object Env extends AiSampleValuesMarkers

    private def withEnv[A](body: => A): A = {
        TenantContext.set(Env.testEnv)
        try body
        finally TenantContext.clear()
    }

    /** Transformation generation fails (no key); the rule generates. */
    private def savedScripts(model: () => String = () => "model-a"): (PipelineScripts, List[PipelineScripts.Outcome]) = {
        val ai: (String, String) => String = (_, user) =>
            if (user.contains(instruction)) throw new RuntimeException("No AI provider key is configured")
            else "print('[]')"
        val scripts = new PipelineScripts(new MemRecords, { val s = new MemStore; _ => s }, ai, model)
        val outcomes = withEnv(scripts.onSave(null, cfg, "todd"))
        (scripts, outcomes)
    }

    test("POST /pipeline returns codegenScripts with ready or pending") {
        val (_, outcomes) = savedScripts()
        val body = PipelineAPIController.saveResponseBody(List("Unity Catalog hint"), outcomes)
        val json = JsonParser.parseString(body.toString).getAsJsonObject

        val warnings = json.getAsJsonArray("warnings").asScala.map(_.getAsString).toList
        assert(warnings.contains("Unity Catalog hint"), s"existing warnings kept: $warnings")
        assert(warnings.exists(_.contains("No AI provider key is configured")), s"a pending script adds a warning with its reason: $warnings")

        val entries = json.getAsJsonArray("codegenScripts").asScala.map(_.getAsJsonObject).toList
        val byKind = entries.map(e => e.get("kind").getAsString -> e).toMap
        assert(byKind.keySet == Set("dataQuality", "transformation"), s"$entries")
        assert(byKind("dataQuality").get("status").getAsString == "ready")
        assert(byKind("transformation").get("status").getAsString == "pending")
        assert(byKind("transformation").get("pendingReason").getAsString.contains("No AI provider key is configured"))
    }

    test("POST /pipeline with no AI instruction returns an empty codegenScripts array") {
        val json = PipelineAPIController.saveResponseBody(Nil, Nil)
        assert(json.getAsJsonArray("warnings").size == 0)
        assert(json.has("codegenScripts") && json.getAsJsonArray("codegenScripts").size == 0)
    }

    test("GET returns both kinds") {
        var model = "model-a"
        val (scripts, _) = savedScripts(() => model)
        model = "model-b"
        val body: JsonObject = withEnv(PipelineAPIController.codegenScriptsBody(cfg, scripts))
        val entries = body.getAsJsonArray("scripts").asScala.map(_.getAsJsonObject).toList
        val byKind = entries.map(e => e.get("kind").getAsString -> e).toMap
        assert(byKind.keySet == Set("dataQuality", "transformation"), s"$entries")

        val dq = byKind("dataQuality")
        Seq("kind", "instruction", "script", "generatedAt", "model", "modelIsCurrent", "status", "origin", "storage").foreach { f =>
            assert(dq.has(f) && !dq.get(f).isJsonNull, s"dataQuality.$f present: $dq")
        }
        assert(dq.get("instruction").getAsString == rule)
        assert(dq.get("script").getAsString == "print('[]')")
        assert(dq.get("model").getAsString == "model-a")
        assert(!dq.get("modelIsCurrent").getAsBoolean, "the current setting differs from the model that wrote it")
        assert(dq.get("status").getAsString == "ready")
        assert(dq.get("origin").getAsString == "save")
        assert(dq.get("storage").getAsString == "minio")

        val tx = byKind("transformation")
        assert(tx.get("instruction").getAsString == instruction)
        assert(tx.get("status").getAsString == "pending")
        assert(tx.get("pendingReason").getAsString.contains("No AI provider key is configured"))
    }

    test("regenerate needs pipeline create") {
        assert(
            CapabilityRoutes.lookup("POST", "/api/v1/pipelines/orders/codegen-scripts/transformation/regenerate") ==
                RouteCheck.Require("pipeline", "create")
        )
        assert(
            CapabilityRoutes.lookup("POST", "/api/v1/pipelines/orders/codegen-scripts/dataQuality/regenerate") ==
                RouteCheck.Require("pipeline", "create")
        )
        assert(CapabilityRoutes.lookup("GET", "/api/v1/pipelines/orders/codegen-scripts") == RouteCheck.Require("pipeline", "read"))
    }

    test("unknown kind is 400") {
        val req = mock(classOf[HttpServletRequest])
        when(req.getMethod).thenReturn("POST")
        when(req.getRequestURI).thenReturn("/api/v1/pipelines/orders/codegen-scripts/bogus/regenerate")
        val r = withEnv(new PipelineAPIController().regenerateCodegenScript(null, "orders", "bogus", req))
        assert(r.getStatusCode.value == 400, r.getBody)
        val err = JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString
        assert(err.contains("bogus"), err)
        assert(err.contains("dataQuality") && err.contains("transformation"), s"names the valid kinds: $err")
    }
}
