import { Component, OnInit, OnDestroy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router, NavigationEnd } from '@angular/router';
import { Subscription, from } from 'rxjs';
import { concatMap, filter, map } from 'rxjs/operators';
import { SearchService, QueryResponse, RevealResponse } from '../search.service';
import { HealthService } from '../health.service';
import { PipelineService } from '../pipeline.service';
import { AuthService } from '../auth.service';
import { httpErrorText } from '../shared/http-error';

@Component({
    selector: 'app-search',
    templateUrl: './search.component.html',
    styleUrls: ['./search.component.css'],
    standalone: false
})
export class SearchComponent implements OnInit, OnDestroy {
  queryType = 'postgres';
  loading = false;
  error = '';
  results: any[] = [];
  columns: string[] = [];
  resultCount = 0;
  /** True once a query has succeeded (drives the "0 results" line). */
  executed = false;
  /** Iceberg snapshot id carried by the last object-store query, if any. */
  snapshotId: string | null = null;
  snapshotTimestamp: string | null = null;

  // AI answer
  aiAnswer = '';
  aiLoading = false;
  aiError = '';

  // PostgreSQL fields
  pgSql = '';
  pgDatabase = 'datris';
  pgLimit = 100;
  pgSchemas: string[] = [];
  pgTables: string[] = [];
  vectorTables: string[] = [];
  pgSelectedSchema = '';
  pgSelectedTable = '';

  // MongoDB fields
  mongoDatabase = '';
  mongoCollection = '';
  mongoCollections: string[] = [];
  mongoFilter = '{}';
  mongoProjection = '';
  mongoLimit = 20;

  // Vector search fields
  searchQuery = '';
  searchCollection = '';
  searchClassName = '';
  searchTable = '';
  searchSchema = 'public';
  embeddingSecretName = 'oss/embedding';
  vectorSecretName = 'oss/pgvector';
  topK = 5;

  // Object Store fields
  osPipelines: { name: string; bucket: string; prefix: string; format: string; provider: string }[] = [];
  osSelectedPipeline = '';
  osLimit = 100;

  // Warehouse (Databricks / Snowflake) fields. Remote warehouses are not in
  // the health check, so the options are gated on pipelines alone.
  whPipelines: { name: string; kind: 'databricks' | 'snowflake'; qualified: string }[] = [];
  whSelectedPipeline = '';
  whSql = '';
  whLimit = 100;

  // Field protection: ciphertext badges and Reveal. Revealed plaintext lives
  // only in these in-memory maps and is dropped on every execute; it is never
  // written to browser storage.
  /** Full configs from GET /api/v1/pipelines (carry source fields' `protect`). */
  private allPipelines: any[] = [];
  /** `pg:<db>.<schema>.<table>` / `mongo:<db>.<collection>` (lowercase) → pipeline names. */
  private destIndex = new Map<string, string[]>();
  /** Pipeline configs fetched for binding, cached per name. */
  private pipelineCache = new Map<string, any>();
  /** Pipeline the current result set binds to ('' when none). */
  revealPipeline = '';
  /** Postgres/MongoDB: the user's choice in the picker. */
  revealPick = '';
  /** Picker options when inference did not find exactly one pipeline. */
  revealCandidates: string[] = [];
  /** True when the picker is needed (postgres/mongodb, not exactly one match). */
  revealNeedsPick = false;
  /** Lowercase column name → source field name of the bound pipeline's `encrypt` fields; null = no pipeline bound. */
  private encryptFields: Map<string, string> | null = null;
  /** Result columns holding ciphertext that the bound pipeline encrypts. */
  encryptedColumns: string[] = [];
  /** True when any result cell (top-level) looks like a Datris ciphertext. */
  hasCiphertext = false;
  /** ciphertext → plaintext, for this result set only. */
  revealed = new Map<string, string>();
  /** ciphertext → server message for values that did not reveal. */
  revealErrors = new Map<string, string>();
  revealing = false;
  revealError = '';
  /** MongoDB: every document is flat (top-level scalars only), so it renders as a table. */
  mongoFlat = false;

