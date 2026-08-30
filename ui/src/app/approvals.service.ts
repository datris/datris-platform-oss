import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError, map, shareReplay } from 'rxjs/operators';

/** Actor that made the gated request — same shape the audit log uses. */
export interface ApprovalActor {
  type: 'user' | 'api-key' | 'assistant' | 'tap' | 'system';
  label: string;
  keyLabel?: string;
  keyId?: string | null;
  username?: string;
  role?: string;
}

export type ApprovalState = 'pending' | 'approved' | 'rejected' | 'expired' | 'executed' | 'failed';

/** One queued (or decided) agent action as returned by GET /api/v1/approvals. */
export interface Approval {
  id: string;
  action: string;
  resourceType?: string;
  resource?: string;
  resourceVersion?: number;
  actor: ApprovalActor;
  reason?: string;
  agentSession?: string;
  request: { method: string; path: string; query?: string; contentType?: string; body?: any };
  createdAt: string;
  expiresAt: string;
  state: ApprovalState;
  decidedBy?: string;
  decidedAt?: string;
  decisionNote?: string;
  executedAt?: string;
  resultStatus?: number;
  resultBody?: string;
  /** Present on 409 conflict responses (stale resource / already decided). */
  error?: string;
  errorKind?: string;
  liveVersion?: number;
  message?: string;
}

export interface ApprovalsPage {
  enabled: boolean;
  approvals: Approval[];
}

/** Shape of a tool result the chats receive when the agent's call was gated
 *  by policy. `status: "pending_approval"` means the action is queued for a
 *  person; `errorKind: "policy_denied"` means it was refused outright. */
export interface PolicyToolOutcome {
  kind: 'pending' | 'denied';
  approvalId?: string;
  action?: string;
  resource?: string;
  resourceType?: string;
  message?: string;
}

/** Shared by the Activity → Approvals section and both chats. */
@Injectable({ providedIn: 'root' })
export class ApprovalsService {
  private enabled$?: Observable<boolean>;

  constructor(private http: HttpClient) {}

  /** USE_AGENT_POLICY flag from /api/v1/version, fetched once per app life. */
  policyEnabled(): Observable<boolean> {
    if (!this.enabled$) {
      this.enabled$ = this.http.get<any>('/api/v1/version').pipe(
        map(v => String(v?.useAgentPolicy) === 'true'),
        catchError(() => of(false)),
        shareReplay(1)
      );
    }
    return this.enabled$;
  }

  list(state?: string, limit?: number): Observable<ApprovalsPage> {
    let p = new HttpParams();
    if (state) p = p.set('state', state);
    if (limit) p = p.set('limit', String(limit));
    return this.http.get<ApprovalsPage>('/api/v1/approvals', { params: p });
  }

  get(id: string): Observable<Approval> {
    return this.http.get<Approval>('/api/v1/approvals/' + encodeURIComponent(id));
  }

  approve(id: string): Observable<Approval> {
    return this.http.post<Approval>('/api/v1/approvals/' + encodeURIComponent(id) + '/approve', {});
  }

  reject(id: string, note?: string): Observable<Approval> {
    const body = note && note.trim() ? { note: note.trim() } : {};
    return this.http.post<Approval>('/api/v1/approvals/' + encodeURIComponent(id) + '/reject', body);
  }

  /** Parse a chat tool-result string for a policy outcome. Returns null for
   *  anything that isn't a pending-approval or policy-denied envelope. */
  static parseToolOutcome(result: string): PolicyToolOutcome | null {
    if (!result || typeof result !== 'string') return null;
    const trimmed = result.trim();
    if (!trimmed.startsWith('{')) return null;
    let obj: any;
    try {
      obj = JSON.parse(trimmed);
    } catch {
      return null;
    }
    if (!obj || typeof obj !== 'object') return null;
    // MCP-style envelopes wrap the payload in content[0].text — unwrap once.
    if (Array.isArray(obj.content) && obj.content.length && typeof obj.content[0]?.text === 'string') {
      try {
        const inner = JSON.parse(obj.content[0].text);
        if (inner && typeof inner === 'object') obj = inner;
      } catch { /* keep outer */ }
    }
    const pick = (o: any, k: string): string | undefined =>
      typeof o?.[k] === 'string' && o[k] ? o[k] : undefined;
    if (obj.status === 'pending_approval') {
      return {
        kind: 'pending',
        approvalId: pick(obj, 'approvalId') || pick(obj, 'id'),
        action: pick(obj, 'action'),
        resource: pick(obj, 'resource'),
        resourceType: pick(obj, 'resourceType'),
        message: pick(obj, 'message')
      };
    }
    if (obj.errorKind === 'policy_denied') {
      return {
        kind: 'denied',
        action: pick(obj, 'action'),
        resource: pick(obj, 'resource'),
        resourceType: pick(obj, 'resourceType'),
        message: pick(obj, 'message') || pick(obj, 'error')
      };
    }
    return null;
  }
}
