import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { ModelCatalogService, ModelOption } from '../model-catalog.service';

@Component({
  selector: 'app-configuration',
  templateUrl: './configuration.component.html',
  styleUrls: ['./configuration.component.css']
})
export class ConfigurationComponent implements OnInit {
  activeTab: 'environment' | 'ai-providers' | 'taps' = 'ai-providers';

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

  // Remember the model the user typed/selected per provider so switching away and back restores it.
  private aiPrimaryModelMemory: Record<string, string> = {};
  private codegenModelMemory: Record<string, string> = {};
  private embeddingModelMemory: Record<string, string> = {};
  private prevAiPrimaryProvider = 'anthropic';
  private prevCodegenProvider = 'anthropic';
  private prevEmbeddingProvider = 'openai';

  // Populated from ModelCatalogService before loadConfig() runs. Remote fetch falls back
  // to the service's baked-in defaults if docs.datris.ai is unreachable.
  models: Record<string, ModelOption[]> = {};
  codegenModelsList: Record<string, ModelOption[]> = {};
  embeddingModels: Record<string, ModelOption[]> = {};

  environment = '';
  version = '';
  isTrial = false;
  isHosted = false;
  multiTenant = false;
  saving = false;
  success = '';
  error = '';
  loading = true;

  constructor(private http: HttpClient, private modelCatalog: ModelCatalogService) {}

  /** Trials share the same trial droplet infra as a hosted dedicated instance:
   *  bundled Ollama for embeddings, no local-Ollama chat option, no Advanced endpoint editing. */
  get hostedOrTrial(): boolean {
    return this.isHosted || this.isTrial;
  }

