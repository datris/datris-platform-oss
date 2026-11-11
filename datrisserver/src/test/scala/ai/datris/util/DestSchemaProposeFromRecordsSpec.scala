package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: Assistant preview-and-confirm schema before create_pipeline
  * (plans/stories/assistant-preview-confirm-schema.md), Acceptance 1.
  *
  * `DestSchemaApply.proposeFromRecords(columns, records)` infers a typed
  * proposal from a tap's preview rows (nothing landed yet). It is conservative:
  * a column with fewer than `MinVotes` (5) non-null values stays `string` and
  * carries a `note` naming the type it would otherwise be; dirty-value columns
  * keep the existing `stringReason`. `inferFieldsFromRecords(..., minVotes = 0)`
  * keeps today's behaviour for the landed-data `propose` path.
  */
class DestSchemaProposeFromRecordsSpec extends AnyFunSuite {

    private def rows(col: String, values: Any*): Seq[Map[String, Any]] =
        values.map(v => Map[String, Any](col -> v))

    private def proposeOne(col: String, values: Any*): InferredDestField = {
        val proposal = DestSchemaApply.proposeFromRecords(List(col), rows(col, values: _*))
        val fields = proposal.fields.asScala.toList
        assert(fields.size == 1, fields)
        fields.head
    }

    test("MinVotes is 5") {
        assert(DestSchemaApply.MinVotes == 5)
    }

    test("20 clean ints propose int with no note and no reason") {
        val f = proposeOne("qty", (1 to 20).map(_.toString): _*)
        assert(f.`type` == "int", f)
        assert(f.note == null, f)
        assert(f.stringReason == null, f)
    }

    test("2 ints + 18 nulls propose string with a note naming int (thin evidence)") {
        val values: Seq[Any] = Seq("1", "2") ++ Seq.fill[Any](18)(null)
        val f = proposeOne("qty", values: _*)
        assert(f.`type` == "string", f)
        assert(f.note != null, s"thin-evidence column must carry a note: $f")
        assert(f.note.contains("int"), s"note must name the type it would be: ${f.note}")
        assert(f.note.contains("2"), s"note must give the non-null count: ${f.note}")
        assert(f.note.contains("20"), s"note must give the row count: ${f.note}")
        // the step's literal wording
        assert(f.note == "2 non-null values in 20 rows; would be int", f.note)
    }

    test("an all-null column proposes string") {
        val f = proposeOne("qty", Seq.fill[Any](20)(null): _*)
        assert(f.`type` == "string", f)
    }

    test("\"N/A\" among ints proposes string with the existing stringReason") {
        val values: Seq[Any] = (1 to 19).map(_.toString) :+ "N/A"
        val f = proposeOne("qty", values: _*)
        assert(f.`type` == "string", f)
        assert(f.stringReason == DestStringReason("N/A", "int"), f)
    }

    test("empty records throw DatrisException") {
        val e = intercept[DatrisException] {
            DestSchemaApply.proposeFromRecords(List("qty"), Seq.empty)
        }
        assert(e.getMessage.contains("No preview rows"), e.getMessage)
    }

    test("proposal is eligible, has no pipeline, and reports the sample row count") {
        val proposal = DestSchemaApply.proposeFromRecords(List("qty"), rows("qty", (1 to 20).map(_.toString): _*))
        assert(proposal.pipeline == null)
        assert(proposal.eligible)
        assert(proposal.sampleRowCount == 20)
    }

    test("null columns default to the union of record keys in first-seen order") {
        val recs = Seq(
            Map[String, Any]("b" -> "1", "a" -> "x"),
            Map[String, Any]("a" -> "y", "c" -> "true")
        )
        val proposal = DestSchemaApply.proposeFromRecords(null, recs)
        assert(proposal.fields.asScala.map(_.name).toList == List("b", "a", "c"))
    }

    // ---- minVotes = 0 reproduces DestTypeInferenceSpec ----

    test("minVotes = 0 reproduces DestTypeInferenceSpec: case-insensitive names, _json never retyped") {
        val fields = DestTypeInference.inferFieldsFromRecords(
            List("id", "active", "_json"),
            Seq(
                Map[String, Any]("ID" -> "3", "Active" -> "true", "_json" -> "{\"a\":1}"),
                Map[String, Any]("id" -> "4", "active" -> "false", "_json" -> "{\"b\":2}")
            ),
            minVotes = 0
        ).asScala.toList
        assert(fields.map(f => f.name -> f.`type`) ==
            List("id" -> "int", "active" -> "boolean", "_json" -> "string"))
        assert(fields(2).samples.asScala.nonEmpty)
        assert(fields(2).stringReason == null)
        assert(fields.forall(_.note == null), fields)
    }

    test("minVotes = 0 reproduces DestTypeInferenceSpec: per-column evidence") {
        val fields = DestTypeInference.inferFieldsFromRecords(
            List("qty"),
            Seq(Map[String, Any]("qty" -> "1"), Map[String, Any]("qty" -> "N/A")),
            minVotes = 0
        ).asScala.toList
        assert(fields.head.`type` == "string")
        assert(fields.head.stringReason == DestStringReason("N/A", "int"))
        assert(fields.head.samples.asScala.toList == List("1", "N/A"))
        assert(fields.head.note == null)
    }

    test("minVotes = 0 types a thin column exactly as the default does") {
        val recs: Seq[Map[String, Any]] = Seq[Any]("1", "2", null, null).map(v => Map[String, Any]("qty" -> v))
        val zero = DestTypeInference.inferFieldsFromRecords(List("qty"), recs, minVotes = 0).asScala.head
        val default = DestTypeInference.inferFieldsFromRecords(List("qty"), recs).asScala.head
        assert(zero.`type` == "int", zero)
        assert(default.`type` == "int", default)
        assert(zero.note == null && default.note == null)
    }
}
