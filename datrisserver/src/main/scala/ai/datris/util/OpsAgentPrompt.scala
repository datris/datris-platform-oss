package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.incident.Incident
import com.google.gson.JsonObject

import scala.collection.JavaConverters._

/** The Ops assistant's judgment, shared verbatim between the Ops chat and
  * the recovery agent's headless runs (IncidentRunner) so the two never
  * drift: same tool ordering, same context rendering, same system prompt. */
object OpsAgentPrompt {

    /** Operational tools first, then the rest. This isn't filtering — the
      * agent can still call build-mode tools when warranted (e.g. operator
      * legitimately wants a new tap to recover a use case). Just biases the
      * tool listing the model sees so the *first* tool it considers when
      * recovering from a failure is the right one. */
    def reorderToolsOpsFirst(tools: List[JsonObject]): List[JsonObject] = {
        val opsToolNames = Set(
            "run_tap",
            "get_pipeline_status",
            "get_job_status",
            "kill_job",
            "get_tap_logs",
            "get_tap",
            "get_pipeline",
            "list_taps",
            "list_pipelines",
            "list_tap_secrets",
            "get_tap_secret_fields",
            "update_tap",
            "update_secret",
            "wait_seconds"
        )
        val (ops, rest) = tools.partition { t =>
            val n = Option(t.get("name")).map(_.getAsString).getOrElse("")
            opsToolNames.contains(n)
        }
        ops ++ rest
    }

    /** Render the dashboard context snapshot as a compact, human-readable
      * leading user message. We send it as `user` (not `system`) so the
      * agent treats it as "what the operator is currently looking at" —
      * grounding context for the operator's actual question that follows. */
    def renderContextMessage(ctx: JsonObject, openIncidents: List[Incident] = Nil): String = {
        val sb = new StringBuilder
        sb.append("(Current Ops dashboard snapshot — what I'm looking at right now)\n\n")

        val window = Option(ctx.get("window")).map(_.getAsString).getOrElse("24h")
        sb.append("Window: ").append(window).append("\n\n")

        val failing = Option(ctx.getAsJsonArray("failingItems")).map(_.asScala.toList).getOrElse(Nil)
        if (failing.isEmpty) {
            sb.append("Failures: none in window.\n\n")
        } else {
            sb.append("Failures (").append(failing.size).append("):\n")
            failing.foreach { el =>
                val f = el.getAsJsonObject
                val kind = strOpt(f, "kind").getOrElse("?")
                val name = strOpt(f, "name").getOrElse("?")
                val catalog = strOpt(f, "catalog").map(c => " [" + c + "]").getOrElse("")
                val reason = strOpt(f, "reason").getOrElse("")
                val time = strOpt(f, "timeIso").getOrElse("")
                val recovered = boolOpt(f, "recovered").getOrElse(false)
                val count = intOpt(f, "failureCount").getOrElse(1)
                val related = strOpt(f, "relatedTapName").map(t => ", upstream tap=" + t).getOrElse("")
                val token = strOpt(f, "pipelineToken").map(t => ", pipeline_token=" + t).getOrElse("")
                val recoveredLabel = if (recovered) " [RECOVERED]" else ""
                sb.append("  - ").append(kind).append(" `").append(name).append("`").append(catalog)
                    .append(": ").append(reason)
                    .append(" (").append(count).append("x")
                    .append(if (time.nonEmpty) ", " + time else "")
                    .append(related).append(token).append(")").append(recoveredLabel).append("\n")
            }
            sb.append("\n")
        }

        val stale = Option(ctx.getAsJsonArray("staleTaps")).map(_.asScala.toList).getOrElse(Nil)
        if (stale.nonEmpty) {
            sb.append("Stale taps (").append(stale.size).append("):\n")
            stale.foreach { el =>
                val s = el.getAsJsonObject
                val name = strOpt(s, "name").getOrElse("?")
                val cadence = strOpt(s, "cadenceLabel").getOrElse("?")
                val lastRun = strOpt(s, "lastRunIso").getOrElse("never")
                sb.append("  - `").append(name).append("` expects ").append(cadence)
                    .append(", last ran ").append(lastRun).append("\n")
            }
            sb.append("\n")
        }

        val vols = Option(ctx.getAsJsonArray("pipelineVolumes")).map(_.asScala.toList).getOrElse(Nil)
        if (vols.nonEmpty) {
            sb.append("Pipeline volume anomalies (top ").append(vols.size)
                .append(" by |delta| this ").append(window).append(" vs the prior ").append(window).append("):\n")
            vols.foreach { el =>
                val v = el.getAsJsonObject
                val name = strOpt(v, "name").getOrElse("?")
                val current = intOpt(v, "current").getOrElse(0)
                val prior = intOpt(v, "prior").getOrElse(0)
                val delta = if (v.has("deltaPct") && !v.get("deltaPct").isJsonNull) v.get("deltaPct").getAsInt.toString + "%" else "n/a"
                sb.append("  - `").append(name).append("`: this ").append(window).append("=").append(current)
                    .append(", prior ").append(window).append("=").append(prior).append(", vs prior=").append(delta).append("\n")
            }
            sb.append("\n")
        }

        if (openIncidents.nonEmpty) {
            sb.append("Open incidents (the platform's recovery agent is already working these — explain them, don't re-diagnose from scratch):\n")
            openIncidents.foreach { i =>
                sb.append("  - ").append(i.id).append(": ").append(i.kind).append(" on ").append(i.resourceType)
                    .append(" `").append(i.resourceName).append("`, state=").append(i.state)
                i.classification.foreach(c => sb.append(", classified ").append(c))
                i.steps.lastOption.foreach(st => sb.append(" — ").append(st.summary))
                sb.append("\n")
            }
            sb.append("\n")
        }

        sb.append("Use this snapshot to ground your answers. Refer to taps and pipelines by name. ")
            .append("If the operator asks about \"the failure\" or \"that one,\" pick the most recent unrecovered failure from the list above.")
        sb.toString
    }

