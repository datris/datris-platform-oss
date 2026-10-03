# Release Notes

## v1.42.0 — October 3, 2026

**Field protection for PII and PHI, reveal for authorized users, and tighter rules for secrets.**

- **Protect sensitive columns before they land anywhere.** Mark a field in a pipeline and its original value never reaches a destination, a preview, or an AI step. Choose per field: a stable one-way pseudonym that still supports joins and grouping, a mask that keeps only the part you pick (last four characters, an email domain, a year), a fixed redaction, dropping the column entirely, or encryption. Protection runs on your server before data quality and transformation, so AI-assisted rules and transformations only ever see protected values. Once the protected copy exists, the raw copies Datris held for that run are removed. See [Field Protection](/transformation/field-protection).
- **Encrypt what an authorized person may need to see again.** Encrypted fields are stored as ciphertext. Someone with the reveal permission can get the original back; every reveal is recorded in the audit log, and no user ever holds a key. No built-in role or key template includes that permission, so it has to be granted on purpose. Keys can be rotated without losing access to older rows.
- **Reveal from the Search tab.** Encrypted values show as a lock in query results for Databricks, Snowflake, object store, PostgreSQL and MongoDB. An admin can reveal the values in the current result with one click. Nothing is kept afterwards: run the query again and the locks are back. Assistants and agents cannot reveal.
- **Suggestions for what to protect.** Datris can propose a protection method for each field from its name and type alone. No data is sent, nothing is applied until you accept it, and the suggestions respect the rules below.
- **Protection in the pipeline wizard and for agents.** The schema step has a Protect column with an explanation of each option and a Suggest button. Agents can set protection when they create a pipeline and can ask for suggestions, and are told to confirm the fields with you first. The pipeline page lists protected fields, and lineage shows which columns were transformed or dropped.
- **Rules that keep protected data usable.** A key column can only use the stable pseudonym, so upserts keep matching the right rows. Methods that produce text require text columns. Unsupported or mistyped methods are refused when the pipeline is saved, never at run time with data in flight.
- **Security: stronger protection for stored secrets.** Taps can now only use tap secrets, the keys Datris manages for itself cannot be used by a tap or pipeline or changed through the secrets screen, and secret names are checked more strictly. We recommend upgrading.
- **Databricks loads are no longer reported as failed when they succeeded.** In some cases a load that completed was marked as an error and its follow-up steps were skipped. The run now finishes normally.
- **MongoDB results read as a table in Search.** Flat documents are shown in the same grid as other query types; nested documents keep the JSON view.
- **`datris doctor` checks tap secrets.** It lists any tap that points at a secret it is no longer allowed to use.

**Upgrading**

Run `docker compose pull && docker compose up -d --force-recreate`. The server, UI and MCP server images changed.

- **Taps and secrets.** A tap whose secret is not a tap secret now fails its next run with a message naming the tap and the secret; saving such a tap is refused. Taps created in the wizard or by an agent already use tap secrets and are unaffected, as are pipelines and destinations. To fix an affected tap, create a tap secret with the fields it needs and select it. To keep the previous behaviour, set `DATRIS_TAP_SECRET_SCOPE=any`. The server logs affected taps at startup and `datris doctor` lists them. See [Taps](/taps#taps-can-only-use-tap-secrets).
- **Secret names.** Names containing spaces or URL-special characters are refused. Such names did not work reliably before.
- **Protected pipelines remove their source file.** After a protected run, the file the run was read from is deleted from the ingest bucket, so a failed run has to be re-uploaded or re-fetched. A pipeline can opt out. Pipelines without protection are unchanged.
- No other configuration changes are required. Existing pipelines behave as before until you add protection to a field.
