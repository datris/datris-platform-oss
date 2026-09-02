import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/** Deterministic lineage views (v1.26): the config-derived graph
 *  Source → Tap → Pipeline → Dataset → Catalog, plus per-pipeline freshness.
 *  Server logic lives in LineageService (Scala); this is a thin typed client. */

export interface LineageNode {
  id: string;
  type: 'source' | 'tap' | 'pipeline' | 'dataset' | 'catalog';
  name: string;
  catalog?: string;
}

export interface LineageEdge {
  from: string;
  to: string;
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
}

@Injectable({ providedIn: 'root' })
export class LineageService {
  constructor(private http: HttpClient) { }

  graph(): Observable<LineageGraph> {
    return this.http.get<LineageGraph>('/api/v1/lineage');
  }

  neighborhood(type: string, name: string): Observable<LineageNeighborhood> {
    return this.http.get<LineageNeighborhood>(
      '/api/v1/lineage/' + encodeURIComponent(type) + '/' + encodeURIComponent(name)
    );
  }
}
