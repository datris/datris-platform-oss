package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.Gson
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Review follow-ups to the pipeline rename
  * (plans/stories/pipeline-rename-keeps-data.md): previous-name aliases,
  * evidence for resuming a half-finished rename, and the I/O helpers that
  * have injectable seams (script copy and history, importScript's new
  * parameters, the pull-table move). */
class PipelineRenameFollowUpSpec extends AnyFunSuite with BeforeAndAfterEach with AiSampleValuesMarkers {

    // ------------------------------------------------------------ fixtures

    private def pg(name: String, catalog: String = "Sales", prev: Seq[String] = null, table: String = "rows"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            destination = Destination(database = Database(dbName = "datris", schema = "public", table = table, usePostgres = true)),
            previousNames = if (prev == null) null else new java.util.ArrayList[String](prev.asJava)
        )

    private def row(entity: String, v: Int, note: String, at: String = null): EntityVersion =
        EntityVersion(key = entity + "|" + v, entityName = entity, version = v, config = "{}", changeNote = note, createdAt = Option(at).getOrElse("t" + v))

    // ------------------------------------------------------- aliases (rule)

    test("ownerOf: a current name wins; else the single pipeline listing it; several or none own nothing") {
        val b = pg("b", prev = Seq("a"))
        assert(PipelineAliases.ownerOf("b", Seq(b)) eq b)
        assert(PipelineAliases.ownerOf("a", Seq(b)) eq b)
        assert(PipelineAliases.ownerOf("zzz", Seq(b)) == null)
        // A pipeline later created under the old name wins the records filed under it.
        val newA = pg("a", catalog = "HR")
        assert(PipelineAliases.ownerOf("a", Seq(b, newA)) eq newA)
        assert(!PipelineAliases.aliasOwners(Seq(b, newA)).contains("a"))
        // Two pipelines listing the same old name: nobody owns it.
        val c = pg("c", prev = Seq("a"))
        assert(PipelineAliases.ownerOf("a", Seq(b, c)) == null)
        assert(PipelineAliases.ownerOf(null, Seq(b)) == null)
    }

    test("recordNames adds a visible pipeline's unclaimed previous names, never another pipeline's") {
        val b = pg("b", prev = Seq("a"))
        val hidden = pg("h", catalog = "HR", prev = Seq("old_h"))
        val all = Seq(b, hidden)
        assert(PipelineAliases.recordNames(Seq(b), all) == Set("b", "a"))
        assert(!PipelineAliases.recordNames(Seq(b), all).contains("old_h"))
        // live pipeline under the old name: the alias no longer applies to b
        val newA = pg("a", catalog = "HR")
        assert(PipelineAliases.recordNames(Seq(b), all :+ newA) == Set("b"))
        assert(PipelineAliases.recordNames(Seq(newA), all :+ newA) == Set("a"))
    }

    test("carriedOver keeps the stored previousNames and never the caller's") {
        val stored = pg("b", prev = Seq("a"))
        val claimed = pg("b", prev = Seq("someone_elses_old_name"))
        assert(PipelineAliases.carriedOver(claimed, stored).previousNames.asScala == Seq("a"))
        // a new pipeline starts with none, whatever the body says
        assert(PipelineAliases.carriedOver(pg("x", prev = Seq("h_old")), null).previousNames == null)
        assert(PipelineAliases.carriedOver(null, stored) == null)
    }

    test("afterRename appends the current name, drops the new name and duplicates") {
        assert(PipelineAliases.afterRename(pg("a"), "b").asScala == Seq("a"))
        assert(PipelineAliases.afterRename(pg("b", prev = Seq("a")), "c").asScala == Seq("a", "b"))
        // renaming back: the name it now carries is not a previous name
        assert(PipelineAliases.afterRename(pg("b", prev = Seq("a")), "a").asScala == Seq("b"))
    }

    // ---------------------------------------------- resume needs evidence

    test("resumable ignores previousNames") {
        val old = pg("a")
        val half = PipelineRename.renamedConfig(old, "b").copy(previousNames = PipelineAliases.afterRename(old, "b"), version = 7)
        assert(PipelineRename.resumable(old, half))
    }

    test("priorAttempt: the rename's own latest version or a copied history is evidence; an unrelated history is not") {
        val oldRows = Seq(row("a", 1, "created"), row("a", 2, "updated"))
        val copied = oldRows.map(PipelineRename.rekeyVersion(_, "b"))
        // stopped after the new config was written
        assert(PipelineRename.priorAttempt("a", "b", copied :+ row("b", 3, "renamed a -> b by todd"), oldRows))
        assert(PipelineRename.priorAttempt("a", "b", copied :+ row("b", 3, "renamed a -> b"), oldRows))
        // stopped after the copy, before the rename's own version (or with the old rows already gone)
        assert(PipelineRename.priorAttempt("a", "b", copied, oldRows))
        assert(PipelineRename.priorAttempt("a", "b", Seq(row("b", 4, PipelineRename.seedNote("a"))), Nil))
        // an unrelated pipeline b with the same config: 409
        assert(!PipelineRename.priorAttempt("a", "b", Seq(row("b", 1, "created", "x1"), row("b", 2, "updated", "x2")), oldRows))
        assert(!PipelineRename.priorAttempt("a", "b", Seq(row("b", 1, "renamed a -> bb by todd")), Nil))
        assert(!PipelineRename.priorAttempt("a", "b", Seq(row("b", 1, "renamed z -> b by todd")), Nil))
        // no rows is not evidence
        assert(!PipelineRename.priorAttempt("a", "b", Nil, oldRows))
    }

