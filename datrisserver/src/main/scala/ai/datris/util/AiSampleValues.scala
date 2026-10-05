package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonArray, JsonElement, JsonNull, JsonObject, JsonParser, JsonPrimitive}
import org.slf4j.{Logger, LoggerFactory}

import java.io.{Reader, StringReader}
import javax.xml.stream.{XMLInputFactory, XMLStreamConstants}
import scala.collection.mutable
import scala.collection.JavaConverters._

/** Per-column statistics that carry no value: counts, lengths and an inferred type. */
case class ColumnStat(name: String, inferredType: String, count: Long, nulls: Long, distinct: Long, minLength: Int, maxLength: Int)

/** DATRIS_AI_SAMPLE_VALUES (plans/stories/field-protection-9-ai-values-switch.md):
  * when `false`, the helpers that generate configuration or code (schema
  * generation, profiling, Assistant attachments, CodeGen rules and
  * transformations) send no row value to a model; they work from the
  * value-free structure built here instead. */
object AiSampleValues {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    val Property = "datris.aiSampleValues"
    val EnvVar = "DATRIS_AI_SAMPLE_VALUES"

    private val warnedUnknown = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()

    /** Read at call time: the `datris.aiSampleValues` system property, else
      * DATRIS_AI_SAMPLE_VALUES; trimmed and lowercased. Only `false` turns it
      * off; unset or empty is on; any other value is on and logged once. */
    def enabled: Boolean = {
        val raw = sys.props.get(Property).orElse(sys.env.get(EnvVar))
        raw.map(_.trim.toLowerCase(java.util.Locale.ROOT)) match {
            case Some("false") => false
            case Some(v) if v.nonEmpty && v != "true" =>
                if (warnedUnknown.putIfAbsent(v, java.lang.Boolean.TRUE) == null)
                    logger.warn(EnvVar + "='" + v + "' is not 'true' or 'false'; treating it as 'true' (row values are sent to the model by helpers)")
                true
            case _ => true
        }
    }

    /** Sent instead of a skeleton when the input cannot be parsed: never the raw text. */
    def structureUnavailable(fileType: String, size: Long): String =
        "structure unavailable (" + fileType + ", " + size + " chars)"

    // ---------------------------------------------------------------- JSON

    /** Cap on distinct shapes kept per array, so a skeleton stays small. */
    private val MaxShapes = 50

    /** Same keys and shape; scalars become "<string>", 0, true, null; arrays
      * keep one representative per distinct shape. NDJSON is skeletonised line
      * by line and merged by shape. Unparseable input yields
      * [[structureUnavailable]], never the input. */
    def jsonSkeleton(sample: String): String = {
        if (sample == null) return structureUnavailable("JSON", 0)
        try skeleton(JsonParser.parseString(sample)).toString
        catch {
            case _: Exception =>
                try {
                    val shapes = new ShapeSet
                    sample.split("\n").iterator.map(_.trim).filter(_.nonEmpty).foreach(l => shapes.add(JsonParser.parseString(l)))
                    if (shapes.isEmpty) structureUnavailable("JSON", sample.length)
                    else shapes.representatives.map(_.toString).mkString("\n")
                } catch { case _: Exception => structureUnavailable("JSON", sample.length) }
        }
    }

    /** Skeleton of a stream of JSON records (a staged NDJSON payload): an
      * array of representatives when `asArray`, else one per line. */
    private[datris] def jsonSkeletonOfRecords(records: Iterator[String], asArray: Boolean): String = {
        val shapes = new ShapeSet
        records.foreach(r => if (r != null && r.trim.nonEmpty) shapes.add(JsonParser.parseString(r)))
        if (asArray) {
            val arr = new JsonArray()
            shapes.representatives.foreach(arr.add)
            arr.toString
        } else shapes.representatives.map(_.toString).mkString("\n")
    }

