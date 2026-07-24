package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, TapPromptFragment}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._

object TapPromptInjector {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val cache = new java.util.concurrent.ConcurrentHashMap[String, (List[TapPromptFragment], Long)]()
    private val TTL_MS = 60000L

    /** Reserved fragment key holding the approved data-sources registry. Excluded
      * from keyword matching; injected whole into the Assistant/Brainstormer prompts. */
    val DataSourcesKey = "data-sources"

    /** The approved data-sources registry, when present, enabled, and non-blank. */
    def dataSourcesDoc(): Option[String] = dataSourcesFrom(loadFragments())

    /** The registry rendered as a ready-to-append system prompt section, or "" when
      * there is no registry. Both the Assistant and the tap Brainstormer append this
      * verbatim so the model sees one consistent contract for curated sources. */
    def approvedSourcesSection(): String = dataSourcesDoc() match {
        case None => ""
        case Some(doc) =>
            "\n\n## Approved data sources\n\n" +
                "The user's organization curates this registry. When a request could be served by a listed source, " +
                "offer the matching source(s) FIRST, by name. Propose sources outside this registry only when nothing " +
                "here covers the ask — and say you are going outside the registry when you do. Registry entries do not " +
                "skip confirmation: still confirm source + scope + destination before building.\n\n" +
                doc + "\n"
    }

    private[util] def dataSourcesFrom(fragments: List[TapPromptFragment]): Option[String] =
        fragments
            .find(f => f.enabled && f.key != null && f.key.equalsIgnoreCase(DataSourcesKey))
            .map(f => Option(f.content).getOrElse("").trim)
            .filter(_.nonEmpty)

    /** Append content from matching prompt fragments to the base system prompt.
      * Matching is case-insensitive, word-boundary on the fragment's key and aliases.
      * Returns the base prompt unchanged when no matches (zero-overhead backward compat). */
    def augment(baseSystemPrompt: String, userText: String): String = {
        val matches = matchFragments(userText)
        if (matches.isEmpty) return baseSystemPrompt
        val body = matches.map(f => s"### ${f.key}\n${f.content.trim}").mkString("\n\n")
        logger.info(s"TapPromptInjector: injected ${matches.size} fragment(s): ${matches.map(_.key).mkString(", ")}")
        baseSystemPrompt + "\n\n## User-provided context\n\n" + body
    }

    /** Return just the matched fragment keys — used by the UI to show which fragments were applied. */
    def matchKeys(userText: String): java.util.List[String] = {
        matchFragments(userText).map(_.key).asJava
    }

    def invalidateCache(): Unit = {
        val table = DatrisEnvironment.current.tapPromptTableName
        if (table != null) cache.remove(table)
    }

    private def matchFragments(userText: String): List[TapPromptFragment] =
        matchFragments(userText, loadFragments())

    private[util] def matchFragments(userText: String, fragments: List[TapPromptFragment]): List[TapPromptFragment] = {
        if (userText == null || userText.isEmpty) return Nil
        if (fragments.isEmpty) return Nil
        val lowered = userText.toLowerCase
        fragments.filter { f =>
            // The data-sources registry is injected whole elsewhere, never keyword-matched.
            f.key == null || !f.key.equalsIgnoreCase(DataSourcesKey)
        }.filter { f =>
            val needles = (Option(f.key).toList ::: Option(f.aliases).map(_.asScala.toList).getOrElse(Nil))
                .filter(s => s != null && s.nonEmpty)
            needles.exists { needle =>
                val pattern = raw"\b" + java.util.regex.Pattern.quote(needle.toLowerCase) + raw"\b"
                java.util.regex.Pattern.compile(pattern).matcher(lowered).find()
            }
        }
    }

    private def loadFragments(): List[TapPromptFragment] = {
        val table = DatrisEnvironment.current.tapPromptTableName
        if (table == null) return Nil
        val now = System.currentTimeMillis()
        Option(cache.get(table)) match {
            case Some((frags, t)) if (now - t) < TTL_MS => frags
            case _ =>
                val frags =
                    try TapPromptFragmentIO.readAll(table).filter(_.enabled)
                    catch {
                        case e: Exception =>
                            logger.warn("TapPromptInjector: failed to load fragments: " + e.getMessage)
                            Nil
                    }
                cache.put(table, (frags, now))
                frags
        }
    }
}
