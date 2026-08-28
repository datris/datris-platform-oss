import { Component, OnDestroy, OnInit } from '@angular/core';
import { Subject } from 'rxjs';
import { debounceTime } from 'rxjs/operators';
import { AuditEntry, AuditFacets, AuditFilter, AuditLogService, AuditStatus } from './audit-log.service';

/** Admin-only Audit Log sub-tab inside Configuration.
 *
 *  Who did what: humans by login, agents by API key, the Assistant on the
 *  user's behalf, scheduled runs as `system`. Read-only — entries are
 *  immutable; only the retention TTL removes them. */
@Component({
    selector: 'app-audit-log',
    templateUrl: './audit-log.component.html',
    styleUrl: './audit-log.component.css',
    standalone: false
})
export class AuditLogComponent implements OnInit, OnDestroy {
  status: AuditStatus | null = null;
  facets: AuditFacets | null = null;
  entries: AuditEntry[] = [];
  nextCursor?: string;
  loading = false;
  loadingMore = false;
  exporting = false;
  error = '';

  /** Time-range presets; 'custom' reveals the datetime inputs. */
  range: '1h' | '24h' | '7d' | '30d' | 'custom' = '24h';
  customSince = '';
  customUntil = '';

  filter: AuditFilter = {};
  readonly pageSize = 100;

  selected: AuditEntry | null = null;

  private resourceInput$ = new Subject<string>();
  private sub = this.resourceInput$.pipe(debounceTime(300)).subscribe(() => this.reload());

  constructor(private svc: AuditLogService) {}

  ngOnInit(): void {
    this.svc.status().subscribe({
      next: (s) => {
        this.status = s;
        if (s.enabled) {
          this.svc.facets().subscribe({ next: (f) => (this.facets = f), error: () => { /* dropdowns fall back to free text */ } });
          this.reload();
        }
      },
      error: (err) => { this.error = err?.error?.error || 'Failed to read audit-log status'; }
    });
  }

  ngOnDestroy(): void {
    this.sub.unsubscribe();
  }

  get enabled(): boolean {
    return !!this.status?.enabled;
  }

  // ------- filters -------

  private effectiveFilter(): AuditFilter {
    const f: AuditFilter = { ...this.filter };
    const now = Date.now();
    const hours: Record<string, number> = { '1h': 1, '24h': 24, '7d': 24 * 7, '30d': 24 * 30 };
    if (this.range === 'custom') {
      f.since = this.customSince ? new Date(this.customSince).toISOString() : undefined;
      f.until = this.customUntil ? new Date(this.customUntil).toISOString() : undefined;
    } else {
      f.since = new Date(now - hours[this.range] * 3600 * 1000).toISOString();
      f.until = undefined;
    }
    return f;
  }

  onResourceInput(value: string): void {
    this.filter.resource = value;
    this.resourceInput$.next(value);
  }

  setRange(r: '1h' | '24h' | '7d' | '30d' | 'custom'): void {
    this.range = r;
    if (r !== 'custom') this.reload();
  }

  clearFilters(): void {
    this.filter = {};
    this.range = '24h';
    this.customSince = '';
    this.customUntil = '';
    this.reload();
  }

  get hasFilters(): boolean {
    return Object.values(this.filter).some(v => v && String(v).trim() !== '') || this.range !== '24h';
  }

  // ------- data -------

  reload(): void {
    if (!this.enabled) return;
    this.loading = true;
    this.error = '';
    this.selected = null;
    this.svc.list(this.effectiveFilter(), this.pageSize).subscribe({
      next: (page) => {
        this.entries = page.entries || [];
        this.nextCursor = page.nextCursor;
        this.loading = false;
      },
      error: (err) => {
        this.error = err?.error?.error || 'Failed to load audit log';
        this.loading = false;
      }
    });
  }

  loadMore(): void {
    if (!this.nextCursor || this.loadingMore) return;
    this.loadingMore = true;
    this.svc.list(this.effectiveFilter(), this.pageSize, this.nextCursor).subscribe({
      next: (page) => {
        this.entries = this.entries.concat(page.entries || []);
        this.nextCursor = page.nextCursor;
        this.loadingMore = false;
      },
      error: (err) => {
        this.error = err?.error?.error || 'Failed to load more';
        this.loadingMore = false;
      }
    });
  }

  select(e: AuditEntry): void {
    this.selected = this.selected?.id === e.id ? null : e;
  }

  exportCsv(): void {
    this.exporting = true;
    this.svc.exportCsv(this.effectiveFilter()).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'datris-audit-log.csv';
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        this.exporting = false;
      },
      error: (err) => {
        this.error = err?.error?.error || 'Export failed';
        this.exporting = false;
      }
    });
  }

  // ------- display helpers -------

  actorDisplay(e: AuditEntry): string {
    const a = e.actor;
    switch (a.type) {
      case 'user': return a.username || a.label;
      case 'assistant': return `${a.username || a.label} via Assistant`;
      case 'api-key': return a.label;
      case 'tap': return `tap ${a.label}`;
      default: return a.label;
    }
  }

  actorTitle(e: AuditEntry): string {
    const a = e.actor;
    const parts = [`type: ${a.type}`, `label: ${a.label}`];
    if (a.keyLabel) parts.push(`key: ${a.keyLabel}`);
    if (a.keyId) parts.push(`keyId: ${a.keyId}`);
    if (a.role) parts.push(`role: ${a.role}`);
    return parts.join('\n');
  }

  resourceDisplay(e: AuditEntry): string {
    const r = e.resource;
    if (!r) return '—';
    if (r.name && r.type && r.type !== e.category) return `${r.type} ${r.name}`;
    return r.name || r.type || '—';
  }

  countBadge(e: AuditEntry): number | null {
    const c = e.metadata && (e.metadata as any)['count'];
    return typeof c === 'number' && c > 1 ? c : null;
  }

  formatTs(ts: string): string {
    const d = new Date(ts);
    return isNaN(d.getTime()) ? ts : d.toLocaleString();
  }

  json(e: AuditEntry): string {
    return JSON.stringify(e, null, 2);
  }

  trackById(_i: number, e: AuditEntry): string {
    return e.id;
  }
}
