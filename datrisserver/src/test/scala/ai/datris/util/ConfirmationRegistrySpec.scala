package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonObject, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

/** Story: Configuration chat, server side (plans/stories/config-chat-server-seam.md), Step 3.
  *
  * A mutating Configuration-chat tool runs only when the call carries a one-shot
  * token bound to (scope, tool, input hash). The registry is plain in-memory
  * state with an injectable clock so expiry is testable without sleeping:
  *
  * {{{
  * class ConfirmationRegistry(clock: () => Long = () => System.currentTimeMillis()) {
  *     def issue(scope: String, tool: String, input: JsonObject): String
  *     def consume(scope: String, tool: String, input: JsonObject, token: String): Either[String, Unit]
  * }
  * }}}
  */
class ConfirmationRegistrySpec extends AnyFunSuite {

    private val Unknown = "unknown or expired confirmation token"
    private val Mismatch = "confirmation token does not match this request"

    private def obj(json: String): JsonObject = JsonParser.parseString(json).getAsJsonObject

    private class FakeClock(var now: Long = 1000000000000L) {
        def apply(): Long = now
        def advanceMinutes(m: Int): Unit = now += m * 60L * 1000L
    }

    private def fresh(): (ConfirmationRegistry, FakeClock) = {
        val clock = new FakeClock()
        (new ConfirmationRegistry(clock = () => clock()), clock)
    }

    test("the exact proposed call succeeds once") {
        val (reg, _) = fresh()
        val input = obj("""{"username":"bob"}""")
        val token = reg.issue("s1", "delete_user", input)
        assert(token != null && token.nonEmpty)
        assert(reg.consume("s1", "delete_user", obj("""{"username":"bob"}"""), token) == Right(()))
    }

    test("the confirmation_token key is not part of the bound input") {
        val (reg, _) = fresh()
        val token = reg.issue("s1", "delete_user", obj("""{"username":"bob"}"""))
        val confirmed = obj("""{"username":"bob"}""")
        confirmed.addProperty("confirmation_token", token)
        assert(reg.consume("s1", "delete_user", confirmed, token) == Right(()))
    }

    test("altered input is a mismatch, and the token is spent by the mismatch") {
        val (reg, _) = fresh()
        val token = reg.issue("s1", "delete_user", obj("""{"username":"bob"}"""))
        assert(reg.consume("s1", "delete_user", obj("""{"username":"alice"}"""), token) == Left(Mismatch))
        assert(reg.consume("s1", "delete_user", obj("""{"username":"bob"}"""), token) == Left(Unknown))
    }

    test("a second consume of a used token is unknown") {
        val (reg, _) = fresh()
        val input = obj("""{"label":"ci"}""")
        val token = reg.issue("s1", "revoke_api_key", input)
        assert(reg.consume("s1", "revoke_api_key", input, token) == Right(()))
        assert(reg.consume("s1", "revoke_api_key", input, token) == Left(Unknown))
    }

    test("a token is expired after 10 minutes (clock advanced 11 min)") {
        val (reg, clock) = fresh()
        val input = obj("""{"username":"bob"}""")
        val token = reg.issue("s1", "delete_user", input)
        clock.advanceMinutes(11)
        assert(reg.consume("s1", "delete_user", input, token) == Left(Unknown))
    }

    test("a token is still valid inside the 10-minute window") {
        val (reg, clock) = fresh()
        val input = obj("""{"username":"bob"}""")
        val token = reg.issue("s1", "delete_user", input)
        clock.advanceMinutes(9)
        assert(reg.consume("s1", "delete_user", input, token) == Right(()))
    }

    test("a token issued for tool A is rejected for tool B") {
        val (reg, _) = fresh()
        val input = obj("""{"label":"ci"}""")
        val token = reg.issue("s1", "rotate_api_key", input)
        assert(reg.consume("s1", "revoke_api_key", input, token).isLeft)
        val other = reg.issue("s1", "rotate_api_key", input)
        assert(reg.consume("s1", "rotate_api_key", input, other) == Right(()))
    }

    test("a token issued in one scope is rejected in another") {
        val (reg, _) = fresh()
        val input = obj("""{"username":"bob"}""")
        val token = reg.issue("session-a", "delete_user", input)
        assert(reg.consume("session-b", "delete_user", input, token).isLeft)
        val other = reg.issue("session-a", "delete_user", input)
        assert(reg.consume("session-a", "delete_user", input, other) == Right(()))
    }

    test("an unknown token is unknown") {
        val (reg, _) = fresh()
        assert(reg.consume("s1", "delete_user", obj("""{"username":"bob"}"""), "not-a-token") == Left(Unknown))
    }

    test("tokens are distinct per issue") {
        val (reg, _) = fresh()
        val input = obj("""{"username":"bob"}""")
        val a = reg.issue("s1", "delete_user", input)
        val b = reg.issue("s1", "delete_user", input)
        assert(a != b)
        // 128 random bits: at least 22 chars in any common text encoding.
        assert(a.length >= 22, s"token too short for 128 bits: $a")
    }
}
