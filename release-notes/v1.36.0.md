# Release Notes

## v1.36.0 — September 23, 2026

**Taps handle sources of tens of millions of rows: faster batches, honest timeouts, and an estimate before anything is built.**

- **A tap can yield batches.** `fetch()` may yield DataFrames or Arrow record batches as well as single records. Batches are written natively, roughly ten times faster than one record at a time, and land exactly what yielding the same rows one by one would land: integers stay integers, nulls stay null, timestamps keep their precision. Frames read with the pyarrow backend are the fastest of all. Scripts that yield or return records are unchanged.
- **Tests preview, runs run.** A tap test started from the Assistant, an MCP client or the CLI now stops after 20 records, exactly like the UI's Test button, instead of streaming the whole source and timing out. The tools say plainly that a test's record count is not what a run will produce. Pass a limit of 0 to test the whole source.
- **Real runs get their own timeout.** Tests still stop at five minutes; real and scheduled runs now get an hour by default. Both are settable, and a timed-out run says which mode it ran in and which setting to raise. A mistyped value warns at startup and falls back instead of stopping the server.
- **A timed-out run keeps its logs.** The error now ends with how many records were streamed before the kill and the last lines the script printed, and the run log holds the script's full output. A script that yields prints progress every 100,000 records, so a slow-but-healthy run reads as slow, not broken.
- **Agents estimate before they build.** For a large source the Assistant and the MCP tools show rows, bytes per record, disk and minutes, compare them against the budgets actually set on your install rather than the documented defaults, and when one is exceeded stop and offer three choices: raise it, narrow the window, or chunk the range. Column projection is named as the lever that cuts both numbers.
- **Malformed schedules are refused at save.** A 5-field Unix cron is rejected with the 6-field equivalent to use, with weekday numbers translated. Taps already stored with a bad schedule log once instead of every 30 seconds.

**Upgrading**

Run `datris doctor --pre-upgrade`, then `docker compose pull && docker compose up -d --force-recreate`. Update all images together, including the UI and the MCP server. Two new optional settings in `.env`: `TAP_SCRIPT_TIMEOUT_SECONDS` (default 300, tests) and `TAP_RUN_TIMEOUT_SECONDS` (default 3600, real and scheduled runs). If you had raised the old single timeout in your own configuration, real runs keep at least that value. The version endpoint now reports the budgets in force and where each came from; a value you never set now reads as "default" rather than as set, with no change to the value itself.
