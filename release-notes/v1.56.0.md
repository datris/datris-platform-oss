# Release Notes

## v1.56.0 — November 3, 2026

**Self-healing stops re-checking the same benign volume spike, and recovery mode is set where you manage the pipeline.**

- **No more repeat triage of a benign volume signal.** When the recovery agent has already looked at a pipeline's volume change and closed it as benign, the incident sweep no longer re-opens an incident for that same signal every few minutes. A genuinely new change in volume still opens one. On an instance where one pipeline's feed kept tripping the volume check, this removes hundreds of unnecessary AI calls a day.
- **Prompt caching for Anthropic.** Chat and agent requests sent directly to Anthropic now mark the system prompt and tool list for prompt caching, so repeat calls in a session reuse the cached prefix at a fraction of the input cost. The server log shows the cache token counts for each call. Bedrock, OpenAI, Azure and Grok requests are unchanged.
- **Set self-healing per pipeline or tap from its page.** The pipeline page, the pipeline edit page and the tap run page now show a **Self-healing** row with the effective recovery mode and where it comes from (instance default or override). Admins can switch it to Off, Propose or Autopilot right there, or send it back to the instance default. The Agent Policy page's override editor shows these recovery entries properly instead of a blank row.

**Upgrading:** `docker compose pull && docker compose up -d`; the server and UI images changed. No configuration changes are required. CLI users: `pip install -U datris-mcp-server` or `brew upgrade datris`.
