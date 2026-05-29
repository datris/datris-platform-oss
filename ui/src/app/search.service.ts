import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface QueryResponse {
  results: any[];
  count: number;
}

@Injectable({
  providedIn: 'root'
})
export class SearchService {
  constructor(private http: HttpClient) { }

  queryPostgres(sql: string, database: string, limit: number): Observable<QueryResponse> {
    return this.http.post<QueryResponse>('/api/v1/query/postgres', { sql, database, limit });
  }

  queryMongodb(collection: string, filter: any, projection: any, limit: number, database?: string): Observable<QueryResponse> {
    const body: any = { collection, filter, projection, limit };
    if (database) body.database = database;
    return this.http.post<QueryResponse>('/api/v1/query/mongodb', body);
  }

  queryObjectstore(pipeline: string, limit: number): Observable<QueryResponse> {
    return this.http.post<QueryResponse>('/api/v1/query/objectstore', { pipeline, limit });
  }

  getPipelines(): Observable<any[]> {
    return this.http.get<any[]>('/api/v1/pipelines');
  }

  searchQdrant(query: string, collection: string, embeddingSecretName: string, qdrantSecretName: string, topK: number): Observable<QueryResponse> {
    return this.http.post<QueryResponse>('/api/v1/search/qdrant', { query, collection, embeddingSecretName, qdrantSecretName, topK });
  }

  searchWeaviate(query: string, className: string, embeddingSecretName: string, weaviateSecretName: string, topK: number): Observable<QueryResponse> {
    return this.http.post<QueryResponse>('/api/v1/search/weaviate', { query, className, embeddingSecretName, weaviateSecretName, topK });
  }

  searchMilvus(query: string, collection: string, embeddingSecretName: string, milvusSecretName: string, topK: number): Observable<QueryResponse> {
    return this.http.post<QueryResponse>('/api/v1/search/milvus', { query, collection, embeddingSecretName, milvusSecretName, topK });
  }

  searchChroma(query: string, collection: string, embeddingSecretName: string, chromaSecretName: string, topK: number): Observable<QueryResponse> {
    return this.http.post<QueryResponse>('/api/v1/search/chroma', { query, collection, embeddingSecretName, chromaSecretName, topK });
  }

  searchPgvector(query: string, table: string, schema: string, embeddingSecretName: string, postgresSecretName: string, topK: number): Observable<QueryResponse> {
    return this.http.post<QueryResponse>('/api/v1/search/pgvector', { query, table, schema, embeddingSecretName, postgresSecretName, topK });
  }

  getPostgresDatabases(): Observable<string[]> {
    return this.http.get<string[]>('/api/v1/metadata/postgres/databases');
  }

  getPostgresSchemas(database: string): Observable<string[]> {
    return this.http.get<string[]>('/api/v1/metadata/postgres/schemas?database=' + encodeURIComponent(database));
  }

  getPostgresTables(database: string, schema: string, vectorOnly: boolean = false): Observable<string[]> {
    let url = '/api/v1/metadata/postgres/tables?database=' + encodeURIComponent(database) + '&schema=' + encodeURIComponent(schema);
    if (vectorOnly) url += '&vectorOnly=true';
    return this.http.get<string[]>(url);
  }

  getPostgresColumns(database: string, schema: string, table: string): Observable<any[]> {
    return this.http.get<any[]>('/api/v1/metadata/postgres/columns?database=' + encodeURIComponent(database) + '&schema=' + encodeURIComponent(schema) + '&table=' + encodeURIComponent(table));
  }

  getMongoDatabases(): Observable<string[]> {
    return this.http.get<string[]>('/api/v1/metadata/mongodb/databases');
  }

  getMongoCollections(database?: string): Observable<string[]> {
    const params = database ? '?database=' + encodeURIComponent(database) : '';
    return this.http.get<string[]>('/api/v1/metadata/mongodb/collections' + params);
  }

  aiAnswer(query: string, context: string): Observable<any> {
    return this.http.post<any>('/api/v1/ai/answer', { query, context });
  }
}
