import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'app-configuration',
  templateUrl: './configuration.component.html',
  styleUrls: ['./configuration.component.css']
})
export class ConfigurationComponent implements OnInit {
  // Shared API keys (entered once in the right-hand panel)
  anthropicApiKey = '';
  openaiApiKey = '';

  // AI Primary
  aiPrimaryProvider = 'anthropic';
  aiPrimaryModel = '';
  aiPrimaryEndpoint = '';
  showAdvancedAiPrimary = false;
  usingDefaultAiPrimary = true;

  // CodeGen
  codegenProvider = 'anthropic';
  codegenModel = '';
  codegenEndpoint = '';
  showAdvancedCodegen = false;
  usingDefaultCodegen = true;

  // Embedding
  embeddingProvider = 'openai';
  embeddingModel = '';
  embeddingEndpoint = '';
  showAdvancedEmbedding = false;
  usingDefaultEmbedding = true;

  // Models loaded from existing secrets that aren't in the predefined dropdown lists.
  // Tracked per section so the dropdown can render whatever the secret actually contains.
  extraAiPrimaryModel: { value: string; label: string } | null = null;
  extraCodegenModel: { value: string; label: string } | null = null;
  extraEmbeddingModel: { value: string; label: string } | null = null;

  models: Record<string, { value: string; label: string }[]> = {
    anthropic: [
      { value: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6 (recommended)' },
      { value: 'claude-opus-4-6', label: 'Claude Opus 4.6' },
      { value: 'claude-haiku-4-5', label: 'Claude Haiku 4.5' },
    ],
    openai: [
      { value: 'gpt-5.4', label: 'GPT-5.4 (recommended)' },
      { value: 'gpt-5.4-pro', label: 'GPT-5.4 Pro' },
      { value: 'gpt-5.4-mini', label: 'GPT-5.4 mini' },
      { value: 'gpt-5.4-nano', label: 'GPT-5.4 nano' },
    ],
  };

  // CodeGen tasks benefit from a stronger model — recommendations differ from the main list.
  codegenModelsList: Record<string, { value: string; label: string }[]> = {
    anthropic: [
      { value: 'claude-opus-4-6', label: 'Claude Opus 4.6 (recommended)' },
      { value: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6' },
      { value: 'claude-haiku-4-5', label: 'Claude Haiku 4.5' },
    ],
    openai: [
      { value: 'gpt-5.3-codex', label: 'GPT-5.3-Codex (recommended)' },
      { value: 'gpt-5.4', label: 'GPT-5.4' },
      { value: 'gpt-5.4-pro', label: 'GPT-5.4 Pro' },
      { value: 'gpt-5.4-mini', label: 'GPT-5.4 mini' },
      { value: 'gpt-5.4-nano', label: 'GPT-5.4 nano' },
    ],
  };

  embeddingModels: Record<string, { value: string; label: string }[]> = {
    openai: [
      { value: 'text-embedding-3-small', label: 'text-embedding-3-small (recommended)' },
      { value: 'text-embedding-3-large', label: 'text-embedding-3-large' },
    ],
    ollama: [
      { value: 'bge-m3', label: 'bge-m3 (1024-dim, bundled)' },
    ],
  };

  environment = '';
  version = '';
  isTrial = false;
  multiTenant = false;
  saving = false;
  resetting = false;
  success = '';
  error = '';
  loading = true;

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    this.http.get<any>('/api/v1/version').subscribe({
      next: (data) => {
        this.environment = data.environment || '';
        this.version = data.version || '';
        this.isTrial = this.environment.startsWith('trial-');
        this.multiTenant = String(data.multiTenant) === 'true';
        this.loadConfig();
      },
      error: () => { this.loading = false; }
    });
  }

  /** Merge predefined model options with any "extra" model loaded from a secret
   *  that wasn't in the predefined list (e.g. a custom Anthropic model name). */
  private withExtra(
    base: { value: string; label: string }[],
    extra: { value: string; label: string } | null
  ): { value: string; label: string }[] {
    if (!extra) return base;
    if (base.find(m => m.value === extra.value)) return base;
    return [...base, extra];
  }

  get aiPrimaryModelOptions(): { value: string; label: string }[] {
    return this.withExtra(this.models[this.aiPrimaryProvider] || [], this.extraAiPrimaryModel);
  }

  get codegenModelOptions(): { value: string; label: string }[] {
    return this.withExtra(this.codegenModelsList[this.codegenProvider] || [], this.extraCodegenModel);
  }

  get embeddingModelOptions(): { value: string; label: string }[] {
    return this.withExtra(this.embeddingModels[this.embeddingProvider] || [], this.extraEmbeddingModel);
  }

  onAiPrimaryProviderChange(): void {
    const options = this.aiPrimaryModelOptions;
    if (options.length > 0 && !options.find(m => m.value === this.aiPrimaryModel)) {
      this.aiPrimaryModel = options[0].value;
    }
    // Refresh endpoint to the new provider's standard URL unless the user has customized it.
    if (!this.showAdvancedAiPrimary) {
      this.aiPrimaryEndpoint = this.endpointFor(this.aiPrimaryProvider, 'chat');
    }
  }

  onCodegenProviderChange(): void {
    const options = this.codegenModelOptions;
    if (options.length > 0 && !options.find(m => m.value === this.codegenModel)) {
      this.codegenModel = options[0].value;
    }
    if (!this.showAdvancedCodegen) {
      this.codegenEndpoint = this.endpointFor(this.codegenProvider, 'chat');
    }
  }

  onEmbeddingProviderChange(): void {
    const options = this.embeddingModelOptions;
    if (options.length > 0 && !options.find(m => m.value === this.embeddingModel)) {
      this.embeddingModel = options[0].value;
    }
    if (!this.showAdvancedEmbedding) {
      this.embeddingEndpoint = this.endpointFor(this.embeddingProvider, 'embedding');
    }
  }

  loadConfig(): void {
    // Three independent direct GETs by known names. 404 = no per-tenant override = use defaults.
    this.resetSectionState();

    let pending = 3;
    const done = () => { pending--; if (pending === 0) this.loading = false; };

    const recordKeyForProvider = (provider: string, apiKey: string) => {
      // Backend masks non-empty sensitive fields as ••••••••; empty stays empty.
      if (!apiKey) return;
      if (provider === 'anthropic') this.anthropicApiKey = apiKey;
      if (provider === 'openai') this.openaiApiKey = apiKey;
    };

    this.http.get<any>('/api/v1/secrets/ai-primary').subscribe({
      next: (data) => {
        const fields = data && data.fields;
        if (fields && fields.apiKey) {
          this.aiPrimaryProvider = (fields.provider || 'anthropic').toLowerCase();
          this.aiPrimaryModel = fields.model || '';
          this.aiPrimaryEndpoint = fields.endpoint || this.endpointFor(this.aiPrimaryProvider, 'chat');
          this.maybeAddExtraModel('aiPrimary', this.aiPrimaryProvider, this.aiPrimaryModel);
          this.showAdvancedAiPrimary = this.endpointIsCustom(this.aiPrimaryEndpoint, this.aiPrimaryProvider, 'chat');
          recordKeyForProvider(this.aiPrimaryProvider, fields.apiKey);
          this.usingDefaultAiPrimary = false;
        } else {
          this.aiPrimaryEndpoint = this.endpointFor(this.aiPrimaryProvider, 'chat');
        }
        done();
      },
      error: () => { this.aiPrimaryEndpoint = this.endpointFor(this.aiPrimaryProvider, 'chat'); done(); }
    });

    this.http.get<any>('/api/v1/secrets/codegen').subscribe({
      next: (data) => {
        const fields = data && data.fields;
        if (fields && fields.apiKey) {
          this.codegenProvider = (fields.provider || 'anthropic').toLowerCase();
          this.codegenModel = fields.model || '';
          this.codegenEndpoint = fields.endpoint || this.endpointFor(this.codegenProvider, 'chat');
          this.maybeAddExtraModel('codegen', this.codegenProvider, this.codegenModel);
          this.showAdvancedCodegen = this.endpointIsCustom(this.codegenEndpoint, this.codegenProvider, 'chat');
          recordKeyForProvider(this.codegenProvider, fields.apiKey);
          this.usingDefaultCodegen = false;
        } else {
          this.codegenEndpoint = this.endpointFor(this.codegenProvider, 'chat');
        }
        done();
      },
      error: () => { this.codegenEndpoint = this.endpointFor(this.codegenProvider, 'chat'); done(); }
    });

    this.http.get<any>('/api/v1/secrets/embedding').subscribe({
      next: (data) => {
        const fields = data && data.fields;
        if (fields && (fields.apiKey || (fields.provider || '').toLowerCase() === 'ollama')) {
          this.embeddingProvider = (fields.provider || 'openai').toLowerCase();
          this.embeddingModel = fields.model || '';
          this.embeddingEndpoint = fields.endpoint || this.endpointFor(this.embeddingProvider, 'embedding');
          this.maybeAddExtraModel('embedding', this.embeddingProvider, this.embeddingModel);
          this.showAdvancedEmbedding = this.endpointIsCustom(this.embeddingEndpoint, this.embeddingProvider, 'embedding');
          recordKeyForProvider(this.embeddingProvider, fields.apiKey || '');
          this.usingDefaultEmbedding = false;
        } else {
          this.embeddingEndpoint = this.endpointFor(this.embeddingProvider, 'embedding');
        }
        done();
      },
      error: () => { this.embeddingEndpoint = this.endpointFor(this.embeddingProvider, 'embedding'); done(); }
    });
  }

  /** Returns true when the loaded endpoint isn't the standard one for that provider/kind. */
  private endpointIsCustom(endpoint: string, provider: string, kind: 'chat' | 'embedding'): boolean {
    if (!endpoint) return false;
    const standard = this.endpointFor(provider, kind);
    return standard !== '' && endpoint.trim() !== standard;
  }

  /** Add a loaded model to the section's "extra" slot if it isn't in the predefined list. */
  private maybeAddExtraModel(section: 'aiPrimary' | 'codegen' | 'embedding', provider: string, model: string): void {
    if (!model) return;
    let list: { value: string; label: string }[];
    if (section === 'embedding') list = this.embeddingModels[provider] || [];
    else if (section === 'codegen') list = this.codegenModelsList[provider] || [];
    else list = this.models[provider] || [];
    if (list.find(m => m.value === model)) return;
    const extra = { value: model, label: model + ' (custom)' };
    if (section === 'aiPrimary') this.extraAiPrimaryModel = extra;
    else if (section === 'codegen') this.extraCodegenModel = extra;
    else this.extraEmbeddingModel = extra;
  }

  private resetSectionState(): void {
    this.anthropicApiKey = '';
    this.openaiApiKey = '';
    this.aiPrimaryModel = '';
    this.aiPrimaryEndpoint = '';
    this.showAdvancedAiPrimary = false;
    this.extraAiPrimaryModel = null;
    this.usingDefaultAiPrimary = true;
    this.codegenModel = '';
    this.codegenEndpoint = '';
    this.showAdvancedCodegen = false;
    this.extraCodegenModel = null;
    this.usingDefaultCodegen = true;
    this.embeddingModel = '';
    this.embeddingEndpoint = '';
    this.showAdvancedEmbedding = false;
    this.extraEmbeddingModel = null;
    this.usingDefaultEmbedding = true;
  }

  /** Look up the shared API key for a given provider. Returns empty string for ollama. */
  private keyForProvider(provider: string): string {
    if (provider === 'anthropic') return this.anthropicApiKey;
    if (provider === 'openai') return this.openaiApiKey;
    return '';
  }

  resetToDatrisDefaults(): void {
    if (!this.isTrial) return;
    this.resetting = true;
    this.success = '';
    this.error = '';

    const deletes = ['ai-primary', 'codegen', 'embedding'].map(name =>
      this.http.delete('/api/v1/secrets/' + name, { responseType: 'text' }).toPromise().catch(() => null)
    );

    Promise.all(deletes).then(() => {
      this.resetting = false;
      this.success = 'Reverted to Datris-managed defaults.';
      this.loadConfig();
    });
  }

  /** A field still showing only the masked placeholder hasn't been edited. */
  private isMaskedOrEmpty(value: string): boolean {
    if (!value || !value.trim()) return true;
    return /^[•]+$/.test(value.trim());
  }

  private endpointFor(provider: string, kind: 'chat' | 'embedding'): string {
    const p = provider.toLowerCase();
    if (kind === 'embedding') {
      if (p === 'openai') return 'https://api.openai.com/v1/embeddings';
      if (p === 'ollama') return 'http://ollama:11434/v1/embeddings';
      return '';
    }
    if (p === 'anthropic') return 'https://api.anthropic.com/v1/messages';
    if (p === 'openai') return 'https://api.openai.com/v1/chat/completions';
    if (p === 'ollama') return 'http://ollama:11434/v1/chat/completions';
    return '';
  }

  save(): void {
    // A section is savable when (a) it has a model picked, AND
    // (b) either it doesn't need a key (ollama) or there's a key available
    //     in the shared Keys panel for its chosen provider.
    // Masked •••••••• values are passed through as-is — the backend's
    // masked-preservation logic keeps the existing apiKey at that path.
    const sectionReady = (provider: string, model: string): boolean => {
      if (!model || !model.trim()) return false;
      if (provider === 'ollama') return true;
      const key = this.keyForProvider(provider);
      return !!(key && key.length > 0);
    };

    const aiPrimaryReady = sectionReady(this.aiPrimaryProvider, this.aiPrimaryModel);
    const codegenReady = sectionReady(this.codegenProvider, this.codegenModel);
    const embeddingReady = sectionReady(this.embeddingProvider, this.embeddingModel);

    if (!aiPrimaryReady && !codegenReady && !embeddingReady) {
      this.error = 'Enter an API key for at least one provider, then pick a model in the section(s) that should use it.';
      this.success = '';
      return;
    }

    this.saving = true;
    this.success = '';
    this.error = '';

    const tasks: Promise<any>[] = [];

    const useEndpoint = (typed: string, fallback: string) => (typed && typed.trim()) ? typed.trim() : fallback;

    if (aiPrimaryReady) {
      const body: any = {
        provider: this.aiPrimaryProvider,
        endpoint: useEndpoint(this.aiPrimaryEndpoint, this.endpointFor(this.aiPrimaryProvider, 'chat')),
        model: this.aiPrimaryModel,
        apiKey: this.keyForProvider(this.aiPrimaryProvider)
      };
      if (this.aiPrimaryProvider === 'anthropic') body.version = '2023-06-01';
      tasks.push(this.http.put('/api/v1/secrets/ai-primary', body, { responseType: 'text' }).toPromise());
    }

    if (codegenReady) {
      const body: any = {
        provider: this.codegenProvider,
        endpoint: useEndpoint(this.codegenEndpoint, this.endpointFor(this.codegenProvider, 'chat')),
        model: this.codegenModel,
        apiKey: this.keyForProvider(this.codegenProvider)
      };
      if (this.codegenProvider === 'anthropic') body.version = '2023-06-01';
      tasks.push(this.http.put('/api/v1/secrets/codegen', body, { responseType: 'text' }).toPromise());
    }

    if (embeddingReady) {
      const body: any = {
        provider: this.embeddingProvider,
        endpoint: useEndpoint(this.embeddingEndpoint, this.endpointFor(this.embeddingProvider, 'embedding')),
        model: this.embeddingModel,
        apiKey: this.keyForProvider(this.embeddingProvider)
      };
      tasks.push(this.http.put('/api/v1/secrets/embedding', body, { responseType: 'text' }).toPromise());
    }

    Promise.all(tasks).then(() => {
      if (aiPrimaryReady) this.usingDefaultAiPrimary = false;
      if (codegenReady) this.usingDefaultCodegen = false;
      if (embeddingReady) this.usingDefaultEmbedding = false;
      this.saving = false;
      this.success = 'Configuration saved. Changes take effect on the next AI call.';
    }).catch(() => {
      this.saving = false;
      this.error = 'Failed to save configuration.';
    });
  }
}
