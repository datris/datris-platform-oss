import { Component, Input, OnChanges, OnInit, SimpleChanges } from '@angular/core';
import { AuthService } from '../../auth.service';
import {
  AgentPolicy,
  AgentPolicyService,
  PolicyOverrides,
  RecoveryMode,
  recoveryRemoved
} from '../../configuration/agent-policy/agent-policy.service';

/** Server default limits (AgentPolicy.scala PolicyLimits). */
const DEFAULT_LIMITS = { pendingTtlHours: 24, maxPendingPerActor: 50 };

/** "Self-healing" row on a pipeline or tap page: the resource's effective
 *  recovery mode, where it comes from (its override or the instance
 *  default), and, for admins, a select that sets or clears the override
 *  (`policy.overrides['<type>:<name>'].recovery`). */
@Component({
  selector: 'app-recovery-mode-row',
  templateUrl: './recovery-mode-row.component.html',
  styleUrl: './recovery-mode-row.component.css',
  standalone: false
})
export class RecoveryModeRowComponent implements OnInit, OnChanges {
  @Input() resourceType: 'pipeline' | 'tap' = 'pipeline';
  @Input() resourceName = '';

  loaded = false;
  enabled = false;
  policy: AgentPolicy | null = null;
  saving = false;
  error = '';

  /** Bound to the admin select: '' = use the instance default. */
  selected: RecoveryMode | '' = '';

  constructor(private svc: AgentPolicyService, private auth: AuthService) {}

  ngOnInit(): void {
    this.load();
  }

  ngOnChanges(changes: SimpleChanges): void {
    const c = changes['resourceName'] || changes['resourceType'];
    if (c && !c.firstChange) this.load();
  }

  get key(): string {
    return this.resourceType + ':' + this.resourceName;
  }

  /** Same rule as the Agent Policy page: everyone is admin when user auth is off. */
  get isAdmin(): boolean {
    return !this.auth.userAuthEnabled || this.auth.current()?.role === 'admin';
  }

  get override(): string | undefined {
    const o = this.policy?.overrides?.[this.key];
    const r = o ? o['recovery'] : undefined;
    return r ? String(r) : undefined;
  }

  get effectiveMode(): string {
    return this.override ?? (this.policy?.recovery?.mode || 'off');
  }

  get source(): string {
    return this.override ? 'override' : 'instance default';
  }

  private load(): void {
    if (!this.resourceName) return;
    this.error = '';
    this.svc.get().subscribe({
      next: (s) => {
        this.enabled = !!s?.enabled;
        this.policy = s?.policy || null;
        this.selected = (this.override as RecoveryMode) || '';
        this.loaded = true;
      },
      error: () => {
        // No policy endpoint or no access: the row stays hidden.
        this.loaded = false;
      }
    });
  }

  onSelect(value: RecoveryMode | ''): void {
    const stored = this.policy;
    if (!stored || !this.enabled || !this.isAdmin || this.saving) return;
    const overrides: PolicyOverrides = JSON.parse(JSON.stringify(stored.overrides || {}));
    const entry = { ...(overrides[this.key] || {}) };
    if (value) entry['recovery'] = value;
    else delete entry['recovery'];
    if (Object.keys(entry).length) overrides[this.key] = entry;
    else delete overrides[this.key];

    this.saving = true;
    this.error = '';
    this.svc.save({
      actions: { ...(stored.actions || {}) },
      overrides,
      limits: { ...(stored.limits || DEFAULT_LIMITS) },
      // The server keeps the stored recovery overrides unless the body
      // carries `recovery` or an override with one; send the stored block
      // back unchanged when this removes the last recovery override.
      ...(stored.recovery && recoveryRemoved(stored.overrides, overrides) ? { recovery: { ...stored.recovery } } : {})
    }).subscribe({
      next: (res) => {
        if (res?.policy) this.policy = res.policy;
        this.selected = (this.override as RecoveryMode) || '';
        this.saving = false;
      },
      error: (err) => {
        this.error = err?.error?.error || err?.error?.message || 'Failed to save recovery mode';
        this.selected = (this.override as RecoveryMode) || '';
        this.saving = false;
      }
    });
  }
}
