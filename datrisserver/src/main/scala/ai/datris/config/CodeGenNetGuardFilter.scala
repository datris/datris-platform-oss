package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.CodeGenRunner
import jakarta.servlet.FilterChain
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

import java.net.{InetAddress, NetworkInterface, URI}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.collection.JavaConverters._
import scala.util.Try

/** Decision logic of [[CodeGenNetGuardFilter]], kept pure so it can be tested
  * with fake addresses.
  *
  * The server pushes generated scripts to `datris-codegen-runner`; the runner
  * never needs to call the server. Two rules refuse a request:
  *   - local address: it arrived on the server's own interface on the
  *     runner's network (`codegen-net`). Only the runner and the server live
  *     there, so nothing legitimate arrives that way.
  *   - remote address: it came from an address the runner's hostname
  *     resolves to.
  *
  * Neither rule may block legitimate traffic by mistake, so the local-address
  * rule is only armed when the runner's network is clearly dedicated: the
  * server has another interface, and none of the other compose services
  * resolves into that subnet. A runner that resolves to loopback or to the
  * server itself (sbt, a single host) disarms both rules. */
object CodeGenNetGuard {

    type Key = Seq[Byte]

    case class Iface(addr: InetAddress, prefix: Int)

    /** `blockedLocal`: the server's own addresses on the runner's network.
      * `blockedRemote`: the runner's addresses. `summary` is the boot log line,
      * `warning` set when a rule was disarmed. */
    case class Decision(blockedLocal: Set[Key], blockedRemote: Set[Key], summary: String, warning: Option[String]) {
        def active: Boolean = blockedLocal.nonEmpty || blockedRemote.nonEmpty
    }

    val Inactive: Decision = Decision(Set.empty, Set.empty, "inactive", None)

    /** Compose services that talk to the server and are never on codegen-net.
      * If one of them resolves into the runner's subnet, that subnet is shared. */
    val SiblingHosts: Seq[String] = Seq("ui", "mcp-server", "datris-tap-runner", "vault", "mongodb", "postgres", "minio")

    val RefreshMillis: Long = 30000L

    /** While the runner's name does not resolve (it starts after the server),
      * retry sooner, so a runner that comes up is covered within seconds. */
    val UnresolvedRetryMillis: Long = 2000L

    val ResponseBody: String =
        "{\"error\":\"Requests from the CodeGen runner network (codegen-net) are not accepted by the Datris API\"}"

    def key(a: InetAddress): Key = a.getAddress.toSeq

    def inSubnet(a: InetAddress, net: InetAddress, prefix: Int): Boolean = {
        val x = a.getAddress
        val y = net.getAddress
        if (x.length != y.length || prefix < 0 || prefix > x.length * 8) return false
        val full = prefix / 8
        var i = 0
        while (i < full) {
            if (x(i) != y(i)) return false
            i += 1
        }
        val rest = prefix % 8
        if (rest == 0) true
        else {
            val mask = (0xff << (8 - rest)) & 0xff
            (x(full) & mask) == (y(full) & mask)
        }
    }

    private def isSelf(a: InetAddress, own: Seq[Iface]): Boolean =
        a.isLoopbackAddress || a.isAnyLocalAddress || own.exists(i => key(i.addr) == key(a))

