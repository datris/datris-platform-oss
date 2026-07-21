package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.hsmf.MAPIMessage
import org.jsoup.Jsoup
import org.slf4j.{Logger, LoggerFactory}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip.ZipInputStream
import javax.swing.text.rtf.RTFEditorKit
import scala.collection.JavaConverters._

object TextExtractorUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    def extractText(bytes: Array[Byte], filename: String): String = {
        val ext = filename.toLowerCase.split("\\.").lastOption.getOrElse("")
        ext match {
            case "pdf" => extractPdf(bytes)
            case "doc" => extractDoc(bytes)
            case "docx" => extractDocx(bytes)
            case "ppt" => extractPpt(bytes)
            case "pptx" => extractPptx(bytes)
            case "xls" => extractXls(bytes)
            case "xlsx" => extractXlsx(bytes)
            case "html" | "htm" => extractHtml(bytes)
            case "rtf" => extractRtf(bytes)
            case "msg" => extractMsg(bytes)
            case "eml" => extractEml(bytes)
            case "epub" => extractEpub(bytes)
            case _ => new String(bytes, "UTF-8")
        }
    }

    private def extractPdf(bytes: Array[Byte]): String = {
        logger.info("Extracting text from PDF ({} bytes)", bytes.length)
        val document = Loader.loadPDF(bytes)
        try {
            val stripper = new PDFTextStripper()
            stripper.getText(document)
        } finally {
            document.close()
        }
    }

    private def extractDoc(bytes: Array[Byte]): String = {
        logger.info("Extracting text from DOC ({} bytes)", bytes.length)
        val document = new HWPFDocument(new ByteArrayInputStream(bytes))
        try {
            val extractor = new WordExtractor(document)
            try {
                extractor.getText
            } finally {
                extractor.close()
            }
        } finally {
            document.close()
        }
    }

    private def extractDocx(bytes: Array[Byte]): String = {
        logger.info("Extracting text from DOCX ({} bytes)", bytes.length)
        val document = new XWPFDocument(new ByteArrayInputStream(bytes))
        try {
            val extractor = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(document)
            try {
                extractor.getText
            } finally {
                extractor.close()
            }
        } finally {
            document.close()
        }
    }

    private def extractPpt(bytes: Array[Byte]): String = {
        logger.info("Extracting text from PPT ({} bytes)", bytes.length)
        val ppt = new HSLFSlideShow(new ByteArrayInputStream(bytes))
        try {
            val extractor = new org.apache.poi.sl.extractor.SlideShowExtractor[
                org.apache.poi.hslf.usermodel.HSLFShape,
                org.apache.poi.hslf.usermodel.HSLFTextParagraph
            ](ppt)
            try {
                extractor.getText
            } finally {
                extractor.close()
            }
        } finally {
            ppt.close()
        }
    }

    private def extractPptx(bytes: Array[Byte]): String = {
        logger.info("Extracting text from PPTX ({} bytes)", bytes.length)
        val pptx = new XMLSlideShow(new ByteArrayInputStream(bytes))
        try {
            val sb = new StringBuilder()
            pptx.getSlides.asScala.foreach { slide =>
                slide.getShapes.asScala.foreach {
                    case shape: org.apache.poi.xslf.usermodel.XSLFTextShape =>
                        sb.append(shape.getText).append("\n")
                    case _ =>
                }
                sb.append("\n")
            }
            sb.toString()
        } finally {
            pptx.close()
        }
    }

    private def extractXls(bytes: Array[Byte]): String = {
        logger.info("Extracting text from XLS ({} bytes)", bytes.length)
        val workbook = new HSSFWorkbook(new ByteArrayInputStream(bytes))
        try {
            extractWorkbook(workbook)
        } finally {
            workbook.close()
        }
    }

    private def extractXlsx(bytes: Array[Byte]): String = {
        logger.info("Extracting text from XLSX ({} bytes)", bytes.length)
        val workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))
        try {
            extractWorkbook(workbook)
        } finally {
            workbook.close()
        }
    }

    private def extractWorkbook(workbook: org.apache.poi.ss.usermodel.Workbook): String = {
        val sb = new StringBuilder()
        val formatter = new org.apache.poi.ss.usermodel.DataFormatter()
        for (i <- 0 until workbook.getNumberOfSheets) {
            val sheet = workbook.getSheetAt(i)
            sb.append("Sheet: ").append(sheet.getSheetName).append("\n")
            sheet.iterator().asScala.foreach { row =>
                val cells = row.iterator().asScala.map(cell => formatter.formatCellValue(cell)).mkString("\t")
                sb.append(cells).append("\n")
            }
            sb.append("\n")
        }
        sb.toString()
    }

    private def extractHtml(bytes: Array[Byte]): String = {
        logger.info("Extracting text from HTML ({} bytes)", bytes.length)
        val html = new String(bytes, "UTF-8")
        Jsoup.parse(html).text()
    }

    private def extractRtf(bytes: Array[Byte]): String = {
        logger.info("Extracting text from RTF ({} bytes)", bytes.length)
        val kit = new RTFEditorKit()
        val doc = kit.createDefaultDocument()
        kit.read(new ByteArrayInputStream(bytes), doc, 0)
        doc.getText(0, doc.getLength)
    }

    private def extractMsg(bytes: Array[Byte]): String = {
        logger.info("Extracting text from MSG ({} bytes)", bytes.length)
        val msg = new MAPIMessage(new ByteArrayInputStream(bytes))
        try {
            val sb = new StringBuilder()
            Option(msg.getSubject).foreach(s => sb.append("Subject: ").append(s).append("\n"))
            Option(msg.getDisplayFrom).foreach(f => sb.append("From: ").append(f).append("\n"))
            Option(msg.getDisplayTo).foreach(t => sb.append("To: ").append(t).append("\n"))
            sb.append("\n")
            Option(msg.getTextBody).foreach(b => sb.append(b))
            sb.toString()
        } finally {
            msg.close()
        }
    }

    private def extractEml(bytes: Array[Byte]): String = {
        logger.info("Extracting text from EML ({} bytes)", bytes.length)
        val session = jakarta.mail.Session.getDefaultInstance(new java.util.Properties())
        val message = new jakarta.mail.internet.MimeMessage(session, new ByteArrayInputStream(bytes))
        val sb = new StringBuilder()
        Option(message.getSubject).foreach(s => sb.append("Subject: ").append(s).append("\n"))
        Option(message.getFrom).foreach(f => sb.append("From: ").append(f.map(_.toString).mkString(", ")).append("\n"))
        sb.append("\n")
        sb.append(extractMimeContent(message))
        sb.toString()
    }

    private def extractMimeContent(part: jakarta.mail.Part): String = {
        val content = part.getContent
        content match {
            case s: String => s
            case mp: jakarta.mail.internet.MimeMultipart =>
                val parts = for (i <- 0 until mp.getCount) yield extractMimeContent(mp.getBodyPart(i))
                parts.filter(_.nonEmpty).mkString("\n")
            case _ => ""
        }
    }

    private def extractEpub(bytes: Array[Byte]): String = {
        logger.info("Extracting text from EPUB ({} bytes)", bytes.length)
        // EPUB files are ZIP archives containing XHTML content
        val sb = new StringBuilder()
        val zis = new ZipInputStream(new ByteArrayInputStream(bytes))
        try {
            var entry = zis.getNextEntry
            while (entry != null) {
                val name = entry.getName.toLowerCase
                if (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) {
                    val baos = new ByteArrayOutputStream()
                    val buffer = new Array[Byte](4096)
                    var len = zis.read(buffer)
                    while (len > 0) {
                        baos.write(buffer, 0, len)
                        len = zis.read(buffer)
                    }
                    val html = new String(baos.toByteArray, "UTF-8")
                    val text = Jsoup.parse(html).text()
                    if (text.nonEmpty) sb.append(text).append("\n\n")
                }
                entry = zis.getNextEntry
            }
        } finally {
            zis.close()
        }
        sb.toString()
    }
}
