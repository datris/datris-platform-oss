package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.PipelineConfig

import java.nio.charset.StandardCharsets

/** The ingest check on NEW column names (header cells not already in the
  * stored schema). Pure, no I/O.
  *
  * Every destination quotes column names, so a new name may hold any
  * character a destination can store. Refused are only the names no
  * configured destination can hold, or that would land under another
  * column:
  *   - NUL or any other control character;
  *   - a name equal to a stored or an earlier new name after trim and case
  *     folding;
  *   - a name longer than the destination allows (Postgres 63 UTF-8 bytes,
  *     SQL Server 128 characters, Snowflake and Databricks 255 characters;
  *     other destinations have no limit);
  *   - on Postgres, a name equal to the first 63 bytes of another column;
  *   - on an object store writing ORC files, a name holding a backtick
  *     (ORC files do not keep it: the column reads back under another name).
  * Stored names are never examined. */
object IngestColumnNames {

    /** Length rules for the configured destination. Built by [[limitsFor]]. */
    final case class Limits(maxBytes: Option[Int], maxChars: Option[Int], postgresPrefix: Boolean, orcNoBacktick: Boolean = false)

    private val PostgresMaxBytes = 63
    private val SqlServerMaxChars = 128
    private val SnowflakeDatabricksMaxChars = 255
    private val DisplayMaxChars = 80

    def limitsFor(config: PipelineConfig): Limits = {
        val db = Option(config).flatMap(c => Option(c.destination)).flatMap(d => Option(d.database))
        val postgres = db.exists(_.usePostgres)
        val chars = Seq(
            db.filter(_.useSqlServer).map(_ => SqlServerMaxChars),
            db.filter(d => d.useSnowflake || d.useDatabricks).map(_ => SnowflakeDatabricksMaxChars)
        ).flatten
        val orc = Option(config)
            .flatMap(c => Option(c.destination))
            .flatMap(d => Option(d.objectStore))
            .flatMap(o => Option(o.fileFormat))
            .exists(_.trim.equalsIgnoreCase("orc"))
        Limits(if (postgres) Some(PostgresMaxBytes) else None, if (chars.isEmpty) None else Some(chars.min), postgres, orc)
    }

    /** The first problem among `newNames`, or None. The message names the
      * cell (through [[display]]) and its 1-based position among the new
      * names. */
    def problem(newNames: List[String], storedNames: List[String], limits: Limits): Option[String] = {
        val stored = storedNames.filter(_ != null)
        val storedByKey: Map[String, String] = stored.reverse.map(s => key(s) -> s).toMap
        val storedPrefixes: Map[String, String] =
            if (limits.postgresPrefix) stored.reverse.map(s => key(clipBytes(s, PostgresMaxBytes)) -> s).toMap else Map.empty
        val seen = scala.collection.mutable.Map[String, Int]()

        newNames.zipWithIndex.iterator.map {
            case (name, i) =>
                val pos = i + 1
                def cell = "New column #" + pos + " in the header, '" + display(name) + "',"
                if (name == null || name.trim.isEmpty) Some(cell + " is blank")
                else if (name.exists(Character.isISOControl))
                    Some(cell + " holds a control character; rename the header cell")
                else if (limits.orcNoBacktick && name.contains("`"))
                    Some(cell + " holds a backtick, which ORC files cannot keep in a column name; rename the header cell")
                else if (storedByKey.contains(key(name)))
                    Some(cell + " is the existing column '" + display(storedByKey(key(name))) + "' apart from spaces or letter case; rename the header cell")
                else if (seen.contains(key(name)))
                    Some(cell + " repeats new column #" + seen(key(name)) + " apart from spaces or letter case; rename one of the header cells")
                else {
                    seen(key(name)) = pos
                    val bytes = name.getBytes(StandardCharsets.UTF_8).length
                    limits.maxBytes.filter(bytes > _) match {
                        case Some(max) => Some(cell + " is " + bytes + " bytes long; this destination allows at most " + max + " bytes")
                        case None =>
                            limits.maxChars.filter(name.length > _) match {
                                case Some(max) =>
                                    Some(cell + " is " + name.length + " characters long; this destination allows at most " + max + " characters")
                                case None =>
                                    storedPrefixes
                                        .get(key(name))
                                        .map(other =>
                                            cell + " is the same as the existing column '" + display(other) +
                                                "' in its first 63 bytes, which is all this destination keeps; rename the header cell"
                                        )
                            }
                    }
                }
        }.collectFirst { case Some(p) => p }
    }

    /** `name` for a message: control characters as `\\uXXXX`, cut at 80
      * characters. */
    def display(name: String): String = {
        if (name == null) return "null"
        val cut =
            if (name.length <= DisplayMaxChars) name
            else {
                val head = name.substring(0, DisplayMaxChars)
                (if (Character.isHighSurrogate(head.last)) head.dropRight(1) else head) + "..."
            }
        cut.flatMap(c => if (Character.isISOControl(c)) "\\u%04x".format(c.toInt) else c.toString)
    }

    private def key(name: String): String = name.trim.toLowerCase

    /** The first `max` UTF-8 bytes of `name`, cut on a character boundary, as
      * Postgres truncates an over-long identifier. */
    private def clipBytes(name: String, max: Int): String = {
        var bytes = 0
        var i = 0
        var done = false
        while (i < name.length && !done) {
            val cp = name.codePointAt(i)
            val n = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length
            if (bytes + n > max) done = true
            else {
                bytes += n
                i += Character.charCount(cp)
            }
        }
        name.substring(0, i)
    }
}
