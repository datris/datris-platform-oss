package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

class ColumnLineageServiceSpec extends AnyFunSuite {

    private def fields(names: String*): SchemaProperties = SchemaProperties(null, names.map(n => SchemaField(n, "string")).asJava)

    private def cfg(
        src: Seq[String],
        dst: Seq[String] = null,
        tx: ai.datris.model.Transformation = null,
        stamp: Boolean = false,
        stampFields: Seq[String] = null
    ): PipelineConfig =
        PipelineConfig(
            name = "p",
            source = Source(schemaProperties = if (src == null) null else fields(src: _*)),
            destination = Destination(
                schemaProperties = if (dst == null) null else fields(dst: _*),
                database = Database(dbName = "d", schema = "public", table = "t", usePostgres = true)
            ),
            transformation = tx,
            provenance = if (stamp) ProvenanceConfig(stamp = true, fields = if (stampFields == null) null else stampFields.asJava) else null
        )

    test("no transformation, no declared destination: every source field passes through (inherited)") {
        val (edges, unresolved, label) = ColumnLineageService.deterministic(cfg(Seq("id", "name")))
        assert(label == "inherited")
        assert(edges.map(e => (e.from.asScala.toList, e.to, e.op, e.confidence)) == List(
            (List("id"), "id", "passthrough", "exact"),
            (List("name"), "name", "passthrough", "exact")
        ))
        assert(unresolved.isEmpty)
    }

    test("declared destination without transformation: matches are exact, extras are drops, new ones unresolved") {
        val (edges, unresolved, label) = ColumnLineageService.deterministic(cfg(Seq("id", "email", "salary"), Seq("id", "salary", "band")))
        assert(label == "declared")
        assert(edges.exists(e => e.to == "id" && e.op == "passthrough"))
        assert(edges.exists(e => e.from.asScala.toList == List("email") && e.op == "drop" && e.to == ""))
        assert(unresolved == List("band"))
    }

    test("with an AI transformation nothing is declared dropped and destination defaults to none") {
        val tx = ai.datris.model.Transformation(aiTransformation = AITransformation("add full_name from first and last"))
        val (edges, _, label) = ColumnLineageService.deterministic(cfg(Seq("first", "last"), null, tx))
        assert(label == "none")
        assert(!edges.exists(_.op == "drop"))
        val (edges2, unresolved2, _) = ColumnLineageService.deterministic(cfg(Seq("first", "last"), Seq("first", "full_name"), tx))
        assert(edges2.map(_.to) == List("first"))
        assert(unresolved2 == List("full_name"))
    }

    test("stamped provenance columns are system edges, filtered by the selected subset") {
        val (edges, _, _) = ColumnLineageService.deterministic(cfg(Seq("id"), null, null, stamp = true, stampFields = Seq("_datris_run_id")))
        val sys = edges.filter(_.confidence == "system")
        assert(sys.map(_.to) == List("_datris_run_id"))
        assert(sys.head.from.isEmpty)
        val (all, _, _) = ColumnLineageService.deterministic(cfg(Seq("id"), null, null, stamp = true))
        assert(all.count(_.confidence == "system") == ProvenanceStamper.AllFields.size)
    }

    test("declared stamped columns on the destination are not double-counted") {
        val (edges, unresolved, _) = ColumnLineageService.deterministic(cfg(Seq("id"), Seq("id", "_datris_run_id"), null, stamp = true))
        assert(edges.count(_.to == "_datris_run_id") == 1)
        assert(unresolved.isEmpty)
    }

