package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: CodeGen scripts 1 (plans/stories/codegen-script-pinning.md).
  * The AI rule / AI transformation script is generated when the pipeline is
  * saved, stored, and executed on every run.
  *
  * Pinned seam (store, record IO and the model call are parameters so the
  * spec runs without Mongo, MinIO or a model):
  * {{{
  * // ai.datris.util
  * trait CodeGenScriptRecords {               // CodeGenScriptIO implements it
  *     def read(pipeline: String, kind: String): Option[CodeGenScript]
  *     def write(record: CodeGenScript): Unit
  *     def delete(pipeline: String, kind: String): Unit
  * }
  *
  * class PipelineScripts(
  *     records: CodeGenScriptRecords,
  *     storeFor: String => CodeStore,          // pipeline name => its script store
  *     ai: (String, String) => String,         // (systemPrompt, userPrompt) => script text
  *     currentModel: () => String              // the CodeGen model setting right now
  * ) {
  *     def onSave(previous: PipelineConfig, saved: PipelineConfig, actor: String): List[PipelineScripts.Outcome]
  *     def forRun(config: PipelineConfig, kind: String, runHeader: List[String], generate: () => String): PipelineScripts.Resolved
  *     def regenerate(config: PipelineConfig, kind: String, actor: String): Either[String, CodeGenScript]
  *     def readText(pipeline: String, kind: String): Option[String]
  *     def deleteAll(pipeline: String): Unit
  * }
  * object PipelineScripts extends PipelineScripts(<production defaults>) {
  *     val DataQuality = "dataQuality"; val Transformation = "transformation"
  *     def fingerprint(instruction: String, schemaSignature: String): String
  *     def contractVersion(kind: String): Int
  *     case class Outcome(kind: String, status: String, pendingReason: String = null,
  *                        generatedAt: String = null, model: String = null)
  *     /** action: "stored" | "generate-and-store" | "generate-once". */
  *     case class Resolved(action: String, script: String, reason: String, record: CodeGenScript)
  * }
  * }}}
  * `forRun`'s `generate` is the evaluator's run-data generation (this run's
  * sample or skeleton plus the model call); it is invoked only when the
  * stored script cannot be used. `CodeGenScript` gains the story's fields
  * (`storage`, `scriptPath`, `fingerprint`, `generatedAgainst`, `model`,
  * `status`, `pendingReason`, `origin`, `contractVersion`, ...).
  *
  * The stored-script failure is asserted through the evaluator entry point
  * that takes the pipeline config:
  * {{{
  * object CodeGenRuleEvaluator {
  *     private[datris] def evaluateCsv(rule: String, data: Data, delimiter: String, config: PipelineConfig,
  *                                     note: String => Unit, scripts: PipelineScripts,
  *                                     ai: (String, String) => String): List[(Int, String)]
  * }
  * }}}
  */
class PipelineScriptsSpec extends AnyFunSuite with BeforeAndAfterEach with AiSampleValuesMarkers {

    // ------------------------------------------------------------- fakes

    private class MemRecords extends CodeGenScriptRecords {
        val rows = mutable.LinkedHashMap[(String, String), CodeGenScript]()
        var writes = 0
        override def read(pipeline: String, kind: String): Option[CodeGenScript] = rows.get((pipeline, kind))
        override def write(record: CodeGenScript): Unit = { writes += 1; rows((record.pipeline, record.kind)) = record }
        override def delete(pipeline: String, kind: String): Unit = rows.remove((pipeline, kind))
    }

    private class MemStore extends CodeStore {
        val objects = mutable.LinkedHashMap[String, String]()
        private var n = 0
        override def storage: String = "minio"
        override def readScript(ref: ScriptRef): Option[String] = Option(ref).flatMap(r => Option(r.scriptPath)).flatMap(objects.get)
        override def storeScript(name: String, script: String, prior: ScriptRef, actor: String): StoredScript = {
            n += 1
            val path = "pipeline-scripts/orders/" + name + "_" + n + ".py"
            objects(path) = script
            StoredScript("minio", scriptPath = path)
        }
        override def deleteScript(ref: ScriptRef): Unit = if (ref != null && ref.scriptPath != null) objects.remove(ref.scriptPath)
        override def scriptExists(ref: ScriptRef): Boolean = readScript(ref).isDefined
    }

    private val calls = mutable.ListBuffer[(String, String)]()
    private var failWith: String = null
    private var model = "model-a"
    private var counter = 0

    /** Records each call; returns a distinct script per call, or throws when `failWith` is set. */
    private val fakeAi: (String, String) => String = (system, user) => {
        calls += ((system, user))
        if (failWith != null) throw new RuntimeException(failWith)
        counter += 1
        "print('generated " + counter + "')"
    }

    private var records: MemRecords = _
    private var store: MemStore = _
    private var scripts: PipelineScripts = _

    override def beforeEach(): Unit = {
        calls.clear(); failWith = null; model = "model-a"; counter = 0
        records = new MemRecords
        store = new MemStore
        scripts = new PipelineScripts(records, _ => store, fakeAi, () => model)
        TenantContext.set(testEnv)
    }

    override def afterEach(): Unit = TenantContext.clear()

    // ------------------------------------------------------------ configs

    private val Dq = "dataQuality"
    private val Tx = "transformation"
    private val rule = "age must be a positive whole number"
    private val instruction = "add a column age_band that buckets age by decade"

    private def csvCfg(
        name: String = "orders",
        fields: Seq[(String, String)] = Seq("full_name" -> "string", "age" -> "int", "email" -> "string"),
        delimiter: String = ",",
        dqRule: String = rule,
        txInstruction: String = null
    ): PipelineConfig =
        PipelineConfig(
            name = name,
            source = Source(
                schemaProperties = SchemaProperties(null, fields.map { case (n, t) => SchemaField(n, t) }.asJava),
                fileAttributes = FileAttributes(csvAttributes = CsvAttributes(delimiter = delimiter))
            ),
            dataQuality = if (dqRule == null) null else ai.datris.model.DataQuality(aiRule = AIRule(dqRule)),
            transformation = if (txInstruction == null) null else ai.datris.model.Transformation(aiTransformation = AITransformation(txInstruction)),
            destination = Destination(database = Database(dbName = "d", schema = "public", table = "t", usePostgres = true))
        )

