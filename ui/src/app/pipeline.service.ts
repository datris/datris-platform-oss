import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class PipelineService {
  constructor(private http: HttpClient) { }

  getPipelines(): Observable<any[]> {
    return this.http.get<any[]>('/api/v1/pipelines');
  }

  getPipeline(name: string): Observable<any> {
    return this.http.get<any>('/api/v1/pipeline?pipeline=' + encodeURIComponent(name));
  }

  deletePipeline(name: string): Observable<any> {
    return this.http.delete<any>('/api/v1/pipeline?pipeline=' + encodeURIComponent(name));
  }

  deletePipelineData(name: string): Observable<any> {
    return this.http.delete<any>('/api/v1/pipeline?pipeline=' + encodeURIComponent(name) + '&deleteData=true&deleteConfig=false', { responseType: 'text' as 'json' });
  }

  createPipeline(config: any): Observable<string> {
    return this.http.post('/api/v1/pipeline', config, { responseType: 'text' });
  }

  uploadConfigFile(file: File, type: string): Observable<any> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<any>('/api/v1/config/upload?type=' + encodeURIComponent(type), formData);
  }

  generateValidationSchema(type: string, name: string, sampleData: string): Observable<any> {
    return this.http.post<any>('/api/v1/config/generate-schema', { type, name, sampleData });
  }

  generateSchema(file: File, dataset: string, delimiter?: string, header?: boolean): Observable<any> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('pipeline', dataset);
    if (delimiter) formData.append('delimiter', delimiter);
    if (header !== undefined) formData.append('header', String(header));
    return this.http.post<any>('/api/v1/pipeline/generate', formData);
  }
}
