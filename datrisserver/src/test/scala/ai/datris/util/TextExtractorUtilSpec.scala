package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.scalatest.funsuite.AnyFunSuite

import java.io.ByteArrayOutputStream

/** Smoke tests for the POI and jsoup extraction paths. These are the only
  *  direct consumers of both libraries, and dependency bumps compile green
  *  even when an extractor API breaks — the failure otherwise surfaces on the
  *  first document a user ingests. Fixture documents are generated in memory
  *  with POI itself, so the round-trip exercises both write and read against
  *  the resolved version.
  */
class TextExtractorUtilSpec extends AnyFunSuite {

    test("docx round-trips through the XWPF extractor") {
        val doc = new XWPFDocument()
        try {
            doc.createParagraph().createRun().setText("quarterly ingest summary")
            val baos = new ByteArrayOutputStream()
            doc.write(baos)
            val text = TextExtractorUtil.extractText(baos.toByteArray, "report.docx")
            assert(text.contains("quarterly ingest summary"))
        } finally {
            doc.close()
        }
    }

    test("xlsx round-trips through the XSSF extractor with formatted cells") {
        val wb = new XSSFWorkbook()
        try {
            val sheet = wb.createSheet("Metrics")
            val row = sheet.createRow(0)
            row.createCell(0).setCellValue("rows_loaded")
            row.createCell(1).setCellValue(42.0)
            val baos = new ByteArrayOutputStream()
            wb.write(baos)
            val text = TextExtractorUtil.extractText(baos.toByteArray, "metrics.xlsx")
            assert(text.contains("Sheet: Metrics"))
            assert(text.contains("rows_loaded"))
            assert(text.contains("42"))
        } finally {
            wb.close()
        }
    }

    test("legacy xls round-trips through the HSSF extractor") {
        val wb = new HSSFWorkbook()
        try {
            wb.createSheet("Old").createRow(0).createCell(0).setCellValue("legacy cell")
            val baos = new ByteArrayOutputStream()
            wb.write(baos)
            val text = TextExtractorUtil.extractText(baos.toByteArray, "legacy.xls")
            assert(text.contains("legacy cell"))
        } finally {
            wb.close()
        }
    }

    test("html strips tags and decodes entities via jsoup") {
        val html = "<html><body><h1>Title</h1><p>rows &amp; columns</p><script>ignored()</script></body></html>"
        val text = TextExtractorUtil.extractText(html.getBytes("UTF-8"), "page.html")
        assert(text.contains("Title"))
        assert(text.contains("rows & columns"))
        assert(!text.contains("<p>"))
        assert(!text.contains("ignored()"))
    }

    test("unknown extensions fall back to UTF-8 passthrough") {
        val text = TextExtractorUtil.extractText("plain payload".getBytes("UTF-8"), "notes.custom")
        assert(text == "plain payload")
    }
}
