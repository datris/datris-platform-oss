import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export type PolicyMode = 'auto' | 'approve' | 'deny';

/** Per-resource override: `pipeline:<name>` / `tap:<name>` → action → mode. */
export type PolicyOverrides = Record<string, Record<string, PolicyMode>>;

export interface PolicyLimits {
  pendingTtlHours: number;
  maxPendingPerActor: number;
}

/** The policy document as stored. Unlisted actions are effectively "auto". */
export interface AgentPolicy {
  version: number;
  actions: Record<string, PolicyMode>;
  overrides: PolicyOverrides;
  limits: PolicyLimits;
  updatedAt?: string;
  updatedBy?: string;
}

export interface PolicyResponse {
  enabled: boolean;
  policy: AgentPolicy;
  recommended: AgentPolicy;
  /** Every gateable action key, sorted (e.g. "pipeline:create", "tap:run"). */
  actions: string[];
  pendingCount: number;
}

export interface PolicyUpdate {
  actions: Record<string, PolicyMode>;
  overrides: PolicyOverrides;
  limits: PolicyLimits;
}

@Injectable({ providedIn: 'root' })
export class AgentPolicyService {
  constructor(private http: HttpClient) {}

  get(): Observable<PolicyResponse> {
    return this.http.get<PolicyResponse>('/api/v1/policy');
  }

  save(update: PolicyUpdate): Observable<{ enabled: boolean; policy: AgentPolicy }> {
    return this.http.put<{ enabled: boolean; policy: AgentPolicy }>('/api/v1/policy', update);
  }
}
