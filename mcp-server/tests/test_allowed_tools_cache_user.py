"""Story: Chat endpoints act with the caller's own authority (review
follow-up). The tool listing filter (`_allowed_tool_names`) is cached per key
AND per on-behalf-of user: the `ui` key lists different tools for a restricted
user than for an admin, so two users must never share one cache entry."""
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import server  # noqa: E402


@pytest.fixture
def listing(monkeypatch):
    calls = []

    def fake_fetch():
        obo = server._session_on_behalf_of.get()
        calls.append(("get", "/api/v1/mcp/tools", obo))
        tools = ["get_pipeline"] if obo == "bob" else ["get_pipeline", "delete_pipeline"]
        return 200, json.dumps({"filtered": True, "tools": tools})

    monkeypatch.setattr(server, "_fetch_allowed_tools", fake_fetch)
    monkeypatch.setattr(server, "_allowed_tools_cache", {})
    return calls


def _as(key, obo):
    tk = server._session_api_key.set(key)
    to = server._session_on_behalf_of.set(obo)
    try:
        return server._allowed_tool_names()
    finally:
        server._session_api_key.reset(tk)
        server._session_on_behalf_of.reset(to)


def test_cache_key_includes_the_on_behalf_of_user():
    assert server._allowed_tools_cache_key("ui-key", "bob") != server._allowed_tools_cache_key("ui-key", "root")
    assert server._allowed_tools_cache_key("ui-key", "") != server._allowed_tools_cache_key("ui-key", "bob")
    assert server._allowed_tools_cache_key(None, None) == server._allowed_tools_cache_key("", "")


def test_restricted_listing_cached_first_does_not_narrow_the_admin(listing):
    assert _as("ui-key", "bob") == {"get_pipeline"}
    assert _as("ui-key", "root") == {"get_pipeline", "delete_pipeline"}
    assert len(listing) == 2


def test_admin_listing_cached_first_is_not_shown_to_the_restricted_user(listing):
    assert _as("ui-key", "root") == {"get_pipeline", "delete_pipeline"}
    assert _as("ui-key", "bob") == {"get_pipeline"}


def test_same_key_and_user_is_served_from_the_cache(listing):
    _as("ui-key", "bob")
    _as("ui-key", "bob")
    assert len(listing) == 1