    private def jsonCfg(): PipelineConfig =
        PipelineConfig(
            name = "events",
            source = Source(
                schemaProperties = SchemaProperties(null, List(SchemaField("_json", "string")).asJava),
                fileAttributes = FileAttributes(jsonAttributes = JsonAttributes())
            ),
            dataQuality = ai.datris.model.DataQuality(aiRule = AIRule(rule)),
            destination = Destination(database = Database(dbName = "d", schema = "public", table = "t", usePostgres = true))
        )

    private val header = List("full_name", "age", "email")

    /** A run-time generator that counts its calls. */
    private class RunGen {
        var count = 0
        val fn: () => String = () => { count += 1; "print('run " + count + "')" }
    }

    // --------------------------------------------------------------- save

    test("saving a delimited pipeline with an AI rule makes one model call and stores a ready script") {
        val out = scripts.onSave(null, csvCfg(), "todd")
        assert(calls.size == 1, s"one model call, got ${calls.size}")
        assert(out.map(o => (o.kind, o.status)) == List((Dq, "ready")), s"$out")
        val rec = records.read("orders", Dq).getOrElse(fail("no record written"))
        assert(rec.status == "ready")
        assert(rec.origin == "save")
        assert(rec.storage == "minio")
        assert(rec.scriptPath != null && store.objects.contains(rec.scriptPath), "script text lives in the store")
        assert(rec.fingerprint != null && rec.fingerprint.nonEmpty)
        assert(rec.model == "model-a")
        assert(rec.generatedAt != null)
        assert(rec.contractVersion == PipelineScripts.contractVersion(Dq))
        assert(scripts.readText("orders", Dq).contains("print('generated 1')"))
    }

    test("the save prompt carries names and types and no row value") {
        scripts.onSave(null, csvCfg(txInstruction = instruction), "todd")
        assert(calls.size == 2, s"rule and transformation each generate once, got ${calls.size}")
        calls.foreach { case (system, user) =>
            assertNoMarker(system)
            assertNoMarker(user)
            Seq("full_name", "age", "email").foreach(n => assert(user.contains(n), s"field $n named:\n$user"))
            assert(user.contains("int"), s"types named:\n$user")
            assert(user.contains("No sample rows are available when a pipeline is saved"), user)
            assert(!user.contains("Sample rows:"), user)
        }
        assert(calls.exists(_._2.contains(rule)))
        assert(calls.exists(_._2.contains(instruction)))
    }

    test("re-saving with the same instruction and schema makes no model call") {
        val cfg = csvCfg(txInstruction = instruction)
        scripts.onSave(null, cfg, "todd")
        val before = calls.size
        val dqBefore = records.read("orders", Dq).get
        val out = scripts.onSave(cfg, cfg.copy(version = 2), "todd")
        assert(calls.size == before, "no model call on an unchanged re-save")
        assert(records.read("orders", Dq).get.generatedAt == dqBefore.generatedAt)
        assert(out.map(_.status).forall(_ == "ready"), s"$out")
        // The MCP tool upserts without a previous config in hand: same answer.
        scripts.onSave(null, cfg, "todd")
        assert(calls.size == before)
    }

    test("a changed instruction, field name, type, order or delimiter regenerates at save") {
        val base = csvCfg()
        val variants: Seq[(String, PipelineConfig)] = Seq(
            "instruction" -> csvCfg(dqRule = "age must be between 0 and 120"),
            "field name" -> csvCfg(fields = Seq("full_name" -> "string", "age_years" -> "int", "email" -> "string")),
            "type" -> csvCfg(fields = Seq("full_name" -> "string", "age" -> "string", "email" -> "string")),
            "order" -> csvCfg(fields = Seq("age" -> "int", "full_name" -> "string", "email" -> "string")),
            "delimiter" -> csvCfg(delimiter = "|")
        )
        variants.foreach { case (what, changed) =>
            beforeEach()
            scripts.onSave(null, base, "todd")
            val fp = records.read("orders", Dq).get.fingerprint
            assert(calls.size == 1)
            scripts.onSave(base, changed, "todd")
            assert(calls.size == 2, s"a changed $what regenerates: ${calls.size} calls")
            val rec = records.read("orders", Dq).get
            assert(rec.fingerprint != fp, s"a changed $what changes the fingerprint")
            assert(scripts.readText("orders", Dq).contains("print('generated 2')"), what)
        }
    }

    test("an instruction change regenerates only that kind") {
        val base = csvCfg(txInstruction = instruction)
        scripts.onSave(null, base, "todd")
        val txBefore = records.read("orders", Tx).get
        scripts.onSave(base, csvCfg(txInstruction = instruction, dqRule = "age must be between 0 and 120"), "todd")
        assert(calls.size == 3)
        assert(records.read("orders", Tx).get == txBefore, "transformation untouched")
    }

    test("a changed codegen model does not regenerate") {
        val cfg = csvCfg()
        scripts.onSave(null, cfg, "todd")
        model = "model-b"
        scripts.onSave(cfg, cfg, "todd")
        assert(calls.size == 1, "a model change alone makes no call at save")
        val rec = records.read("orders", Dq).get
        assert(rec.model == "model-a", "the record keeps the model that wrote the script")
        val gen = new RunGen
        val r = scripts.forRun(cfg, Dq, header, gen.fn)
        assert(r.action == "stored" && gen.count == 0 && calls.size == 1, "nor at run")
    }

