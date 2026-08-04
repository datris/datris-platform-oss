import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, timer } from 'rxjs';
import { map, switchMap } from 'rxjs/operators';

@Injectable({
  providedIn: 'root'
})
export class McpService {
  constructor(private http: HttpClient) { }

  getVersion(): Observable<any> {
    return this.http.get<any>('/api/v1/version');
  }

  getServiceHealth(): Observable<any> {
    return this.http.get<any>('/api/v1/health/services');
  }

  executeTool(toolName: string, params: Record<string, any>): Observable<any> {
    switch (toolName) {
      // Pipeline Management
      case 'list_pipelines':
        return this.http.get<any>('/api/v1/pipelines');
      case 'get_pipeline':
        return this.http.get<any>('/api/v1/pipeline?pipeline=' + encodeURIComponent(params['pipeline']));
      case 'create_pipeline': {
        // Replicate MCP server flow: generate schema then create pipeline
        const genFormData = new FormData();
        const byteStr = atob(params['content']);
        const bytes = new Uint8Array(byteStr.length);
        for (let i = 0; i < byteStr.length; i++) bytes[i] = byteStr.charCodeAt(i);
        genFormData.append('file', new Blob([bytes]), params['filename']);
        genFormData.append('pipeline', params['pipeline']);
        genFormData.append('allStrings', 'true');
        if (params['header'] !== undefined) genFormData.append('header', String(params['header']));
        if (params['delimiter']) genFormData.append('delimiter', params['delimiter']);
        return new Observable(observer => {
          this.http.post<any>('/api/v1/pipeline/generate', genFormData).subscribe({
            next: (config) => {
              const dest: any = {};
              const table = params['table'] || params['pipeline'];
              const db = params['database'] || 'datris';
              const destType = params['destination'] || 'postgres';
              if (destType === 'postgres') dest.database = { dbName: db, schema: 'public', table, usePostgres: true };
              else if (destType === 'mongodb') dest.database = { dbName: db, table, useMongoDB: true };
              else if (destType === 'snowflake') dest.database = { dbName: db, schema: params['schema'] || 'PUBLIC', table, useSnowflake: true, credentialsSecret: params['credentialsSecret'], warehouse: params['warehouse'], ...(params['role'] ? { role: params['role'] } : {}) };
              else if (destType === 'databricks') dest.database = { dbName: db, schema: params['schema'] || 'default', table, useDatabricks: true, credentialsSecret: params['credentialsSecret'], warehouse: params['warehouse'] };
              else dest.database = { dbName: db, schema: 'public', table, usePostgres: true };
              config.destination = dest;
              this.http.post<any>('/api/v1/pipeline', config).subscribe({
                next: () => { observer.next({ status: 'Pipeline created', pipeline: params['pipeline'], destination: destType, table }); observer.complete(); },
                error: (err) => observer.error(err)
              });
            },
            error: (err) => observer.error(err)
          });
        });
      }
      case 'delete_pipeline':
        return this.http.delete<any>('/api/v1/pipeline?pipeline=' + encodeURIComponent(params['pipeline']));
      case 'get_job_status': {
        let url = '/api/v1/pipeline/status?';
        const qp: string[] = [];
        if (params['pipeline_token']) qp.push('pipelinetoken=' + encodeURIComponent(params['pipeline_token']));
        if (params['pipeline_name']) qp.push('pipelinename=' + encodeURIComponent(params['pipeline_name']));
        if (params['page']) qp.push('page=' + params['page']);
        return this.http.get<any>(url + qp.join('&'));
      }
      case 'kill_job':
        return this.http.post<any>('/api/v1/job/kill', { pipelineToken: params['pipeline_token'] });
      case 'upload_data':
        return this.uploadBase64('/api/v1/pipeline/upload', params['content'], params['filename'], { pipeline: params['pipeline'] });
      case 'profile_data':
        return this.uploadBase64('/api/v1/pipeline/profile', params['content'], params['filename'], {
          ...(params['delimiter'] && { delimiter: params['delimiter'] }),
          ...(params['header'] !== undefined && { header: params['header'] }),
          ...(params['sample_size'] && { sampleSize: params['sample_size'] })
        });
      case 'upload_config':
        return this.uploadBase64('/api/v1/config/upload?type=' + encodeURIComponent(params['type']), params['content'], params['filename']);

      // Vector Search
      case 'search_qdrant':
        return this.http.post<any>('/api/v1/search/qdrant', {
          query: params['query'],
          ...(params['collection'] && { collection: params['collection'] }),
          ...(params['top_k'] && { topK: +params['top_k'] })
        });
      case 'search_weaviate':
        return this.http.post<any>('/api/v1/search/weaviate', {
          query: params['query'],
          ...(params['class_name'] && { className: params['class_name'] }),
          ...(params['top_k'] && { topK: +params['top_k'] })
        });
      case 'search_milvus':
        return this.http.post<any>('/api/v1/search/milvus', {
          query: params['query'],
          ...(params['collection'] && { collection: params['collection'] }),
          ...(params['top_k'] && { topK: +params['top_k'] })
        });
      case 'search_chroma':
        return this.http.post<any>('/api/v1/search/chroma', {
          query: params['query'],
          ...(params['collection'] && { collection: params['collection'] }),
          ...(params['top_k'] && { topK: +params['top_k'] })
        });
      case 'search_pgvector':
        return this.http.post<any>('/api/v1/search/pgvector', {
          query: params['query'],
          ...(params['table'] && { table: params['table'] }),
          ...(params['schema'] && { schema: params['schema'] }),
          ...(params['top_k'] && { topK: +params['top_k'] })
        });

      // Database Query
      case 'query_postgres':
        return this.http.post<any>('/api/v1/query/postgres', {
          sql: params['sql'],
          ...(params['limit'] && { limit: +params['limit'] })
        });
      case 'query_mongodb':
        return this.http.post<any>('/api/v1/query/mongodb', {
          collection: params['collection'],
          ...(params['filter'] && { filter: JSON.parse(params['filter']) }),
          ...(params['projection'] && { projection: JSON.parse(params['projection']) }),
          ...(params['limit'] && { limit: +params['limit'] })
        });
      case 'query_objectstore':
        return this.http.post<any>('/api/v1/query/objectstore', {
          pipeline: params['pipeline'],
          ...(params['limit'] && { limit: +params['limit'] })
        });
      case 'query_snowflake':
        return this.http.post<any>('/api/v1/query/snowflake', {
          pipeline: params['pipeline'],
          ...(params['sql'] && { sql: params['sql'] }),
          ...(params['limit'] && { limit: +params['limit'] })
        });
      case 'query_databricks':
        return this.http.post<any>('/api/v1/query/databricks', {
          pipeline: params['pipeline'],
          ...(params['sql'] && { sql: params['sql'] }),
          ...(params['limit'] && { limit: +params['limit'] })
        });
      case 'query_natural':
        return this.http.post<any>('/api/v1/query/natural', {
          question: params['question'],
          table: params['table'],
          ...(params['schema'] && { schema: params['schema'] }),
          ...(params['database'] && { database: params['database'] }),
          ...(params['limit'] && { limit: +params['limit'] })
        });

      // Metadata Discovery
      case 'list_postgres_databases':
        return this.http.get<any>('/api/v1/metadata/postgres/databases');
      case 'list_postgres_schemas': {
        const db = params['database'] ? '?database=' + encodeURIComponent(params['database']) : '';
        return this.http.get<any>('/api/v1/metadata/postgres/schemas' + db);
      }
      case 'list_postgres_tables': {
        const qps: string[] = [];
        if (params['database']) qps.push('database=' + encodeURIComponent(params['database']));
        if (params['schema']) qps.push('schema=' + encodeURIComponent(params['schema']));
        if (params['vector_only'] === true || params['vector_only'] === 'true') qps.push('vectorOnly=true');
        return this.http.get<any>('/api/v1/metadata/postgres/tables' + (qps.length ? '?' + qps.join('&') : ''));
      }
      case 'list_postgres_columns': {
        const qps2: string[] = ['table=' + encodeURIComponent(params['table'])];
        if (params['database']) qps2.push('database=' + encodeURIComponent(params['database']));
        if (params['schema']) qps2.push('schema=' + encodeURIComponent(params['schema']));
        return this.http.get<any>('/api/v1/metadata/postgres/columns?' + qps2.join('&'));
      }
      case 'list_mongodb_databases':
        return this.http.get<any>('/api/v1/metadata/mongodb/databases');
      case 'list_mongodb_collections': {
        const dbp = params['database'] ? '?database=' + encodeURIComponent(params['database']) : '';
        return this.http.get<any>('/api/v1/metadata/mongodb/collections' + dbp);
      }

      // Vector Store Metadata
      case 'list_qdrant_collections':
        return this.http.get<any>('/api/v1/metadata/qdrant/collections');
      case 'list_weaviate_classes':
        return this.http.get<any>('/api/v1/metadata/weaviate/classes');
      case 'list_milvus_collections':
        return this.http.get<any>('/api/v1/metadata/milvus/collections');
      case 'list_chroma_collections':
        return this.http.get<any>('/api/v1/metadata/chroma/collections');
      case 'list_pgvector_collections':
        return this.http.get<any>('/api/v1/metadata/postgres/tables?vectorOnly=true');

      // AI
      case 'ai_answer':
        return this.http.post<any>('/api/v1/ai/answer', {
          query: params['query'],
          context: params['context']
        });

      // System
      case 'get_version':
        return this.http.get<any>('/api/v1/version');
      case 'check_service_health':
        return this.http.get<any>('/api/v1/health/services');

      // Secrets
      case 'update_secret':
        return this.http.put<any>('/api/v1/secrets/' + encodeURIComponent(params['name']), JSON.parse(params['fields']));

      // Agent utilities
      case 'wait_seconds': {
        const secs = Math.min(Math.max(Number(params['seconds']) || 1, 1), 120);
        return timer(secs * 1000).pipe(map(() => ({ waited_seconds: secs })));
      }

      // Taps
      case 'list_taps':
        return this.http.get<any>('/api/v1/taps');
      case 'get_tap':
        return this.http.get<any>('/api/v1/tap?name=' + encodeURIComponent(params['name']));
      case 'run_tap': {
        const body: any = { name: params['name'], mode: 'run' };
        if (params['params']) {
          body.params = typeof params['params'] === 'string' ? JSON.parse(params['params']) : params['params'];
        }
        return this.http.post<any>('/api/v1/tap/run', body);
      }
      case 'test_tap':
        return this.http.post<any>('/api/v1/tap/run', { name: params['name'], mode: 'test' });
      case 'update_tap':
        // Read-modify-write, mirroring the MCP server flow (there is no PATCH).
        // GET decorates the config with script/state fields the POST path doesn't
        // take — strip them before saving.
        return this.http.get<any>('/api/v1/tap?name=' + encodeURIComponent(params['name'])).pipe(
          switchMap((tap: any) => {
            const updated: any = { ...tap };
            delete updated.script;
            delete updated.scriptMissing;
            delete updated.state;
            delete updated.stateUpdatedAt;
            if (params['enabled'] !== undefined) updated.enabled = String(params['enabled']) === 'true';
            if (params['cron_expression'] !== undefined) updated.cronExpression = params['cron_expression'] || null;
            if (params['target_pipeline'] !== undefined) updated.targetPipeline = params['target_pipeline'] || null;
            if (params['description'] !== undefined) updated.description = params['description'];
            return this.http.post<any>('/api/v1/tap', updated);
          })
        );
      case 'get_tap_logs':
        return this.http.get<any>('/api/v1/tap/logs?name=' + encodeURIComponent(params['name']));
      case 'get_tap_ledger': {
        const ledgerName = encodeURIComponent(params['name']);
        if (params['clear_uri']) {
          return this.http.delete<any>('/api/v1/tap/ledger?name=' + ledgerName + '&uri=' + encodeURIComponent(params['clear_uri']));
        }
        if (String(params['clear_all']) === 'true') {
          return this.http.delete<any>('/api/v1/tap/ledger?name=' + ledgerName);
        }
        return this.http.get<any>('/api/v1/tap/ledger?name=' + ledgerName);
      }
      case 'get_tap_state':
        return this.http.get<any>('/api/v1/tap/state?name=' + encodeURIComponent(params['name']));
      case 'set_tap_state': {
        if (String(params['reset']) === 'true') {
          return this.http.delete<any>('/api/v1/tap/state?name=' + encodeURIComponent(params['name']));
        }
        const state = typeof params['state'] === 'string' ? JSON.parse(params['state']) : params['state'];
        return this.http.post<any>('/api/v1/tap/state', { name: params['name'], state });
      }
      case 'delete_tap':
        return this.http.delete<any>('/api/v1/tap?name=' + encodeURIComponent(params['name']));
      case 'get_pipeline_status': {
        if (params['publisher_token']) {
          return this.http.get<any>('/api/v1/pipeline/status?publishertoken=' + encodeURIComponent(params['publisher_token']));
        }
        return this.http.get<any>('/api/v1/pipeline/status?pipelinetoken=' + encodeURIComponent(params['pipeline_token']));
      }

      // Definition versions
      case 'list_tap_versions':
        return this.http.get<any>('/api/v1/tap/versions?name=' + encodeURIComponent(params['name']));
      case 'get_tap_version':
        return this.http.get<any>('/api/v1/tap/version?name=' + encodeURIComponent(params['name']) + '&version=' + params['version']);
      case 'diff_tap_versions':
        return this.http.get<any>('/api/v1/tap/version/diff?name=' + encodeURIComponent(params['name']) + '&version=' + params['version'] + '&against=' + params['against']);
      case 'restore_tap_version':
        return this.http.post<any>('/api/v1/tap/version/restore?name=' + encodeURIComponent(params['name']) + '&version=' + params['version'], {});
      case 'list_pipeline_versions':
        return this.http.get<any>('/api/v1/pipeline/versions?name=' + encodeURIComponent(params['name']));
      case 'get_pipeline_version':
        return this.http.get<any>('/api/v1/pipeline/version?name=' + encodeURIComponent(params['name']) + '&version=' + params['version']);
      case 'diff_pipeline_versions':
        return this.http.get<any>('/api/v1/pipeline/version/diff?name=' + encodeURIComponent(params['name']) + '&version=' + params['version'] + '&against=' + params['against']);
      case 'restore_pipeline_version':
        return this.http.post<any>('/api/v1/pipeline/version/restore?name=' + encodeURIComponent(params['name']) + '&version=' + params['version'], {});

      // Catalog assignment (read-modify-write on the named tap or pipeline)
      case 'set_catalog': {
        const catalogValue = params['catalog'] || null;
        if (params['tap']) {
          return this.http.get<any>('/api/v1/tap?name=' + encodeURIComponent(params['tap'])).pipe(
            switchMap((tap: any) => {
              const updated: any = { ...tap, catalog: catalogValue };
              delete updated.script;
              delete updated.scriptMissing;
              delete updated.state;
              delete updated.stateUpdatedAt;
              return this.http.post<any>('/api/v1/tap', updated);
            })
          );
        }
        return this.http.get<any>('/api/v1/pipeline?pipeline=' + encodeURIComponent(params['pipeline'])).pipe(
          switchMap((cfg: any) => this.http.post<any>('/api/v1/pipeline', { ...cfg, catalog: catalogValue }))
        );
      }

      // Tap & platform secrets (field NAMES only are ever returned — never values)
      case 'list_tap_secrets':
        return this.http.get<any>('/api/v1/secrets?type=tap');
      case 'get_tap_secret_fields':
        return this.http.get<any>('/api/v1/secrets/' + encodeURIComponent(params['name'])).pipe(
          map((data: any) => ({ secret: params['name'], fields: Object.keys(data || {}).filter(k => k !== '_type') }))
        );
      case 'create_tap_secret': {
        const fields = typeof params['fields'] === 'string' ? JSON.parse(params['fields']) : params['fields'];
        return this.http.put<any>('/api/v1/secrets/' + encodeURIComponent(params['name']), { ...fields, _type: 'tap' });
      }
      case 'delete_tap_secret':
        return this.http.delete<any>('/api/v1/secrets/' + encodeURIComponent(params['name']));
      case 'list_platform_secrets':
        return this.http.get<any>('/api/v1/secrets?type=platform');
      case 'get_platform_secret_fields':
        return this.http.get<any>('/api/v1/secrets/' + encodeURIComponent(params['name'])).pipe(
          map((data: any) => ({ secret: params['name'], fields: Object.keys(data || {}).filter(k => k !== '_type') }))
        );

      default:
        throw new Error('Unknown tool: ' + toolName);
    }
  }

  private uploadBase64(url: string, contentB64: string, filename: string, extraData?: Record<string, any>): Observable<any> {
    const byteString = atob(contentB64);
    const bytes = new Uint8Array(byteString.length);
    for (let i = 0; i < byteString.length; i++) {
      bytes[i] = byteString.charCodeAt(i);
    }
    const blob = new Blob([bytes]);
    const formData = new FormData();
    formData.append('file', blob, filename);
    if (extraData) {
      Object.entries(extraData).forEach(([key, value]) => {
        if (value !== undefined && value !== null) {
          formData.append(key, String(value));
        }
      });
    }
    return this.http.post<any>(url, formData);
  }
}
