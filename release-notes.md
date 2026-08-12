# Release Notes

## v1.16.1 — August 12, 2026

**Reliability: long AI generations now complete, and the Assistant shows live progress.**

This release fixes a hang that could stop tap-script generation at around the five-minute mark, and makes long-running Assistant work visible instead of silent.

- **Large script generations finish reliably.** Generating a big tap script (many sources, many locations) can legitimately take more than five minutes — previously these could time out or hang partway. Generation responses now stream under the hood and get a much larger time budget, so long generations run to completion.
- **Live progress in the Assistant.** Running steps now show an elapsed-time counter once they take more than a few seconds, and after about a minute a short note explains that long AI generations are normal and that temporary provider errors retry automatically — no more wondering whether a quiet spinner is working or stuck.
- **Unresponsive AI providers no longer hang requests.** During a provider outage, AI calls used to wait indefinitely for a response that would never come. They now fail fast and feed the automatic retry ladder, so a transient outage surfaces as a clear, actionable error instead of a frozen screen.

**Upgrading**

Standard upgrade: `docker compose pull && docker compose up -d`. No configuration changes required.

---

## v1.16.0 — August 11, 2026

**Amazon Bedrock support — run Claude through your AWS account.**

Datris now supports Amazon Bedrock as a first-class AI provider alongside Anthropic, OpenAI, Azure OpenAI, and Ollama. If your organization runs on AWS, the chat assistants and code generation can now use Claude models served from your own AWS account — IAM authentication, AWS billing, and no Anthropic API key required.

See the [full v1.16.0 notes](release-notes/v1.16.0.md) for details.
