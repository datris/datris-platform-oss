package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.slf4j.{Logger, LoggerFactory}

/** Postgres TLS enforcement (security plan Phase 5).
  *
  * Opt-in: when `DATRIS_ENV=production` is set, a JDBC URL pointing at an
  * external Postgres host must carry `sslmode=require` (or stricter) or the
  * load fails with a clear error. `DATRIS_ALLOW_PLAINTEXT_DB=true` is the
  * documented opt-out (start + logged warning) — same shape as
  * `DATRIS_ALLOW_PRIVATE_EGRESS` on [[SsrfGuard]].
  *
  * The bundled in-network services (`postgres`, `pgvector`) and local hosts are
  * exempt: the stock containers run without server certs (`ssl=off`) and cannot
  * negotiate TLS, and SECURITY.md declares the compose host network the trust
  * boundary — enforcement targets *external* databases, where traffic crosses a
  * real network. Without the flag nothing changes for any existing install;
  * an external-looking plaintext URL still gets a startup WARNING for
  * visibility.
  */
object PostgresTlsGuard {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    // JDBC hosts that never require TLS: the bundled compose services and the
    // local machine (sbt/IDE dev against published ports).
    private val InternalHosts = Set("postgres", "pgvector", "localhost", "127.0.0.1", "::1", "host.docker.internal")

    private val SecureSslModes = Set("require", "verify-ca", "verify-full")

    // Warn once per distinct URL — secrets are re-read on every pipeline run and
    // repeating the same warning each time is noise.
    private val warned = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

    // The system-property twins exist because env vars are fixed at process
    // start — tests exercise both gates through the properties.
    private def productionMode: Boolean =
        sys.props.get("datris.env").orElse(sys.env.get("DATRIS_ENV"))
            .exists(_.equalsIgnoreCase("production"))

    private def allowPlaintextDb: Boolean =
        sys.props.get("datris.allowPlaintextDb").orElse(sys.env.get("DATRIS_ALLOW_PLAINTEXT_DB"))
            .exists(_.equalsIgnoreCase("true"))

    /** Host portion of a `jdbc:postgresql://host[:port]/db[?params]` URL, or
      * None when the URL doesn't parse (validation then stays out of the way —
      * the driver will produce its own error). */
    private[util] def jdbcHost(jdbcUrl: String): Option[String] = {
        if (jdbcUrl == null) return None
        // java.net.URI can't parse the jdbc: prefix; strip it first.
        val stripped = jdbcUrl.trim.stripPrefix("jdbc:")
        try {
            Option(new java.net.URI(stripped).getHost).map(_.toLowerCase)
        } catch {
            case _: Exception => None
        }
    }

    private[util] def isInternalHost(host: String): Boolean =
        InternalHosts.contains(host.toLowerCase)

    private[util] def hasSecureSslMode(jdbcUrl: String): Boolean = {
        val query = jdbcUrl.split("\\?", 2) match {
            case Array(_, q) => q
            case _ => return false
        }
        query.split("&").exists { param =>
            param.split("=", 2) match {
                case Array(k, v) => k.equalsIgnoreCase("sslmode") && SecureSslModes.contains(v.trim.toLowerCase)
                case _ => false
            }
        }
    }

    /** Validate a Postgres JDBC URL at load time. `label` names the secret in
      * messages (e.g. "platform Postgres", "pgvector"). Throws only when
      * `DATRIS_ENV=production` is set, the host is external, the URL has no
      * secure sslmode, and the operator has not set
      * `DATRIS_ALLOW_PLAINTEXT_DB=true`. */
    def validate(jdbcUrl: String, label: String): Unit = {
        val host = jdbcHost(jdbcUrl) match {
            case Some(h) => h
            case None => return // unparseable — let the driver report it
        }
        if (isInternalHost(host) || hasSecureSslMode(jdbcUrl)) return

        if (productionMode) {
            if (allowPlaintextDb) {
                warnOnce(
                    jdbcUrl,
                    s"DATRIS_ALLOW_PLAINTEXT_DB=true: $label connection to '$host' is plaintext in production mode. " +
                        "You have accepted this risk; add sslmode=require to the JDBC URL to enforce TLS."
                )
            } else {
                throw new DatrisException(
                    s"DATRIS_ENV=production requires TLS for the $label connection to external host '$host', " +
                        "but the JDBC URL has no sslmode=require (or stricter). Add sslmode=require to the jdbcUrl " +
                        "in the secret, or set DATRIS_ALLOW_PLAINTEXT_DB=true to accept plaintext and start anyway."
                )
            }
        } else {
            warnOnce(
                jdbcUrl,
                s"$label connection to external host '$host' has no sslmode in the JDBC URL — traffic is plaintext. " +
                    "Add sslmode=require to the jdbcUrl, and set DATRIS_ENV=production to enforce TLS at startup."
            )
        }
    }

    private def warnOnce(jdbcUrl: String, message: String): Unit =
        if (warned.add(jdbcUrl)) logger.warn(message)
}
