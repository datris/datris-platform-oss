import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface TapPromptFragment {
  key: string;
  aliases: string[];
  content: string;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

@Injectable({ providedIn: 'root' })
export class TapPromptService {
  constructor(private http: HttpClient) {}

  list(): Observable<TapPromptFragment[]> {
    return this.http.get<TapPromptFragment[]>('/api/v1/tap-prompts');
  }

  save(fragment: TapPromptFragment) {
    return this.http.post('/api/v1/tap-prompts', fragment, { responseType: 'text' });
  }

  delete(key: string) {
    return this.http.delete(`/api/v1/tap-prompts/${encodeURIComponent(key)}`, { responseType: 'text' });
  }

  suggest(key: string, aliases: string[], content: string): Observable<{ content: string }> {
    return this.http.post<{ content: string }>('/api/v1/tap-prompts/suggest', { key, aliases, content });
  }
}
