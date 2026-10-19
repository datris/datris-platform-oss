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

    /** The rag-builder template's pipeline grants: create and read, no update. */
    private val createOnlyKey =
        ResolvedKey(None, "rag", Capability.parseList(Seq("pipeline:create", "pipeline:read")), isLegacyFullAccess = false)

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

    /** A repository store whose files were all hand-edited at head: a delete
      * is always refused (story pipeline-delete-drift-warning-and-404.md, Part 2). */
    private class EditedRepo extends CodeStore {
        val files = mutable.LinkedHashMap[String, String]()
        override def storage: String = "github"
        override def readScript(ref: ScriptRef): Option[String] = Option(ref.scriptRepoPath).flatMap(files.get)
        override def storeScript(name: String, script: String, prior: ScriptRef, actor: String): StoredScript = {
            val path = "taps/pipelines/orders/" + name + ".py"
            files(path) = script
            StoredScript("github", scriptRepoPath = path, scriptCommitSha = "%040x".format(files.size + 0xabc001))
        }
        override def deleteScript(ref: ScriptRef): Unit = Option(ref.scriptRepoPath).foreach(files.remove)
        override def scriptExists(ref: ScriptRef): Boolean = readScript(ref).isDefined
        override def deleteIfUnchanged(ref: ScriptRef): DeleteOutcome = DeleteOutcome.KeptEdited("f00dfeed")
    }

    test("a removal warning is in warnings and not in codegenScripts") {
        val repo = new EditedRepo
        val scripts = new PipelineScripts(
            new MemRecords,
            { val s = new MemStore; _ => s },
            (_, _) => "print('[]')",
            () => "model-a",
            repoStoreFor = _ => repo,
            defaultStorage = () => "github"
        )
        withEnv(scripts.onSave(null, cfg, "todd"))
        val withoutTx = cfg.copy(transformation = null)
        val result: PipelineScripts.SaveResult = withEnv(scripts.onSaveResult(cfg, withoutTx, "todd"))
        assert(result.warnings.size == 1, s"${result.warnings}")
        val removalWarning = result.warnings.head
        assert(removalWarning.contains("saved without"), removalWarning)

        val json = JsonParser.parseString(PipelineAPIController.saveResponseBody(List(removalWarning), result.outcomes).toString).getAsJsonObject
        val warnings = json.getAsJsonArray("warnings").asScala.map(_.getAsString).toList
        assert(warnings.contains(removalWarning), s"$warnings")
        val entries = json.getAsJsonArray("codegenScripts").asScala.map(_.getAsJsonObject).toList
        assert(!entries.exists(_.get("status").getAsString == "removed"), s"no removed status: $entries")
        assert(!entries.exists(_.get("kind").getAsString == "transformation"), s"no entry for the removed kind: $entries")
        assert(!entries.exists(e => e.has("warning") && e.get("warning").getAsString == removalWarning), s"$entries")
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

    // Story self-healing-5a: regenerate writes what the pipeline runs, so it
    // is a pipeline update (was pipeline create); a create-only key is denied.
    test("regenerate needs pipeline update") {
        assert(
            CapabilityRoutes.lookup("POST", "/api/v1/pipelines/orders/codegen-scripts/transformation/regenerate") ==
                RouteCheck.Require("pipeline", "update")
        )
        assert(
            CapabilityRoutes.lookup("POST", "/api/v1/pipelines/orders/codegen-scripts/dataQuality/regenerate") ==
                RouteCheck.Require("pipeline", "update")
        )
        assert(!createOnlyKey.grants("pipeline", "update"), "a pipeline:create-only key is refused (403)")
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

    // =====================================================================
    // Story: CodeGen scripts 2 (plans/stories/codegen-script-git-storage.md):
    // repository-backed scripts on the read endpoint, and pull.
    //
    // Pinned seam (PipelineScripts' repository parameters, `drift` and
    // `pull` are pinned in PipelineScriptsSpec):
    // {{{
    // object PipelineAPIController {
    //     /** GET body entries also carry repoPath, commitSha, drift (boolean,
    //       * always present) and headSha (when drift is true). */
    //     def codegenScriptsBody(config: PipelineConfig, scripts: PipelineScripts): JsonObject
    //     /** POST /api/v1/pipelines/{name}/codegen-scripts/{kind}/pull after key
    //       * and capability checks: 400 for an unknown kind or a built-in script,
    //       * 200 with the kind's GET entry on success. */
    //     def pullCodegenScriptWith(scripts: PipelineScripts, name: String, kind: String, actor: String): ResponseEntity[String]
    // }
    // }}}
    // =====================================================================

    /** One-file-per-path GitHub branch; every commit gets a new sha. */
    private class FakeRepo extends CodeStore {
        val versions = mutable.ListBuffer[(String, String, String)]() // (sha, path, content)
        override def storage: String = "github"
        def headSha: String = versions.lastOption.map(_._1).orNull
        private def head(path: String): Option[String] = versions.filter(_._2 == path).lastOption.map(_._3)
        def commit(path: String, content: String): String = {
            val sha = "%040x".format(versions.size + 0xfeed01)
            versions += ((sha, path, content))
            sha
        }
        override def readScript(ref: ScriptRef): Option[String] =
            versions.find(v => v._1 == ref.scriptCommitSha && v._2 == ref.scriptRepoPath).map(_._3)
        override def storeScript(name: String, script: String, prior: ScriptRef, actor: String): StoredScript = {
            val path = "taps/pipelines/orders/" + (if (name == "dataQuality") "data-quality.py" else "transformation.py")
            StoredScript("github", scriptRepoPath = path, scriptCommitSha = commit(path, script))
        }
        override def deleteScript(ref: ScriptRef): Unit = ()
        override def scriptExists(ref: ScriptRef): Boolean = readScript(ref).isDefined
        override def pullLatest(ref: ScriptRef): Option[(String, String)] = head(ref.scriptRepoPath).map(c => (c, headSha))
    }

    private def repoScripts(repo: FakeRepo): PipelineScripts = {
        val scripts = new PipelineScripts(
            new MemRecords,
            { val s = new MemStore; _ => s },
            (_, _) => "print('[]')",
            () => "model-a",
            repoStoreFor = _ => repo,
            defaultStorage = () => "github"
        )
        withEnv(scripts.onSave(null, cfg, "todd"))
        scripts
    }

    private def entries(body: JsonObject): Map[String, JsonObject] =
        body.getAsJsonArray("scripts").asScala.map(_.getAsJsonObject).map(e => e.get("kind").getAsString -> e).toMap

    test("GET reports drift and headSha") {
        val repo = new FakeRepo
        val scripts = repoScripts(repo)
        val before = entries(withEnv(PipelineAPIController.codegenScriptsBody(cfg, scripts)))
        Seq("dataQuality" -> "taps/pipelines/orders/data-quality.py", "transformation" -> "taps/pipelines/orders/transformation.py").foreach {
            case (k, path) =>
                val e = before(k)
                assert(e.get("storage").getAsString == "github", s"$e")
                assert(e.get("repoPath").getAsString == path, s"$e")
                assert(e.has("commitSha") && e.get("commitSha").getAsString.nonEmpty, s"$e")
                assert(e.has("drift") && !e.get("drift").getAsBoolean, s"no drift yet: $e")
        }
        val pinnedTx = before("transformation").get("commitSha").getAsString

        val editSha = repo.commit("taps/pipelines/orders/transformation.py", "print('hand edit')")
        val after = entries(withEnv(PipelineAPIController.codegenScriptsBody(cfg, scripts)))
        val tx = after("transformation")
        assert(tx.get("drift").getAsBoolean, s"$tx")
        assert(tx.get("headSha").getAsString == editSha, s"$tx")
        assert(tx.get("commitSha").getAsString == pinnedTx, "the recorded commit is unchanged by drift")
        assert(tx.get("script").getAsString == "print('[]')", "GET shows the recorded commit's script")
        assert(!after("dataQuality").get("drift").getAsBoolean, s"${after("dataQuality")}")
    }

    test("pull needs pipeline update") {
        Seq("transformation", "dataQuality").foreach { k =>
            assert(
                CapabilityRoutes.lookup("POST", "/api/v1/pipelines/orders/codegen-scripts/" + k + "/pull") ==
                    RouteCheck.Require("pipeline", "update")
            )
        }
        assert(!createOnlyKey.grants("pipeline", "update"), "a pipeline:create-only key is refused (403)")
    }

    test("pull on a built-in script is 400") {
        val (scripts, _) = savedScripts() // built-in store: dataQuality ready in "minio"
        assert(scripts.record("orders", "dataQuality").exists(_.storage == "minio"))
        val r = withEnv(PipelineAPIController.pullCodegenScriptWith(scripts, "orders", "dataQuality", "todd"))
        assert(r.getStatusCode.value == 400, r.getBody)
        val err = JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString
        assert(err.toLowerCase.contains("repository"), s"says the script is not repository-backed: $err")
        assert(scripts.record("orders", "dataQuality").exists(_.storage == "minio"), "record untouched")

        val bogus = withEnv(PipelineAPIController.pullCodegenScriptWith(scripts, "orders", "bogus", "todd"))
        assert(bogus.getStatusCode.value == 400, bogus.getBody)

        // A repository-backed script pulls with 200.
        val repo = new FakeRepo
        val rs = repoScripts(repo)
        val editSha = repo.commit("taps/pipelines/orders/transformation.py", "print('hand edit')")
        val ok = withEnv(PipelineAPIController.pullCodegenScriptWith(rs, "orders", "transformation", "todd"))
        assert(ok.getStatusCode.value == 200, ok.getBody)
        val e = JsonParser.parseString(ok.getBody).getAsJsonObject
        assert(e.get("commitSha").getAsString == editSha, s"$e")
        assert(e.get("origin").getAsString == "repository", s"$e")
    }

    // =====================================================================
    // Story: Restore honours archived CodeGen scripts
    // (plans/stories/multi-env-3-restore-import-codegen-scripts.md):
    // the GET entry also carries `fingerprint`, so a caller can see what a
    // restore wrote.
    // =====================================================================

    test("codegenScriptsBody entry carries fingerprint equal to the record's") {
        val (scripts, _) = savedScripts()
        val byKind = entries(withEnv(PipelineAPIController.codegenScriptsBody(cfg, scripts)))
        val rec = scripts.record("orders", "dataQuality").getOrElse(fail("no dataQuality record"))
        assert(rec.fingerprint != null && rec.fingerprint.nonEmpty)
        val dq = byKind("dataQuality")
        assert(dq.has("fingerprint") && !dq.get("fingerprint").isJsonNull, s"fingerprint present: $dq")
        assert(dq.get("fingerprint").getAsString == rec.fingerprint, s"$dq")
        // A pending record carries its fingerprint as well.
        val txRec = scripts.record("orders", "transformation").get
        assert(byKind("transformation").get("fingerprint").getAsString == txRec.fingerprint, s"${byKind("transformation")}")
    }

    // =====================================================================
    // Story: Version history for stored CodeGen scripts
    // (plans/stories/self-healing-5a-script-versions.md), Acceptance bullet 2.
    //
    // Pinned seam (the controller's route methods do the key, pipeline-404
    // and owner-scope checks, then call these with PipelineScripts):
    // {{{
    // // GET  /api/v1/pipelines/{name}/codegen-scripts/{kind}/versions
    // // GET  /api/v1/pipelines/{name}/codegen-scripts/{kind}/versions/{version}
    // // GET  /api/v1/pipelines/{name}/codegen-scripts/{kind}/versions/{version}/diff?against=N
    // // POST /api/v1/pipelines/{name}/codegen-scripts/{kind}/versions/{version}/restore
    // object CodeGenScriptVersionAPIController {
    //     /** 200 JSON array, newest first: version, createdAt, createdBy, changeNote,
    //       * origin, model, commitSha, fingerprintMatchesCurrent, isLive. 400 unknown kind. */
    //     def listVersionsWith(scripts: PipelineScripts, config: PipelineConfig, kind: String): ResponseEntity[String]
    //     /** 200 the list fields plus script, fingerprint, generatedAt, contractVersion; 404 unknown version. */
    //     def getVersionWith(scripts: PipelineScripts, config: PipelineConfig, kind: String, version: Int): ResponseEntity[String]
    //     /** 200 {from: against, to: version, script: {before, after, lines: [{type, text}]}}; 404 unknown version. */
    //     def diffVersionsWith(scripts: PipelineScripts, config: PipelineConfig, kind: String, version: Int, against: Int): ResponseEntity[String]
    //     /** 200 the kind's codegenScriptEntry; 404 unknown version; 400 unknown kind;
    //       * 409 Left containing ConflictMarker; 422 fingerprint/contract refusal. Errors are {"error": "..."}. */
    //     def restoreVersionWith(scripts: PipelineScripts, config: PipelineConfig, kind: String, version: Int, actor: String): ResponseEntity[String]
    // }
    // }}}
    // codegenScriptEntry gains `version` (= record.scriptVersion, omitted when 0).
    // =====================================================================

    private class MemVersions extends CodeGenScriptVersions {
        val rows = mutable.ListBuffer[CodeGenScriptVersion]()
        override def listVersions(pipeline: String, kind: String): List[CodeGenScriptVersion] =
            rows.filter(v => v.pipeline == pipeline && v.kind == kind).sortBy(_.version).toList
        override def latestVersion(pipeline: String, kind: String): Int = (0 :: listVersions(pipeline, kind).map(_.version)).max
        override def get(pipeline: String, kind: String, version: Int): Option[CodeGenScriptVersion] =
            listVersions(pipeline, kind).find(_.version == version)
        override def append(v: CodeGenScriptVersion): Unit = rows += v
        override def prune(pipeline: String, kind: String, keep: Int): Unit =
            if (keep > 0) rows --= listVersions(pipeline, kind).dropRight(keep)
        override def deleteAllForPipeline(pipeline: String): Unit = rows --= rows.filter(_.pipeline == pipeline).toList
    }

    private val dqOnly: PipelineConfig = cfg.copy(transformation = null)

    /** Every model call returns a distinct two-line script sharing its first line. */
    private def versionedScripts(repo: CodeStore = null): PipelineScripts = {
        var n = 0
        val ai: (String, String) => String = (_, _) => { n += 1; "import json\nprint('v" + n + "')" }
        if (repo == null) new PipelineScripts(new MemRecords, { val s = new MemStore; _ => s }, ai, () => "model-a", versions = new MemVersions)
        else
            new PipelineScripts(
                new MemRecords,
                { val s = new MemStore; _ => s },
                ai,
                () => "model-a",
                repoStoreFor = _ => repo,
                defaultStorage = () => "github",
                versions = new MemVersions
            )
    }

    private def json(r: org.springframework.http.ResponseEntity[String]): JsonObject = JsonParser.parseString(r.getBody).getAsJsonObject

    private def errorOf(r: org.springframework.http.ResponseEntity[String]): String = json(r).get("error").getAsString

    test("version routes: list, get and diff are pipeline reads; restore is a pipeline update") {
        val base = "/api/v1/pipelines/orders/codegen-scripts/transformation/versions"
        assert(CapabilityRoutes.lookup("GET", base) == RouteCheck.Require("pipeline", "read"))
        assert(CapabilityRoutes.lookup("GET", base + "/2") == RouteCheck.Require("pipeline", "read"))
        assert(CapabilityRoutes.lookup("GET", base + "/2/diff") == RouteCheck.Require("pipeline", "read"))
        assert(CapabilityRoutes.lookup("POST", base + "/1/restore") == RouteCheck.Require("pipeline", "update"))
        assert(!createOnlyKey.grants("pipeline", "update"))
    }

    test("GET versions lists newest first with isLive on the latest and fingerprintMatchesCurrent") {
        val scripts = versionedScripts()
        withEnv(scripts.onSave(null, dqOnly, "todd")) // v1
        val changed = dqOnly.copy(dataQuality = ai.datris.model.DataQuality(aiRule = AIRule("age must be between 0 and 120")))
        withEnv(scripts.onSave(dqOnly, changed, "todd")) // v2
        val r = withEnv(CodeGenScriptVersionAPIController.listVersionsWith(scripts, changed, "dataQuality"))
        assert(r.getStatusCode.value == 200, r.getBody)
        val arr = JsonParser.parseString(r.getBody).getAsJsonArray.asScala.map(_.getAsJsonObject).toList
        assert(arr.map(_.get("version").getAsInt) == List(2, 1), s"newest first: $arr")
        Seq("version", "createdAt", "createdBy", "changeNote", "origin", "model", "fingerprintMatchesCurrent", "isLive").foreach { f =>
            assert(arr.head.has(f), s"$f present: ${arr.head}")
        }
        assert(arr.head.has("commitSha"), s"commitSha key present (null for built-in): ${arr.head}")
        assert(arr.head.get("isLive").getAsBoolean && !arr(1).get("isLive").getAsBoolean, s"$arr")
        assert(arr.head.get("fingerprintMatchesCurrent").getAsBoolean, s"${arr.head}")
        assert(!arr(1).get("fingerprintMatchesCurrent").getAsBoolean, s"${arr(1)}")
        assert(arr.head.get("origin").getAsString == "save" && arr.head.get("createdBy").getAsString == "todd")
        assert(!arr.head.has("script"), "the list is lightweight")

        val bogus = withEnv(CodeGenScriptVersionAPIController.listVersionsWith(scripts, changed, "bogus"))
        assert(bogus.getStatusCode.value == 400, bogus.getBody)
    }

    test("GET versions/{n} carries the script text; an unknown version is 404") {
        val scripts = versionedScripts()
        withEnv(scripts.onSave(null, dqOnly, "todd"))
        val r = withEnv(CodeGenScriptVersionAPIController.getVersionWith(scripts, dqOnly, "dataQuality", 1))
        assert(r.getStatusCode.value == 200, r.getBody)
        val o = json(r)
        assert(o.get("version").getAsInt == 1)
        assert(o.get("script").getAsString == "import json\nprint('v1')", s"$o")
        Seq("fingerprint", "generatedAt", "contractVersion").foreach(f => assert(o.has(f) && !o.get(f).isJsonNull, s"$f: $o"))
        val missing = withEnv(CodeGenScriptVersionAPIController.getVersionWith(scripts, dqOnly, "dataQuality", 7))
        assert(missing.getStatusCode.value == 404, missing.getBody)
    }

    test("GET versions/2/diff?against=1 has script lines typed add and del") {
        val scripts = versionedScripts()
        withEnv(scripts.onSave(null, dqOnly, "todd"))
        assert(withEnv(scripts.regenerate(dqOnly, "dataQuality", "todd")).isRight)
        val r = withEnv(CodeGenScriptVersionAPIController.diffVersionsWith(scripts, dqOnly, "dataQuality", 2, 1))
        assert(r.getStatusCode.value == 200, r.getBody)
        val o = json(r)
        assert(o.get("from").getAsInt == 1 && o.get("to").getAsInt == 2, s"$o")
        val script = o.getAsJsonObject("script")
        assert(script.get("before").getAsString == "import json\nprint('v1')", s"$script")
        assert(script.get("after").getAsString == "import json\nprint('v2')", s"$script")
        val lines = script.getAsJsonArray("lines").asScala.map(_.getAsJsonObject).map(l => (l.get("type").getAsString, l.get("text").getAsString)).toList
        assert(lines.contains(("ctx", "import json")), s"$lines")
        assert(lines.contains(("del", "print('v1')")), s"$lines")
        assert(lines.contains(("add", "print('v2')")), s"$lines")
        val missing = withEnv(CodeGenScriptVersionAPIController.diffVersionsWith(scripts, dqOnly, "dataQuality", 2, 9))
        assert(missing.getStatusCode.value == 404, missing.getBody)
    }

    test("POST restore is 200 with the entry, and the GET entry's version is the new number") {
        val scripts = versionedScripts()
        withEnv(scripts.onSave(null, dqOnly, "todd")) // v1
        withEnv(scripts.regenerate(dqOnly, "dataQuality", "todd")) // v2
        val before = entries(withEnv(PipelineAPIController.codegenScriptsBody(dqOnly, scripts)))("dataQuality")
        assert(before.has("version") && before.get("version").getAsInt == 2, s"$before")

        val r = withEnv(CodeGenScriptVersionAPIController.restoreVersionWith(scripts, dqOnly, "dataQuality", 1, "ops"))
        assert(r.getStatusCode.value == 200, r.getBody)
        val body = json(r)
        assert(body.get("kind").getAsString == "dataQuality" && body.has("version") && body.get("version").getAsInt == 3, s"$body")
        assert(body.get("origin").getAsString == "restore-version", s"$body")
        assert(body.get("script").getAsString == "import json\nprint('v1')", s"$body")
        val after = entries(withEnv(PipelineAPIController.codegenScriptsBody(dqOnly, scripts)))("dataQuality")
        assert(after.has("version") && after.get("version").getAsInt == 3, s"$after")
    }

    test("POST restore of the live version is 200 with noop true and the current entry") {
        val scripts = versionedScripts()
        withEnv(scripts.onSave(null, dqOnly, "todd")) // v1
        withEnv(scripts.regenerate(dqOnly, "dataQuality", "todd")) // v2, live
        val live = scripts.record("orders", "dataQuality").get
        val r = withEnv(CodeGenScriptVersionAPIController.restoreVersionWith(scripts, dqOnly, "dataQuality", 2, "ops"))
        assert(r.getStatusCode.value == 200, r.getBody)
        val body = json(r)
        assert(body.has("noop") && body.get("noop").getAsBoolean, s"$body")
        assert(body.get("version").getAsInt == 2 && body.get("kind").getAsString == "dataQuality", s"$body")
        assert(scripts.record("orders", "dataQuality").get == live, "nothing written")
        val listed = JsonParser.parseString(withEnv(CodeGenScriptVersionAPIController.listVersionsWith(scripts, dqOnly, "dataQuality")).getBody).getAsJsonArray
        assert(listed.size == 2, s"$listed")
        // A real restore carries no noop.
        val real = json(withEnv(CodeGenScriptVersionAPIController.restoreVersionWith(scripts, dqOnly, "dataQuality", 1, "ops")))
        assert(!real.has("noop") && real.get("version").getAsInt == 3, s"$real")
    }

    test("POST restore: unknown version 404, unknown kind 400, mismatched fingerprint 422 with the reason") {
        val scripts = versionedScripts()
        withEnv(scripts.onSave(null, dqOnly, "todd")) // v1
        val changed = dqOnly.copy(dataQuality = ai.datris.model.DataQuality(aiRule = AIRule("age must be between 0 and 120")))
        withEnv(scripts.onSave(dqOnly, changed, "todd")) // v2

        val missing = withEnv(CodeGenScriptVersionAPIController.restoreVersionWith(scripts, changed, "dataQuality", 9, "ops"))
        assert(missing.getStatusCode.value == 404, missing.getBody)
        assert(errorOf(missing).contains("no version 9"), missing.getBody)

        val bogus = withEnv(CodeGenScriptVersionAPIController.restoreVersionWith(scripts, changed, "bogus", 1, "ops"))
        assert(bogus.getStatusCode.value == 400, bogus.getBody)
        assert(errorOf(bogus).contains("bogus"), bogus.getBody)

        val live = scripts.record("orders", "dataQuality").get
        val r = withEnv(CodeGenScriptVersionAPIController.restoreVersionWith(scripts, changed, "dataQuality", 1, "ops"))
        assert(r.getStatusCode.value == 422, r.getBody)
        assert(errorOf(r).contains("different instruction or source schema"), r.getBody)
        assert(scripts.record("orders", "dataQuality").get == live, "nothing changed")
    }

    /** A repository whose file was edited at head once `edited` is set: every commit is rejected. */
    private class ConflictRepo extends FakeRepo {
        var edited = false
        override def storeScript(name: String, script: String, prior: ScriptRef, actor: String): StoredScript =
            if (edited) throw new CodeRepoConflictException("'" + prior.scriptRepoPath + "' changed in the repository since this was loaded")
            else super.storeScript(name, script, prior, actor)
    }

    test("POST restore over a repository file edited since the recorded commit is 409") {
        val repo = new ConflictRepo
        val scripts = versionedScripts(repo)
        withEnv(scripts.onSave(null, dqOnly, "todd"))
        withEnv(scripts.regenerate(dqOnly, "dataQuality", "todd"))
        repo.edited = true
        val live = scripts.record("orders", "dataQuality").get
        val r = withEnv(CodeGenScriptVersionAPIController.restoreVersionWith(scripts, dqOnly, "dataQuality", 1, "ops"))
        assert(r.getStatusCode.value == 409, r.getBody)
        assert(errorOf(r).contains(PipelineScripts.ConflictMarker), r.getBody)
        assert(scripts.record("orders", "dataQuality").get.scriptCommitSha == live.scriptCommitSha, "the pin is unchanged")
    }

    test("a pre-upgrade record's GET entry omits version") {
        val scripts =
            new PipelineScripts(new MemRecords, { val s = new MemStore; _ => s }, (_, _) => "print('[]')", () => "model-a", versions = new MemVersions)
        withEnv(scripts.onSave(null, dqOnly, "todd"))
        // Simulate a record written before versioning (scriptVersion absent -> 0).
        val rec = scripts.record("orders", "dataQuality").get
        val records = new MemRecords
        records.write(rec.copy(scriptVersion = 0))
        val legacy = new PipelineScripts(records, { val s = new MemStore; _ => s }, (_, _) => "print('[]')", () => "model-a", versions = new MemVersions)
        val e = entries(withEnv(PipelineAPIController.codegenScriptsBody(dqOnly, legacy)))("dataQuality")
        assert(!e.has("version"), s"$e")
    }
}
