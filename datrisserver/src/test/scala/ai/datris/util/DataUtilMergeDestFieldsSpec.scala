package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.SchemaField
import org.scalatest.funsuite.AnyFunSuite

/** Story: plans/stories/schema-evolution-dest-duplicate.md.
  *
  * Pinned seam:
  * {{{
  * object DataUtil {
  *     private[datris] def mergeDestFields(existing: List[SchemaField], added: List[SchemaField]): List[SchemaField]
  * }
  * }}}
  * Names match case-insensitively after trimming; an existing entry is kept
  * untouched (name and declared type); new names are appended in order; a
  * duplicate already inside `existing` is dropped (first occurrence wins). */
class DataUtilMergeDestFieldsSpec extends AnyFunSuite {

    private def names(fs: List[SchemaField]): List[String] = fs.map(_.name)

    test("a column already in the destination is not added again") {
        val existing = List(SchemaField("id", "int"), SchemaField("name", "string"), SchemaField("admit_date", "date"))
        val merged = DataUtil.mergeDestFields(existing, List(SchemaField("admit_date", "string")))
        assert(names(merged) == List("id", "name", "admit_date"), merged)
        assert(merged.count(_.name == "admit_date") == 1, merged)
        assert(merged.find(_.name == "admit_date").get.`type` == "date", "declared type kept: " + merged)
    }

    test("the match is case-insensitive and the declared type is kept") {
        val existing = List(SchemaField("id", "int"), SchemaField("Admit_Date", "date"))
        val merged = DataUtil.mergeDestFields(existing, List(SchemaField(" admit_DATE ", "string")))
        assert(merged == List(SchemaField("id", "int"), SchemaField("Admit_Date", "date")), merged)
    }

    test("columns the destination does not have are appended in order") {
        val existing = List(SchemaField("id", "int"), SchemaField("admit_date", "date"))
        val added = List(SchemaField("zeta", "string"), SchemaField("ADMIT_DATE", "string"), SchemaField("alpha", "string"))
        val merged = DataUtil.mergeDestFields(existing, added)
        assert(
            merged == List(SchemaField("id", "int"), SchemaField("admit_date", "date"), SchemaField("zeta", "string"), SchemaField("alpha", "string")),
            merged
        )
    }

    test("an existing list that already has a duplicate is repaired, first entry wins") {
        val existing = List(SchemaField("id", "int"), SchemaField("admit_date", "date"), SchemaField("name", "string"), SchemaField("admit_date", "string"))
        val merged = DataUtil.mergeDestFields(existing, Nil)
        assert(merged == List(SchemaField("id", "int"), SchemaField("admit_date", "date"), SchemaField("name", "string")), merged)

        val withNew = DataUtil.mergeDestFields(existing, List(SchemaField("extra", "string")))
        assert(names(withNew) == List("id", "admit_date", "name", "extra"), withNew)
        assert(withNew.find(_.name == "admit_date").get.`type` == "date", withNew)
    }
}