    test("parseInferred keeps only evidenced mappings over known fields, strips fences") {
        val text =
            """```json
              |[{"from":["first","last"],"to":"full_name","op":"derive","evidence":"first + ' ' + last"},
              | {"from":["email"],"to":"","op":"drop","evidence":"drop email"},
              | {"from":["ghost"],"to":"full_name","op":"rename","evidence":"x"},
              | {"from":["first"],"to":"nope","op":"rename","evidence":"x"},
              | {"from":["first"],"to":"full_name","op":"teleport","evidence":"x"}]
              |```""".stripMargin
        val edges = ColumnLineageService.parseInferred(text, List("first", "last", "email"), List("first", "full_name"))
        assert(edges.map(e => (e.from.asScala.toList, e.to, e.op)) == List((List("first", "last"), "full_name", "derive"), (List("email"), "", "drop")))
        assert(edges.forall(_.confidence == "inferred"))
        assert(edges.head.evidence == "first + ' ' + last")
    }

    test("parseInferred returns nothing for prose, empty arrays or garbage") {
        assert(ColumnLineageService.parseInferred("I cannot determine any mappings.", List("a"), List("b")).isEmpty)
        assert(ColumnLineageService.parseInferred("[]", List("a"), List("b")).isEmpty)
        assert(ColumnLineageService.parseInferred(null, List("a"), List("b")).isEmpty)
    }

    test("transformationInfo classifies ai, rowFunctions, preprocessor, none") {
        assert(ColumnLineageService.transformationInfo(cfg(Seq("a"))).get("kind").getAsString == "none")
        assert(ColumnLineageService.transformationInfo(cfg(Seq("a"), null, ai.datris.model.Transformation(aiTransformation = AITransformation("x")))).get(
            "kind"
        ).getAsString == "ai")
        assert(ColumnLineageService.transformationInfo(cfg(
            Seq("a"),
            null,
            ai.datris.model.Transformation(rowFunctions = List(RowFunction("javascript", null)).asJava)
        )).get("kind").getAsString == "rowFunctions")
        assert(ColumnLineageService.transformationInfo(cfg(Seq("a")).copy(preprocessor = RestEndpoint("http://x"))).get("kind").getAsString == "preprocessor")
    }

    // --- Field protection (story: field-protection-1-stage) ------------------
    // Parsed with Gson so the fixture is the same document a stored pipeline is.

    private def protectedCfg(destFields: String = null): PipelineConfig = {
        val dst = if (destFields == null) "" else s""","schemaProperties":{"fields":$destFields}"""
        new com.google.gson.Gson().fromJson(
            s"""{"name":"p",
               |"source":{"fileAttributes":{"csvAttributes":{}},"schemaProperties":{"fields":[
               |{"name":"id","type":"string"},
               |{"name":"mrn","type":"string","protect":{"method":"hmac"}},
               |{"name":"email","type":"string","protect":{"method":"mask","preserve":"domain"}},
               |{"name":"phone","type":"string","protect":{"method":"mask"}},
               |{"name":"notes","type":"string","protect":{"method":"redact"}},
               |{"name":"ssn","type":"string","protect":{"method":"drop"}}]}},
               |"destination":{"database":{"dbName":"d","schema":"public","table":"t","usePostgres":true}$dst}}""".stripMargin,
            classOf[PipelineConfig]
        )
    }

    private def edgeTuples(edges: List[ColumnEdge]) = edges.map(e => (e.from.asScala.toList, e.to, e.op, e.confidence, e.evidence))

    test("protected fields are derive edges with the method as evidence") {
        val c = protectedCfg()
        assert(ColumnLineageService.transformationInfo(c).get("kind").getAsString == "protect")
        val (edges, unresolved, _) = ColumnLineageService.deterministic(c)
        val t = edgeTuples(edges)
        assert(t.contains((List("id"), "id", "passthrough", "exact", null)), s"$t")
        assert(t.contains((List("mrn"), "mrn", "derive", "exact", "hmac")), s"$t")
        assert(t.contains((List("email"), "email", "derive", "exact", "mask:domain")), s"$t")
        assert(t.contains((List("phone"), "phone", "derive", "exact", "mask")), s"$t")
        assert(t.contains((List("notes"), "notes", "derive", "exact", "redact")), s"$t")
        assert(
            !t.exists(e => e._3 == "passthrough" && Set("mrn", "email", "phone", "notes", "ssn").contains(e._2)),
            s"protected fields are not passthrough: $t"
        )
        assert(unresolved.isEmpty)
        // Same with a declared destination.
        val (declared, _, label) = ColumnLineageService.deterministic(protectedCfg(
            """[{"name":"id","type":"string"},{"name":"mrn","type":"string"},{"name":"email","type":"string"}]"""
        ))
        assert(label == "declared")
        assert(edgeTuples(declared).contains((List("mrn"), "mrn", "derive", "exact", "hmac")), s"${edgeTuples(declared)}")
    }

