package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{Gson, JsonObject}
import ai.datris.model.{DatrisEnvironment, DatrisException}
import ai.datris.util.{APIKeyValidator, AiSampleValues, AttachmentStore, CodeGenTransformationEvaluator}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._
import org.springframework.web.multipart.MultipartFile

/** Staging endpoint for files dropped into the Assistant chat.
  *
  * The Assistant agent loop runs server-side and the model can't emit a real
  * file's bytes, so the UI uploads the file here first. We cache the bytes in
  * [[AttachmentStore]] (tenant-scoped, TTL'd), extract a small text sample for
  * the model to reason about, and return a short `attachmentId`. Only that
  * handle + sample travel through the chat; when the model later calls a file
  * tool with the `attachmentId`, AgentLoop substitutes the real bytes. */
@RestController
@RequestMapping(Array("/api/v1"))
class AssistantAttachmentController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[AssistantAttachmentController])

    /** Per-file staging cap. Large enough for typical CSV/JSON/document drops,
      * bounded so heap can't be exhausted by one upload. */
    private val MaxBytes: Int = 25 * 1024 * 1024 // 25 MB

    /** Cap on the sample handed to the model — enough to infer schema/type
      * without bloating the chat request. */
    private val SampleMaxChars: Int = 8000
    private val SampleMaxLines: Int = 50

    @PostMapping(path = Array("/assistant/attachment"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def stage(@RequestHeader(name = "x-api-key", required = false) apiKey: String, @RequestPart("file") file: MultipartFile): ResponseEntity[String] = {
        try {
            APIKeyValidator.validate(apiKey)

            val filename = Option(file.getOriginalFilename).filter(_.nonEmpty).getOrElse("upload")
            val bytes = file.getBytes
            if (bytes == null || bytes.length == 0)
                throw new DatrisException("Attached file is empty.")
            if (bytes.length > MaxBytes)
                return new ResponseEntity[String](
                    "{\"error\":\"File too large to attach (" + bytes.length + " bytes; limit " + MaxBytes + ").\"}",
                    HttpStatus.PAYLOAD_TOO_LARGE
                )

            val tenantEnv = DatrisEnvironment.current.environment
            val (detectedType, sample) = extractSample(filename, bytes)
            val att = AttachmentStore.put(tenantEnv, filename, bytes, sample, detectedType)

            logger.info("Assistant attachment staged: tenant=" + tenantEnv + ", id=" + att.id +
                ", filename=" + filename + ", type=" + detectedType + ", bytes=" + bytes.length)

            val payload = new JsonObject()
            payload.addProperty("attachmentId", att.id)
            payload.addProperty("filename", att.filename)
            payload.addProperty("detectedType", att.detectedType)
            payload.addProperty("byteSize", att.bytes.length)
            payload.addProperty("sample", att.sample)
            new ResponseEntity[String](new Gson().toJson(payload), HttpStatus.OK)
        } catch {
            case e: DatrisException if APIKeyValidator.isKeyRejection(e) =>
                ApiErrors.internal(e)
            case e: DatrisException =>
                new ResponseEntity[String]("{\"error\":\"" + escape(e.getMessage) + "\"}", HttpStatus.BAD_REQUEST)
            case e: Exception =>
                logger.warn("Assistant attachment staging failed: " + e.getMessage)
                new ResponseEntity[String]("{\"error\":\"" + escape(e.getMessage) + "\"}", HttpStatus.INTERNAL_SERVER_ERROR)
        }
    }

    /** Detect the source category from the extension and pull a small sample
      * for the model. Text-shaped files get a decoded head; binary documents
      * get a one-line note (the model only needs to know it's a document →
      * vector store, not its contents). */
    private[datris] def extractSample(filename: String, bytes: Array[Byte]): (String, String) = {
        val ext = filename.lastIndexOf('.') match {
            case -1 => ""
            case i => filename.substring(i + 1).toLowerCase
        }
        if (!AiSampleValues.enabled) return withheldSample(filename, ext, bytes)
        ext match {
            case "csv" | "tsv" => ("CSV (structured)", headLines(bytes))
            case "json" | "ndjson" => ("JSON (structured)", headChars(bytes))
            case "xml" => ("XML (structured)", headChars(bytes))
            case "txt" | "md" | "html" | "htm" => ("document (unstructured text)", headChars(bytes))
            case "pdf" | "docx" | "doc" | "pptx" | "xlsx" =>
                ("document (unstructured)", "(binary ." + ext + " document, " + bytes.length + " bytes — text not extracted; route to a vector store)")
            case _ =>
                ("unknown", headChars(bytes))
        }
    }

    /** DATRIS_AI_SAMPLE_VALUES=false: the filename, detected type, record
      * count and column / top-level key names, never a value. The stored
      * bytes are unchanged, so tools can still upload the file. */
    private def withheldSample(filename: String, ext: String, bytes: Array[Byte]): (String, String) = {
        val note = "Values withheld by configuration (" + AiSampleValues.EnvVar + "=false); the file is attached and can still be uploaded to a pipeline."
        def lines(items: String*): String = (("File: " + filename) +: items :+ note).mkString("\n")
        lazy val text = new String(bytes, "UTF-8")
        val unparsed = "Structure unavailable (" + bytes.length + " bytes)."
        ext match {
            case "csv" | "tsv" =>
                val rows = text.split("\n").iterator.map(_.stripSuffix("\r")).filter(_.trim.nonEmpty).toList
                val delimiter = if (ext == "tsv") "\t" else ","
                val cells = rows.headOption.map(h => CodeGenTransformationEvaluator.splitLine(h, delimiter)).getOrElse(Nil)
                // Line 1 counts as a header only when it reads as names (see
                // AiSampleValues.headerLooksLikeData: person-style names and
                // letters-dash-digits ids mark it as data); a cell that is not an
                // identifier is never sent (column_N instead).
                val hasHeader = rows.nonEmpty && !AiSampleValues.headerLooksLikeData(cells)
                val columns = AiSampleValues.safeColumnNames(cells, hasHeader)
                val t = "CSV (structured)"
                val rowLine =
                    if (hasHeader) "Rows: " + (rows.size - 1) + " (excluding the header)"
                    else "Rows: " + rows.size + " (no header row detected; columns are numbered)"
                (t, lines("Type: " + t, rowLine, "Columns: " + columns.mkString(", ")))
            case "json" | "ndjson" =>
                val t = "JSON (structured)"
                val outline = AiSampleValues.jsonTopLevel(text) match {
                    case Some((records, keys)) => Seq("Records: " + records, "Top-level keys: " + keys.mkString(", "))
                    case None => Seq(unparsed)
                }
                (t, lines(("Type: " + t) +: outline: _*))
            case "xml" =>
                val t = "XML (structured)"
                val outline = AiSampleValues.xmlTopLevel(text) match {
                    case Some((root, records, names)) => Seq("Root element: " + root, "Records: " + records, "Record elements: " + names.mkString(", "))
                    case None => Seq(unparsed)
                }
                (t, lines(("Type: " + t) +: outline: _*))
            case "pdf" | "docx" | "doc" | "pptx" | "xlsx" =>
                ("document (unstructured)", "(binary ." + ext + " document, " + bytes.length + " bytes — text not extracted; route to a vector store)")
            case "txt" | "md" | "html" | "htm" =>
                val t = "document (unstructured text)"
                (t, lines("Type: " + t, "Size: " + bytes.length + " bytes"))
            case _ =>
                ("unknown", lines("Type: unknown", "Size: " + bytes.length + " bytes"))
        }
    }

    /** First N lines of a UTF-8 decode, capped at the char limit. */
    private def headLines(bytes: Array[Byte]): String =
        truncate(new String(bytes, "UTF-8").split("\n", SampleMaxLines + 1).take(SampleMaxLines).mkString("\n"))

    /** First chars of a UTF-8 decode. */
    private def headChars(bytes: Array[Byte]): String =
        truncate(new String(bytes, "UTF-8"))

    private def truncate(s: String): String =
        if (s.length <= SampleMaxChars) s else s.substring(0, SampleMaxChars) + "\n…[sample truncated]"

    private def escape(s: String): String = AssistantSseSupport.escape(s)
}
