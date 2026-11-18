package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** Story: Chat endpoints act with the caller's own authority
  * (plans/stories/chat-endpoints-caller-identity.md). Caller keys now reach
  * MCPClient's tool / resource caches, so two keys must never share an entry.
  * Assumes `private[util] def cacheKey(apiKey: String, suffix: String): String`. */
class MCPClientCacheKeySpec extends AnyFunSuite {

    test("two keys sharing their first six characters get different cache keys") {
        val a = "dk_abc-scoped-key-1111"
        val b = "dk_abc-full-access-2222"
        assert(a.take(6) == b.take(6))
        Seq("tools", "resources", "resource|datris://pipeline-config-reference").foreach { s =>
            assert(MCPClient.cacheKey(a, s) != MCPClient.cacheKey(b, s), s)
        }
    }

    test("null and empty key share the anonymous entry") {
        Seq("tools", "resources").foreach { s =>
            assert(MCPClient.cacheKey(null, s) == MCPClient.cacheKey("", s), s)
            assert(MCPClient.cacheKey(null, s) != MCPClient.cacheKey("dk_abc-scoped-key-1111", s), s)
        }
    }
}
