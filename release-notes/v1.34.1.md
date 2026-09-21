# Release Notes

## v1.34.1 — September 20, 2026

**Taps stream large sources, and failures say what went wrong.**

- **A tap can yield its records.** `fetch()` may return a generator or any iterator instead of a list. Records are written to disk as they are produced, so a tap over millions of rows runs in a few tens of megabytes of memory. Scripts that return a list are unchanged.
- **A tap killed for memory says so.** Instead of a bare exit code, the error explains that the script was killed, almost certainly out of memory, and that `fetch()` should yield records instead of building the whole result. The Assistant and the MCP guidance carry the same rule, stop to report after two failed tests, and no longer probe the runner environment.
- **Rows returned as lists land in CSV pipelines.** A tap that returns each row as a list, with the header as the first row, now loads into a CSV pipeline with the header applied and column names normalised, or fails with a message naming the problem. Previously this shape fed unparseable data into the pipeline and corrupted its schema.
- **A bad first line no longer changes the pipeline.** A CSV upload or object-store pickup whose header line does not parse fails before schema evolution runs, so the pipeline's schema and version are untouched. Quoted header names now resolve to their plain names.
- **A blank schedule is no schedule.** Saving a tap with an empty schedule stores none, and the scheduler stays quiet instead of logging an error on every tick. Taps already saved that way are quiet on upgrade with no action needed.
- **Testing a tap that yields stops at the test limit** instead of draining the whole source.

**Upgrading**

Run `datris doctor --pre-upgrade`, then `docker compose pull && docker compose up -d --force-recreate`. Update all images together, including the MCP server. No configuration changes are required.
