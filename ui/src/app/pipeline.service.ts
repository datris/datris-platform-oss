import { Injectable } from '@angular/core';
import { HttpClient, HttpResponse } from '@angular/common/http';
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

  /** Unity Catalog sync state (Databricks and object-store Iceberg pipelines that opted in, or Databricks pipelines the install default turned on):
   *  { enabled, enabledBy: 'pipeline' | 'default' | null (null = off, no block), syncSwitchedOff (DATRIS_UNITY_CATALOG_SYNC=false), coordinates, state: 'never' | 'synced' | 'error', lastSyncAt, lastRunId, lastError,
   *    lineage: 'off' | 'never' | 'published' | 'error', lineageEnabled, lineageHash, lastLineageAt,
   *    register: 'off' | 'never' | 'registered' | 'stale' | 'error' | 'rest' | 'managed' | 'refused', registerEnabled,
   *    registeredMetadataLocation, lastRegisterAt, catalogMode: 'register' | 'rest' | 'managed',
   *    restMetadataLocation, lastRestCommitAt, restRefusedReason, restCreatedTable }. */
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

  /** Enable order of a catalog's taps: `{catalog, levels: [{level, taps:
   *  [{name, blockedBy, hasPassingTest, lastRealRunLanded, ready, ...}]}],
   *  cycles, warnings}`; level 1 = taps that read no other tap's tables. */
  enableOrder(name: string): Observable<any> {
    return this.http.get<any>('/api/v1/catalog/' + encodeURIComponent(name) + '/enable-order');
  }

  /** Download a catalog as a zip archive. Resolves the full response so the
   *  caller can take the file name from Content-Disposition; the body is the
   *  zip Blob. Errors arrive with a Blob body (responseType 'blob'). */
  exportCatalog(name: string): Observable<HttpResponse<Blob>> {
    return this.http.get('/api/v1/catalog/' + encodeURIComponent(name) + '/export',
      { responseType: 'blob', observe: 'response' });
  }

  /** Restore a catalog archive as catalog `name`. observe:'response' so a 207
   *  (per-item failures in `failed`) resolves through `next` like a 200; a 409
   *  comes through `error` with the server's JSON body (`clashes`). A restore
   *  never merges: mode 'overwrite' replaces items already in `name` and needs
   *  `confirm` equal to the name. `restoreState=false` skips each tap's
   *  incremental state (for a clone). */
  restoreCatalog(name: string, file: File, mode: 'new' | 'overwrite', confirm?: string,
                 restoreState = true): Observable<HttpResponse<any>> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('mode', mode);
    if (mode === 'overwrite' && confirm != null) formData.append('confirm', confirm);
    if (restoreState === false) formData.append('restoreState', 'false');
    return this.http.post<any>('/api/v1/catalog/' + encodeURIComponent(name) + '/restore', formData,
      { observe: 'response' });
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

  // --- Field protection ---------------------------------------------------------
  // Classifier suggestions from field names and types only (never data).
  // Response: {model, fields: [{name, type, current, suggested: {method, preserve} | null, reason}]}
  suggestFieldProtection(fields: Array<{name: string, type: string}>): Observable<any> {
    return this.http.post<any>('/api/v1/pipeline/protect/suggest', { fields });
  }

  // Preset proposal (names and types only, never data). Response: {preset,
  // fields: [{name, class, method, preserve, reason, current}], unclassified:
  // [names], review: [notes]}; `method` is "none" when a clamp leaves nothing.
  presetFieldProtection(preset: string, fields: Array<{name: string, type: string}>): Observable<any> {
    return this.http.post<any>('/api/v1/pipeline/protect/preset', { preset, fields });
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