    /** What to block, given the runner's resolved addresses, the server's own
      * non-loopback interface addresses, and the resolved addresses of the
      * sibling services (only consulted when the runner shares a subnet with
      * the server). */
    def decide(runnerHost: String, runnerAddrs: Seq[InetAddress], own: Seq[Iface], siblings: => Seq[InetAddress]): Decision = {
        if (runnerAddrs.isEmpty)
            return Decision(
                Set.empty,
                Set.empty,
                "cannot resolve " + runnerHost,
                Some(
                    "CodeGen runner API block: cannot resolve " + runnerHost + " (not started yet?); not blocking yet, retrying every " +
                        (UnresolvedRetryMillis / 1000) + " s"
                )
            )
        val usable = runnerAddrs.filterNot(isSelf(_, own))
        if (usable.isEmpty)
            return Decision(
                Set.empty,
                Set.empty,
                "disarmed",
                Some(
                    "CodeGen runner API block disarmed: " + runnerHost + " resolves to this server or loopback (" +
                        runnerAddrs.map(_.getHostAddress).mkString(", ") + "), so blocking it would block local clients"
                )
            )
        val remote = usable.map(key).toSet
        val remoteText = usable.map(_.getHostAddress).mkString(", ")
        val ownReal = own.filterNot(i => i.addr.isLoopbackAddress || i.addr.isLinkLocalAddress)
        val matches = ownReal.filter(i => usable.exists(inSubnet(_, i.addr, i.prefix)))
        if (matches.isEmpty)
            return Decision(
                Set.empty,
                remote,
                "blocking requests from " + runnerHost + " (" + remoteText + "); the runner is not on a network this server is attached to",
                None
            )
        val matchText = matches.map(i => i.addr.getHostAddress + "/" + i.prefix).mkString(", ")
        if (matches.map(i => key(i.addr)).toSet == ownReal.map(i => key(i.addr)).toSet)
            return Decision(
                Set.empty,
                remote,
                "blocking requests from " + runnerHost + " (" + remoteText + ")",
                Some(
                    "CodeGen runner API block: " + matchText + " is this server's only network, so requests arriving on it are " +
                        "not refused (that would block every client); only requests from " + runnerHost + " itself are"
                )
            )
        val shared = siblings.filter(s => matches.exists(i => inSubnet(s, i.addr, i.prefix)))
        if (shared.nonEmpty)
            return Decision(
                Set.empty,
                remote,
                "blocking requests from " + runnerHost + " (" + remoteText + ")",
                Some(
                    "CodeGen runner API block: the runner's network (" + matchText + ") is shared with other services (" +
                        shared.map(_.getHostAddress).mkString(", ") + "), so requests arriving on it are not refused; " +
                        "only requests from " + runnerHost + " itself are. Give datris-codegen-runner its own network (codegen-net)"
                )
            )
        Decision(
            matches.map(i => key(i.addr)).toSet,
            remote,
            "blocking every request that arrives on " + matchText + " (the runner's network) and every request from " +
                runnerHost + " (" + remoteText + ")",
            None
        )
    }

    /** An IP literal as Tomcat reports it (IPv6 may carry a %scope), or None.
      * Never resolves a name. */
    def parseLiteral(s: String): Option[InetAddress] = {
        if (s == null) return None
        val bare = s.trim.takeWhile(_ != '%')
        if (bare.isEmpty || !bare.forall(c => Character.digit(c, 16) >= 0 || c == '.' || c == ':')) return None
        Try(InetAddress.getByName(bare)).toOption
    }

    /** The filter's decision for one request. */
    def blocked(localAddr: String, remoteAddr: String, d: Decision): Boolean =
        (d.blockedLocal.nonEmpty && parseLiteral(localAddr).exists(a => d.blockedLocal.contains(key(a)))) ||
            (d.blockedRemote.nonEmpty && parseLiteral(remoteAddr).exists(a => d.blockedRemote.contains(key(a))))

    /** On when the runner is in use and `CODEGEN_RUNNER_API_BLOCK` is not
      * `false`. Returns the runner host, or why the guard is off. */
    def settings(env: Map[String, String]): Either[String, String] = {
        val useRunner = env.getOrElse("USE_CODEGEN_RUNNER", "false").trim.equalsIgnoreCase("true")
        val block = !env.getOrElse("CODEGEN_RUNNER_API_BLOCK", "true").trim.equalsIgnoreCase("false")
        if (!useRunner) Left("USE_CODEGEN_RUNNER is not true")
        else if (!block) Left("CODEGEN_RUNNER_API_BLOCK=false")
        else {
            val url = env.get("CODEGEN_RUNNER_URL").map(_.trim).filter(_.nonEmpty).getOrElse(CodeGenRunner.DefaultUrl)
            Try(new URI(url).getHost).toOption.filter(h => h != null && h.nonEmpty) match {
                case Some(h) => Right(h.stripPrefix("[").stripSuffix("]"))
                case None => Left("CODEGEN_RUNNER_URL has no host: " + url)
            }
        }
    }

