import { Component, OnDestroy, OnInit } from '@angular/core';
import { Subscription } from 'rxjs';
import { filter } from 'rxjs/operators';
import { ConfigAssistantStateService } from '../../config-chat/config-assistant-state.service';
import { Router } from '@angular/router';
import { AuthService } from '../../auth.service';
import { AgentPolicy, AgentPolicyService, PolicyMode, PolicyResponse, RecoverySettings } from './agent-policy.service';

/** One row of the action matrix. Sub-actions ("pipeline:update:dest-types")
 *  are indented under their two-part parent and inherit its mode unless set. */
interface ActionRow {
  key: string;
  action: string;      // the part after the resource prefix, e.g. "update:dest-types"
  sub: boolean;        // 3-part key
  parentKey?: string;  // for sub-actions: the 2-part key it inherits from
}

interface ResourceGroup {
  resource: string;
  rows: ActionRow[];
}

/** Editable override row. Each holds a resource key plus its own action→mode
 *  pairs; the form works on this flat shape and folds it back into the
 *  server's map-of-maps on save. */
interface OverrideRow {
  resource: string;
  entries: { action: string; mode: PolicyMode }[];
}

/** Admin-only Agent Policy sub-tab inside Configuration.
 *
 *  Decides what agents may do on their own, what waits for a person, and what
 *  is refused. Humans are never gated; reads are never gated; agents can never
 *  change the policy or decide approvals. */
@Component({
    selector: 'app-agent-policy',
    templateUrl: './agent-policy.component.html',
    styleUrl: './agent-policy.component.css',
    standalone: false
})
export class AgentPolicyComponent implements OnInit, OnDestroy {
  private chatSub?: Subscription;

  status: PolicyResponse | null = null;
  loading = false;
  saving = false;
  error = '';
  success = '';

  readonly modes: PolicyMode[] = ['auto', 'approve', 'deny'];

  groups: ResourceGroup[] = [];
  /** Working copy of the matrix — only keys set to approve/deny are kept. */
  actions: Record<string, PolicyMode> = {};
  overrides: OverrideRow[] = [];
  pendingTtlHours = 24;
  maxPendingPerActor = 10;

  /** Saved document metadata, shown after load/save. */
  version = 0;
  updatedAt?: string;
  updatedBy?: string;
  pendingCount = 0;

  constructor(
    private svc: AgentPolicyService,
    private router: Router,
    private chatState: ConfigAssistantStateService,
    private auth: AuthService
  ) {}

  ngOnInit(): void {
    // Reload when the configuration chat changes this sub-tab's setting.
    this.chatSub = this.chatState.changed$
      .pipe(filter(e => e.tab === 'agent-policy'))
      .subscribe(() => this.reload());
    this.reload();
  }

  ngOnDestroy(): void {
    this.chatSub?.unsubscribe();
  }

  get enabled(): boolean {
    return !!this.status?.enabled;
  }

  reload(): void {
    this.loading = true;
    this.error = '';
    this.svc.get().subscribe({
      next: (s) => {
        this.status = s;
        this.loading = false;
        if (s.enabled) {
          this.groups = this.buildGroups(s.actions || []);
          this.applyPolicy(s.policy);
          this.pendingCount = s.pendingCount || 0;
        }
      },
      error: (err) => {
        // 404 = feature off on a server that predates the endpoint being
        // always-on; render the enable-it card instead of an error.
        if (err?.status === 404) {
          this.status = { enabled: false } as PolicyResponse;
        } else {
          this.error = err?.error?.error || 'Failed to read agent policy';
        }
        this.loading = false;
      }
    });
  }

  // ------- matrix -------

  private buildGroups(keys: string[]): ResourceGroup[] {
    const byResource = new Map<string, ActionRow[]>();
    const sorted = keys.slice().sort();
    for (const key of sorted) {
      const parts = key.split(':');
      const resource = parts[0] || key;
      const action = parts.slice(1).join(':');
      const sub = parts.length >= 3;
      const row: ActionRow = { key, action, sub };
      if (sub) row.parentKey = parts.slice(0, 2).join(':');
      if (!byResource.has(resource)) byResource.set(resource, []);
      byResource.get(resource)!.push(row);
    }
    // Keep sub-actions directly under their parent regardless of sort order.
    const groups: ResourceGroup[] = [];
    for (const [resource, rows] of byResource) {
      const ordered: ActionRow[] = [];
      const parents = rows.filter(r => !r.sub);
      const subs = rows.filter(r => r.sub);
      for (const p of parents) {
        ordered.push(p);
        for (const s of subs) if (s.parentKey === p.key) ordered.push(s);
      }
      // Orphan sub-actions (no listed parent) go at the end.
      for (const s of subs) if (!ordered.includes(s)) ordered.push(s);
      groups.push({ resource, rows: ordered });
    }
    return groups;
  }

  private applyPolicy(p: AgentPolicy | undefined): void {
    this.actions = { ...(p?.actions || {}) };
    this.overrides = Object.entries(p?.overrides || {}).map(([resource, m]) => ({
      resource,
      entries: Object.entries(m || {}).map(([action, mode]) => ({ action, mode }))
    }));
    this.pendingTtlHours = p?.limits?.pendingTtlHours ?? 24;
    this.maxPendingPerActor = p?.limits?.maxPendingPerActor ?? 10;
    this.version = p?.version ?? 0;
    this.updatedAt = p?.updatedAt;
    this.updatedBy = p?.updatedBy;
  }