  isTrial = false;
  private routerSub: Subscription | null = null;

  // Sub-panel toggle: the new conversational "Chat" search vs. the existing
  // structured "Traditional" query UI. Chat is the default. Persisted in
  // sessionStorage so the choice survives navigating away and back (the
  // conversation itself lives in a root singleton; this is just the view).
  private static readonly VIEW_KEY = 'search.activeView';
  activeView: 'chat' | 'traditional' = 'chat';

  constructor(private searchService: SearchService, public healthService: HealthService, private http: HttpClient, private router: Router,
              private pipelineService: PipelineService, private auth: AuthService) { }

  setActiveView(view: 'chat' | 'traditional'): void {
    this.activeView = view;
    try { sessionStorage.setItem(SearchComponent.VIEW_KEY, view); } catch { /* ignore */ }
  }

  ngOnInit(): void {
    const savedView = (() => { try { return sessionStorage.getItem(SearchComponent.VIEW_KEY); } catch { return null; } })();
    if (savedView === 'chat' || savedView === 'traditional') this.activeView = savedView;

    // Re-fetch health when the tab opens so store gating recovers from a failed
    // bootstrap fetch or a server restart; then make sure the selected query
    // type is still one of the visible options.
    this.healthService.refresh().then(() => this.ensureQueryTypeAvailable());

    this.http.get<any>('/api/v1/version').subscribe({
      next: (data) => {
        this.isTrial = data.multiTenant === 'true';
        // Canonical, server-chosen db names.
        this.pgDatabase = data.postgresDatabase || 'datris';
        this.mongoDatabase = data.mongodbDatabase || 'datris';
        this.loadPgSchemas();
        this.loadMongoCollections();
        this.loadObjectStorePipelines();
      }
    });

    // Refresh metadata whenever the user navigates back to /search
    this.routerSub = this.router.events.pipe(
      filter(e => e instanceof NavigationEnd)
    ).subscribe((e: any) => {
      if (e.urlAfterRedirects === '/search' || e.url === '/search') {
        this.refreshMetadata();
      }
    });
  }

  ngOnDestroy(): void {
    if (this.routerSub) {
      this.routerSub.unsubscribe();
      this.routerSub = null;
    }
  }

  refreshMetadata(): void {
    // Reload postgres schemas/tables and mongo collections so the user
    // sees any objects created since they last visited the tab.
    this.searchService.getPostgresSchemas(this.pgDatabase).subscribe({
      next: (schemas) => {
        this.pgSchemas = schemas;
        if (this.pgSchemas.length > 0 && !this.pgSelectedSchema) {
          this.pgSelectedSchema = this.pgSchemas.includes('public') ? 'public' : this.pgSchemas[0];
        }
        if (this.pgSelectedSchema) this.loadPgTables();
      },
      error: () => {}
    });
    this.loadMongoCollections();
    this.loadObjectStorePipelines();
  }

  loadObjectStorePipelines(): void {
    this.searchService.getPipelines().subscribe({
      next: (configs) => {
        this.osPipelines = (configs || [])
          .filter(c => c && c.destination && c.destination.objectStore)
          .map(c => ({
            name: c.name,
            bucket: c.destination.objectStore.destinationBucketOverride || '(default)',
            prefix: c.destination.objectStore.prefixKey || '',
            format: (c.destination.objectStore.fileFormat || 'parquet').toLowerCase(),
            provider: (c.destination.objectStore.provider || 'minio').toLowerCase()
          }));
        if (this.osPipelines.length > 0 && !this.osSelectedPipeline) {
          this.osSelectedPipeline = this.osPipelines[0].name;
        }
        this.setWarehousePipelines(configs);
        this.setDestIndex(configs);
      },
      error: () => { this.osPipelines = []; this.whPipelines = []; this.setDestIndex([]); }
    });
  }

