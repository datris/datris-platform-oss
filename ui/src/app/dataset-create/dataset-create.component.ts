import { Component } from '@angular/core';
import { Router } from '@angular/router';
import { DatasetService } from '../dataset.service';

interface SchemaField {
  name: string;
  type: string;
}

@Component({
  selector: 'app-dataset-create',
  templateUrl: './dataset-create.component.html',
  styleUrls: ['./dataset-create.component.css']
})
export class DatasetCreateComponent {
  step = 1;
  error = '';
  creating = false;
  generatingSchema = false;

  // Step 1 — Basics
  datasetName = '';
  sourceType = 'csv';

  // Step 2 — Source config
  csvDelimiter = ',';
  csvEncoding = 'UTF-8';
  csvHeader = true;
  jsonEveryRow = true;
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

  fieldTypes = ['string', 'int', 'bigint', 'float', 'double', 'boolean', 'date', 'timestamp'];

  constructor(private datasetService: DatasetService, private router: Router) { }

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
  }

  onSourceTypeChange(): void {
    if (this.sourceType === 'json') {
      this.schemaFields = [{ name: '_json', type: 'string' }];
    } else if (this.sourceType === 'xml') {
      this.schemaFields = [{ name: '_xml', type: 'string' }];
    } else if (this.sourceType === 'csv') {
      this.schemaFields = [{ name: '', type: 'string' }];
    }
    // For unstructured, destination should default to vector
    if (this.sourceType === 'unstructured') {
      this.destType = 'pgvector';
      this.onDestTypeChange();
    }
  }

  addField(): void {
    this.schemaFields.push({ name: '', type: 'string' });
  }

  removeField(index: number): void {
    this.schemaFields.splice(index, 1);
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.schemaFile = input.files[0];
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
        // Extract schema fields from generated config
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
