package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import ai.datris.util._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: Restore honours archived CodeGen scripts
  * (plans/stories/multi-env-3-restore-import-codegen-scripts.md), the
  * controller-level Acceptance bullet. No Spring, Mongo or model: the
  * archive is built with CatalogArchive.archiveEntries / readContents and
  * the scripts live in a PipelineScripts with in-memory records and store
  * (the PipelineScriptsSpec fakes); the model call is a recording stub.
  *
  * Pinned seam (step 2 of restoreCatalog, before savePipelineInternal):
  * {{{
  * object CatalogAPIController {
  *     /** What restore did with one pipeline's archived AI scripts.
  *       * `imported`: the records importScript wrote.
  *       * `regenerated`: ("<pipeline>/<kind>", reason) for every AI kind the
  *       * archived pipeline has that was not imported (its next save
  *       * regenerates it). */
  *     case class ScriptImport(imported: Seq[CodeGenScript], regenerated: Seq[(String, String)])
  *
  *     /** For each AI kind of `pipelineName`'s archived config: import the
  *       * archived .py with its .json sidecar through scripts.importScript.
  *       * A .py with no sidecar is reported with the reason
  *       * "no script record in the archive; re-export to carry it". With
  *       * `regenerateScripts` nothing is imported and every kind is reported
  *       * under `regenerated`. */
  *     private[api] def importCodegenScripts(contents: CatalogArchive.Contents, pipelineName: String,
  *                                           scripts: PipelineScripts, actor: String,
  *                                           warnings: mutable.ArrayBuffer[String],
  *                                           regenerateScripts: Boolean = false): ScriptImport
  * }
  * }}}
  */
class CatalogRestoreScriptsSpec extends AnyFunSuite with BeforeAndAfterEach {

    private class MemRecords extends CodeGenScriptRecords {
        val rows = mutable.LinkedHashMap[(String, String), CodeGenScript]()
        override def read(pipeline: String, kind: String): Option[CodeGenScript] = rows.get((pipeline, kind))
        override def write(record: CodeGenScript): Unit = rows((record.pipeline, record.kind)) = record
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

    private object Env extends AiSampleValuesMarkers

    private val calls = mutable.ListBuffer[(String, String)]()
    private val fakeAi: (String, String) => String = (system, user) => {
        calls += ((system, user))
        "print('generated " + calls.size + "')"
    }

    private var records: MemRecords = _
    private var store: MemStore = _
    private var scripts: PipelineScripts = _

    override def beforeEach(): Unit = {
        calls.clear()
        records = new MemRecords
        store = new MemStore
        scripts = new PipelineScripts(records, _ => store, fakeAi, () => "model-b")
        TenantContext.set(Env.testEnv)
    }

    override def afterEach(): Unit = TenantContext.clear()

    private val Dq = "dataQuality"
    private val Tx = "transformation"
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

    private def sidecar(kind: String): CodeGenScript = {
        val instr = PipelineScripts.instructionOf(cfg, kind).get
        CatalogArchive.stripCodeGenRecordForExport(
            CodeGenScript(
                pipeline = "orders",
                kind = kind,
                instruction = instr,
                script = null,
                generatedAt = "2026-10-01T12:00:00Z",
                storage = "minio",
                scriptPath = "pipeline-scripts/orders/" + kind + "_9.py",
                fingerprint = PipelineScripts.fingerprint(instr, PipelineScripts.schemaSignature(cfg)),
                model = "model-a",
                status = "ready",
                origin = "save",
                contractVersion = PipelineScripts.contractVersion(kind)
            )
        )
    }

    private val archivedText = Map(Dq -> "print('archived dq')", Tx -> "print('archived tx')")

    /** The archive as readContents returns it; `withRecords` false is a
      * v1.48.0-v1.50.0 archive (.py only). */
    private def contents(withRecords: Boolean): CatalogArchive.Contents = {
        val mf = CatalogArchive.Manifest(
            formatVersion = CatalogArchive.FormatVersion,
            catalog = "sales",
            exportedAt = "2026-10-15 10:00:00",
            datrisVersion = "1.50.0",
            taps = Nil,
            pipelines = Seq("orders"),
            requiredSecrets = Nil,
            warnings = Nil
        )
        val entries = CatalogArchive.archiveEntries(
            mf,
            Nil,
            Seq(CatalogArchive.stripPipelineForExport(cfg.copy(catalog = "sales"))),
            codegen = Map("orders" -> archivedText),
            codegenRecords = if (withRecords) Map("orders" -> Map(Dq -> sidecar(Dq), Tx -> sidecar(Tx))) else Map.empty
        )
        val map = CatalogArchive.unpack(CatalogArchive.pack(entries)).fold(e => fail(e), identity)
        CatalogArchive.readContents(mf, map).fold(e => fail(e), identity)
    }

