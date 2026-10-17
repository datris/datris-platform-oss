package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import scala.collection.JavaConverters._

/** The column-set change between two runs of a source. A rename surfaces as
  * one removed plus one added; the diagnosis names it. */
case class SchemaDriftDiff(added: List[String], removed: List[String])

object SchemaDrift {

    /** None when either side is null or empty, or the two column sets are
      * equal (order-insensitive). Output keeps each side's first-seen order. */
    def detect(before: java.util.List[String], after: java.util.List[String]): Option[SchemaDriftDiff] = {
        if (before == null || after == null || before.isEmpty || after.isEmpty) return None
        val b = before.asScala.filter(_ != null).toList.distinct
        val a = after.asScala.filter(_ != null).toList.distinct
        if (b.isEmpty || a.isEmpty) return None
        val bs = b.toSet
        val as = a.toSet
        if (bs == as) None
        else Some(SchemaDriftDiff(added = a.filterNot(bs.contains), removed = b.filterNot(as.contains)))
    }
}
