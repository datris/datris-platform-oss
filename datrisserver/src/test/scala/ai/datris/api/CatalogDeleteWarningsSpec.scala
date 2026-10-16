package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonArray, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: Delete keeps a hand-edited repository script and warns
  * (plans/stories/pipeline-delete-drift-warning-and-404.md). The cascade
  * delete response merges pipeline and tap warnings into one `warnings`
  * array. No Spring or Mongo: the array is built by a pure helper.
  *
  * Pinned seam:
  * {{{
  * object CatalogAPIController {
  *     /** Pipeline warnings as {pipeline, message}, then tap warnings as
  *       * {tap, message}, each list in input order. */
  *     private[api] def warningsArray(pipelineWarnings: Seq[(String, String)],
  *                                    tapWarnings: Seq[(String, String)]): JsonArray
  * }
  * }}}
  */
class CatalogDeleteWarningsSpec extends AnyFunSuite {

    test("pipeline and tap warnings render as {pipeline, message} and {tap, message} in input order") {
        val arr: JsonArray = CatalogAPIController.warningsArray(
            Seq("p1" -> "'taps/pipelines/p1/data-quality.py' was kept", "p2" -> "second pipeline warning"),
            Seq("t1" -> "'taps/t1.py' was kept", "t2" -> "second tap warning")
        )
        val entries = JsonParser.parseString(arr.toString).getAsJsonArray.asScala.map(_.getAsJsonObject).toList
        assert(entries.size == 4, s"$entries")

        val rendered = entries.map { o =>
            val keys = o.keySet.asScala.toSet
            if (o.has("pipeline")) {
                assert(keys == Set("pipeline", "message"), s"$o")
                ("pipeline", o.get("pipeline").getAsString, o.get("message").getAsString)
            } else {
                assert(keys == Set("tap", "message"), s"$o")
                ("tap", o.get("tap").getAsString, o.get("message").getAsString)
            }
        }
        assert(
            rendered.filter(_._1 == "pipeline") == List(
                ("pipeline", "p1", "'taps/pipelines/p1/data-quality.py' was kept"),
                ("pipeline", "p2", "second pipeline warning")
            ),
            s"$rendered"
        )
        assert(
            rendered.filter(_._1 == "tap") == List(("tap", "t1", "'taps/t1.py' was kept"), ("tap", "t2", "second tap warning")),
            s"$rendered"
        )
    }

    test("no warnings render an empty array") {
        assert(CatalogAPIController.warningsArray(Nil, Nil).size == 0)
    }

    test("pipeline-only warnings keep the existing {pipeline, message} shape") {
        val arr = CatalogAPIController.warningsArray(Seq("p1" -> "m1"), Nil)
        assert(arr.size == 1)
        val o = arr.get(0).getAsJsonObject
        assert(o.get("pipeline").getAsString == "p1" && o.get("message").getAsString == "m1" && !o.has("tap"), s"$o")
    }
}
