---
description: Drive one story from branch to reviewed PR — implementer, reviewer loop, optional releaser
argument-hint: <plans/stories/x.md | issue URL> [--release <version>]
---

You are the lead. Drive the story at `$ARGUMENTS` to a reviewed pull request. You delegate; you
do not implement or review yourself.

## 0. Load the story
- If the argument is a GitHub issue URL, `gh issue view <url> --json title,body -q .body` and save
  it to `plans/stories/issue-<number>.md`. Otherwise read the file.
- Refuse if any required section is missing. Print which, and stop.
- Extract **Branch** from the story.

## 1. Claim and branch
- `git status --porcelain` must be empty. Stop if not.
- `git checkout main && git pull --ff-only`, then `git checkout -b <branch>` (or `git checkout
  <branch>` if it exists and is ahead of main only by story commits).
- Set the story's **Status** line to `claimed`.

## 2. Implement
Spawn `implementer` with the story path and the branch. Wait for its report.
- `STATUS: blocked` → set story Status to `proposed`, print the report, stop.

## 3. Review loop (max 3 rounds)
Spawn `reviewer` with the story path. Wait.
- `VERDICT: PASS` → go to 4.
- `VERDICT: FAIL` → continue the **same** implementer agent (SendMessage by its ID) with the
  reviewer's FINDINGS verbatim and the instruction "fix only these, then rerun Verify". Wait,
  then spawn a fresh reviewer. Repeat.
- After 3 FAILs → set Status to `in-review`, print the last findings, stop. A human decides.

## 4. Release prep (only if `--release <version>` was given)
Spawn `releaser` with the version. Wait. Stop if its VERIFY is not all pass.

## 5. Pull request
- `git push -u origin <branch>`
- `gh pr create --base main --title "<story title>" --body-file -` with body: Goal paragraph,
  Acceptance checklist (ticked), reviewer's final VERDICT block, backward-compat note if the
  implementer gave one, and the trailer
  `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- Set story Status to `in-review` and add `**PR:** <url>`.

## Rules
- Never merge. Never tag. Never push to main. Never stage anything under `plans/`.
- Relay reports verbatim between agents; do not paraphrase findings.
- Final message to the human: story title, branch, PR URL, rounds of review, and any
  backward-compat note. Nothing else.
