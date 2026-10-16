# Release Notes

## Unreleased

- **Fixes are rehearsed in a scratch lane before they land.** When the recovery agent proposes a fix for a failing tap, the platform first runs it against a scratch copy of the pipeline and compares the result with the last good run. A fix that clearly breaks the data is stopped with nothing written; one that changes the data's shape waits for a person, who can review the evidence and choose "Proceed to real run". Installs with the recovery agent in `propose` or `autopilot` will see one extra verification step per incident, and some proposals may now end as failed or waiting for a person on the evidence alone. Set `recovery.verifyMode` to `real` in the agent policy to keep the previous behaviour.

## v1.51.0 — October 16, 2026

**Run Datris across dev, QA and production: promote what you tested, and always know where you are.**

- **A guide to dev, QA and production.** A new [Dev, QA and production](https://docs.datris.ai/production/environments) page under Production explains how to run one install per environment, promote a catalog archive from one to the next, keep secrets and GitHub branches per environment, drive promotion from CI, and point agents and the CLI at the right install.
- **See which environment you are on.** Set `DATRIS_ENV` to `dev`, `qa`, `production` or any other word and the UI header shows it (red for `production`, amber for anything else), the version check and `datris doctor` report it, and catalog archives record where they were exported from. Restoring an archive into an install with a different label adds a warning naming both; the restore still goes ahead. Only `production` changes behaviour, exactly as before.
- **Agents know the environment too.** A connected agent is told which environment it is on before it does anything, and responses from tools that change or remove things name the environment they changed, so a call to the wrong install stands out. On `production`, agents are asked to confirm with you before a change. `datris version` prints the label as well.
- **Promoted pipelines run the AI scripts you tested.** Restoring a catalog archive now makes each pipeline's archived AI data-quality and transformation scripts the live scripts, with the model and generation time they were recorded with, so production runs exactly what QA tested and the restore makes no model call. The restore result lists which scripts were imported, regenerated or kept, and why. Pass `--regenerate-scripts` to regenerate them on the target instead. With GitHub script storage, a restore that would place a new pipeline's AI script on a different file already in the repository is refused and nothing is written.
- **Deleting never removes a hand-edited script.** With GitHub script storage, deleting a pipeline, a tap or a whole catalog keeps any script file that was edited in the repository since Datris last wrote it. The item is still deleted, and the result names each kept file so you can remove it by hand. Saving a pipeline without an AI instruction it used to have keeps an edited script file the same way and says so.
- **Clearer "not found" answers.** Looking up or deleting a pipeline or tap, or running or setting the state of a tap, that does not exist now answers "not found" instead of a server error or a false "deleted".

**Upgrading**

- The server, UI and MCP server images changed: run `docker compose pull && docker compose up -d`.
- No configuration changes are required. Installs without `DATRIS_ENV` look and behave as before, and agents see the same instructions and responses as before.
- Archives exported by earlier releases restore as before, and their AI scripts are regenerated on the target as before; re-export a catalog to carry its scripts' records.
- Scripts that delete a tap unconditionally should expect a "not found" answer for a tap that does not exist, where they used to get a success.
