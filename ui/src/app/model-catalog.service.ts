import { Injectable } from '@angular/core';

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

const CATALOG_URL = 'https://docs.datris.ai/models.json';
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

  async fetch(): Promise<ModelCatalog> {
    if (this.inFlight) return this.inFlight;
    this.inFlight = this.doFetch();
    return this.inFlight;
  }

  private async doFetch(): Promise<ModelCatalog> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);
    try {
      const res = await window.fetch(CATALOG_URL, {
        signal: controller.signal,
        cache: 'no-cache',
      });
      if (!res.ok) return this.cachedOrFallback();
      const data = await res.json();
      if (!this.looksValid(data)) return this.cachedOrFallback();
      try {
        localStorage.setItem(CACHE_KEY, JSON.stringify({ ts: Date.now(), catalog: data }));
      } catch { /* storage quota / disabled — ignore */ }
      return data as ModelCatalog;
    } catch {
      return this.cachedOrFallback();
    } finally {
      clearTimeout(timer);
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
