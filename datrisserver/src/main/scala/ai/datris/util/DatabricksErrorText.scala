package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException

/** Pure helpers that turn Databricks driver failures into short messages.
  *
  * The Databricks JDBC driver puts the server's whole Spark stack (often
  * 16–36 KB: `TGetOperationStatusResp(...)` and hundreds of `at org.apache...`
  * frames) into the exception message. None of that belongs in an HTTP body,
  * an MCP tool result or an audit entry: keep the Databricks error class
  * (`[NO_SUCH_CATALOG_EXCEPTION]`, …) and the first sentence after it. */
object DatabricksErrorText {

    /** Hard cap for any message leaving the server. */
    val MaxMessageLength = 500

    private val ErrorClassRe = """\[([A-Z][A-Z0-9_]{2,}(?:\.[A-Z0-9_]+)*)\]""".r
    private val BareClassRe = """\b(PERMISSION_DENIED|INSUFFICIENT_PERMISSIONS)\b""".r
    // `[RequestId=<uuid> ErrorClass=INVALID_PARAMETER_VALUE] ...` (Unity Catalog RPC errors).
    private val TaggedClassRe = """\[[^\[\]]*?\bErrorClass=([A-Z][A-Z0-9_.]*[A-Z0-9_])\]""".r
    private val StackCutRe =
        """(\r?\n\s*at |\tat |TGetOperationStatusResp|TOpenSessionReq|\s*Request\s*\{T|\s+at [A-Za-z_$][\w$]*(?:\.[\w$]+)+\()""".r
    // `Invalid input: RPC ListSchemas Field managedcatalog.ListSchemas.catalog_name: ` before the real sentence.
    private val RpcPrefixRe = """^Invalid input:\s*(?:RPC\s+\S+\s+)?(?:Field\s+\S+?:\s+)?""".r
    private val JavaClassPrefixRe = """^\s*(?:[a-z_$][\w$]*\.)+[A-Z][\w$]*:\s*""".r
    // A sentence ends at . ! ? followed by whitespace, end, or dump punctuation (`.:37:36`, `.",`).
    private val SentenceRe = """^(.+?[.!?])(?=\s|$|:\d|[,;")\]])""".r

    private def chain(t: Throwable): List[Throwable] = {
        val seen = new java.util.IdentityHashMap[Throwable, java.lang.Boolean]()
        Iterator.iterate(t)(_.getCause).takeWhile(x => x != null && seen.put(x, java.lang.Boolean.TRUE) == null).toList
    }

    /** Every message in the cause chain, joined by newlines. */
    def fullText(e: Throwable): String =
        chain(e).flatMap(t => Option(t.getMessage)).filter(_.trim.nonEmpty).distinct.mkString("\n")

    /** Everything before the first stack frame / Thrift status dump. */
    def stripStack(text: String): String =
        if (text == null) ""
        else StackCutRe.findFirstMatchIn(text).map(m => text.substring(0, m.start)).getOrElse(text)

    /** The first non-blank line of `text` with any stack text removed. */
    def firstLine(text: String): String =
        stripStack(text).linesIterator.map(_.trim).find(_.nonEmpty).getOrElse("")

    /** `s` trimmed to at most [[MaxMessageLength]] characters. */
    def cap(s: String, max: Int = MaxMessageLength): String =
        if (s == null) ""
        else if (s.length <= max) s
        else s.take(math.max(0, max - 3)) + "..."

    /** First line (stack stripped), capped — for errors with no class to parse. */
    def short(text: String, max: Int = MaxMessageLength): String = cap(firstLine(text), max)

    /** The first sentence following `[cls]` anywhere in `text`, with Java
      * exception-class prefixes (`org.apache....NoSuchCatalogException: `)
      * removed. None when every occurrence is followed only by another
      * bracket or stack text. */
    private def sentenceAfter(text: String, cls: String): Option[String] = {
        val marker = "[" + cls + "]"
        val starts = Iterator.iterate(text.indexOf(marker))(i => text.indexOf(marker, i + marker.length)).takeWhile(_ >= 0).map(_ + marker.length)
        sentenceAt(text, starts, 1)
    }

