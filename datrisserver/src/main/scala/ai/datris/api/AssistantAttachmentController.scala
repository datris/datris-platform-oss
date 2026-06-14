package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.{Gson, JsonObject}
import ai.datris.model.{DatrisEnvironment, DatrisException}
import ai.datris.util.{APIKeyValidator, AttachmentStore}
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
    def stage(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
              @RequestPart("file") file: MultipartFile): ResponseEntity[String] = {
        try {
            APIKeyValidator.validate(apiKey)

            val filename = Option(file.getOriginalFilename).filter(_.nonEmpty).getOrElse("upload")
            val bytes = file.getBytes
            if (bytes == null || bytes.length == 0)
                throw new DatrisException("Attached file is empty.")
            if (bytes.length > MaxBytes)
                return new ResponseEntity[String](
                    "{\"error\":\"File too large to attach (" + bytes.length + " bytes; limit " + MaxBytes + ").\"}",
                    HttpStatus.PAYLOAD_TOO_LARGE)

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
    private def extractSample(filename: String, bytes: Array[Byte]): (String, String) = {
        val ext = filename.lastIndexOf('.') match {
            case -1 => ""
            case i  => filename.substring(i + 1).toLowerCase
        }
        ext match {
            case "csv" | "tsv"                  => ("CSV (structured)", headLines(bytes))
            case "json" | "ndjson"              => ("JSON (structured)", headChars(bytes))
            case "xml"                          => ("XML (structured)", headChars(bytes))
            case "txt" | "md" | "html" | "htm"  => ("document (unstructured text)", headChars(bytes))
            case "pdf" | "docx" | "doc" | "pptx" | "xlsx" =>
                ("document (unstructured)", "(binary ." + ext + " document, " + bytes.length + " bytes — text not extracted; route to a vector store)")
            case _ =>
                ("unknown", headChars(bytes))
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
