# Release Notes

## v1.5.9 — April 11, 2026

Full tap MCP tool suite and user-supplied tap scripts.

### New MCP tools for taps

Four new MCP tools give agents complete control over taps:

- **get_tap** — retrieve full tap details including the generated Python script
- **test_tap** — validate a tap script without pushing data to the pipeline
- **update_tap** — enable/disable, change schedule, retarget pipeline, or replace the script
- **get_tap_logs** — view run history with status, record counts, duration, and errors

### User-supplied tap scripts

`create_tap` now accepts an optional `script` parameter — a raw Python `fetch()` function you write yourself instead of relying on AI generation. This is faster and more reliable when you know exactly what data to fetch. A `secret_name` parameter was also added to inject Vault credentials into the script.

The CLI's `datris tap create` mirrors this: pass `--script path/to/script.py` to supply your own script, or omit it for AI generation as before. A new `datris tap show` command displays full tap details.

### Server: direct script storage endpoint

New `POST /tap/script` API endpoint lets clients store a tap script directly without AI generation, and automatically links it to an existing tap config.

### Updated agent workflow

The MCP system prompt now includes a dedicated "Tap workflow" section guiding agents through the full create → test → run → schedule → monitor cycle, and the required workflow step 1 now checks for existing taps alongside pipelines.

### UI: tap workflow steps

The MCP tab workflow diagram now shows the two ingestion options (direct upload vs. tap) and includes sub-steps 4a–4d for the full tap lifecycle: create, test, run, and schedule.

### Upgrading from v1.5.8

No configuration changes required. Pull the new images and restart:

```sh
docker compose pull datris ui mcp-server
docker compose up -d datris ui mcp-server
```

### Version

- Server: 1.5.9
- MCP Server: 1.5.9
- CLI: 1.5.9

---

## v1.5.8 — April 10, 2026

See [v1.5.8 release notes](release-notes/v1.5.8.md).

## v1.5.7 — April 9, 2026

See [v1.5.7 release notes](release-notes/v1.5.7.md).

## v1.5.6 — April 8, 2026

See [v1.5.6 release notes](release-notes/v1.5.6.md).

## v1.5.5 — April 8, 2026

See [v1.5.5 release notes](release-notes/v1.5.5.md).

## v1.5.4 — April 7, 2026

See [v1.5.4 release notes](release-notes/v1.5.4.md).

## v1.5.3 — April 3, 2026

See [v1.5.3 release notes](release-notes/v1.5.3.md).

## v1.5.2 — April 3, 2026

See [v1.5.2 release notes](release-notes/v1.5.2.md).

## v1.5.1 — April 3, 2026

See [v1.5.1 release notes](release-notes/v1.5.1.md).

## v1.5.0 — April 2, 2026

See [v1.5.0 release notes](release-notes/v1.5.0.md).

## v1.4.4 — March 30, 2026

See [v1.4.4 release notes](release-notes/v1.4.4.md).

## v1.4.3 — March 29, 2026

See [v1.4.3 release notes](release-notes/v1.4.3.md).

## v1.4.2 — March 27, 2026

See [v1.4.2 release notes](release-notes/v1.4.2.md).
