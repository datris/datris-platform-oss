package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{PipelineConfig, SchemaField, SchemaProperties}

import java.util.regex.Pattern
import scala.collection.JavaConverters._

/** The one rule for the type text a schema field may carry. Pure, no I/O.
  *
  * A type is [[safe]] when the whole text, in any letter case, is one of the
  * plain type names (`boolean, int, tinyint, smallint, bigint, float, double,
  * string, date, timestamp`, plus `integer`, which the loaders map and which
  * is tolerated at load only: the save validator still refuses it), or
  * `decimal(P)` / `decimal(P,S)` (1-4 digits each), `varchar(N)` / `char(N)`
  * (1-9 digits) or `varchar(max)`. Spaces (U+0020 only) may stand only
  * inside the parentheses, beside a number, `max` or the comma; nothing may
  * come before the type name, between the name and `(`, or after the
  * closing `)`.
  *
  * The save path ([[changedProblem]]) refuses a new or changed type that is
  * not safe, on either schema; a field whose name and type the stored config
  * already has is kept. The loaders send a destination only a fixed mapping
  * or a safe type, and use [[loadProblem]] to fail before any statement when
  * a stored type that is not safe would be needed to create a table or a
  * column. Document columns (`_json`, `_xml`) are typed by name on every
  * engine, so their type text is never examined at load. */
object ColumnTypeText {

    /** How much of a refused type a message shows. */
    val MaxShown: Int = 80

    private val Rule: Pattern = Pattern.compile(
        "(?:boolean|int|integer|tinyint|smallint|bigint|float|double|string|date|timestamp" +
            "|decimal\\( *[0-9]{1,4} *(?:, *[0-9]{1,4} *)?\\)" +
            "|(?:var)?char\\( *[0-9]{1,9} *\\)" +
            "|varchar\\( *max *\\))",
        Pattern.CASE_INSENSITIVE
    )

    private val Supported =
        "supported types include boolean, int, tinyint, smallint, bigint, float, double, decimal(?,?), string, varchar(?), char(?), date, and timestamp"

    /** True when `t` is exactly one of the accepted forms; false for null. */
    def safe(t: String): Boolean = t != null && Rule.matcher(t).matches()

    /** `_json` / `_xml` (any letter case): typed by name on every engine. */
    def isDocumentColumn(f: SchemaField): Boolean =
        f != null && f.name != null && (f.name.equalsIgnoreCase("_json") || f.name.equalsIgnoreCase("_xml"))

    /** The fields among `fields` that are not document columns and whose
      * type is not [[safe]]. */
    def unsafeFields(fields: Seq[SchemaField]): Seq[SchemaField] =
        Option(fields).getOrElse(Nil).filter(f => f != null && !isDocumentColumn(f) && !safe(f.`type`))

    /** The refusal for `field` in the `schema` ("source" or "destination")
      * schema, worded like the save validator; None when its type is safe.
      * At most [[MaxShown]] characters of the type are shown, with control
      * characters replaced, so the message is one line. */
    def problem(field: SchemaField, schema: String): Option[String] =
        if (field == null || safe(field.`type`)) None
        else
            Some(
                "Invalid field type passed: " + shown(field.`type`) + " for field '" + oneLine(field.name) + "' in the " + schema +
                    " schema, " + Supported
            )

    /** The save rule: the first source, then destination, field whose type
      * is not safe and is not kept from `stored` (a field of the same name in
      * the same schema with the same type, both compared case-insensitively),
      * as [[problem]] words it. Null-safe for missing configs and schemas. */
    def changedProblem(candidate: PipelineConfig, stored: PipelineConfig): Option[String] = {
        if (candidate == null) return None
        schemaProblem(sourceFields(candidate), sourceFields(stored), "source")
            .orElse(schemaProblem(destinationFields(candidate), destinationFields(stored), "destination"))
    }

    /** At load: the first field of `fields` whose type is not safe and that
      * is not a document column and not already in the table
      * (`existingColumns`, lower-cased names). The loader would need that
      * type to create the table or the column, so the run fails with this
      * message before any statement. */
    def loadProblem(fields: Seq[SchemaField], existingColumns: Set[String]): Option[String] =
        unsafeFields(fields)
            .find(f => f.name == null || !existingColumns.contains(f.name.toLowerCase))
            .flatMap(f => problem(f, "destination"))
            .map(_ + ". The type is needed to create this column and is not sent to the destination; change it to a supported type and save the pipeline.")

    private def schemaProblem(candidate: Seq[SchemaField], stored: Seq[SchemaField], schema: String): Option[String] =
        candidate.iterator
            .filter(f => f != null && !safe(f.`type`) && !kept(f, stored))
            .map(f => problem(f, schema))
            .collectFirst { case Some(p) => p }

    private def kept(f: SchemaField, stored: Seq[SchemaField]): Boolean =
        stored.exists(s => s != null && sameText(s.name, f.name) && sameText(s.`type`, f.`type`))

    private def sameText(a: String, b: String): Boolean =
        if (a == null || b == null) a == null && b == null else a.equalsIgnoreCase(b)

    private def fieldsOf(p: SchemaProperties): Seq[SchemaField] =
        if (p == null || p.fields == null) Nil else p.fields.asScala.toList

    private def sourceFields(c: PipelineConfig): Seq[SchemaField] =
        if (c == null || c.source == null) Nil else fieldsOf(c.source.schemaProperties)

    private def destinationFields(c: PipelineConfig): Seq[SchemaField] =
        if (c == null || c.destination == null) Nil else fieldsOf(c.destination.schemaProperties)

    private def oneLine(s: String): String =
        if (s == null) "null" else s.map(c => if (Character.isISOControl(c)) ' ' else c)

    private def shown(t: String): String = if (t == null) "null" else oneLine(t.take(MaxShown))
}
