import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { PipelineService } from '../pipeline.service';
import { SearchService } from '../search.service';
import { HealthService } from '../health.service';
import { TapService } from '../tap.service';
import { sanitizeLabel, sanitizeIdentifier } from '../shared/sanitize';

interface SchemaField {
  name: string;
  type: string;
}

@Component({
  selector: 'app-pipeline-create',
  templateUrl: './pipeline-create.component.html',
  styleUrls: ['./pipeline-create.component.css']
})
export class PipelineCreateComponent implements OnInit {
  isEditMode = false;
  step = 1;
  error = '';
  creating = false;
  generatingSchema = false;
  sampleFileDetected = false;

  // Step 1 — Basics + Source
  pipelineName = '';
  catalog = '';
  availableCatalogs: string[] = [];
  showNewCatalog = false;
  newCatalogName = '';
  pipelineSource = 'file';  // 'file' | 'tap' | 'manual'
  sampleFile: File | null = null;
  sourceType = 'csv';
  taps: any[] = [];
  selectedTapName = '';

  // Step 2 — Source config
  csvDelimiter = ',';
  csvEncoding = 'UTF-8';
  csvHeader = true;
  jsonEveryRow = false;
  showJsonInfo = false;
  showXmlInfo = false;
  xmlEveryRow = false;
  unstructuredExtension = 'pdf';

  // Preprocessor (optional, shown in Step 2)
  showPreprocessorInfo = false;
  usePreprocessor = false;
  ppEndpoint = '';
  ppAsync = false;
  ppBearerToken = '';
  ppApiKey = '';
  ppTimeout = 300000;

  // Step 3 — Schema
  schemaDbName = 'datris';
  schemaFields: SchemaField[] = [{ name: '', type: 'string' }];
  schemaFile: File | null = null;

  // Step 7 — Destination Schema (CSV only)
  destSchemaFields: SchemaField[] = [];

  // Step 5 — Data Quality
  dqValidateHeader = false;
  dqValidationSchema = '';
  dqSchemaMode = 'upload';
  dqSchemaName = '';
  dqSampleData = '';
  dqGenerating = false;
  dqGenerateError = '';
  dqUseAiRule = false;
  dqAiInstruction = '';
  dqAiOnFailureIsError = false;
  dqProfileSummary = '';

  // Step 6 — Transformation
  txTrimWhitespace = false;
  txDeduplicate = false;
  txAiInstruction = '';

  // Step 7 — Destination
  destType = 'postgres';
  // Structured destinations installed on this instance, from
  // /api/v1/destinations/available. null = unknown (not loaded yet, or the
  // call failed). FAIL-OPEN: when null, every destination renders enabled —
  // a transient availability blip must never hide options from the user.
  availableDestinations: string[] | null = null;
  pgDbName = 'datris';
  dbTruncateBeforeWrite = false;
  pgSchema = 'public';
  pgTable = '';
  mongoDbName = 'datris';
  mongoTable = '';
  osPrefix = '';
  osFormat = 'parquet';
  osBucket = '';
  osDeleteBeforeWrite = false;
  osPartitionBy: string[] = [];
  osProvider: 'minio' | 's3' = 'minio';
  osEndpoint = '';
  osCredentialsSecret = '';
  pgKeyFields: string[] = [];
  sfCredentialsSecret = '';
  sfWarehouse = '';
  sfRole = '';
  sfDbName = '';
  sfSchema = 'PUBLIC';
  sfTable = '';
  sfKeyFields: string[] = [];
  dbxCredentialsSecret = '';
  dbxWarehouse = '';
  dbxCatalog = '';
  dbxSchema = 'default';
  dbxTable = '';
  dbxKeyFields: string[] = [];
  mongoKeyFields: string[] = [];
  kafkaTopic = '';
  amqQueue = '';
  restEndpointUrl = '';
  restEndpointBearerToken = '';
  restEndpointTimeout = 300000;
  restEndpointApiKey = '';
  restEndpointAsync = false;
  vectorCollection = '';
  vectorTable = '';
  vectorSchema = 'public';
  vectorClassName = '';
  chunkStrategy = 'recursive';
  chunkSize = 500;
  chunkOverlap = 50;
  embeddingSecret = 'oss/embedding';
  vectorSecret = '';

  // Step 5 — Review
  configJson = '';

  // Metadata for dropdowns
  pgSchemas: string[] = [];
  pgTables: string[] = [];
  mongoCollections: string[] = [];

  fieldTypes = ['string', 'int', 'bigint', 'float', 'double', 'boolean', 'date', 'timestamp'];

  isTrial = false;

  constructor(private pipelineService: PipelineService, private searchService: SearchService, public healthService: HealthService, private tapService: TapService, private route: ActivatedRoute, private router: Router, private http: HttpClient) { }