    test("dropped fields are drop edges even when declared in the destination") {
        val (edges, unresolved, _) = ColumnLineageService.deterministic(protectedCfg(
            """[{"name":"id","type":"string"},{"name":"mrn","type":"string"},{"name":"ssn","type":"string"}]"""
        ))
        val t = edgeTuples(edges)
        assert(t.exists(e => e._1 == List("ssn") && e._2 == "" && e._3 == "drop" && e._4 == "exact"), s"$t")
        assert(!t.exists(_._2 == "ssn"), s"nothing lands in ssn: $t")
        assert(!unresolved.contains("ssn"))
        // And with no declared destination.
        val (inherited, _, _) = ColumnLineageService.deterministic(protectedCfg())
        val ti = edgeTuples(inherited)
        assert(ti.exists(e => e._1 == List("ssn") && e._2 == "" && e._3 == "drop"), s"$ti")
        assert(!ti.exists(_._2 == "ssn"), s"$ti")
    }

    // --- story: codegen-script-pinning (plans/stories/codegen-script-pinning.md)
    //
    // Pinned seam: inference and transformationInfo read the script through a
    // PipelineScripts instance (see PipelineScriptsSpec for its constructor),
    // and inference takes the model call as a function:
    // {{{
    // object ColumnLineageService {
    //     private[datris] def infer(c: PipelineConfig, version: Int, src: List[String], dst: List[String],
    //                               exact: List[ColumnEdge], scripts: PipelineScripts,
    //                               ai: (String, String) => String): InferredColumnLineage
    //     private[datris] def transformationInfo(c: PipelineConfig, scripts: PipelineScripts): JsonObject
    // }
    // }}}

    private val txInstruction = "add full_name from first and last"

    private class LineageRecords extends CodeGenScriptRecords {
        val rows = scala.collection.mutable.Map[(String, String), CodeGenScript]()
        override def read(pipeline: String, kind: String): Option[CodeGenScript] = rows.get((pipeline, kind))
        override def write(record: CodeGenScript): Unit = rows((record.pipeline, record.kind)) = record
        override def delete(pipeline: String, kind: String): Unit = rows.remove((pipeline, kind))
    }

    private class LineageStore(objects: Map[String, String]) extends CodeStore {
        override def storage: String = "minio"
        override def readScript(ref: ScriptRef): Option[String] = Option(ref.scriptPath).flatMap(objects.get)
        override def storeScript(name: String, script: String, prior: ScriptRef, actor: String): StoredScript =
            throw new UnsupportedOperationException("lineage never writes scripts")
        override def deleteScript(ref: ScriptRef): Unit = ()
        override def scriptExists(ref: ScriptRef): Boolean = readScript(ref).isDefined
    }

    private def lineageScripts(rec: CodeGenScript, objects: Map[String, String]): PipelineScripts = {
        val recs = new LineageRecords
        if (rec != null) recs.write(rec)
        new PipelineScripts(recs, _ => new LineageStore(objects), (_, _) => fail("no generation here"), () => "model-now")
    }

    private def aiCfg(): PipelineConfig =
        cfg(Seq("first", "last"), Seq("full_name"), ai.datris.model.Transformation(aiTransformation = AITransformation(txInstruction)))

    private object LineageEnv extends AiSampleValuesMarkers