    test("a JSON pipeline is saved pending with no model call") {
        val out = scripts.onSave(null, jsonCfg(), "todd")
        assert(calls.isEmpty)
        assert(out.map(o => (o.kind, o.status)) == List((Dq, "pending")), s"$out")
        assert(out.head.pendingReason != null && out.head.pendingReason.nonEmpty)
        val rec = records.read("events", Dq).getOrElse(fail("pending record expected"))
        assert(rec.status == "pending")
        assert(rec.pendingReason != null && rec.pendingReason.nonEmpty)
    }

    test("a failed generation leaves the pipeline saved and the script pending with the reason") {
        failWith = "No AI provider key is configured"
        val out =
            try scripts.onSave(null, csvCfg(txInstruction = instruction), "todd")
            catch { case e: Throwable => fail("onSave must never throw: " + e) }
        assert(out.map(_.status).toSet == Set("pending"), s"$out")
        assert(out.forall(_.pendingReason.contains("No AI provider key is configured")), s"$out")
        Seq(Dq, Tx).foreach { k =>
            val rec = records.read("orders", k).getOrElse(fail(s"pending $k record expected"))
            assert(rec.status == "pending")
            assert(rec.pendingReason.contains("No AI provider key is configured"))
        }
        assert(store.objects.isEmpty)
    }

    // ---------------------------------------------------------------- run

    test("a run with a matching stored script makes no model call") {
        val cfg = csvCfg()
        scripts.onSave(null, cfg, "todd")
        val gen = new RunGen
        val r1 = scripts.forRun(cfg, Dq, header, gen.fn)
        val r2 = scripts.forRun(cfg, Dq, header, gen.fn)
        assert(calls.size == 1 && gen.count == 0, "only the save called the model")
        assert(r1.action == "stored" && r2.action == "stored")
        assert(r1.script == "print('generated 1')" && r2.script == r1.script)
        assert(r1.record.generatedAt == r2.record.generatedAt)
    }

    test("a run with no stored script, a pending script or a stale fingerprint generates, stores and runs") {
        val cfg = csvCfg()

        // No stored script (a pipeline from before the upgrade).
        val gen = new RunGen
        val r = scripts.forRun(cfg, Dq, header, gen.fn)
        assert(gen.count == 1 && r.action == "generate-and-store" && r.script == "print('run 1')")
        val rec = records.read("orders", Dq).getOrElse(fail("the run stores what it generated"))
        assert(rec.status == "ready" && rec.origin == "run")
        assert(scripts.readText("orders", Dq).contains("print('run 1')"))
        assert(scripts.forRun(cfg, Dq, header, gen.fn).action == "stored", "the next run reuses it")
        assert(gen.count == 1)

        // Pending (generation failed at save).
        beforeEach()
        failWith = "model unreachable"
        scripts.onSave(null, cfg, "todd")
        val gen2 = new RunGen
        val p = scripts.forRun(cfg, Dq, header, gen2.fn)
        assert(gen2.count == 1 && p.action == "generate-and-store")
        assert(records.read("orders", Dq).get.status == "ready")
        assert(scripts.forRun(cfg, Dq, header, gen2.fn).action == "stored")

        // Stale fingerprint: a legacy transformation row (text, no fingerprint) and a changed instruction.
        beforeEach()
        val txCfg = csvCfg(dqRule = null, txInstruction = instruction)
        records.write(CodeGenScript("orders", Tx, instruction, "print('legacy')", "2026-01-01T00:00:00Z"))
        val gen3 = new RunGen
        val legacy = scripts.forRun(txCfg, Tx, header, gen3.fn)
        assert(gen3.count == 1 && legacy.action == "generate-and-store", "a legacy row has no fingerprint to match")
        scripts.onSave(null, cfg, "todd")
        val gen4 = new RunGen
        val stale = scripts.forRun(csvCfg(dqRule = "age must be between 0 and 120"), Dq, header, gen4.fn)
        assert(gen4.count == 1 && stale.action == "generate-and-store", "config written past the save hook (version restore)")
    }

    test("a run whose header differs from the stored script's generates for that run and leaves the stored script") {
        val cfg = csvCfg()
        scripts.onSave(null, cfg, "todd")
        val stored = records.read("orders", Dq).get
        val storedText = scripts.readText("orders", Dq)
        val gen = new RunGen
        // An earlier stage (field protection drop) removed a column.
        val r = scripts.forRun(cfg, Dq, List("full_name", "age"), gen.fn)
        assert(gen.count == 1 && r.action == "generate-once")
        assert(r.reason != null && r.reason.nonEmpty)
        assert(records.read("orders", Dq).get == stored, "stored record untouched")
        assert(scripts.readText("orders", Dq) == storedText, "stored script untouched")
    }

    test("a stored script that exits non-zero throws DatrisException naming generated-at and the regenerate endpoint and makes no model call") {
        assume(
            scala.util.Try(new ProcessBuilder("python3", "--version").start().waitFor() == 0).getOrElse(false),
            "python3 not available"
        )
        val failing = new PipelineScripts(records, _ => store, (_, _) => "import sys\nsys.stderr.write('boom')\nsys.exit(3)\n", () => model)
        val cfg = csvCfg()
        failing.onSave(null, cfg, "todd")
        val generatedAt = records.read("orders", Dq).get.generatedAt
        val runCalls = mutable.ListBuffer[String]()
        val notes = mutable.ListBuffer[String]()
        StagingArea.withToken("pipeline-scripts-spec") {
            val data = Data(100L, header, header.map(SchemaField(_, "string")), List("Ada,41,a@example.com"), null)
            val e = intercept[DatrisException] {
                CodeGenRuleEvaluator.evaluateCsv(
                    rule,
                    data,
                    ",",
                    cfg,
                    (n: String) => { notes += n; () },
                    failing,
                    (_: String, u: String) => { runCalls += u; "print('[]')" }
                )
            }
            assert(e.getMessage.contains(generatedAt), e.getMessage)
            assert(e.getMessage.contains("codegen-scripts/dataQuality/regenerate"), e.getMessage)
        }
        assert(runCalls.isEmpty, "a failing stored script is not regenerated")
        assert(records.read("orders", Dq).get.generatedAt == generatedAt)
    }

