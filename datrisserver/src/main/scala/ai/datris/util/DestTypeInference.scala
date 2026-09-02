package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.SchemaField

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import scala.collection.JavaConverters._

/** Why a column stayed `string`: the first landed value that would not parse,
  * and the type the rest of the column's values would otherwise have earned.
  * Lets the typing dialog say "stayed text — found 'N/A', would otherwise be
  * int" so an override is an informed choice. */
case class DestStringReason(value: String, blocked: String)

/** One proposed destination column with the evidence behind it: up to
  * [[DestTypeInference.maxSamples]] distinct landed values, and (for columns
  * held at `string` by a dirty value) the reason. */
case class InferredDestField(
    name: String,
    `type`: String,
    samples: java.util.List[String],
    stringReason: DestStringReason
)

/** Deterministic destination-type inference over string values.
  *
  * Classifies each non-empty value to its narrowest type, then widens per
  * column until every value fits: boolean → int → bigint → double →
  * date → timestamp → string. Any value that fits nothing typed makes the
  * column `string` — inference must never produce a type a landed value would
  * break, so all choices are conservative:
  *   - empty/null values don't vote (they're NULL either way)
  *   - leading-zero integers ("007", zip codes) stay string
  *   - integers beyond Long stay string (double would silently lose precision)
  *   - `_json` / `_xml` blob columns are never retyped (loaders map them natively)
  *
  * Emits only types every loader understands: boolean, int, bigint, double,
  * date, timestamp, string. No AI call — see plans/destination-schema-after-load.md.
  */
object DestTypeInference {

    private val NeverRetyped = Set("_json", "_xml")

    /** Provenance columns (ProvenanceStamper) stay `string`: they are stamped
      * in-memory per run, so retyping them would desync from the stored config. */
    private def isProvenanceColumn(name: String): Boolean =
        name != null && name.toLowerCase.startsWith(ProvenanceStamper.Prefix)

    /** Evidence caps for the typing dialog: how many distinct values are shown
      * per column, and how long any shown value can be. */
    private[util] val maxSamples = 5
    private[util] val maxSampleLength = 80

    private val intPattern = "^[+-]?\\d+$".r
    private val decimalPattern = "^[+-]?(\\d+\\.\\d*|\\.\\d+|\\d+)([eE][+-]?\\d+)?$".r
    private val datePattern = "^\\d{4}-\\d{2}-\\d{2}$".r
    // ISO-ish timestamps: date + 'T' or space + time, optional fraction/offset/Z.
    private val timestampPattern =
        "^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?([+-]\\d{2}:?\\d{2}|Z)?$".r

    private val isoDate = DateTimeFormatter.ISO_LOCAL_DATE

    /** Narrowest type for one value, or None for empty (doesn't vote). */
    private[util] def classify(raw: String): Option[String] = {
        if (raw == null) return None
        val v = raw.trim
        if (v.isEmpty) return None

        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) return Some("boolean")

        if (intPattern.pattern.matcher(v).matches()) {
            val digits = v.dropWhile(c => c == '+' || c == '-')
            // Leading zero on a multi-digit integer is an identifier, not a number.
            if (digits.length > 1 && digits.head == '0') return Some("string")
            return try {
                val l = digits.toLong * (if (v.startsWith("-")) -1L else 1L)
                if (l >= Int.MinValue && l <= Int.MaxValue) Some("int") else Some("bigint")
            } catch {
                case _: NumberFormatException => Some("string") // beyond Long
            }
        }

        if (decimalPattern.pattern.matcher(v).matches()) {
            return try { v.toDouble; Some("double") }
            catch { case _: NumberFormatException => Some("string") }
        }

        if (datePattern.pattern.matcher(v).matches()) {
            return try { LocalDate.parse(v, isoDate); Some("date") }
            catch { case _: Exception => Some("string") }
        }

        if (timestampPattern.pattern.matcher(v).matches()) {
            // The date part must still be a real calendar date.
            return try { LocalDate.parse(v.substring(0, 10), isoDate); Some("timestamp") }
            catch { case _: Exception => Some("string") }
        }

