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
      { value: 'claude-fable-5', label: 'Claude Fable 5 (recommended)', recommended: true },
      { value: 'claude-opus-5', label: 'Claude Opus 5' },
      { value: 'claude-opus-4-8', label: 'Claude Opus 4.8' },
      { value: 'claude-sonnet-5', label: 'Claude Sonnet 5' },
      { value: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6' },
      { value: 'claude-opus-4-6', label: 'Claude Opus 4.6' },
      { value: 'claude-haiku-4-5', label: 'Claude Haiku 4.5' },
    ],
    openai: [
      { value: 'gpt-5.5', label: 'GPT-5.5 (recommended)', recommended: true },
      { value: 'gpt-5.5-pro', label: 'GPT-5.5 Pro' },
      { value: 'gpt-5.4', label: 'GPT-5.4' },
      { value: 'gpt-5.4-pro', label: 'GPT-5.4 Pro' },
      { value: 'gpt-5.4-mini', label: 'GPT-5.4 mini' },
      { value: 'gpt-5.4-nano', label: 'GPT-5.4 nano' },
    ],
    // Claude on Amazon Bedrock — `anthropic.`-prefixed ids. Fallback only:
    // when AWS credentials are saved, the UI swaps this for live discovery
    // (only models the account/region can actually invoke).
    bedrock: [
      { value: 'anthropic.claude-fable-5', label: 'Claude Fable 5 (recommended)', recommended: true },
      { value: 'anthropic.claude-opus-5', label: 'Claude Opus 5' },
      { value: 'anthropic.claude-sonnet-5', label: 'Claude Sonnet 5' },
      { value: 'anthropic.claude-opus-4-8', label: 'Claude Opus 4.8' },
      { value: 'anthropic.claude-opus-4-7', label: 'Claude Opus 4.7' },
      { value: 'anthropic.claude-haiku-4-5', label: 'Claude Haiku 4.5' },
    ],
    grok: [
      { value: 'grok-4.6', label: 'Grok 4.6 (recommended)', recommended: true },
      { value: 'grok-4.5', label: 'Grok 4.5' },
      { value: 'grok-4.1-fast', label: 'Grok 4.1 Fast' },
    ],
  },
  codegen: {
    anthropic: [
      { value: 'claude-opus-5', label: 'Claude Opus 5 (recommended)', recommended: true },
      { value: 'claude-opus-4-8', label: 'Claude Opus 4.8' },
      { value: 'claude-sonnet-5', label: 'Claude Sonnet 5' },
      { value: 'claude-fable-5', label: 'Claude Fable 5' },
      { value: 'claude-opus-4-7', label: 'Claude Opus 4.7' },
      { value: 'claude-opus-4-6', label: 'Claude Opus 4.6' },
      { value: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6' },
      { value: 'claude-haiku-4-5', label: 'Claude Haiku 4.5' },
    ],
    openai: [
      { value: 'gpt-5.5', label: 'GPT-5.5 (recommended)', recommended: true },
      { value: 'gpt-5.5-pro', label: 'GPT-5.5 Pro' },
      { value: 'gpt-5.3-codex', label: 'GPT-5.3-Codex' },
      { value: 'gpt-5.4', label: 'GPT-5.4' },
      { value: 'gpt-5.4-pro', label: 'GPT-5.4 Pro' },
      { value: 'gpt-5.4-mini', label: 'GPT-5.4 mini' },
      { value: 'gpt-5.4-nano', label: 'GPT-5.4 nano' },
    ],
    bedrock: [
      { value: 'anthropic.claude-opus-5', label: 'Claude Opus 5 (recommended)', recommended: true },
      { value: 'anthropic.claude-fable-5', label: 'Claude Fable 5' },
      { value: 'anthropic.claude-opus-4-8', label: 'Claude Opus 4.8' },
      { value: 'anthropic.claude-sonnet-5', label: 'Claude Sonnet 5' },
      { value: 'anthropic.claude-opus-4-7', label: 'Claude Opus 4.7' },
      { value: 'anthropic.claude-haiku-4-5', label: 'Claude Haiku 4.5' },
    ],
    grok: [
      { value: 'grok-4.6', label: 'Grok 4.6 (recommended)', recommended: true },
      { value: 'grok-code-fast-1', label: 'Grok Code Fast 1' },
      { value: 'grok-4.1-fast', label: 'Grok 4.1 Fast' },
    ],
  },
  embedding: {
    openai: [
      { value: 'text-embedding-3-small', label: 'text-embedding-3-small (recommended)', recommended: true },
      { value: 'text-embedding-3-large', label: 'text-embedding-3-large' },
    ],
    tei: [
      { value: 'BAAI/bge-m3', label: 'bge-m3 (1024-dim, bundled)' },
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
      return this.withProviderFallbacks(data);
    } catch {
      return this.cachedOrFallback();
    }
  }

  private cachedOrFallback(): ModelCatalog {
    try {
      const raw = localStorage.getItem(CACHE_KEY);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (parsed && this.looksValid(parsed.catalog)) return this.withProviderFallbacks(parsed.catalog);
      }
    } catch { /* ignore */ }
    return FALLBACK;
  }

  /** Backfill provider lists the remote catalog doesn't carry yet from the
   *  baked-in defaults — a valid remote catalog that predates a new provider
   *  (e.g. bedrock) must not leave that provider's dropdown empty. */
  private withProviderFallbacks(c: ModelCatalog): ModelCatalog {
    for (const section of ['aiPrimary', 'codegen', 'embedding'] as const) {
      const lists = c[section] = c[section] || {};
      for (const [provider, models] of Object.entries(FALLBACK[section])) {
        if (!lists[provider] || lists[provider].length === 0) lists[provider] = models;
      }
    }
    return c;
  }

  private looksValid(c: any): boolean {
    return !!(c && c.aiPrimary && c.codegen && c.embedding);
  }
}
