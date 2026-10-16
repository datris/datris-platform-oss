package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** Story: Schema-drift signal, credential and platform classifications, wider
  * diagnosis tools (plans/stories/self-healing-4-signals.md), Acceptance
  * bullet 1.
  *
  * Contract pinned here (new util/SchemaDrift.scala, pure):
  * {{{
  * case class SchemaDriftDiff(added: List[String], removed: List[String])
  * object SchemaDrift {
  *   /** None when either side is null or empty, or the two sets are equal. */
  *   def detect(before: java.util.List[String], after: java.util.List[String]): Option[SchemaDriftDiff]
  * }
  * }}}
  * The inputs are `TapConfig.lastRunColumns` (java.util.List[String]). */
class SchemaDriftSpec extends AnyFunSuite {

    private def cols(names: String*): java.util.List[String] = java.util.Arrays.asList(names: _*)

    test("unchanged columns are None") {
        assert(SchemaDrift.detect(cols("a", "b", "c"), cols("a", "b", "c")).isEmpty)
    }

    test("an added column is reported as added") {
        val d = SchemaDrift.detect(cols("a", "b"), cols("a", "b", "c"))
        assert(d.isDefined)
        assert(d.get.added == List("c"), d)
        assert(d.get.removed.isEmpty, d)
    }

    test("a rename surfaces as one removed plus one added") {
        val d = SchemaDrift.detect(cols("a", "b"), cols("a", "c"))
        assert(d.isDefined)
        assert(d.get.removed == List("b"), d)
        assert(d.get.added == List("c"), d)
    }

    test("null or empty on either side is None") {
        assert(SchemaDrift.detect(null, cols("a")).isEmpty)
        assert(SchemaDrift.detect(cols("a"), null).isEmpty)
        assert(SchemaDrift.detect(null, null).isEmpty)
        assert(SchemaDrift.detect(cols(), cols("a")).isEmpty)
        assert(SchemaDrift.detect(cols("a"), cols()).isEmpty)
    }

    test("comparison is order-insensitive") {
        assert(SchemaDrift.detect(cols("a", "b", "c"), cols("c", "a", "b")).isEmpty)
        val d = SchemaDrift.detect(cols("b", "a"), cols("c", "b", "a", "d"))
        assert(d.isDefined)
        assert(d.get.added.toSet == Set("c", "d"), d)
        assert(d.get.removed.isEmpty, d)
    }
}