  modeOf(key: string): PolicyMode {
    return this.actions[key] || 'auto';
  }

  /** True when a sub-action has no explicit mode — it follows its parent. */
  inherits(row: ActionRow): boolean {
    return row.sub && !this.actions[row.key];
  }

  /** The mode a sub-action effectively runs under when unset. */
  inheritedMode(row: ActionRow): PolicyMode {
    return row.parentKey ? this.modeOf(row.parentKey) : 'auto';
  }

  setMode(key: string, mode: PolicyMode): void {
    if (mode === 'auto') delete this.actions[key];
    else this.actions[key] = mode;
    this.success = '';
  }

  useRecommended(): void {
    const rec = this.status?.recommended;
    if (!rec) return;
    this.actions = { ...(rec.actions || {}) };
    this.success = '';
  }

  // ------- overrides -------

  addOverride(): void {
    this.overrides.push({ resource: '', entries: [{ action: '', mode: 'approve' }] });
  }

  removeOverride(i: number): void {
    this.overrides.splice(i, 1);
  }

  addOverrideEntry(o: OverrideRow): void {
    o.entries.push({ action: '', mode: 'approve' });
  }

  removeOverrideEntry(o: OverrideRow, j: number): void {
    o.entries.splice(j, 1);
  }

  /** Action keys offered in the override dropdowns. */
  get actionKeys(): string[] {
    return this.status?.actions || [];
  }

  // ------- save -------

  private collectOverrides(): Record<string, Record<string, PolicyMode>> {
    const out: Record<string, Record<string, PolicyMode>> = {};
    for (const o of this.overrides) {
      const res = (o.resource || '').trim();
      if (!res) continue;
      const m: Record<string, PolicyMode> = {};
      for (const e of o.entries) {
        const a = (e.action || '').trim();
        if (a) m[a] = e.mode;
      }
      if (Object.keys(m).length) out[res] = m;
    }
    return out;
  }

  save(): void {
    if (!this.enabled || this.saving) return;
    this.saving = true;
    this.error = '';
    this.success = '';
    this.svc.save({
      actions: { ...this.actions },
      overrides: this.collectOverrides(),
      limits: {
        pendingTtlHours: Number(this.pendingTtlHours) || 0,
        maxPendingPerActor: Number(this.maxPendingPerActor) || 0
      }
    }).subscribe({
      next: (res) => {
        // Keep the stored copy current: Apply recommended builds its body from it.
        if (this.status) this.status = { ...this.status, policy: res.policy };
        this.applyPolicy(res.policy);
        this.success = `Policy saved (version ${this.version}).`;
        this.saving = false;
      },
      error: (err) => {
        this.error = err?.error?.error || err?.error?.message || 'Failed to save policy';
        this.saving = false;
      }
    });
  }

  // ------- recovery (read-only, plus apply-recommended) -------

  /** Admins (or everyone when user auth is off) may apply the recommendation. */
  get isAdmin(): boolean {
    return !this.auth.userAuthEnabled || this.auth.current()?.role === 'admin';
  }

  get recovery(): RecoverySettings | undefined {
    return this.status?.policy?.recovery;
  }

  get recoveryRecommended(): RecoverySettings | undefined {
    return this.status?.recoveryRecommended;
  }

  applyingRecovery = false;

  /** PUT the stored policy unchanged except `recovery` = the recommendation.
   *  The stored overrides (which carry the per-resource recovery modes) go
   *  back as-is: a PUT with `recovery` also replaces those from the body. */
  applyRecommendedRecovery(): void {
    const rec = this.recoveryRecommended;
    const stored = this.status?.policy;
    if (!rec || !stored || !this.enabled || this.applyingRecovery || !this.isAdmin) return;
    const label = this.status?.environmentLabel ? `the "${this.status.environmentLabel}" environment` : 'this unlabelled install';
    const msg = `Set the recovery agent to "${rec.mode}" mode with the thresholds recommended for ${label}?`;
    if (!window.confirm(msg)) return;
    this.applyingRecovery = true;
    this.error = '';
    this.success = '';
    this.svc.save({
      actions: { ...(stored.actions || {}) },
      overrides: JSON.parse(JSON.stringify(stored.overrides || {})),
      limits: { ...(stored.limits || { pendingTtlHours: 24, maxPendingPerActor: 50 }) },
      recovery: { ...rec }
    }).subscribe({
      next: (res) => {
        if (this.status) this.status = { ...this.status, policy: res.policy };
        this.applyPolicy(res.policy);
        this.success = `Recovery set to ${res.policy?.recovery?.mode ?? rec.mode} (version ${this.version}).`;
        this.applyingRecovery = false;
      },
      error: (err) => {
        this.error = err?.error?.error || err?.error?.message || 'Failed to apply recovery settings';
        this.applyingRecovery = false;
      }
    });
  }

  openApprovals(): void {
    this.router.navigate(['/ops', 'activity'], { fragment: 'approvals' });
  }

  // ------- display helpers -------

  formatTs(ts?: string): string {
    if (!ts) return '—';
    const d = new Date(ts);
    return isNaN(d.getTime()) ? ts : d.toLocaleString();
  }

  trackByKey(_i: number, r: ActionRow): string {
    return r.key;
  }

  trackByResource(_i: number, g: ResourceGroup): string {
    return g.resource;
  }

  trackByIndex(i: number): number {
    return i;
  }
}
