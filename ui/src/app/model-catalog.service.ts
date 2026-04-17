import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom, timeout } from 'rxjs';

export interface ModelOption {
  value: string;
  label: string;
  recommended?: boolean;
}

export interface ModelCatalog {
  aiPrimary: Record<string, ModelOption[]>;
  codegen: Record<string, ModelOption[]>;
  embedding: Record<string, ModelOption[]>;
}

// Same-origin backend proxy that server-side fetches docs.datris.ai/models.json.
// Going through the backend avoids the CORS gap on Mintlify's static assets and
// lets the auth interceptor attach the user's API key.
const CATALOG_URL = '/api/v1/ai/model-catalog';
const CACHE_KEY = 'datris.modelCatalog';
const FETCH_TIMEOUT_MS = 5000;

// Baked-in fallback — used when the remote fetch fails (airgapped installs, DNS flake).
// Keep shape in sync with docs/models.json.
const FALLBACK: ModelCatalog = {
  aiPrimary: {
    anthropic: [
      { value: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6 (recommended)', recommended: true },
      { value: 'claude-opus-4-6', label: 'Claude Opus 4.6' },
      { value: 'claude-haiku-4-5', label: 'Claude Haiku 4.5' },
    ],
    openai: [
      { value: 'gpt-5.4', label: 'GPT-5.4 (recommended)', recommended: true },
      { value: 'gpt-5.4-pro', label: 'GPT-5.4 Pro' },
      { value: 'gpt-5.4-mini', label: 'GPT-5.4 mini' },
      { value: 'gpt-5.4-nano', label: 'GPT-5.4 nano' },
    ],
  },
  codegen: {
    anthropic: [
      { value: 'claude-opus-4-6', label: 'Claude Opus 4.6 (recommended)', recommended: true },
      { value: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6' },
      { value: 'claude-haiku-4-5', label: 'Claude Haiku 4.5' },
    ],
    openai: [
      { value: 'gpt-5.3-codex', label: 'GPT-5.3-Codex (recommended)', recommended: true },
      { value: 'gpt-5.4', label: 'GPT-5.4' },
      { value: 'gpt-5.4-pro', label: 'GPT-5.4 Pro' },
      { value: 'gpt-5.4-mini', label: 'GPT-5.4 mini' },
      { value: 'gpt-5.4-nano', label: 'GPT-5.4 nano' },
    ],
  },
  embedding: {
    openai: [
      { value: 'text-embedding-3-small', label: 'text-embedding-3-small (recommended)', recommended: true },
      { value: 'text-embedding-3-large', label: 'text-embedding-3-large' },
    ],
    ollama: [
      { value: 'bge-m3', label: 'bge-m3 (1024-dim, bundled)' },
    ],
  },
};

@Injectable({ providedIn: 'root' })
export class ModelCatalogService {
  private inFlight: Promise<ModelCatalog> | null = null;

  constructor(private http: HttpClient) {}

  async fetch(): Promise<ModelCatalog> {
    if (this.inFlight) return this.inFlight;
    this.inFlight = this.doFetch();
    return this.inFlight;
  }

  private async doFetch(): Promise<ModelCatalog> {
    try {
      const data = await firstValueFrom(
        this.http.get<ModelCatalog>(CATALOG_URL).pipe(timeout(FETCH_TIMEOUT_MS))
      );
      if (!this.looksValid(data)) return this.cachedOrFallback();
      try {
        localStorage.setItem(CACHE_KEY, JSON.stringify({ ts: Date.now(), catalog: data }));
      } catch { /* storage quota / disabled — ignore */ }
      return data;
    } catch {
      return this.cachedOrFallback();
    }
  }

  private cachedOrFallback(): ModelCatalog {
    try {
      const raw = localStorage.getItem(CACHE_KEY);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (parsed && this.looksValid(parsed.catalog)) return parsed.catalog;
      }
    } catch { /* ignore */ }
    return FALLBACK;
  }

  private looksValid(c: any): boolean {
    return !!(c && c.aiPrimary && c.codegen && c.embedding);
  }
}
