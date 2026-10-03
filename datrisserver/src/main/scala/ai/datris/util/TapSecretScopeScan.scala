package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, TapConfig}
import org.slf4j.{Logger, LoggerFactory}

import scala.util.{Failure, Success, Try}

/** Which saved taps use a secret that is not tagged `_type=tap`
  * (plans/stories/field-protection-8-tap-secret-scope.md). Such a tap fails
  * closed on its next run unless DATRIS_TAP_SECRET_SCOPE=any; this scan names
  * them at startup and backs the `tap.secret_scope` doctor check. */
object TapSecretScopeScan {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** What a scan saw: (tap, secret, stored `_type`) for every tap whose
      * secret exists, and the secret names whose read failed (so "could not
      * check" is never reported as "nothing to report"). */
    case class Result(refs: List[(String, String, Option[String])], unreadable: List[String]) {
        def offenders: List[(String, String)] =
            refs.collect { case (tap, secret, storedType) if !storedType.contains(SecretNames.TapScopeTap) => (tap, secret) }
    }

    private def secretOf(t: TapConfig): Option[String] =
        Option(t.secretName).filter(_.trim.nonEmpty)

    /** (tap name, secret name) in tap order for taps whose secret exists and
      * is not tap-typed. `typeOf(secretName)`: outer None = the secret does
      * not exist (skipped), inner Option = its stored `_type`. */
    def offenders(taps: List[TapConfig], typeOf: String => Option[Option[String]]): List[(String, String)] =
        scan(taps, name => Success(typeOf(name))).offenders

    /** Resolve every tap's secret once per distinct name (taps often share a
      * secret). `typeOf`: Success(None) = absent (skipped), Failure = the read
      * failed (listed under `unreadable`, once per name). */
    def scan(taps: List[TapConfig], typeOf: String => Try[Option[Option[String]]]): Result = {
        val cache = scala.collection.mutable.LinkedHashMap[String, Try[Option[Option[String]]]]()
        def lookup(name: String): Try[Option[Option[String]]] =
            cache.getOrElseUpdate(name, Try(typeOf(name)).flatten)
        val refs = taps.flatMap { t =>
            secretOf(t).flatMap { secret =>
                lookup(secret) match {
                    case Success(Some(storedType)) => Some((t.name, secret, storedType))
                    case _ => None
                }
            }
        }
        val unreadable = cache.collect { case (name, Failure(_)) => name }.toList
        Result(refs, unreadable)
    }

    /** The stored `_type` of a secret in the current environment. A name
      * that cannot address a secret safely is treated as absent (the save
      * path already refuses it); a failed Vault read is a Failure. */
    def liveTypeOf(name: String): Try[Option[Option[String]]] = {
        val path = DatrisEnvironment.current.environment + "/" + name
        if (!SecretNames.isSafePath(path)) Success(None)
        else SecretsUtil.tryGetSecretMap(path).map(_.map(m => Option(m.get("_type"))))
    }

    /** Scan every saved tap. Listing the taps throws on failure (the doctor
      * turns that into an error row). */
    def liveScan(): Result =
        scan(TapConfigIO.readAll(DatrisEnvironment.current.tapTableName), liveTypeOf)

    /** One WARN line naming every offending tap, and one naming any secret
      * that could not be read. Never throws. */
    def logAtStartup(): Unit =
        try {
            val taps =
                try TapConfigIO.readAll(DatrisEnvironment.current.tapTableName)
                catch {
                    case scala.util.control.NonFatal(e) =>
                        logger.warn("Tap secret scope scan could not list taps (continuing): " + e.getMessage)
                        return
                }
            val result = scan(taps, liveTypeOf)
            val found = result.offenders
            if (found.nonEmpty) {
                val list = found.map { case (tap, secret) => tap + "→" + secret }.mkString(", ")
                val n = found.size + " tap(s) use a platform secret"
                if (SecretNames.tapScopeEnforced)
                    logger.warn(
                        n + " and will fail to run: " + list + ". Taps may only use tap secrets (Configuration → Secrets → Tap); " +
                            "create a tap secret with the fields each tap needs and select it, tag a hand-made tap secret with _type=tap, " +
                            "or set " + SecretNames.TapScopeEnv + "=any to allow platform secrets."
                    )
                else
                    logger.warn(n + " and are allowed by " + SecretNames.TapScopeEnv + "=any: " + list + ". These taps can read platform secrets.")
            }
            if (result.unreadable.nonEmpty)
                logger.warn(
                    "Tap secret scope scan could not read " + result.unreadable.size + " secret(s): " + result.unreadable.mkString(", ") +
                        "; the taps using them were not checked"
                )
        } catch {
            case scala.util.control.NonFatal(e) => logger.warn("Tap secret scope scan failed (continuing): " + e.getMessage)
        }
}
