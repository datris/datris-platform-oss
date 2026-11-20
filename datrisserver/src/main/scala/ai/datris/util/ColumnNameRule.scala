package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{PipelineConfig, SchemaProperties}

import scala.collection.JavaConverters._

/** The save rule for schema field names. Pure, no I/O.
  *
  * A field name saved in a source or destination schema must be [[plain]]
  * (letters, digits and `_`), unless it is [[kept]]: the stored pipeline
  * already has a field with exactly that name in the same schema, and the
  * save's lower-casing (`PipelineValidatorUtil.modify`) leaves it as it is.
  * Such a name can only have been stored by schema evolution, which keeps a
  * header cell as written (lower-cased); every destination quotes it. So a
  * pipeline with an evolved column can be saved again unchanged, while a new
  * or changed name (one the stored schema does not have in exactly that
  * form, including a different case or spacing, or one moved to the other
  * schema) still has to be plain. */
object ColumnNameRule {

    /** True when `name` is letters, digits and `_` only. */
    def plain(name: String): Boolean = name != null && name.matches("[A-Za-z0-9_]+")

    /** The validator's refusal for a field name that is not plain. */
    def problem(name: String): String = "Column name: " + name + " is invalid.  Valid characters are a-z, 0-9 and _"

    /** True when `name` is in `storedNames` exactly, and the save's
      * lower-casing would not change it (so a re-save never renames it). */
    def kept(name: String, storedNames: Seq[String]): Boolean =
        name != null && name.toLowerCase == name && storedNames.contains(name)

    /** The stored config's field names in one schema (`sourceSchema` true:
      * source, else destination); Nil for a missing config or schema. */
    def storedNames(stored: PipelineConfig, sourceSchema: Boolean): Seq[String] =
        if (stored == null) Nil
        else {
            val props: SchemaProperties =
                if (sourceSchema) Option(stored.source).map(_.schemaProperties).orNull
                else Option(stored.destination).map(_.schemaProperties).orNull
            if (props == null || props.fields == null) Nil
            else props.fields.asScala.toList.filter(_ != null).map(_.name).filter(_ != null)
        }

    /** The refusal for `name` in a schema whose stored names are
      * `storedNames()` (read only when `name` is not plain); None when the
      * name is plain or kept. */
    def refusal(name: String, storedNames: () => Seq[String]): Option[String] =
        if (plain(name) || kept(name, storedNames())) None else Some(problem(name))
}
