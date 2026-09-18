import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface PipelineStatus {
  pipelineToken: string;
  pipeline: string;
  process: string;
  startTime: string;
  endTime: string;
  totalTime: string;
  status: string;
  recordCount?: number;
  dataType?: string;
  aiSummary?: string;
}

export interface PipelineStatusDetail {
  dateTime: string;
  pipeline: string;
  processName: string;
  publisherToken: string;
  pipelineToken: string;
  filename: string;
  state: string;
  code: string;
  description: string;
  aiSummary?: string;
  aiDiagnosis?: string;
  aiSuggestion?: string;
}

/** One job in the `withrollup=true` wrapper. The `result*` fields are present
 *  only for scratch pipelines on a server that produces them. */
export interface PipelineJobRollup {
  pipelineToken: string;
  pipeline: string;
  filename: string;
  status: string;
  startedAt: string;
  lastEventAt: string;
  elapsed: string;
  lastError: { processName: string; description: string } | null;
  resultUri?: string | null;
  resultRowCount?: number | null;
  resultExpiresAt?: string | null;
  resultPreview?: any[] | null;
  resultTruncated?: boolean | null;
}

export interface PipelineStatusRollup {
  allDone: boolean;
  status: string;
  jobs: PipelineJobRollup[];
}

export interface PipelineStatusRollupResponse {
  rollup: PipelineStatusRollup;
  events: PipelineStatusDetail[];
}

/** One page of a scratch pipeline's result from GET /api/v1/pipeline/result. */
export interface PipelineResultPage {
  records: any[];
  rowCount: number;
  returnedCount: number;
  offset: number;
  truncated: boolean;
  resultUri?: string;
  resultExpiresAt?: string;
}

@Injectable({
  providedIn: 'root'
})
export class PipelineStatusService {
  private apiUrl = '/api/v1/pipeline/status';

  constructor(private http: HttpClient) { }

  getPipelineStatus(page: number): Observable<PipelineStatus[]> {
    return this.http.get<PipelineStatus[]>(this.apiUrl + "?page=" + String(page));
  }

  getPipelineStatusDetail(pipelineToken: string) {
    return this.http.get<PipelineStatusDetail[]>(this.apiUrl + "?pipelinetoken=" + pipelineToken);
  }

  /** Same query with `withrollup=true`: `{rollup, events}` where `events` is
   *  the array getPipelineStatusDetail returns. */
  getPipelineStatusDetailWithRollup(pipelineToken: string): Observable<PipelineStatusRollupResponse> {
    return this.http.get<PipelineStatusRollupResponse>(
      this.apiUrl + "?pipelinetoken=" + encodeURIComponent(pipelineToken) + "&withrollup=true");
  }

  /** Page through a scratch pipeline's result. `offset` and `limit` are sent
   *  only when supplied — the server clamps `limit` to its inline cap, so
   *  callers step by the returned page's `returnedCount`. */
  getPipelineResult(pipelineToken: string, offset?: number, limit?: number): Observable<PipelineResultPage> {
    let url = '/api/v1/pipeline/result?pipelinetoken=' + encodeURIComponent(pipelineToken);
    if (offset !== undefined && offset !== null) url += '&offset=' + offset;
    if (limit !== undefined && limit !== null) url += '&limit=' + limit;
    return this.http.get<PipelineResultPage>(url);
  }

  clearAllStatus(): Observable<any> {
    return this.http.delete<any>(this.apiUrl);
  }

  uploadFile(file: File, pipeline: string): Observable<string> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append("pipeline", pipeline);
    return this.http.post('/api/v1/pipeline/upload', formData, { responseType: 'text' });
  }
}