    test("an older contract version regenerates at run") {
        val cfg = csvCfg()
        scripts.onSave(null, cfg, "todd")
        val rec = records.read("orders", Dq).get
        records.write(rec.copy(contractVersion = PipelineScripts.contractVersion(Dq) - 1))
        val gen = new RunGen
        val r = scripts.forRun(cfg, Dq, header, gen.fn)
        assert(gen.count == 1 && r.action == "generate-and-store")
        assert(records.read("orders", Dq).get.contractVersion == PipelineScripts.contractVersion(Dq))
    }

    // ------------------------------------------------- regenerate / delete

    test("regenerate replaces the script, and leaves it in place when generation fails") {
        val cfg = csvCfg(txInstruction = instruction)
        scripts.onSave(null, cfg, "todd")
        val beforeText = scripts.readText("orders", Tx)

        val ok = scripts.regenerate(cfg, Tx, "todd")
        assert(ok.isRight, s"$ok")
        val after = records.read("orders", Tx).get
        assert(after.origin == "regenerate")
        assert(after.status == "ready")
        assert(scripts.readText("orders", Tx).contains("print('generated 3')"))
        assert(scripts.readText("orders", Tx) != beforeText)
        assert(after.generatedAt != null)

        val current = records.read("orders", Tx).get
        val currentText = scripts.readText("orders", Tx)
        failWith = "model unreachable"
        val bad = scripts.regenerate(cfg, Tx, "todd")
        assert(bad.isLeft && bad.left.get.contains("model unreachable"), s"$bad")
        assert(records.read("orders", Tx).get == current)
        assert(scripts.readText("orders", Tx) == currentText)

        // Regenerate also adopts the current model.
        failWith = null
        model = "model-b"
        assert(scripts.regenerate(cfg, Tx, "todd").isRight)
        assert(records.read("orders", Tx).get.model == "model-b")
    }

    test("deleteAll removes the objects and the records") {
        scripts.onSave(null, csvCfg(txInstruction = instruction), "todd")
        assert(store.objects.size == 2)
        scripts.deleteAll("orders")
        assert(store.objects.isEmpty, s"${store.objects}")
        assert(records.read("orders", Dq).isEmpty && records.read("orders", Tx).isEmpty)
        assert(scripts.readText("orders", Dq).isEmpty)
    }

    // =====================================================================
    // Story: CodeGen scripts 2 (plans/stories/codegen-script-git-storage.md).
    // Scripts go to the code repository when one is enabled.
    //
    // Pinned seam (extends story 1's; story-1 four-argument construction
    // keeps compiling and behaves as before):
    // {{{
    // trait CodeStore {
    //     ...
    //     /** Branch head of the script's file: (content, head commit sha).
    //       * None for the built-in store (no branch). */
    //     def pullLatest(ref: ScriptRef): Option[(String, String)] = None
    // }
    // class PipelineScripts(
    //     records: CodeGenScriptRecords,
    //     storeFor: String => CodeStore,              // pipeline => built-in store
    //     ai: (String, String) => String,
    //     currentModel: () => String,
    //     repoStoreFor: String => CodeStore = null,   // pipeline => repository store (GithubCodeStore.forPipeline)
    //     defaultStorage: () => String = () => "minio" // backend for a NEW script: "github" when
    //                                                  // CodeRepoConfigIO.readEnabled is defined, else "minio"
    // ) {
    //     def regenerate(config: PipelineConfig, kind: String, actor: String,
    //                    storage: String = null,       // "github" | "builtin"; null = the script's current backend
    //                    overwrite: Boolean = false): Either[String, CodeGenScript]
    //     /** Some(headSha) when the file at branch head differs from the recorded commit. */
    //     def drift(pipeline: String, kind: String): Option[String]
    //     /** Adopt the branch-head version: records its sha, origin "repository". Left for built-in scripts. */
    //     def pull(pipeline: String, kind: String, actor: String): Either[String, CodeGenScript]
    // }
    // object PipelineScripts {
    //     case class Outcome(kind: String, status: String, pendingReason: String = null,
    //                        generatedAt: String = null, model: String = null,
    //                        warning: String = null)   // set when a repository commit was rejected
    // }
    // }}}
    // A repository store's `storeScript` receives the saving key's label as
    // `actor` (null during a run, rendered "datris"), passes the recorded
    // `prior.scriptCommitSha` as the base, and throws
    // `CodeRepoConflictException` when the file changed in the repository
    // since that base (as `GithubClient.putFile`). `readScript` throws the
    // `DatrisException` `GithubScriptStore.readScript` throws when the
    // repository is unreachable and the commit is not cached.
    // =====================================================================

    /** In-memory GitHub branch: a global commit sequence, per-path history kept. */
    private class FakeRepo extends CodeStore {
        case class Commit(sha: String, path: String, content: Option[String], base: String, actor: String)
        val commits = mutable.ListBuffer[Commit]()

        /** When set, every call fails as an unreachable repository / expired token would. */
        var down: String = null

        override def storage: String = "github"

        def pathOf(kind: String): String =
            "taps/pipelines/orders/" + (if (kind == "dataQuality") "data-quality.py" else "transformation.py")

        def headSha: String = commits.lastOption.map(_.sha).orNull

        private def contentAt(path: String, sha: String): Option[String] = {
            val idx = commits.indexWhere(_.sha == sha)
            if (idx < 0) None else commits.take(idx + 1).filter(_.path == path).lastOption.flatMap(_.content)
        }
        def head(path: String): Option[String] = commits.filter(_.path == path).lastOption.flatMap(_.content)