    /** Up to `maxSentences` sentences from the first of `starts` that yields
      * one: Java class prefixes and a Unity Catalog `Invalid input: RPC ...
      * Field ...: ` prefix are removed first. */
    private def sentenceAt(text: String, starts: Iterator[Int], maxSentences: Int): Option[String] = {
        for (start <- starts) {
            var rest = text.substring(start)
            var stripped = true
            while (stripped) {
                val next = JavaClassPrefixRe.replaceFirstIn(rest, "")
                stripped = next != rest
                rest = next
            }
            rest = RpcPrefixRe.replaceFirstIn(rest.trim, "").trim
            if (rest.nonEmpty && !rest.startsWith("[")) {
                val line = firstLine(rest)
                val first = SentenceRe.findFirstMatchIn(line).map(_.group(1))
                val sentence = first match {
                    case Some(one) =>
                        var acc = one
                        var more = maxSentences - 1
                        while (more > 0) {
                            val tail = line.substring(acc.length)
                            val next = if (tail.nonEmpty && tail.charAt(0).isWhitespace) SentenceRe.findFirstMatchIn(tail.trim).map(_.group(1)) else None
                            next.filter(n => n.headOption.exists(_.isUpper) && !n.contains("SQLSTATE")) match {
                                case Some(n) =>
                                    acc = acc + tail.takeWhile(_.isWhitespace) + n
                                    more -= 1
                                case None => more = 0
                            }
                        }
                        acc
                    case None => line.split("SQLSTATE", 2)(0)
                }
                if (sentence.trim.nonEmpty) return Some(sentence.trim)
            }
        }
        None
    }

    private val NotFoundClasses = Set("NO_SUCH_CATALOG_EXCEPTION", "SCHEMA_NOT_FOUND", "TABLE_OR_VIEW_NOT_FOUND", "CATALOG_NOT_FOUND")

    // Errors in the caller's SQL (syntax, unknown column/table/function,
    // ambiguity, type mismatch, bad cast) — the caller's to fix, so 400.
    private val ClientSqlErrorPrefixes =
        Seq("PARSE_SYNTAX_ERROR", "UNRESOLVED_COLUMN", "UNRESOLVED_TABLE", "UNRESOLVED_ROUTINE", "AMBIGUOUS_", "DATATYPE_MISMATCH", "CAST_INVALID_INPUT")

    private def statusFor(cls: String): Int = {
        val base = cls.takeWhile(_ != '.')
        if (NotFoundClasses.contains(base) || base.startsWith("NO_SUCH_") || base.endsWith("_NOT_FOUND")) 404
        else if (base == "INVALID_PARAMETER_VALUE" || ClientSqlErrorPrefixes.exists(base.startsWith)) 400
        else if (base == "PERMISSION_DENIED" || base.startsWith("INSUFFICIENT")) 403
        else 502
    }

    private def fallbackSentence(cls: String): String = {
        val base = cls.takeWhile(_ != '.')
        if (base.contains("CATALOG")) "Catalog was not found."
        else if (base.contains("SCHEMA")) "Schema was not found."
        else if (base.contains("TABLE") || base.contains("VIEW")) "Table or view was not found."
        else if (statusFor(cls) == 404) "Object was not found."
        else if (statusFor(cls) == 403) "Permission denied."
        else if (ClientSqlErrorPrefixes.exists(base.startsWith)) "The SQL is invalid."
        else if (statusFor(cls) == 400) "Invalid parameter value."
        else "Databricks query failed."
    }

    /** HTTP status and a short (≤ [[MaxMessageLength]]) message for a SQL
      * warehouse failure: 404 for missing catalog/schema/table, 400 for
      * INVALID_PARAMETER_VALUE and SQL errors (PARSE_SYNTAX_ERROR,
      * UNRESOLVED_*, AMBIGUOUS_*, DATATYPE_MISMATCH*, CAST_INVALID_INPUT), 403 for PERMISSION_DENIED/INSUFFICIENT_*,
      * else 502. Connect-time failures (already translated into a
      * [[DatrisException]] naming the field to fix) keep their text, first
      * line only. */
    def translateWarehouseError(e: Throwable): (Int, String) = {
        if (e == null) return (502, "Databricks query failed")
        val text = fullText(e)
        if (text.isEmpty) return (502, e.getClass.getSimpleName)
        if (e.isInstanceOf[DatrisException]) return (502, short(text))
        val bare = ErrorClassRe.findFirstMatchIn(text)
        val tagged = TaggedClassRe.findFirstMatchIn(text)
        val useTagged = tagged.isDefined && bare.forall(_.start > tagged.get.start)
        (if (useTagged) tagged else bare).map(_.group(1)) match {
            case Some(cls) =>
                val sentence =
                    (if (useTagged) sentenceAt(text, TaggedClassRe.findAllMatchIn(text).filter(_.group(1) == cls).map(_.end), 2)
                     else sentenceAfter(text, cls)).getOrElse(fallbackSentence(cls))
                (statusFor(cls), cap("[" + cls + "] " + sentence))
            case None =>
                val status = if (BareClassRe.findFirstIn(text).isDefined) 403 else 502
                val line = short(text)
                (status, if (line.nonEmpty) line else e.getClass.getSimpleName)
        }
    }
}
