#!/usr/bin/env python3
"""
rlimit_exec.py: per-script resource limits for datris-tap-runner (runner overload protection).

Usage: python3 rlimit_exec.py <program> [args...]

Lowers RLIMIT_AS to TAP_RUNNER_SCRIPT_MEMORY_MB megabytes (default 1024; 0 = no limit)
and RLIMIT_NPROC to TAP_RUNNER_SCRIPT_MAX_PROCS (default 0 = no limit), then
replaces itself with <program>. It runs in the child, so the threaded runner never
needs a preexec_fn. Lowering a limit is allowed unprivileged.

RLIMIT_NPROC is off by default: the kernel counts it per uid across every process and
thread (both runner containers share uid 10001), so a small value starves ordinary
multi-threaded scripts. The container's pids_limit is the fork-bomb bound. On macOS RLIMIT_AS
cannot be lowered (setrlimit raises); that is tolerated and the script runs without it.
"""
import os
import resource
import sys


def _int_env(name, default):
    raw = os.environ.get(name, "")
    try:
        return int(raw) if raw.strip() else default
    except ValueError:
        return default


def _lower(which, value):
    """Set soft and hard limit to `value`, never raising an existing lower hard limit."""
    if value <= 0:
        return
    try:
        _, hard = resource.getrlimit(which)
        if hard != resource.RLIM_INFINITY and hard < value:
            value = hard
        resource.setrlimit(which, (value, value))
    except (ValueError, OSError):
        # macOS refuses RLIMIT_AS; an exotic kernel may refuse others. Run unlimited
        # rather than refuse the script.
        pass


def main(argv):
    if not argv:
        print("usage: rlimit_exec.py <program> [args...]", file=sys.stderr)
        return 2
    mem_mb = _int_env("TAP_RUNNER_SCRIPT_MEMORY_MB", 1024)
    procs = _int_env("TAP_RUNNER_SCRIPT_MAX_PROCS", 0)
    _lower(resource.RLIMIT_AS, mem_mb * 1024 * 1024)
    _lower(resource.RLIMIT_NPROC, procs)
    # The runner hands these two in only for this launcher; the script never sees them.
    os.environ.pop("TAP_RUNNER_SCRIPT_MEMORY_MB", None)
    os.environ.pop("TAP_RUNNER_SCRIPT_MAX_PROCS", None)
    program = argv[0]
    if os.sep in program:
        os.execv(program, argv)
    os.execvp(program, argv)
    return 127  # not reached


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
