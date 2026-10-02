package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.{GsonBuilder, JsonArray, JsonElement, JsonNull, JsonObject, JsonParser}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._

/** One field's suggestion. `current` is the field's existing `protect` (null
  * when it has none) so a re-run reads as a suggestion next to the stored
  * policy, never as an overwrite. `suggested` is null for "no protection". */
case class Suggested(name: String, `type`: String, current: ProtectionPolicy, suggested: ProtectionPolicy, reason: String)

case class FieldProtectionSuggestion(fields: List[Suggested], model: String)

/** Field protection suggestions (plans/stories/field-protection-3-classifier.md).
  *
  * Asks the CodeGen model which source fields should carry `protect`, from
  * the field NAMES and TYPES alone: the user prompt is `name:type` lines and
  * nothing else, so no row value (and no existing policy) ever leaves the
  * server. Stateless and read-only. Every suggestion is clamped to what
  * PipelineValidatorUtil accepts, so accepting one never yields a config the
  * validator rejects. */
object FieldProtectionAdvisor {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    val SystemPrompt: String =
        """You advise on per-field data protection for a data pipeline.
          |
          |You receive one line per field in the form name:type. You see field names and types only; you will see no values.
          |For each field choose one method:
          |- "hmac": a stable identifier that must still join or group across rows (equal inputs give equal tokens). String fields only.
          |- "mask": a display field where a short tail is still useful. Set "preserve" to the one that fits, or null to mask every character:
          |  "last4" keeps the last four characters, "domain" keeps the part after "@", "year" keeps a leading four-digit year. String fields only.
          |- "redact": free text that may hold sensitive details and is not needed as written. String fields only.
          |- "drop": a field that is never needed downstream. Any type.
          |- "none": everything else (counts, amounts, flags, codes, timestamps that identify no one).
          |Only "drop" or "none" may be chosen for a field whose type is not string.
          |Prefer "none" when a name gives no clear signal.
          |
          |Reply with a JSON array only: no prose, no markdown, no code fences. One object per input field, in input order:
          |{"name": <field name exactly as given>, "method": "hmac" | "mask" | "redact" | "drop" | "none", "preserve": "last4" | "domain" | "year" | null, "reason": <one short sentence>}
          |
          |Example input:
          |id_a:string
          |contact_b:string
          |count_c:int
          |
          |Example output:
          |[{"name": "id_a", "method": "hmac", "preserve": null, "reason": "stable identifier"}, {"name": "contact_b", "method": "mask", "preserve": "domain", "reason": "contact address; the domain is still useful"}, {"name": "count_c", "method": "none", "preserve": null, "reason": "a count"}]""".stripMargin

    /** Production: the CodeGen model (`ai.codegen.*`, else the primary config). */
    def suggest(fields: List[SchemaField]): FieldProtectionSuggestion = {
        val cfg = DatrisEnvironment.aiConfigForCodegen
        val ai: (String, String) => String = (system, user) => {
            val response = AIUtil.callAIWithSystem(system, user, cfg)
            AIUtil.extractText(response, cfg)
        }
        suggest(fields, ai, if (cfg == null) null else cfg.model)
    }

    /** `ai(systemPrompt, userPrompt)` returns the model's extracted text (a JSON array). */
    private[datris] def suggest(fields: List[SchemaField], ai: (String, String) => String, model: String): FieldProtectionSuggestion = {
        val input = Option(fields).getOrElse(Nil).filter(f => f != null && f.name != null && f.name.trim.nonEmpty)
        if (input.isEmpty) throw new DatrisException("No fields to suggest protection for")

        val user = userPrompt(input)
        logger.info("Field protection suggest: asking model for " + input.size + " field(s), names and types only")
        val answer = parseAnswer(ai(SystemPrompt, user))

        val result = input.map { f =>
            answer.get(f.name.trim.toLowerCase) match {
                case Some((method, preserve, reason)) =>
                    Suggested(f.name, f.`type`, f.protect, clamp(f.`type`, method, preserve), reason)
                case None =>
                    Suggested(f.name, f.`type`, f.protect, null, null)
            }
        }
        FieldProtectionSuggestion(result, model)
    }

    /** `name:type` lines only. */
    private[datris] def userPrompt(fields: List[SchemaField]): String =
        fields.map(f => oneLine(f.name) + ":" + oneLine(f.`type`)).mkString("\n")

    private def oneLine(s: String): String = Option(s).map(_.replaceAll("[\\r\\n]+", " ").trim).getOrElse("")

    private def isString(t: String): Boolean = t != null && t.trim.equalsIgnoreCase("string")

    /** Validator rules: known method; preserve only with mask and only from
      * the allowed set; string-producing methods only on string fields. */
    private def clamp(fieldType: String, method: String, preserve: String): ProtectionPolicy = {
        val m = Option(method).map(_.trim.toLowerCase).getOrElse("")
        if (!ProtectionPolicy.Methods.contains(m)) return null
        if (m != "drop" && !isString(fieldType)) return null
        val p =
            if (m == "mask") Option(preserve).map(_.trim.toLowerCase).filter(ProtectionPolicy.Preserves.contains).orNull
            else null
        ProtectionPolicy(m, p, null)
    }

    private def str(o: JsonObject, key: String): String = {
        val e = o.get(key)
        if (e == null || e.isJsonNull || !e.isJsonPrimitive) null else e.getAsString
    }

    /** lowercased name → (method, preserve, reason); the first entry per name wins. */
    private def parseAnswer(text: String): Map[String, (String, String, String)] = {
        val raw = Option(text).getOrElse("")
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start)
            throw new DatrisException("The AI model returned no field protection suggestions")
        val arr: JsonArray =
            try JsonParser.parseString(raw.substring(start, end + 1)).getAsJsonArray
            catch {
                case _: Exception => throw new DatrisException("The AI model returned malformed field protection suggestions")
            }
        val out = scala.collection.mutable.LinkedHashMap[String, (String, String, String)]()
        arr.asScala.foreach { e: JsonElement =>
            if (e != null && e.isJsonObject) {
                val o = e.getAsJsonObject
                val name = str(o, "name")
                if (name != null && name.trim.nonEmpty) {
                    val key = name.trim.toLowerCase
                    if (!out.contains(key)) out(key) = (str(o, "method"), str(o, "preserve"), str(o, "reason"))
                }
            }
        }
        out.toMap
    }

    private def policyJson(p: ProtectionPolicy): JsonElement =
        if (p == null || p.method == null) JsonNull.INSTANCE
        else {
            val o = new JsonObject()
            o.addProperty("method", p.method)
            if (p.preserve != null) o.addProperty("preserve", p.preserve) else o.add("preserve", JsonNull.INSTANCE)
            o
        }

    /** `{"model", "fields": [{"name","type","current","suggested":{"method","preserve"}|null,"reason"}]}` */
    def toJson(s: FieldProtectionSuggestion): String = {
        val root = new JsonObject()
        root.addProperty("model", s.model)
        val arr = new JsonArray()
        s.fields.foreach { f =>
            val o = new JsonObject()
            o.addProperty("name", f.name)
            o.addProperty("type", f.`type`)
            o.add("current", policyJson(f.current))
            o.add("suggested", policyJson(f.suggested))
            o.addProperty("reason", f.reason)
            arr.add(o)
        }
        root.add("fields", arr)
        new GsonBuilder().serializeNulls().create().toJson(root)
    }
}
