"""Story: retire the multi-tenant setting (plans/stories/retire-multi-tenant-setting.md).

The compose files keep forwarding MULTI_TENANT to the server as MULTITENANT, so
a `true` left in an operator's `.env` reaches the server (which then refuses to
start) instead of turning into a silent single-tenant start. The default stays
off, so an install that never set it sees no change."""
import os

import pytest
import yaml

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


@pytest.mark.parametrize("name", ["docker-compose.yml", "docker-compose.standalone.yml"])
def test_compose_still_forwards_multi_tenant_with_an_off_default(name):
    with open(os.path.join(ROOT, name), encoding="utf-8") as f:
        services = yaml.safe_load(f)["services"]
    env = services["datris"]["environment"]
    if isinstance(env, list):
        env = dict(item.partition("=")[::2] for item in env)
    assert env.get("MULTITENANT") == "${MULTI_TENANT:-false}", env.get("MULTITENANT")
