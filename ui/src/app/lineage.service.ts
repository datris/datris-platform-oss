import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

/** Deterministic lineage views (v1.26): the config-derived graph
 *  Source → Tap → Pipeline → Dataset → Catalog, plus per-pipeline freshness.
 *  Server logic lives in LineageService (Scala); this is a thin typed client. */

export type LineageNodeType = 'source' | 'tap' | 'pipeline' | 'dataset' | 'catalog';

export interface LineageNode {
  id: string;
  type: LineageNodeType;
  name: string;
  catalog?: string;
  tags?: string[];
  /** A dataset no current config lands into, but a recorded run did. */
  historical?: boolean;
}

export interface LineageEdge {
  from: string;
  to: string;
  historical?: boolean;
}

/** One recorded pipeline run (v1.27): what it read and wrote. */
export interface LineageRunOutput {
  kind: string;
  coords?: string;
  datasetId?: string;
  status: 'SUCCESS' | 'ERROR' | 'UNKNOWN' | string;
  recordCount: number;
  error?: string;
}

export interface LineageRun {
  runId: string;
  pipeline?: string;
  configVersion: number;
  status?: string;
  startedAt?: string;
  completedAt?: string;
  durationMs: number;
  recordCount: number;
  input?: { kind: string; tapName?: string; tapRunTime?: string; scriptSha?: string; source?: string; filename?: string };
  outputs: LineageRunOutput[];
}

export interface LineageNeighborhoodOptions {
  direction?: 'up' | 'down' | 'both';
  depth?: number;
  runs?: number;
  columns?: boolean;
}

/** Column-level lineage (L3): one edge per destination column. */
export interface ColumnEdge {
  from: string[];
  to: string;
  op: 'passthrough' | 'rename' | 'derive' | 'drop' | 'system' | string;
  confidence: 'exact' | 'inferred' | 'system' | string;
  evidence?: string;
}

export interface ColumnLineage {
  pipeline: string;
  version: number;
  versionSource: 'current' | 'snapshot';
  sourceFields: string[];
  destinationFields: string[];
  destinationSchema: 'declared' | 'inherited' | 'none';
  transformation: { kind: 'ai' | 'rowFunctions' | 'preprocessor' | 'none'; instruction?: string };
  edges: ColumnEdge[];
  unresolved: string[];
  inferred: { available: boolean; computed?: boolean; computedAt?: string; model?: string; note?: string; error?: string };
}

export interface LineageGraph {
  nodes: LineageNode[];
  edges: LineageEdge[];
}

export interface LineageFreshness {
  state: 'fresh' | 'stale' | 'unknown';
  lastLandedAt?: string;
  recordCount?: number;
  latestRunId?: string;
  cursorUpdatedAt?: string;
}

export interface LineageNeighborhood {
  node: LineageNode;
  upstream: LineageNode[];
  downstream: LineageNode[];
  edges: LineageEdge[];
  freshness?: LineageFreshness;
  runs?: LineageRun[];
  columns?: ColumnLineage;
}

@Injectable({ providedIn: 'root' })
export class LineageService {
  constructor(private http: HttpClient) { }

  graph(): Observable<LineageGraph> {
    return this.http.get<LineageGraph>('/api/v1/lineage');
  }

  neighborhood(type: string, name: string, opts?: LineageNeighborhoodOptions): Observable<LineageNeighborhood> {
    let params = new HttpParams();
    if (opts?.direction) params = params.set('direction', opts.direction);
    if (opts?.depth) params = params.set('depth', String(opts.depth));
    if (opts?.runs) params = params.set('runs', String(opts.runs));
    if (opts?.columns) params = params.set('columns', 'true');
    return this.http.get<LineageNeighborhood>(
      '/api/v1/lineage/' + encodeURIComponent(type) + '/' + encodeURIComponent(name),
      { params }
    );
  }

  /** Column lineage for one pipeline definition; `infer` runs the opt-in AI tier. */
  columns(pipeline: string, opts?: { version?: number; infer?: boolean }): Observable<ColumnLineage> {
    let params = new HttpParams();
    if (opts?.version) params = params.set('version', String(opts.version));
    if (opts?.infer) params = params.set('infer', 'true');
    return this.http.get<ColumnLineage>('/api/v1/lineage/columns/' + encodeURIComponent(pipeline), { params });
  }
}
