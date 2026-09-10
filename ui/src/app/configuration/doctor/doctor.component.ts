import { Component } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

export interface DoctorCheck {
  id: string;
  status: 'ok' | 'warn' | 'error' | 'skip';
  severity: string;
  detail: string;
  remediation: string;
  surface: string;
  ms: number;
}

export interface DoctorReport {
  doctorVersion: number;
  ranAt: string;
  mode: string;
  surface: Record<string, string>;
  summary: { ok: number; warn: number; error: number; skip: number };
  checks: DoctorCheck[];
}

/** Admin-only Doctor sub-tab inside Configuration.
 *
 *  Runs the server's operational self-check (GET /api/v1/doctor) on demand —
 *  never on load: the AI probe spends tokens and the report is only useful
 *  when someone is looking at it. Host-level checks (Docker volumes,
 *  container env drift) need `datris doctor` on the machine running Docker;
 *  the footer says so. */
@Component({
    selector: 'app-doctor',
    templateUrl: './doctor.component.html',
    styleUrl: './doctor.component.css',
    standalone: false
})
export class DoctorComponent {
  report: DoctorReport | null = null;
  loading = false;
  error = '';
  includeAiProbes = false;
  /** Stamped into the UI image at build time (/version.json); 'unknown' when
   *  built from source without APP_VERSION. Sent to the server so the
   *  version-skew check can compare it. */
  uiVersion = '';

  constructor(private http: HttpClient) {}

  async run(): Promise<void> {
    this.loading = true;
    this.error = '';
    try {
      if (!this.uiVersion) this.uiVersion = await this.loadUiVersion();
      let params = new HttpParams().set('mode', 'full').set('ui', this.uiVersion);
      if (this.includeAiProbes) params = params.set('probes', 'ai');
      this.report = await firstValueFrom(this.http.get<DoctorReport>('/api/v1/doctor', { params }));
    } catch (err: any) {
      this.report = null;
      this.error = err?.error?.error || err?.message || 'Doctor request failed';
    } finally {
      this.loading = false;
    }
  }

  private async loadUiVersion(): Promise<string> {
    try {
      const v = await firstValueFrom(this.http.get<{ version?: string }>('/version.json'));
      return (v?.version || 'unknown').trim();
    } catch {
      return 'unknown';
    }
  }

  icon(status: string): string {
    switch (status) {
      case 'ok': return '✓';
      case 'warn': return '!';
      case 'error': return '✗';
      default: return '○';
    }
  }

  get surfaceLine(): string {
    if (!this.report) return '';
    return Object.entries(this.report.surface).map(([k, v]) => `${k} ${v}`).join(', ');
  }

  get hasProblems(): boolean {
    return !!this.report && (this.report.summary.warn > 0 || this.report.summary.error > 0);
  }
}
