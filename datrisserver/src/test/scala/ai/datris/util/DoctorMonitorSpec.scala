package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.DoctorService.{CheckResult, Report}
import com.google.gson.JsonObject
import org.scalatest.funsuite.AnyFunSuite

/** The periodic doctor: when it runs, what counts as a flip, and what the
  * webhook receives. */
class DoctorMonitorSpec extends AnyFunSuite {

    private def r(id: String, status: String, detail: String = "d", remediation: String = "") =
        CheckResult(id, status, status, detail, remediation)

    private def report(checks: CheckResult*) = Report("full", Map("server" -> "1.30.0"), checks)

    test("diff: ok→error posts doctor_error; error→ok posts doctor_recovered; warn moves and skips are silent") {
        val prev = Map("a" -> "ok", "b" -> "error", "c" -> "warn", "d" -> "error", "e" -> "ok")
        val now = Seq(r("a", "error"), r("b", "ok"), r("c", "ok"), r("d", "skip"), r("e", "warn"), r("f", "error"))
        val t = DoctorMonitor.diff(prev, now)
        assert(t.map(x => (x.event, x.check.id)) == Seq(("doctor_error", "a"), ("doctor_recovered", "b"), ("doctor_error", "f")))
    }

    test("diff: an error that stays an error is not re-posted") {
        assert(DoctorMonitor.diff(Map("a" -> "error"), Seq(r("a", "error"))).isEmpty)
    }

    test("tick: off when interval is 0, not due before the interval, due after it") {
        var runs = 0
        val run = () => { runs += 1; report(r("a", "ok")) }
        assert(DoctorMonitor.tick(0L, 0, None, Map.empty, run, _ => ()).isEmpty)
        assert(runs == 0)
        assert(DoctorMonitor.tick(1000L, 15, None, Map.empty, run, _ => ()).nonEmpty) // first run is always due
        assert(DoctorMonitor.tick(14 * 60000L, 15, Some(0L), Map.empty, run, _ => ()).isEmpty)
        assert(DoctorMonitor.tick(15 * 60000L, 15, Some(0L), Map.empty, run, _ => ()).nonEmpty)
        assert(runs == 2)
    }

    test("tick: posts one payload per transition with the remediation, and carries statuses forward (skips keep prior)") {
        val posted = scala.collection.mutable.ArrayBuffer[JsonObject]()
        val run = () => report(r("vault.token_ttl", "error", "expires in 3d", "recreate vault"), r("disk.usage", "skip"), r("env.seen", "ok"))
        val Some((transitions, next)) = DoctorMonitor.tick(60000L, 1, None, Map("disk.usage" -> "warn"), run, posted += _)
        assert(transitions.size == 1)
        assert(posted.size == 1)
        val p = posted.head
        assert(p.get("event").getAsString == "doctor_error")
        assert(p.get("check").getAsString == "vault.token_ttl")
        assert(p.get("detail").getAsString == "expires in 3d")
        assert(p.get("remediation").getAsString == "recreate vault")
        assert(p.get("ts").getAsString == "1970-01-01T00:01:00Z")
        assert(next == Map("vault.token_ttl" -> "error", "disk.usage" -> "warn", "env.seen" -> "ok"))
    }

    test("tick: a webhook that throws does not stop the run or the status update") {
        val run = () => report(r("a", "error"))
        val Some((t, next)) = DoctorMonitor.tick(1L, 1, None, Map.empty, run, _ => throw new RuntimeException("boom"))
        assert(t.size == 1 && next("a") == "error")
    }
}