        /** Every version of `path` ever committed (history is never rewritten). */
        def history(path: String): List[String] = commits.filter(_.path == path).flatMap(_.content).toList

        private def commit(path: String, content: Option[String], base: String, actor: String): String = {
            val sha = "%040x".format(commits.size + 0xabc001)
            commits += Commit(sha, path, content, base, actor)
            sha
        }

        /** Somebody edits the file on GitHub. */
        def hubEdit(path: String, content: String): String = commit(path, Some(content), null, "someone")

        private def check(): Unit = if (down != null) throw new RuntimeException(down)

        override def readScript(ref: ScriptRef): Option[String] = {
            if (ref == null || ref.scriptRepoPath == null) return None
            if (down != null)
                throw new DatrisException(
                    "Code repository is unreachable and no cached copy of '" + ref.scriptRepoPath + "' at " +
                        Option(ref.scriptCommitSha).map(_.take(9)).orNull + " exists locally. " + down
                )
            if (ref.scriptCommitSha == null) head(ref.scriptRepoPath) else contentAt(ref.scriptRepoPath, ref.scriptCommitSha)
        }

        override def storeScript(name: String, script: String, prior: ScriptRef, actor: String): StoredScript = {
            check()
            val fromRepo = prior != null && prior.storage == "github" && prior.scriptRepoPath != null
            val path = if (fromRepo) prior.scriptRepoPath else pathOf(name)
            val base = if (fromRepo) prior.scriptCommitSha else null
            if (base != null && head(path).isDefined && contentAt(path, base).isDefined && contentAt(path, base) != head(path))
                throw new CodeRepoConflictException(
                    "'" + path + "' changed in the repository since this tap was loaded. Pull the latest script, reapply your edits, and save again."
                )
            StoredScript("github", scriptRepoPath = path, scriptCommitSha = commit(path, Some(script), base, actor))
        }

        override def deleteScript(ref: ScriptRef): Unit = {
            check()
            if (ref != null && ref.scriptRepoPath != null && head(ref.scriptRepoPath).isDefined) commit(ref.scriptRepoPath, None, null, null)
        }

        override def scriptExists(ref: ScriptRef): Boolean = readScript(ref).isDefined

        override def pullLatest(ref: ScriptRef): Option[(String, String)] = {
            check()
            if (ref == null || ref.scriptRepoPath == null) None else head(ref.scriptRepoPath).map(c => (c, headSha))
        }

        def writes: List[Commit] = commits.filter(c => c.actor != "someone").toList
    }

    private var repo: FakeRepo = _
    private var repoEnabled = true

    /** PipelineScripts with both backends; the install default follows `repoEnabled`. */
    private def withRepo(): PipelineScripts = {
        repo = new FakeRepo
        repoEnabled = true
        new PipelineScripts(
            records,
            _ => store,
            fakeAi,
            () => model,
            repoStoreFor = _ => repo,
            defaultStorage = () => if (repoEnabled) "github" else "minio"
        )
    }

    test("with an enabled repository a new script is committed and the record holds storage, path and sha") {
        val s = withRepo()
        val out = s.onSave(null, csvCfg(txInstruction = instruction), "todd")
        assert(out.map(_.status).toSet == Set("ready"), s"$out")
        assert(repo.writes.size == 2, s"one commit per script: ${repo.commits}")
        assert(repo.writes.forall(_.actor == "todd"), "the saving key's label is the commit {user}")
        assert(store.objects.isEmpty, "nothing in built-in storage")
        Seq(Dq -> "taps/pipelines/orders/data-quality.py", Tx -> "taps/pipelines/orders/transformation.py").foreach { case (k, path) =>
            val rec = records.read("orders", k).getOrElse(fail(s"no $k record"))
            assert(rec.storage == "github", s"$rec")
            assert(rec.scriptRepoPath == path, s"$rec")
            assert(rec.scriptPath == null)
            val c = repo.writes.find(_.path == path).getOrElse(fail(s"no commit for $path"))
            assert(rec.scriptCommitSha == c.sha, "the record pins the commit that wrote it")
            assert(rec.status == "ready" && rec.origin == "save")
            assert(s.readText("orders", k) == c.content)
        }
        // Runs execute the recorded commit; no model call, no new commit.
        val gen = new RunGen
        val r = s.forRun(csvCfg(txInstruction = instruction), Tx, header, gen.fn)
        assert(r.action == "stored" && gen.count == 0 && calls.size == 2)
        assert(repo.commits.size == 2)
    }

    test("with none it goes to built-in storage") {
        val s = withRepo()
        repoEnabled = false
        s.onSave(null, csvCfg(txInstruction = instruction), "todd")
        assert(repo.commits.isEmpty, "no commit without an enabled repository")
        Seq(Dq, Tx).foreach { k =>
            val rec = records.read("orders", k).get
            assert(rec.storage == "minio" && rec.scriptPath != null && rec.scriptRepoPath == null && rec.scriptCommitSha == null, s"$rec")
        }
        assert(store.objects.size == 2)
    }

    test("a save-time regeneration keeps the script's current backend") {
        // Built-in script, then a repository is enabled (upgrade path).
        val s = withRepo()
        repoEnabled = false
        val base = csvCfg()
        s.onSave(null, base, "todd")
        assert(records.read("orders", Dq).get.storage == "minio")
        repoEnabled = true
        s.onSave(base, csvCfg(dqRule = "age must be between 0 and 120"), "todd")
        assert(calls.size == 2, "the changed instruction regenerated")
        assert(records.read("orders", Dq).get.storage == "minio", "an existing built-in script stays built-in")
        assert(repo.commits.isEmpty)

        // Repository script, then the default flips to built-in.
        beforeEach()
        val s2 = withRepo()
        s2.onSave(null, base, "todd")
        val first = records.read("orders", Dq).get
        assert(first.storage == "github")
        repoEnabled = false
        s2.onSave(base, csvCfg(dqRule = "age must be between 0 and 120"), "todd")
        val second = records.read("orders", Dq).get
        assert(second.storage == "github" && second.scriptRepoPath == first.scriptRepoPath, s"$second")
        assert(second.scriptCommitSha != first.scriptCommitSha, "a new commit on the same file")
        assert(repo.writes.size == 2 && repo.writes.last.base == first.scriptCommitSha, "the recorded sha is the commit base")
        assert(store.objects.isEmpty)
    }

