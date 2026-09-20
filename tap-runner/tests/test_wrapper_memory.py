"""Story: taps survive large sources (plans/stories/tap-large-sources.md),
the memory half of the wrapper contract.

The REAL wrapper (`TapScriptRunner.WRAPPER_TEMPLATE`, extracted from the
Scala source exactly as TapStagingSpec does — it is a raw triple-quoted
`stripMargin` string, so stripping the `|` margin recovers it verbatim) is
run under python3 with `DATRIS_TAP_OUTPUT` pointed at a file:

* a script whose `fetch()` YIELDS 2,000,000 rows must stage them all
  (envelope count == 2,000,000, one NDJSON line each) while its peak RSS
  stays bounded — the wrapper writes one row at a time and never holds the
  result;
* the same rows RETURNED AS A LIST must not fit: under an address-space
  rlimit the interpreter dies (MemoryError / killed), and where the kernel
  does not enforce RLIMIT_AS (macOS) the peak RSS provably exceeds the bound.

Peak RSS comes from `os.wait4` (per-child rusage; ru_maxrss is bytes on
Darwin, kilobytes on Linux). `resource.setrlimit(RLIMIT_AS)` is applied in
the child where the platform allows it. Today the wrapper treats a generator
as a single JSON value (`count 1`, the file holds `"<generator object ...>"`),
so the generator case fails on the count. No fixtures exist under
tap-runner/ — run with `python3 -m pytest tap-runner/tests -q`.
"""
import json
import os
import re
import resource
import subprocess
import sys
import threading

import pytest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SCALA_SOURCE = os.path.join(REPO_ROOT, "datrisserver", "src", "main", "scala", "ai", "datris", "util", "TapScriptRunner.scala")

ROWS = 2_000_000
# Address-space cap for the child (enforced on Linux; Darwin ignores it).
RLIMIT_BYTES = 640 * 1024 * 1024
# The streaming run must stay well under the cap; the list run must blow past it.
STREAM_RSS_BOUND = 256 * 1024 * 1024
LIST_RSS_FLOOR = 512 * 1024 * 1024

GENERATOR_SCRIPT = """\
def fetch():
    for i in range(%d):
        yield {"id": i, "pad": ("%%07d" %% i) * 17}
""" % ROWS

LIST_SCRIPT = """\
def fetch():
    return [{"id": i, "pad": ("%%07d" %% i) * 17} for i in range(%d)]
""" % ROWS


def wrapper_source():
    with open(SCALA_SOURCE, encoding="utf-8") as fh:
        scala = fh.read()
    m = re.search(r'WRAPPER_TEMPLATE =\s*\n\s*"""(.*?)""".stripMargin', scala, re.S)
    assert m, "WRAPPER_TEMPLATE not found in TapScriptRunner.scala"
    out = []
    for line in m.group(1).split("\n"):
        stripped = line.lstrip()
        if stripped.startswith("|"):
            out.append(stripped[1:])
        elif stripped == "":
            continue
        else:
            out.append(line)
    return "\n".join(out) + "\n"


def _limit_address_space():
    try:
        soft, hard = resource.getrlimit(resource.RLIMIT_AS)
        cap = RLIMIT_BYTES if hard == resource.RLIM_INFINITY else min(RLIMIT_BYTES, hard)
        resource.setrlimit(resource.RLIMIT_AS, (cap, hard))
    except (ValueError, OSError):
        pass  # not enforceable here; the RSS assertions still hold


def _maxrss_bytes(ru):
    return ru.ru_maxrss if sys.platform == "darwin" else ru.ru_maxrss * 1024


def run_wrapper(tmp_path, script_body, name):
    wrapper = tmp_path / ("wrapper_%s.py" % name)
    wrapper.write_text(wrapper_source(), encoding="utf-8")
    script = tmp_path / ("script_%s.py" % name)
    script.write_text(script_body, encoding="utf-8")
    output = tmp_path / ("out_%s.ndjson" % name)
    env = {k: v for k, v in os.environ.items() if not k.startswith("DATRIS_")}
    env["DATRIS_TAP_OUTPUT"] = str(output)
    proc = subprocess.Popen(
        [sys.executable, str(wrapper), str(script)],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        preexec_fn=_limit_address_space,
    )
    # Drain both pipes on threads, then reap the child ourselves with wait4 so
    # the rusage is THIS child's (RUSAGE_CHILDREN is cumulative across the
    # whole pytest session and would smear the list run's peak onto the
    # generator run, or vice versa, depending on test order).
    bufs = {}

    def drain(name, stream):
        bufs[name] = stream.read()
        stream.close()

    threads = [threading.Thread(target=drain, args=(n, s)) for n, s in (("out", proc.stdout), ("err", proc.stderr))]
    for t in threads:
        t.start()
    _, status, ru = os.wait4(proc.pid, 0)
    for t in threads:
        t.join()
    proc.returncode = os.waitstatus_to_exitcode(status)
    return proc.returncode, bufs["out"].decode("utf-8", "replace").strip(), bufs["err"].decode("utf-8", "replace"), output, _maxrss_bytes(ru)


def _count_lines(path):
    n = 0
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            n += chunk.count(b"\n")
    return n


def test_extracted_wrapper_runs_a_trivial_list_script(tmp_path):
    # Sanity for the extraction: a broken margin strip must fail here, not in
    # the 2M-row case.
    rc, out, err, output, _ = run_wrapper(tmp_path, "def fetch():\n    return [{'id': 1}, {'id': 2}]\n", "sanity")
    assert rc == 0, err
    env = json.loads(out)
    assert env["type"] == "json" and env["count"] == 2 and env["columns"] == ["id"], out
    assert _count_lines(output) == 2


def test_generator_of_two_million_rows_stages_with_bounded_rss(tmp_path):
    rc, out, err, output, maxrss = run_wrapper(tmp_path, GENERATOR_SCRIPT, "generator")
    assert rc == 0, "the streaming run must survive the address-space cap: rc=%s stderr tail: %s" % (rc, err[-500:])
    env = json.loads(out)
    assert env["type"] == "json", out
    assert env["count"] == ROWS, "every yielded row is counted: " + out
    assert env["columns"] == ["id", "pad"], out
    assert "data" not in env, out
    assert _count_lines(output) == ROWS, "one NDJSON line per yielded row"
    with open(output, "rb") as fh:
        first = fh.readline()
    assert b"generator object" not in first, "the file must hold records, not the generator's repr: %r" % first[:80]
    assert maxrss < STREAM_RSS_BOUND, "streaming 2M rows must not grow the interpreter: peak RSS %d MB" % (maxrss >> 20)


def test_the_same_rows_returned_as_a_list_are_not_bounded(tmp_path):
    # A materialised 2M-row list is what the story's field incident did. Under
    # RLIMIT_AS the interpreter cannot build it (MemoryError, exit 1, or
    # SIGKILL); where the limit is not enforced, its RSS is provably above the
    # streaming bound. Either way the list lane is the unbounded one.
    rc, out, err, output, maxrss = run_wrapper(tmp_path, LIST_SCRIPT, "list")
    assert rc != 0 or maxrss > LIST_RSS_FLOOR, (
        "a materialised list of %d rows completed with rc=%s and peak RSS %d MB — the fixture is not big enough to prove the bound"
        % (ROWS, rc, maxrss >> 20)
    )
