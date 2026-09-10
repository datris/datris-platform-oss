# Release Notes

## v1.28.2 — September 10, 2026

**Failed loads now say which destination failed, and why.**

- **Destination failures are named in the job status.** When a pipeline run fails while writing to a destination, the job's error now identifies that destination (for example the PostgreSQL loader) and carries the destination's own error message, instead of a generic orchestration failure with a stack trace. The full stack trace is still available in the run's event stream for troubleshooting.
- **Agents and the CLI act on the real cause.** The Assistant, MCP clients, and the recovery agent read the same job status field, so they now see the failing destination directly and retry or repair the right thing. The `datris` CLI prints the failing destination with the error on a failed upload.
- **Multi-destination runs** still report as a single job. When one destination fails and another succeeds, the error names the one that failed.
- The AI fix suggestion for failed runs is unchanged and continues to appear alongside the error.

**Upgrading**

`docker compose pull && docker compose up -d --force-recreate`. No configuration changes required. CLI users on pip or Homebrew: upgrade to 1.28.2.
