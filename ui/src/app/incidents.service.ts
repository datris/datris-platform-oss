import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError, map, shareReplay } from 'rxjs/operators';

export type IncidentKind = 'tap_failure' | 'pipeline_failure' | 'stale' | 'volume' | 'schema_drift';

// ── Incident evidence (server: ai.datris.incident.IncidentEvidence.toJson) ──
// Deterministic, platform-computed. Units: record deltas and
// `maxRecordDeltaPct` are percentages; null rates and DQ pass rates are
// fractions (0.0-1.0). Optional fields are omitted by the server when absent.

export type EvidenceVerdict = 'pass' | 'fail' | 'needs-human' | 'pending';

export interface Quantiles {
  p1: number;
  p25: number;
  p50: number;
  p75: number;
  p99: number;
}

export interface ColumnStats {
  name: string;
  inferredType: string;
  nullRate: number;
  distinct: number;
  quantiles?: Quantiles;
  lengthQuantiles?: Quantiles;
  /** Only present for columns the server deems safe. The UI never displays
   *  these values. */
  topK?: { value: string; count: number }[];
}

export interface RunProfile {
  runId: string;
  source: string; // landed-sample | scratch | snapshot | lineage-only
  recordCount: number;
  columns: ColumnStats[];
  dqFailures: number;
  dqPassRate: number;
  durationMs: number;
  scriptSha?: string;
  tapVersion?: number;
  pipelineVersion?: number;
  sampledRows: number;
  computedAt: string;
}

export interface ProfileDiff {
  columnsAdded: string[];
  columnsRemoved: string[];
  typeChanges: { column: string; from: string; to: string }[];
  recordDeltaPct?: number;
  recordDeltaVsMeanOfLastN?: number;
  lastN: number;
  nullRateDeltas: { column: string; delta: number }[];
  quantileShift: { column: string; shift: number }[];
  dqPassRateDelta?: number;
}

export interface BlastRadius {
  downstreamPipelines: string[];
  downstreamDatasets: { kind: string; coords: string }[];
  ucTables: string[];
  consumersFromContracts?: string[];
  lastDownstreamRunAt?: string;
  /** Absent (or null) when reads are not audited — readers unknown. */
  agentsThatQueriedLast7d?: string[] | null;
  secretName?: string;
}

export interface Thresholds {
  maxRecordDeltaPct: number;
  allowColumnRemoval: boolean;
  allowTypeChange: boolean;
  minDqPassRate?: number;
  maxNullRateIncrease: number;
}

export interface ThresholdCheck {
  name: string;
  passed: boolean;
  observed: string;
  limit: string;
}

export interface IncidentEvidence {
  baseline?: RunProfile;
  candidate?: RunProfile;
  real?: RunProfile;
  diff?: ProfileDiff;
  blastRadius: BlastRadius;
  thresholds: Thresholds;
  checks: ThresholdCheck[];
  verdict: EvidenceVerdict;
  computedAt: string;
}

/** Full lifecycle of a recovery-agent incident. `open` through `verifying`
 *  are active states; `resolved` / `failed` / `abandoned` are terminal. */
export type IncidentState =
  | 'open' | 'diagnosing' | 'proposed' | 'awaiting_approval'
  | 'executing' | 'verifying'
  | 'resolved' | 'failed' | 'abandoned';

/** One entry in an incident's timeline — the agent appends a step for every
 *  phase transition (open → diagnose → propose → gate → execute → verify →
 *  close). Steps that queued a gated action carry the approval's id. */
export interface IncidentStep {
  ts: string;
  phase: 'open' | 'diagnose' | 'propose' | 'gate' | 'execute' | 'verify' | 'close';
  summary: string;
  detail?: string;
  approvalId?: string;
}

/** One incident as returned by GET /api/v1/incidents. */
export interface Incident {
  id: string;
  kind: IncidentKind;
  resourceType: 'tap' | 'pipeline';
  resource: string;
  openedAt: string;
  state: IncidentState;
  trigger: any;
  steps: IncidentStep[];
  classification?: string;
  proposal?: any;
  aiCalls: number;
  actionsTaken: number;
  awaitingApprovalIds?: string[];
  /** Tap version to restore if verification fails (pre-incident version). */
  revertToVersion?: number;
  closedAt?: string;
  outcome?: string;
  /** Absent on incidents opened before evidence existed. */
  evidence?: IncidentEvidence;
  verdict?: EvidenceVerdict;
}

export interface IncidentsPage {
  enabled: boolean;
  incidents: Incident[];
}

// ── Server-side dashboard signals (GET /api/v1/activity/signals) ────────────
// The same detection the recovery agent runs on the server, exposed so the
// dashboard's chat context (and optionally its panels) agree with what the
// agent sees.

export interface SignalFailing {
  kind: 'tap' | 'pipeline';
  name: string;
  catalog?: string;
  reason: string;
  timeIso?: string;
  recovered: boolean;
  failureCount: number;
  pipelineToken?: string;
  relatedTapName?: string;
}

export interface SignalStaleTap {
  name: string;
  catalog?: string;
  cadenceLabel: string;
  cadenceMs: number;
  lastRunIso?: string;
}

export interface SignalVolume {
  name: string;
  catalog?: string;
  current: number;
  prior: number;
  priorRuns: number;
  deltaPct?: number;
  anomaly: boolean;
}

export interface ActivitySignals {
  windowMs: number;
  computedAt: string;
  failing: SignalFailing[];
  staleTaps: SignalStaleTap[];
  volumes: SignalVolume[];
  /** `volumes` filtered to anomalous entries only. */
  anomalies: SignalVolume[];
}

/** Shared by the Activity → Incidents section. Same style as ApprovalsService. */
@Injectable({ providedIn: 'root' })
export class IncidentsService {
  private enabled$?: Observable<boolean>;

  constructor(private http: HttpClient) {}

  /** RECOVERY_AGENT flag from /api/v1/version, fetched once per app life. */
  recoveryEnabled(): Observable<boolean> {
    if (!this.enabled$) {
      this.enabled$ = this.http.get<any>('/api/v1/version').pipe(
        map(v => String(v?.recoveryAgentEnabled) === 'true'),
        catchError(() => of(false)),
        shareReplay(1)
      );
    }
    return this.enabled$;
  }

  /** Newest first. `state` may be "open" (any active state) or a specific one. */
  list(state?: string, limit?: number): Observable<IncidentsPage> {
    let p = new HttpParams();
    if (state) p = p.set('state', state);
    if (limit) p = p.set('limit', String(limit));
    return this.http.get<IncidentsPage>('/api/v1/incidents', { params: p });
  }

  get(id: string): Observable<Incident> {
    return this.http.get<Incident>('/api/v1/incidents/' + encodeURIComponent(id));
  }

  /** Evidence for one incident (404 when the incident has none). */
  evidence(id: string): Observable<IncidentEvidence> {
    return this.http.get<IncidentEvidence>('/api/v1/incidents/' + encodeURIComponent(id) + '/evidence');
  }

  /** Close an active incident without further agent action. 409 when the
   *  incident already reached a terminal state. Admin/editor only. */
  abandon(id: string): Observable<Incident> {
    return this.http.post<Incident>('/api/v1/incidents/' + encodeURIComponent(id) + '/abandon', {});
  }

  /** Server-computed dashboard signals for the given window (24h/7d/30d). */
  signals(window: '24h' | '7d' | '30d'): Observable<ActivitySignals> {
    const p = new HttpParams().set('window', window);
    return this.http.get<ActivitySignals>('/api/v1/activity/signals', { params: p });
  }
}