  ngOnInit(): void {
    this.http.get<any>('/api/v1/version').subscribe({
      next: (data) => {
        this.environment = data.environment || '';
        this.version = data.version || '';
        this.isTrial = this.environment.startsWith('trial-');
        this.isHosted = String(data.hosted) === 'true';
        this.multiTenant = String(data.multiTenant) === 'true';
        // Load the model catalog before reading secrets so maybeAddExtraModel compares
        // loaded model names against the freshest dropdown list.
        this.modelCatalog.fetch().then(catalog => {
          this.models = catalog.aiPrimary;
          this.codegenModelsList = catalog.codegen;
          this.embeddingModels = catalog.embedding;
          this.loadConfig();
        });
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
    // Stash the model from the previous provider so switching back restores it.
    if (this.aiPrimaryModel) {
      this.aiPrimaryModelMemory[this.prevAiPrimaryProvider] = this.aiPrimaryModel;
    }
    this.prevAiPrimaryProvider = this.aiPrimaryProvider;
    const remembered = this.aiPrimaryModelMemory[this.aiPrimaryProvider];
    if (remembered) {
      this.aiPrimaryModel = remembered;
    } else if (this.isOllama(this.aiPrimaryProvider)) {
      this.aiPrimaryModel = '';
    } else {
      const options = this.aiPrimaryModelOptions;
      this.aiPrimaryModel = options.length > 0 ? options[0].value : '';
    }
    // Refresh endpoint to the new provider's standard URL unless the user has customized it.
    if (!this.showAdvancedAiPrimary) {
      this.aiPrimaryEndpoint = this.endpointFor(this.aiPrimaryProvider, 'chat');
    }
    // On hosted instances, auto-set embedding based on the AI provider.
    if (this.isHosted) {
      if (this.aiPrimaryProvider === 'anthropic') {
        this.embeddingProvider = 'ollama';
        this.embeddingModel = 'bge-m3';
        this.embeddingEndpoint = this.endpointFor('ollama', 'embedding');
      } else if (this.aiPrimaryProvider === 'openai' && this.embeddingProvider === 'ollama') {
        // Switching from Anthropic→OpenAI: default to OpenAI embeddings (user can switch back to Ollama)
        this.embeddingProvider = 'openai';
        this.embeddingModel = 'text-embedding-3-small';
        this.embeddingEndpoint = this.endpointFor('openai', 'embedding');
      }
    }
  }

  onCodegenProviderChange(): void {
    if (this.codegenModel) {
      this.codegenModelMemory[this.prevCodegenProvider] = this.codegenModel;
    }
    this.prevCodegenProvider = this.codegenProvider;
    const remembered = this.codegenModelMemory[this.codegenProvider];
    if (remembered) {
      this.codegenModel = remembered;
    } else if (this.isOllama(this.codegenProvider)) {
      this.codegenModel = '';
    } else {
      const options = this.codegenModelOptions;
      this.codegenModel = options.length > 0 ? options[0].value : '';
    }
    if (!this.showAdvancedCodegen) {
      this.codegenEndpoint = this.endpointFor(this.codegenProvider, 'chat');
    }
  }

  onEmbeddingProviderChange(): void {
    if (this.embeddingModel) {
      this.embeddingModelMemory[this.prevEmbeddingProvider] = this.embeddingModel;
    }
    this.prevEmbeddingProvider = this.embeddingProvider;
    const remembered = this.embeddingModelMemory[this.embeddingProvider];
    if (remembered) {
      this.embeddingModel = remembered;
    } else if (this.embeddingProvider === 'ollama') {
      this.embeddingModel = 'bge-m3';
    } else if (this.embeddingProvider === 'ollama-local') {
      this.embeddingModel = '';
    } else {
      const options = this.embeddingModelOptions;
      this.embeddingModel = options.length > 0 ? options[0].value : '';
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
        if (fields && (fields.apiKey || (fields.provider || '').toLowerCase() === 'ollama')) {
          this.aiPrimaryProvider = (fields.provider || 'anthropic').toLowerCase();
          this.prevAiPrimaryProvider = this.aiPrimaryProvider;
          this.aiPrimaryModel = fields.model || '';
          this.aiPrimaryEndpoint = fields.endpoint || this.endpointFor(this.aiPrimaryProvider, 'chat');
          this.maybeAddExtraModel('aiPrimary', this.aiPrimaryProvider, this.aiPrimaryModel);
          this.showAdvancedAiPrimary = this.endpointIsCustom(this.aiPrimaryEndpoint, this.aiPrimaryProvider, 'chat');
          recordKeyForProvider(this.aiPrimaryProvider, fields.apiKey || '');
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
        if (fields && (fields.apiKey || (fields.provider || '').toLowerCase() === 'ollama')) {
          this.codegenProvider = (fields.provider || 'anthropic').toLowerCase();
          this.prevCodegenProvider = this.codegenProvider;
          this.codegenModel = fields.model || '';
          this.codegenEndpoint = fields.endpoint || this.endpointFor(this.codegenProvider, 'chat');
          this.maybeAddExtraModel('codegen', this.codegenProvider, this.codegenModel);
          this.showAdvancedCodegen = this.endpointIsCustom(this.codegenEndpoint, this.codegenProvider, 'chat');
          recordKeyForProvider(this.codegenProvider, fields.apiKey || '');
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
          let ep = (fields.provider || 'openai').toLowerCase();
          // Distinguish bundled vs unbundled Ollama by endpoint — bundled uses docker hostname.
          if (ep === 'ollama' && fields.endpoint && !fields.endpoint.includes('ollama:')) {
            ep = 'ollama-local';
          }
          this.embeddingProvider = ep;
          this.prevEmbeddingProvider = this.embeddingProvider;
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
    return '';  // ollama / ollama-local need no key
  }

  /** A field still showing only the masked placeholder hasn't been edited. */
  private isMaskedOrEmpty(value: string): boolean {
    if (!value || !value.trim()) return true;
    return /^[•]+$/.test(value.trim());
  }

  /** Returns true if the provider value is any Ollama variant (bundled or unbundled). */
  private isOllama(provider: string): boolean {
    return provider === 'ollama' || provider === 'ollama-local';
  }

  private endpointFor(provider: string, kind: 'chat' | 'embedding'): string {
    const p = provider.toLowerCase();
    if (kind === 'embedding') {
      if (p === 'openai') return 'https://api.openai.com/v1/embeddings';
      if (p === 'ollama') return 'http://ollama:11434/v1/embeddings';
      if (p === 'ollama-local') return 'http://host.docker.internal:11434/v1/embeddings';
      return '';
    }
    if (p === 'anthropic') return 'https://api.anthropic.com/v1/messages';
    if (p === 'openai') return 'https://api.openai.com/v1/chat/completions';
    if (p === 'ollama') return 'http://host.docker.internal:11434/v1/chat/completions';
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
      if (this.isOllama(provider)) return true;
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

    // Catch the silent-skip case: user picked a non-Ollama provider + model but
    // hasn't supplied that provider's key. Without this, the section's PUT is
    // silently dropped and the success banner lies about what was saved.
    const missing = new Set<string>();
    const flagIfMissingKey = (provider: string, model: string) => {
      if (!model || !model.trim()) return;
      if (this.isOllama(provider)) return;
      if (!this.keyForProvider(provider)) missing.add(provider);
    };
    flagIfMissingKey(this.aiPrimaryProvider, this.aiPrimaryModel);
    flagIfMissingKey(this.codegenProvider, this.codegenModel);
    flagIfMissingKey(this.embeddingProvider, this.embeddingModel);
    if (missing.size > 0) {
      const label = (p: string) => p === 'anthropic' ? 'Anthropic' : p === 'openai' ? 'OpenAI' : p;
      const names = Array.from(missing).map(label).join(' and ');
      this.error = `Enter the ${names} API key on the right — it's required by a section you've selected.`;
      this.success = '';
      return;
    }

    this.saving = true;
    this.success = '';
    this.error = '';

    const tasks: Promise<any>[] = [];

    const useEndpoint = (typed: string, fallback: string) => (typed && typed.trim()) ? typed.trim() : fallback;

    // Backend only knows "ollama" — map the UI-only "ollama-local" variant.
    const backendProvider = (p: string) => p === 'ollama-local' ? 'ollama' : p;

    if (aiPrimaryReady) {
      const body: any = {
        provider: backendProvider(this.aiPrimaryProvider),
        endpoint: useEndpoint(this.aiPrimaryEndpoint, this.endpointFor(this.aiPrimaryProvider, 'chat')),
        model: this.aiPrimaryModel,
        apiKey: this.keyForProvider(this.aiPrimaryProvider)
      };
      if (this.aiPrimaryProvider === 'anthropic') body.version = '2023-06-01';
      tasks.push(this.http.put('/api/v1/secrets/ai-primary', body, { responseType: 'text' }).toPromise());
    }

    if (codegenReady) {
      const body: any = {
        provider: backendProvider(this.codegenProvider),
        endpoint: useEndpoint(this.codegenEndpoint, this.endpointFor(this.codegenProvider, 'chat')),
        model: this.codegenModel,
        apiKey: this.keyForProvider(this.codegenProvider)
      };
      if (this.codegenProvider === 'anthropic') body.version = '2023-06-01';
      tasks.push(this.http.put('/api/v1/secrets/codegen', body, { responseType: 'text' }).toPromise());
    }

    if (embeddingReady) {
      const body: any = {
        provider: backendProvider(this.embeddingProvider),
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
