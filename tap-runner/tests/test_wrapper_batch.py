"""Story: taps may yield batches (plans/stories/tap-batch-yield.md), the
memory half of the batch lane.

The REAL wrapper (`TapScriptRunner.WRAPPER_TEMPLATE`, extracted from the
Scala source by `test_wrapper_memory.wrapper_source`) is run under python3
with `DATRIS_TAP_OUTPUT` pointed at a file and an address-space rlimit on
the child: a script whose `fetch()` YIELDS 20 pandas DataFrames of 100,000
rows x 25 columns must stage all 2,000,000 rows (envelope count ==
2,000,000, one NDJSON line each) while its peak RSS stays bounded — the
wrapper serialises one batch at a time and never holds the stream.

Today the wrapper writes each DataFrame through `json.dumps(..., default=str)`,
so the file holds 20 lines of frame repr and the envelope says count 20:
the count assertion fails. Skips (does not fail) when pandas is absent.
Run with `python3 -m pytest tap-runner/tests -q`.
"""
import json
import os
import resource
import subprocess
import sys
import threading

import pytest

from test_wrapper_memory import _maxrss_bytes, _count_lines, wrapper_source

pd = pytest.importorskip("pandas", reason="pandas not installed in this interpreter")

BATCHES = 20
BATCH_ROWS = 100_000
COLUMNS = 25
ROWS = BATCHES * BATCH_ROWS
# Peak RSS bound for the streaming batch run. A pandas import plus ONE
# 100,000 x 25 frame is tens of MB; holding the stream would be GBs.
BATCH_RSS_BOUND = 640 * 1024 * 1024
# Address-space cap for the child (enforced on Linux, ignored on Darwin). Set
# above the RSS bound: pandas reserves far more virtual address space than it
# resides in, and this test is about RSS, not VA.
RLIMIT_BYTES = 2048 * 1024 * 1024


def _limit_address_space():
    try:
        soft, hard = resource.getrlimit(resource.RLIMIT_AS)
        cap = RLIMIT_BYTES if hard == resource.RLIM_INFINITY else min(RLIMIT_BYTES, hard)
        resource.setrlimit(resource.RLIMIT_AS, (cap, hard))
    except (ValueError, OSError):
        pass  # not enforceable here; the RSS assertion still holds

BATCH_SCRIPT = """\
import pandas as pd


def _frame(base):
    cols = {}
    for c in range(18):
        cols["f%%02d" %% c] = [float(base + c) + 0.5] * %(rows)d
    for c in range(4):
        cols["i%%02d" %% c] = list(range(base, base + %(rows)d))
    cols["t0"] = pd.to_datetime(["2026-09-20 12:00:00"] * %(rows)d)
    cols["t1"] = pd.to_datetime(["2026-09-20 12:00:00.123456"] * %(rows)d)
    cols["s0"] = ["row-%%d" %% base] * %(rows)d
    return pd.DataFrame(cols)


def fetch():
    for b in range(%(batches)d):
        yield _frame(b * %(rows)d)
""" % {"rows": BATCH_ROWS, "batches": BATCHES}


def run_wrapper(tmp_path, script_body, name):
    """Same child-reaping harness as test_wrapper_memory.run_wrapper (its rusage
    must be THIS child's, not the cumulative session's)."""
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
    bufs = {}

    def drain(key, stream):
        bufs[key] = stream.read()
        stream.close()

    threads = [threading.Thread(target=drain, args=(n, s)) for n, s in (("out", proc.stdout), ("err", proc.stderr))]
    for t in threads:
        t.start()
    _, status, ru = os.wait4(proc.pid, 0)
    for t in threads:
        t.join()
    proc.returncode = os.waitstatus_to_exitcode(status)
    return proc.returncode, bufs["out"].decode("utf-8", "replace").strip(), bufs["err"].decode("utf-8", "replace"), output, _maxrss_bytes(ru)