    /** Record count and the top-level key names of a JSON document (array of
      * objects, one object, or NDJSON); None when it cannot be parsed. */
    private[datris] def jsonTopLevel(sample: String): Option[(Long, List[String])] = {
        def keysOf(elements: Seq[JsonElement]): List[String] = {
            val keys = mutable.LinkedHashSet[String]()
            elements.foreach(e => if (e.isJsonObject) e.getAsJsonObject.keySet().asScala.foreach(keys += _))
            // Keys that are data (emails, ids, names) are never listed.
            if (keys.exists(k => !isIdentifier(k)) || elements.exists(e => e.isJsonObject && keyedByData(e.getAsJsonObject))) List(CollapsedKey)
            else if (keys.size > MaxKeys) keys.take(MaxKeys).toList :+ ("… " + (keys.size - MaxKeys) + " more")
            else keys.toList
        }
        try {
            val e = JsonParser.parseString(sample)
            if (e.isJsonArray) {
                val items = e.getAsJsonArray.asScala.toSeq
                Some((items.size.toLong, keysOf(items)))
            } else Some((1L, keysOf(Seq(e))))
        } catch {
            case _: Exception =>
                try {
                    val items = sample.split("\n").iterator.map(_.trim).filter(_.nonEmpty).map(l => JsonParser.parseString(l)).toSeq
                    if (items.isEmpty) None else Some((items.size.toLong, keysOf(items)))
                } catch { case _: Exception => None }
        }
    }

    private def skeleton(e: JsonElement): JsonElement = skeleton(e, underKey = false)

    /** `underKey`: `e` is the value of an object key (not the document root,
      * not an array element), where a keyed map of same-shaped values is
      * collapsed ([[keyedByData]]). */
    private def skeleton(e: JsonElement, underKey: Boolean): JsonElement = {
        if (e == null || e.isJsonNull) JsonNull.INSTANCE
        else if (e.isJsonObject) {
            val out = new JsonObject()
            e.getAsJsonObject.entrySet().asScala.foreach(en => out.add(en.getKey, skeleton(en.getValue, underKey = true)))
            collapseIfKeyedByData(out, underKey)
        } else if (e.isJsonArray) {
            val shapes = new ShapeSet
            e.getAsJsonArray.asScala.foreach(shapes.add)
            val out = new JsonArray()
            shapes.representatives.foreach(out.add)
            out
        } else {
            val p = e.getAsJsonPrimitive
            if (p.isBoolean) new JsonPrimitive(true)
            else if (p.isNumber) new JsonPrimitive(0)
            else new JsonPrimitive("<string>")
        }
    }

    /** Placeholder for object keys that are data. */
    val CollapsedKey = "<key>"

    /** Objects with more keys than this are treated as maps keyed by data. */
    private val MaxKeys = 50

    private val IdentifierPattern = "^[A-Za-z_][A-Za-z0-9_ .\\-]{0,63}$".r

    /** Header cells: letters, digits, underscore and space only. */
    private val HeaderCellPattern = "^[A-Za-z_][A-Za-z0-9_ ]{0,63}$".r

    /** A dash next to a digit, an `@`, or more than four digits in a row: a
      * cell like this is a value (ids, dates, emails, numbers), never a name. */
    private val DataLike = "(?:\\d-|-\\d|@|\\d{5,})".r

    /** Two or more space-separated capitalised words ("Jane Doe"). */
    private val NameLike = "(?:^|\\s)[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+(?=\\s|$)".r

    private def digitCount(s: String): Int = s.count(_.isDigit)

    /** A name that reads as a key or stored field name, not a value: starts
      * with a letter or underscore; at most 64 letters, digits, `_`, space,
      * `.` and `-`; not all digits; no dash next to a digit, no `@`, no run
      * of more than four digits; at most two digits; and not two or more
      * capitalised words. Used for JSON keys and stored schema field names. */
    def isIdentifier(name: String): Boolean =
        name != null && IdentifierPattern.pattern.matcher(name).matches() && !name.forall(_.isDigit) &&
            DataLike.findFirstIn(name).isEmpty && digitCount(name) <= 2 && NameLike.findFirstIn(name).isEmpty

    /** A header cell that may be sent as a column name: letters, digits,
      * underscore and space only (no dash, dot or other punctuation), starts
      * with a letter or underscore, at most 64 characters and two digits, not
      * all digits, and not two or more capitalised words. */
    def isHeaderCell(cell: String): Boolean =
        cell != null && HeaderCellPattern.pattern.matcher(cell).matches() && !cell.forall(_.isDigit) &&
            digitCount(cell) <= 2 && NameLike.findFirstIn(cell).isEmpty

