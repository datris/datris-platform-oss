package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.model.{DatrisEnvironment, DatrisException, TapPromptFragment}
import ai.datris.util.{AIUtil, APIKeyValidator, TapPromptFragmentIO, TapPromptInjector}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

@RestController
@RequestMapping(Array("/api/v1"))
class TapPromptAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[TapPromptAPIController])

    @GetMapping(path = Array("/tap-prompts"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def listFragments(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /tap-prompts called")
            APIKeyValidator.validate(apiKey)
            val tableName = DatrisEnvironment.current.tapPromptTableName
            val fragments = TapPromptFragmentIO.readAll(tableName)
            val gson = new Gson
            new ResponseEntity[String](gson.toJson(fragments.asJava), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/tap-prompts/{key}"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getFragment(@RequestHeader(name = "x-api-key", required = false) apiKey: String, @PathVariable key: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /tap-prompts/" + key + " called")
            APIKeyValidator.validate(apiKey)
            val tableName = DatrisEnvironment.current.tapPromptTableName
            val fragment = TapPromptFragmentIO.read(tableName, key)
            if (fragment == null)
                ResponseEntity.status(HttpStatus.NOT_FOUND).body[String]("{\"error\": \"Fragment not found: " + key + "\"}")
            else {
                val gson = new Gson
                new ResponseEntity[String](gson.toJson(fragment), HttpStatus.OK)
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap-prompts"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def saveFragment(@RequestHeader(name = "x-api-key", required = false) apiKey: String, @RequestBody body: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /tap-prompts called")
            APIKeyValidator.validate(apiKey)

            val gson = new Gson
            val incoming = gson.fromJson(body, classOf[TapPromptFragment])
            if (incoming == null || incoming.key == null || incoming.key.trim.isEmpty)
                throw new DatrisException("Fragment key is required")

            val tableName = DatrisEnvironment.current.tapPromptTableName
            val now = new java.text.SimpleDateFormat(DatrisEnvironment.current.dateFormat)
                .format(new java.util.Date())
            val existing = TapPromptFragmentIO.read(tableName, incoming.key)
            val createdAt = if (existing != null && existing.createdAt != null) existing.createdAt else now
            val stamped = incoming.copy(createdAt = createdAt, updatedAt = now)

            TapPromptFragmentIO.write(stamped)
            TapPromptInjector.invalidateCache()

            new ResponseEntity[String]("{\"status\": \"ok\"}", HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap-prompts/suggest"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def suggestContent(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: java.util.Map[String, Any]
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /tap-prompts/suggest called")
            APIKeyValidator.validate(apiKey)

            val key = Option(body.get("key")).map(_.toString.trim).getOrElse("")
            if (key.isEmpty) throw new DatrisException("Fragment key is required")

            val aliases = body.get("aliases") match {
                case list: java.util.List[_] => list.asScala.map(_.toString).filter(_.nonEmpty).toList
                case _ => List.empty[String]
            }
            val current = Option(body.get("content")).map(_.toString).getOrElse("")

            val systemPrompt =
                """You write system-prompt fragments that are injected into an AI code generator when a user creates a "tap" — a Python script that fetches data from an external source.
                  |
                  |Your job: given a source key (e.g. "AWS", "ExampleAPI", "Stripe") and optional aliases, write a short, dense fragment (2-6 sentences, under 500 characters when possible) that captures the non-obvious conventions, gotchas, or constraints the code generator should know when writing a tap for that source. Prefer concrete specifics: library choice, auth env var names, rate limits, pagination style, required headers, known quirks.
                  |
                  |Rules:
                  |- Return ONLY the fragment text. No preamble, no markdown fences, no commentary, no headings.
                  |- Do NOT restate the key as a title — the key is already shown in the UI.
                  |- If the user provided existing content, refine/expand it rather than replacing its intent.
                  |- Reference concrete Python libraries and env var names the generator should use.
                  |- Never hardcode credentials; always route through os.environ.get("...").
                  |- If the source is a public/free API, say so and list any rate limits.
                  |- Keep it tight and actionable — this text gets appended to a much longer system prompt.""".stripMargin

            val aliasClause = if (aliases.nonEmpty) " Aliases: " + aliases.mkString(", ") + "." else ""
            val currentClause = if (current.trim.nonEmpty)
                "\n\nExisting content (refine or expand this):\n" + current.trim
            else ""
            val userPrompt = "Data source key: " + key + "." + aliasClause + currentClause +
                "\n\nWrite the fragment now."

            val codegenCfg = DatrisEnvironment.aiConfigForCodegen
            val responseText = AIUtil.callAIWithSystem(systemPrompt, userPrompt, codegenCfg)
            val extracted = AIUtil.extractText(responseText, codegenCfg).trim
            val cleaned = extracted
                .replaceAll("(?s)^```(?:\\w+)?\\s*", "")
                .replaceAll("(?s)\\s*```$", "")
                .trim

            val gson = new Gson
            val result = new java.util.HashMap[String, Any]()
            result.put("content", cleaned)
            new ResponseEntity[String](gson.toJson(result), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @DeleteMapping(path = Array("/tap-prompts/{key}"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def deleteFragment(@RequestHeader(name = "x-api-key", required = false) apiKey: String, @PathVariable key: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /tap-prompts/" + key + " called")
            APIKeyValidator.validate(apiKey)
            val tableName = DatrisEnvironment.current.tapPromptTableName
            TapPromptFragmentIO.delete(tableName, key)
            TapPromptInjector.invalidateCache()
            new ResponseEntity[String]("{\"status\": \"ok\"}", HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }
}
