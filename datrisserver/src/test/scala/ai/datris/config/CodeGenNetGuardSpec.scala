package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.CodeGenNetGuard._
import org.scalatest.funsuite.AnyFunSuite

import java.net.InetAddress

/** The CodeGen runner may not call the Datris API: requests arriving on the
  * server's codegen-net interface, or from the runner's address, are refused.
  * Nothing else may be refused by mistake. */
class CodeGenNetGuardSpec extends AnyFunSuite {

    private def ip(s: String): InetAddress = InetAddress.getByName(s)

    // Compose layout: datris-net 172.18/16, tap-net 172.19/16, codegen-net 172.20/16.
    private val serverDatrisNet = Iface(ip("172.18.0.5"), 16)
    private val serverTapNet = Iface(ip("172.19.0.3"), 16)
    private val serverCodegenNet = Iface(ip("172.20.0.3"), 16)
    private val loopback = Iface(ip("127.0.0.1"), 8)
    private val composeServer = Seq(loopback, serverDatrisNet, serverTapNet, serverCodegenNet)
    private val runner = Seq(ip("172.20.0.2"))
    private val siblingsOnDatrisNet = Seq(ip("172.18.0.2"), ip("172.18.0.3"), ip("172.19.0.2"))

    private def composeDecision = decide("datris-codegen-runner", runner, composeServer, siblingsOnDatrisNet)

    test("compose layout: requests arriving on the codegen-net interface are refused, before any auth") {
        val d = composeDecision
        assert(d.warning.isEmpty, d.warning)
        assert(blocked("172.20.0.3", "172.20.0.2", d))
        // Another container someone attaches to codegen-net later: refused by the local address.
        assert(blocked("172.20.0.3", "172.20.0.9", d))
        assert(d.summary.contains("172.20.0.3/16"))
    }

    test("compose layout: browsers, the UI, the MCP server, the tap runner and loopback are not refused") {
        val d = composeDecision
        assert(!blocked("172.18.0.5", "172.18.0.7", d)) // ui / nginx on datris-net
        assert(!blocked("172.18.0.5", "172.18.0.8", d)) // mcp-server
        assert(!blocked("172.19.0.3", "172.19.0.2", d)) // tap callback over tap-net
        assert(!blocked("127.0.0.1", "127.0.0.1", d)) // in-container self call (policy replay)
        assert(!blocked("0:0:0:0:0:0:0:1", "0:0:0:0:0:0:0:1", d))
    }

    test("the runner's own address is refused whatever interface it arrives on") {
        val d = composeDecision
        assert(blocked("172.18.0.5", "172.20.0.2", d))
        assert(blocked("::ffff:172.20.0.3", "::ffff:10.0.0.1", d), "IPv4-mapped local address")
    }

    test("a runner name that does not resolve yet blocks nothing and says it will retry") {
        val d = decide("datris-codegen-runner", Seq.empty, composeServer, siblingsOnDatrisNet)
        assert(!d.active)
        assert(d.warning.exists(_.contains("retrying")))
        assert(!blocked("172.20.0.3", "172.20.0.2", d))
    }

    test("a runner on loopback or on this server disarms the guard (sbt, single host)") {
        val local = decide("localhost", Seq(ip("127.0.0.1"), ip("::1")), composeServer, Seq.empty)
        assert(!local.active && local.warning.exists(_.contains("disarmed")))
        assert(!blocked("127.0.0.1", "127.0.0.1", local))
        val self = decide("datris", Seq(ip("172.18.0.5")), composeServer, Seq.empty)
        assert(!self.active)
        assert(!blocked("172.18.0.5", "172.18.0.5", self))
    }

    test("a runner on the server's only network: only its own address is refused") {
        val d = decide("datris-codegen-runner", Seq(ip("10.0.0.9")), Seq(loopback, Iface(ip("10.0.0.4"), 24)), Seq.empty)
        assert(d.blockedLocal.isEmpty)
        assert(d.warning.exists(_.contains("only network")))
        assert(!blocked("10.0.0.4", "10.0.0.7", d))
        assert(blocked("10.0.0.4", "10.0.0.9", d))
    }

    test("a runner moved onto a network shared with other services: only its own address is refused") {
        val sharedRunner = Seq(ip("172.18.0.9"))
        val d = decide("datris-codegen-runner", sharedRunner, composeServer, siblingsOnDatrisNet)
        assert(d.blockedLocal.isEmpty)
        assert(d.warning.exists(_.contains("shared")))
        assert(!blocked("172.18.0.5", "172.18.0.2", d))
        assert(blocked("172.18.0.5", "172.18.0.9", d))
    }

    test("siblings are only resolved when the runner shares a subnet with the server") {
        var resolved = false
        def siblings: Seq[InetAddress] = { resolved = true; Seq.empty }
        decide("runner", Seq(ip("192.168.50.2")), composeServer, siblings)
        assert(!resolved)
        val d = decide("runner", Seq(ip("192.168.50.2")), composeServer, siblings)
        assert(d.blockedLocal.isEmpty && d.blockedRemote.nonEmpty)
    }

    test("addresses that are not IP literals are never resolved and never match") {
        val d = composeDecision
        assert(parseLiteral("datris-codegen-runner").isEmpty)
        assert(parseLiteral("abc").isEmpty, "hex-only token is a hostname, never resolved")
        assert(parseLiteral("cafe").isEmpty)
        assert(parseLiteral("1234").isEmpty)
        assert(parseLiteral("172.20.0.3").isDefined)
        assert(parseLiteral("::1").isDefined)
        assert(parseLiteral(null).isEmpty)
        assert(parseLiteral("fe80::1%eth0").isDefined)
        assert(!blocked(null, null, d))
        assert(!blocked("", "unknown", d))
    }

    test("subnet arithmetic") {
        assert(inSubnet(ip("172.20.255.254"), ip("172.20.0.3"), 16))
        assert(!inSubnet(ip("172.21.0.1"), ip("172.20.0.3"), 16))
        assert(inSubnet(ip("10.1.2.130"), ip("10.1.2.129"), 25))
        assert(!inSubnet(ip("10.1.2.127"), ip("10.1.2.129"), 25))
        assert(!inSubnet(ip("fd00::1"), ip("172.20.0.3"), 16), "families never match")
        assert(inSubnet(ip("fd00:0:0:1::2"), ip("fd00:0:0:1::3"), 64))
    }

    test("settings: on only with the runner in use and the block not switched off") {
        assert(settings(Map.empty).isLeft)
        assert(settings(Map("USE_CODEGEN_RUNNER" -> "true")) == Right("datris-codegen-runner"))
        assert(settings(Map("USE_CODEGEN_RUNNER" -> "true", "CODEGEN_RUNNER_URL" -> "http://10.0.0.9:8090")) == Right("10.0.0.9"))
        assert(settings(Map("USE_CODEGEN_RUNNER" -> "true", "CODEGEN_RUNNER_API_BLOCK" -> "false")).isLeft)
        assert(settings(Map("USE_CODEGEN_RUNNER" -> "false", "CODEGEN_RUNNER_API_BLOCK" -> "true")).isLeft)
    }
}