    /** An object is a map keyed by data when any key is not an identifier,
      * when it has more than [[MaxKeys]] keys, or when it has two or more keys
      * whose values are all objects of one shape (`{"Jane Doe": {...},
      * "John Roe": {...}}`). */
    private[datris] def keyedByData(o: JsonObject, underKey: Boolean = false): Boolean = {
        val keys = o.keySet().asScala
        if (keys.size > MaxKeys || keys.exists(k => !isIdentifier(k))) return true
        val values = o.entrySet().asScala.toList.map(_.getValue)
        if (values.size < 2 || values.map(shapeKey).distinct.size != 1) return false
        // Objects of one shape: a map at any level. Arrays (one per key): a map
        // when nested under a key (`{"patients": {"smith": [..], "jones": [..]}}`).
        // Same-typed scalars are not collapsed: a record's nested object
        // (`"address": {"city": .., "zip": ..}`) has exactly that shape.
        values.forall(v => v != null && v.isJsonObject) || (underKey && values.forall(v => v != null && v.isJsonArray))
    }

    /** A skeleton object (values already skeletons) that is [[keyedByData]]
      * becomes `{"<key>": <merged value shape>}`. */
    private def collapseIfKeyedByData(o: JsonObject, underKey: Boolean = false): JsonObject = {
        if (!keyedByData(o, underKey)) return o
        val merged = o.entrySet().asScala.toList.map(_.getValue).foldLeft(null: JsonElement)((acc, v) => if (acc == null) v else merge(acc, v))
        val out = new JsonObject()
        out.add(CollapsedKey, if (merged == null) JsonNull.INSTANCE else merged)
        out
    }

    /** Column names safe to send to a model. With `header == false`, or when
      * the line reads as data ([[headerLooksLikeData]]), every name is
      * `column_N`; otherwise each cell that is not [[isHeaderCell]] becomes
      * `column_N`. */
    def safeColumnNames(cells: List[String], header: Boolean): List[String] = {
        val trimmed = cells.map(cleanCell)
        if (!header || headerLooksLikeData(trimmed)) trimmed.indices.map(i => "column_" + (i + 1)).toList
        else trimmed.zipWithIndex.map { case (c, i) => if (isHeaderCell(c)) c else "column_" + (i + 1) }
    }

    /** Trimmed, with a leading UTF-8 byte-order mark removed. */
    private def cleanCell(c: String): String = if (c == null) "" else c.stripPrefix("\uFEFF").trim

    /** First non-whitespace character, or NUL. */
    def firstNonBlank(s: String): Char = {
        if (s == null) return '\u0000'
        var i = 0
        while (i < s.length && Character.isWhitespace(s.charAt(i))) i += 1
        if (i < s.length) s.charAt(i) else '\u0000'
    }

    /** True when the line reads as a data row: ANY cell has a dash next to a
      * digit, an `@`, or more than four digits in a row; or is non-empty with
      * no letter (`42`, `555 1234`); or has more than two digits. */
    def headerLooksLikeData(cells: List[String]): Boolean =
        cells.map(cleanCell).exists { c =>
            DataLike.findFirstIn(c).isDefined || (c.nonEmpty && !c.exists(_.isLetter)) || digitCount(c) > 2
        }

    private def shapeKey(e: JsonElement): String =
        if (e == null || e.isJsonNull) "null"
        else if (e.isJsonObject) e.getAsJsonObject.keySet().asScala.toList.sorted.mkString("{", ",", "}")
        else if (e.isJsonArray) "[]"
        else {
            val p = e.getAsJsonPrimitive
            if (p.isBoolean) "b" else if (p.isNumber) "n" else "s"
        }

    /** Merge two skeletons of the same shape: key union, arrays re-reduced,
      * a null yields to a typed value. */
    private def merge(a: JsonElement, b: JsonElement): JsonElement = {
        if (a == null || a.isJsonNull) b
        else if (b == null || b.isJsonNull) a
        else if (a.isJsonObject && b.isJsonObject) {
            val out = new JsonObject()
            val ao = a.getAsJsonObject
            val bo = b.getAsJsonObject
            ao.entrySet().asScala.foreach(en => out.add(en.getKey, if (bo.has(en.getKey)) merge(en.getValue, bo.get(en.getKey)) else en.getValue))
            bo.entrySet().asScala.foreach(en => if (!ao.has(en.getKey)) out.add(en.getKey, en.getValue))
            collapseIfKeyedByData(out)
        } else if (a.isJsonArray && b.isJsonArray) {
            val shapes = new ShapeSet
            a.getAsJsonArray.asScala.foreach(shapes.add)
            b.getAsJsonArray.asScala.foreach(shapes.add)
            val out = new JsonArray()
            shapes.representatives.foreach(out.add)
            out
        } else a
    }

