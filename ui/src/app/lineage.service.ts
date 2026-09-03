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
    return this.http.get<LineageNeighborhood>(
      '/api/v1/lineage/' + encodeURIComponent(type) + '/' + encodeURIComponent(name),
      { params }
    );
  }
}
