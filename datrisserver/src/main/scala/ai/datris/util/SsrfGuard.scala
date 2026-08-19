package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.apache.http.conn.DnsResolver
import org.apache.http.impl.conn.SystemDefaultDnsResolver
import org.slf4j.{Logger, LoggerFactory}

import java.net.{InetAddress, URI}

/** Server-Side Request Forgery guard for outbound HTTP the platform makes to
  * user-supplied URLs — REST-endpoint pre/post processors and HTTP taps.
  *
  * Those features legitimately call a user's OWN endpoint, but without a check
  * a user can point the URL at the cloud metadata service
  * (169.254.169.254 → IAM credentials) or an internal service
  * (vault:8200, minio:9000, localhost) and have the platform fetch it and hand
  * the response back — a full-read SSRF.
  *
  * This guard resolves the target host and rejects loopback, link-local
  * (includes the metadata IP), private/site-local, unique-local IPv6,
  * multicast, and wildcard addresses. Using it as the HTTP client's DnsResolver
  * (see HttpUtil) also means the SAME resolution is used to connect, which
  * closes the DNS-rebinding TOCTOU gap that a separate pre-flight check leaves.
  *
  * Operators who intentionally run their endpoints on a private network can set
  * `DATRIS_ALLOW_PRIVATE_EGRESS=true` to disable the block. Default is secure. */
object SsrfGuard {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    // Operators opt into internal egress with the DATRIS_ALLOW_PRIVATE_EGRESS
    // env var. The `datris.allowPrivateEgress` system property is an equivalent
    // override that can also be set at runtime (env vars are fixed at process
    // start) — used by tests that exercise the loopback HTTP path.
    private def allowPrivateEgress: Boolean =
        sys.props.get("datris.allowPrivateEgress").orElse(sys.env.get("DATRIS_ALLOW_PRIVATE_EGRESS"))
            .exists(_.equalsIgnoreCase("true"))

    /** True if `addr` is in a range the platform must not be tricked into
      * reaching on a user's behalf. */
    def isBlocked(addr: InetAddress): Boolean = {
        addr.isLoopbackAddress ||        // 127.0.0.0/8, ::1
            addr.isLinkLocalAddress ||   // 169.254.0.0/16 (incl. cloud metadata), fe80::/10
            addr.isSiteLocalAddress ||   // 10/8, 172.16/12, 192.168/16
            addr.isAnyLocalAddress ||    // 0.0.0.0, ::
            addr.isMulticastAddress ||
            isUniqueLocalIPv6(addr)      // fc00::/7
    }

    // java.net has no predicate for IPv6 ULA (fc00::/7); check the top 7 bits.
    private def isUniqueLocalIPv6(addr: InetAddress): Boolean = {
        val b = addr.getAddress
        b.length == 16 && (b(0) & 0xfe) == 0xfc
    }

    /** Resolve `url`'s host and throw DatrisException if it maps to any blocked
      * address. No-op when the operator has opted into private egress. Rejects
      * if ANY resolved address is blocked, so a hostname that returns both a
      * public and a private record can't be used to slip through. */
    def assertAllowed(url: String): Unit = {
        if (allowPrivateEgress) return
        val host =
            try new URI(url).getHost
            catch { case _: Exception => null }
        if (host == null || host.isEmpty)
            throw new DatrisException("Refusing request to a URL with no resolvable host: " + url)

        val addresses =
            try InetAddress.getAllByName(host)
            catch {
                case e: Exception =>
                    throw new DatrisException("Could not resolve host '" + host + "': " + e.getMessage)
            }
        addresses.find(isBlocked).foreach { bad =>
            logger.warn("Blocked SSRF attempt to " + url + " (resolved " + host + " -> " + bad.getHostAddress + ")")
            throw new DatrisException(
                "Refusing to connect to '" + host + "' — it resolves to a private, loopback, or " +
                    "link-local address (" + bad.getHostAddress + "), which is not allowed for user-supplied URLs. " +
                    "Set DATRIS_ALLOW_PRIVATE_EGRESS=true to permit internal endpoints."
            )
        }
    }

    /** An Apache HttpClient DnsResolver that resolves normally, then rejects the
      * whole result if any address is blocked. Wiring this into the client means
      * connection setup uses this exact resolution — no separate lookup a
      * rebinding attacker could race. */
    val filteringDnsResolver: DnsResolver = new DnsResolver {
        private val system = SystemDefaultDnsResolver.INSTANCE
        override def resolve(host: String): Array[InetAddress] = {
            val addresses = system.resolve(host)
            if (!allowPrivateEgress) {
                addresses.find(isBlocked).foreach { bad =>
                    logger.warn("Blocked SSRF connection to host " + host + " (-> " + bad.getHostAddress + ")")
                    throw new java.net.UnknownHostException(
                        "Host '" + host + "' resolves to a blocked address (" + bad.getHostAddress +
                            "); refusing to connect. Set DATRIS_ALLOW_PRIVATE_EGRESS=true to permit internal endpoints."
                    )
                }
            }
            addresses
        }
    }
}