    test("importCodegenScripts on an archive with .py only reports scriptsRegenerated with the re-export reason") {
        val warnings = mutable.ArrayBuffer.empty[String]
        val out = CatalogAPIController.importCodegenScripts(contents(withRecords = false), "orders", scripts, "todd", warnings)
        assert(out.imported.isEmpty, s"${out.imported}")
        assert(out.regenerated.map(_._1).toSet == Set("orders/dataQuality", "orders/transformation"), s"${out.regenerated}")
        out.regenerated.foreach { case (item, reason) =>
            assert(reason == "no script record in the archive; re-export to carry it", s"$item: $reason")
        }
        assert(records.rows.isEmpty, "nothing imported")
        // The save that follows takes the regenerate path, as before the change.
        scripts.onSave(null, cfg, "todd")
        assert(calls.size == 2, s"one generation per kind: ${calls.size}")
    }

    test("regenerateScripts=true skips the import and reports every kind under scriptsRegenerated") {
        val warnings = mutable.ArrayBuffer.empty[String]
        val out = CatalogAPIController.importCodegenScripts(contents(withRecords = true), "orders", scripts, "todd", warnings, regenerateScripts = true)
        assert(out.imported.isEmpty, s"${out.imported}")
        assert(out.regenerated.map(_._1).toSet == Set("orders/dataQuality", "orders/transformation"), s"${out.regenerated}")
        assert(out.regenerated.forall(r => r._2 != null && r._2.trim.nonEmpty), s"each with a reason: ${out.regenerated}")
        assert(records.rows.isEmpty && store.objects.isEmpty, "nothing imported")
        assert(calls.isEmpty, "importCodegenScripts itself makes no model call")
    }

    test("importCodegenScripts with matching sidecars imports both kinds and the save that follows makes no model call") {
        val warnings = mutable.ArrayBuffer.empty[String]
        val out = CatalogAPIController.importCodegenScripts(contents(withRecords = true), "orders", scripts, "todd", warnings)
        assert(out.regenerated.isEmpty, s"${out.regenerated}")
        assert(out.imported.map(_.kind).toSet == Set(Dq, Tx), s"${out.imported}")
        out.imported.foreach { r =>
            assert(r.origin == "restore" && r.model == "model-a" && r.generatedAt == "2026-10-01T12:00:00Z", s"$r")
        }
        scripts.onSave(null, cfg, "todd")
        assert(calls.isEmpty, s"no model call: ${calls.size}")
        Seq(Dq, Tx).foreach { k =>
            assert(scripts.readText("orders", k).contains(archivedText(k)), k)
            assert(scripts.record("orders", k).exists(_.origin == "restore"), k)
        }
    }

    test("importCodegenScripts reports a sidecar whose fingerprint does not match the archived config as regenerated") {
        val warnings = mutable.ArrayBuffer.empty[String]
        val c = contents(withRecords = true)
        val stale = c.copy(codegenRecords = Map("orders" -> Map(Dq -> sidecar(Dq).copy(fingerprint = "not-this-config"), Tx -> sidecar(Tx))))
        val out = CatalogAPIController.importCodegenScripts(stale, "orders", scripts, "todd", warnings)
        assert(out.imported.map(_.kind) == Seq(Tx), s"${out.imported}")
        val dq = out.regenerated.find(_._1 == "orders/dataQuality").getOrElse(fail(s"dataQuality not reported: ${out.regenerated}"))
        assert(dq._2.contains("instruction or source schema"), dq._2)
    }