  ngOnInit(): void {
    this.http.get<any>('/api/v1/version').subscribe({
      next: (data) => {
        this.isTrial = data.multiTenant === 'true';
        // Canonical db names are server-chosen; UI displays, never edits.
        this.pgDbName = data.postgresDatabase || 'datris';
        this.mongoDbName = data.mongodbDatabase || 'datris';
        if (this.isTrial) {
          this.schemaDbName = data.environment || 'datris';
        }
      }
    });

    // Which structured destinations are installed (mirrors the document-tap
    // wizard's vector-store availability check on modal open).
    this.pipelineService.getAvailableDestinations().subscribe({
      next: (dests) => this.availableDestinations = dests || null,
      // Fail-open: on error/timeout keep null so all destinations stay
      // enabled — an availability blip must never hide options.
      error: () => this.availableDestinations = null
    });

    // Load taps for "Create from Tap" option + extract catalogs. Catalogs
    // are collected from BOTH taps and pipelines (and from __catalog__
    // placeholder taps, which represent empty catalogs created via the
    // Catalog page). Pulling from taps alone misses catalogs that contain
    // only pipelines — when the wizard opens for such a pipeline, the
    // dropdown lacks a matching option, the displayed value diverges from
    // this.catalog, and a subsequent save can write null to the server.
    const collectedCatalogs = new Set<string>();
    const finalizeCatalogs = () => {
      this.availableCatalogs = Array.from(collectedCatalogs).sort();
    };
    this.tapService.getTaps().subscribe({
      next: (data) => {
        const allTaps = data || [];
        this.taps = allTaps.filter((t: any) => t.lastTestRunDataType || t.lastRunDataType)
                        .sort((a: any, b: any) => (a.name || '').localeCompare(b.name || ''));
        allTaps.forEach((t: any) => {
          // Real tap with a catalog assignment, OR a placeholder for an empty catalog.
          if (t.catalog) collectedCatalogs.add(t.catalog);
        });
        finalizeCatalogs();
      },
      error: () => {}
    });
    this.pipelineService.getPipelines().subscribe({
      next: (pipelines: any[]) => {
        (pipelines || []).forEach((p: any) => { if (p.catalog) collectedCatalogs.add(p.catalog); });
        finalizeCatalogs();
      },
      error: () => {}
    });

    // Pre-fill the catalog field when the user entered Create from a specific
    // catalog (e.g. clicked Create Pipeline inside a catalog card). Uncataloged
    // means "no catalog assignment" — leave the field empty.
    const catalogParam = this.route.snapshot.queryParamMap.get('catalog');
    if (catalogParam && catalogParam !== 'Uncataloged') {
      this.catalog = catalogParam;
    }

    const editName = this.route.snapshot.paramMap.get('name');
    if (editName) {
      this.isEditMode = true;
      this.pipelineService.getPipeline(editName).subscribe({
        next: (config) => this.loadFromConfig(config),
        error: (err) => this.error = 'Failed to load dataset: ' + (err.error || err.message)
      });
    }
  }

