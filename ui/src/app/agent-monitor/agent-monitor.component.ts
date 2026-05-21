import { Component, OnInit, OnDestroy, ViewChild, ElementRef, AfterViewChecked } from '@angular/core';
import { AgentMonitorService, AgentSession, AgentCall } from '../agent-monitor.service';

interface AgentNode {
  session: AgentSession;
  x: number;
  y: number;
  active: boolean;
  activeUntil: number;
}

interface LogRow {
  id: string;
  ts: number;
  timeLabel: string;
  sessionShort: string;
  tool: string;
  status: string;
  latencyMs: number;
  agentLabel: string;
  argsPreview: string;
  argsFull: string;
  responsePreview: string;
  responseSizeLabel: string;
  recordCount: number | null;
  error: string;
  expanded: boolean;
}

@Component({
  selector: 'app-agent-monitor',
  templateUrl: './agent-monitor.component.html',
  styleUrls: ['./agent-monitor.component.css']
})
export class AgentMonitorComponent implements OnInit, OnDestroy, AfterViewChecked {
  @ViewChild('logScroll') logScroll?: ElementRef<HTMLDivElement>;

  agents: AgentNode[] = [];
  log: LogRow[] = [];
  error = '';
  connected = true;
  copied = false;
  copyLabel = 'Copy log to clipboard';
  confirmingClear = false;
  private copyResetTimer: any;
  private confirmClearTimer: any;

  readonly serverX = 110;
  readonly serverY = 200;
  readonly canvasWidth = 760;
  readonly canvasHeight = 400;
  readonly agentX = 650;

  private since = 0;
  private refreshInterval: any;
  private animationTimer: any;
  private shouldScroll = false;
  private readonly maxLogRows = 200;

  constructor(private service: AgentMonitorService) { }

  ngOnInit(): void {
    this.loadData();
    this.refreshInterval = setInterval(() => this.loadData(), 2000);
    this.animationTimer = setInterval(() => this.decayActive(), 200);
  }

  ngOnDestroy(): void {
    if (this.refreshInterval) clearInterval(this.refreshInterval);
    if (this.animationTimer) clearInterval(this.animationTimer);
    if (this.copyResetTimer) clearTimeout(this.copyResetTimer);
    if (this.confirmClearTimer) clearTimeout(this.confirmClearTimer);
  }

  /** First click on the trash icon — show inline "Clear N events?" prompt.
   *  Auto-reverts after 5s of inactivity so the toolbar never gets stuck in
   *  confirmation state. */
  askClear(): void {
    if (this.log.length === 0) return;
    this.confirmingClear = true;
    if (this.confirmClearTimer) clearTimeout(this.confirmClearTimer);
    this.confirmClearTimer = setTimeout(() => { this.confirmingClear = false; }, 5000);
  }

  cancelClear(): void {
    this.confirmingClear = false;
    if (this.confirmClearTimer) { clearTimeout(this.confirmClearTimer); this.confirmClearTimer = null; }
  }

  clearLog(): void {
    this.cancelClear();
    // Clear the server-side buffer first. Without this, the next /activity poll
    // (or a page reload) replays the buffer and the log refills. On failure,
    // leave the local view alone — clearing it would just refill on next poll
    // and confuse the user — and surface the error.
    this.service.clearActivity().subscribe({
      next: () => {
        this.log = [];
        this.error = '';
      },
      error: (err) => {
        this.error = err?.error || err?.message || 'Failed to clear activity log';
      }
    });
  }

  /** True when this component is rendered in a popped-out browser window
   *  (opened via openInPopout below). The template uses this to hide the
   *  pop-out button itself so popout windows don't show a "pop out again"
   *  control, and to drop the subtitle paragraph to save vertical space. */
  get isPopout(): boolean {
    return typeof window !== 'undefined' && window.location.pathname.startsWith('/popout/');
  }

  /** Open the Agent Monitor (Connections + Activity Log) in a new browser
   *  window. The fixed window name ('datris-agent-monitor') means a second
   *  click focuses the existing popout instead of opening another one. */
  openInPopout(): void {
    const features = 'width=1100,height=900,resizable=yes,scrollbars=yes,menubar=no,toolbar=no,location=no,status=no';
    window.open('/popout/activity', 'datris-agent-monitor', features);
  }

