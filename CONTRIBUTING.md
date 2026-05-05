# Contributing to Datris

Thanks for your interest in Datris. This guide explains how to file feedback and contribute changes.

## Where to send what

| Type of feedback | Where to send it |
|---|---|
| Confirmed bug with repro steps | [New issue → Bug Report](../../issues/new?template=bug_report.yml) |
| Concrete feature request | [New issue → Feature Request](../../issues/new?template=feature_request.yml) |
| Question, "is this a bug?", or open-ended idea | [Discussions](../../discussions) |
| Security vulnerability | [SECURITY.md](SECURITY.md) — **do not** file a public issue |

## Reporting bugs

The bug-report template will ask for your Datris version, component, deployment type, and reproduction steps. Please fill it out completely — vague reports take much longer to triage.

## Proposing features

For early-stage ideas, post in [Discussions → Ideas](../../discussions/categories/ideas) first. Thumbs-up reactions act as lightweight upvoting. Once an idea is concrete enough to scope, it can be promoted to an issue.

## Submitting pull requests

1. Fork the repo and create a branch off `main`
2. Make your changes — keep PRs focused (one logical change per PR)
3. Update docs in `docs/` if user-visible (Mintlify `.mdx` only)
4. Update `release-notes.md` if the change ships in the next release
5. Open a PR with a clear description of the problem and the fix

PRs that touch the public API, MCP tools, or persistence layer should describe backward-compatibility implications.

## Local development

See [docs.datris.ai](https://docs.datris.ai) for the developer setup. The short version:

- `sbt clean assembly` builds the server jar
- `docker compose up -d` runs the full stack locally
- Full rebuild after server changes: `sbt clean assembly` → `docker compose build --no-cache` → `docker compose up -d`

## Code of conduct

Be respectful. Assume good faith. Keep discussions focused on the work.