  /** Databricks and Snowflake pipelines from the same /pipelines response the
   * object-store picker uses. Both name their target as dbName.schema.table
   * (Databricks: dbName is the Unity Catalog catalog). */
  private setWarehousePipelines(configs: any[]): void {
    this.whPipelines = (configs || [])
      .filter(c => c && c.destination && c.destination.database &&
        (c.destination.database.useDatabricks || c.destination.database.useSnowflake))
      .map(c => {
        const db = c.destination.database;
        return {
          name: c.name,
          kind: (db.useDatabricks ? 'databricks' : 'snowflake') as 'databricks' | 'snowflake',
          qualified: [db.dbName, db.schema, db.table].filter((p: any) => !!p).join('.')
        };
      });
    if (this.isWarehouse()) this.ensureWarehousePipelineSelected();
  }

  warehousePipelines(kind: string = this.queryType): { name: string; kind: string; qualified: string }[] {
    return this.whPipelines.filter(p => p.kind === kind);
  }

  hasWarehousePipelines(kind: string): boolean {
    return this.warehousePipelines(kind).length > 0;
  }

  selectedWarehouseMeta(): { name: string; kind: string; qualified: string } | null {
    return this.warehousePipelines().find(p => p.name === this.whSelectedPipeline) || null;
  }

  warehouseSqlPlaceholder(): string {
    const meta = this.selectedWarehouseMeta();
    return 'SELECT * FROM ' + (meta && meta.qualified ? meta.qualified : 'catalog.schema.table') + ' LIMIT 10';
  }

  private ensureWarehousePipelineSelected(): void {
    const list = this.warehousePipelines();
    if (!list.some(p => p.name === this.whSelectedPipeline)) {
      this.whSelectedPipeline = list.length > 0 ? list[0].name : '';
    }
  }

  selectedObjectStoreMeta(): { bucket: string; prefix: string; format: string; provider: string } | null {
    return this.osPipelines.find(p => p.name === this.osSelectedPipeline) || null;
  }

  loadPgSchemas(): void {
    this.searchService.getPostgresSchemas(this.pgDatabase).subscribe({
      next: (schemas) => {
        this.pgSchemas = schemas;
        if (this.pgSchemas.length > 0 && !this.pgSelectedSchema) {
          this.pgSelectedSchema = this.pgSchemas.includes('public') ? 'public' : this.pgSchemas[0];
          this.loadPgTables();
        }
      },
      error: () => { this.pgSchemas = []; }
    });
  }

  loadPgTables(): void {
    if (!this.pgSelectedSchema) return;
    this.searchService.getPostgresTables(this.pgDatabase, this.pgSelectedSchema).subscribe({
      next: (tables) => { this.pgTables = tables; },
      error: () => { this.pgTables = []; }
    });
    // Also load vector tables for pgvector dropdown
    this.searchService.getPostgresTables(this.pgDatabase, this.pgSelectedSchema, true).subscribe({
      next: (tables) => { this.vectorTables = tables; },
      error: () => { this.vectorTables = []; }
    });
  }

  onPgSchemaChange(): void {
    this.pgSelectedTable = '';
    this.loadPgTables();
  }

  onSearchSchemaChange(): void {
    this.searchTable = '';
    this.searchService.getPostgresTables(this.pgDatabase, this.searchSchema, true).subscribe({
      next: (tables) => { this.vectorTables = tables; },
      error: () => { this.vectorTables = []; }
    });
  }

