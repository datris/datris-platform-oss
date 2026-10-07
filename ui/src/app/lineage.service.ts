import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

/** Deterministic lineage views (v1.26): the config-derived graph
 *  Source → Tap → Pipeline → Dataset → Catalog, plus per-pipeline freshness.
 *  Server logic lives in LineageService (Scala); this is a thin typed client. */

export type LineageNodeType = 'source' | 'tap' | 'pipeline' | 'dataset' | 'catalog';

export type LineageAuthority = 'authoritative' | 'derived' | 'undeclared';

export interface LineageNode {
  id: string;
  type: LineageNodeType;
  name: string;
  catalog?: string;
  tags?: string[];
  /** A dataset no current config lands into, but a recorded run did. */
  historical?: boolean;
  /** Dataset nodes only: which copy may be cited (L5b). */
  authority?: LineageAuthority;
  /** Dataset nodes only: storage format of the landed copy (e.g. an Iceberg table). */
  format?: string;
}

/** What traversed an edge inside the evidence window (L5a). Absent = a
 *  configuration claim with no recorded run behind it. */
export interface EdgeEvidence {
  runs: number;
  records: number;
  lastRunAt?: string;
  lastStatus?: string;
  failedRuns: number;
  windowDays: number;
}

export interface LineageEdge {
  from: string;
  to: string;
  historical?: boolean;
  evidence?: EdgeEvidence;
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
  transformation: {
    kind: 'ai' | 'rowFunctions' | 'preprocessor' | 'protect' | 'none';
    instruction?: string;
    /** Stored CodeGen script for an AI transformation (when one is recorded). */
    scriptGeneratedAt?: string;
    scriptModel?: string;
    scriptStatus?: 'ready' | 'pending' | string;
    scriptPendingReason?: string;
    /** 'minio' (built-in) | 'github' (code repository). */
    scriptStorage?: string;
    /** Recorded commit of a repository-backed script. */
    scriptCommitSha?: string;
    /** Unresolved repository conflict: runs keep the recorded commit until pull or overwrite. */
    scriptConflict?: string;
  };
  edges: ColumnEdge[];
  unresolved: string[];
  inferred: { available: boolean; computed?: boolean; computedAt?: string; model?: string; note?: string; error?: string };
}

/** One stored CodeGen script (GET /api/v1/pipelines/{name}/codegen-scripts). */
export interface CodegenScript {
  kind: 'dataQuality' | 'transformation' | string;
  instruction: string;
  script?: string | null;
  generatedAt?: string | null;
  model?: string | null;
  modelIsCurrent?: boolean;
  status: 'ready' | 'pending' | string;
  pendingReason?: string | null;
  origin?: 'save' | 'run' | 'regenerate' | 'repository' | string | null;
  /** 'minio' (built-in) | 'github' (code repository). */
  storage?: string | null;
  /** Repository-backed scripts: file path and the commit runs execute. */
  repoPath?: string | null;
  commitSha?: string | null;
  /** True when the file at branch head differs from the recorded commit. */
  drift?: boolean;
  /** Branch-head commit, present when `drift` is true. */
  headSha?: string;
  /** True while a new script was not committed because the repository file changed; runs keep the recorded commit. */
  conflict?: boolean;
  conflictReason?: string;
}

/** Options for a forced regenerate. */
export interface RegenerateOptions {
  /** Move the script: 'github' (code repository) or 'builtin'. Default: its current backend. */
  storage?: 'github' | 'builtin';
  /** Replace a repository file that was edited since the recorded commit. */
  overwrite?: boolean;
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

  /** The pipeline's stored CodeGen scripts (AI rule, AI transformation). */
  codegenScripts(pipeline: string): Observable<{ pipeline: string; scripts: CodegenScript[] }> {
    return this.http.get<{ pipeline: string; scripts: CodegenScript[] }>(
      '/api/v1/pipelines/' + encodeURIComponent(pipeline) + '/codegen-scripts'
    );
  }

  /** Force a new CodeGen script for one kind; resolves with the new entry. */
  regenerateCodegenScript(pipeline: string, kind: string, opts?: RegenerateOptions): Observable<CodegenScript> {
    let params = new HttpParams();
    if (opts?.storage) params = params.set('storage', opts.storage);
    if (opts?.overwrite) params = params.set('overwrite', 'true');
    return this.http.post<CodegenScript>(
      '/api/v1/pipelines/' + encodeURIComponent(pipeline) + '/codegen-scripts/' + encodeURIComponent(kind) + '/regenerate',
      {},
      { params }
    );
  }

  /** Adopt the code repository's branch-head version of a repository-backed script; resolves with the new entry. */
  pullCodegenScript(pipeline: string, kind: string): Observable<CodegenScript> {
    return this.http.post<CodegenScript>(
      '/api/v1/pipelines/' + encodeURIComponent(pipeline) + '/codegen-scripts/' + encodeURIComponent(kind) + '/pull',
      {}
    );
  }
}
