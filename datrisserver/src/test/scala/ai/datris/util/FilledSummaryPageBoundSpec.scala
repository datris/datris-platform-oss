package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.PipelineStatusSummary
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: Four small read leaks for callers limited to a catalog
  * (plans/stories/limited-caller-read-leaks.md), D3 with lead ruling 1: the
  * over-read bound grows with the page asked for, so deep pages stay
  * reachable. Visible page N reads at most N + 9 store pages of 20 rows. */
class FilledSummaryPageBoundSpec extends AnyFunSuite {

    private class Store(total: Int, visible: Int => Boolean) {
        val rows: IndexedSeq[PipelineStatusSummary] = (0 until total).map { i =>
            val at = 1800000000000L - i * 1000L
            PipelineStatusSummary("ts" + i, at, at, if (visible(i)) "orders" else "payroll", "tok-" + i, "JobRunner", "s", "e", "1s", "success")
        }
        val pagesRead: mutable.ArrayBuffer[Int] = mutable.ArrayBuffer[Int]()
        val readPage: Int => java.util.List[PipelineStatusSummary] = n => {
            pagesRead += n
            val from = math.max(0, (n - 1) * 20)
            new java.util.ArrayList[PipelineStatusSummary](rows.slice(from, from + 20).asJava)
        }
        def visibleTokens: IndexedSeq[String] = rows.filter(_.pipeline == "orders").map(_.pipelineToken)
    }

    private val keep: PipelineStatusSummary => Boolean = _.pipeline == "orders"
    private def tokens(l: java.util.List[PipelineStatusSummary]): Seq[String] = l.asScala.map(_.pipelineToken).toSeq

    test("ruling 1: visible page N reads at most N + 9 store pages and, where they hold enough rows, returns a full page") {
        for (page <- Seq(1, 3, 12, 40)) {
            val s = new Store(2000, _ % 2 == 0) // half visible: page N needs 2N store pages
            val got = PipelineStatusUtil.filledSummaryPage(page, keep, s.readPage)
            assert(s.pagesRead.size <= page + 9, page + ": " + s.pagesRead)
            assert(s.pagesRead == (1 to s.pagesRead.size), "store pages in order, each once: " + s.pagesRead)
            val expected = s.visibleTokens.slice((page - 1) * 20, page * 20)
            if (2 * page <= page + 9) assert(tokens(got) == expected, page + ": " + tokens(got))
            else {
                // past the bound: a short (possibly empty) page, never rows out of place
                val reachable = s.visibleTokens.take((page + 9) * 10)
                assert(tokens(got) == reachable.slice((page - 1) * 20, page * 20), page + ": " + tokens(got))
            }
        }
    }

    test("ruling 1: a caller that keeps every row reads exactly the store pages up to the one it asked for") {
        for (page <- Seq(1, 2, 25)) {
            val s = new Store(1000, _ => true)
            val got = PipelineStatusUtil.filledSummaryPage(page, keep, s.readPage)
            assert(s.pagesRead == (1 to page), page + ": " + s.pagesRead)
            assert(tokens(got) == s.rows.slice((page - 1) * 20, page * 20).map(_.pipelineToken))
        }
    }

    test("a page below 1 reads that store page once, filtered, as the store answers it") {
        val s = new Store(40, _ % 2 == 0)
        assert(tokens(PipelineStatusUtil.filledSummaryPage(0, keep, s.readPage)) == s.rows.take(20).filter(keep).map(_.pipelineToken))
        assert(s.pagesRead == Seq(0), s.pagesRead)
    }
}