    /** One representative skeleton per distinct shape, in first-seen order. */
    private final class ShapeSet {
        private val reps = mutable.LinkedHashMap[String, JsonElement]()
        def add(e: JsonElement): Unit = {
            val s = skeleton(e)
            val k = shapeKey(s)
            reps.get(k) match {
                case Some(r) => reps(k) = merge(r, s)
                case None => if (reps.size < MaxShapes) reps(k) = s
            }
        }
        def isEmpty: Boolean = reps.isEmpty
        def representatives: List[JsonElement] = reps.values.toList
    }

    // ----------------------------------------------------------------- XML

    /** A StAX factory that never reads a DTD or resolves an external entity. */
    private def secureFactory(): XMLInputFactory = {
        val f = XMLInputFactory.newFactory()
        f.setProperty(XMLInputFactory.SUPPORT_DTD, java.lang.Boolean.FALSE)
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, java.lang.Boolean.FALSE)
        try f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, java.lang.Boolean.FALSE)
        catch { case _: IllegalArgumentException => () }
        f.setXMLResolver((_: String, _: String, _: String, _: String) => new java.io.ByteArrayInputStream(Array.emptyByteArray))
        f
    }

    private final class XNode(val name: String) {
        val attrs = mutable.LinkedHashSet[String]()
        val namespaces = mutable.LinkedHashMap[String, String]() // prefix -> "" (URIs are never kept)
        val children = mutable.LinkedHashMap[String, XNode]()
        val repeated = mutable.Set[String]()
        var hasText = false
    }

    private def qname(prefix: String, local: String): String =
        if (prefix == null || prefix.isEmpty) local else prefix + ":" + local

    /** Element and attribute names kept (repeated siblings merged and marked),
      * text, comments, CDATA, attribute values and namespace URIs dropped
      * (namespace prefixes are kept). Unparseable
      * input yields [[structureUnavailable]], never the input. */
    def xmlSkeleton(sample: String): String =
        if (sample == null) structureUnavailable("XML", 0)
        else xmlSkeletonOf(new StringReader(sample), sample.length)

    /** [[xmlSkeleton]] over a reader (a staged document), streamed. */
    private[datris] def xmlSkeletonOf(reader: Reader, size: Long): String =
        xmlTree(reader) match {
            case Some(root) =>
                val sb = new StringBuilder
                render(root, 0, sb)
                sb.toString.stripSuffix("\n")
            case None => structureUnavailable("XML", size)
        }

    /** Root element name, number of record elements under it and their
      * names; None when the document cannot be parsed. */
    private[datris] def xmlTopLevel(sample: String): Option[(String, Long, List[String])] = {
        try {
            val r = secureFactory().createXMLStreamReader(new StringReader(sample))
            try {
                var depth = 0
                var root: String = null
                var records = 0L
                val names = mutable.LinkedHashSet[String]()
                while (r.hasNext) {
                    r.next() match {
                        case XMLStreamConstants.START_ELEMENT =>
                            depth += 1
                            val n = qname(r.getPrefix, r.getLocalName)
                            if (depth == 1) root = n
                            else if (depth == 2) { records += 1; names += n }
                        case XMLStreamConstants.END_ELEMENT => depth -= 1
                        case _ => ()
                    }
                }
                if (root == null) None else Some((root, records, names.toList))
            } finally r.close()
        } catch { case _: Exception => None }
    }

    private def xmlTree(reader: Reader): Option[XNode] = {
        try {
            val r = secureFactory().createXMLStreamReader(reader)
            try {
                var root: XNode = null
                val stack = new java.util.ArrayDeque[(XNode, mutable.Map[String, Int])]()
                while (r.hasNext) {
                    r.next() match {
                        case XMLStreamConstants.START_ELEMENT =>
                            val n = qname(r.getPrefix, r.getLocalName)
                            val node =
                                if (stack.isEmpty) {
                                    if (root == null) root = new XNode(n)
                                    root
                                } else {
                                    val (parent, counts) = stack.peek()
                                    val c = counts.getOrElse(n, 0) + 1
                                    counts(n) = c
                                    if (c > 1) parent.repeated += n
                                    parent.children.getOrElseUpdate(n, new XNode(n))
                                }
                            (0 until r.getNamespaceCount).foreach { i =>
                                val p = r.getNamespacePrefix(i)
                                node.namespaces(if (p == null || p.isEmpty) "xmlns" else "xmlns:" + p) = "" // prefixes kept, URIs blanked
                            }
                            (0 until r.getAttributeCount).foreach(i => node.attrs += qname(r.getAttributePrefix(i), r.getAttributeLocalName(i)))
                            stack.push((node, mutable.Map[String, Int]()))
                        case XMLStreamConstants.END_ELEMENT =>
                            stack.pop()
                        case XMLStreamConstants.CHARACTERS | XMLStreamConstants.CDATA =>
                            if (!stack.isEmpty && !r.isWhiteSpace && r.getText.trim.nonEmpty) stack.peek()._1.hasText = true
                        case XMLStreamConstants.ENTITY_REFERENCE =>
                            if (!stack.isEmpty) stack.peek()._1.hasText = true
                        case _ => ()
                    }
                }
                Option(root)
            } finally r.close()
        } catch {
            case e: Exception =>
                logger.debug("XML skeleton: document could not be parsed: " + e.getClass.getSimpleName)
                None
        }
    }

    private def render(node: XNode, depth: Int, sb: StringBuilder): Unit = {
        val pad = "  " * depth
        sb.append(pad).append('<').append(node.name)
        node.namespaces.keys.foreach(k => sb.append(' ').append(k).append("=\"\""))
        node.attrs.foreach(a => sb.append(' ').append(a).append("=\"\""))
        if (node.children.isEmpty) {
            if (node.hasText) sb.append("></").append(node.name).append(">\n")
            else sb.append("/>\n")
        } else {
            sb.append(">\n")
            node.children.values.foreach { c =>
                render(c, depth + 1, sb)
                if (node.repeated.contains(c.name)) sb.append(pad).append("  <!-- ").append(c.name).append(" repeats -->\n")
            }
            sb.append(pad).append("</").append(node.name).append(">\n")
        }
    }

    // ------------------------------------------------------------ delimited

    /** Distinct values are counted by hash, capped per column. */
    private val MaxDistinctTracked = 100000

    /** Per-column counts, nulls (empty after trim), distinct values, value
      * lengths and the type `DestTypeInference` gives the column. No value is
      * retained in the result. */
    def columnStats(header: List[String], rows: Iterator[String], delimiter: String): List[ColumnStat] = {
        val n = header.size
        val count = Array.fill(n)(0L)
        val nulls = Array.fill(n)(0L)
        val minLen = Array.fill(n)(Int.MaxValue)
        val maxLen = Array.fill(n)(0)
        val types = Array.fill[String](n)(null)
        val hashes = Array.fill(n)(mutable.HashSet[Int]())
        val d = if (delimiter == "\\t") "\t" else delimiter
        rows.foreach { line =>
            val values = CodeGenTransformationEvaluator.splitLine(line.stripSuffix("\r"), d)
            var i = 0
            while (i < n) {
                val v = if (i < values.size) values(i) else ""
                count(i) += 1
                val t = v.trim
                if (t.isEmpty) nulls(i) += 1
                else {
                    if (hashes(i).size < MaxDistinctTracked) hashes(i) += scala.util.hashing.MurmurHash3.stringHash(t)
                    minLen(i) = math.min(minLen(i), t.length)
                    maxLen(i) = math.max(maxLen(i), t.length)
                    DestTypeInference.classify(t).foreach { c =>
                        types(i) = if (types(i) == null) c else DestTypeInference.widen(types(i), c)
                    }
                }
                i += 1
            }
        }
        header.zipWithIndex.map {
            case (name, i) =>
                ColumnStat(
                    name,
                    Option(types(i)).getOrElse("string"),
                    count(i),
                    nulls(i),
                    hashes(i).size.toLong,
                    if (minLen(i) == Int.MaxValue) 0 else minLen(i),
                    maxLen(i)
                )
        }
    }

    /** The stats as a text table for a prompt. */
    private[datris] def statsTable(stats: List[ColumnStat]): String =
        ("column | inferredType | count | nulls | distinct | minLength | maxLength" +:
            stats.map(s => List(s.name, s.inferredType, s.count, s.nulls, s.distinct, s.minLength, s.maxLength).mkString(" | "))).mkString("\n")

    // ------------------------------------------------------------- errors

    val DetailsWithheld = "<details withheld by configuration>"

    /** The server's own fixed message prefixes whose text carries no row
      * value. Anything after a prefix is scrubbed again (prefixes nest). */
    private val KnownPrefixes: List[scala.util.matching.Regex] = List(
        "^Aborting processing this pipeline, \\d+ error\\(s\\) were found while performing data quality rules:",
        "^CodeGen (validation|transformation) script failed \\(exit code -?\\d+\\):",
        "^CodeGen (data quality|transformation) script failed:",
        "^CodeGen script output did not contain a JSON array\\.",
        "^Pipeline error:",
        "^Data quality CodeGen failure, row: \\d+, reason:",
        "^File header validation failed for pipeline: [A-Za-z0-9_\\-]+\\.",
        "^Header validation AI response did not contain JSON:"
    ).map(_.r)

    /** `[Caused by: ]pkg.Class(Exception|Error|Throwable)[: message]`, with the
      * package under a known root (java, javax, scala, ai.datris, org, com, io). */
    private val ExceptionLine =
        "^((?:Caused by: )?)((?:java|javax|scala|ai\\.datris|org|com|io)\\.(?:[A-Za-z_$][\\w$]*\\.)*[A-Z][\\w$]*(?:Exception|Error|Throwable))(?::\\s?(.*))?$".r

    /** A JVM stack frame under a known root: `at pkg.Class.method(File.scala:12)`. */
    private val FramePattern =
        "^at (?:[\\w.$-]+/)?(?:java|javax|scala|ai\\.datris|org|com|io)\\.(?:[\\w$<>]+\\.)+[\\w$<>]+\\((?:[\\w$.-]+\\.(?:scala|java|kt)(?::\\d+)?|Unknown Source|Native Method)\\)$".r

    /** A run error made safe to send to a model with DATRIS_AI_SAMPLE_VALUES=false:
      * exception class names, stack frames and the server's fixed message
      * prefixes only. Data-quality reasons, script stderr/stdout excerpts and
      * any other message text become [[DetailsWithheld]]. */
    def scrubErrorForModel(text: String): String = {
        if (text == null) return null
        val out = mutable.ListBuffer[String]()
        def withheldOnce(): Unit = if (out.isEmpty || out.last != DetailsWithheld) out += DetailsWithheld
        text.split("\n").foreach { raw =>
            val line = raw.stripSuffix("\r")
            val t = line.trim
            if (t.isEmpty) ()
            else if (FramePattern.pattern.matcher(t).matches()) out += line
            else if (t.matches("\\.\\.\\. \\d+ more")) out += line
            else
                ExceptionLine.findFirstMatchIn(t) match {
                    case Some(m) =>
                        val msg = Option(m.group(3)).map(_.trim).getOrElse("")
                        out += m.group(1) + m.group(2) + (if (msg.isEmpty) "" else ": " + scrubMessage(msg, 0))
                    case None =>
                        val scrubbed = scrubMessage(t, 0)
                        if (scrubbed == DetailsWithheld) withheldOnce() else out += scrubbed
                }
        }
        out.mkString("\n")
    }

    private def scrubMessage(msg: String, depth: Int): String = {
        if (msg.isEmpty) return ""
        if (depth > 8) return DetailsWithheld
        KnownPrefixes.iterator.flatMap(_.findPrefixMatchOf(msg)).toSeq.headOption match {
            case Some(m) =>
                val rest = msg.substring(m.end).trim
                if (rest.isEmpty) m.matched else m.matched + " " + scrubMessage(rest, depth + 1)
            case None => DetailsWithheld
        }
    }
}