  copyLog(): void {
    if (this.log.length === 0) return;
    const text = this.log.map(r => this.formatRowForCopy(r)).join('\n\n' + '-'.repeat(60) + '\n\n');

    const done = () => {
      this.copied = true;
      this.copyLabel = 'Copied';
      if (this.copyResetTimer) clearTimeout(this.copyResetTimer);
      this.copyResetTimer = setTimeout(() => {
        this.copied = false;
        this.copyLabel = 'Copy log to clipboard';
      }, 1500);
    };

    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      navigator.clipboard.writeText(text).then(done, () => this.fallbackCopy(text, done));
    } else {
      this.fallbackCopy(text, done);
    }
  }

  private formatRowForCopy(r: LogRow): string {
    const lines: string[] = [];
    const header = ['[' + r.timeLabel + ']', r.agentLabel, '→', r.tool];
    if (r.argsPreview) header.push('(' + r.argsPreview + ')');
    lines.push(header.join('  '));

    const meta: string[] = ['status=' + r.status, 'latency=' + r.latencyMs + 'ms'];
    if (r.responseSizeLabel) meta.push('size=' + r.responseSizeLabel);
    if (r.recordCount !== null) meta.push('records=' + r.recordCount);
    lines.push('  ' + meta.join('  '));

    if (r.error) {
      lines.push('');
      lines.push('  --- error ---');
      lines.push(this.indent(r.error, '  '));
    }

    if (r.argsFull) {
      lines.push('');
      lines.push('  --- arguments ---');
      lines.push(this.indent(this.formatJson(r.argsFull), '  '));
    }

    if (r.responsePreview) {
      lines.push('');
      lines.push('  --- response ---');
      lines.push(this.indent(this.formatJson(r.responsePreview), '  '));
    }

    return lines.join('\n');
  }

  private indent(text: string, prefix: string): string {
    return text.split('\n').map(l => prefix + l).join('\n');
  }

  private fallbackCopy(text: string, done: () => void): void {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.top = '-10000px';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); done(); }
    finally { document.body.removeChild(ta); }
  }

  ngAfterViewChecked(): void {
    if (this.shouldScroll && this.logScroll) {
      const el = this.logScroll.nativeElement;
      el.scrollTop = el.scrollHeight;
      this.shouldScroll = false;
    }
  }

  private loadData(): void {
    this.service.getActivity(this.since).subscribe({
      next: (data) => {
        this.error = data.error || '';
        this.connected = !data.error;
        if (data.server_time) this.since = data.server_time;
        this.updateAgents(data.sessions || []);
        this.appendCalls(data.calls || []);
      },
      error: (err) => {
        this.connected = false;
        this.error = err?.message || 'Failed to reach server';
      }
    });
  }

  private updateAgents(sessions: AgentSession[]): void {
    const existing = new Map<string, AgentNode>();
    for (const a of this.agents) existing.set(a.session.session_id, a);

    const next: AgentNode[] = sessions.map((s, i) => {
      const prev = existing.get(s.session_id);
      return {
        session: s,
        x: this.agentX,
        y: 0,
        active: prev?.active || false,
        activeUntil: prev?.activeUntil || 0,
      };
    });

    const count = next.length;
    if (count > 0) {
      const gap = Math.min(90, (this.canvasHeight - 80) / Math.max(count, 1));
      const totalHeight = gap * (count - 1);
      const startY = this.canvasHeight / 2 - totalHeight / 2;
      next.forEach((n, i) => n.y = startY + i * gap);
    }

    this.agents = next;
  }

  private appendCalls(calls: AgentCall[]): void {
    if (!calls.length) return;
    const now = Date.now();

    const pulseUntil = now + 900;
    const agentById = new Map(this.agents.map(a => [a.session.session_id, a]));

    for (const c of calls) {
      const a = agentById.get(c.session_id);
      if (a) {
        a.active = true;
        a.activeUntil = pulseUntil;
      }
      this.log.push(this.toLogRow(c));
    }

    if (this.log.length > this.maxLogRows) {
      this.log = this.log.slice(this.log.length - this.maxLogRows);
    }
    this.shouldScroll = true;
  }

  private decayActive(): void {
    const now = Date.now();
    let changed = false;
    for (const a of this.agents) {
      if (a.active && a.activeUntil <= now) {
        a.active = false;
        changed = true;
      }
    }
    if (changed) this.agents = [...this.agents];
  }

  private toLogRow(c: AgentCall): LogRow {
    const timeLabel = c.ts_formatted || this.fallbackTimeLabel(c.ts);
    const sessionShort = c.session_id.substring(0, 6);
    return {
      id: c.ts + '-' + sessionShort + '-' + c.tool,
      ts: c.ts,
      timeLabel,
      sessionShort,
      tool: c.tool,
      status: c.status,
      latencyMs: c.latency_ms,
      agentLabel: this.agentLabel(c),
      argsPreview: c.args_preview || '',
      argsFull: c.args_full || '',
      responsePreview: c.response_preview || '',
      responseSizeLabel: this.formatSize(c.response_size),
      recordCount: c.record_count ?? null,
      error: c.error || '',
      expanded: false,
    };
  }

  toggleRow(row: LogRow): void {
    row.expanded = !row.expanded;
  }

  private fallbackTimeLabel(ts: number): string {
    const d = new Date(ts * 1000);
    const pad = (n: number) => n.toString().padStart(2, '0');
    return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
  }

  formatSize(bytes: number | undefined): string {
    if (!bytes || bytes <= 0) return '';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }

  formatJson(text: string): string {
    if (!text) return '';
    // Server already pretty-prints; only reformat if the string still looks like
    // a single-line JSON object/array (e.g. stdio-mode calls or non-JSON outputs).
    if (text.indexOf('\n') !== -1) return text;
    const trimmed = text.trim();
    if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return text;
    try {
      return JSON.stringify(JSON.parse(trimmed), null, 2);
    } catch {
      return text;
    }
  }

  agentLabel(s: {
    tenant?: string;
    key_name?: string;
    client_name?: string;
    session_id: string;
    api_key_hint?: string;
  }): string {
    if (s.client_name) return s.client_name;
    if (s.tenant) return s.tenant;
    if (s.key_name) return s.key_name;
    if (s.api_key_hint) return s.api_key_hint + '…';
    return s.session_id.substring(0, 6);
  }

  trackAgent(_: number, a: AgentNode): string {
    return a.session.session_id;
  }

  trackLog(_: number, r: LogRow): string {
    return r.id;
  }
}
