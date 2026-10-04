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

    /** Upper bound per call: one answer line per field must fit the model's output budget. */
    val MaxFields = 150

    /** A key column may only carry hmac: mask/redact would merge rows, drop would remove the key. */
    val KeyColumnReason = "key column; only hmac keeps rows distinct"

    /** Document fields (`_json`, `_xml`) hold a whole record; protect names its top-level keys instead. */
    val DocumentFields: Set[String] = Set("_json", "_xml")
    val DocumentFieldReason = "document field; protect its top-level keys instead"

    /** Config-dependent validator rules for a stored pipeline: lowercased
      * keyFields (database ++ objectStore) and destination field types. */
    def constraintsOf(config: PipelineConfig): (Set[String], Map[String, String]) = {
        if (config == null || config.destination == null) return (Set.empty, Map.empty)
        val d = config.destination
        val db = if (d.database != null) d.database.keyFields else null
        val os = if (d.objectStore != null) d.objectStore.keyFields else null
        val keys = (Option(db).map(_.asScala.toList).getOrElse(Nil) ++ Option(os).map(_.asScala.toList).getOrElse(Nil))
            .filter(_ != null)
            .map(_.trim.toLowerCase)
            .toSet
        val destTypes =
            if (d.schemaProperties != null && d.schemaProperties.fields != null)
                d.schemaProperties.fields.asScala
                    .filter(f => f != null && f.name != null && f.`type` != null)
                    .map(f => f.name.trim.toLowerCase -> f.`type`)
                    .toMap
            else Map.empty[String, String]
        (keys, destTypes)
    }

    /** Production: the CodeGen model (`ai.codegen.*`, else the primary config).
      * `keyFields` / `destTypes` (lowercased names) come from a stored
      * pipeline, so suggestions also respect the validator's config rules. */
    def suggest(
        fields: List[SchemaField],
        keyFields: Set[String],
        destTypes: Map[String, String]
    ): FieldProtectionSuggestion = {
        val cfg = DatrisEnvironment.aiConfigForCodegen
        val ai: (String, String) => String = (system, user) => {
            val response = AIUtil.callAIWithSystem(system, user, cfg)
            AIUtil.extractText(response, cfg)
        }
        suggest(fields, ai, if (cfg == null) null else cfg.model, keyFields, destTypes)
    }

    def suggest(fields: List[SchemaField]): FieldProtectionSuggestion = suggest(fields, Set.empty[String], Map.empty[String, String])

    /** `ai(systemPrompt, userPrompt)` returns the model's extracted text (a JSON array). */
    private[datris] def suggest(
        fields: List[SchemaField],
        ai: (String, String) => String,
        model: String,
        keyFields: Set[String] = Set.empty,
        destTypes: Map[String, String] = Map.empty
    ): FieldProtectionSuggestion = {
        val input = Option(fields).getOrElse(Nil).filter(f => f != null && f.name != null && f.name.trim.nonEmpty)
        if (input.isEmpty) throw new DatrisException("No fields to suggest protection for")
        if (input.size > MaxFields)
            throw new DatrisException("Too many fields for one suggestion call (" + input.size + "); pass a subset through 'fields'")

        def isDocument(f: SchemaField): Boolean = DocumentFields.contains(f.name.trim.toLowerCase)
        val asked = input.filterNot(isDocument)

        val answer =
            if (asked.isEmpty) Map.empty[String, (String, String, String)]
            else {
                logger.info("Field protection suggest: asking model for " + asked.size + " field(s), names and types only")
                parseAnswer(ai(SystemPrompt, userPrompt(asked)))
            }

        val keys = keyFields.map(_.toLowerCase)
        val dest = destTypes.map { case (k, v) => k.toLowerCase -> v }

        val result = input.map { f =>
            val key = f.name.trim.toLowerCase
            if (isDocument(f)) Suggested(f.name, f.`type`, f.protect, null, DocumentFieldReason)
            else
                answer.get(key) match {
                    case Some((method, preserve, reason)) =>
                        val policy = clamp(f.`type`, method, preserve)
                        if (policy == null) Suggested(f.name, f.`type`, f.protect, null, reason)
                        else
                            constrain(f.name, f.`type`, policy, keys, dest) match {
                                case Left(why) => Suggested(f.name, f.`type`, f.protect, null, why)
                                case Right(p) => Suggested(f.name, f.`type`, f.protect, p, reason)
                            }
                    case None =>
                        Suggested(f.name, f.`type`, f.protect, null, null)
                }
        }
        FieldProtectionSuggestion(result, model)
    }

    /** The validator's config-dependent rules as a clamp, shared by the
      * suggestion endpoint and the Safe Harbor preset (ProtectionPreset):
      * Right(policy) when `policy` can stand on this field, else Left(reason)
      * meaning "no protection". A string-producing method needs a string
      * source type; a key column (`keyFields`, lowercased) only takes hmac;
      * a non-string destination column only takes drop. */
    private[datris] def constrain(
        fieldName: String,
        fieldType: String,
        policy: ProtectionPolicy,
        keyFields: Set[String],
        destTypes: Map[String, String]
    ): Either[String, ProtectionPolicy] = {
        val method = Option(policy).flatMap(p => Option(p.method)).map(_.trim.toLowerCase).getOrElse("")
        val key = Option(fieldName).map(_.trim.toLowerCase).getOrElse("")
        if (method.isEmpty) Left(null)
        else if (method != "drop" && !isString(fieldType))
            Left("source type is " + Option(fieldType).map(_.trim).getOrElse("unknown") + "; " + method + " produces a string")
        else if (method != "hmac" && keyFields.contains(key)) Left(KeyColumnReason)
        else if (method != "drop" && destTypes.get(key).exists(t => !isString(t)))
            Left("destination type is " + destTypes(key).trim + "; " + method + " produces a string")
        else Right(policy)
    }

    /** `name:type` lines only. */
    private[datris] def userPrompt(fields: List[SchemaField]): String =
        fields.map(f => oneLine(f.name) + ":" + oneLine(f.`type`)).mkString("\n")

    private def oneLine(s: String): String = Option(s).map(_.replaceAll("[\\r\\n]+", " ").trim).getOrElse("")

    private def isString(t: String): Boolean = t != null && t.trim.equalsIgnoreCase("string")

    /** Methods a suggestion may carry: every accepted method except
      * `encrypt`, which is reversible and so a deliberate choice by a person
      * (field-protection-5), never a model's proposal. */
    private val Suggestable: Set[String] = ProtectionPolicy.Methods - "encrypt"

    /** Preserve values the prompt offers. `first3` (field-protection-10) is a
      * Safe Harbor preset value for ZIP codes and is not offered to the model,
      * so an off-prompt `first3` answer drops the preserve like any unknown one. */
    private val SuggestablePreserves: Set[String] = Set("last4", "domain", "year")

    /** Validator rules: known method; preserve only with mask and only from
      * the allowed set; string-producing methods only on string fields. */
    private def clamp(fieldType: String, method: String, preserve: String): ProtectionPolicy = {
        val m = Option(method).map(_.trim.toLowerCase).getOrElse("")
        if (!Suggestable.contains(m)) return null
        if (m != "drop" && !isString(fieldType)) return null
        val p =
            if (m == "mask") Option(preserve).map(_.trim.toLowerCase).filter(SuggestablePreserves.contains).orNull
            else null
        ProtectionPolicy(m, p, null)
    }

    private def str(o: JsonObject, key: String): String = {
        val e = o.get(key)
        if (e == null || e.isJsonNull || !e.isJsonPrimitive) null else e.getAsString
    }

    private val ArrayStart = "\\[\\s*[\\{\\]]".r
    private val Fence = "(?s)^```[a-zA-Z]*\\s*(.*?)\\s*```$".r

    /** The model's text as a JSON array: the whole (fence-stripped) text when
      * it parses, else the slice from the first `[{` / `[]` to the last `]`,
      * so a bracketed preamble such as "[Analysis]" does not break parsing. */
    private[datris] def extractArray(text: String): JsonArray = {
        val trimmed = Option(text).getOrElse("").trim
        val body = trimmed match {
            case Fence(inner) => inner.trim
            case other => other
        }
        val whole =
            try {
                val e = JsonParser.parseString(body)
                if (e != null && e.isJsonArray) e.getAsJsonArray else null
            } catch { case _: Exception => null }
        if (whole != null) return whole

        val start = ArrayStart.findFirstMatchIn(body).map(_.start).getOrElse(-1)
        val end = body.lastIndexOf(']')
        if (start < 0 || end <= start)
            throw new DatrisException("The AI model returned no field protection suggestions")
        try JsonParser.parseString(body.substring(start, end + 1)).getAsJsonArray
        catch {
            case _: Exception => throw new DatrisException("The AI model returned malformed field protection suggestions")
        }
    }

    /** lowercased name → (method, preserve, reason); the first entry per name wins. */
    private def parseAnswer(text: String): Map[String, (String, String, String)] = {
        val arr = extractArray(text)
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