    test("importCodegenScripts reports an unreadable or mismatched sidecar with its own reason, not the re-export one") {
        val mf = CatalogArchive.Manifest(CatalogArchive.FormatVersion, "sales", "2026-10-15 10:00:00", "1.50.0", Nil, Seq("orders"), Nil, Nil)
        val entries = CatalogArchive.archiveEntries(
            mf,
            Nil,
            Seq(CatalogArchive.stripPipelineForExport(cfg.copy(catalog = "sales"))),
            codegen = Map("orders" -> archivedText),
            codegenRecords = Map("orders" -> Map(Dq -> sidecar(Dq), Tx -> sidecar(Tx).copy(pipeline = "refunds")))
        )
        val map = entries.map(e => e.path -> e.bytes).toMap +
            (CatalogArchive.codegenRecordPath("orders", Dq) -> "{not json".getBytes("UTF-8"))
        val c = CatalogArchive.readContents(mf, map).fold(e => fail(e), identity)
        assert(c.codegenRecords.getOrElse("orders", Map.empty).isEmpty, c.codegenRecords)
        val out = CatalogAPIController.importCodegenScripts(c, "orders", scripts, "todd", mutable.ArrayBuffer.empty[String])
        assert(out.imported.isEmpty, s"${out.imported}")
        val reasons = out.regenerated.toMap
        assert(reasons("orders/dataQuality") == "the script record in the archive could not be read", reasons)
        assert(reasons("orders/transformation") == "the script record in the archive names refunds/transformation", reasons)
    }

    // E2E A1: an overwrite restore whose archived script is not imported, onto
    // a pipeline that already has a usable live script, keeps that script; it
    // is reported under kept, not regenerated.

    private def snapshot(): Map[String, Option[CodeGenScript]] = Seq(Dq, Tx).map(k => k -> scripts.record("orders", k)).toMap

    test("classifyScripts reports a usable live script the save kept as kept, not regenerated") {
        scripts.onSave(null, cfg, "todd") // the target already runs this pipeline
        val live = snapshot()
        val callsBefore = calls.size
        val warnings = mutable.ArrayBuffer.empty[String]
        val plan = CatalogAPIController.importCodegenScripts(contents(withRecords = false), "orders", scripts, "todd", warnings)
        val before = snapshot()
        scripts.onSave(cfg, cfg, "todd") // savePipelineInternal's hook
        val report = CatalogAPIController.classifyScripts(cfg, plan, before, scripts, "todd", warnings, regenerateScripts = false)
        assert(calls.size == callsBefore, "nothing generated")
        assert(snapshot() == live, "live records unchanged")
        assert(report.imported.isEmpty && report.regenerated.isEmpty, s"$report")
        assert(
            report.kept.toMap == Map(
                "orders/dataQuality" -> CatalogAPIController.NoRecordReason,
                "orders/transformation" -> CatalogAPIController.NoRecordReason
            ),
            s"$report"
        )
    }

    test("classifyScripts on a fresh target reports the scripts the save generated as regenerated") {
        val warnings = mutable.ArrayBuffer.empty[String]
        val plan = CatalogAPIController.importCodegenScripts(contents(withRecords = false), "orders", scripts, "todd", warnings)
        val before = snapshot()
        scripts.onSave(null, cfg, "todd")
        val report = CatalogAPIController.classifyScripts(cfg, plan, before, scripts, "todd", warnings, regenerateScripts = false)
        assert(calls.size == 2)
        assert(report.kept.isEmpty && report.imported.isEmpty, s"$report")
        assert(report.regenerated.map(_._1).toSet == Set("orders/dataQuality", "orders/transformation"), s"$report")
    }

    test("classifyScripts with regenerateScripts regenerates a live script the save kept, and reports it regenerated") {
        scripts.onSave(null, cfg, "todd")
        val callsBefore = calls.size
        val warnings = mutable.ArrayBuffer.empty[String]
        val plan = CatalogAPIController.importCodegenScripts(contents(withRecords = true), "orders", scripts, "todd", warnings, regenerateScripts = true)
        val before = snapshot()
        scripts.onSave(cfg, cfg, "todd")
        val report = CatalogAPIController.classifyScripts(cfg, plan, before, scripts, "todd", warnings, regenerateScripts = true)
        assert(calls.size == callsBefore + 2, "one generation per kind")
        assert(report.kept.isEmpty, s"$report")
        assert(report.regenerated.map(_._1).toSet == Set("orders/dataQuality", "orders/transformation"), s"$report")
    }
}
