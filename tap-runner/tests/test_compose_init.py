"""Both runner services run under an init (tini as PID 1).

app.py is the container's entrypoint and never waits for processes it did not
start, so a script's orphaned grandchild (reparented to PID 1) would stay a
zombie until the container restarts. `init: true` puts tini in front of app.py
to reap them. Verified live (codegen-script-isolation follow-ups): five runs of
a script whose grandchild is orphaned left 5 zombies without --init, 0 with it.
That live check needs Docker; this pins the compose setting."""
import os
import re

import pytest

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")


def _service_block(text, name):
    m = re.search(r"^  %s:\n((?:    .*\n|\s*\n)+)" % re.escape(name), text, re.M)
    assert m, "service %s not found" % name
    return m.group(1)


@pytest.mark.parametrize("compose", ["docker-compose.yml", "docker-compose.standalone.yml"])
@pytest.mark.parametrize("service", ["datris-tap-runner", "datris-codegen-runner"])
def test_runner_services_run_under_an_init(compose, service):
    with open(os.path.join(ROOT, compose)) as f:
        block = _service_block(f.read(), service)
    assert re.search(r"^    init: true\s*$", block, re.M), "%s in %s needs `init: true`" % (service, compose)