    test("regenerate with storage moves the script") {
        val s = withRepo()
        repoEnabled = false
        val cfg = csvCfg(txInstruction = instruction)
        s.onSave(null, cfg, "todd")
        assert(records.read("orders", Tx).get.storage == "minio")

        // builtin -> github
        assert(s.regenerate(cfg, Tx, "todd", storage = "github").isRight)
        val moved = records.read("orders", Tx).get
        assert(moved.storage == "github" && moved.scriptRepoPath == "taps/pipelines/orders/transformation.py", s"$moved")
        assert(moved.scriptCommitSha == repo.headSha)
        assert(moved.origin == "regenerate")
        assert(s.readText("orders", Tx) == repo.head(moved.scriptRepoPath))

        // A regenerate with no storage keeps the current backend (even with the default built-in).
        assert(s.regenerate(cfg, Tx, "todd").isRight)
        assert(records.read("orders", Tx).get.storage == "github")

        // github -> builtin; the repository file is left in place, as for taps.
        val commitsBefore = repo.commits.size
        assert(s.regenerate(cfg, Tx, "todd", storage = "builtin").isRight)
        val back = records.read("orders", Tx).get
        assert(back.storage == "minio" && back.scriptPath != null && back.scriptRepoPath == null && back.scriptCommitSha == null, s"$back")
        assert(repo.commits.size == commitsBefore, "no delete commit on a move")
        assert(repo.head("taps/pipelines/orders/transformation.py").isDefined)
        assert(s.readText("orders", Tx).exists(store.objects.values.toSet.contains))
        // The data-quality script was not touched by any of this.
        assert(records.read("orders", Dq).get.storage == "minio")
    }

    test("a commit rejected because the file changed returns a warning and leaves the record") {
        val s = withRepo()
        val base = csvCfg()
        s.onSave(null, base, "todd")
        val before = records.read("orders", Dq).get
        repo.hubEdit(before.scriptRepoPath, "print('hand edit')")
        val writesBefore = repo.writes.size

        val changed = csvCfg(dqRule = "age must be between 0 and 120")
        val out =
            try s.onSave(base, changed, "todd")
            catch { case e: Throwable => fail("onSave must never throw: " + e) }
        val o = out.find(_.kind == Dq).getOrElse(fail(s"$out"))
        assert(o.warning != null, s"a conflict is a warning: $o")
        assert(o.warning.contains("pull"), o.warning)
        assert(o.warning.contains("overwrite=true"), o.warning)
        // Story 2 E2E fix: the record also carries the conflict marker; pin,
        // script, status and fingerprint are as they were.
        val marked = records.read("orders", Dq).get
        assert(marked.copy(conflictFingerprint = null, conflictReason = null) == before, "the record is left as it was")
        assert(marked.conflictFingerprint != null && marked.conflictReason == o.warning, s"$marked")
        assert(repo.writes.size == writesBefore, "no commit landed")
        assert(repo.head(before.scriptRepoPath).contains("print('hand edit')"), "the hand edit is not overwritten")

        // A forced regenerate without overwrite is rejected the same way.
        val forced = s.regenerate(changed, Dq, "todd")
        assert(forced.isLeft && forced.left.get.contains("overwrite=true"), s"$forced")
        assert(records.read("orders", Dq).get.copy(conflictFingerprint = null, conflictReason = null) == before)
        assert(repo.head(before.scriptRepoPath).contains("print('hand edit')"))
    }

    test("overwrite replaces it") {
        val s = withRepo()
        val base = csvCfg()
        s.onSave(null, base, "todd")
        val before = records.read("orders", Dq).get
        repo.hubEdit(before.scriptRepoPath, "print('hand edit')")
        val changed = csvCfg(dqRule = "age must be between 0 and 120")
        s.onSave(base, changed, "todd") // conflict warning

        val r = s.regenerate(changed, Dq, "todd", overwrite = true)
        assert(r.isRight, s"$r")
        val after = records.read("orders", Dq).get
        assert(after.storage == "github" && after.scriptRepoPath == before.scriptRepoPath)
        assert(after.scriptCommitSha == repo.headSha && after.scriptCommitSha != before.scriptCommitSha)
        assert(after.fingerprint != before.fingerprint, "the record now matches the changed instruction")
        assert(repo.head(after.scriptRepoPath) == s.readText("orders", Dq))
        assert(!repo.head(after.scriptRepoPath).contains("print('hand edit')"))
        assert(repo.history(after.scriptRepoPath).contains("print('hand edit')"), "history is kept")
    }

