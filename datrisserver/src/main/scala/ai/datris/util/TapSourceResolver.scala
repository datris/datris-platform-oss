package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.TapConfig
import org.slf4j.LoggerFactory

/** The source identity a tap represents in provenance and lineage — "where the
  * data actually comes from" (an external provider host), never credentials.
  *
  * Precedence, all deterministic:
  *   1. `TapConfig.source` declared by a human or agent (e.g. "SEC EDGAR").
  *   2. HTTP taps: the endpoint host.
  *   3. Script taps: the host the script references most, normalized so
  *      `www.sec.gov` and `data.sec.gov` both read `sec.gov`. Memoized per
  *      tap version because reading a script can be a MinIO/GitHub round trip.
  *   4. `tap:<name>` when nothing better is known.
  */
object TapSourceResolver {

    private val logger = LoggerFactory.getLogger(getClass)

    private val HostPattern = """https?://([A-Za-z0-9.-]+\.[A-Za-z]{2,})(?=[/:"'\s?)]|$)""".r
    /** Leading labels that name an API surface, not the provider. */
    private val GenericLabels = Set("www", "api", "apis", "data", "feeds", "feed", "app", "rest", "ws", "cdn")
    /** Hosts that are infrastructure or placeholders, never a data source. */
    private val Ignored = Set("localhost", "host.docker.internal", "example.com", "datris.ai", "github.com", "pypi.org", "schema.org", "w3.org")

    private case class Memo(key: String, source: Option[String])
    private val memo = new java.util.concurrent.ConcurrentHashMap[String, Memo]()

    def resolve(tap: TapConfig): String = {
        if (tap == null) return null
        declared(tap)
            .orElse(httpHost(tap))
            .orElse(scriptHost(tap))
            .getOrElse("tap:" + tap.name)
    }

    private def declared(tap: TapConfig): Option[String] =
        Option(tap.source).map(_.trim).filter(_.nonEmpty)

    private def httpHost(tap: TapConfig): Option[String] =
        if (tap.isHttp && tap.endpointUrl != null)
            try Option(java.net.URI.create(tap.endpointUrl).getHost).filter(_.nonEmpty)
            catch { case _: Exception => None }
        else None

    private def scriptHost(tap: TapConfig): Option[String] = {
        if (tap.isHttp) return None
        val key = List(tap.updatedAt, tap.scriptPath, tap.scriptCommitSha, tap.scriptRepoPath).map(v => Option(v).getOrElse("")).mkString("|")
        val cached = memo.get(tap.name)
        if (cached != null && cached.key == key) return cached.source
        val derived =
            try TapCodeStore.forTap(tap).readScript(tap).flatMap(deriveFromScript)
            catch {
                case e: Exception =>
                    logger.debug("source derivation skipped for tap " + tap.name + ": " + e.getMessage)
                    None
            }
        memo.put(tap.name, Memo(key, derived))
        derived
    }

    /** Most-referenced normalized host in a script; ties go to first mention.
      * Pure — the unit-testable core. */
    private[datris] def deriveFromScript(script: String): Option[String] = {
        if (script == null || script.isEmpty) return None
        val hosts = HostPattern.findAllMatchIn(script).map(m => normalize(m.group(1).toLowerCase)).filter(keep).toList
        if (hosts.isEmpty) return None
        val counts = hosts.groupBy(identity).mapValues(_.size)
        val firstIndex = hosts.zipWithIndex.groupBy(_._1).mapValues(_.head._2)
        Some(hosts.distinct.maxBy(h => (counts(h), -firstIndex(h))))
    }

    /** Drop leading generic labels while at least a two-label host remains. */
    private[datris] def normalize(host: String): String = {
        var labels = host.split('.').toList
        while (labels.length > 2 && GenericLabels.contains(labels.head)) labels = labels.tail
        labels.mkString(".")
    }

    private def keep(host: String): Boolean =
        !Ignored.contains(host) && !Ignored.exists(i => host.endsWith("." + i)) && !host.contains("{") && !host.startsWith("$")

    /** Drop the memo (tests, or after a bulk script import). */
    private[datris] def invalidate(): Unit = memo.clear()
}
