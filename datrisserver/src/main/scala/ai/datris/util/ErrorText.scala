package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException

/** Plain-words text for a failure, for status lines a person or a model reads.
  * The full stack trace belongs in the server log and the status event's
  * `detail` field, never here. */
object ErrorText {

    /** The throwable's message (or its simple class name when it has none),
      * then each distinct cause as "\ncaused by: <message>". A cause whose text
      * repeats the previous line is skipped; a wrapper whose message is the
      * JDK's auto-generated `cause.toString` contributes nothing; cycles end.
      * Each message is reduced to its first line unless it comes from a
      * DatrisException (whose multi-line messages, e.g. the data-quality
      * failure list, are kept, minus any embedded stack-frame lines). The total is capped at `maxChars`, ending "…". */
    def messageChain(e: Throwable, maxChars: Int = 2000): String = {
        if (e == null) return ""
        val seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[Throwable, java.lang.Boolean]())
        val lines = scala.collection.mutable.ListBuffer[String]()
        var current = e
        while (current != null && seen.add(current)) {
            val cause = current.getCause
            val isAutoWrapper = cause != null && cause != current && current.getMessage != null && current.getMessage == cause.toString
            if (!isAutoWrapper) {
                val line = text(current)
                if (lines.isEmpty || lines.last != line) lines += line
            }
            current = if (cause == current) null else cause
        }
        val out = lines.mkString("\ncaused by: ")
        cap(out, maxChars)
    }

    private def text(t: Throwable): String = {
        val m = Option(t.getMessage).map(_.trim).filter(_.nonEmpty)
        m match {
            case None => Option(t.getClass.getSimpleName).filter(_.nonEmpty).getOrElse(t.getClass.getName)
            case Some(msg) if t.isInstanceOf[DatrisException] =>
                // Keep the multi-line message, but never an embedded trace.
                val kept = msg.split("\\r?\\n").filterNot(l => TraceLine.pattern.matcher(l).matches()).mkString("\n").trim
                if (kept.nonEmpty) kept else Option(t.getClass.getSimpleName).filter(_.nonEmpty).getOrElse(t.getClass.getName)
            case Some(msg) => msg.split("\\r?\\n", 2)(0).trim
        }
    }

    /** A stack-frame line ("\tat pkg.Class.method(") or a "... N more" line. */
    private val TraceLine = "^\\s*(?:at [\\w.$/<>-]+\\(.*|\\.\\.\\. \\d+ more)$".r

    /** Package roots whose class names are shown fully qualified; the same
      * roots AiSampleValues.scrubErrorForModel lets through. */
    private val KnownRoots = List("java.", "javax.", "scala.", "ai.datris.", "org.", "com.", "io.")

    /** The exception classes along the cause chain, outermost first, joined by
      * " <- " (e.g. "java.lang.IllegalStateException <- java.io.IOException").
      * Class names only, never message text: safe for a model even when
      * message text must be withheld. Classes outside the known package roots
      * are shown by simple name. Cycles end; capped at `maxChars`. */
    def classChain(e: Throwable, maxChars: Int = 500): String = {
        if (e == null) return ""
        val seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[Throwable, java.lang.Boolean]())
        val names = scala.collection.mutable.ListBuffer[String]()
        var current = e
        while (current != null && seen.add(current)) {
            val fqn = current.getClass.getName
            names += (
                if (KnownRoots.exists(fqn.startsWith)) fqn
                else Option(current.getClass.getSimpleName).filter(_.nonEmpty).getOrElse("Throwable")
            )
            current = current.getCause
        }
        cap(names.mkString(" <- "), maxChars)
    }

    private def cap(s: String, maxChars: Int): String =
        if (maxChars <= 0) ""
        else if (s.length <= maxChars) s
        else s.take(maxChars - 1) + "…"
}
