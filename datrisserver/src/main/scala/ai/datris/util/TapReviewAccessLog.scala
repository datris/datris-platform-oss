package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisEnvironment

import java.time.Instant
import scala.collection.JavaConverters._

/** The identities that called `POST /tap/review` without holding
  * `tap:update` since the server started (plans/stories/tap-save-references-in-scope.md,
  * rule 4, report only), per environment, as the `tap.references` doctor row
  * that reads it is. In memory, at most [[MaxLabels]] distinct labels per
  * environment; past that the label recorded longest ago is dropped. The
  * durable record is the audit row [[TapOwnerScopeReport.reviewWithoutUpdate]]
  * writes. */
object TapReviewAccessLog {

    /** Distinct labels kept per environment. */
    val MaxLabels = 200

    /** When recording started: the server start. */
    val since: Instant = Instant.now()

    private val held = new java.util.concurrent.ConcurrentHashMap[String, java.util.LinkedHashMap[String, java.lang.Boolean]]()

    private def currentEnv: String = Option(DatrisEnvironment.current).map(e => String.valueOf(e.environment)).getOrElse("null")

    private def of(env: String): java.util.LinkedHashMap[String, java.lang.Boolean] =
        held.computeIfAbsent(String.valueOf(env), _ => new java.util.LinkedHashMap[String, java.lang.Boolean]())

    /** Record `label` for `env` (the current environment by default; blank
      * labels are ignored). Never throws. */
    def record(label: String, env: String = null): Unit =
        if (label != null && label.trim.nonEmpty) {
            val m = of(Option(env).getOrElse(currentEnv))
            m.synchronized {
                m.remove(label)
                m.put(label, java.lang.Boolean.TRUE)
                val it = m.keySet().iterator()
                while (m.size > MaxLabels && it.hasNext) { it.next(); it.remove() }
            }
        }

    /** The distinct labels recorded for `env` (the current environment by
      * default), earliest first, at most [[MaxLabels]]. */
    def labels(env: String = null): List[String] = {
        val m = held.get(Option(env).getOrElse(currentEnv))
        if (m == null) Nil else m.synchronized(m.keySet().asScala.toList)
    }
}
