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
            keys.toList
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

    private def skeleton(e: JsonElement): JsonElement = {
        if (e == null || e.isJsonNull) JsonNull.INSTANCE
        else if (e.isJsonObject) {
            val out = new JsonObject()
            e.getAsJsonObject.entrySet().asScala.foreach(en => out.add(en.getKey, skeleton(en.getValue)))
            out
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
            out
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
        val namespaces = mutable.LinkedHashMap[String, String]()
        val children = mutable.LinkedHashMap[String, XNode]()
        val repeated = mutable.Set[String]()
        var hasText = false
    }

    private def qname(prefix: String, local: String): String =
        if (prefix == null || prefix.isEmpty) local else prefix + ":" + local

    /** Element and attribute names kept (repeated siblings merged and marked),
      * text, comments, CDATA and attribute values dropped. Namespace
      * declarations are kept (they name the schema, not a row). Unparseable
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
                                node.namespaces(if (p == null || p.isEmpty) "xmlns" else "xmlns:" + p) = Option(r.getNamespaceURI(i)).getOrElse("")
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
        node.namespaces.foreach { case (k, v) => sb.append(' ').append(k).append("=\"").append(escapeAttr(v)).append('"') }
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

    private def escapeAttr(s: String): String =
        s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

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
}