    // ---------------------------------------------------- pull-table move

    test("PipelinePullTableUtil.renameWith keeps the pull position and leaves no entry under the old name") {
        val gson = new Gson
        val table = mutable.Map[String, String]("a" -> gson.toJson(PipelinePull("a", "2026-11-18 00:00:00.000", "2026-11-17 09:00:00")))
        val inits = mutable.ListBuffer[(String, String)]()
        def run(cron: String): Unit =
            PipelinePullTableUtil.renameWith("a", "b", cron, table.get, (k, v) => table(k) = v, k => table.remove(k), (p, c) => inits += ((p, c)))
        run("0 0 * * * ?")
        assert(!table.contains("a"))
        val moved = gson.fromJson(table("b"), classOf[PipelinePull])
        assert(moved == PipelinePull("b", "2026-11-18 00:00:00.000", "2026-11-17 09:00:00"))
        assert(inits.isEmpty, "a moved entry is never re-initialized")
        // nothing under the old name (or a resumed rename): the new entry is kept as is
        run("0 0 * * * ?")
        assert(gson.fromJson(table("b"), classOf[PipelinePull]) == moved && inits.isEmpty)
        // nothing under either name: the new one is initialized from the cron
        table.clear()
        run("0 0 * * * ?")
        assert(inits == Seq(("b", "0 0 * * * ?")))
    }

    // ---------------------------------------------------- script copy

    private class MemRecords extends CodeGenScriptRecords {
        val rows = mutable.LinkedHashMap[(String, String), CodeGenScript]()
        override def read(pipeline: String, kind: String): Option[CodeGenScript] = rows.get((pipeline, kind))
        override def write(record: CodeGenScript): Unit = rows((record.pipeline, record.kind)) = record
        override def delete(pipeline: String, kind: String): Unit = rows.remove((pipeline, kind))
    }

    private val objects = mutable.LinkedHashMap[String, String]()
    private var failStoreFor: String = null

    private class MemStore(pipeline: String) extends CodeStore {
        private var n = 0
        override def storage: String = "minio"
        override def readScript(ref: ScriptRef): Option[String] = Option(ref).flatMap(r => Option(r.scriptPath)).flatMap(objects.get)
        override def storeScript(name: String, script: String, prior: ScriptRef, actor: String): StoredScript = {
            if (pipeline == failStoreFor) throw new RuntimeException("object store unreachable")
            n += 1
            val path = "pipeline-scripts/" + pipeline + "/" + name + "_" + n + ".py"
            objects(path) = script
            StoredScript("minio", scriptPath = path)
        }
        override def deleteScript(ref: ScriptRef): Unit = if (ref != null && ref.scriptPath != null) objects.remove(ref.scriptPath)
        override def scriptExists(ref: ScriptRef): Boolean = readScript(ref).isDefined
    }

    private class MemVersions extends CodeGenScriptVersions {
        val rows = mutable.ListBuffer[CodeGenScriptVersion]()
        var failList = false
        override def listVersions(pipeline: String, kind: String): List[CodeGenScriptVersion] = {
            if (failList) throw new RuntimeException("history unreadable")
            rows.filter(v => v.pipeline == pipeline && v.kind == kind).sortBy(_.version).toList
        }
        override def latestVersion(pipeline: String, kind: String): Int =
            (0 :: rows.filter(v => v.pipeline == pipeline && v.kind == kind).map(_.version).toList).max
        override def get(pipeline: String, kind: String, version: Int): Option[CodeGenScriptVersion] =
            rows.find(v => v.pipeline == pipeline && v.kind == kind && v.version == version)
        override def append(v: CodeGenScriptVersion): Unit = rows += v
        override def prune(pipeline: String, kind: String, keep: Int): Unit = ()
        override def deleteAllForPipeline(pipeline: String): Unit = rows --= rows.filter(_.pipeline == pipeline).toList
    }

    private var records: MemRecords = _
    private var history: MemVersions = _
    private var scripts: PipelineScripts = _
    private var aiCalls = 0

    override def beforeEach(): Unit = {
        objects.clear(); failStoreFor = null; aiCalls = 0
        records = new MemRecords
        history = new MemVersions
        scripts = new PipelineScripts(
            records,
            p => new MemStore(p),
            (_, _) => { aiCalls += 1; "print('generated " + aiCalls + "')" },
            () => "model-a",
            versions = history
        )
        TenantContext.set(testEnv)
    }

    override def afterEach(): Unit = TenantContext.clear()

    private val Dq = PipelineScripts.DataQuality

