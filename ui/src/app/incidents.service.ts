import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError, map, shareReplay } from 'rxjs/operators';

export type IncidentKind = 'tap_failure' | 'pipeline_failure' | 'stale' | 'volume';

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
  closedAt?: string;
  outcome?: string;
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
