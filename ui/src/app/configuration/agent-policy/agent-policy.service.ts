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

export type RecoveryMode = 'off' | 'propose' | 'autopilot';
export type VerifyMode = 'scratch-first' | 'real';

/** The recovery agent's settings (`policy.recovery`). Editable on the Agent
 *  Policy page (mode, verify mode, schema-drift actions); thresholds via the
 *  API or the Configuration assistant. */
export interface RecoverySettings {
  mode: RecoveryMode | string;
  maxAiCallsPerIncident?: number;
  maxActionsPerIncident?: number;
  maxRuntimeMinutes?: number;
  maxOpenIncidents?: number;
  cooldownHours?: number;
  maxRecordDeltaPct?: number;
  allowColumnRemoval?: boolean;
  allowTypeChange?: boolean;
  allowSchemaDriftActions?: boolean;
  minDqPassRate?: number;
  maxNullRateIncrease?: number;
  verifyMode?: VerifyMode | string;
}

/** The policy document as stored. Unlisted actions are effectively "auto". */
export interface AgentPolicy {
  version: number;
  actions: Record<string, PolicyMode>;
  overrides: PolicyOverrides;
  limits: PolicyLimits;
  recovery?: RecoverySettings;
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
  /** The recovery block recommended for this install's environment label. */
  recoveryRecommended?: RecoverySettings;
  /** DATRIS_ENV, absent when unset. */
  environmentLabel?: string;
}

export interface PolicyUpdate {
  actions: Record<string, PolicyMode>;
  overrides: PolicyOverrides;
  limits: PolicyLimits;
  /** When present the server also replaces the per-resource recovery modes
   *  from `overrides` — send them back inside `overrides`. */
  recovery?: RecoverySettings;
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