  loadFromConfig(config: any): void {
    this.pipelineName = config.name || '';
    this.catalog = config.catalog || '';
    this.sampleFileDetected = true; // skip sample file prompt

    // Detect source type
    const fa = config.source?.fileAttributes;
    if (fa?.csvAttributes) {
      this.sourceType = 'csv';
      this.csvDelimiter = fa.csvAttributes.delimiter || ',';
      this.csvEncoding = fa.csvAttributes.encoding || 'UTF-8';
      this.csvHeader = fa.csvAttributes.header !== false;
    } else if (fa?.jsonAttributes) {
      this.sourceType = 'json';
      this.jsonEveryRow = fa.jsonAttributes.everyRowContainsObject || false;
    } else if (fa?.xmlAttributes) {
      this.sourceType = 'xml';
      this.xmlEveryRow = fa.xmlAttributes.everyRowContainsObject || false;
    } else if (fa?.unstructuredAttributes) {
      this.sourceType = 'unstructured';
      this.unstructuredExtension = fa.unstructuredAttributes.fileExtension || 'pdf';
    }

    // Schema
    if (config.source?.schemaProperties) {
      this.schemaDbName = config.source.schemaProperties.dbName || 'datris';
      if (config.source.schemaProperties.fields) {
        this.schemaFields = config.source.schemaProperties.fields.map((f: any) => ({
          name: f.name, type: f.type
        }));
      }
    }

    // Data Quality
    if (config.dataQuality) {
      const dq = config.dataQuality;
      this.dqValidateHeader = dq.validateFileHeader || (dq.validationSchema ? true : false);
      this.dqValidationSchema = dq.validationSchema || '';
      if (dq.aiRule) {
        this.dqUseAiRule = true;
        this.dqAiInstruction = dq.aiRule.instruction || '';
        this.dqAiOnFailureIsError = dq.aiRule.onFailureIsError || false;
      }
    }

    // Transformation
    if (config.transformation?.aiTransformation) {
      this.txAiInstruction = config.transformation.aiTransformation.instruction || '';
      // Set checkboxes based on instruction text
      this.txTrimWhitespace = this.txAiInstruction.includes('Trim leading/trailing whitespace');
      this.txDeduplicate = this.txAiInstruction.includes('Remove duplicate rows');
    }

    // Preprocessor
    if (config.preprocessor) {
      this.usePreprocessor = true;
      this.ppEndpoint = config.preprocessor.endpoint || '';
      this.ppAsync = config.preprocessor.async || false;
      this.ppBearerToken = config.preprocessor.bearerToken || '';
      this.ppApiKey = config.preprocessor.apiKey || '';
      this.ppTimeout = config.preprocessor.timeoutMs || (config.preprocessor.timeoutSeconds ? config.preprocessor.timeoutSeconds * 1000 : 300000);
    }

    // Destination schema
    if (config.destination?.schemaProperties?.fields) {
      this.destSchemaFields = config.destination.schemaProperties.fields.map((f: any) => ({
        name: f.name, type: f.type
      }));
    }

    // Destination
    const dest = config.destination;
    if (dest?.database?.usePostgres) {
      this.destType = 'postgres';
      this.pgDbName = dest.database.dbName || 'datris';
      this.pgSchema = dest.database.schema || 'public';
      this.pgTable = dest.database.table || '';
      this.dbTruncateBeforeWrite = !!dest.database.truncateBeforeWrite;
      this.pgKeyFields = Array.isArray(dest.database.keyFields) ? [...dest.database.keyFields] : [];
    } else if (dest?.database?.useMongoDB) {
      this.destType = 'mongodb';
      this.mongoDbName = dest.database.dbName || 'datris';
      this.mongoTable = dest.database.table || '';
      this.dbTruncateBeforeWrite = !!dest.database.truncateBeforeWrite;
      this.mongoKeyFields = Array.isArray(dest.database.keyFields) ? [...dest.database.keyFields] : [];
    } else if (dest?.database?.useSnowflake) {
      this.destType = 'snowflake';
      this.sfDbName = dest.database.dbName || '';
      this.sfSchema = dest.database.schema || 'PUBLIC';
      this.sfTable = dest.database.table || '';
      this.sfWarehouse = dest.database.warehouse || '';
      this.sfRole = dest.database.role || '';
      this.sfCredentialsSecret = dest.database.credentialsSecret || '';
      this.dbTruncateBeforeWrite = !!dest.database.truncateBeforeWrite;
      this.sfKeyFields = Array.isArray(dest.database.keyFields) ? [...dest.database.keyFields] : [];
    } else if (dest?.database?.useDatabricks) {
      this.destType = 'databricks';
      this.dbxCatalog = dest.database.dbName || '';
      this.dbxSchema = dest.database.schema || 'default';
      this.dbxTable = dest.database.table || '';
      this.dbxWarehouse = dest.database.warehouse || '';
      this.dbxCredentialsSecret = dest.database.credentialsSecret || '';
      this.dbTruncateBeforeWrite = !!dest.database.truncateBeforeWrite;
      this.dbxKeyFields = Array.isArray(dest.database.keyFields) ? [...dest.database.keyFields] : [];
    } else if (dest?.objectStore) {
      this.destType = 'objectstore';
      this.osPrefix = dest.objectStore.prefixKey || '';
      this.osFormat = dest.objectStore.fileFormat || 'parquet';
      this.osBucket = dest.objectStore.destinationBucketOverride || '';
      this.osDeleteBeforeWrite = !!dest.objectStore.deleteBeforeWrite;
      this.osPartitionBy = Array.isArray(dest.objectStore.partitionBy) ? [...dest.objectStore.partitionBy] : [];
      this.osProvider = (dest.objectStore.provider === 's3') ? 's3' : 'minio';
      this.osEndpoint = dest.objectStore.endpoint || '';
      this.osCredentialsSecret = dest.objectStore.credentialsSecret || '';
    } else if (dest?.kafka) {
      this.destType = 'kafka';
      this.kafkaTopic = dest.kafka.topic || '';
    } else if (dest?.activeMQ) {
      this.destType = 'activemq';
      this.amqQueue = dest.activeMQ.queueName || '';
    } else if (dest?.restEndpoint) {
      this.destType = 'restendpoint';
      this.restEndpointUrl = dest.restEndpoint.endpoint || '';
      this.restEndpointAsync = dest.restEndpoint.async || false;
      this.restEndpointBearerToken = dest.restEndpoint.bearerToken || '';
      this.restEndpointTimeout = dest.restEndpoint.timeoutMs || (dest.restEndpoint.timeoutSeconds ? dest.restEndpoint.timeoutSeconds * 1000 : 300000);
      this.restEndpointApiKey = dest.restEndpoint.apiKey || '';
    } else if (dest?.qdrant) {
      this.destType = 'qdrant';
      this.vectorCollection = dest.qdrant.collectionName || '';
      this.embeddingSecret = dest.qdrant.embeddingSecretName || 'oss/embedding';
      this.vectorSecret = dest.qdrant.qdrantSecretName || 'oss/qdrant';
      this.loadChunking(dest.qdrant.chunking);
    } else if (dest?.weaviate) {
      this.destType = 'weaviate';
      this.vectorClassName = dest.weaviate.className || '';
      this.embeddingSecret = dest.weaviate.embeddingSecretName || 'oss/embedding';
      this.vectorSecret = dest.weaviate.weaviateSecretName || 'oss/weaviate';
      this.loadChunking(dest.weaviate.chunking);
    } else if (dest?.milvus) {
      this.destType = 'milvus';
      this.vectorCollection = dest.milvus.collectionName || '';
      this.embeddingSecret = dest.milvus.embeddingSecretName || 'oss/embedding';
      this.vectorSecret = dest.milvus.milvusSecretName || 'oss/milvus';
      this.loadChunking(dest.milvus.chunking);
    } else if (dest?.chroma) {
      this.destType = 'chroma';
      this.vectorCollection = dest.chroma.collectionName || '';
      this.embeddingSecret = dest.chroma.embeddingSecretName || 'oss/embedding';
      this.vectorSecret = dest.chroma.chromaSecretName || 'oss/chroma';
      this.loadChunking(dest.chroma.chunking);
    } else if (dest?.pgvector) {
      this.destType = 'pgvector';
      this.vectorTable = dest.pgvector.tableName || '';
      this.vectorSchema = dest.pgvector.schemaName || 'public';
      this.embeddingSecret = dest.pgvector.embeddingSecretName || 'oss/embedding';
      this.vectorSecret = dest.pgvector.postgresSecretName || 'oss/pgvector';
      this.loadChunking(dest.pgvector.chunking);
    }
  }

  private loadChunking(chunking: any): void {
    if (!chunking) return;
    this.chunkStrategy = chunking.strategy || 'recursive';
    this.chunkSize = chunking.chunkSize || 500;
    this.chunkOverlap = chunking.chunkOverlap || 50;
  }

  loadPgSchemas(): void {
    this.searchService.getPostgresSchemas(this.pgDbName).subscribe({
      next: (schemas) => {
        this.pgSchemas = schemas;
        if (schemas.length > 0 && !this.pgSchema) {
          this.pgSchema = schemas.includes('public') ? 'public' : schemas[0];
          this.loadPgTables();
        }
      },
      error: () => { this.pgSchemas = []; }
    });
  }

  loadPgTables(): void {
    if (!this.pgSchema) return;
    this.searchService.getPostgresTables(this.pgDbName, this.pgSchema).subscribe({
      next: (tables) => { this.pgTables = tables; },
      error: () => { this.pgTables = []; }
    });
  }

  onPgSchemaChange(): void {
    this.pgTable = '';
    this.loadPgTables();
  }

  onVectorSchemaChange(): void {
    this.vectorTable = '';
    this.pgSchema = this.vectorSchema;
    this.loadVectorTables();
  }

  loadVectorTables(): void {
    if (!this.vectorSchema) return;
    this.searchService.getPostgresTables(this.pgDbName || 'datris', this.vectorSchema, true).subscribe({
      next: (tables) => { this.pgTables = tables; },
      error: () => { this.pgTables = []; }
    });
  }

  loadMongoCollections(): void {
    this.searchService.getMongoCollections(this.mongoDbName).subscribe({
      next: (collections) => { this.mongoCollections = collections; },
      error: () => { this.mongoCollections = []; }
    });
  }

