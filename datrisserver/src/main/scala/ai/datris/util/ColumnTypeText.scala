package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisException, PipelineConfig, SchemaField, SchemaProperties}

import java.util.regex.Pattern
import scala.collection.JavaConverters._

/** The one rule for the type text a schema field may carry. Pure, no I/O.
  *
  * A type is [[safe]] when the whole text, in any letter case, is one of the
  * plain type names (`boolean, int, tinyint, smallint, bigint, float, double,
  * string, date, timestamp`, plus `integer`, which the loaders map and which
  * is tolerated at load only: the save validator still refuses it), or
  * `decimal(P)` / `decimal(P,S)` (1-4 digits each), `varchar(N)` / `char(N)`
  * (1-9 digits) or `varchar(max)`. Spaces (U+0020 only) may stand inside
  * the parentheses, beside a number, `max` or the comma, and after the
  * closing `)`; nothing may come before the type name, between the name and
  * `(`, or after a plain type name. At load, [[loadText]] also reads other
  * ASCII blanks in those places (tab, line break) as stored by earlier
  * versions.
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
            "|decimal\\( *[0-9]{1,4} *(?:, *[0-9]{1,4} *)?\\) *" +
            "|(?:var)?char\\( *[0-9]{1,9} *\\) *" +
            "|varchar\\( *max *\\) *)",
        Pattern.CASE_INSENSITIVE
    )

    private val Supported =
        "supported types include boolean, int, tinyint, smallint, bigint, float, double, decimal(?,?), string, varchar(?), char(?), date, and timestamp"

    /** True when `t` is exactly one of the accepted forms; false for null. */
    def safe(t: String): Boolean = t != null && Rule.matcher(t).matches()

    /** The ASCII whitespace characters an engine's SQL lexer reads as a
      * blank: space, tab, LF, VT, FF, CR. */
    private def asciiBlank(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\u000b' || c == '\f' || c == '\r'

    /** Load only: an ASCII blank or a Unicode space separator
      * (`Character.isSpaceChar`: U+00A0, U+2000-U+200A, U+2028, U+3000, ...). */
    private[util] def loadBlank(c: Char): Boolean = asciiBlank(c) || Character.isSpaceChar(c)

    /** The type text a loader may map and send for stored text `t`: `t`
      * itself when it is [[safe]]; otherwise, when `t` with every ASCII
      * blank (tab, line break, VT, FF) or Unicode space separator read as a
      * space is [[safe]], `t` with every such character removed (a stored type such as `decimal(10,\t2)`
      * loads as `decimal(10,2)`). None for anything else, null included.
      * Load only: the save rule is [[safe]]. */
    def loadText(t: String): Option[String] =
        if (t == null) None
        else if (safe(t)) Some(t)
        else if (safe(t.map(c => if (loadBlank(c)) ' ' else c))) Some(t.filterNot(loadBlank))
        else None

    /** [[loadText]], or a `DatrisException` with [[problem]]'s message for
      * the destination schema. */
    def loadTextOrThrow(field: SchemaField): String =
        loadText(field.`type`).getOrElse(throw new DatrisException(problem(field, "destination").get))

    /** `_json` / `_xml` (any letter case): typed by name on every engine. */
    def isDocumentColumn(f: SchemaField): Boolean =
        f != null && f.name != null && (f.name.equalsIgnoreCase("_json") || f.name.equalsIgnoreCase("_xml"))

    /** The fields among `fields` that are not document columns and whose
      * type has no [[loadText]]. */
    def unsafeFields(fields: Seq[SchemaField]): Seq[SchemaField] =
        Option(fields).getOrElse(Nil).filter(f => f != null && !isDocumentColumn(f) && loadText(f.`type`).isEmpty)

    /** The one status line a load writes when the table already has every
      * column whose stored type has no [[loadText]]. */
    def notUsedStatus(unsafe: Seq[SchemaField]): String =
        "The table already has column(s) " + unsafe.map(f => shownName(f.name)).mkString(", ") +
            "; their stored type is not a supported type and was not used. Change it to a supported type and save the pipeline."

    /** What a load runs against a table it may create, in order: `schemaSql`
      * then `tableSql` (both None when a stored type has no [[loadText]]),
      * then, once the existing columns are read, [[evolve]]'s statements. */
    case class CreatePlan(schemaSql: Option[String], tableSql: Option[String])

    /** The ADD COLUMN statements, in field order, and the status line
      * ([[notUsedStatus]]) when a stored type was not used. */
    case class EvolvePlan(addColumns: Seq[String], statusLine: Option[String])

    /** The create step for `fields`: nothing when a field has no
      * [[loadText]] (the existing columns are read first), else the given
      * statements. */
    def create(fields: Seq[SchemaField], schemaSql: => Option[String], tableSql: => String): CreatePlan =
        if (unsafeFields(fields).nonEmpty) CreatePlan(None, None) else CreatePlan(schemaSql, Some(tableSql))

    /** The evolve step: Left([[loadProblem]]) when a stored type that has no
      * [[loadText]] would be needed; else `addSql` for each field whose
      * lower-cased name is not in `existing`, and [[notUsedStatus]] when any
      * field's stored type was not used. */
    def evolve(fields: Seq[SchemaField], existing: Set[String], addSql: SchemaField => String): Either[String, EvolvePlan] = {
        val unsafe = unsafeFields(fields)
        val p = if (unsafe.isEmpty) None else loadProblem(fields, existing)
        if (p.isDefined) Left(p.get)
        else
            Right(
                EvolvePlan(
                    fields.filter(f => !existing.contains(f.name.toLowerCase)).map(addSql),
                    if (unsafe.isEmpty) None else Some(notUsedStatus(unsafe))
                )
            )
    }

    /** The refusal for `field` in the `schema` ("source" or "destination")
      * schema, worded like the save validator; None when its type is safe.
      * At most [[MaxShown]] characters of the type are shown, with control
      * characters replaced, so the message is one line. */
    def problem(field: SchemaField, schema: String): Option[String] =
        if (field == null || safe(field.`type`)) None
        else
            Some(
                "Invalid field type passed: " + shown(field.`type`) + " for field '" + shownName(field.name) + "' in the " + schema +
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

    /** Printable ASCII as is; every other character (control characters,
      * line and paragraph separators, non-ASCII spaces and letters) as
      * `\\uXXXX`, so a message is one line and cannot show text that looks
      * like an accepted type when it is not. */
    private def oneLine(s: String): String =
        if (s == null) "null"
        else s.flatMap(c => if (c >= ' ' && c <= '~') c.toString else "\\u%04x".format(c.toInt))

    private def shown(t: String): String = if (t == null) "null" else oneLine(t.take(MaxShown))

    /** A field name as a message shows it: at most [[MaxShown]] characters,
      * one line. */
    private[util] def shownName(n: String): String = shown(n)
}
