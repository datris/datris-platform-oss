# Release Notes

## v1.6.10 — April 21, 2026

**Tap prompt fragments, post-run script review, BYO code, and Configuration page reorg.**

- **Tap prompt fragments (new).** A new Configuration → Taps sub-tab lets you save reusable, per-tenant context snippets — things like API conventions, required headers, rate limits, and preferred libraries for a given source. When the key or any of its aliases appears in a Create Tap description, brainstorm, auto-fix, optimize, or Discovery chat, the fragment's content is automatically added to the system prompt. Includes an AI Suggest button, a Load Examples catalog (AWS, Polygon, Stripe, SEC EDGAR), JSON import/export, and an "Extra context applied" chip row in the tap wizard showing which fragments hit.
- **Post-run script review.** After a tap's first successful test, the AI now scans the captured stderr/stdout for signals that the *script* should change — rate-limit or burst warnings, deprecation hints, pagination cues, schema drift, auth warnings — and regenerates the script if needed. On a rewrite the wizard auto-retests; the performance optimizer runs only when the logs are clean. The optimizer's prompt was also tightened so rate-limit markers push it toward throttling instead of more concurrency.
- **I Have My Own Code (new).** A third Tap Type on the Create Tap wizard lets you paste a fetch() script directly instead of having AI generate one. Step 1 switches to a code textarea with a Use My Code button; after upload the button flips to Re-upload My Code and Step 2 gates on the text matching what's on disk, so edits force a fresh upload before running the test.
- **Configuration page reorganized into three sub-tabs** — Environment, AI Providers, and Taps — with a prominent "Highly recommended: Anthropic with the latest coding model" tip on the CodeGen Provider section.
- **Tap Name collision warning.** If the name you type in the Create Tap wizard matches an existing tap, an amber banner appears under the field warning that continuing will overwrite the existing tap's configuration and script.
- **Auto-fix retries bumped to 3.** When a tap script fails its first test, the AI now gets up to three repair attempts (was two) before giving up.
- **Cron Custom preset no longer blocked by AI formatting.** AI-generated cron expressions wrapped in code fences, brackets, or quotes are now cleaned automatically, so the Next button is enabled on valid output.

---

See [archived release notes](release-notes/) for prior versions.