    test("pull records the head sha and later saves with unchanged inputs do not regenerate") {
        val s = withRepo()
        val cfg = csvCfg(txInstruction = instruction)
        s.onSave(null, cfg, "todd")
        val before = records.read("orders", Tx).get
        assert(s.drift("orders", Tx).isEmpty, "no drift right after the commit")

        val editSha = repo.hubEdit(before.scriptRepoPath, "print('hand edit')")
        assert(s.drift("orders", Tx).contains(editSha), "drift reports the head sha")
        // Runs stay on the recorded commit until the edit is adopted.
        val gen = new RunGen
        val pinned = s.forRun(cfg, Tx, header, gen.fn)
        assert(pinned.action == "stored" && pinned.script == "print('generated 2')" && gen.count == 0, s"$pinned")

        val commitsBefore = repo.commits.size
        val pulled = s.pull("orders", Tx, "todd")
        assert(pulled.isRight, s"$pulled")
        val rec = records.read("orders", Tx).get
        assert(rec.scriptCommitSha == editSha, s"$rec")
        assert(rec.origin == "repository")
        assert(rec.status == "ready")
        assert(repo.commits.size == commitsBefore, "pull makes no commit")
        assert(s.drift("orders", Tx).isEmpty)
        assert(s.readText("orders", Tx).contains("print('hand edit')"))

        val run = s.forRun(cfg, Tx, header, gen.fn)
        assert(run.action == "stored" && run.script == "print('hand edit')" && gen.count == 0)

        val callsBefore = calls.size
        s.onSave(cfg, cfg.copy(version = 2), "todd")
        s.onSave(null, cfg, "todd")
        assert(calls.size == callsBefore, "unchanged inputs: no model call")
        assert(records.read("orders", Tx).get == rec, "the adopted edit survives the save")
        assert(repo.commits.size == commitsBefore)
    }

    test("a pull that resolves a save-time conflict is not regenerated over by the next run or save") {
        val s = withRepo()
        val base = csvCfg()
        s.onSave(null, base, "todd")
        val before = records.read("orders", Dq).get
        val editSha = repo.hubEdit(before.scriptRepoPath, "print('hand edit')")
        val changed = csvCfg(dqRule = "age must be between 0 and 120")
        val o = s.onSave(base, changed, "todd").find(_.kind == Dq).get
        assert(o.warning != null && o.warning.contains("pull"), s"$o")

        // The warning's advice: pull, with the pipeline as saved now.
        assert(s.pull("orders", Dq, "todd", changed).isRight)
        val pulled = records.read("orders", Dq).get
        assert(pulled.scriptCommitSha == editSha && pulled.origin == "repository")
        val commitsBefore = repo.commits.size
        val callsBefore = calls.size

        val gen = new RunGen
        val run = s.forRun(changed, Dq, header, gen.fn)
        assert(run.action == "stored" && run.script == "print('hand edit')" && gen.count == 0, s"$run")
        s.onSave(changed, changed, "todd")
        assert(calls.size == callsBefore, "no model call after the pull")
        assert(repo.commits.size == commitsBefore, "the hand edit is not overwritten")
        assert(repo.head(pulled.scriptRepoPath).contains("print('hand edit')"))
    }

    test("a store failure at save leaves the script pending") {
        val s = withRepo()
        repo.down = "401 Bad credentials"
        val out =
            try s.onSave(null, csvCfg(), "todd")
            catch { case e: Throwable => fail("onSave must never throw: " + e) }
        assert(out.map(o => (o.kind, o.status)) == List((Dq, "pending")), s"$out")
        assert(out.head.pendingReason.contains("Bad credentials"), s"$out")
        val rec = records.read("orders", Dq).getOrElse(fail("pending record expected"))
        assert(rec.status == "pending" && rec.pendingReason.contains("Bad credentials"), s"$rec")
        assert(repo.commits.isEmpty)
        assert(store.objects.isEmpty, "no silent fall back to built-in storage")
    }

    test("a store failure during a run still runs the generated script and stays pending") {
        val s = withRepo()
        val cfg = csvCfg()
        repo.down = "connection refused"
        s.onSave(null, cfg, "todd")
        assert(records.read("orders", Dq).get.status == "pending")

        val gen = new RunGen
        val r =
            try s.forRun(cfg, Dq, header, gen.fn)
            catch { case e: Throwable => fail("a commit failure must not fail the run: " + e) }
        assert(gen.count == 1 && r.script == "print('run 1')", s"$r")
        assert(r.action != "stored")
        val rec = records.read("orders", Dq).get
        assert(rec.status == "pending", s"the script stays pending: $rec")
        assert(repo.commits.isEmpty && store.objects.isEmpty)

        // Repository back: the next run generates and commits, as the run's actor.
        repo.down = null
        val next = s.forRun(cfg, Dq, header, gen.fn)
        assert(next.action == "generate-and-store" && gen.count == 2)
        val stored = records.read("orders", Dq).get
        assert(stored.status == "ready" && stored.storage == "github" && stored.scriptCommitSha == repo.headSha, s"$stored")
        assert(Option(repo.writes.last.actor).forall(_ == "datris"), "a run commits as datris")

        // A run whose commit is rejected because the file was hand-edited:
        // the recorded commit runs (one model call, then none), the reason
        // names pull / overwrite, the pin and the hand edit are untouched.
        repo.hubEdit(stored.scriptRepoPath, "print('hand edit')")
        val changed = csvCfg(dqRule = "age must be between 0 and 120")
        val writesBefore = repo.writes.size
        val conflicted =
            try s.forRun(changed, Dq, header, gen.fn)
            catch { case e: Throwable => fail("a rejected commit must not fail the run: " + e) }
        assert(gen.count == 3 && conflicted.action == "stored" && conflicted.script == "print('run 2')", s"$conflicted")
        val reason = conflicted.reason
        assert(reason != null && reason.contains("pull") && reason.contains("overwrite=true"), reason)
        assert(!reason.contains("this tap"), s"pipeline wording, not the tap message: $reason")
        val line = PipelineScripts.statusLine(conflicted)
        assert(line.contains("pull") && !line.contains("this tap"), line)
        assert(records.read("orders", Dq).get.copy(conflictFingerprint = null, conflictReason = null) == stored, "the pin is unchanged")
        assert(records.read("orders", Dq).get.conflictReason == reason)
        val again = s.forRun(changed, Dq, header, gen.fn)
        assert(again.action == "stored" && gen.count == 3, "no model call while the conflict is unresolved")
        assert(repo.writes.size == writesBefore)
        assert(repo.head(stored.scriptRepoPath).contains("print('hand edit')"))
    }

