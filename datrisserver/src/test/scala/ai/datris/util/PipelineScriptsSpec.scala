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
}
