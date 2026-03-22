import { Component, OnInit } from '@angular/core';
import { SearchService, QueryResponse } from '../search.service';

@Component({
  selector: 'app-search',
  templateUrl: './search.component.html',
  styleUrls: ['./search.component.css']
})
export class SearchComponent implements OnInit {
  queryType = 'postgres';
  loading = false;
  error = '';
  results: any[] = [];
  columns: string[] = [];
  resultCount = 0;

  // AI answer
  aiAnswer = '';
  aiLoading = false;
  aiError = '';

  // PostgreSQL fields
  pgSql = '';
  pgDatabase = 'idata';
  pgLimit = 100;
  pgSchemas: string[] = [];
  pgTables: string[] = [];
  pgSelectedSchema = '';
  pgSelectedTable = '';

  // MongoDB fields
  mongoDatabase = '';
  mongoDatabases: string[] = [];
  mongoCollection = '';
  mongoCollections: string[] = [];
  mongoFilter = '{}';
  mongoProjection = '';
  mongoLimit = 20;

  // Vector search fields
  searchQuery = '';
  searchCollection = 'financial_documents';
  searchClassName = 'FinancialDocuments';
  searchTable = 'financial_documents';
  searchSchema = 'public';
  embeddingSecretName = 'oss/embedding';
  vectorSecretName = 'oss/pgvector';
  topK = 5;

  constructor(private searchService: SearchService) { }

  ngOnInit(): void {
    this.loadPgSchemas();
    this.loadMongoDatabases();
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
  }

  onPgSchemaChange(): void {
    this.pgSelectedTable = '';
    this.loadPgTables();
  }

  onSearchSchemaChange(): void {
    this.searchTable = '';
    this.searchService.getPostgresTables(this.pgDatabase, this.searchSchema, true).subscribe({
      next: (tables) => { this.pgTables = tables; },
      error: () => { this.pgTables = []; }
    });
  }

  onPgTableSelect(): void {
    if (this.pgSelectedSchema && this.pgSelectedTable) {
      this.searchService.getPostgresColumns(this.pgDatabase, this.pgSelectedSchema, this.pgSelectedTable).subscribe({
        next: (columns) => {
          const colNames = columns.map((c: any) => c.name).join(', ');
          this.pgSql = 'SELECT ' + colNames + ' FROM ' + this.pgSelectedSchema + '.' + this.pgSelectedTable;
        },
        error: () => {
          this.pgSql = 'SELECT * FROM ' + this.pgSelectedSchema + '.' + this.pgSelectedTable;
        }
      });
    }
  }

  loadMongoDatabases(): void {
    this.searchService.getMongoDatabases().subscribe({
      next: (databases) => {
        this.mongoDatabases = databases;
        if (databases.length > 0 && !this.mongoDatabase) {
          this.mongoDatabase = databases[0];
          this.loadMongoCollections();
        }
      },
      error: () => { this.mongoDatabases = []; }
    });
  }

  onMongoDatabaseChange(): void {
    this.mongoCollection = '';
    this.loadMongoCollections();
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
    this.vectorSecretName = this.getDefaultVectorSecret();
  }

  isVectorSearch(): boolean {
    return ['qdrant', 'weaviate', 'milvus', 'chroma', 'pgvector'].includes(this.queryType);
  }

  retrieveAllMongo(): void {
    this.mongoFilter = '{}';
    this.mongoProjection = '';
    this.execute();
  }

  execute(): void {
    this.loading = true;
    this.error = '';
    this.results = [];
    this.columns = [];

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
      default:
        this.loading = false;
        return;
    }

    request.subscribe({
      next: (response: QueryResponse) => {
        this.results = response.results || [];
        this.resultCount = response.count || 0;
        if (this.results.length > 0) {
          this.columns = Object.keys(this.results[0]);
        }
        this.loading = false;

        // Auto-submit to LLM for vector searches
        if (this.isVectorSearch() && this.results.length > 0) {
          this.askAI();
        }
      },
      error: (err: any) => {
        this.error = err.error || err.message || 'An error occurred';
        this.loading = false;
      }
    });
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
        this.aiError = err.error || err.message || 'AI answer failed';
        this.aiLoading = false;
      }
    });
  }
}