    private def csv(name: String): PipelineConfig =
        PipelineConfig(
            name = name,
            source = Source(
                schemaProperties = SchemaProperties(null, Seq(SchemaField("id", "string"), SchemaField("age", "int")).asJava),
                fileAttributes = FileAttributes(csvAttributes = CsvAttributes(delimiter = ","))
            ),
            dataQuality = DataQuality(aiRule = AIRule("age must be positive")),
            destination = Destination(database = Database(dbName = "d", schema = "public", table = "t", usePostgres = true))
        )

    /** `a` with a saved script and two versions of it. */
    private def savedA(): PipelineConfig = {
        val a = csv("a")
        scripts.onSave(null, a, "todd")
        scripts.regenerate(a, Dq, "todd")
        assert(history.listVersions("a", Dq).map(_.version) == List(1, 2), s"${history.rows}")
        a
    }

    test("copyForRename + copyHistoryForRename: same text and model under the new name, numbered after the re-keyed history, no model call") {
        val a = savedA()
        val calls = aiCalls
        val oldRec = records.read("a", Dq).get
        val b = PipelineRename.renamedConfig(a, "b")
        assert(scripts.copyForRename(a, b, "todd") == Right(Nil))
        assert(scripts.copyHistoryForRename("a", "b").isEmpty)
        assert(aiCalls == calls, "a rename never calls the model")
        val rec = records.read("b", Dq).get
        assert(scripts.readText("b", Dq) == scripts.readText("a", Dq))
        assert(rec.model == oldRec.model && rec.generatedAt == oldRec.generatedAt && rec.fingerprint == oldRec.fingerprint)
        assert(rec.origin == oldRec.origin, "the copy keeps the old record's origin")
        assert(rec.scriptPath.startsWith("pipeline-scripts/b/"), rec.scriptPath)
        val rows = history.listVersions("b", Dq)
        assert(rows.map(_.version) == List(1, 2, 3), s"$rows")
        assert(rows.last.changeNote == "renamed from a" && rec.scriptVersion == 3)
        assert(rows.take(2).map(_.script) == history.listVersions("a", Dq).map(_.script))
        assert(rows.forall(r => r.pipeline == "b" && r.key.startsWith("b|")))
        // a repeated (resumed) history copy adds nothing
        assert(scripts.copyHistoryForRename("a", "b").isEmpty)
        assert(history.listVersions("b", Dq).size == 3)
        // the old scripts are untouched until the caller deletes them
        assert(records.read("a", Dq).isDefined && scripts.readText("a", Dq).isDefined)
    }

    test("copyForRename refuses when a script that runs today cannot be copied, and skips a stale one with a warning") {
        val a = savedA()
        failStoreFor = "b"
        val r = scripts.copyForRename(a, PipelineRename.renamedConfig(a, "b"), "todd")
        assert(r.isLeft && r.left.get.contains("could not be copied"), s"$r")
        assert(records.read("b", Dq).isEmpty)

        // stale: the instruction changed since the script was generated
        failStoreFor = null
        val changed = a.copy(dataQuality = DataQuality(aiRule = AIRule("age must be below 200")))
        val w = scripts.copyForRename(changed, PipelineRename.renamedConfig(changed, "b"), "todd")
        assert(w.isRight && w.right.get.size == 1 && w.right.get.head.contains("not copied"), s"$w")
        assert(records.read("b", Dq).isEmpty)
    }

    test("a failed history copy is a warning, and deleteAll(keepHistory = true) keeps the old history") {
        savedA()
        history.failList = true
        val w = scripts.copyHistoryForRename("a", "b")
        history.failList = false
        assert(w.nonEmpty && w.head.contains("could not be copied"), s"$w")
        scripts.deleteAll("a", keepHistory = true)
        assert(records.read("a", Dq).isEmpty, "live records go")
        assert(history.rows.count(_.pipeline == "a") == 2, "history kept")
        scripts.deleteAll("a")
        assert(history.rows.count(_.pipeline == "a") == 0)
    }

    test("importScript: defaults are the catalog restore's; changeNote, origin and versionFloor apply when given") {
        val a = savedA()
        val archived = records.read("a", Dq).get
        val x = csv("x")
        assert(scripts.importScript(x, Dq, "print('archived')", archived.copy(pipeline = "x"), "todd").isRight)
        assert(records.read("x", Dq).get.origin == "restore")
        assert(history.listVersions("x", Dq).map(v => (v.version, v.changeNote)) == List((1, "imported from catalog archive")))

        val y = csv("y")
        assert(scripts.importScript(y, Dq, "print('copied')", archived.copy(pipeline = "y"), "todd", "renamed from a", "save", versionFloor = 5).isRight)
        val rec = records.read("y", Dq).get
        assert(rec.origin == "save" && rec.scriptVersion == 6)
        assert(history.listVersions("y", Dq).map(v => (v.version, v.changeNote, v.origin)) == List((6, "renamed from a", "save")))
        // a floor below the next number changes nothing
        assert(scripts.importScript(y, Dq, "print('again')", archived.copy(pipeline = "y"), "todd", versionFloor = 2).isRight)
        assert(records.read("y", Dq).get.scriptVersion == 7)
        assert(a.name == "a")
    }
}
