import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { PipelineService } from '../pipeline.service';
import { SearchService } from '../search.service';

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

  // Step 1 — Basics + Sample File
  pipelineName = '';
  sampleFile: File | null = null;
  sourceType = 'csv';

  // Step 2 — Source config
  csvDelimiter = ',';
  csvEncoding = 'UTF-8';
  csvHeader = true;
  jsonEveryRow = false;
  showJsonInfo = false;
  xmlEveryRow = true;
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

  // Step 4 — Data Quality
  useDq = false;
  dqValidateHeader = false;
  dqValidationSchema = '';
  dqUseAiRule = false;
  dqAiInstruction = '';
  dqAiOnFailureIsError = false;
  dqAiSample = false;
  dqAiSampleSize = 200;
  showSampleInfo = false;
  showRowModeInfo = false;
  dqProfileSummary = '';
  dqSelectedColumn = '';
  regexPrompt = '';
  regexResult = '';
  regexExplanation = '';
  regexGenerating = false;
  dqColumnRules: { columnName: string; function: string; parameter: string; onFailureIsError: boolean; description: string }[] = [];
  dqRowRules: { function: string; parameters: string[]; onFailureIsError: boolean }[] = [];

  // Step 6 — Transformation (optional, not for unstructured)
  useTransformation = false;
  txTrimWhitespace = false;
  txDeduplicate = false;
  txUseAi = false;
  txAiInstruction = '';
  txAiSample = false;
  txAiSampleSize = 200;
  txUseRowFunction = false;
  txRowFunctionFile = '';
  txUseRestEndpoint = false;
  txRestEndpoint = '';
  txRestMode = 'row';
  txRestTimeout = '30000';
  txRestBearerToken = '';
  txRestApiKey = '';
  showTxSampleInfo = false;

  // Step 7 — Destination
  destType = 'postgres';
  pgDbName = 'datris';
  pgSchema = 'public';
  pgTable = '';
  mongoDbName = 'datris';
  mongoTable = '';
  osPrefix = '';
  osFormat = 'parquet';
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
  pgDatabases: string[] = [];
  pgSchemas: string[] = [];
  pgTables: string[] = [];
  mongoDatabases: string[] = [];
  mongoCollections: string[] = [];

  fieldTypes = ['string', 'int', 'bigint', 'float', 'double', 'boolean', 'date', 'timestamp'];

  constructor(private pipelineService: PipelineService, private searchService: SearchService, private route: ActivatedRoute, private router: Router) { }

  ngOnInit(): void {
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
      this.useDq = true;
      const dq = config.dataQuality;
      this.dqValidateHeader = dq.validateFileHeader || false;
      this.dqValidationSchema = dq.validationSchema || '';
      if (dq.aiRule) {
        this.dqUseAiRule = true;
        this.dqAiInstruction = dq.aiRule.instruction || '';
        this.dqAiOnFailureIsError = dq.aiRule.onFailureIsError || false;
        this.dqAiSample = dq.aiRule.sample || false;
        this.dqAiSampleSize = dq.aiRule.sampleSize || 200;
      }
      if (dq.columnRules && dq.columnRules.length > 0) {
        this.dqColumnRules = dq.columnRules.map((r: any) => ({
          columnName: r.columnName || '', function: r.function || 'regex',
          parameter: r.parameter || '', onFailureIsError: r.onFailureIsError || false,
          description: r.description || ''
        }));
      }
      if (dq.rowRules && dq.rowRules.length > 0) {
        this.dqRowRules = dq.rowRules.map((r: any) => ({
          function: r.function || 'ai',
          parameters: r.parameters || [''],
          onFailureIsError: r.onFailureIsError || false
        }));
      }
    }

    // Transformation
    if (config.transformation) {
      this.useTransformation = true;
      const tx = config.transformation;
      this.txTrimWhitespace = tx.trimColumnWhitespace || false;
      this.txDeduplicate = tx.deduplicate || false;
      if (tx.rowFunctions && tx.rowFunctions.length > 0) {
        for (const rf of tx.rowFunctions) {
          if (rf.function === 'javascript') {
            this.txUseRowFunction = true;
            this.txRowFunctionFile = rf.parameters?.[0] || '';
          } else if (rf.function === 'restEndpoint') {
            this.txUseRestEndpoint = true;
            this.txRestEndpoint = rf.parameters?.[0] || '';
            this.txRestMode = rf.parameters?.[1] || 'row';
            this.txRestTimeout = rf.parameters?.[2] || '30000';
            this.txRestBearerToken = rf.parameters?.[3] || '';
            this.txRestApiKey = rf.parameters?.[4] || '';
          }
        }
      }
      if (tx.aiTransformation) {
        this.txUseAi = true;
        this.txAiInstruction = tx.aiTransformation.instruction || '';
        this.txAiSample = tx.aiTransformation.sample || false;
        this.txAiSampleSize = tx.aiTransformation.sampleSize || 200;
      }
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

    // Destination
    const dest = config.destination;
    if (dest?.database?.usePostgres) {
      this.destType = 'postgres';
      this.pgDbName = dest.database.dbName || 'datris';
      this.pgSchema = dest.database.schema || 'public';
      this.pgTable = dest.database.table || '';
    } else if (dest?.database?.useMongoDB) {
      this.destType = 'mongodb';
      this.mongoDbName = dest.database.dbName || 'datris';
      this.mongoTable = dest.database.table || '';
    } else if (dest?.objectStore) {
      this.destType = 'objectstore';
      this.osPrefix = dest.objectStore.prefixKey || '';
      this.osFormat = dest.objectStore.fileFormat || 'parquet';
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

  loadPgDatabases(): void {
    this.searchService.getPostgresDatabases().subscribe({
      next: (databases) => {
        this.pgDatabases = databases;
        if (databases.length > 0 && !databases.includes(this.pgDbName)) {
          this.pgDbName = databases[0];
        }
        this.loadPgSchemas();
      },
      error: () => { this.pgDatabases = []; }
    });
  }

  onPgDbChange(): void {
    this.pgSchema = '';
    this.pgTable = '';
    this.loadPgSchemas();
  }

  loadMongoDatabases(): void {
    this.searchService.getMongoDatabases().subscribe({
      next: (databases) => {
        this.mongoDatabases = databases;
        if (databases.length > 0 && !databases.includes(this.mongoDbName)) {
          this.mongoDbName = databases[0];
        }
        this.loadMongoCollections();
      },
      error: () => { this.mongoDatabases = []; }
    });
  }

  onMongoDbChange(): void {
    this.mongoTable = '';
    this.loadMongoCollections();
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
      // JSON can't go to PostgreSQL or Object Store
      if (this.destType === 'postgres' || this.destType === 'objectstore') {
        this.destType = 'mongodb';
      }
    } else if (this.sourceType === 'xml') {
      this.schemaFields = [{ name: '_xml', type: 'string' }];
      if (this.destType === 'postgres' || this.destType === 'objectstore') {
        this.destType = 'mongodb';
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

  addField(): void {
    this.schemaFields.push({ name: '', type: 'string' });
  }

  removeField(index: number): void {
    this.schemaFields.splice(index, 1);
  }

  onSampleFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.sampleFile = input.files[0];
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

  onJsFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0 && this.dqRowRules.length > 0) {
      const file = input.files[0];
      this.dqRowRules[0].parameters[0] = file.name;
      // Upload to MinIO
      this.pipelineService.uploadConfigFile(file, 'javascript').subscribe({
        next: (resp) => {
          this.dqRowRules[0].parameters[0] = resp.filename;
        },
        error: (err) => {
          this.error = 'Failed to upload JavaScript file: ' + (err.error || err.message);
        }
      });
    }
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
    if (!this.sampleFile || !this.pipelineName.trim()) {
      this.error = !this.pipelineName.trim() ? 'Pipeline name is required' : 'Please select a file';
      return;
    }

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
        this.sampleFile, this.pipelineName,
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
    if (this.step === 1 && this.sampleFile && !this.sampleFileDetected) {
      this.error = 'Please press Analyze File before continuing';
      return;
    }

    // Validate step 2 — preprocessor
    if (this.step === 2 && this.usePreprocessor) {
      if (!this.ppEndpoint.trim()) {
        this.error = 'An endpoint URL is required when preprocessor is enabled';
        return;
      }
      if (!this.isValidUrl(this.ppEndpoint)) {
        this.error = 'Preprocessor endpoint must be a valid URL (e.g., http://service:8080/preprocess)';
        return;
      }
    }

    // Validate step 4 — data quality
    if (this.step === 4 && this.useDq) {
      if (this.dqValidateHeader && (this.sourceType === 'json' || this.sourceType === 'xml') && !this.dqValidationSchema.trim()) {
        this.error = 'A validation schema file is required when header validation is enabled for ' + this.sourceType.toUpperCase();
        return;
      }
      if (this.dqUseAiRule && !this.dqAiInstruction.trim()) {
        this.error = 'An AI instruction is required when AI Rule is enabled';
        return;
      }
    }

    // Validate step 5 — row rules and column rules
    if (this.step === 5) {
      // Row rule validation
      if (this.dqRowRules.length > 0) {
        const rule = this.dqRowRules[0];
        if (rule.function === 'restEndpoint' && !rule.parameters[0]?.trim()) {
          this.error = 'An endpoint URL is required for REST Endpoint row rules';
          return;
        }
        if (rule.function === 'restEndpoint' && rule.parameters[0] && !this.isValidUrl(rule.parameters[0])) {
          this.error = 'Row rule endpoint must be a valid URL (e.g., http://service:8080/validate)';
          return;
        }
        if (rule.function === 'javascript' && !rule.parameters[0]?.trim()) {
          this.error = 'A JavaScript file is required';
          return;
        }
      }
      // Column rule validation
      for (const rule of this.dqColumnRules) {
        if (!rule.parameter?.trim()) {
          this.error = 'A regex pattern is required for column "' + rule.columnName + '"';
          return;
        }
        if (!this.isValidRegex(rule.parameter)) {
          this.error = 'Invalid regex pattern for column "' + rule.columnName + '": ' + rule.parameter;
          return;
        }
      }
    }

    // Validate step 6 — transformation
    if (this.step === 6 && this.useTransformation) {
      if (this.txUseAi && !this.txAiInstruction.trim()) {
        this.error = 'An AI instruction is required when AI Transformation is enabled';
        return;
      }
      if (this.txUseRowFunction && !this.txRowFunctionFile.trim()) {
        this.error = 'A JavaScript file is required when row function is enabled';
        return;
      }
      if (this.txUseRestEndpoint && !this.txRestEndpoint.trim()) {
        this.error = 'Endpoint URL is required when REST endpoint transformation is enabled';
        return;
      }
    }

    // Validate step 7 — destination
    if (this.step === 7) {
      if (this.destType === 'postgres') {
        if (!this.pgDbName.trim()) { this.error = 'Database name is required'; return; }
        if (!this.pgSchema.trim()) { this.error = 'Schema is required'; return; }
        if (!this.pgTable.trim()) { this.error = 'Table name is required'; return; }
      } else if (this.destType === 'mongodb') {
        if (!this.mongoDbName.trim()) { this.error = 'Database name is required'; return; }
        if (!this.mongoTable.trim()) { this.error = 'Collection name is required'; return; }
      } else if (this.destType === 'objectstore') {
        if (!this.osPrefix.trim()) { this.error = 'Prefix key is required'; return; }
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

    // Skip schema (3), dq (4), dq rules (5), transformation (6) for unstructured
    if (this.isUnstructured() && this.step >= 3 && this.step <= 6) {
      this.step = 7;
    }

    // Auto-profile when entering DQ step (4) if sample file exists
    if (this.step === 4 && this.sampleFileDetected && !this.dqProfileSummary) {
      this.autoProfileSampleFile();
    }

    // Skip dq rules (5) if data quality not enabled or not CSV
    if (this.step === 5 && (!this.useDq || this.sourceType !== 'csv')) {
      this.step = 6;
    }

    // Skip transformation (6) for unstructured (already handled above)
    // Transformation is optional — always shown for structured types

    // Load metadata when entering destination step (7)
    if (this.step === 7) {
      this.loadPgDatabases();
      this.loadMongoDatabases();
    }

    // Generate config JSON when entering review step (8)
    if (this.step === 8) {
      this.configJson = JSON.stringify(this.buildConfig(), null, 2);
    }
  }

  prevStep(): void {
    this.step--;

    // Skip transformation (6) for unstructured going back
    if (this.step === 6 && this.isUnstructured()) {
      this.step = 2;
    }

    // Skip dq rules (5) if data quality not enabled or not CSV
    if (this.step === 5 && (!this.useDq || this.sourceType !== 'csv')) {
      this.step = 4;
    }

    // Skip dq (4), schema (3) for unstructured going back
    if (this.isUnstructured() && this.step >= 3 && this.step <= 6) {
      this.step = 2;
    }
  }

  onTxRowFunctionFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      const file = input.files[0];
      this.txRowFunctionFile = file.name;
      this.pipelineService.uploadConfigFile(file, 'javascript').subscribe({
        next: (resp) => { this.txRowFunctionFile = resp.filename; },
        error: (err) => { this.error = 'Failed to upload JavaScript file: ' + (err.error || err.message); }
      });
    }
  }

  // Data quality helpers
  addColumnRule(): void {
    this.dqColumnRules.push({ columnName: '', function: 'regex', parameter: '', onFailureIsError: false, description: '' });
  }

  removeColumnRule(index: number): void {
    this.dqColumnRules.splice(index, 1);
  }

  addRowRule(): void {
    this.dqRowRules.push({ function: 'restEndpoint', parameters: ['', 'row', '30000', ''], onFailureIsError: false });
  }

  removeRowRule(index: number): void {
    this.dqRowRules.splice(index, 1);
  }

  onRowRuleFunctionChange(rule: any): void {
    if (rule.function === 'restEndpoint') {
      rule.parameters = ['', 'row', '30000', '', ''];
    } else if (rule.function === 'javascript') {
      rule.parameters = [''];
    }
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

  addColumnRuleForColumn(): void {
    if (!this.dqSelectedColumn) return;
    const exists = this.dqColumnRules.some(r => r.columnName === this.dqSelectedColumn);
    if (exists) return;
    this.dqColumnRules.push({
      columnName: this.dqSelectedColumn, function: 'regex',
      parameter: '', onFailureIsError: false, description: ''
    });
    this.dqSelectedColumn = '';
  }

  getAvailableColumns(): string[] {
    return this.schemaFields
      .filter(f => f.name.trim())
      .filter(f => !this.dqColumnRules.some(r => r.columnName === f.name))
      .map(f => f.name);
  }

  isValidRegex(pattern: string): boolean {
    try {
      new RegExp(pattern);
      return true;
    } catch {
      return false;
    }
  }

  generateRegex(): void {
    if (!this.regexPrompt) return;
    this.regexGenerating = true;
    this.regexResult = '';
    this.regexExplanation = '';

    const query = 'Generate a regex pattern that: ' + this.regexPrompt +
      '. Return ONLY the regex pattern on the first line, then a brief explanation on the second line. No markdown, no code fences.';

    this.searchService.aiAnswer(query, 'Generate a regex expression for data validation.').subscribe({
      next: (response: any) => {
        const answer = response.answer || '';
        const lines = answer.trim().split('\n').filter((l: string) => l.trim());
        if (lines.length > 0) {
          // Extract regex - remove any wrapping like /pattern/ or `pattern`
          let pattern = lines[0].trim().replace(/^[`\/]|[`\/]$/g, '');
          this.regexResult = pattern;
          this.regexExplanation = lines.slice(1).join(' ').trim();
        }
        this.regexGenerating = false;
      },
      error: (err: any) => {
        this.regexResult = 'Error: ' + (err.error || err.message);
        this.regexGenerating = false;
      }
    });
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
        onFailureIsError: this.dqAiOnFailureIsError,
        sample: this.dqAiSample,
        sampleSize: this.dqAiSampleSize
      };
      hasDq = true;
    }
    if (this.dqColumnRules.length > 0) {
      const rules = this.dqColumnRules.filter(r => r.columnName && r.parameter);
      if (rules.length > 0) { dq.columnRules = rules; hasDq = true; }
    }
    if (this.dqRowRules.length > 0) {
      const rules = this.dqRowRules.filter(r => r.parameters[0]);
      if (rules.length > 0) { dq.rowRules = rules; hasDq = true; }
    }
    if (hasDq) {
      config.dataQuality = dq;
    }

    // Transformation
    if (this.useTransformation) {
      const tx: any = {};
      let hasTx = false;
      if (this.txTrimWhitespace && this.sourceType === 'csv') {
        tx.trimColumnWhitespace = true; hasTx = true;
      }
      if (this.txDeduplicate) {
        tx.deduplicate = true; hasTx = true;
      }
      if (this.txUseRowFunction && this.txRowFunctionFile && this.sourceType === 'csv') {
        tx.rowFunctions = [{ function: 'javascript', parameters: [this.txRowFunctionFile] }];
        hasTx = true;
      }
      if (this.txUseRestEndpoint && this.txRestEndpoint.trim()) {
        if (!tx.rowFunctions) tx.rowFunctions = [];
        tx.rowFunctions.push({
          function: 'restEndpoint',
          parameters: [this.txRestEndpoint, this.txRestMode, this.txRestTimeout, this.txRestBearerToken, this.txRestApiKey]
        });
        hasTx = true;
      }
      if (this.txUseAi && this.txAiInstruction) {
        tx.aiTransformation = { instruction: this.txAiInstruction };
        if (this.txAiSample && this.sourceType === 'csv') {
          tx.aiTransformation.sample = true;
          tx.aiTransformation.sampleSize = this.txAiSampleSize;
        }
        hasTx = true;
      }
      if (hasTx) config.transformation = tx;
    }

    // Destination
    if (this.destType === 'postgres') {
      config.destination.database = { dbName: this.pgDbName, schema: this.pgSchema, table: this.pgTable, usePostgres: true };
    } else if (this.destType === 'mongodb') {
      config.destination.database = { dbName: this.mongoDbName, table: this.mongoTable, useMongoDB: true };
    } else if (this.destType === 'objectstore') {
      config.destination.objectStore = { prefixKey: this.osPrefix, fileFormat: this.osFormat };
    } else if (this.destType === 'kafka') {
      config.destination.kafka = { topic: this.kafkaTopic };
    } else if (this.destType === 'activemq') {
      config.destination.activeMQ = { queueName: this.amqQueue };
    } else if (this.destType === 'restendpoint') {
      const re: any = { endpoint: this.restEndpointUrl, async: this.restEndpointAsync, timeoutMs: this.restEndpointTimeout };
      if (this.restEndpointBearerToken) re.bearerToken = this.restEndpointBearerToken;
      if (this.restEndpointApiKey) re.apiKey = this.restEndpointApiKey;
      config.destination.restEndpoint = re;
    } else if (this.destType === 'qdrant') {
      config.destination.qdrant = {
        collectionName: this.vectorCollection, embeddingSecretName: this.embeddingSecret,
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
        collectionName: this.vectorCollection, embeddingSecretName: this.embeddingSecret,
        milvusSecretName: this.vectorSecret,
        chunking: { strategy: this.chunkStrategy, chunkSize: this.chunkSize, chunkOverlap: this.chunkOverlap }
      };
    } else if (this.destType === 'chroma') {
      config.destination.chroma = {
        collectionName: this.vectorCollection, embeddingSecretName: this.embeddingSecret,
        chromaSecretName: this.vectorSecret,
        chunking: { strategy: this.chunkStrategy, chunkSize: this.chunkSize, chunkOverlap: this.chunkOverlap }
      };
    } else if (this.destType === 'pgvector') {
      config.destination.pgvector = {
        tableName: this.vectorTable || this.vectorCollection, schemaName: this.vectorSchema,
        embeddingSecretName: this.embeddingSecret, postgresSecretName: this.vectorSecret,
        chunking: { strategy: this.chunkStrategy, chunkSize: this.chunkSize, chunkOverlap: this.chunkOverlap }
      };
    }

    return config;
  }

  create(): void {
    this.creating = true;
    this.error = '';

    let config;
    try {
      config = JSON.parse(this.configJson);
    } catch (e) {
      this.error = 'Invalid JSON configuration';
      this.creating = false;
      return;
    }

    this.pipelineService.createPipeline(config).subscribe({
      next: () => {
        this.router.navigate(['/pipelines']);
      },
      error: (err: any) => {
        this.error = 'Failed to create dataset: ' + (err.error || err.message);
        this.creating = false;
      }
    });
  }
}
