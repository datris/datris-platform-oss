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

    private def cfg(src: Seq[String], dst: Seq[String] = null, tx: ai.datris.model.Transformation = null, stamp: Boolean = false, stampFields: Seq[String] = null): PipelineConfig =
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
        assert(edges.map(e => (e.from.asScala.toList, e.to, e.op, e.confidence)) == List((List("id"), "id", "passthrough", "exact"), (List("name"), "name", "passthrough", "exact")))
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
        assert(ColumnLineageService.transformationInfo(cfg(Seq("a"), null, ai.datris.model.Transformation(aiTransformation = AITransformation("x")))).get("kind").getAsString == "ai")
        assert(ColumnLineageService.transformationInfo(cfg(Seq("a"), null, ai.datris.model.Transformation(rowFunctions = List(RowFunction("javascript", null)).asJava))).get("kind").getAsString == "rowFunctions")
        assert(ColumnLineageService.transformationInfo(cfg(Seq("a")).copy(preprocessor = RestEndpoint("http://x"))).get("kind").getAsString == "preprocessor")
    }
}