  onPgTableSelect(): void {
    if (this.pgSelectedSchema && this.pgSelectedTable) {
      this.searchService.getPostgresColumns(this.pgDatabase, this.pgSelectedSchema, this.pgSelectedTable).subscribe({
        next: (columns) => {
          // Double-quote every identifier so columns like `eps estimate` and
          // `surprise(%)` survive parsing. Embedded quotes get escaped per the
          // SQL standard (`"` → `""`).
          const quote = (id: string) => '"' + id.replace(/"/g, '""') + '"';
          const colNames = columns.map((c: any) => quote(c.name)).join(', ');
          this.pgSql = 'SELECT ' + colNames + ' FROM ' + quote(this.pgSelectedSchema) + '.' + quote(this.pgSelectedTable);
        },
        error: () => {
          const quote = (id: string) => '"' + id.replace(/"/g, '""') + '"';
          this.pgSql = 'SELECT * FROM ' + quote(this.pgSelectedSchema) + '.' + quote(this.pgSelectedTable);
        }
      });
    }
  }

  loadMongoCollections(): void {
    if (!this.mongoDatabase) return;
    this.searchService.getMongoCollections(this.mongoDatabase).subscribe({
      next: (collections) => {
        this.mongoCollections = collections;
        if (collections.length > 0 && !this.mongoCollection) {
          this.mongoCollection = collections[0];
        }
      },
      error: () => { this.mongoCollections = []; }
    });
  }

  getVectorSecretLabel(): string {
    const labels: Record<string, string> = {
      qdrant: 'Qdrant Secret Name',
      weaviate: 'Weaviate Secret Name',
      milvus: 'Milvus Secret Name',
      chroma: 'Chroma Secret Name',
      pgvector: 'PostgreSQL Secret Name'
    };
    return labels[this.queryType] || 'Secret Name';
  }

  getDefaultVectorSecret(): string {
    const defaults: Record<string, string> = {
      qdrant: 'oss/qdrant',
      weaviate: 'oss/weaviate',
      milvus: 'oss/milvus',
      chroma: 'oss/chroma',
      pgvector: 'oss/pgvector'
    };
    return defaults[this.queryType] || '';
  }

  onQueryTypeChange(): void {
    this.results = [];
    this.columns = [];
    this.error = '';
    this.resultCount = 0;
    this.executed = false;
    this.snapshotId = null;
    this.snapshotTimestamp = null;
    this.vectorSecretName = this.getDefaultVectorSecret();
    this.mongoFlat = false;
    this.resetReveal();
    this.revealPick = '';
    if (this.isWarehouse()) this.ensureWarehousePipelineSelected();
  }

  /** If health gating hid the currently-selected query type, fall back to the
   * first visible option (dropdown order) so the select never sits on a value
   * that has no matching <option>. */
  private ensureQueryTypeAvailable(): void {
    if (this.isWarehouse()) return;
    const healthKey: Record<string, string> = {
      postgres: 'postgres', mongodb: 'mongodb', objectstore: 'minio',
      qdrant: 'qdrant', weaviate: 'weaviate', milvus: 'milvus', chroma: 'chroma', pgvector: 'pgvector'
    };
    if (this.healthService.isAvailable(healthKey[this.queryType])) return;
    const fallback = Object.keys(healthKey).find(t => this.healthService.isAvailable(healthKey[t]));
    if (fallback) {
      this.queryType = fallback;
      this.onQueryTypeChange();
    }
  }

  isVectorSearch(): boolean {
    return ['qdrant', 'weaviate', 'milvus', 'chroma', 'pgvector'].includes(this.queryType);
  }

  isObjectStore(): boolean {
    return this.queryType === 'objectstore';
  }

  isWarehouse(): boolean {
    return this.queryType === 'databricks' || this.queryType === 'snowflake';
  }

  retrieveAllMongo(): void {
    this.mongoFilter = '{}';
    this.mongoProjection = '';
    this.mongoLimit = 1000;
    this.execute();
  }

  retrieveAllPostgres(): void {
    if (this.pgSelectedSchema && this.pgSelectedTable) {
      this.pgSql = 'SELECT * FROM "' + this.pgSelectedSchema + '"."' + this.pgSelectedTable + '"';
    }
    this.pgLimit = 1000;
    this.execute();
  }

  execute(): void {
    this.error = '';

    if (this.isVectorSearch() && !this.searchQuery.trim()) {
      this.error = 'Please enter a search query';
      return;
    }
    if (this.isVectorSearch()) {
      const noCollection =
        (this.queryType === 'pgvector' && !this.searchTable.trim()) ||
        (this.queryType === 'weaviate' && !this.searchClassName.trim()) ||
        (this.queryType !== 'pgvector' && this.queryType !== 'weaviate' && !this.searchCollection.trim());
      if (noCollection) {
        this.error = 'Please select a collection';
        return;
      }
    }
    if (this.isObjectStore() && !this.osSelectedPipeline) {
      this.error = 'Please select a pipeline with an Object Store destination';
      return;
    }
    if (this.isWarehouse() && !this.whSelectedPipeline) {
      this.error = 'Please select a pipeline with a ' + (this.queryType === 'databricks' ? 'Databricks' : 'Snowflake') + ' destination';
      return;
    }

    this.loading = true;
    this.executed = false;
    this.results = [];
    this.columns = [];
    this.mongoFlat = false;
    this.resetReveal();

    let request;

    switch (this.queryType) {
      case 'postgres':
        request = this.searchService.queryPostgres(this.pgSql, this.pgDatabase, this.pgLimit);
        break;
      case 'mongodb':
        const filter = this.mongoFilter ? JSON.parse(this.mongoFilter) : {};
        const projection = this.mongoProjection ? JSON.parse(this.mongoProjection) : null;
        request = this.searchService.queryMongodb(this.mongoCollection, filter, projection, this.mongoLimit, this.mongoDatabase);
        break;
      case 'qdrant':
        request = this.searchService.searchQdrant(this.searchQuery, this.searchCollection, this.embeddingSecretName, this.vectorSecretName, this.topK);
        break;
      case 'weaviate':
        request = this.searchService.searchWeaviate(this.searchQuery, this.searchClassName, this.embeddingSecretName, this.vectorSecretName, this.topK);
        break;
      case 'milvus':
        request = this.searchService.searchMilvus(this.searchQuery, this.searchCollection, this.embeddingSecretName, this.vectorSecretName, this.topK);
        break;
      case 'chroma':
        request = this.searchService.searchChroma(this.searchQuery, this.searchCollection, this.embeddingSecretName, this.vectorSecretName, this.topK);
        break;
      case 'pgvector':
        request = this.searchService.searchPgvector(this.searchQuery, this.searchTable, this.searchSchema, this.embeddingSecretName, this.vectorSecretName, this.topK);
        break;
      case 'objectstore':
        request = this.searchService.queryObjectstore(this.osSelectedPipeline, this.osLimit);
        break;
      case 'databricks':
        request = this.searchService.queryDatabricks(this.whSelectedPipeline, this.whSql.trim(), this.whLimit);
        break;
      case 'snowflake':
        request = this.searchService.querySnowflake(this.whSelectedPipeline, this.whSql.trim(), this.whLimit);
        break;
      default:
        this.loading = false;
        return;
    }

    request.subscribe({
      next: (response: QueryResponse) => {
        this.results = response.results || [];
        this.resultCount = response.count || 0;
        this.executed = true;
        this.snapshotId = response.snapshotId ? String(response.snapshotId) : null;
        this.snapshotTimestamp = response.snapshotTimestamp || null;
        if (this.queryType === 'mongodb') {
          this.prepareMongoResults();
        } else if (this.results.length > 0) {
          this.columns = Object.keys(this.results[0]);
        }
        this.loading = false;
        this.bindRevealPipeline();

        // Auto-submit to LLM for vector searches
        if (this.isVectorSearch() && this.results.length > 0) {
          this.askAI();
        }
      },
      error: (err: any) => {
        this.error = this.errorMessage(err);
        this.loading = false;
      }
    });
  }

  /** Message for the error box. Query endpoints answer {"error": "..."};
   *  some answer {"message": "..."}; a network failure (status 0) carries a
   *  ProgressEvent body, which is never rendered. */
  private errorMessage(err: any): string {
    const body = err ? err.error : null;
    if (body && typeof body === 'object') {
      if (typeof body.error === 'string' && body.error) return body.error;
      if (typeof body.message === 'string' && body.message) return body.message;
    }
    if (typeof body === 'string' && body) return body;
    if (err && typeof err.message === 'string' && err.message) return err.message;
    return 'An error occurred';
  }

  /** Objects and arrays render through the json pipe, not "[object Object]". */
  isStructured(value: any): boolean {
    return value !== null && typeof value === 'object';
  }

  // ---------------------------------------------------------------------------
  // Field protection: ciphertext badges and Reveal
  // ---------------------------------------------------------------------------

  private static readonly CIPHERTEXT = /^enc:v(\d+):/;
  /** The reveal endpoint accepts at most this many values per call. */
  private static readonly REVEAL_CHUNK = 1000;

  /** A Datris field-protection ciphertext (`enc:v<n>:...`). */
  isCiphertext(v: any): boolean {
    return typeof v === 'string' && SearchComponent.CIPHERTEXT.test(v);
  }

  /** Key version of a ciphertext, e.g. "2" for `enc:v2:...`. */
  ciphertextVersion(v: string): string {
    const m = SearchComponent.CIPHERTEXT.exec(v || '');
    return m ? m[1] : '';
  }

  /** Reveal is offered to admin sessions, or to any caller when the install runs
   *  on API keys alone (the server's 403 is the real gate). */
  get canReveal(): boolean {
    return !this.auth.userAuthEnabled || this.auth.isAdmin();
  }

  /** Whether a ciphertext cell in `col` renders as a badge: by shape alone until a
   *  pipeline is bound, then only for the bound pipeline's `encrypt` fields. */
  isProtectedCell(col: string, v: any): boolean {
    if (!this.isCiphertext(v)) return false;
    return this.encryptFields === null || this.encryptFields.has(String(col).toLowerCase());
  }

  /** Reveal button: admin/key mode, nothing revealed yet, and either the bound
   *  pipeline has encrypted columns here or a pick is still needed. */
  showRevealButton(): boolean {
    if (!this.canReveal || this.revealed.size > 0) return false;
    if (this.revealPipeline) return this.encryptedColumns.length > 0;
    return this.hasCiphertext && this.revealNeedsPick;
  }

  showRevealPicker(): boolean {
    return this.canReveal && this.hasCiphertext && this.revealNeedsPick;
  }

  private resetReveal(): void {
    this.revealed = new Map<string, string>();
    this.revealErrors = new Map<string, string>();
    this.revealError = '';
    this.revealing = false;
    this.revealPipeline = '';
    this.revealCandidates = [];
    this.revealNeedsPick = false;
    this.encryptFields = null;
    this.encryptedColumns = [];
    this.hasCiphertext = false;
  }

  private static hasEncryptField(config: any): boolean {
    return SearchComponent.encryptFieldNames(config).length > 0;
  }

  private static encryptFieldNames(config: any): string[] {
    const fields = config && config.source && config.source.schemaProperties && config.source.schemaProperties.fields;
    if (!Array.isArray(fields)) return [];
    return fields
      .filter((f: any) => f && f.name && f.protect && String(f.protect.method || '').toLowerCase() === 'encrypt')
      .map((f: any) => String(f.name));
  }

  /** Index the pipelines list by Postgres table and MongoDB collection. */
  private setDestIndex(configs: any[]): void {
    this.allPipelines = (configs || []).filter(c => c && c.name);
    this.pipelineCache.clear();
    this.destIndex = new Map<string, string[]>();
    const add = (key: string, name: string) => {
      const k = key.toLowerCase();
      const list = this.destIndex.get(k) || [];
      if (!list.includes(name)) list.push(name);
      this.destIndex.set(k, list);
    };
    for (const c of this.allPipelines) {
      const db = c.destination && c.destination.database;
      if (!db || !db.table) continue;
      if (db.usePostgres) {
        add('pg:' + (db.dbName || this.pgDatabase) + '.' + (db.schema || 'public') + '.' + db.table, c.name);
      }
      if (db.useMongoDB) {
        add('mongo:' + (db.dbName || this.mongoDatabase) + '.' + db.table, c.name);
      }
    }
  }

  /** Postgres/MongoDB: pipelines writing what was queried. */
  private inferPipeline(): string[] {
    if (this.queryType === 'mongodb') {
      return this.destIndex.get(('mongo:' + this.mongoDatabase + '.' + this.mongoCollection).toLowerCase()) || [];
    }
    if (this.queryType === 'postgres') {
      const m = /\bfrom\s+("?[\w]+"?\.)?"?([\w]+)"?/i.exec(this.pgSql || '');
      if (!m) return [];
      const schema = m[1] ? m[1].replace(/"/g, '').replace(/\.$/, '') : 'public';
      return this.destIndex.get(('pg:' + this.pgDatabase + '.' + schema + '.' + m[2]).toLowerCase()) || [];
    }
    return [];
  }

  /** Decide which pipeline the result set binds to, then load its encrypt fields. */
  private bindRevealPipeline(): void {
    this.hasCiphertext = this.results.some(r => r && typeof r === 'object' &&
      Object.keys(r).some(k => this.isCiphertext(r[k])));
    this.revealPipeline = '';
    this.revealCandidates = [];
    this.revealNeedsPick = false;
    this.encryptFields = null;
    this.encryptedColumns = [];
    if (!this.hasCiphertext) return;

    if (this.isWarehouse()) {
      this.revealPipeline = this.whSelectedPipeline;
    } else if (this.isObjectStore()) {
      this.revealPipeline = this.osSelectedPipeline;
    } else if (this.queryType === 'postgres' || this.queryType === 'mongodb') {
      const matches = this.inferPipeline();
      if (matches.length === 1) {
        this.revealPipeline = matches[0];
      } else {
        this.revealNeedsPick = true;
        this.revealCandidates = matches.length > 1
          ? matches
          : this.allPipelines.filter(c => SearchComponent.hasEncryptField(c)).map(c => c.name);
        if (this.revealPick && this.revealCandidates.includes(this.revealPick)) {
          this.revealPipeline = this.revealPick;
        }
      }
    }
    if (this.revealPipeline) this.loadEncryptFields(this.revealPipeline);
  }

  /** Picker change (postgres/mongodb). */
  onRevealPick(name: string): void {
    this.revealPick = name || '';
    this.revealErrors = new Map<string, string>();
    this.revealError = '';
    this.revealPipeline = this.revealPick;
    this.encryptFields = null;
    this.encryptedColumns = [];
    if (this.revealPipeline) this.loadEncryptFields(this.revealPipeline);
  }

  private loadEncryptFields(name: string): void {
    const apply = (config: any) => {
      if (this.revealPipeline !== name) return;
      const fields = new Map<string, string>();
      for (const f of SearchComponent.encryptFieldNames(config)) fields.set(f.toLowerCase(), f);
      this.encryptFields = fields;
      this.encryptedColumns = this.columns.filter(col =>
        fields.has(col.toLowerCase()) && this.results.some(r => r && this.isCiphertext(r[col])));
    };
    const cached = this.pipelineCache.get(name);
    if (cached) { apply(cached); return; }
    this.pipelineService.getPipeline(name).subscribe({
      next: (config) => {
        if (config) this.pipelineCache.set(name, config);
        apply(config);
      },
      error: () => { /* unbound: badges stay shape-based, no Reveal */ }
    });
  }

  /** Reveal every encrypted cell in the result set: one call per encrypted
   *  column (chunked at 1000 distinct values), sequential so a 403 stops the rest. */
  revealAll(): void {
    const pipeline = this.revealPipeline;
    if (!pipeline || !this.encryptFields || this.revealing) return;
    const fields = this.encryptFields;
    const calls: { field: string; values: string[] }[] = [];
    for (const col of this.encryptedColumns) {
      const field = fields.get(col.toLowerCase());
      if (!field) continue;
      const distinct = Array.from(new Set(this.results.map(r => r && r[col]).filter(v => this.isCiphertext(v)))) as string[];
      for (let i = 0; i < distinct.length; i += SearchComponent.REVEAL_CHUNK) {
        calls.push({ field, values: distinct.slice(i, i + SearchComponent.REVEAL_CHUNK) });
      }
    }
    if (calls.length === 0) return;

    this.revealing = true;
    this.revealError = '';
    const revealed = new Map<string, string>();
    const errors = new Map<string, string>();
    const publish = () => {
      this.revealed = new Map(revealed);
      this.revealErrors = new Map(errors);
    };
    from(calls).pipe(
      concatMap(c => this.searchService.reveal(pipeline, c.field, c.values).pipe(
        map((resp: RevealResponse) => ({ call: c, resp }))
      ))
    ).subscribe({
      next: ({ call, resp }) => {
        const out = (resp && resp.values) || [];
        call.values.forEach((v, i) => {
          const p = out[i];
          if (p !== null && p !== undefined) revealed.set(v, String(p));
        });
        for (const e of (resp && resp.errors) || []) {
          const v = call.values[e.index];
          if (v !== undefined) errors.set(v, e.message || 'Could not reveal this value');
        }
      },
      error: (err: any) => {
        this.revealError = err && err.status === 403
          ? 'You do not have the protect:reveal capability'
          : this.errorMessage(err);
        publish();
        this.revealing = false;
      },
      complete: () => {
        publish();
        this.revealing = false;
      }
    });
  }

  /** MongoDB: stringify `_id` object ids, take the union of top-level keys as
   *  columns, and decide whether every document is flat (table) or not (JSON). */
  private prepareMongoResults(): void {
    const idString = (id: any): any => {
      if (id === null || typeof id !== 'object') return id;
      if (typeof id.$oid === 'string') return id.$oid;
      return JSON.stringify(id);
    };
    const docs = this.results.map(d => (d && typeof d === 'object' && '_id' in d) ? { ...d, _id: idString(d._id) } : d);
    const flat = docs.length > 0 && docs.every(d => d && typeof d === 'object' && !Array.isArray(d) &&
      Object.keys(d).every(k => d[k] === null || typeof d[k] !== 'object'));
    const cols: string[] = [];
    for (const d of docs) {
      if (!d || typeof d !== 'object') continue;
      for (const k of Object.keys(d)) if (!cols.includes(k)) cols.push(k);
    }
    this.columns = cols;
    this.mongoFlat = flat;
    if (flat) this.results = docs;
  }

  /** MongoDB JSON view (nested documents): top-level ciphertext shown as a
   *  `🔒 enc:v<n>` marker, or the plaintext once revealed; nested values untouched. */
  revealedJson(): string {
    const view = this.results.map(d => {
      if (!d || typeof d !== 'object' || Array.isArray(d)) return d;
      const out: any = {};
      for (const k of Object.keys(d)) {
        const v = d[k];
        if (this.isProtectedCell(k, v)) {
          out[k] = this.revealed.has(v) ? this.revealed.get(v) : '🔒 enc:v' + this.ciphertextVersion(v);
        } else {
          out[k] = v;
        }
      }
      return out;
    });
    return JSON.stringify(view, null, 2);
  }

  askAI(): void {
    this.aiAnswer = '';
    this.aiError = '';
    this.aiLoading = true;

    // Build context from retrieved chunks
    const context = this.results.map((r: any, i: number) => {
      const text = r.text || JSON.stringify(r);
      const score = r._score ? ' (score: ' + r._score.toFixed(3) + ')' : '';
      return '[' + (i + 1) + ']' + score + ' ' + text;
    }).join('\n\n');

    this.searchService.aiAnswer(this.searchQuery, context).subscribe({
      next: (response: any) => {
        this.aiAnswer = response.answer || '';
        this.aiLoading = false;
      },
      error: (err: any) => {
        this.aiError = httpErrorText(err, 'AI answer failed');
        this.aiLoading = false;
      }
    });
  }
}
