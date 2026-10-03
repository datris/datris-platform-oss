package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, TapConfig}
import org.slf4j.{Logger, LoggerFactory}

/** Which saved taps use a secret that is not tagged `_type=tap`
  * (plans/stories/field-protection-8-tap-secret-scope.md). Such a tap fails
  * closed on its next run unless DATRIS_TAP_SECRET_SCOPE=any; this scan names
  * them at startup so operators find out before a run fails. */
object TapSecretScopeScan {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** (tap name, secret name) in tap order for taps whose secret exists and
      * is not tap-typed. `typeOf(secretName)`: outer None = the secret does
      * not exist (skipped), inner Option = its stored `_type`. */
    def offenders(taps: List[TapConfig], typeOf: String => Option[Option[String]]): List[(String, String)] =
        taps.flatMap { t =>
            val secret = t.secretName
            if (secret == null || secret.trim.isEmpty) None
            else
                typeOf(secret) match {
                    case Some(storedType) if !storedType.contains(SecretNames.TapScopeTap) => Some((t.name, secret))
                    case _ => None
                }
        }

    /** The stored `_type` of a secret in the current environment; None when
      * it does not exist or cannot be read safely. */
    def liveTypeOf(name: String): Option[Option[String]] = {
        val path = DatrisEnvironment.current.environment + "/" + name
        if (!SecretNames.isSafePath(path)) None
        else
            try SecretsUtil.getSecretMap(path).map(m => Option(m.get("_type")))
            catch {
                case e: Exception =>
                    logger.debug("tap secret scope: could not read secret '" + name + "': " + e.getMessage)
                    None
            }
    }

    /** (tap, secret, stored `_type`) for every saved tap whose secret exists. */
    def liveRefs(): List[(String, String, Option[String])] = {
        val taps = TapConfigIO.readAll(DatrisEnvironment.current.tapTableName)
        taps.flatMap { t =>
            val secret = t.secretName
            if (secret == null || secret.trim.isEmpty) None
            else liveTypeOf(secret).map(storedType => (t.name, secret, storedType))
        }
    }

    /** One WARN line naming every offending tap. Never throws. */
    def logAtStartup(): Unit =
        try {
            val taps = TapConfigIO.readAll(DatrisEnvironment.current.tapTableName)
            val found = offenders(taps, liveTypeOf)
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
        } catch {
            case scala.util.control.NonFatal(e) => logger.warn("Tap secret scope scan failed (continuing): " + e.getMessage)
        }
}
