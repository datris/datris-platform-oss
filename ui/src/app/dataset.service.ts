import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class DatasetService {
  constructor(private http: HttpClient) { }

  getDatasets(): Observable<any[]> {
    return this.http.get<any[]>('/api/v1/datasets');
  }

  getDataset(name: string): Observable<any> {
    return this.http.get<any>('/api/v1/dataset?dataset=' + encodeURIComponent(name));
  }

  deleteDataset(name: string): Observable<any> {
    return this.http.delete<any>('/api/v1/dataset?dataset=' + encodeURIComponent(name));
  }

  createDataset(config: any): Observable<any> {
    return this.http.post<any>('/api/v1/dataset', config);
  }

  generateSchema(file: File, dataset: string, delimiter?: string, header?: boolean): Observable<any> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('dataset', dataset);
    if (delimiter) formData.append('delimiter', delimiter);
    if (header !== undefined) formData.append('header', String(header));
    return this.http.post<any>('/api/v1/dataset/generate', formData);
  }
}