  isUnstructured(): boolean {
    return this.sourceType === 'unstructured';
  }

  isVectorDest(): boolean {
    return ['qdrant', 'weaviate', 'milvus', 'chroma', 'pgvector'].includes(this.destType);
  }

  getDefaultVectorSecret(): string {
    const defaults: Record<string, string> = {
      qdrant: 'oss/qdrant', weaviate: 'oss/weaviate', milvus: 'oss/milvus',
      chroma: 'oss/chroma', pgvector: 'oss/pgvector'
    };
    return defaults[this.destType] || '';
  }

  /** True when the structured destination is installed on this instance.
   *  FAIL-OPEN: an unknown availability list (call pending or failed) enables
   *  everything — a blip must never hide options. Only the five structured
   *  destinations are gated; other types always return true. */
  isDestAvailable(name: string): boolean {
    if (this.availableDestinations === null) return true;
    const structured = ['mongodb', 'postgres', 'objectstore', 'snowflake', 'databricks'];
    if (!structured.includes(name)) return true;
    return this.availableDestinations.includes(name);
  }

  /** The currently selected destination is structured but not installed.
   *  We deliberately do NOT auto-switch the selection (e.g. when editing a
   *  pipeline whose destination was uninstalled) — keep it selected and let
   *  the user decide. */
  selectedDestUnavailable(): boolean {
    return !this.isDestAvailable(this.destType);
  }

  onDestTypeChange(): void {
    this.vectorSecret = this.getDefaultVectorSecret();
    if (this.destType === 'pgvector') {
      this.loadPgSchemas();
      if (this.vectorSchema) {
        this.pgSchema = this.vectorSchema;
        this.loadVectorTables();
      }
    }
  }

  onSourceTypeChange(): void {
    if (this.sourceType === 'json') {
      this.schemaFields = [{ name: '_json', type: 'string' }];
      // JSON can't go to PostgreSQL, Snowflake, Databricks, or Object Store
      if (this.destType === 'postgres' || this.destType === 'snowflake' || this.destType === 'databricks' || this.destType === 'objectstore') {
        this.destType = 'mongodb';
      }
    } else if (this.sourceType === 'xml') {
      this.schemaFields = [{ name: '_xml', type: 'string' }];
      // XML can go to PostgreSQL or vector stores
      if (this.destType === 'objectstore' || this.destType === 'kafka' || this.destType === 'activemq' || this.destType === 'restendpoint' || this.destType === 'mongodb') {
        this.destType = 'postgres';
      }
    } else if (this.sourceType === 'csv') {
      this.schemaFields = [{ name: '', type: 'string' }];
    }
    // For unstructured, destination must be a vector DB
    if (this.sourceType === 'unstructured') {
      if (!this.isVectorDest()) {
        this.destType = 'pgvector';
      }
      this.onDestTypeChange();
    }
  }

  onTapSelected(tapName: string): void {
    const tap = this.taps.find(t => t.name === tapName);
    if (!tap) return;

    // Derive pipeline name from tap name if not set
    if (!this.pipelineName.trim()) {
      this.pipelineName = tapName.replace(/-tap$/, '') + '-pipeline';
    }

    const dataType = tap.lastTestRunDataType || tap.lastRunDataType || '';
    const columns = tap.lastTestRunColumns || tap.lastRunColumns || [];

    // Map tap data type to pipeline source type
    if (dataType === 'csv') {
      this.sourceType = 'csv';
      if (columns.length > 0) {
        this.schemaFields = columns.map((name: string) => ({ name, type: 'string' }));
      }
    } else if (dataType === 'json') {
      this.sourceType = 'json';
      this.schemaFields = [{ name: '_json', type: 'string' }];
    } else if (dataType === 'xml') {
      this.sourceType = 'xml';
      this.schemaFields = [{ name: '_xml', type: 'string' }];
    }

    this.sampleFileDetected = true;
    this.onSourceTypeChange();
    // Restore columns after onSourceTypeChange resets them for csv
    if (dataType === 'csv' && columns.length > 0) {
      this.schemaFields = columns.map((name: string) => ({ name, type: 'string' }));
    }
  }

  getColumnPreview(): string {
    const names = this.schemaFields.filter(f => f.name).map(f => f.name);
    if (names.length <= 5) return names.join(', ');
    return names.slice(0, 5).join(', ') + ', ...';
  }

  onCatalogChange(value: string): void {
    if (value === '__new__') {
      this.showNewCatalog = true;
      this.newCatalogName = '';
      this.catalog = '';
    } else {
      this.showNewCatalog = false;
    }
  }

  confirmNewCatalog(): void {
    const name = sanitizeLabel(this.newCatalogName);
    if (!name) return;
    this.catalog = name;
    if (!this.availableCatalogs.includes(name)) {
      this.availableCatalogs.push(name);
      this.availableCatalogs.sort();
    }
    this.showNewCatalog = false;
    this.newCatalogName = '';
  }

  onPipelineSourceChange(): void {
    if (this.pipelineSource === 'file') {
      this.selectedTapName = '';
      this.sampleFileDetected = false;
      this.sourceType = 'csv';
      this.schemaFields = [{ name: '', type: 'string' }];
    } else if (this.pipelineSource === 'manual') {
      this.selectedTapName = '';
      this.sampleFileDetected = true; // skip file upload requirement, user defines everything by hand
      this.sourceType = 'csv';
      this.schemaFields = [{ name: '', type: 'string' }];
    }
  }

  addField(): void {
    this.schemaFields.push({ name: '', type: 'string' });
  }

  removeField(index: number): void {
    this.schemaFields.splice(index, 1);
  }

  removeDestField(index: number): void {
    this.destSchemaFields.splice(index, 1);
  }

  onSampleFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.sampleFile = input.files[0];
      this.analyzeSampleFile();
    }
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.schemaFile = input.files[0];
    }
  }

  onSchemaFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      const file = input.files[0];
      this.dqValidationSchema = file.name;
      // Upload to MinIO
      this.pipelineService.uploadConfigFile(file, 'validation-schema').subscribe({
        next: (resp) => {
          this.dqValidationSchema = resp.filename;
        },
        error: (err) => {
          this.error = 'Failed to upload validation schema: ' + (err.error || err.message);
        }
      });
    }
  }

  loadSampleFileForSchema(): void {
    if (!this.sampleFile) return;
    const reader = new FileReader();
    reader.onload = () => {
      this.dqSampleData = reader.result as string;
    };
    reader.readAsText(this.sampleFile);
  }

  generateValidationSchema(): void {
    if (!this.dqSchemaName.trim()) {
      this.dqGenerateError = 'Schema name is required';
      return;
    }
    if (!this.dqSampleData.trim()) {
      this.dqGenerateError = 'Sample data is required';
      return;
    }
    const schemaType = this.sourceType === 'xml' ? 'xsd' : 'json-schema';
    this.dqGenerating = true;
    this.dqGenerateError = '';
    this.pipelineService.generateValidationSchema(schemaType, this.dqSchemaName.trim(), this.dqSampleData).subscribe({
      next: (resp) => {
        this.dqValidationSchema = resp.filename;
        this.dqGenerating = false;
      },
      error: (err) => {
        this.dqGenerateError = 'Failed to generate schema: ' + (err.error || err.message);
        this.dqGenerating = false;
      }
    });
  }

  detectSourceType(filename: string): string {
    const ext = filename.split('.').pop()?.toLowerCase() || '';
    const unstructuredExts = ['pdf', 'doc', 'docx', 'pptx', 'ppt', 'xlsx', 'html', 'htm', 'txt', 'md', 'epub', 'eml', 'msg', 'rtf'];
    if (ext === 'csv' || ext === 'tsv') return 'csv';
    if (ext === 'json' || ext === 'ndjson') return 'json';
    if (ext === 'xml') return 'xml';
    if (unstructuredExts.includes(ext)) return 'unstructured';
    return 'csv';
  }

  getFileExtension(filename: string): string {
    return filename.split('.').pop()?.toLowerCase() || '';
  }

  analyzeSampleFile(): void {
    if (!this.sampleFile) return;

    const filename = this.sampleFile.name;
    const detectedType = this.detectSourceType(filename);
    this.sourceType = detectedType;
    this.sampleFileDetected = true;

    // Set source-specific defaults from file
    if (detectedType === 'csv') {
      const ext = this.getFileExtension(filename);
      if (ext === 'tsv') this.csvDelimiter = '\t';
    } else if (detectedType === 'unstructured') {
      this.unstructuredExtension = this.getFileExtension(filename);
      this.destType = 'pgvector';
      this.onDestTypeChange();
      this.onSourceTypeChange();
      return; // No schema generation for unstructured
    }

    this.onSourceTypeChange();

    // For structured files, call schema generation
    if (detectedType === 'csv' || detectedType === 'json' || detectedType === 'xml') {
      this.generatingSchema = true;
      this.error = '';

      this.pipelineService.generateSchema(
        this.sampleFile, this.pipelineName.trim() || 'temp_schema_detect',
        detectedType === 'csv' ? this.csvDelimiter : undefined,
        detectedType === 'csv' ? this.csvHeader : undefined
      ).subscribe({
        next: (response: any) => {
          if (response.source?.schemaProperties?.fields) {
            this.schemaFields = response.source.schemaProperties.fields.map((f: any) => ({
              name: f.name, type: f.type
            }));
          }
          this.generatingSchema = false;
        },
        error: (err: any) => {
          this.error = 'Schema generation failed: ' + (err.error || err.message);
          this.generatingSchema = false;
        }
      });
    }
  }

  generateSchema(): void {
    if (!this.schemaFile || !this.pipelineName) return;
    this.generatingSchema = true;
    this.error = '';

    this.pipelineService.generateSchema(
      this.schemaFile, this.pipelineName,
      this.sourceType === 'csv' ? this.csvDelimiter : undefined,
      this.sourceType === 'csv' ? this.csvHeader : undefined
    ).subscribe({
      next: (response: any) => {
        if (response.source?.schemaProperties?.fields) {
          this.schemaFields = response.source.schemaProperties.fields.map((f: any) => ({
            name: f.name, type: f.type
          }));
        }
        this.generatingSchema = false;
      },
      error: (err: any) => {
        this.error = 'Schema generation failed: ' + (err.error || err.message);
        this.generatingSchema = false;
      }
    });
  }

  nextStep(): void {
    this.error = '';
    if (this.step === 1 && !this.pipelineName.trim()) {
      this.error = 'Pipeline name is required';
      return;
    }
    if (this.step === 1 && this.generatingSchema) {
      this.error = 'Please wait for the file analysis to complete';
      return;
    }
    if (this.step === 1 && !this.isEditMode && this.pipelineSource === 'file' && !this.sampleFileDetected) {
      this.error = 'Please upload a sample file to continue';
      return;
    }
    if (this.step === 1 && !this.isEditMode && this.pipelineSource === 'tap' && !this.selectedTapName) {
      this.error = 'Please select a tap to continue';
      return;
    }

    // Validate step 2 — source configuration
    if (this.step === 2) {
      if (this.sourceType === 'csv' && !this.csvDelimiter.trim()) {
        this.error = 'CSV delimiter is required';
        return;
      }
    }

    // Validate step 3 — source schema
    if (this.step === 3 && this.sourceType === 'csv') {
      if (this.schemaFields.length === 0 || this.schemaFields.every(f => !f.name.trim())) {
        this.error = 'At least one field with a name is required';
        return;
      }
      const blank = this.schemaFields.find(f => !f.name.trim());
      if (blank) {
        this.error = 'All schema fields must have a name — remove empty fields or fill in their names';
        return;
      }
    }

    // Validate step 4 — preprocessor
    if (this.step === 4 && this.usePreprocessor) {
      if (!this.ppEndpoint.trim()) {
        this.error = 'Endpoint URL is required when preprocessor is enabled';
        return;
      }
      if (!this.isValidUrl(this.ppEndpoint)) {
        this.error = 'Preprocessor endpoint must be a valid URL (e.g., http://service:8080/preprocess)';
        return;
      }
    }

    // Validate step 5 — data quality
    if (this.step === 5) {
      if (this.dqValidateHeader && (this.sourceType === 'json' || this.sourceType === 'xml') && !this.dqValidationSchema.trim()) {
        this.error = 'A validation schema file is required when header validation is enabled for ' + this.sourceType.toUpperCase();
        return;
      }
      if (this.dqUseAiRule && !this.dqAiInstruction.trim()) {
        this.error = 'An AI instruction is required when AI Rule is enabled';
        return;
      }
    }

    // Step 6 — transformation (no required fields, instruction is optional)

    // Validate step 7 — destination schema
    if (this.step === 7 && this.sourceType === 'csv') {
      if (this.destSchemaFields.length === 0 || this.destSchemaFields.every(f => !f.name.trim())) {
        this.error = 'At least one field with a name is required in the destination schema';
        return;
      }
      const blank = this.destSchemaFields.find(f => !f.name.trim());
      if (blank) {
        this.error = 'All destination schema fields must have a name — remove empty fields or fill in their names';
        return;
      }
    }

    // Validate step 8 — destination
    if (this.step === 8) {
      if (this.destType === 'postgres') {
        if (!this.pgDbName.trim()) { this.error = 'Database name is required'; return; }
        if (!this.pgSchema.trim()) { this.error = 'Schema is required'; return; }
        if (!this.pgTable.trim()) { this.error = 'Table name is required'; return; }
      } else if (this.destType === 'mongodb') {
        if (!this.mongoDbName.trim()) { this.error = 'Database name is required'; return; }
        if (!this.mongoTable.trim()) { this.error = 'Collection name is required'; return; }
      } else if (this.destType === 'snowflake') {
        if (!this.sfCredentialsSecret.trim()) { this.error = 'Credentials secret is required for Snowflake'; return; }
        if (!this.sfWarehouse.trim()) { this.error = 'Warehouse is required'; return; }
        if (!this.sfDbName.trim()) { this.error = 'Database name is required'; return; }
        if (!this.sfSchema.trim()) { this.error = 'Schema is required'; return; }
        if (!this.sfTable.trim()) { this.error = 'Table name is required'; return; }
      } else if (this.destType === 'databricks') {
        if (!this.dbxCredentialsSecret.trim()) { this.error = 'Credentials secret is required for Databricks'; return; }
        if (!this.dbxWarehouse.trim()) { this.error = 'SQL warehouse ID is required'; return; }
        if (!this.dbxCatalog.trim()) { this.error = 'Catalog is required'; return; }
        if (!this.dbxSchema.trim()) { this.error = 'Schema is required'; return; }
        if (!this.dbxTable.trim()) { this.error = 'Table name is required'; return; }
      } else if (this.destType === 'objectstore') {
        if (!this.osPrefix.trim()) { this.error = 'Key is required'; return; }
        if (this.osProvider === 's3') {
          if (!this.osBucket.trim()) { this.error = 'Bucket is required when provider is S3'; return; }
          if (this.osEndpoint.trim() && this.osEndpoint.trim().toLowerCase().startsWith('http://')) {
            this.error = 'S3 endpoint must use https://'; return;
          }
        }
      } else if (this.destType === 'kafka') {
        if (!this.kafkaTopic.trim()) { this.error = 'Topic is required'; return; }
      } else if (this.destType === 'activemq') {
        if (!this.amqQueue.trim()) { this.error = 'Queue name is required'; return; }
      } else if (this.destType === 'restendpoint') {
        if (!this.restEndpointUrl.trim()) { this.error = 'Endpoint URL is required'; return; }
        if (!this.isValidUrl(this.restEndpointUrl)) { this.error = 'Endpoint must be a valid URL'; return; }
      } else if (this.destType === 'pgvector') {
        if (!this.vectorTable.trim()) { this.error = 'Collection name is required'; return; }
      } else if (this.destType === 'weaviate') {
        if (!this.vectorClassName.trim()) { this.error = 'Class name is required'; return; }
      } else if (this.destType === 'qdrant' || this.destType === 'milvus' || this.destType === 'chroma') {
        if (!this.vectorCollection.trim()) { this.error = 'Collection name is required'; return; }
      }
    }

    this.step++;

    // Skip schema (3), preprocessor (4), dq (5), transformation (6) for unstructured
    if (this.isUnstructured() && this.step >= 3 && this.step <= 7) {
      this.step = 8;
    }

    // Auto-profile when entering DQ step (5) if sample file exists
    if (this.step === 5 && this.sampleFileDetected && !this.dqProfileSummary) {
      this.autoProfileSampleFile();
    }

    // Initialize destination schema from source schema when entering step 7 (CSV only)
    if (this.step === 7 && this.sourceType === 'csv') {
      if (this.destSchemaFields.length === 0) {
        this.destSchemaFields = this.schemaFields
          .filter(f => f.name.trim())
          .map(f => ({ name: f.name, type: f.type }));
      }
    }

    // Skip destination schema (7) for non-CSV
    if (this.step === 7 && this.sourceType !== 'csv') {
      this.step = 8;
    }

    // Load metadata when entering destination step (8).
    // Db name is server-chosen (see /api/v1/version); only schemas/tables need lookup.
    if (this.step === 8) {
      this.loadPgSchemas();
      this.loadMongoCollections();
    }

  }

  prevStep(): void {
    this.step--;

    // Skip destination schema (7) for non-CSV going back
    if (this.step === 7 && this.sourceType !== 'csv') {
      this.step = 6;
    }

    // Skip transformation (6), dq (5), preprocessor (4), schema (3) for unstructured going back
    if (this.isUnstructured() && this.step >= 3 && this.step <= 7) {
      this.step = 2;
    }
  }

  private readonly TX_TRIM_TEXT = 'Trim leading/trailing whitespace from all columns.';
  private readonly TX_DEDUP_TEXT = 'Remove duplicate rows.';

  onTxCheckboxChange(): void {
    // Add or remove the checkbox text from the instruction
    let instruction = this.txAiInstruction;

    // Remove existing checkbox text first
    instruction = instruction.replace(this.TX_TRIM_TEXT, '').replace(this.TX_DEDUP_TEXT, '').trim();

    // Append checked items
    const additions: string[] = [];
    if (this.txTrimWhitespace) additions.push(this.TX_TRIM_TEXT);
    if (this.txDeduplicate) additions.push(this.TX_DEDUP_TEXT);

    if (additions.length > 0) {
      instruction = instruction ? instruction + ' ' + additions.join(' ') : additions.join(' ');
    }

    this.txAiInstruction = instruction;
  }


  isValidUrl(url: string): boolean {
    try {
      const u = new URL(url);
      return u.protocol === 'http:' || u.protocol === 'https:';
    } catch {
      return false;
    }
  }

  autoProfileSampleFile(): void {
    if (!this.sampleFile || !this.pipelineName || this.sourceType === 'unstructured') return;
    this.pipelineService.generateSchema(
      this.sampleFile, this.pipelineName,
      this.sourceType === 'csv' ? this.csvDelimiter : undefined,
      this.sourceType === 'csv' ? this.csvHeader : undefined
    ).subscribe({
      next: (response: any) => {
        if (response.source?.schemaProperties?.fields) {
          const fields = response.source.schemaProperties.fields;
          this.dqProfileSummary = 'Detected ' + fields.length + ' fields: ' +
            fields.map((f: any) => f.name + ' (' + f.type + ')').join(', ');
        }
      },
      error: () => { /* ignore profiling errors */ }
    });
  }

  isValidRegex(pattern: string): boolean {
    try {
      new RegExp(pattern);
      return true;
    } catch {
      return false;
    }
  }

  buildConfig(): any {
    const config: any = { name: this.pipelineName, source: {}, destination: {} };

    // Preprocessor
    if (this.usePreprocessor && this.ppEndpoint) {
      config.preprocessor = {
        endpoint: this.ppEndpoint,
        async: this.ppAsync,
        timeoutMs: this.ppTimeout
      };
      if (this.ppApiKey) { config.preprocessor.apiKey = this.ppApiKey; }
      if (this.ppBearerToken) {
        config.preprocessor.bearerToken = this.ppBearerToken;
      }
    }

    // Source
    if (this.sourceType === 'csv') {
      config.source.fileAttributes = {
        csvAttributes: { delimiter: this.csvDelimiter, encoding: this.csvEncoding, header: this.csvHeader }
      };
    } else if (this.sourceType === 'json') {
      config.source.fileAttributes = {
        jsonAttributes: { everyRowContainsObject: this.jsonEveryRow, encoding: 'UTF-8' }
      };
    } else if (this.sourceType === 'xml') {
      config.source.fileAttributes = {
        xmlAttributes: { everyRowContainsObject: this.xmlEveryRow, encoding: 'UTF-8' }
      };
    } else if (this.sourceType === 'unstructured') {
      config.source.fileAttributes = {
        unstructuredAttributes: { fileExtension: this.unstructuredExtension, preserveFilename: true }
      };
    }

    // Schema (not for unstructured)
    if (!this.isUnstructured()) {
      config.source.schemaProperties = {
        dbName: this.schemaDbName,
        fields: this.schemaFields.filter(f => f.name.trim())
      };
    }

    // Data Quality
    const dq: any = {};
    let hasDq = false;

    if (this.dqValidateHeader && this.sourceType === 'csv') {
      dq.validateFileHeader = true;
      hasDq = true;
    }
    if (this.dqValidationSchema && (this.sourceType === 'json' || this.sourceType === 'xml')) {
      dq.validationSchema = this.dqValidationSchema;
      hasDq = true;
    }
    if (this.dqUseAiRule && this.dqAiInstruction) {
      dq.aiRule = {
        instruction: this.dqAiInstruction,
        onFailureIsError: this.dqAiOnFailureIsError
      };
      hasDq = true;
    }
    if (hasDq) {
      config.dataQuality = dq;
    }

    // Transformation
    if (this.txAiInstruction.trim()) {
      config.transformation = { aiTransformation: { instruction: this.txAiInstruction } };
    }

    // Destination Schema (CSV only — if fields were modified from source)
    if (this.sourceType === 'csv' && this.destSchemaFields.length > 0) {
      const destFields = this.destSchemaFields.filter(f => f.name.trim());
      if (destFields.length > 0) {
        config.destination.schemaProperties = {
          dbName: this.schemaDbName,
          fields: destFields
        };
      }
    }

    // Destination
    if (this.destType === 'postgres') {
      const pgDb: any = { dbName: sanitizeIdentifier(this.pgDbName), schema: sanitizeIdentifier(this.pgSchema), table: sanitizeIdentifier(this.pgTable), usePostgres: true, truncateBeforeWrite: this.dbTruncateBeforeWrite };
      const pgKeys = this.pgKeyFields.filter(k => k && k.trim());
      if (pgKeys.length > 0) pgDb.keyFields = pgKeys;
      config.destination.database = pgDb;
    } else if (this.destType === 'mongodb') {
      const mongoDb: any = { dbName: sanitizeIdentifier(this.mongoDbName), table: sanitizeIdentifier(this.mongoTable), useMongoDB: true, truncateBeforeWrite: this.dbTruncateBeforeWrite };
      const mongoKeys = this.mongoKeyFields.filter(k => k && k.trim());
      if (mongoKeys.length > 0) mongoDb.keyFields = mongoKeys;
      config.destination.database = mongoDb;
    } else if (this.destType === 'snowflake') {
      // Preserve case — Snowflake quotes identifiers (case-sensitive) and its
      // convention is uppercase; sanitizeIdentifier would lowercase them.
      const sfDb: any = {
        dbName: this.sfDbName.trim(),
        schema: this.sfSchema.trim(),
        table: this.sfTable.trim(),
        warehouse: this.sfWarehouse.trim(),
        credentialsSecret: this.sfCredentialsSecret.trim(),
        useSnowflake: true,
        truncateBeforeWrite: this.dbTruncateBeforeWrite
      };
      if (this.sfRole.trim()) sfDb.role = this.sfRole.trim();
      const sfKeys = this.sfKeyFields.filter(k => k && k.trim());
      if (sfKeys.length > 0) sfDb.keyFields = sfKeys;
      config.destination.database = sfDb;
    } else if (this.destType === 'databricks') {
      // Preserve case — Databricks backtick-quotes non-simple identifiers;
      // sanitizeIdentifier would lowercase them.
      const dbxDb: any = {
        dbName: this.dbxCatalog.trim(),
        schema: this.dbxSchema.trim(),
        table: this.dbxTable.trim(),
        warehouse: this.dbxWarehouse.trim(),
        credentialsSecret: this.dbxCredentialsSecret.trim(),
        useDatabricks: true,
        truncateBeforeWrite: this.dbTruncateBeforeWrite
      };
      const dbxKeys = this.dbxKeyFields.filter(k => k && k.trim());
      if (dbxKeys.length > 0) dbxDb.keyFields = dbxKeys;
      config.destination.database = dbxDb;
    } else if (this.destType === 'objectstore') {
      const os: any = { prefixKey: this.osPrefix, fileFormat: this.osFormat, deleteBeforeWrite: this.osDeleteBeforeWrite };
      if (this.osBucket.trim()) os.destinationBucketOverride = this.osBucket.trim();
      const partitions = this.osPartitionBy.filter(p => p && p.trim());
      if (partitions.length > 0) os.partitionBy = partitions;
      if (this.osProvider === 's3') {
        os.provider = 's3';
        if (this.osEndpoint.trim()) os.endpoint = this.osEndpoint.trim();
        if (this.osCredentialsSecret.trim()) os.credentialsSecret = this.osCredentialsSecret.trim();
      }
      config.destination.objectStore = os;
    } else if (this.destType === 'kafka') {
      config.destination.kafka = { topic: sanitizeIdentifier(this.kafkaTopic) };
    } else if (this.destType === 'activemq') {
      config.destination.activeMQ = { queueName: sanitizeIdentifier(this.amqQueue) };
    } else if (this.destType === 'restendpoint') {
      const re: any = { endpoint: this.restEndpointUrl, async: this.restEndpointAsync, timeoutMs: this.restEndpointTimeout };
      if (this.restEndpointBearerToken) re.bearerToken = this.restEndpointBearerToken;
      if (this.restEndpointApiKey) re.apiKey = this.restEndpointApiKey;
      config.destination.restEndpoint = re;
    } else if (this.destType === 'qdrant') {
      config.destination.qdrant = {
        collectionName: sanitizeIdentifier(this.vectorCollection), embeddingSecretName: this.embeddingSecret,
        qdrantSecretName: this.vectorSecret,
        chunking: { strategy: this.chunkStrategy, chunkSize: this.chunkSize, chunkOverlap: this.chunkOverlap }
      };
    } else if (this.destType === 'weaviate') {
      config.destination.weaviate = {
        className: this.vectorClassName, embeddingSecretName: this.embeddingSecret,
        weaviateSecretName: this.vectorSecret,
        chunking: { strategy: this.chunkStrategy, chunkSize: this.chunkSize, chunkOverlap: this.chunkOverlap }
      };
    } else if (this.destType === 'milvus') {
      config.destination.milvus = {
        collectionName: sanitizeIdentifier(this.vectorCollection), embeddingSecretName: this.embeddingSecret,
        milvusSecretName: this.vectorSecret,
        chunking: { strategy: this.chunkStrategy, chunkSize: this.chunkSize, chunkOverlap: this.chunkOverlap }
      };
    } else if (this.destType === 'chroma') {
      config.destination.chroma = {
        collectionName: sanitizeIdentifier(this.vectorCollection), embeddingSecretName: this.embeddingSecret,
        chromaSecretName: this.vectorSecret,
        chunking: { strategy: this.chunkStrategy, chunkSize: this.chunkSize, chunkOverlap: this.chunkOverlap }
      };
    } else if (this.destType === 'pgvector') {
      config.destination.pgvector = {
        tableName: sanitizeIdentifier(this.vectorTable || this.vectorCollection), schemaName: sanitizeIdentifier(this.vectorSchema),
        embeddingSecretName: this.embeddingSecret, postgresSecretName: this.vectorSecret,
        chunking: { strategy: this.chunkStrategy, chunkSize: this.chunkSize, chunkOverlap: this.chunkOverlap }
      };
    }

    return config;
  }

  create(): void {
    this.creating = true;
    this.error = '';

    const config: any = this.buildConfig();

    // Set catalog on the pipeline config
    config.catalog = this.catalog || null;

    this.pipelineService.createPipeline(config).subscribe({
      next: () => {
        // If created from a tap, link the tap to this pipeline
        if (this.pipelineSource === 'tap' && this.selectedTapName) {
          const tap = this.taps.find(t => t.name === this.selectedTapName);
          if (tap) {
            tap.targetPipeline = this.pipelineName;
            this.tapService.createOrUpdateTap(tap).subscribe({
              next: () => this.router.navigate(['/pipelines']),
              error: () => this.router.navigate(['/pipelines'])
            });
            return;
          }
        }
        this.router.navigate(['/pipelines']);
      },
      error: (err: any) => {
        let detail: string;
        if (typeof err.error === 'string' && err.error.trim()) {
          detail = err.error;
        } else if (err.error?.message) {
          detail = err.error.message;
        } else if (err.status) {
          detail = 'HTTP ' + err.status + ': ' + (err.statusText || 'Unknown error');
          if (err.error) detail += ' — ' + JSON.stringify(err.error);
        } else {
          detail = err.message || 'Unknown error';
        }
        this.error = 'Failed to create dataset: ' + detail;
        this.creating = false;
      }
    });
  }
}
