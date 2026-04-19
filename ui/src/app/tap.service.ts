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

  runTap(name: string, pushToPipeline: boolean = false): Observable<any> {
    return this.http.post<any>('/api/v1/tap/run', { name, pushToPipeline: String(pushToPipeline) });
  }

  fixScript(tapName: string, script: string, diagnosis: string, logs: string, error: string, oldScriptPath?: string): Observable<any> {
    return this.http.post<any>('/api/v1/tap/fix', { tapName, script, diagnosis, logs, error, oldScriptPath: oldScriptPath || null });
  }

  optimizeScript(tapName: string, script: string, recordCount: number, durationMs: number, logs: string, oldScriptPath?: string): Observable<any> {
    return this.http.post<any>('/api/v1/tap/optimize', { tapName, script, recordCount, durationMs, logs, oldScriptPath: oldScriptPath || null });
  }

  getTapLogs(name: string): Observable<any[]> {
    return this.http.get<any[]>('/api/v1/tap/logs?name=' + encodeURIComponent(name));
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
}
