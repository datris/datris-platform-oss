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
      { value: 'claude-opus-5', label: 'Claude Opus 5 (recommended)', recommended: true },
      { value: 'claude-opus-4-8', label: 'Claude Opus 4.8' },
      { value: 'claude-sonnet-5', label: 'Claude Sonnet 5' },
      { value: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6' },
      { value: 'claude-fable-5', label: 'Claude Fable 5' },
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
    openrouter: [
      { value: 'anthropic/claude-opus-5', label: 'Claude Opus 5 (recommended)', recommended: true },
      { value: 'anthropic/claude-sonnet-5', label: 'Claude Sonnet 5' },
      { value: 'openai/gpt-5.5', label: 'GPT-5.5' },
      { value: 'moonshotai/kimi-k3', label: 'Kimi K3 (open source)' },
      { value: 'deepseek/deepseek-v4-pro', label: 'DeepSeek V4 Pro (open source)' },
      { value: 'z-ai/glm-5.2', label: 'GLM-5.2 (open source)' },
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
    openrouter: [
      { value: 'anthropic/claude-opus-5', label: 'Claude Opus 5 (recommended)', recommended: true },
      { value: 'anthropic/claude-sonnet-5', label: 'Claude Sonnet 5' },
      { value: 'openai/gpt-5.5', label: 'GPT-5.5' },
      { value: 'moonshotai/kimi-k3', label: 'Kimi K3 (open source)' },
      { value: 'deepseek/deepseek-v4-pro', label: 'DeepSeek V4 Pro (open source)' },
      { value: 'z-ai/glm-5.2', label: 'GLM-5.2 (open source)' },
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
    openrouter: [
      { value: 'openai/text-embedding-3-small', label: 'text-embedding-3-small (recommended)', recommended: true },
      { value: 'openai/text-embedding-3-large', label: 'text-embedding-3-large' },
      { value: 'voyageai/voyage-multimodal-3.5', label: 'voyage-multimodal-3.5' },
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

  // ---- OpenRouter live catalog -------------------------------------------
  // The backend proxies OpenRouter's two disjoint catalogs (chat models vs
  // embedding models) and, for chat, applies the frontier capability filter
  // behind tier=recommended. On any failure we fall back to the baked-in
  // openrouter lists above so the dropdown never comes up empty.

  private openrouterInFlight = new Map<string, Promise<ModelOption[]>>();

  fetchOpenrouter(kind: 'chat' | 'embedding', tier: 'recommended' | 'all' = 'recommended'): Promise<ModelOption[]> {
    const key = `${kind}:${tier}`;
    const existing = this.openrouterInFlight.get(key);
    if (existing) return existing;
    const p = this.doFetchOpenrouter(kind, tier);
    this.openrouterInFlight.set(key, p);
    return p;
  }

  private async doFetchOpenrouter(kind: 'chat' | 'embedding', tier: 'recommended' | 'all'): Promise<ModelOption[]> {
    try {
      const data = await firstValueFrom(
        this.http.get<ModelOption[]>(`${CATALOG_URL}/openrouter?kind=${kind}&tier=${tier}`)
          .pipe(timeout(FETCH_TIMEOUT_MS))
      );
      if (Array.isArray(data) && data.length > 0) return data;
    } catch { /* fall through to baked-in list */ }
    this.openrouterInFlight.delete(`${kind}:${tier}`);
    return kind === 'embedding' ? FALLBACK.embedding['openrouter'] : FALLBACK.aiPrimary['openrouter'];
  }
}
