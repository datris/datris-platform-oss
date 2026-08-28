import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

/** One audit-log entry as returned by GET /api/v1/audit-log. */
export interface AuditEntry {
  id: string;
  ts: string;
  actor: {
    type: 'user' | 'api-key' | 'assistant' | 'tap' | 'system';
    label: string;
    keyLabel?: string;
    keyId?: string | null;
    username?: string;
    role?: string;
    legacyFullAccess?: boolean;
  };
  category: string;
  action: string;
  resource?: { type?: string; name?: string };
  outcome: 'success' | 'failure' | 'denied';
  httpStatus?: number;
  durationMs?: number;
  errorMessage?: string;
  request?: { method: string; path: string; query?: string; ip?: string; userAgent?: string };
  metadata?: Record<string, unknown>;
}

export interface AuditPage {
  entries: AuditEntry[];
  nextCursor?: string;
  enabled: boolean;
}

export interface AuditFacets {
  categories: string[];
  actions: string[];
  actors: string[];
  actorTypes: string[];
  windowDays: number;
}

export interface AuditStatus {
  enabled: boolean;
  retentionDays?: number;
  logReads?: boolean;
  emitLogLine?: boolean;
  dropped: number;
  queueDepth: number;
}

/** Filter state shared by list and export. Empty strings mean "any". */
export interface AuditFilter {
  since?: string;
  until?: string;
  category?: string;
  action?: string;
  actor?: string;
  actorType?: string;
  outcome?: string;
  resource?: string;
}

@Injectable({ providedIn: 'root' })
export class AuditLogService {
  constructor(private http: HttpClient) {}

  private params(filter: AuditFilter, extra: Record<string, string | number | undefined> = {}): HttpParams {
    let p = new HttpParams();
    const all: Record<string, string | number | undefined> = { ...filter, ...extra };
    for (const [k, v] of Object.entries(all)) {
      if (v !== undefined && v !== null && String(v).trim() !== '') p = p.set(k, String(v));
    }
    return p;
  }

  list(filter: AuditFilter, limit: number, cursor?: string): Observable<AuditPage> {
    return this.http.get<AuditPage>('/api/v1/audit-log', { params: this.params(filter, { limit, cursor }) });
  }

  facets(): Observable<AuditFacets> {
    return this.http.get<AuditFacets>('/api/v1/audit-log/facets');
  }

  status(): Observable<AuditStatus> {
    return this.http.get<AuditStatus>('/api/v1/audit-log/status');
  }

  /** The export needs the session cookie / x-api-key the interceptor adds, so
   *  fetch it as a blob rather than navigating to the URL. */
  exportCsv(filter: AuditFilter): Observable<Blob> {
    return this.http.get('/api/v1/audit-log/export', { params: this.params(filter), responseType: 'blob' });
  }
}