    def ownInterfaces(): Seq[Iface] =
        Try {
            NetworkInterface.getNetworkInterfaces.asScala.toSeq
                .filter(n => Try(n.isUp).getOrElse(false))
                .flatMap(_.getInterfaceAddresses.asScala.toSeq)
                .filter(_.getAddress != null)
                .map(ia => Iface(ia.getAddress, ia.getNetworkPrefixLength.toInt))
        }.getOrElse(Seq.empty)

    def resolve(host: String): Seq[InetAddress] = Try(InetAddress.getAllByName(host).toSeq).getOrElse(Seq.empty)
}

/** Refuses, with 403, every request that comes from the CodeGen runner
  * (see [[CodeGenNetGuard]]). Registered before every other filter and
  * interceptor, so it applies to every path (API, actuator, MinIO events)
  * before any authentication. The decision is logged at boot and whenever it
  * changes; the runner's hostname is re-resolved every 30 s (every 2 s while
  * it does not resolve), so a runner that starts after the server, or
  * restarts with a new address, is covered. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CodeGenNetGuardFilter extends OncePerRequestFilter {
    import CodeGenNetGuard._

    private val log: Logger = LoggerFactory.getLogger(classOf[CodeGenNetGuardFilter])

    private val hostOrReason: Either[String, String] = CodeGenNetGuard.settings(sys.env)
    @volatile private var decision: Decision = Inactive
    @volatile private var lastRunnerKeys: Set[Key] = Set.empty
    @volatile private var nextRefresh: Long = 0L
    private val refreshing = new AtomicBoolean(false)
    private val lastBlockLog = new AtomicLong(0L)
    private val blockedSinceLog = new AtomicLong(0L)

    hostOrReason match {
        case Left(why) => log.info("CodeGen runner API block off: " + why)
        case Right(_) => refresh(force = true)
    }

    private def refresh(force: Boolean): Unit = hostOrReason.foreach { host =>
        if (refreshing.compareAndSet(false, true)) refreshAs(host, force)
    }

    private def refreshAs(host: String, force: Boolean): Unit = {
        try {
            val addrs = resolve(host)
            val keys = addrs.map(key).toSet
            // Keep the last good decision while the name does not resolve (runner restarting).
            if (addrs.isEmpty && decision.active) return
            if (!force && keys == lastRunnerKeys) return
            lastRunnerKeys = keys
            val d = decide(host, addrs, ownInterfaces(), SiblingHosts.flatMap(resolve))
            if (d != decision || force) {
                log.info("CodeGen runner API block: " + d.summary)
                d.warning.foreach(w => log.warn(w))
            }
            decision = d
        } catch {
            case e: Exception => log.warn("CodeGen runner API block: refresh failed, keeping the previous decision: " + e)
        } finally {
            nextRefresh = System.currentTimeMillis() + (if (decision.active) RefreshMillis else UnresolvedRetryMillis)
            refreshing.set(false)
        }
    }

    override def shouldNotFilter(request: HttpServletRequest): Boolean = hostOrReason.isLeft

    override def doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain): Unit = {
        if (System.currentTimeMillis() >= nextRefresh) refresh(force = false)
        if (blocked(request.getLocalAddr, request.getRemoteAddr, decision)) {
            logBlocked(request)
            response.setStatus(HttpServletResponse.SC_FORBIDDEN)
            response.setContentType("application/json")
            response.setCharacterEncoding(StandardCharsets.UTF_8.name())
            response.getWriter.write(ResponseBody)
        } else chain.doFilter(request, response)
    }

    /** At most one line a minute, so a script hammering the port cannot flood the log. */
    private def logBlocked(request: HttpServletRequest): Unit = {
        val n = blockedSinceLog.incrementAndGet()
        val now = System.currentTimeMillis()
        val last = lastBlockLog.get()
        if (now - last >= 60000L && lastBlockLog.compareAndSet(last, now)) {
            blockedSinceLog.set(0L)
            log.warn(
                "CodeGen runner API block: refused " + request.getMethod + " " + request.getRequestURI + " from " +
                    request.getRemoteAddr + " (" + n + " request(s) refused since the last line)"
            )
        }
    }
}
