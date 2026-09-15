---
description: Write a self-contained story from an idea, plan file, or issue URL using the planner role
argument-hint: <idea | plans/foo.md | issue URL>
---

Spawn the `planner` agent with this input: $ARGUMENTS

Tell it to write the story to `plans/stories/<slug>.md`, where slug is a short kebab-case name
derived from the title. If the input is a GitHub issue URL, fetch the body first with
`gh issue view <url> --json title,body` and pass it along as the Source.

When the planner returns, read the story back and check it has every required section
(Goal, Files, Steps, Acceptance, Verify, Out of scope) and that every path under Files exists or
is clearly marked new. Send it back once with the gaps if not. Then print the story path and the
first ten lines.