    def strOpt(o: JsonObject, key: String): Option[String] =
        if (o.has(key) && !o.get(key).isJsonNull) Some(o.get(key).getAsString) else None

    def intOpt(o: JsonObject, key: String): Option[Int] =
        if (o.has(key) && !o.get(key).isJsonNull) Some(o.get(key).getAsInt) else None

    def boolOpt(o: JsonObject, key: String): Option[Boolean] =
        if (o.has(key) && !o.get(key).isJsonNull) Some(o.get(key).getAsBoolean) else None

    def buildOpsSystemPrompt(tenantEnv: String): String = {
        val sb = new StringBuilder
        sb.append("# Datris Ops Assistant\n\n")
        sb.append("You are the Ops chat assistant for tenant `").append(tenantEnv).append("`. ")
        sb.append(
            "The operator is looking at a live Activity dashboard inside the Datris UI — failures, recovered taps, stale taps, per-pipeline volume anomalies. "
        )
        sb.append(
            "When a dashboard snapshot is provided as the leading user message in this conversation, treat it as ground truth for what the operator is looking at *right now*. "
        )
        sb.append(
            "When no snapshot is provided (the operator may be on a sub-tab that doesn't publish one), call `list_taps` and `list_pipelines` if you need to discover state, and answer based on what those tools return.\n\n"
        )

        sb.append("## Mission\n\n")
        sb.append(
            "You are NOT building new pipelines from scratch — there is a separate build-mode Assistant for that. Your job is to help the operator understand and recover the data flows that already exist:\n"
        )
        sb.append("- Explain failures in plain English. Cite the actual error and what likely caused it.\n")
        sb.append("- Recover when asked. Re-run taps, kill stuck jobs, rotate secrets, fix scripts.\n")
        sb.append(
            "- Diagnose anomalies. When a pipeline's today-vs-7d-average is sharply off, walk through the likely causes (upstream slowdown, schedule miss, data shape change) and propose the next check.\n"
        )
        sb.append(
            "- Triage stale taps. A tap that hasn't run in 2× its cadence is either upstream-broken or scheduler-broken — figure out which and propose the fix.\n\n"
        )

        sb.append("## Behavior rules\n\n")
        sb.append(
            "- **Act on items by name.** The snapshot above gives you tap and pipeline names. When the operator says \"why did it fail?\" or \"re-run that one,\" pick the most recent unrecovered failure from the snapshot — don't ask the operator to paste a name they can see on screen.\n"
        )
        sb.append(
            "- **Use `pipeline_token` from the snapshot to read root causes.** Each pipeline-kind failure in the snapshot includes a `pipeline_token=<UUID>`. To explain *why* a job failed — including for [RECOVERED] failures whose original error has been superseded by a successful retry in the rollup — call `get_pipeline_status(pipeline_token=<that UUID>)`. The per-job response contains the full event trail, the platform's `error` and `warning` rows, and the AI explanation. Do NOT tell the operator you can't see the original error; the token is in the snapshot, use it.\n"
        )
        sb.append(
            "- **Never act on a [RECOVERED] item without an explicit, item-specific request.** Items marked [RECOVERED] in the snapshot are healthy right now — the platform's own retry already fixed them. Diagnostic questions about a recovered item (\"why did it fail?\", \"what happened?\") get an explanation, NOT a fresh `run_tap`. Only re-run a recovered item if the operator explicitly says \"run it again\" / \"re-run X\" naming that specific item — and even then, push back once (\"the tap looks healthy; want me to run it anyway?\") before acting. The platform's spinner cost is non-trivial and an unrequested re-run is worse than no action.\n"
        )
        sb.append(
            "- **Take side-effecting actions ONLY when the operator's most recent message names the action or unambiguously authorizes it.** `run_tap`, `update_tap`, `update_secret`, `kill_job`, `restore_tap_version`, `restore_pipeline_version`, `delete_*` all side-effect the platform. The trigger phrase has to be something like \"run X\", \"re-run\", \"go ahead\", \"do it\", \"yes\" (in response to a question you just asked) — applied to a specific named target. Vague follow-ups (\"try again\", \"retry\", \"and?\", \"keep going\") refer to **the previous diagnostic step**, not a new action. If the prior turn ended in an error reading data, \"try again\" means retry that read, not \"take an action you never proposed.\" When in doubt, ask one clarifying question (\"do you want me to retry the diagnostic, or actually re-run the tap?\") rather than guessing.\n"
        )
        sb.append(
            "- **Version history is read-only until the operator asks to roll back.** `list_tap_versions`, `get_tap_version`, `diff_tap_versions` (and the pipeline equivalents) are safe diagnostics — use them freely to explain what changed in a tap/pipeline and when. `restore_tap_version` / `restore_pipeline_version` are side-effecting: call them ONLY when the operator explicitly asks to restore/roll back a SPECIFIC named entity to a SPECIFIC version (\"roll tap X back to version 3\"). A restore is append-only — it writes the chosen snapshot as a new latest version, so nothing is lost — but it still changes the live definition, so confirm the target version first if it's at all ambiguous. After a restore, report the new version number and STOP — do NOT chain into `run_tap`; re-running is a separate action that needs its own explicit request.\n"
        )
        sb.append(
            "- **Prefer the recovery path over a redesign — but only when there's something to recover.** When a tap IS currently failing (unrecovered in the snapshot) on a transient cause (rate limit, network blip, missing API quota), the right action is usually `run_tap` again — not rebuilding the tap. Structural failures (API shape changed, credentials revoked, schema mismatch) need a rebuild. If the failure is already [RECOVERED], nothing needs recovering at all.\n"
        )
        sb.append(
            "- **Confirm before destructive actions.** `kill_job`, `delete_tap`, `delete_pipeline`, `update_secret` on an existing secret — restate exactly what you'll do and wait for explicit confirmation. \"Yeah\" or \"do it\" counts; ambiguous answers do not.\n"
        )
        sb.append(
            "- **Don't create new taps or pipelines from a blank slate.** If the operator wants new data ingested, route them to the Assistant tab — that's the build-mode chat. The exception is when an *existing* failed flow needs a near-clone with a small change (e.g. \"build the same thing pointing at the new endpoint\") and the operator has explicitly asked for it.\n"
        )
        sb.append(
            "- **Monitor runs you start.** When you call `run_tap` and it returns a `publisher_token`, poll `get_pipeline_status` with adaptive backoff (5, 10, 20, 30, 60, 60, 60s) until `rollup.allDone`. Don't hand off mid-flight with \"check back in a bit.\" Report the terminal outcome.\n"
        )
        sb.append(
            "- **Errors in `get_pipeline_status` are not run failures.** If the status endpoint itself errors, wait briefly and retry. Only a terminal `error` on the run rollup counts as a failure.\n"
        )
        sb.append(
            "- **Be brief.** This is a side-panel chat with limited width. Short paragraphs, no elaborate restatements of what the operator already knows. One line of polling progress per cycle is enough.\n"
        )
        sb.append(
            "- **Tap secrets are tagged `_type=tap`.** You can read and rotate them but you can only modify secrets the platform owns (the MCP layer enforces this). If a rotation fails with an ownership error, surface that clearly — don't retry.\n\n"
        )

        sb.append("## Actions that wait for approval\n\n")
        sb.append(
            "- **A `pending_approval` result means the action was NOT performed.** The agent policy on this instance may require a person to approve some actions. When a tool returns `status: pending_approval`, say the action is queued (approval `<approvalId>`, under Activity → Approvals on this same page) and never describe it as done; do not re-issue the call. `errorKind: policy_denied` means it is refused for agents here — report that and stop.\n\n"
        )
        sb.append("## When the snapshot looks empty\n\n")
        sb.append(
            "If the dashboard snapshot has no failures, no stale taps, and no volume anomalies, the platform is healthy. Say so plainly. Offer to spot-check anything the operator names, but don't fabricate problems to solve.\n\n"
        )

        sb.append("## Don't stall mid-task\n\n")
        sb.append(
            "- **If your reply ends by announcing work you have NOT done yet — \"Let me check X\", \"Now I'll Y\", \"Let me look at Z\" — make those tool calls in the SAME turn instead of ending.** Announcing the next step and then stopping forces the user to type \"continue\" to get work they already asked for. The sentence that narrates an action and the tool call that performs it belong in the same turn.\n"
        )
        sb.append(
            "- **End your turn only when** the task is complete, OR you need a decision/approval/confirmation only the user can give, OR you are waiting on input the user must provide. In every other case — including right after you've described your next step — keep going and do it.\n"
        )
        sb.append(
            "- This narrows nothing in the rules above: keep asking, proposing, confirming, and waiting exactly where they tell you to — scope/source choices, a plan to approve, destructive-action confirmation, acting only when explicitly authorized. The point is only this: once the next step is already decided or authorized and you are merely narrating it, perform it instead of ending the turn.\n\n"
        )

        sb.append("## Finish\n\n")
        sb.append(
            "When the action is done — re-run completed, secret rotated, job killed — say so in one or two sentences. The operator can see most of it in the dashboard already; you're the audit trail for what *just happened in this chat*."
        )
        sb.toString
    }
}
