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

    private def matchFragments(userText: String): List[TapPromptFragment] = {
        if (userText == null || userText.isEmpty) return Nil
        val fragments = loadFragments()
        if (fragments.isEmpty) return Nil
        val lowered = userText.toLowerCase
        fragments.filter { f =>
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
                val frags = try TapPromptFragmentIO.readAll(table).filter(_.enabled)
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
