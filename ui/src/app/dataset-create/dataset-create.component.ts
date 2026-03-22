import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { DatasetService } from '../dataset.service';
import { SearchService } from '../search.service';

interface SchemaField {
  name: string;
  type: string;
}

@Component({
  selector: 'app-dataset-create',
  templateUrl: './dataset-create.component.html',
  styleUrls: ['./dataset-create.component.css']
})
export class DatasetCreateComponent implements OnInit {
  isEditMode = false;
  step = 1;
  error = '';
  creating = false;
  generatingSchema = false;
  sampleFileDetected = false;

  // Step 1 — Basics + Sample File
  datasetName = '';
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

  // Step 3 — Schema
  schemaDbName = 'idata';
  schemaFields: SchemaField[] = [{ name: '', type: 'string' }];
  schemaFile: File | null = null;

  // Step 4 — Destination
  destType = 'postgres';
  pgDbName = 'idata';
  pgSchema = 'public';
  pgTable = '';
  mongoDbName = 'idata';
  mongoTable = '';
  osPrefix = '';
  osFormat = 'parquet';
  kafkaTopic = '';
  amqQueue = '';
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

  constructor(private datasetService: DatasetService, private searchService: SearchService, private route: ActivatedRoute, private router: Router) { }

  ngOnInit(): void {
    const editName = this.route.snapshot.paramMap.get('name');
    if (editName) {
      this.isEditMode = true;
      this.datasetService.getDataset(editName).subscribe({
        next: (config) => this.loadFromConfig(config),
        error: (err) => this.error = 'Failed to load dataset: ' + (err.error || err.message)
      });
    }
  }

  loadFromConfig(config: any): void {
    this.datasetName = config.name || '';
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
      this.schemaDbName = config.source.schemaProperties.dbName || 'idata';
      if (config.source.schemaProperties.fields) {
        this.schemaFields = config.source.schemaProperties.fields.map((f: any) => ({
          name: f.name, type: f.type
        }));
      }
    }

    // Destination
    const dest = config.destination;
    if (dest?.database?.usePostgres) {
      this.destType = 'postgres';
      this.pgDbName = dest.database.dbName || 'idata';
      this.pgSchema = dest.database.schema || 'public';
      this.pgTable = dest.database.table || '';
    } else if (dest?.database?.useMongoDB) {
      this.destType = 'mongodb';
      this.mongoDbName = dest.database.dbName || 'idata';
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
    this.searchService.getPostgresTables(this.pgDbName || 'idata', this.vectorSchema, true).subscribe({
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
    if (!this.sampleFile || !this.datasetName.trim()) {
      this.error = !this.datasetName.trim() ? 'Dataset name is required' : 'Please select a file';
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

      this.datasetService.generateSchema(
        this.sampleFile, this.datasetName,
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
    if (!this.schemaFile || !this.datasetName) return;
    this.generatingSchema = true;
    this.error = '';

    this.datasetService.generateSchema(
      this.schemaFile, this.datasetName,
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
    if (this.step === 1 && !this.datasetName.trim()) {
      this.error = 'Dataset name is required';
      return;
    }

    this.step++;

    // Skip schema step (3) for unstructured
    if (this.step === 3 && this.isUnstructured()) {
      this.step = 4;
    }

    // Load metadata when entering destination step (4)
    if (this.step === 4) {
      this.loadPgDatabases();
      this.loadMongoDatabases();
    }

    // Generate config JSON when entering review step (5)
    if (this.step === 5) {
      this.configJson = JSON.stringify(this.buildConfig(), null, 2);
    }
  }

  prevStep(): void {
    this.step--;
    // Skip schema step (3) for unstructured going back
    if (this.step === 3 && this.isUnstructured()) {
      this.step = 2;
    }
  }

  buildConfig(): any {
    const config: any = { name: this.datasetName, source: {}, destination: {} };

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

    this.datasetService.createDataset(config).subscribe({
      next: () => {
        this.router.navigate(['/datasets']);
      },
      error: (err: any) => {
        this.error = 'Failed to create dataset: ' + (err.error || err.message);
        this.creating = false;
      }
    });
  }
}
