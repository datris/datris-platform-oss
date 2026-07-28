import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface CodeRepoConfig {
  name?: string;
  provider: string;
  repo: string;
  apiBaseUrl: string;
  branch: string;
  pathPrefix: string;
  authSecretName: string;
  commitAuthor: string;
  commitMessageTemplate: string;
  enabled: boolean;
}

export interface CodeRepoTestResult {
  ok: boolean;
  canPush?: boolean;
  branchExists?: boolean;
  defaultBranch?: string;
  branches?: string[];
  error?: string;
}

@Injectable({
  providedIn: 'root'
})
export class CodeRepoService {
  constructor(private http: HttpClient) { }

  get(): Observable<CodeRepoConfig> {
    return this.http.get<CodeRepoConfig>('/api/v1/code-repo');
  }

  put(config: CodeRepoConfig): Observable<CodeRepoConfig> {
    return this.http.put<CodeRepoConfig>('/api/v1/code-repo', config);
  }

  test(config: CodeRepoConfig): Observable<CodeRepoTestResult> {
    return this.http.post<CodeRepoTestResult>('/api/v1/code-repo/test', config);
  }

  pullScript(tapName: string, apply = false): Observable<any> {
    return this.http.post<any>('/api/v1/tap/script/pull', { tapName, apply: String(apply) });
  }

  migrateStorage(tapName: string, target: 'github' | 'minio'): Observable<any> {
    return this.http.post<any>('/api/v1/tap/migrate-storage', { tapName, target });
  }
}
