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

  /** Unity Catalog sync state (Databricks and object-store Iceberg pipelines that opted in):
   *  { enabled, coordinates, state: 'never' | 'synced' | 'error', lastSyncAt, lastRunId, lastError,
   *    lineage: 'off' | 'never' | 'published' | 'error', lineageEnabled, lineageHash, lastLineageAt,
   *    register: 'off' | 'never' | 'registered' | 'stale' | 'error' | 'rest' | 'refused', registerEnabled,
   *    registeredMetadataLocation, lastRegisterAt, catalogMode: 'register' | 'rest',
   *    restMetadataLocation, lastRestCommitAt, restRefusedReason }. */
  getUnityCatalog(name: string): Observable<any> {
    return this.http.get<any>('/api/v1/pipelines/' + encodeURIComponent(name) + '/unity-catalog');
  }

  deletePipeline(name: string): Observable<any> {
    return this.http.delete<any>('/api/v1/pipeline?pipeline=' + encodeURIComponent(name));
  }

  // --- Catalog-level operations ---------------------------------------------
  // Server-side rename/delete of a whole catalog. Both resolve with the parsed
  // body on 200 and on 207 (per-item failures in `failed`); 400/404/409 come
  // through `error` with the server's JSON body intact. A 404 means the catalog
  // no longer exists (renamed or deleted elsewhere); callers show a
  // refresh message and write nothing.
  renameCatalog(name: string, newName: string): Observable<any> {
    return this.http.put<any>('/api/v1/catalog/' + encodeURIComponent(name), { newName });
  }

  deleteCatalog(name: string, mode: 'detach' | 'cascade', confirm?: string): Observable<any> {
    let url = '/api/v1/catalog/' + encodeURIComponent(name) + '?mode=' + encodeURIComponent(mode);
    if (mode === 'cascade' && confirm != null) url += '&confirm=' + encodeURIComponent(confirm);
    return this.http.delete<any>(url);
  }

  deletePipelineData(name: string): Observable<any> {
    return this.http.delete<any>('/api/v1/pipeline?pipeline=' + encodeURIComponent(name) + '&deleteData=true&deleteConfig=false', { responseType: 'text' as 'json' });
  }

  createPipeline(config: any): Observable<string> {
    return this.http.post('/api/v1/pipeline', config, { responseType: 'text' });
  }

  // Structured destinations installed on this instance. Mirrors
  // TapService.getAvailableVectorStores (/api/v1/vector-stores/available).
  getAvailableDestinations(): Observable<string[]> {
    return this.http.get<string[]>('/api/v1/destinations/available');
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

  // --- Destination-side typing ----------------------------------------------
  // Stateless propose/apply pair: GET infers types on demand from landed
  // rows; POST migrates the destination table and writes the typed config.
  getDestTypes(name: string): Observable<any> {
    return this.http.get<any>('/api/v1/pipeline/dest-types?pipeline=' + encodeURIComponent(name));
  }

  applyDestTypes(name: string, fields: Array<{name: string, type: string}>): Observable<any> {
    return this.http.post<any>('/api/v1/pipeline/dest-types', { pipeline: name, fields });
  }

  // --- Definition version history -------------------------------------------
  getPipelineVersions(name: string): Observable<any[]> {
    return this.http.get<any[]>('/api/v1/pipeline/versions?name=' + encodeURIComponent(name));
  }

  getPipelineVersion(name: string, version: number): Observable<any> {
    return this.http.get<any>('/api/v1/pipeline/version?name=' + encodeURIComponent(name) + '&version=' + version);
  }

  diffPipelineVersions(name: string, version: number, against: number): Observable<any> {
    return this.http.get<any>('/api/v1/pipeline/version/diff?name=' + encodeURIComponent(name) +
      '&version=' + version + '&against=' + against);
  }

  restorePipelineVersion(name: string, version: number): Observable<any> {
    return this.http.post<any>('/api/v1/pipeline/version/restore?name=' + encodeURIComponent(name) +
      '&version=' + version, {});
  }
}
