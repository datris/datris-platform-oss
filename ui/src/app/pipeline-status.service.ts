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
