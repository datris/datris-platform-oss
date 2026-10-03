package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import java.sql.{ResultSet, ResultSetMetaData, SQLException, Statement}

import org.mockito.Mockito.{mock, never, times, verify, when}
import org.scalatest.funsuite.AnyFunSuite

/** Databricks COPY INTO with no result set (story
  * plans/stories/databricks-copy-no-resultset.md). The loader issues COPY INTO
  * with `execute()` and hands the outcome to
  * `DatabricksLoader.loadedRowCount(statement, hasResultSet)`, pinned here as
  * `private[util]` on the DatabricksLoader COMPANION OBJECT (pure: no
  * JobContext needed). With a result set it reads `num_inserted_rows` /
  * `num_affected_rows` (lowercase labels) exactly as before and always closes
  * it; without one it falls back to `getUpdateCount`, a negative count being
  * unknown (None). No Spark, no Databricks: Statement / ResultSet /
  * ResultSetMetaData are Mockito stubs. */
class DatabricksLoaderCopyResultSpec extends AnyFunSuite {

    /** A one-row-per-entry COPY INTO result set with the given column labels. */
    private def resultSet(labels: Seq[String], rows: Seq[Seq[Long]]): ResultSet = {
        val rs = mock(classOf[ResultSet])
        val meta = mock(classOf[ResultSetMetaData])
        when(meta.getColumnCount).thenReturn(labels.size)
        labels.zipWithIndex.foreach { case (l, i) => when(meta.getColumnLabel(i + 1)).thenReturn(l) }
        when(rs.getMetaData).thenReturn(meta)
        var cursor = -1
        when(rs.next()).thenAnswer { _ => cursor += 1; cursor < rows.size }
        labels.indices.foreach { i =>
            when(rs.getLong(i + 1)).thenAnswer(_ => rows(cursor)(i))
        }
        rs
    }

    private def statementWith(rs: ResultSet): Statement = {
        val st = mock(classOf[Statement])
        when(st.getResultSet).thenReturn(rs)
        st
    }

    private def statementWithUpdateCount(n: Int): Statement = {
        val st = mock(classOf[Statement])
        when(st.getResultSet).thenReturn(null)
        when(st.getUpdateCount).thenReturn(n)
        st
    }

    test("a result set with num_inserted_rows yields that count") {
        val rs = resultSet(
            Seq("num_affected_rows", "num_inserted_rows"),
            Seq(Seq(0L, 200L))
        )
        assert(DatabricksLoader.loadedRowCount(statementWith(rs), hasResultSet = true) == Some(200L))
    }

    test("a result set with only num_affected_rows yields that count") {
        val rs = resultSet(Seq("NUM_AFFECTED_ROWS"), Seq(Seq(137L)))
        assert(DatabricksLoader.loadedRowCount(statementWith(rs), hasResultSet = true) == Some(137L))
    }

    test("a result set with neither column yields 0") {
        val rs = resultSet(Seq("file_name", "rows_skipped"), Seq(Seq(1L, 5L)))
        assert(DatabricksLoader.loadedRowCount(statementWith(rs), hasResultSet = true) == Some(0L))
    }

    test("no result set uses the statement update count") {
        val st = statementWithUpdateCount(200)
        assert(DatabricksLoader.loadedRowCount(st, hasResultSet = false) == Some(200L))
        verify(st, never()).getResultSet
    }

    test("no result set and a negative update count is unknown") {
        val st = statementWithUpdateCount(-1)
        assert(DatabricksLoader.loadedRowCount(st, hasResultSet = false).isEmpty)
    }

    test("execute true but null result set falls back to the update count") {
        val st = statementWithUpdateCount(200)
        assert(DatabricksLoader.loadedRowCount(st, hasResultSet = true) == Some(200L))
        assert(DatabricksLoader.loadedRowCount(statementWithUpdateCount(-1), hasResultSet = true).isEmpty)
    }

    test("the result set is closed in every case") {
        val withInserted = resultSet(Seq("num_inserted_rows"), Seq(Seq(10L)))
        DatabricksLoader.loadedRowCount(statementWith(withInserted), hasResultSet = true)
        verify(withInserted, times(1)).close()

        val withAffected = resultSet(Seq("num_affected_rows"), Seq(Seq(3L)))
        DatabricksLoader.loadedRowCount(statementWith(withAffected), hasResultSet = true)
        verify(withAffected, times(1)).close()

        val withNeither = resultSet(Seq("other"), Seq(Seq(1L)))
        DatabricksLoader.loadedRowCount(statementWith(withNeither), hasResultSet = true)
        verify(withNeither, times(1)).close()

        val empty = resultSet(Seq("num_inserted_rows"), Seq.empty)
        DatabricksLoader.loadedRowCount(statementWith(empty), hasResultSet = true)
        verify(empty, times(1)).close()

        // A read that throws still closes the result set and propagates.
        val broken = resultSet(Seq("num_inserted_rows"), Seq(Seq(1L)))
        when(broken.next()).thenThrow(new SQLException("boom"))
        intercept[SQLException] {
            DatabricksLoader.loadedRowCount(statementWith(broken), hasResultSet = true)
        }
        verify(broken, times(1)).close()
    }
}
