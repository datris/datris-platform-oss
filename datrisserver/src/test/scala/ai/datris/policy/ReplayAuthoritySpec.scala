package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.ReplayScopeFixtures._
import ai.datris.model.{DatrisEnvironment, TenantContext}
import ai.datris.util.InMemoryUserDb
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

/** Story: an approved agent action runs with the requester's access, and
  * internal headers count only from the platform's own callers
  * (plans/stories/approval-replay-requester-scope.md), Acceptance bullet 1
  * (`ReplayAuthoritySpec`) and Steps 1.
  *
  * The new pure check, assumed as
  * {{{
  * object ReplayAuthority {
  *   sealed trait Answer
  *   final case class Replay(pa: PendingAction) extends Answer
  *   case object NotReplay extends Answer
  *   case object Unchecked extends Answer
  *   def verify(request: HttpServletRequest, store: PolicyInterceptor.Store): Answer
  * }
  * }}}
  * reads the approval header (`PolicyReplay.HeaderApproval`, `<id>.<token>`)
  * from the request, looks the action up in `store` and answers `Replay(pa)`
  * only when `PolicyInterceptor.replayable(pa, token)` and
  * `PolicyInterceptor.replayMatches(request, pa)` hold and the agent policy
  * is on. It never consumes the token. */
class ReplayAuthoritySpec extends AnyFunSuite with BeforeAndAfterEach {

    private var saved: DatrisEnvironment = _
    private var store: MemoryStore = _

    private val Token = "tok-one-time-0001"
    private val Body = "{\"name\":\"t1\"}"

    override def beforeEach(): Unit = {
        saved = DatrisEnvironment.values
        DatrisEnvironment.values = InMemoryUserDb.env().copy(useApiKeys = true, useAgentPolicy = true)
        TenantContext.clear()
        store = new MemoryStore
        store.put(approved(
            "pa_ok",
            keyActor(opsAgent),
            Token,
            method = "POST",
            path = "/api/v1/tap/run",
            body = Some(Body),
            resourceName = Some("t1"),
            resourceType = "tap",
            action = "tap:run"
        ))
    }

    override def afterEach(): Unit = {
        TenantContext.clear()
        DatrisEnvironment.values = saved
    }

    private def ask(header: String, method: String = "POST", uri: String = "/api/v1/tap/run", body: Option[String] = Some(Body)): ReplayAuthority.Answer = {
        val (r, _) = call(method, uri, body = body, headers = Option(header).map(h => PolicyReplay.HeaderApproval -> h).toSeq)
        ReplayAuthority.verify(r, store)
    }

    private def notReplay(a: ReplayAuthority.Answer, what: String): Unit =
        assert(a == ReplayAuthority.NotReplay, what + ": " + a)

    test("the exact approved request with its unused token verifies, and nothing is consumed") {
        val before = store.docs("pa_ok")
        ask("pa_ok." + Token) match {
            case ReplayAuthority.Replay(pa) => assert(pa.id == "pa_ok" && pa.actor == keyActor(opsAgent))
            case other => fail("expected a verified replay, got " + other)
        }
        assert(store.consumed.isEmpty, "verify must not consume the token")
        assert(store.docs("pa_ok") == before, "the stored action is unchanged")
        // still verifies a second time: PolicyInterceptor is the one that consumes it
        assert(ask("pa_ok." + Token).isInstanceOf[ReplayAuthority.Replay])
    }

    test("an unknown approval id does not verify") {
        notReplay(ask("pa_nope." + Token), "unknown id")
    }

    test("a wrong, empty or missing token does not verify") {
        notReplay(ask("pa_ok.wrong-token"), "wrong token")
        notReplay(ask("pa_ok."), "empty token")
        notReplay(ask("pa_ok"), "no token")
        notReplay(ask(""), "blank header")
        notReplay(ask(null), "no header")
    }

    test("a token that was already used does not verify") {
        store.put(store.docs("pa_ok").copy(replayConsumedAt = Some(java.time.Instant.now()), replayTokenHash = None))
        notReplay(ask("pa_ok." + Token), "consumed")
    }

    test("an action that is pending, rejected, executed or failed does not verify") {
        for (state <- Seq(PendingAction.Pending, PendingAction.Rejected, PendingAction.Executed, PendingAction.Failed, PendingAction.Expired)) {
            store.put(store.docs("pa_ok").copy(state = state))
            notReplay(ask("pa_ok." + Token), state)
        }
    }

    test("the token on a different path, method or resource name does not verify") {
        notReplay(ask("pa_ok." + Token, uri = "/api/v1/tap"), "other path")
        notReplay(ask("pa_ok." + Token, method = "DELETE"), "other method")
        notReplay(ask("pa_ok." + Token, body = Some("{\"name\":\"t2\"}")), "other resource name")
        notReplay(ask("pa_ok." + Token, body = None), "no resource name")
    }

    test("with the agent policy off nothing verifies") {
        DatrisEnvironment.values = DatrisEnvironment.values.copy(useAgentPolicy = false)
        notReplay(ask("pa_ok." + Token), "policy off")
    }

    // Re-pinned (lead ruling: nothing runs when the approval cannot be
    // checked): a failed read is its own answer, never NotReplay, so the
    // request is refused instead of running with an ordinary identity.
    test("a store read that fails answers Unchecked (neither a replay nor not one) and does not throw") {
        store.failReads = true
        assert(ask("pa_ok." + Token) == ReplayAuthority.Unchecked)
        assert(store.consumed.isEmpty)
    }

    test("an action stored before the upgrade (no hash version) verifies on its exact request") {
        store.put(store.docs("pa_ok").copy(id = "pa_old", hashVersion = None))
        assert(ask("pa_old." + Token).isInstanceOf[ReplayAuthority.Replay])
    }
}
