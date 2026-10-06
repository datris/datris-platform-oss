# Release Notes

## v1.44.1 — October 6, 2026

**`datris doctor` tells you when a newer version is available.**

- **Know when you are behind.** A new `version.update` row in `datris doctor` reads "up to date" or names the newer version and the upgrade command. To find out, doctor tells datris.ai your Datris version, operating system and CPU architecture, and nothing else, each time you run it. Datris uses this to count the versions in use. It never happens from the server, on a timer, or during `datris doctor --pre-upgrade`, and nothing is stored on your machine. See [Doctor](/doctor#update-check).
- **Off with one line.** Add `DATRIS_UPDATE_CHECK=0` to `.env` (run doctor from the install directory or pass `--project-dir`, or set it in the environment) and doctor sends nothing. A fresh install prints a short notice about the check, and `DATRIS_UPDATE_CHECK=0` given to the installer writes the line for you.
- **Never in the way.** If datris.ai cannot be reached within three seconds, the row is skipped and doctor's result is unaffected.
- **How to verify.** [Security Architecture](/production/security-architecture#no-telemetry) now lists both outbound requests Datris can make, what each sends, and commands to confirm there are no others.
- **Security updates.** Refreshed the server, UI and tap-runner dependencies and the tap-runner base image to clear the open vulnerability reports. No behaviour change.

**Upgrading**

Run `docker compose pull && docker compose up -d --force-recreate`. All four images changed.

- **The update check is on after you upgrade the CLI** (`pip install -U datris-mcp-server` or `brew upgrade datris`). A deployment that made no request to datris.ai outside the Configuration screen now makes one each time someone runs `datris doctor`. To keep it off, add `DATRIS_UPDATE_CHECK=0` to `.env`; the installer never edits an existing `.env`, so the line survives every upgrade.
- **`datris doctor` exit code.** On a deployment that is behind the latest release, doctor now reports a warning and exits 1 where it exited 0 before. Upgrade, switch the check off, or treat 1 as passing if a script gates on 0. `datris doctor --pre-upgrade`, which the installer runs, is unchanged.
- No other configuration changes are required.