        Some("string")
    }

    /** Least upper bound of two inferred types. */
    private[util] def widen(a: String, b: String): String = {
        if (a == b) return a
        val pair = Set(a, b)
        if (pair.contains("string")) return "string"
        if (pair == Set("int", "bigint")) return "bigint"
        if (pair.subsetOf(Set("int", "bigint", "double"))) return "double"
        if (pair == Set("date", "timestamp")) return "timestamp"
        "string" // boolean vs anything, temporal vs numeric, ...
    }

    /** Column type over all its values; string when nothing voted. */
    private[util] def inferColumn(values: Iterable[String]): String = {
        var current: String = null
        val it = values.iterator
        while (it.hasNext) {
            classify(it.next()) match {
                case Some(t) =>
                    current = if (current == null) t else widen(current, t)
                    if (current == "string") return "string" // can't narrow again
                case None => ()
            }
        }
        if (current == null) "string" else current
    }

    /** Column type plus the dialog evidence, in one walk: distinct sample
      * values, and — when an unparseable value held an otherwise-typed column
      * at `string` — the first such value and the type it blocked. */
    private[util] def inferColumnWithEvidence(values: Iterable[String]): (String, java.util.List[String], DestStringReason) = {
        val samples = new java.util.LinkedHashSet[String]()
        var typedWidened: String = null // widen over values that parsed as something
        var firstUnparseable: String = null

        val it = values.iterator
        while (it.hasNext) {
            val raw = it.next()
            classify(raw) match {
                case Some(t) =>
                    val v = truncate(raw.trim)
                    if (samples.size < maxSamples) samples.add(v)
                    if (t == "string") {
                        if (firstUnparseable == null) firstUnparseable = v
                    } else {
                        typedWidened = if (typedWidened == null) t else widen(typedWidened, t)
                    }
                case None => ()
            }
        }

        val inferredType =
            if (firstUnparseable != null) "string"
            else if (typedWidened == null) "string"
            else typedWidened
        // A reason only makes sense when a dirty value blocked a well-defined type.
        val reason =
            if (firstUnparseable != null && typedWidened != null && typedWidened != "string")
                DestStringReason(firstUnparseable, typedWidened)
            else null
        (inferredType, new java.util.ArrayList[String](samples), reason)
    }

    private def truncate(v: String): String =
        if (v.length <= maxSampleLength) v else v.substring(0, maxSampleLength) + "…"

    /** Infer typed fields with per-column dialog evidence from records
      * (name → value maps, e.g. rows sampled from the destination). Values are
      * stringified; null stays a non-vote; names match case-insensitively.
      * `_json`/`_xml` keep their samples but are never retyped and never carry
      * a reason (they were never candidates). */
    def inferFieldsFromRecords(names: List[String], records: Iterable[Map[String, Any]]): java.util.List[InferredDestField] = {
        val lowerRecords = records.map(r => r.map { case (k, v) => k.toLowerCase -> v })
        val fields = names.map { name =>
            val values = lowerRecords.flatMap(_.get(name.toLowerCase)).map(v => if (v == null) null else v.toString)
            val (inferredType, samples, reason) = inferColumnWithEvidence(values)
            if (NeverRetyped.contains(name.toLowerCase) || isProvenanceColumn(name))
                InferredDestField(name, "string", samples, null)
            else
                InferredDestField(name, inferredType, samples, reason)
        }
        fields.asJava
    }

    /** True when every field is (still) type string — the population this
      * feature targets. */
    def allString(fields: java.util.List[SchemaField]): Boolean = {
        fields != null && !fields.isEmpty && fields.asScala.forall(f => f.`type` == null || f.`type`.equalsIgnoreCase("string"))
    }

    /** True when at least one field is a non-string type — an all-string apply
      * would be a no-op. */
    def hasTypedField(fields: java.util.List[SchemaField]): Boolean =
        fields != null && fields.asScala.exists(f => f.`type` != null && !f.`type`.equalsIgnoreCase("string"))
}
