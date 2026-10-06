# Release Notes

## Unreleased

**`datris doctor` tells you when a newer version is available.**

- **Know when you are behind.** A new `version.update` row in `datris doctor` reads "up to date" or names the newer version and the upgrade command. To find out, doctor tells datris.ai your Datris version, operating system and CPU architecture, and nothing else, each time you run it. Datris uses this to count the versions in use. It never happens from the server, on a timer, or during `datris doctor --pre-upgrade`, and nothing is stored on your machine. See [Doctor](/doctor#update-check).
- **Off with one line.** Add `DATRIS_UPDATE_CHECK=0` to `.env` (or set it in the environment) and doctor sends nothing. A fresh install prints a short notice about the check, and `DATRIS_UPDATE_CHECK=0` given to the installer writes the line for you.
- **Never in the way.** If datris.ai cannot be reached within three seconds, the row is skipped and doctor's result is unaffected.
- **How to verify.** [Security Architecture](/production/security-architecture#no-telemetry) now lists both outbound requests Datris can make, what each sends, and commands to confirm there are no others.

**Upgrading**

- **The update check is on after you upgrade the CLI** (`pip install -U datris-mcp-server` or `brew upgrade datris`). A deployment that made no request to datris.ai outside the Configuration screen now makes one each time someone runs `datris doctor`. To keep it off, add `DATRIS_UPDATE_CHECK=0` to `.env`; the installer never edits an existing `.env`, so the line survives every upgrade.
- **`datris doctor` exit code.** On a deployment that is behind the latest release, doctor now reports a warning and exits 1 where it exited 0 before. Upgrade, switch the check off, or treat 1 as passing if a script gates on 0. `datris doctor --pre-upgrade`, which the installer runs, is unchanged.
- No other configuration changes are required.

## v1.44.0 — October 6, 2026

**One switch to turn on the governance controls, a doctor check that reports when they are off, and corrected first-login instructions.**

- **Switch on the governance controls in one step.** User login, API keys, the audit log and the agent policy ship in the open-source build and are switched on for production. A fresh install now takes one installer option, `DATRIS_GOVERNED=1`, that turns all four on; an interactive install asks once, defaulting to no. An existing install adds four documented lines to its `.env`. Nothing changes for installs that do not ask for it. See [Quick Start](/quick-start#2-switch-on-the-governance-controls).
- **`datris doctor` reports the governance controls.** A new `governance.controls` row names any of the four controls that is off, with the exact lines to add. It appears in the CLI, in Configuration → Doctor and in the agent's doctor tool, and is a warning, never an error. See [Doctor](/doctor).
- **First-login instructions corrected.** The `admin` account is created with a bootstrap password that the server prints once to its log, not a blank password. The quick start, the user-auth page, the configuration reference and the login screen now say where to read it, and to save it before recreating the container, because recreating discards that log. A new [Recovering admin access](/user-auth#recovering-admin-access) section covers the case where it is gone.
- **Fixed: a headless install on Debian or Ubuntu stopped silently.** Running the installer with no terminal attached, as in CI, cron or an agent-driven install on a Linux host, exited right after fetching the runtime files with no message. It now continues as intended.
- **Governed installs skip a check that needs a key.** When the governance controls are on, the installer no longer waits on the post-boot store check, which requires an API key once API keys are enforced. It prints the command to run after a key exists instead.
- **Docs and website wording.** Pages that described the controls as "on by default" now say they are switched on for production, matching what a fresh install does.

**Upgrading**

Run `docker compose pull && docker compose up -d --force-recreate`. The server, UI and MCP server images changed.

- **No defaults change.** The four controls stay off unless you turn them on, and an existing `.env` is never edited by the installer.
- **`datris doctor` exit code.** On an install with any of the four controls off, doctor now reports a warning and exits 1 where it exited 0 before. Anything that treats a non-zero doctor exit as a failure will notice. The row is informational; turn the controls on, or read past it.
- **Before turning the controls on.** Read and save the `admin` bootstrap password from the server log first: `docker compose logs datris | grep "Bootstrap login"`. If nothing is printed, follow [Recovering admin access](/user-auth#recovering-admin-access).
- **`DATRIS_GOVERNED`.** New installer variable, accepted on a fresh install only. Values `1`/`true`/`yes`/`on` turn the controls on, `0`/`false`/`no`/`off` leave them off, and any other value stops the install before anything is written.
- No other configuration changes are required.
