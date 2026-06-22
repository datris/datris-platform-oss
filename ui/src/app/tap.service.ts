import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class TapService {
  constructor(private http: HttpClient) { }

  getTaps(): Observable<any[]> {
    return this.http.get<any[]>('/api/v1/taps');
  }

  getTap(name: string): Observable<any> {
    return this.http.get<any>('/api/v1/tap?name=' + encodeURIComponent(name));
  }

  createOrUpdateTap(config: any): Observable<any> {
    return this.http.post<any>('/api/v1/tap', config);
  }

  deleteTap(name: string): Observable<any> {
    return this.http.delete<any>('/api/v1/tap?name=' + encodeURIComponent(name));
  }

  generateCron(description: string): Observable<any> {
    return this.http.post<any>('/api/v1/tap/cron', { description });
  }

  brainstorm(messages: Array<{role: string, content: string}>, currentDescription: string, tapType?: string): Observable<any> {
    return this.http.post<any>('/api/v1/tap/brainstorm', { messages, currentDescription, tapType: tapType || 'structured' });
  }

  generateScript(description: string, tapName: string, oldScriptPath?: string, secretName?: string, tapType?: string): Observable<any> {
    return this.http.post<any>('/api/v1/tap/generate', { description, tapName, oldScriptPath: oldScriptPath || null, secretName: secretName || null, tapType: tapType || 'structured' });
  }

  storeScript(tapName: string, script: string, oldScriptPath?: string): Observable<any> {
    return this.http.post<any>('/api/v1/tap/script', { tapName, script, oldScriptPath: oldScriptPath || null });
  }

  testTap(config: any): Observable<any> {
    const url = config.testLimit
      ? '/api/v1/tap/test?testLimit=' + encodeURIComponent(config.testLimit)
      : '/api/v1/tap/test';
    return this.http.post<any>(url, config);
  }

  runTap(name: string, mode: 'run' | 'test' = 'test'): Observable<any> {
    return this.http.post<any>('/api/v1/tap/run', { name, mode });
  }

  fixScript(tapName: string, script: string, diagnosis: string, logs: string, error: string, oldScriptPath?: string, priorIterations?: any[]): Observable<any> {
    return this.http.post<any>('/api/v1/tap/fix', { tapName, script, diagnosis, logs, error, oldScriptPath: oldScriptPath || null, priorIterations: JSON.stringify(priorIterations || []) });
  }

  optimizeScript(tapName: string, script: string, recordCount: number, durationMs: number, logs: string, oldScriptPath?: string, priorIterations?: any[]): Observable<any> {
    return this.http.post<any>('/api/v1/tap/optimize', { tapName, script, recordCount, durationMs, logs, oldScriptPath: oldScriptPath || null, priorIterations: JSON.stringify(priorIterations || []) });
  }

  reviewScript(tapName: string, script: string, recordCount: number, durationMs: number, logs: string, oldScriptPath?: string, priorIterations?: any[]): Observable<any> {
    return this.http.post<any>('/api/v1/tap/review', { tapName, script, recordCount, durationMs, logs, oldScriptPath: oldScriptPath || null, priorIterations: JSON.stringify(priorIterations || []) });
  }

  getTapLogs(name: string): Observable<any[]> {
    return this.http.get<any[]>('/api/v1/tap/logs?name=' + encodeURIComponent(name));
  }

  // All-taps time-range query for the Ops activity dashboard. Server-side
  // indexed scan on the created_at field, capped to `limit` rows.
  getAllTapLogsSince(sinceEpochMs: number, limit: number = 2000): Observable<any[]> {
    const url = '/api/v1/tap/logs/all?since=' + sinceEpochMs + '&limit=' + limit;
    return this.http.get<any[]>(url);
  }

  getTapLedger(name: string): Observable<any[]> {
    return this.http.get<any[]>('/api/v1/tap/ledger?name=' + encodeURIComponent(name));
  }

  deleteLedgerEntry(name: string, uri: string): Observable<any> {
    return this.http.delete<any>('/api/v1/tap/ledger?name=' + encodeURIComponent(name) + '&uri=' + encodeURIComponent(uri));
  }

  clearLedger(name: string): Observable<any> {
    return this.http.delete<any>('/api/v1/tap/ledger?name=' + encodeURIComponent(name));
  }

  getPipelines(): Observable<any[]> {
    return this.http.get<any[]>('/api/v1/pipelines');
  }

  getAvailableVectorStores(): Observable<string[]> {
    return this.http.get<string[]>('/api/v1/vector-stores/available');
  }

  // --- Definition version history -------------------------------------------
  getTapVersions(name: string): Observable<any[]> {
    return this.http.get<any[]>('/api/v1/tap/versions?name=' + encodeURIComponent(name));
  }

  getTapVersion(name: string, version: number): Observable<any> {
    return this.http.get<any>('/api/v1/tap/version?name=' + encodeURIComponent(name) + '&version=' + version);
  }

  diffTapVersions(name: string, version: number, against: number): Observable<any> {
    return this.http.get<any>('/api/v1/tap/version/diff?name=' + encodeURIComponent(name) +
      '&version=' + version + '&against=' + against);
  }

  restoreTapVersion(name: string, version: number): Observable<any> {
    return this.http.post<any>('/api/v1/tap/version/restore?name=' + encodeURIComponent(name) +
      '&version=' + version, {});
  }
}
