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

    /** In-memory `<env>-codegen-script-version` (story self-healing-5a). */
    private class MemVersions extends CodeGenScriptVersions {
        val rows = mutable.ListBuffer[CodeGenScriptVersion]()
        val pruneCalls = mutable.ListBuffer[(String, String, Int)]()
        override def listVersions(pipeline: String, kind: String): List[CodeGenScriptVersion] =
            rows.filter(v => v.pipeline == pipeline && v.kind == kind).sortBy(_.version).toList
        override def latestVersion(pipeline: String, kind: String): Int = (0 :: listVersions(pipeline, kind).map(_.version)).max
        override def get(pipeline: String, kind: String, version: Int): Option[CodeGenScriptVersion] =
            listVersions(pipeline, kind).find(_.version == version)
        override def append(v: CodeGenScriptVersion): Unit = rows += v
        override def prune(pipeline: String, kind: String, keep: Int): Unit = {
            pruneCalls += ((pipeline, kind, keep))
            if (keep > 0) rows --= listVersions(pipeline, kind).dropRight(keep)
        }
        override def deleteAllForPipeline(pipeline: String): Unit = rows --= rows.filter(_.pipeline == pipeline).toList
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
    private var history: MemVersions = _

    override def beforeEach(): Unit = {
        calls.clear(); failWith = null; model = "model-a"; counter = 0
        records = new MemRecords
        store = new MemStore
        history = new MemVersions
        scripts = new PipelineScripts(records, _ => store, fakeAi, () => model, versions = history)
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
        val failing = new PipelineScripts(records, _ => store, (_, _) => "import sys\nsys.stderr.write('boom')\nsys.exit(3)\n", () => model, versions = history)
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
        val warnings: Seq[String] = scripts.deleteAll("orders")
        assert(warnings == Nil, s"built-in storage never keeps a file: $warnings")
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

        /** When set, a delete commits and then fails, as a request that timed
          * out client-side after GitHub committed it would. */
        var deleteFails: String = null

        override def deleteScript(ref: ScriptRef): Unit = {
            check()
            if (ref != null && ref.scriptRepoPath != null && head(ref.scriptRepoPath).isDefined) commit(ref.scriptRepoPath, None, null, null)
            if (deleteFails != null) throw new RuntimeException(deleteFails)
        }

        override def scriptExists(ref: ScriptRef): Boolean = readScript(ref).isDefined

        override def pullLatest(ref: ScriptRef): Option[(String, String)] = {
            check()
            if (ref == null || ref.scriptRepoPath == null) None else head(ref.scriptRepoPath).map(c => (c, headSha))
        }

        override def repoPathFor(name: String): Option[String] = Some(pathOf(name))

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
            defaultStorage = () => if (repoEnabled) "github" else "minio",
            versions = history
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

    // =====================================================================
    // Story: Delete keeps a hand-edited repository script and warns
    // (plans/stories/pipeline-delete-drift-warning-and-404.md).
    // Pinned seam:
    // {{{
    // trait CodeStore {
    //     /** Delete unless the file changed at head since ref.scriptCommitSha;
    //       * Some(warning) when the file was kept (edited, or not checkable). */
    //     def deleteUnlessEdited(ref: ScriptRef): Option[String]
    // }
    // class PipelineScripts(...) {
    //     /** Records always deleted; returns one warning per kept file. Never throws. */
    //     def deleteAll(pipeline: String): Seq[String]
    // }
    // object PipelineScripts {
    //     def keptOnDeleteWarning(pipeline: String, kind: String, path: String, sha: String): String
    // }
    // }}}
    // The drift check must reach FakeRepo through CodeStore.driftHead (its
    // default compares pullLatest with readScript; pullLatest's check()
    // throws while `down` is set).
    // =====================================================================

    private val DqPath = "taps/pipelines/orders/data-quality.py"
    private val TxPath = "taps/pipelines/orders/transformation.py"

    test("deleteAll deletes an unchanged repository file with no warning") {
        val s = withRepo()
        s.onSave(null, csvCfg(txInstruction = instruction), "todd")
        assert(repo.head(DqPath).isDefined && repo.head(TxPath).isDefined, s"${repo.commits}")
        val warnings: Seq[String] =
            try s.deleteAll("orders")
            catch { case e: Throwable => fail("deleteAll must not throw: " + e) }
        assert(warnings == Nil, s"$warnings")
        assert(repo.head(DqPath).isEmpty, "unchanged data-quality file deleted at head")
        assert(repo.head(TxPath).isEmpty, "unchanged transformation file deleted at head")
        assert(records.read("orders", Dq).isEmpty && records.read("orders", Tx).isEmpty)
    }

    test("deleteAll keeps a file edited at head and reports it") {
        val s = withRepo()
        s.onSave(null, csvCfg(txInstruction = instruction), "todd")
        val dqRec = records.read("orders", Dq).getOrElse(fail("no data-quality record"))
        assert(dqRec.scriptRepoPath == DqPath, s"$dqRec")
        repo.hubEdit(DqPath, "# edited")
        val warnings: Seq[String] =
            try s.deleteAll("orders")
            catch { case e: Throwable => fail("deleteAll must not throw: " + e) }
        assert(repo.head(DqPath).contains("# edited"), s"the hand edit stays at head: ${repo.commits}")
        assert(repo.head(TxPath).isEmpty, "the unchanged transformation file is deleted")
        assert(!repo.commits.exists(c => c.path == DqPath && c.content.isEmpty), "no delete commit for the edited file")
        assert(records.read("orders", Dq).isEmpty && records.read("orders", Tx).isEmpty, "both records deleted")
        assert(warnings.size == 1, s"$warnings")
        val w = warnings.head
        assert(w.contains(DqPath), w)
        assert(w.contains("kept in the code repository"), w)
        assert(w.contains(dqRec.scriptCommitSha.take(7)), s"names the recorded sha prefix: $w")
    }

    test("deleteAll reports an unconfirmed delete, not a kept file, when only the delete request fails") {
        val s = withRepo()
        s.onSave(null, csvCfg(txInstruction = instruction), "todd")
        repo.hubEdit(DqPath, "# edited")
        repo.deleteFails = "api.github.com:443 failed to respond"
        val warnings: Seq[String] =
            try s.deleteAll("orders")
            catch { case e: Throwable => fail("deleteAll must not throw: " + e) }
        assert(repo.head(DqPath).contains("# edited"), "the hand edit stays at head")
        assert(repo.head(TxPath).isEmpty, "the transformation delete was committed")
        assert(records.read("orders", Dq).isEmpty && records.read("orders", Tx).isEmpty, "both records deleted")
        assert(warnings.size == 2, s"$warnings")
        val kept = warnings.filter(_.contains(DqPath))
        assert(kept.size == 1 && kept.head.contains("kept in the code repository"), s"$warnings")
        val failed = warnings.filter(_.contains(TxPath))
        assert(failed.size == 1, s"$warnings")
        val w = failed.head
        assert(w.contains("could not be confirmed") && w.contains("api.github.com:443 failed to respond"), w)
        assert(!w.contains("was kept") && !w.contains("could not be checked"), s"must not claim the file was kept: $w")
    }

    test("deleteAll keeps the files and warns when the repository is unreachable") {
        val s = withRepo()
        s.onSave(null, csvCfg(txInstruction = instruction), "todd")
        val commitsBefore = repo.commits.size
        repo.down = "401"
        val warnings: Seq[String] =
            try s.deleteAll("orders")
            catch { case e: Throwable => fail("deleteAll must not throw: " + e) }
        assert(repo.commits.size == commitsBefore, s"nothing committed: ${repo.commits}")
        assert(records.read("orders", Dq).isEmpty && records.read("orders", Tx).isEmpty, "records deleted anyway")
        assert(warnings.size == 2, s"one warning per kept file: $warnings")
    }

    // =====================================================================
    // Story: pipeline-delete-drift-warning-and-404.md, Part 2. A save that
    // drops an AI instruction removes that kind's record; a repository file
    // edited at head since the recorded commit is kept and named in the save
    // warnings. Pinned seam:
    // {{{
    // object PipelineScripts {
    //     case class SaveResult(outcomes: List[Outcome], warnings: List[String])
    //     def keptOnRemoveWarning(...): String      // "... saved without its <kind> instruction ..."
    //     def uncheckedOnRemoveWarning(...): String
    // }
    // class PipelineScripts(...) {
    //     /** onSave's body; onSave == onSaveResult(...).outcomes. Never throws. */
    //     def onSaveResult(previous: PipelineConfig, saved: PipelineConfig, actor: String): PipelineScripts.SaveResult
    // }
    // }}}
    // =====================================================================

    test("saving without an instruction keeps a file edited at head and warns") {
        val s = withRepo()
        val saved = csvCfg()
        s.onSave(null, saved, "todd")
        val dqRec = records.read("orders", Dq).getOrElse(fail("no data-quality record"))
        assert(dqRec.scriptRepoPath == DqPath, s"$dqRec")
        repo.hubEdit(DqPath, "# edited")
        val cfgWithoutDq = csvCfg(dqRule = null)
        val result: PipelineScripts.SaveResult =
            try s.onSaveResult(saved, cfgWithoutDq, "todd")
            catch { case e: Throwable => fail("onSaveResult must not throw: " + e) }
        assert(result.warnings.size == 1, s"${result.warnings}")
        val w = result.warnings.head
        assert(w.contains(DqPath), w)
        assert(w.contains("kept in the code repository"), w)
        assert(w.contains(dqRec.scriptCommitSha.take(7)), s"names the recorded sha prefix: $w")
        assert(w.contains("saved without"), s"save wording, not delete wording: $w")
        assert(repo.head(DqPath).isDefined, s"the hand edit stays at head: ${repo.commits}")
        assert(records.read("orders", Dq).isEmpty, "the data-quality record is removed")
        assert(!result.outcomes.exists(_.kind == Dq), s"no outcome for the removed kind: ${result.outcomes}")
    }

    test("saving without an instruction removes an unchanged file silently") {
        val s = withRepo()
        val saved = csvCfg()
        s.onSave(null, saved, "todd")
        assert(repo.head(DqPath).isDefined, s"${repo.commits}")
        val result: PipelineScripts.SaveResult =
            try s.onSaveResult(saved, csvCfg(dqRule = null), "todd")
            catch { case e: Throwable => fail("onSaveResult must not throw: " + e) }
        assert(result.warnings == Nil, s"${result.warnings}")
        assert(repo.head(DqPath).isEmpty, "the unchanged file is deleted at head")
        assert(records.read("orders", Dq).isEmpty, "the data-quality record is removed")
        assert(!result.outcomes.exists(_.kind == Dq), s"${result.outcomes}")
    }

    // =====================================================================
    // Story: Restore honours archived CodeGen scripts
    // (plans/stories/multi-env-3-restore-import-codegen-scripts.md),
    // Acceptance bullets 2-4. Pinned seam:
    // {{{
    // class PipelineScripts(...) {
    //     /** Make an archived script the live one, with the archive's
    //       * fingerprint, model, generatedAt, generatedAgainst and
    //       * contractVersion, status ready, origin "restore". Left when the
    //       * archived fingerprint is not fingerprint(instructionOf(config, kind),
    //       * schemaSignature(config)) (message mentions "instruction or source
    //       * schema") or its contractVersion is not contractVersion(kind).
    //       * Repository backend: branch head equal to `text` (normalised) pins
    //       * the head sha with no commit; a different file at head is
    //       * Left(conflictWarning(...)) (contains ConflictMarker), no commit. */
    //     def importScript(config: PipelineConfig, kind: String, text: String,
    //                      archived: CodeGenScript, actor: String): Either[String, CodeGenScript]
    // }
    // }}}
    // =====================================================================

    private val archivedAt = "2026-10-01T12:00:00Z"

    /** The archive's sidecar for `cfg`'s rule, written by "model-a" on another install. */
    private def archivedRecord(cfg: PipelineConfig, kind: String = Dq): CodeGenScript = {
        val instr = PipelineScripts.instructionOf(cfg, kind).get
        CodeGenScript(
            pipeline = cfg.name,
            kind = kind,
            instruction = instr,
            script = null,
            generatedAt = archivedAt,
            fingerprint = PipelineScripts.fingerprint(instr, PipelineScripts.schemaSignature(cfg)),
            generatedAgainst = java.util.Arrays.asList("full_name", "age", "email"),
            model = "model-a",
            status = "ready",
            origin = "save",
            contractVersion = PipelineScripts.contractVersion(kind)
        )
    }

    test("importScript then onSave with a different current model makes no model call") {
        val cfg = csvCfg()
        model = "model-b"
        val archived = archivedRecord(cfg)
        val r = scripts.importScript(cfg, Dq, "print('archived')", archived, "todd")
        assert(r.isRight, r)
        scripts.onSave(null, cfg, "todd")
        assert(calls.isEmpty, s"no model call after an import: ${calls.size}")
        val rec = scripts.record("orders", Dq).getOrElse(fail("no record written by importScript"))
        assert(rec.model == "model-a", s"the archived model, not the current setting: $rec")
        assert(rec.origin == "restore", s"$rec")
        assert(rec.fingerprint == archived.fingerprint, s"$rec")
        assert(rec.generatedAt == archivedAt, s"$rec")
        assert(rec.status == "ready", s"$rec")
        assert(rec.contractVersion == PipelineScripts.contractVersion(Dq), s"$rec")
        assert(scripts.readText("orders", Dq).contains("print('archived')"), "the archived bytes are the live script")
        // Runs execute it as well, still without a model call.
        val gen = new RunGen
        val run = scripts.forRun(cfg, Dq, header, gen.fn)
        assert(run.action == "stored" && run.script == "print('archived')" && gen.count == 0 && calls.isEmpty, s"$run")
    }

    test("importScript with a fingerprint that does not match the archived config is Left, and onSave then regenerates once") {
        val cfg = csvCfg()
        val stale = archivedRecord(cfg).copy(fingerprint = PipelineScripts.fingerprint("a different rule", PipelineScripts.schemaSignature(cfg)))
        val r = scripts.importScript(cfg, Dq, "print('archived')", stale, "todd")
        assert(r.isLeft, r)
        assert(r.left.get.contains("instruction or source schema"), r.left.get)
        assert(records.read("orders", Dq).isEmpty, "nothing imported")
        scripts.onSave(null, cfg, "todd")
        assert(calls.size == 1, s"the regenerate path: exactly one model call, got ${calls.size}")
        assert(records.read("orders", Dq).exists(_.origin == "save"))
    }

    test("importScript with a different contractVersion is Left, and onSave then regenerates once") {
        val cfg = csvCfg()
        val old = archivedRecord(cfg).copy(contractVersion = PipelineScripts.contractVersion(Dq) + 1)
        val r = scripts.importScript(cfg, Dq, "print('archived')", old, "todd")
        assert(r.isLeft, r)
        assert(records.read("orders", Dq).isEmpty, "nothing imported")
        scripts.onSave(null, cfg, "todd")
        assert(calls.size == 1, s"the regenerate path: exactly one model call, got ${calls.size}")
    }

    test("importScript on a repository script: the identical file at branch head is pinned with no commit") {
        val s = withRepo()
        val cfg = csvCfg()
        s.onSave(null, cfg, "todd") // the target already has a repository-backed script
        val path = repo.pathOf(Dq)
        // The same bytes reach branch head from elsewhere (another install sharing the repository).
        val headSha = repo.hubEdit(path, "print('archived')")
        val commitsBefore = repo.commits.size
        val callsBefore = calls.size
        val r = s.importScript(cfg, Dq, "print('archived')\n", archivedRecord(cfg), "todd")
        assert(r.isRight, r)
        assert(repo.commits.size == commitsBefore, s"no commit for identical bytes: ${repo.commits}")
        val rec = records.read("orders", Dq).get
        assert(rec.storage == "github" && rec.scriptRepoPath == path, s"$rec")
        assert(rec.scriptCommitSha == headSha, s"pinned to branch head: $rec")
        assert(rec.origin == "restore" && rec.model == "model-a" && rec.generatedAt == archivedAt, s"$rec")
        s.onSave(null, cfg, "todd")
        assert(calls.size == callsBefore, "no model call after the import")
    }

    test("importScript on a repository script: a different file at branch head is Left with the conflict marker and no commit") {
        val s = withRepo()
        val cfg = csvCfg()
        s.onSave(null, cfg, "todd")
        val pinned = records.read("orders", Dq).get.scriptCommitSha
        repo.hubEdit(repo.pathOf(Dq), "print('hand edit')")
        val commitsBefore = repo.commits.size
        val r = s.importScript(cfg, Dq, "print('archived')", archivedRecord(cfg), "todd")
        assert(r.isLeft, r)
        assert(r.left.get.contains(PipelineScripts.ConflictMarker), r.left.get)
        assert(repo.commits.size == commitsBefore, s"a hand edit is never overwritten: ${repo.commits}")
        assert(repo.head(repo.pathOf(Dq)).contains("print('hand edit')"))
        assert(records.read("orders", Dq).get.scriptCommitSha == pinned, "the record keeps its pin")
    }

    // A fresh target (no record yet): CodeStore.repoPathFor gives the path a
    // new script would get, so branch head is checked before the first write.

    test("importScript on a fresh repository target: a different file already at the script path is Left, no commit") {
        val s = withRepo()
        val cfg = csvCfg()
        repo.hubEdit(repo.pathOf(Dq), "print('someone else')")
        val commitsBefore = repo.commits.size
        val r = s.importScript(cfg, Dq, "print('archived')", archivedRecord(cfg), "todd")
        assert(r.isLeft, r)
        // Not a conflict warning; a catalog restore refuses this case up front
        // (CatalogArchive.planRepoScripts), so only a direct caller sees it.
        assert(r.left.get == PipelineScripts.differentFileAtHeadReason(repo.pathOf(Dq)), r.left.get)
        assert(r.left.get.endsWith("; the archived script was not imported"), r.left.get)
        assert(!r.left.get.contains("regenerated"), r.left.get)
        assert(!r.left.get.contains(PipelineScripts.ConflictMarker), r.left.get)
        assert(repo.commits.size == commitsBefore, s"never overwritten: ${repo.commits}")
        assert(records.read("orders", Dq).isEmpty)
    }

    test("importScript on a fresh repository target: the identical file at the script path is pinned, and no file commits once") {
        val s = withRepo()
        val cfg = csvCfg()
        val headSha = repo.hubEdit(repo.pathOf(Dq), "print('archived')")
        val r = s.importScript(cfg, Dq, "print('archived')", archivedRecord(cfg), "todd")
        assert(r.isRight, r)
        assert(records.read("orders", Dq).get.scriptCommitSha == headSha)
        assert(repo.writes.isEmpty, s"no commit: ${repo.writes}")

        val tx = csvCfg(txInstruction = instruction)
        val r2 = s.importScript(tx, Tx, "print('archived tx')", archivedRecord(tx, Tx), "todd")
        assert(r2.isRight, r2)
        assert(repo.writes.size == 1, s"one create commit: ${repo.writes}")
        assert(repo.head(repo.pathOf(Tx)).contains("print('archived tx')"))
    }

    test("importScript on a repository record that pins no commit and a different file at head is Left, no commit") {
        val s = withRepo()
        val cfg = csvCfg()
        repo.hubEdit(repo.pathOf(Dq), "print('someone else')")
        records.write(
            CodeGenScript("orders", Dq, rule, null, storage = "github", scriptRepoPath = repo.pathOf(Dq), status = "pending", origin = "save")
        )
        val commitsBefore = repo.commits.size
        val r = s.importScript(cfg, Dq, "print('archived')", archivedRecord(cfg), "todd")
        assert(r.isLeft, r)
        assert(r.left.get.contains("pins no commit"), r.left.get)
        assert(repo.commits.size == commitsBefore, s"never overwritten: ${repo.commits}")
        assert(repo.head(repo.pathOf(Dq)).contains("print('someone else')"))
    }

    // Story: self-healing-3 review — a scratch rehearsal job may generate a
    // script but never stores it (the candidate's header may be wrong).
    test("a rehearsal run with no stored script generates for this run only and writes nothing") {
        val cfg = csvCfg()
        val gen = new RunGen
        val writesBefore = records.writes
        val objectsBefore = store.objects.size
        val r = PipelineScripts.withRehearsal(true)(scripts.forRun(cfg, Dq, List("renamed_col", "age", "email"), gen.fn))
        assert(gen.count == 1, "generated once for the run")
        assert(r.action == PipelineScripts.GenerateOnce, r.action)
        assert(r.reason.startsWith(PipelineScripts.RehearsalReason), r.reason)
        assert(records.writes == writesBefore, "no CodeGen record written")
        assert(records.read("orders", Dq).isEmpty, "nothing stored")
        assert(store.objects.size == objectsBefore, "no script object written")
        assert(PipelineScripts.statusLine(r).contains("for this run only"), PipelineScripts.statusLine(r))
        // Outside the rehearsal the same call stores, as before.
        val real = scripts.forRun(cfg, Dq, header, gen.fn)
        assert(real.action == PipelineScripts.GenerateAndStore && records.read("orders", Dq).isDefined)
    }

    test("a rehearsal run with a matching stored script runs it, writing nothing") {
        val cfg = csvCfg()
        scripts.onSave(null, cfg, "todd")
        val writesBefore = records.writes
        val gen = new RunGen
        val r = PipelineScripts.withRehearsal(true)(scripts.forRun(cfg, Dq, header, gen.fn))
        assert(r.action == PipelineScripts.Stored && gen.count == 0)
        assert(records.writes == writesBefore)
    }

    // =====================================================================
    // Story: Version history for stored CodeGen scripts
    // (plans/stories/self-healing-5a-script-versions.md), Acceptance bullet 1.
    //
    // Pinned seam:
    // {{{
    // // ai.datris.model
    // case class CodeGenScriptVersion(key: String /* pipeline|kind|version */, entityName: String /* pipeline|kind */,
    //     pipeline: String, kind: String, version: Int, script: String, storage: String, scriptRepoPath: String,
    //     scriptCommitSha: String, fingerprint: String, model: String, origin: String, changeNote: String,
    //     createdAt: String, createdBy: String, generatedAt: String, contractVersion: Int,
    //     generatedAgainst: java.util.List[String])
    // // CodeGenScript gains `scriptVersion: Int = 0` (the live record's version number).
    //
    // // ai.datris.util
    // trait CodeGenScriptVersions {          // object CodeGenScriptVersionIO implements it
    //     def listVersions(pipeline: String, kind: String): List[CodeGenScriptVersion]   // ascending
    //     def latestVersion(pipeline: String, kind: String): Int                         // 0 when none
    //     def get(pipeline: String, kind: String, version: Int): Option[CodeGenScriptVersion]
    //     def append(v: CodeGenScriptVersion): Unit
    //     def prune(pipeline: String, kind: String, keep: Int): Unit
    //     def deleteAllForPipeline(pipeline: String): Unit
    // }
    // class PipelineScripts(..., versions: CodeGenScriptVersions = CodeGenScriptVersionIO) {
    //     /** Ascending; seeds v1 "(seeded from pre-versioning state)" from a
    //       * ready, readable live record when the kind has no rows yet. */
    //     def scriptVersions(pipeline: String, kind: String): List[CodeGenScriptVersion]
    //     def scriptVersion(pipeline: String, kind: String, version: Int): Option[CodeGenScriptVersion]
    //     def restoreVersion(config: PipelineConfig, kind: String, version: Int, actor: String): Either[String, CodeGenScript]
    // }
    // }}}
    // Origins: "save", "regenerate", "run", "restore-version", "restore"
    // (catalog import, note "imported from catalog archive"), "repository"
    // (pull, note "adopted repository commit <sha7>").
    // =====================================================================

    private val SeedNote = "(seeded from pre-versioning state)"

    private def fpOf(cfg: PipelineConfig, kind: String = Dq): String =
        PipelineScripts.fingerprint(PipelineScripts.instructionOf(cfg, kind).get, PipelineScripts.schemaSignature(cfg))

    private def ver(n: Int, kind: String = Dq): CodeGenScriptVersion =
        history.get("orders", kind, n).getOrElse(fail(s"no version $n of $kind: ${history.rows}"))

    private def nums(kind: String = Dq): List[Int] = history.listVersions("orders", kind).map(_.version)

    /** Make the stored script look written under an older script contract, so
      * the next run generates and stores (the run-time store path). */
    private def ageContract(kind: String = Dq): Unit =
        records.write(records.read("orders", kind).get.copy(contractVersion = 0))

    test("script versions: save, regenerate and a run-time store each append one numbered version holding the text") {
        val cfg = csvCfg()
        scripts.onSave(null, cfg, "todd")
        val live1 = records.read("orders", Dq).get
        val v1 = history.get("orders", Dq, 1).getOrElse(fail(s"save wrote no version: ${history.rows}"))
        assert(v1.origin == "save", s"$v1")
        assert(v1.script == "print('generated 1')", s"the version holds the script text itself: $v1")
        assert(v1.pipeline == "orders" && v1.kind == Dq, s"$v1")
        assert(v1.entityName == "orders|dataQuality", s"$v1")
        assert(v1.key == "orders|dataQuality|1", s"$v1")
        assert(v1.fingerprint == live1.fingerprint && v1.model == "model-a" && v1.storage == "minio", s"$v1")
        assert(v1.createdBy == "todd", s"$v1")
        assert(v1.createdAt != null && v1.generatedAt == live1.generatedAt, s"$v1")
        assert(v1.contractVersion == PipelineScripts.contractVersion(Dq), s"$v1")
        assert(live1.scriptVersion == 1, s"the live record carries its version number: $live1")

        model = "model-b"
        assert(scripts.regenerate(cfg, Dq, "todd").isRight)
        val v2 = history.get("orders", Dq, 2).getOrElse(fail(s"regenerate wrote no version: ${history.rows}"))
        assert(v2.origin == "regenerate" && v2.script == "print('generated 2')" && v2.script != v1.script, s"$v2")
        assert(v2.model == "model-b", s"$v2")
        assert(records.read("orders", Dq).get.scriptVersion == 2)

        ageContract()
        val gen = new RunGen
        val run = scripts.forRun(cfg, Dq, header, gen.fn)
        assert(run.action == PipelineScripts.GenerateAndStore, s"$run")
        val v3 = history.get("orders", Dq, 3).getOrElse(fail(s"a run-time store wrote no version: ${history.rows}"))
        assert(v3.origin == "run" && v3.script == "print('run 1')", s"$v3")

        assert(history.latestVersion("orders", Dq) == 3)
        assert(nums() == List(1, 2, 3), s"${history.rows}")
        assert(scripts.scriptVersions("orders", Dq).map(_.version) == List(1, 2, 3))
        assert(scripts.scriptVersion("orders", Dq, 2).map(_.script).contains("print('generated 2')"))
        assert(records.read("orders", Dq).get.scriptVersion == 3)
    }

    test("script versions: a re-save with the same fingerprint, a stored run and a run-only script append nothing") {
        val cfg = csvCfg()
        scripts.onSave(null, cfg, "todd")
        scripts.onSave(cfg, cfg.copy(version = 2), "todd")
        scripts.onSave(null, cfg, "todd")
        val gen = new RunGen
        assert(scripts.forRun(cfg, Dq, header, gen.fn).action == PipelineScripts.Stored)
        // A run whose header differs generates for that run only: never stored, never versioned.
        val once = scripts.forRun(cfg, Dq, List("a", "b", "c"), gen.fn)
        assert(once.action == PipelineScripts.GenerateOnce, s"$once")
        assert(nums() == List(1), s"${history.rows}")
    }

    test("script versions: a pending write appends nothing and the last version keeps its text") {
        val cfg = csvCfg()
        scripts.onSave(null, cfg, "todd")
        ageContract()
        scripts.forRun(cfg, Dq, header, new RunGen().fn) // v2, origin run
        assert(nums() == List(1, 2))
        failWith = "model unreachable"
        val out = scripts.onSave(cfg, csvCfg(dqRule = "age must be between 0 and 120"), "todd")
        assert(out.exists(o => o.kind == Dq && o.status == "pending"), s"$out")
        assert(nums() == List(1, 2), s"writePending appends nothing: ${history.rows}")
        assert(history.get("orders", Dq, 2).map(_.script).contains("print('run 1')"), "the previous version keeps the last script")
        // A JSON pipeline's regenerate is pending as well: still nothing appended.
        val json = jsonCfg()
        scripts.regenerate(json, Dq, "todd")
        assert(history.listVersions("events", Dq).isEmpty, s"${history.rows}")
    }

    test("script versions: a pipeline without AI instructions never gets a row") {
        scripts.onSave(null, csvCfg(dqRule = null), "todd")
        assert(history.rows.isEmpty, s"${history.rows}")
        assert(scripts.scriptVersions("orders", Dq).isEmpty)
        assert(scripts.scriptVersions("orders", Tx).isEmpty)
    }

    /** A live record written before versioning: ready, text in the store, no history rows, scriptVersion 0. */
    private def preUpgradeRecord(cfg: PipelineConfig, text: String = "print('legacy')", status: String = "ready"): CodeGenScript = {
        val path = "pipeline-scripts/orders/legacy.py"
        store.objects(path) = text
        val rec = CodeGenScript(
            "orders",
            Dq,
            PipelineScripts.instructionOf(cfg, Dq).get,
            null,
            generatedAt = "2026-09-01T00:00:00Z",
            storage = "minio",
            scriptPath = path,
            fingerprint = fpOf(cfg),
            model = "model-old",
            status = status,
            origin = "save",
            contractVersion = PipelineScripts.contractVersion(Dq)
        )
        records.write(rec)
        rec
    }

    test("script versions: a pre-existing live record with no rows seeds v1 before the regenerate appends v2") {
        val cfg = csvCfg()
        preUpgradeRecord(cfg)
        assert(history.rows.isEmpty)
        assert(scripts.regenerate(cfg, Dq, "todd").isRight)
        val all = history.listVersions("orders", Dq)
        assert(all.map(_.version) == List(1, 2), s"$all")
        val seeded = all.head
        assert(seeded.changeNote == SeedNote, s"$seeded")
        assert(seeded.script == "print('legacy')", s"the seed holds the pre-upgrade text: $seeded")
        assert(seeded.model == "model-old" && seeded.fingerprint == fpOf(cfg), s"$seeded")
        assert(all(1).origin == "regenerate" && all(1).script == "print('generated 1')", s"${all(1)}")
        assert(records.read("orders", Dq).get.scriptVersion == 2)
    }

    test("script versions: the first list seeds v1 lazily, once; a pending pre-existing record seeds nothing") {
        val cfg = csvCfg()
        preUpgradeRecord(cfg)
        val listed = scripts.scriptVersions("orders", Dq)
        assert(listed.map(v => (v.version, v.changeNote, v.script)) == List((1, SeedNote, "print('legacy')")), s"$listed")
        assert(scripts.scriptVersions("orders", Dq).size == 1, "listing again does not seed again")
        assert(history.rows.size == 1, s"${history.rows}")

        beforeEach()
        preUpgradeRecord(cfg, status = "pending")
        assert(scripts.scriptVersions("orders", Dq).isEmpty, "no readable text: nothing to seed")
        assert(history.rows.isEmpty)
    }

    test("script versions: a list seeds v1 without writing the live record; the next regenerate appends exactly one v2") {
        val cfg = csvCfg()
        preUpgradeRecord(cfg)
        val writesBefore = records.writes
        assert(scripts.scriptVersions("orders", Dq).map(_.version) == List(1))
        assert(scripts.scriptVersion("orders", Dq, 1).isDefined)
        assert(records.writes == writesBefore, "a history read never writes the live record")
        assert(records.read("orders", Dq).get.scriptVersion == 0)
        assert(scripts.liveVersion("orders", Dq, history.listVersions("orders", Dq)) == 1, "the seeded pre-upgrade script is live")

        assert(scripts.regenerate(cfg, Dq, "todd").isRight)
        val all = history.listVersions("orders", Dq)
        assert(all.count(_.version == 1) == 1 && all.count(_.version == 2) == 1 && all.size == 2, s"$all")
        assert(all.head.changeNote == SeedNote && all.head.script == "print('legacy')", s"$all")
        assert(records.read("orders", Dq).get.scriptVersion == 2)
        assert(scripts.liveVersion("orders", Dq, all) == 2)
    }

    test("restoreVersion(1) appends v4 with v1's text and model, origin restore-version, and deletes v3's built-in object") {
        val cfg = csvCfg()
        scripts.onSave(null, cfg, "todd") // v1, model-a
        model = "model-b"
        scripts.regenerate(cfg, Dq, "todd") // v2
        ageContract()
        scripts.forRun(cfg, Dq, header, new RunGen().fn) // v3
        val v1 = ver(1, Dq)
        val v3Path = records.read("orders", Dq).get.scriptPath
        assert(store.objects.contains(v3Path))
        val callsBefore = calls.size

        val r = scripts.restoreVersion(cfg, Dq, 1, "ops")
        assert(r.isRight, s"$r")
        val rec = records.read("orders", Dq).get
        assert(rec.origin == "restore-version", s"$rec")
        assert(rec.model == "model-a", s"a restored script keeps the model that wrote it: $rec")
        assert(rec.status == "ready" && rec.fingerprint == fpOf(cfg), s"$rec")
        assert(rec.scriptVersion == 4, s"$rec")
        assert(r.toOption.get.scriptVersion == 4)
        assert(scripts.readText("orders", Dq).contains(v1.script), "the live script is v1's text")
        assert(!store.objects.contains(v3Path), s"v3's object is replaced, as a regenerate does: ${store.objects}")
        assert(calls.size == callsBefore, "a restore makes no model call")

        val v4 = history.get("orders", Dq, 4).getOrElse(fail(s"restore wrote no version: ${history.rows}"))
        assert(v4.script == v1.script && v4.origin == "restore-version" && v4.model == "model-a" && v4.createdBy == "ops", s"$v4")
        assert(nums() == List(1, 2, 3, 4), "earlier versions are immutable and kept")
        assert(ver(1, Dq) == v1)

        // Runs execute the restored text, no generation.
        val gen = new RunGen
        val run = scripts.forRun(cfg, Dq, header, gen.fn)
        assert(run.action == PipelineScripts.Stored && run.script == v1.script && gen.count == 0, s"$run")
    }

    test("restoreVersion of the version that is already live appends, stores and commits nothing") {
        val cfg = csvCfg()
        scripts.onSave(null, cfg, "todd") // v1
        assert(scripts.regenerate(cfg, Dq, "todd").isRight) // v2, live
        val live = records.read("orders", Dq).get
        val writesBefore = records.writes
        val objectsBefore = store.objects.toMap
        val historyBefore = history.rows.toList
        val r = scripts.restoreVersion(cfg, Dq, 2, "ops")
        assert(r == Right(live), s"$r")
        assert(records.writes == writesBefore && records.read("orders", Dq).get == live, "no record write")
        assert(store.objects.toMap == objectsBefore, "store untouched")
        assert(history.rows.toList == historyBefore, "no version appended")

        // A different version's text still restores as a new version.
        assert(scripts.restoreVersion(cfg, Dq, 1, "ops").toOption.exists(_.scriptVersion == 3))

        // Repository-backed: no empty commit either.
        beforeEach()
        val s2 = withRepo()
        s2.onSave(null, cfg, "todd") // v1 commit
        assert(records.read("orders", Dq).get.storage == "github")
        val commitsBefore = repo.writes.size
        val writes2 = records.writes
        assert(s2.restoreVersion(cfg, Dq, 1, "ops").isRight)
        assert(repo.writes.size == commitsBefore, s"no commit: ${repo.writes}")
        assert(records.writes == writes2)
        assert(history.latestVersion("orders", Dq) == 1)
    }

    test("restoreVersion of a version written for a different instruction or schema is Left and changes nothing") {
        val cfgA = csvCfg()
        val cfgB = csvCfg(dqRule = "age must be between 0 and 120")
        scripts.onSave(null, cfgA, "todd") // v1 for cfgA
        scripts.onSave(cfgA, cfgB, "todd") // v2 for cfgB
        assert(nums() == List(1, 2))
        val rowsBefore = records.rows.toMap
        val objectsBefore = store.objects.toMap
        val writesBefore = records.writes
        val historyBefore = history.rows.toList
        val callsBefore = calls.size

        val r = scripts.restoreVersion(cfgB, Dq, 1, "ops")
        assert(r.isLeft, s"$r")
        val msg = r.left.get
        assert(
            msg == "script version 1 was generated for a different instruction or source schema than pipeline orders has now; nothing was changed",
            msg
        )
        assert(records.rows.toMap == rowsBefore && records.writes == writesBefore, "records untouched")
        assert(store.objects.toMap == objectsBefore, "store untouched")
        assert(history.rows.toList == historyBefore, "no version appended")
        assert(calls.size == callsBefore)
    }

    test("restoreVersion of an unknown version, an unknown kind or another contract version is Left") {
        val cfg = csvCfg()
        scripts.onSave(null, cfg, "todd")
        val missing = scripts.restoreVersion(cfg, Dq, 9, "ops")
        assert(missing == Left("no version 9 of the dataQuality script for pipeline orders"), s"$missing")
        assert(scripts.restoreVersion(cfg, "bogus", 1, "ops") == Left(PipelineScripts.unknownKindMessage("bogus")))

        val v1 = ver(1, Dq)
        history.append(v1.copy(key = "orders|dataQuality|2", version = 2, contractVersion = PipelineScripts.contractVersion(Dq) + 1))
        val old = scripts.restoreVersion(cfg, Dq, 2, "ops")
        assert(old.isLeft && old.left.get.contains("contract version"), s"$old")
        assert(nums() == List(1, 2), s"${history.rows}")
    }

    test("restoreVersion against a repository file edited since the recorded commit is Left with the conflict marker") {
        val s = withRepo()
        val cfg = csvCfg()
        s.onSave(null, cfg, "todd") // v1 commit
        assert(s.regenerate(cfg, Dq, "todd").isRight) // v2 commit
        val path = repo.pathOf(Dq)
        repo.hubEdit(path, "print('hand edit')")
        val writesBefore = repo.writes.size

        val r = s.restoreVersion(cfg, Dq, 1, "ops")
        assert(r.isLeft, s"$r")
        assert(r.left.get.contains(PipelineScripts.ConflictMarker), r.left.get)
        assert(repo.writes.size == writesBefore, "no commit over the hand edit")
        assert(repo.head(path).contains("print('hand edit')"))
        assert(history.latestVersion("orders", Dq) == 2, s"${history.rows}")
    }

    test("restoreVersion of a repository-backed script commits v1's text forward and records the commit sha") {
        val s = withRepo()
        val cfg = csvCfg()
        s.onSave(null, cfg, "todd")
        s.regenerate(cfg, Dq, "todd")
        val v1 = ver(1, Dq)
        assert(v1.storage == "github" && v1.scriptRepoPath == repo.pathOf(Dq) && v1.scriptCommitSha != null, s"$v1")
        val r = s.restoreVersion(cfg, Dq, 1, "ops")
        assert(r.isRight, s"$r")
        val rec = records.read("orders", Dq).get
        assert(rec.scriptCommitSha == repo.headSha && rec.scriptCommitSha != v1.scriptCommitSha, "a new commit, not a re-pin")
        assert(repo.head(repo.pathOf(Dq)).contains(v1.script))
        val v3 = ver(3, Dq)
        assert(v3.scriptCommitSha == rec.scriptCommitSha && v3.scriptRepoPath == rec.scriptRepoPath, s"$v3")
    }

    test("importScript appends one version with the imported text") {
        val cfg = csvCfg()
        assert(scripts.importScript(cfg, Dq, "print('archived')", archivedRecord(cfg), "todd").isRight)
        val all = history.listVersions("orders", Dq)
        assert(all.size == 1, s"$all")
        val v = all.head
        assert(v.version == 1 && v.script == "print('archived')" && v.origin == "restore", s"$v")
        assert(v.changeNote == "imported from catalog archive", s"$v")
        assert(v.model == "model-a", s"$v")
        assert(records.read("orders", Dq).get.scriptVersion == 1)

        // Over an existing script: one more version.
        scripts.importScript(cfg, Dq, "print('archived again')", archivedRecord(cfg), "todd")
        assert(nums() == List(1, 2) && ver(2, Dq).script == "print('archived again')", s"${history.rows}")
    }

    test("pull appends one version with the pulled text and the adopted commit") {
        val s = withRepo()
        val cfg = csvCfg(txInstruction = instruction)
        s.onSave(null, cfg, "todd")
        assert(nums(Tx) == List(1))
        val editSha = repo.hubEdit(repo.pathOf(Tx), "print('hand edit')")
        assert(s.pull("orders", Tx, "todd").isRight)
        val all = history.listVersions("orders", Tx)
        assert(all.map(_.version) == List(1, 2), s"$all")
        val v2 = all(1)
        assert(v2.script == "print('hand edit')" && v2.origin == "repository", s"$v2")
        assert(v2.scriptCommitSha == editSha, s"$v2")
        assert(v2.changeNote == "adopted repository commit " + editSha.take(7), s"$v2")
        assert(v2.createdBy == "todd", s"$v2")
        assert(records.read("orders", Tx).get.scriptVersion == 2)
        assert(nums(Dq) == List(1), "the other kind is untouched")
    }

    test("script versions are pruned per pipeline|kind to versionCap, newest kept") {
        TenantContext.set(testEnv.copy(versionCap = 3))
        val cfg = csvCfg(txInstruction = instruction)
        scripts.onSave(null, cfg, "todd")
        (1 to 4).foreach(_ => assert(scripts.regenerate(cfg, Dq, "todd").isRight))
        assert(nums(Dq) == List(3, 4, 5), s"${history.rows}")
        assert(nums(Tx) == List(1), "the other kind keeps its own history")
        assert(history.pruneCalls.exists { case (p, k, n) => p == "orders" && k == Dq && n == 3 }, s"${history.pruneCalls}")
        assert(records.read("orders", Dq).get.scriptVersion == 5)
        assert(history.get("orders", Dq, 5).map(_.script) == scripts.readText("orders", Dq))
    }

    test("deleteAll empties the pipeline's script history; dropping one instruction keeps that kind's history") {
        val cfg = csvCfg(txInstruction = instruction)
        scripts.onSave(null, cfg, "todd")
        scripts.onSave(cfg, csvCfg(), "todd") // the transformation instruction is dropped
        assert(records.read("orders", Tx).isEmpty)
        assert(nums(Tx) == List(1), "removeKind keeps history (the instruction may come back)")

        scripts.deleteAll("orders")
        assert(history.rows.filter(_.pipeline == "orders").isEmpty, s"${history.rows}")
    }

    test("a scratch rehearsal run writes no script version") {
        val cfg = csvCfg()
        PipelineScripts.withRehearsal(true)(scripts.forRun(cfg, Dq, header, new RunGen().fn))
        assert(history.rows.isEmpty, s"${history.rows}")
        scripts.onSave(null, cfg, "todd")
        ageContract()
        PipelineScripts.withRehearsal(true)(scripts.forRun(cfg, Dq, header, new RunGen().fn))
        assert(nums() == List(1), s"${history.rows}")
    }

    test("a failing history store never fails the save, run or regenerate") {
        val broken = new CodeGenScriptVersions {
            override def listVersions(pipeline: String, kind: String): List[CodeGenScriptVersion] = throw new RuntimeException("history down")
            override def latestVersion(pipeline: String, kind: String): Int = throw new RuntimeException("history down")
            override def get(pipeline: String, kind: String, version: Int): Option[CodeGenScriptVersion] = throw new RuntimeException("history down")
            override def append(v: CodeGenScriptVersion): Unit = throw new RuntimeException("history down")
            override def prune(pipeline: String, kind: String, keep: Int): Unit = throw new RuntimeException("history down")
            override def deleteAllForPipeline(pipeline: String): Unit = throw new RuntimeException("history down")
        }
        val s = new PipelineScripts(records, _ => store, fakeAi, () => model, versions = broken)
        val cfg = csvCfg()
        val out =
            try s.onSave(null, cfg, "todd")
            catch { case e: Throwable => fail("a history failure must not fail the save: " + e) }
        assert(out.map(o => (o.kind, o.status)) == List((Dq, "ready")), s"$out")
        assert(s.readText("orders", Dq).contains("print('generated 1')"), "the live script is stored")
        assert(s.regenerate(cfg, Dq, "todd").isRight)
        try s.deleteAll("orders")
        catch { case e: Throwable => fail("deleteAll never throws: " + e) }
    }
}