def test_two_million_rows_yielded_as_twenty_frames_stage_with_bounded_rss(tmp_path):
    rc, out, err, output, maxrss = run_wrapper(tmp_path, BATCH_SCRIPT, "batches")
    assert rc == 0, "the batch run must survive the address-space cap: rc=%s stderr tail: %s" % (rc, err[-500:])
    env = json.loads(out)
    assert env["type"] == "json", out
    assert env["count"] == ROWS, "count is ROWS across all batches, not batches: " + out
    assert len(env["columns"]) == COLUMNS, "the columns union is the frame's %d columns: %s" % (COLUMNS, out)
    assert "data" not in env, out
    assert _count_lines(output) == ROWS, "one NDJSON line per ROW of every batch"
    with open(output, "rb") as fh:
        first = fh.readline()
    row = json.loads(first)
    assert len(row) == COLUMNS, "each line is one record, not a frame repr: %r" % first[:120]
    assert "DataFrame" not in first.decode("utf-8", "replace"), "the file must hold records, not the frame's repr: %r" % first[:120]
    assert maxrss < BATCH_RSS_BOUND, "streaming %d rows as batches must not grow the interpreter: peak RSS %d MB" % (ROWS, maxrss >> 20)


# Story: plans/stories/tap-batch-arrow-backend-fast-path.md — the same 20 x
# 100,000-row stream, but with arrow-BACKED (ArrowDtype) columns, the backend a
# tap gets from pd.read_parquet(..., dtype_backend="pyarrow"). The vectorised
# fast path must not trade the bounded-RSS guarantee for its speed: the wrapper
# still holds one batch (and one 10,000-row write chunk) at a time.
ARROW_BATCH_SCRIPT = """\
import pandas as pd
import pyarrow as pa


def _table(base):
    cols = {}
    for c in range(18):
        cols["f%%02d" %% c] = pa.array([float(base + c) + 0.5] * %(rows)d, type=pa.float64())
    for c in range(4):
        cols["i%%02d" %% c] = pa.array(list(range(base, base + %(rows)d)), type=pa.int64())
    cols["t0"] = pa.array([pd.Timestamp("2026-09-20 12:00:00").to_pydatetime()] * %(rows)d, type=pa.timestamp("us"))
    cols["t1"] = pa.array([pd.Timestamp("2026-09-20 12:00:00.123456").to_pydatetime()] * %(rows)d, type=pa.timestamp("us"))
    cols["s0"] = pa.array(["row-%%d" %% base] * %(rows)d)
    return pa.table(cols)


def fetch():
    for b in range(%(batches)d):
        yield _table(b * %(rows)d).to_pandas(types_mapper=pd.ArrowDtype)
""" % {"rows": BATCH_ROWS, "batches": BATCHES}


def test_two_million_arrow_dtype_rows_stage_with_bounded_rss(tmp_path):
    pytest.importorskip("pyarrow", reason="pyarrow not installed in this interpreter")
    rc, out, err, output, maxrss = run_wrapper(tmp_path, ARROW_BATCH_SCRIPT, "arrow_batches")
    assert rc == 0, "the ArrowDtype batch run must survive the address-space cap: rc=%s stderr tail: %s" % (rc, err[-500:])
    env = json.loads(out)
    assert env["type"] == "json", out
    assert env["count"] == ROWS, "count is ROWS across all ArrowDtype batches: " + out
    assert len(env["columns"]) == COLUMNS, "the columns union is the frame's %d columns: %s" % (COLUMNS, out)
    assert _count_lines(output) == ROWS, "one NDJSON line per ROW of every ArrowDtype batch"
    with open(output, "rb") as fh:
        first = fh.readline()
    row = json.loads(first)
    assert len(row) == COLUMNS, "each line is one record, not a frame repr: %r" % first[:120]
    assert row["i00"] == 0 and isinstance(row["i00"], int), "an ArrowDtype int stays an int: %r" % first[:120]
    # 25 plain columns x 10 write chunks x 20 batches, all on the fast path.
    assert "arrow-backed columns: %d fast / 0 per-value" % (COLUMNS * (ROWS // 10000)) in err, (
        "every plain ArrowDtype column must take the fast path: %s" % err[-500:]
    )
    assert maxrss < BATCH_RSS_BOUND, "streaming %d ArrowDtype rows must not grow the interpreter: peak RSS %d MB" % (
        ROWS,
        maxrss >> 20,
    )
