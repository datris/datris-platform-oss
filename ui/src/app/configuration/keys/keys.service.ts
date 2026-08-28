import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/** A single API-key row returned by GET /keys. Never contains the value — that
 *  is only returned at issue/rotate time and discarded server-side after the
 *  response. */
export interface KeyRow {
  label: string;
  /** Stable per-issue id (audit-log identity). Absent on keys seeded before ids existed. */
  keyId?: string;
  capabilities: string[];
  isLegacyFullAccess: boolean;
  revoked: boolean;
  createdAt?: string;
  createdBy?: string;
  revokedAt?: string;
  revokedBy?: string;
}

export interface IssueKeyResponse {
  label: string;
  value: string;
  keyId?: string;
  capabilities: string[];
  createdAt: string;
  createdBy: string;
}

export interface RotateKeyResponse {
  label: string;
  value: string;
  rotatedAt: string;
}

export interface CapabilityResource {
  resource: string;
  actions: string[];
  scopeKeys: string[];
}

export interface KeyTemplate {
  name: string;
  description: string;
  capabilities: string[];
}

@Injectable({ providedIn: 'root' })
export class KeysService {
  constructor(private http: HttpClient) {}

  list(): Observable<{ keys: KeyRow[] }> {
    return this.http.get<{ keys: KeyRow[] }>('/api/v1/keys');
  }

  issue(label: string, capabilities: string[]): Observable<IssueKeyResponse> {
    return this.http.post<IssueKeyResponse>('/api/v1/keys', { label, capabilities });
  }

  revoke(label: string): Observable<{ status: string }> {
    return this.http.delete<{ status: string }>('/api/v1/keys/' + encodeURIComponent(label));
  }

  rotate(label: string): Observable<RotateKeyResponse> {
    return this.http.post<RotateKeyResponse>('/api/v1/keys/' + encodeURIComponent(label) + '/rotate', {});
  }

  capabilities(): Observable<{ resources: CapabilityResource[] }> {
    return this.http.get<{ resources: CapabilityResource[] }>('/api/v1/keys/capabilities/catalog');
  }

  templates(): Observable<{ templates: KeyTemplate[] }> {
    return this.http.get<{ templates: KeyTemplate[] }>('/api/v1/keys/templates');
  }
}