    test("an unresolved save-time conflict runs the recorded commit with no model call until pull clears it") {
        val s = withRepo()
        val base = csvCfg()
        s.onSave(null, base, "todd")
        val before = records.read("orders", Dq).get
        val editSha = repo.hubEdit(before.scriptRepoPath, "print('hand edit')")
        val changed = csvCfg(dqRule = "age must be between 0 and 120")
        val o = s.onSave(base, changed, "todd").find(_.kind == Dq).get
        assert(o.warning.contains("Runs keep using the recorded commit"), o.warning)
        assert(records.read("orders", Dq).get.conflictReason == o.warning)
        val callsAfterSave = calls.size

        val gen = new RunGen
        Seq(1, 2).foreach { i =>
            val r = s.forRun(changed, Dq, header, gen.fn)
            assert(r.action == "stored", s"run $i: $r")
            assert(r.script == "print('generated 1')", s"run $i executes the recorded commit: $r")
            assert(r.record.scriptCommitSha == before.scriptCommitSha)
            val line = PipelineScripts.statusLine(r)
            assert(line.contains(before.scriptCommitSha.take(7)) && line.contains("pull") && line.contains("overwrite=true"), line)
        }
        assert(gen.count == 0 && calls.size == callsAfterSave, "no model call while in conflict")

        // Re-saving the conflicted config makes no model call and repeats the warning.
        val again = s.onSave(changed, changed, "todd").find(_.kind == Dq).get
        assert(again.warning == o.warning && calls.size == callsAfterSave)

        // Pull clears the marker and the runs move to the hand edit.
        assert(s.pull("orders", Dq, "todd", changed).isRight)
        val pulled = records.read("orders", Dq).get
        assert(pulled.conflictFingerprint == null && pulled.conflictReason == null, s"$pulled")
        assert(pulled.scriptCommitSha == editSha)
        val after = s.forRun(changed, Dq, header, gen.fn)
        assert(after.action == "stored" && after.reason == null && after.script == "print('hand edit')" && gen.count == 0)
        assert(!PipelineScripts.statusLine(after).contains("overwrite"))
    }

    test("changing the instruction back clears the conflict marker") {
        // At save.
        val s = withRepo()
        val base = csvCfg()
        s.onSave(null, base, "todd")
        val before = records.read("orders", Dq).get
        repo.hubEdit(before.scriptRepoPath, "print('hand edit')")
        val changed = csvCfg(dqRule = "age must be between 0 and 120")
        s.onSave(base, changed, "todd")
        assert(records.read("orders", Dq).get.conflictReason != null)
        val callsBefore = calls.size
        val out = s.onSave(changed, base, "todd").find(_.kind == Dq).get
        assert(out.warning == null && out.status == "ready", s"$out")
        assert(records.read("orders", Dq).get == before, "marker cleared, record otherwise as recorded")
        assert(calls.size == callsBefore, "no model call")

        // At run (the first thing to see the reverted config, e.g. a version restore).
        s.onSave(base, changed, "todd")
        assert(records.read("orders", Dq).get.conflictReason != null)
        val gen = new RunGen
        val r = s.forRun(base, Dq, header, gen.fn)
        assert(r.action == "stored" && r.reason == null && gen.count == 0, s"$r")
        assert(!PipelineScripts.statusLine(r).contains("overwrite"))
        assert(records.read("orders", Dq).get == before)
    }

    test("overwrite clears the conflict marker") {
        val s = withRepo()
        val base = csvCfg()
        s.onSave(null, base, "todd")
        repo.hubEdit(records.read("orders", Dq).get.scriptRepoPath, "print('hand edit')")
        val changed = csvCfg(dqRule = "age must be between 0 and 120")
        s.onSave(base, changed, "todd")
        assert(records.read("orders", Dq).get.conflictReason != null)
        assert(s.regenerate(changed, Dq, "todd", overwrite = true).isRight)
        val rec = records.read("orders", Dq).get
        assert(rec.conflictFingerprint == null && rec.conflictReason == null && rec.scriptCommitSha == repo.headSha, s"$rec")
    }

    test("an unreadable recorded commit fails the run with no model call") {
        val s = withRepo()
        val cfg = csvCfg()
        s.onSave(null, cfg, "todd")
        val rec = records.read("orders", Dq).get
        val callsBefore = calls.size
        repo.down = "token expired"
        val gen = new RunGen
        val e = intercept[DatrisException](s.forRun(cfg, Dq, header, gen.fn))
        assert(e.getMessage.contains("Code repository is unreachable"), e.getMessage)
        assert(e.getMessage.contains(rec.scriptRepoPath), e.getMessage)
        assert(gen.count == 0 && calls.size == callsBefore, "no fall back to generating")
        assert(records.read("orders", Dq).get == rec)
    }

    test("deleteAll removes both files and survives a failing store") {
        val s = withRepo()
        s.onSave(null, csvCfg(txInstruction = instruction), "todd")
        val paths = Seq(Dq, Tx).map(k => records.read("orders", k).get.scriptRepoPath)
        val versions = paths.map(p => repo.head(p).get)
        s.deleteAll("orders")
        val deletes = repo.commits.filter(_.content.isEmpty)
        assert(deletes.map(_.path).toSet == paths.toSet, s"a delete commit per file: ${repo.commits}")
        paths.foreach(p => assert(repo.head(p).isEmpty, s"$p gone from branch head"))
        paths.zip(versions).foreach { case (p, v) => assert(repo.history(p).contains(v), "history kept") }
        assert(records.read("orders", Dq).isEmpty && records.read("orders", Tx).isEmpty)

        // A failing repository does not block the delete.
        beforeEach()
        val s2 = withRepo()
        s2.onSave(null, csvCfg(txInstruction = instruction), "todd")
        repo.down = "502 Bad Gateway"
        try s2.deleteAll("orders")
        catch { case e: Throwable => fail("deleteAll must not throw: " + e) }
        assert(records.read("orders", Dq).isEmpty && records.read("orders", Tx).isEmpty)
    }
}
