package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import org.mockito.Mockito.when
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.mockito.MockitoSugar

import java.sql.{ResultSet, ResultSetMetaData, Types}

class SQLUtilSpec extends AnyFunSuite with MockitoSugar {

    private def resultSetWith(columns: List[(String, Int)], configure: ResultSet => Unit, rowCount: Int = 1): ResultSet = {
        val meta = mock[ResultSetMetaData]
        when(meta.getColumnCount).thenReturn(columns.size)
        columns.zipWithIndex.foreach { case ((name, sqlType), i) =>
            when(meta.getColumnName(i + 1)).thenReturn(name)
            when(meta.getColumnType(i + 1)).thenReturn(sqlType)
        }
        val rs = mock[ResultSet]
        when(rs.getMetaData).thenReturn(meta)
        val nexts: List[Boolean] = List.fill(rowCount)(true) :+ false
        when(rs.next()).thenReturn(nexts.head, nexts.tail: _*)
        configure(rs)
        rs
    }

    test("maps common column types to their string values") {
        val rs = resultSetWith(
            List(("id", Types.INTEGER), ("name", Types.VARCHAR), ("big", Types.BIGINT),
                ("flag", Types.BOOLEAN), ("rate", Types.DOUBLE)),
            rs => {
                when(rs.getInt(1)).thenReturn(42)
                when(rs.getString(2)).thenReturn("alpha")
                when(rs.getLong(3)).thenReturn(9999999999L)
                when(rs.getBoolean(4)).thenReturn(true)
                when(rs.getDouble(5)).thenReturn(1.5)
            })
        val rows = SQLUtil.getResultSet(rs)
        assert(rows == List(Map("id" -> "42", "name" -> "alpha", "big" -> "9999999999",
            "flag" -> "true", "rate" -> "1.5")))
    }

    test("NUMERIC values are converted to integers — scientific notation removed, fraction TRUNCATED") {
        // Documents existing behavior: BigDecimal(value).toInt drops the fractional
        // part entirely (12.7 → "12") and collapses scientific notation (1.23E+3 → "1230").
        val rs = resultSetWith(
            List(("sci", Types.NUMERIC), ("frac", Types.DECIMAL)),
            rs => {
                when(rs.getBigDecimal(1)).thenReturn(new java.math.BigDecimal("1.23E+3"))
                when(rs.getBigDecimal(2)).thenReturn(new java.math.BigDecimal("12.7"))
            })
        val rows = SQLUtil.getResultSet(rs)
        assert(rows == List(Map("sci" -> "1230", "frac" -> "12")))
    }

    test("null-returning accessors become empty strings") {
        val rs = resultSetWith(
            List(("s", Types.VARCHAR), ("n", Types.NUMERIC), ("d", Types.DATE)),
            rs => {
                when(rs.getString(1)).thenReturn(null)
                when(rs.getBigDecimal(2)).thenReturn(null)
                when(rs.getDate(3)).thenReturn(null)
            })
        assert(SQLUtil.getResultSet(rs) == List(Map("s" -> "", "n" -> "", "d" -> "")))
    }

    test("unknown SQL types fall back to getString") {
        val rs = resultSetWith(
            List(("blob", Types.BLOB)),
            rs => when(rs.getString(1)).thenReturn("raw"))
        assert(SQLUtil.getResultSet(rs) == List(Map("blob" -> "raw")))
    }

    test("empty result set yields empty list") {
        val rs = resultSetWith(List(("a", Types.VARCHAR)), _ => (), rowCount = 0)
        assert(SQLUtil.getResultSet(rs) == List.empty)
    }

    test("multiple rows are returned in order") {
        val rs = resultSetWith(
            List(("v", Types.INTEGER)),
            rs => when(rs.getInt(1)).thenReturn(1, 2),
            rowCount = 2)
        assert(SQLUtil.getResultSet(rs) == List(Map("v" -> "1"), Map("v" -> "2")))
    }
}
