package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import java.time.Instant
import scala.collection.JavaConverters._

/** The identities that called `POST /tap/review` without holding
  * `tap:update` since the server started (plans/stories/tap-save-references-in-scope.md,
  * rule 4, report only). In memory, at most [[MaxLabels]] distinct labels;
  * past that the label recorded longest ago is dropped. Read by the
  * `tap.references` doctor row. */
object TapReviewAccessLog {

    /** Distinct labels kept. */
    val MaxLabels = 200

    /** When recording started: the server start. */
    val since: Instant = Instant.now()

    private val held = new java.util.LinkedHashMap[String, java.lang.Boolean]()

    /** Record `label` (blank labels are ignored). Never throws. */
    def record(label: String): Unit =
        if (label != null && label.trim.nonEmpty) held.synchronized {
            held.remove(label)
            held.put(label, java.lang.Boolean.TRUE)
            val it = held.keySet().iterator()
            while (held.size > MaxLabels && it.hasNext) { it.next(); it.remove() }
        }

    /** The distinct labels recorded, earliest first, at most [[MaxLabels]]. */
    def labels(): List[String] = held.synchronized(held.keySet().asScala.toList)
}
