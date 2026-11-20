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

    /** What a destination can hold, for comparing a save with the stored
      * config: the database engine flags, the object store and its file
      * format, and which other destinations are present. */
    private[util] def destinationKind(c: PipelineConfig): Seq[Any] = {
        val d = if (c == null) null else c.destination
        if (d == null) Nil
        else {
            val db = Option(d.database).map(x => (x.usePostgres, x.useMongoDB, x.useSnowflake, x.useDatabricks, x.useSqlServer))
            val os = Option(d.objectStore).map(o => Option(o.fileFormat).map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("parquet"))
            Seq(db, os, d.kafka != null, d.activeMQ != null, d.restEndpoint != null, d.scratch != null)
        }
    }

    /** The save rule for one field name of `candidate`'s schema
      * (`sourceSchema`): None when it is plain, or kept and the destination
      * is unchanged; the plain-name refusal when it is not kept. A kept name
      * on a save that changes the destination (another engine, another
      * object-store file format, or an added destination) must pass the
      * check a new header cell passes for that destination
      * (`IngestColumnNames.problem`: control characters, the destination's
      * length limit, the ORC backtick rule). `stored` is called only for a
      * name that is not plain. */
    def saveProblem(name: String, candidate: PipelineConfig, stored: () => PipelineConfig, sourceSchema: Boolean): Option[String] =
        if (plain(name)) None
        else {
            val s = stored()
            if (!kept(name, storedNames(s, sourceSchema))) Some(problem(name))
            else if (destinationKind(candidate) == destinationKind(s)) None
            else
                IngestColumnNames
                    .problem(List(name), Nil, IngestColumnNames.limitsFor(candidate))
                    .map(p =>
                        "Column name: " + IngestColumnNames.display(name) + " in the " + (if (sourceSchema) "source" else "destination") +
                            " schema cannot be kept for the new destination: " + p
                    )
        }

    /** The refusal for `name` in a schema whose stored names are
      * `storedNames()` (read only when `name` is not plain); None when the
      * name is plain or kept. */
    def refusal(name: String, storedNames: () => Seq[String]): Option[String] =
        if (plain(name) || kept(name, storedNames())) None else Some(problem(name))
}