    private def capturedInferencePrompt(scripts: PipelineScripts): String = {
        var user: String = null
        TenantContext.set(LineageEnv.testEnv)
        try ColumnLineageService.infer(aiCfg(), 1, List("first", "last"), List("full_name"), Nil, scripts, (_, u) => { user = u; "[]" })
        finally TenantContext.clear()
        assert(user != null, "inference made its model call")
        user
    }

    test("inference reads the script from the store") {
        val rec = CodeGenScript(
            pipeline = "p",
            kind = "transformation",
            instruction = txInstruction,
            script = null,
            generatedAt = "2026-10-06T00:00:00Z",
            storage = "minio",
            scriptPath = "pipeline-scripts/p/transformation_1.py",
            status = "ready",
            origin = "save",
            model = "model-a"
        )
        val prompt = capturedInferencePrompt(lineageScripts(rec, Map("pipeline-scripts/p/transformation_1.py" -> "STORE_SCRIPT_TEXT = 1")))
        assert(prompt.contains("STORE_SCRIPT_TEXT = 1"), prompt)

        // An unreadable script is treated as absent: inference still runs on the instruction.
        val missing = capturedInferencePrompt(lineageScripts(rec, Map.empty))
        assert(missing.contains(txInstruction))
        assert(!missing.contains("Generated script implementing it"), missing)
    }

    test("a legacy record's text is still used") {
        val legacy = CodeGenScript("p", "transformation", txInstruction, "LEGACY_SCRIPT_TEXT = 1", "2026-01-01T00:00:00Z")
        val prompt = capturedInferencePrompt(lineageScripts(legacy, Map.empty))
        assert(prompt.contains("LEGACY_SCRIPT_TEXT = 1"), prompt)
    }

    test("transformationInfo carries the script fields") {
        val rec = CodeGenScript(
            pipeline = "p",
            kind = "transformation",
            instruction = txInstruction,
            script = null,
            generatedAt = "2026-10-06T01:02:03Z",
            storage = "minio",
            scriptPath = "pipeline-scripts/p/transformation_1.py",
            status = "ready",
            origin = "save",
            model = "model-a"
        )
        val o = ColumnLineageService.transformationInfo(aiCfg(), lineageScripts(rec, Map("pipeline-scripts/p/transformation_1.py" -> "x = 1")))
        assert(o.get("kind").getAsString == "ai")
        assert(o.get("instruction").getAsString == txInstruction)
        assert(o.get("scriptGeneratedAt").getAsString == "2026-10-06T01:02:03Z", o.toString)
        assert(o.get("scriptModel").getAsString == "model-a", o.toString)
        assert(o.get("scriptStatus").getAsString == "ready", o.toString)

        val pending = rec.copy(status = "pending", pendingReason = "No AI provider key is configured", scriptPath = null, generatedAt = null, model = null)
        val p = ColumnLineageService.transformationInfo(aiCfg(), lineageScripts(pending, Map.empty))
        assert(p.get("scriptStatus").getAsString == "pending", p.toString)
    }

    // --- blank AI transformation instruction (E2E follow-up) -----------------
    // The run skips an aiTransformation whose instruction is empty or
    // whitespace, so lineage must not classify it as an AI transformation.

    test("a blank AI transformation instruction is not classified as ai") {
        Seq("", "   ", "\t").foreach { b =>
            val c = cfg(Seq("a", "b"), null, ai.datris.model.Transformation(aiTransformation = AITransformation(b)))
            assert(ColumnLineageService.transformationInfo(c).get("kind").getAsString == "none", s"blank '$b'")
            assert(ColumnLineageService.transformationInfo(c, lineageScripts(null, Map.empty)).get("kind").getAsString == "none", s"blank '$b'")
            val (edges, unresolved, label) = ColumnLineageService.deterministic(c)
            assert(label == "inherited", s"blank '$b': destination schema inherited from the source, got $label")
            assert(edges.map(e => (e.to, e.op)).toSet == Set(("a", "passthrough"), ("b", "passthrough")), s"blank '$b': $edges")
            assert(unresolved.isEmpty, s"blank '$b': $unresolved")
        }
    }
}
