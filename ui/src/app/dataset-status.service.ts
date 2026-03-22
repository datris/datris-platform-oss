import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface DatasetStatus {
  pipelineToken: string;
  dataset: string;
  process: string;
  startTime: string;
  endTime: string;
  totalTime: string;
  status: string;
}

export interface DatasetStatusDetail {
  dateTime: string;
  dataset: string;
  processName: string;
  publisherToken: string;
  pipelineToken: string;
  filename: string;
  state: string;
  code: string;
  description: string;
}

@Injectable({
  providedIn: 'root'
})
export class DatasetStatusService {
  private apiUrl = '/api/v1/dataset/status';

  constructor(private http: HttpClient) { }

  getDatasetStatus(page: number): Observable<DatasetStatus[]> {
    return this.http.get<DatasetStatus[]>(this.apiUrl + "?page=" + String(page));
  }

  getDatasetStatusDetail(pipelineToken: string) {
    return this.http.get<DatasetStatusDetail[]>(this.apiUrl + "?pipelinetoken=" + pipelineToken);
  }

  uploadFile(file: File, dataset: string): Observable<string> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('dataset', dataset);
    return this.http.post('/api/v1/dataset/upload', formData, { responseType: 'text' });
  }
}
