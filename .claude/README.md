# Agent team scaffold

Reusable Claude Code roles and a lead command so one manager session can drive a story
from plan to reviewed pull request.

```
/plan-story <idea>          planner  -> writes a story file
/lead plans/stories/x.md    lead     -> implementer -> reviewer (loop) -> releaser (optional) -> PR
```

## Roles (`agents/`)

| Role | Purpose | Tools |
|---|---|---|
| `planner` | Turn an idea into a self-contained story with files, acceptance criteria, test commands | read-only + Write |
| `implementer` | Implement one story on the current branch, run the relevant tests, commit | all |
| `reviewer` | Independent review of the diff against the story; runs tests; returns PASS/FAIL with findings | read-only + Bash |
| `releaser` | Prepare a release PR: version bump, release notes, archive. Never tags, never pushes images | Read, Edit, Bash |

Each role is a markdown file with frontmatter (`name`, `description`, `tools`, `model`). The
manager spawns them with the Agent tool by name. Workers report to the manager and do not
talk to each other; if two workers must negotiate an interface, the lead relays it.

## Story format

A story is a markdown file the implementer can act on with zero prior context.
Required sections, in order:

```
# <title>
**Status:** proposed | claimed | in-review | done
**Branch:** feature/<slug>
## Goal            one paragraph, user-facing outcome
## Files           expected files to touch, repo-relative
## Steps           numbered, concrete, each independently checkable
## Acceptance      bullet list; each bullet is a test or an observable behaviour
## Verify          exact commands (sbt, pytest, npm) the reviewer will run
## Out of scope    what NOT to do
```

Stories live in `plans/stories/`, which is gitignored like the rest of `plans/`. Point `/lead` at
a GitHub Issue URL instead and it reads the body with `gh issue view`.

## Running it unattended

- **In-session cron:** `/loop 30m /lead plans/stories/next.md`
- **System cron / launchd:** `claude -p "/lead <story>" --permission-mode bypassPermissions`
- **Cloud routine:** `/schedule` with the same prompt; the routine sees the GitHub clone only,
  so stories that need the local Docker stack must run locally.

Workers inherit the session's permission mode. Unattended runs need `bypassPermissions` or an
explicit allowlist, otherwise the first prompt stalls the whole team.

## Repo rules every role follows

- Never commit or push anything under `plans/`.
- Editing `docker-compose.yml` requires `python3 scripts/build-standalone-compose.py` (CI guard).
- Docs are `.mdx` only under `docs/`; never create `docs/**/mcp.mdx`.
- Release notes: latest in `release-notes.md`, archived copy in `release-notes/v<version>.md`,
  user-facing outcomes only, no filenames, env vars or class names.
- Changing a default or replacing a hardcoded constant requires a backward-compat note in the PR.
- Never `docker push`. Releases go out via `git tag` and GitHub Actions.
- Never push to `main`. Every story lands as a PR.
