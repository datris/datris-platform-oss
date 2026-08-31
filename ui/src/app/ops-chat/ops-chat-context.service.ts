import { Injectable } from '@angular/core';

/** Compact, structured snapshot of the dashboard the Ops chat agent should
 *  reason against. Activity publishes this on every loadData; the chat panel
 *  re-injects it as a system message on every turn (per the plan's
 *  ship-the-dumb-version decision on context refresh cadence). */
export interface OpsChatContext {
  window: '24h' | '7d' | '30d';
  failingItems: OpsChatFailing[];
  /** Sourced from the server's GET /api/v1/activity/signals staleTaps (the
   *  same detection the recovery agent runs) when the endpoint responds;
   *  Activity's client-side heuristic is the fallback so the dashboard —
   *  and this snapshot — never break when it errors. */
  staleTaps: OpsChatStale[];
  /** Sourced from the signals endpoint's `anomalies` (server-filtered to
   *  volumes worth reasoning about) when it responds; falls back to the
   *  client-computed per-pipeline volumes sorted by |deltaPct|. */
  pipelineVolumes: OpsChatVolume[];
}

export interface OpsChatFailing {
  kind: 'tap' | 'pipeline';
  name: string;
  catalog: string | null;
  reason: string;
  timeIso: string | null;
  recovered: boolean;
  failureCount: number;
  relatedTapName: string | null;
  /** Per-job ingestion token for pipeline-kind failures. The agent uses this
   *  to call `get_pipeline_status(pipeline_token=...)` and read the full
   *  per-job event trail — including the AI-explained root cause that the
   *  dashboard surfaces when you expand the row. Without this, the agent
   *  has no way back to a specific failed job once its retry has
   *  superseded it in the rollup. Null for tap-kind failures (they carry
   *  their error in the tap log, which the agent already reads via
   *  `get_tap_logs`). */
  pipelineToken: string | null;
}

export interface OpsChatStale {
  name: string;
  catalog: string | null;
  cadenceLabel: string;
  lastRunIso: string | null;
}

export interface OpsChatVolume {
  name: string;
  catalog: string | null;
  current: number;   // records in the selected window (matches `window` above)
  prior: number;     // records in the equal-length prior window
  deltaPct: number | null;
}

/** Singleton bridge between the Activity dashboard and the Ops chat panel.
 *  Activity calls publish() after each load; the chat panel reads snapshot()
 *  before each user message and forwards it to the server.
 *
 *  Only Activity publishes today (Ingestion's context shape isn't defined
 *  yet — deferred per the plan). When the user opens the chat from
 *  Ingestion before Activity has loaded, snapshot() returns null and the
 *  server falls back to the no-context behavior. */
@Injectable({ providedIn: 'root' })
export class OpsChatContextService {
  private current: OpsChatContext | null = null;

  publish(ctx: OpsChatContext): void {
    this.current = ctx;
  }

  snapshot(): OpsChatContext | null {
    return this.current;
  }
}
