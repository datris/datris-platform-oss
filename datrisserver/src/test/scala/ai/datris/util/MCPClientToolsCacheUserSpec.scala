package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** Story: Chat endpoints act with the caller's own authority (review
  * follow-up). The MCP server lists tools per key AND per on-behalf-of user,
  * so MCPClient's tools cache must not share one `ui`-key entry between a
  * restricted user and an admin. */
class MCPClientToolsCacheUserSpec extends AnyFunSuite {

    private val ui = "ui-secret-value"

    test("two on-behalf-of users of the same key get different tools cache keys") {
        assert(MCPClient.cacheKey(ui, MCPClient.toolsSuffix(Some("bob"))) != MCPClient.cacheKey(ui, MCPClient.toolsSuffix(Some("root"))))
    }

    test("a user's entry differs from the no-user entry; an empty user is no user") {
        assert(MCPClient.cacheKey(ui, MCPClient.toolsSuffix(Some("bob"))) != MCPClient.cacheKey(ui, MCPClient.toolsSuffix(None)))
        assert(MCPClient.toolsSuffix(Some("")) == MCPClient.toolsSuffix(None))
        assert(MCPClient.toolsSuffix(None) == "tools")
    }

    test("a username cannot forge another user's suffix") {
        assert(MCPClient.toolsSuffix(Some("a|user:1:b")) != MCPClient.toolsSuffix(Some("a")))
        assert(MCPClient.toolsSuffix(Some("ab")) != MCPClient.toolsSuffix(Some("a")))
    }

    test("the same key and user share the entry") {
        assert(MCPClient.cacheKey(ui, MCPClient.toolsSuffix(Some("bob"))) == MCPClient.cacheKey(ui, MCPClient.toolsSuffix(Some("bob"))))
    }
}
