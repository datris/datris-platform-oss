package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** Per-tenant code-repository connection for tap script storage. Single
  * document keyed `name = "default"` in `<env>-code-repo` (one repo per
  * tenant in v1; the key leaves multi-repo open). The auth token itself
  * lives in Vault under `authSecretName` — never in this document.
  * See plans/tap-github-storage.md.
  */
case class CodeRepoConfig(
    name: String = "default",
    provider: String = "github",
    // "owner/repo"
    repo: String = null,
    // Overridable for GitHub Enterprise Server (https://ghes.example.com/api/v3)
    apiBaseUrl: String = "https://api.github.com",
    branch: String = "main",
    // Script path becomes {pathPrefix}{tapName}.py
    pathPrefix: String = "taps/",
    // Vault secret (tagged _type=repo_token) holding { token: "..." }
    authSecretName: String = null,
    // "Name <email>" used as commit author; falls back to a synthetic author
    commitAuthor: String = "Datris <bot@datris.ai>",
    // Tokens: {name} {action} {user} {tapType}
    commitMessageTemplate: String = "tap({name}): {action} via Datris",
    enabled: Boolean = false,
    createdAt: String = null,
    updatedAt: String = null
)
