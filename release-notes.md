# Release Notes

## v1.16.2 — August 13, 2026

**Azure OpenAI without API keys — Microsoft Entra ID authentication.**

Azure OpenAI now works keyless. If your organization disables API keys on its Azure resources (`disableLocalAuth`, often set tenant-wide by policy), Entra ID is the only way in — and Datris now supports it end to end. It's also the right choice anywhere you'd rather rotate credentials centrally than store provider keys.

Two keyless modes, chosen in **Configuration → AI Providers** under the Azure OpenAI credentials section:

- **Service principal** — works for any Datris deployment. Enter your Entra Tenant ID, Client ID, and Client Secret once; tokens are acquired and refreshed automatically. Centrally rotatable and RBAC-scoped.
- **Managed identity** — zero stored secrets. When Datris runs on Azure compute (VM, AKS, App Service), it authenticates as the server's managed identity with nothing to enter at all.

Either way, grant the identity the **Cognitive Services OpenAI User** role on your Azure OpenAI resource. Chat, CodeGen, embeddings, and the Assistant all honor the same authentication — and a stored API key always takes precedence, so switching modes in the UI safely clears the one you're leaving.

Keyless setup also works at install time: leave the API key out of `.env` and provide the service-principal values (or nothing, on Azure compute with a managed identity). See the [AI Configuration guide](https://docs.datris.ai/ai-configuration#keyless-auth-microsoft-entra-id) for details.

**Upgrading**

Standard upgrade: `docker compose pull && docker compose up -d`. No configuration changes required — existing Azure OpenAI API-key setups keep working exactly as before.

---

## v1.16.1 — August 12, 2026

**Reliability: long AI generations now complete, and the Assistant shows live progress.**

See the [full v1.16.1 notes](release-notes/v1.16.1.md) for details.
