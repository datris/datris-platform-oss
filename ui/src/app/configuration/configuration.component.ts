import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute } from '@angular/router';
import { ModelCatalogService, ModelOption } from '../model-catalog.service';
import { AuthService } from '../auth.service';

type ConfigTab = 'environment' | 'ai-providers' | 'users' | 'secrets' | 'keys' | 'data-sources' | 'code-repo';

@Component({
  selector: 'app-configuration',
  templateUrl: './configuration.component.html',
  styleUrls: ['./configuration.component.css']
})
export class ConfigurationComponent implements OnInit {
  activeTab: ConfigTab = 'ai-providers';
  useUserAuth = false;

  // Shared API keys (entered once in the right-hand panel)
  anthropicApiKey = '';
  openaiApiKey = '';
  azureApiKey = '';

  // Azure OpenAI auth mode. Three modes, nothing persisted as a mode field —
  // on load it's inferred from which ai-keys fields came back (stored key →
  // 'key'; SP trio → 'sp'; an azure section configured with neither → 'mi').
  // Switching modes clears the other mode's stored fields on save, so a stale
  // API key can never shadow Entra auth (a stored key always wins server-side).
  azureAuthMode: 'key' | 'sp' | 'mi' = 'key';
  azureTenantId = '';
  azureClientId = '';
  azureClientSecret = '';
  // What the store held at load time — tells save() whether a mode switch has
  // stored fields from the other mode to clear.
  private azureStoredKey = false;
  private azureStoredSp = false;

  // AWS credentials for Amazon Bedrock (no API-key concept — SigV4). All four
  // live in the shared ai-keys store. Keys may be left blank to use the
  // server's IAM role / AWS default credential chain.
  awsAccessKeyId = '';
  awsSecretAccessKey = '';
  awsSessionToken = '';
  awsRegion = '';

  // Live Bedrock model discovery (server calls ListFoundationModels +
  // ListInferenceProfiles with the saved AWS credentials). null = discovery
  // unavailable → fall back to the static catalog's bedrock list.
  bedrockModels: ModelOption[] | null = null;

  // Bedrock model ids the discovery/catalog list doesn't cover (older dated
  // ids, region-specific inference profiles) can be typed in via a free-text
  // toggle per section.
  bedrockCustomModelAiPrimary = false;
  bedrockCustomModelCodegen = false;

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

  // Web Search (optional). Independent service — pick a provider regardless of
  // AI Primary, just like Embedding. The runtime attaches the tool natively when
  // possible (provider matches the AI call) or runs an out-of-band search call
  // and injects results when providers differ.
  webSearchEnabled = false;
  webSearchProvider: 'anthropic' | 'openai' = 'anthropic';
  webSearchEndpoint = '';
  webSearchModel = '';
  webSearchMaxUses = 3;
  // Tracks whether oss/web-search already has an apiKey saved AND for which provider.
  // Used by save() to skip the "re-enter key" guard when the server can preserve the
  // existing apiKey via masked-preservation (provider unchanged, key already stored).
  private webSearchStoredProvider: 'anthropic' | 'openai' | '' = '';
  private webSearchHasStoredKey = false;

  // Models loaded from existing secrets that aren't in the predefined dropdown lists.
  // Tracked per section, WITH the provider they were saved under — the extra
  // must only appear while that provider is selected (a Bedrock inference-profile
  // id has no business in the Anthropic dropdown after a provider switch).
  extraAiPrimaryModel: { provider: string; value: string; label: string } | null = null;
  extraCodegenModel: { provider: string; value: string; label: string } | null = null;
  extraEmbeddingModel: { provider: string; value: string; label: string } | null = null;
  extraWebSearchModel: { provider: string; value: string; label: string } | null = null;

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
  useApiKeys = false;
  saving = false;
  success = '';
  error = '';
  loading = true;

  constructor(
    private http: HttpClient,
    private modelCatalog: ModelCatalogService,
    private route: ActivatedRoute,
    private auth: AuthService
  ) {}

  /** Trials share the same trial droplet infra as a hosted dedicated instance:
   *  bundled Ollama for embeddings, no local-Ollama chat option, no Advanced endpoint editing. */
  get hostedOrTrial(): boolean {
    return this.isHosted || this.isTrial;
  }

  isAdmin(): boolean {
    return this.auth.current()?.role === 'admin';
  }

  /** Match the visibility rule that used to gate the top-nav Secrets link. */
  get canSeeSecrets(): boolean {
    return !this.isTrial && (!this.useUserAuth || this.isAdmin());
  }

  /** Keys are managed by admins only when user-auth is on. In legacy
   *  no-user-auth mode the whole UI is essentially admin, so anyone with
   *  access to the page can see them. Hidden on trial droplets (matches
   *  Secrets) since the platform's keys are managed centrally there.
   *  Also hidden when USE_API_KEYS=false — issuing keys is pointless when
   *  the validation layer isn't checking them. */
  get canSeeKeys(): boolean {
    return this.useApiKeys && !this.isTrial && (!this.useUserAuth || this.isAdmin());
  }

  ngOnInit(): void {
    // Honor ?tab=<name> for deep-links (e.g. the redirect from /secrets).
    this.route.queryParamMap.subscribe(p => {
      const t = p.get('tab');
      if (t === 'environment' || t === 'ai-providers' ||
          t === 'users' || t === 'secrets' || t === 'keys' || t === 'data-sources') {
        this.activeTab = t;
      }
    });


    this.http.get<any>('/api/v1/version').subscribe({
      next: (data) => {
        this.environment = data.environment || '';
        this.version = data.version || '';
        this.isTrial = this.environment.startsWith('trial-');
        this.isHosted = String(data.hosted) === 'true';
        this.multiTenant = String(data.multiTenant) === 'true';
        this.useUserAuth = String(data.useUserAuth) === 'true';
        this.useApiKeys = String(data.useApiKeys) === 'true';
        // The Users sub-tab only exists when user-auth is on. If a deep-link
        // (or stale URL) put us on activeTab='users' before we knew that,
        // bounce back to the default so the page isn't blank.
        if (this.activeTab === 'users' && !this.useUserAuth) {
          this.activeTab = 'ai-providers';
        }
        // Same fallback for the API-Keys tab when USE_API_KEYS is off — the
        // tab is hidden, so a deep-link landing on it would show nothing.
        if (this.activeTab === 'keys' && !this.canSeeKeys) {
          this.activeTab = 'ai-providers';
        }
        // Load the model catalog before reading secrets so maybeAddExtraModel compares
        // loaded model names against the freshest dropdown list.
        this.modelCatalog.fetch().then(catalog => {
          this.models = catalog.aiPrimary;
          this.codegenModelsList = catalog.codegen;
          this.embeddingModels = catalog.embedding;
          this.loadConfig();
          this.fetchBedrockModels();
        });
      },
      error: () => { this.loading = false; }
    });
  }

  /** Merge predefined model options with any "extra" model loaded from a secret
   *  that wasn't in the predefined list (e.g. a custom Anthropic model name).
   *  The extra only applies while its own provider is selected. */
  private withExtra(
    base: { value: string; label: string }[],
    extra: { provider: string; value: string; label: string } | null,
    provider: string
  ): { value: string; label: string }[] {
    if (!extra || extra.provider !== provider) return base;
    if (base.find(m => m.value === extra.value)) return base;
    return [...base, { value: extra.value, label: extra.label }];
  }

  get aiPrimaryModelOptions(): { value: string; label: string }[] {
    if (this.aiPrimaryProvider === 'bedrock') {
      return this.withExtra(this.bedrockModelList('aiPrimary'), this.extraAiPrimaryModel, 'bedrock');
    }
    return this.withExtra(this.models[this.aiPrimaryProvider] || [], this.extraAiPrimaryModel, this.aiPrimaryProvider);
  }

  get codegenModelOptions(): { value: string; label: string }[] {
    if (this.codegenProvider === 'bedrock') {
      return this.withExtra(this.bedrockModelList('codegen'), this.extraCodegenModel, 'bedrock');
    }
    return this.withExtra(this.codegenModelsList[this.codegenProvider] || [], this.extraCodegenModel, this.codegenProvider);
  }

  /** Bedrock model list: live discovery when available (models the AWS account
   *  can actually invoke, already resolved to invokable ids), else the static
   *  catalog's bedrock entries. Discovered models get catalog labels/recommended
   *  flags overlaid — matched loosely, because discovery returns regional
   *  inference-profile ids (`us.anthropic....`, `global....-v1:0`) that wrap
   *  the catalog's bare `anthropic.` ids. Recommended entries sort first. */
  private bedrockModelList(section: 'aiPrimary' | 'codegen'): ModelOption[] {
    const catalog = (section === 'codegen' ? this.codegenModelsList['bedrock'] : this.models['bedrock']) || [];
    if (!this.bedrockModels || this.bedrockModels.length === 0) return catalog;
    const merged = this.bedrockModels.map(d => {
      const c = catalog.find(x => d.value === x.value || d.value.includes(x.value));
      return c ? { value: d.value, label: c.label, recommended: c.recommended } : d;
    });
    return [...merged.filter(m => m.recommended), ...merged.filter(m => !m.recommended)];
  }

  /** Live Bedrock model discovery via the server. Non-200 (no AWS credentials
   *  saved yet, IAM policy without the List permissions) is expected — the
   *  dropdowns silently fall back to the static catalog. */
  fetchBedrockModels(): void {
    this.http.get<any>('/api/v1/ai/models', { params: { provider: 'bedrock' } }).subscribe({
      next: (data) => {
        if (data && Array.isArray(data.models) && data.models.length > 0) {
          this.bedrockModels = data.models;
          this.defaultBedrockModels();
        }
      },
      error: () => { /* discovery unavailable — static catalog fallback */ }
    });
  }

  /** A bedrock section with no model yet gets the recommended (or first)
   *  option pre-selected, so the dropdown never sits blank after the list
   *  loads. Called after discovery lands and after secrets load. */
  private defaultBedrockModels(): void {
    if (this.aiPrimaryProvider === 'bedrock' && !this.aiPrimaryModel) {
      const opts = this.aiPrimaryModelOptions;
      this.aiPrimaryModel = (opts.find(o => (o as ModelOption).recommended) || opts[0])?.value || '';
    }
    if (this.codegenProvider === 'bedrock' && !this.codegenModel) {
      const opts = this.codegenModelOptions;
      this.codegenModel = (opts.find(o => (o as ModelOption).recommended) || opts[0])?.value || '';
    }
  }

  get embeddingModelOptions(): { value: string; label: string }[] {
    return this.withExtra(this.embeddingModels[this.embeddingProvider] || [], this.extraEmbeddingModel, this.embeddingProvider);
  }

  /** Web search runs general-purpose summarization (read pages → write a research
   *  note) — same shape of work as AI Primary, so we reuse the AI Primary catalog
   *  for the provider's model list. Slow reasoning / codex models are still in
   *  the list but the section hint warns the user off them. */
  get webSearchModelOptions(): { value: string; label: string }[] {
    return this.withExtra(this.models[this.webSearchProvider] || [], this.extraWebSearchModel, this.webSearchProvider);
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
        this.embeddingProvider = 'tei';
        this.embeddingModel = 'BAAI/bge-m3';
        this.embeddingEndpoint = this.endpointFor('tei', 'embedding');
      } else if (this.aiPrimaryProvider === 'openai' && this.isBundledEmbedding(this.embeddingProvider)) {
        // Switching from Anthropic→OpenAI: default to OpenAI embeddings (user can switch back to bundled)
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
    } else if (this.embeddingProvider === 'tei') {
      this.embeddingModel = 'BAAI/bge-m3';
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
    // Four independent direct GETs by known names. 404 = no per-tenant override = use defaults.
    this.resetSectionState();

    let pending = 5;
    const done = () => {
      pending--;
      if (pending === 0) {
        this.loading = false;
        // Secrets are loaded — backfill any blank bedrock model selection from
        // whatever list is available (discovery if it already landed, else catalog).
        this.defaultBedrockModels();
        // Needs both the ai-keys fields and the slot providers, so it runs
        // only once everything has landed.
        this.inferAzureAuthMode();
      }
    };

    const recordKeyForProvider = (provider: string, apiKey: string) => {
      // Backend masks non-empty sensitive fields as ••••••••; empty stays empty.
      if (!apiKey) return;
      if (provider === 'anthropic') this.anthropicApiKey = apiKey;
      if (provider === 'openai') this.openaiApiKey = apiKey;
      if (provider === 'azure') this.azureApiKey = apiKey;
    };

    this.http.get<any>('/api/v1/secrets/ai-primary').subscribe({
      next: (data) => {
        const fields = data && data.fields;
        // Load the saved override whenever the secret has ANY meaningful state
        // (apiKey, Ollama provider, OR just a stored provider). The apiKey can
        // be missing after a provider switch — the backend intentionally drops
        // it so the old provider's key isn't sent to the new one. That doesn't
        // mean the saved provider/model/endpoint should be ignored.
        if (fields && (fields.apiKey || fields.provider)) {
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
        // Same relaxation as ai-primary: load the override whenever a provider
        // is stored, even if apiKey was cleared by a provider switch.
        if (fields && (fields.apiKey || fields.provider)) {
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

    this.http.get<any>('/api/v1/secrets/web-search').subscribe({
      next: (data) => {
        const fields = data && data.fields;
        if (fields) {
          this.webSearchEnabled = String(fields.enabled).toLowerCase() === 'true';
          const p = (fields.provider || '').toLowerCase();
          if (p === 'anthropic' || p === 'openai') {
            this.webSearchProvider = p;
            this.webSearchStoredProvider = p;
            this.prevWebSearchProvider = p;
          }
          this.webSearchEndpoint = fields.endpoint || '';
          this.webSearchModel = fields.model || '';
          this.maybeAddExtraModel('webSearch', this.webSearchProvider, this.webSearchModel);
          const n = parseInt(fields.maxUses, 10);
          if (!isNaN(n) && n > 0) this.webSearchMaxUses = n;
          this.webSearchHasStoredKey = !!(fields.apiKey && fields.apiKey.length > 0);
          // Populate the right-hand panel from this secret's key if no other section already did.
          const recordKeyForProvider = (provider: string, apiKey: string) => {
            if (!apiKey) return;
            if (provider === 'anthropic' && !this.anthropicApiKey) this.anthropicApiKey = apiKey;
            if (provider === 'openai' && !this.openaiApiKey) this.openaiApiKey = apiKey;
          };
          recordKeyForProvider(this.webSearchProvider, fields.apiKey || '');
        }
        done();
      },
      error: () => { done(); }
    });

    this.http.get<any>('/api/v1/secrets/embedding').subscribe({
      next: (data) => {
        const fields = data && data.fields;
        const loadedProvider = (fields && fields.provider || '').toLowerCase();
        const isBundledNoKey = loadedProvider === 'ollama' || loadedProvider === 'tei';
        // Same relaxation as ai-primary / codegen: load the override whenever
        // a provider is stored, even if apiKey was cleared by a provider switch.
        if (fields && (fields.apiKey || isBundledNoKey || loadedProvider)) {
          let ep = loadedProvider || 'openai';
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

    // Shared per-provider key store — the authoritative source for the right-hand
    // "Your API Keys" panel. Keys live here independent of which slot uses each
    // provider, so a provider has its key shown even when no section currently
    // uses it (the "enter once, switch freely" model). Values arrive masked
    // (••••••••); masked round-trips and preserves the stored key on save.
    this.http.get<any>('/api/v1/secrets/ai-keys').subscribe({
      next: (data) => {
        const fields = data && data.fields;
        if (fields) {
          if (fields.anthropicApiKey) this.anthropicApiKey = fields.anthropicApiKey;
          if (fields.openaiApiKey) this.openaiApiKey = fields.openaiApiKey;
          if (fields.azureApiKey) this.azureApiKey = fields.azureApiKey;
          // Bedrock AWS credentials. Key-like fields arrive masked (••••••••)
          // and round-trip via the server's masked-preservation; awsRegion is
          // plain and displays as stored.
          if (fields.awsAccessKeyId) this.awsAccessKeyId = fields.awsAccessKeyId;
          if (fields.awsSecretAccessKey) this.awsSecretAccessKey = fields.awsSecretAccessKey;
          if (fields.awsSessionToken) this.awsSessionToken = fields.awsSessionToken;
          if (fields.awsRegion) this.awsRegion = fields.awsRegion;
          // Azure Entra service principal. The clientSecret is key-like and
          // arrives masked (••••••••); tenant/client IDs are plain, like awsRegion.
          if (fields.azureTenantId) this.azureTenantId = fields.azureTenantId;
          if (fields.azureClientId) this.azureClientId = fields.azureClientId;
          if (fields.azureClientSecret) this.azureClientSecret = fields.azureClientSecret;
        }
        done();
      },
      error: () => { done(); }
    });
  }

  /** Returns true when the loaded endpoint isn't the standard one for that provider/kind. */
  private endpointIsCustom(endpoint: string, provider: string, kind: 'chat' | 'embedding'): boolean {
    if (!endpoint) return false;
    const standard = this.endpointFor(provider, kind);
    return standard !== '' && endpoint.trim() !== standard;
  }

  /** Add a loaded model to the section's "extra" slot if it isn't in the predefined list. */
  private maybeAddExtraModel(section: 'aiPrimary' | 'codegen' | 'embedding' | 'webSearch', provider: string, model: string): void {
    if (!model) return;
    let list: { value: string; label: string }[];
    if (section === 'embedding') list = this.embeddingModels[provider] || [];
    else if (section === 'codegen') list = this.codegenModelsList[provider] || [];
    else list = this.models[provider] || [];
    if (list.find(m => m.value === model)) return;
    const extra = { provider, value: model, label: model + ' (custom)' };
    if (section === 'aiPrimary') this.extraAiPrimaryModel = extra;
    else if (section === 'codegen') this.extraCodegenModel = extra;
    else if (section === 'webSearch') this.extraWebSearchModel = extra;
    else this.extraEmbeddingModel = extra;
  }

  private resetSectionState(): void {
    this.anthropicApiKey = '';
    this.openaiApiKey = '';
    this.azureApiKey = '';
    this.awsAccessKeyId = '';
    this.awsSecretAccessKey = '';
    this.awsSessionToken = '';
    this.awsRegion = '';
    this.azureAuthMode = 'key';
    this.azureTenantId = '';
    this.azureClientId = '';
    this.azureClientSecret = '';
    this.azureStoredKey = false;
    this.azureStoredSp = false;
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
    this.webSearchEnabled = false;
    this.webSearchProvider = 'anthropic';
    this.webSearchEndpoint = '';
    this.webSearchModel = '';
    this.webSearchMaxUses = 3;
    this.webSearchStoredProvider = '';
    this.webSearchHasStoredKey = false;
    this.extraWebSearchModel = null;
  }

  /** Web search is its own service (like Embedding). Always offer it as long as
   *  the platform has Anthropic or OpenAI as an available provider somewhere. */
  get webSearchAvailable(): boolean {
    return true;
  }

  /** Disable a provider radio when its key isn't entered. */
  get anthropicKeyAvailable(): boolean {
    return !!(this.anthropicApiKey && this.anthropicApiKey.length > 0);
  }
  get openaiKeyAvailable(): boolean {
    return !!(this.openaiApiKey && this.openaiApiKey.length > 0);
  }
  get azureKeyAvailable(): boolean {
    return !!(this.azureApiKey && this.azureApiKey.length > 0);
  }
  /** Bedrock credentials are "available" with explicit keys OR fully blank
   *  (IAM-role / default-chain mode) — partial entry is the only invalid state,
   *  caught at save time. */
  get bedrockCredsPartial(): boolean {
    const ak = !!(this.awsAccessKeyId && this.awsAccessKeyId.trim());
    const sk = !!(this.awsSecretAccessKey && this.awsSecretAccessKey.trim());
    return ak !== sk;
  }

  /** The Azure Entra service-principal trio is all-or-none; partial entry is
   *  the only invalid state, caught at save time (mirrors bedrockCredsPartial). */
  get azureSpPartial(): boolean {
    const n = this.azureSpFieldCount();
    return n > 0 && n < 3;
  }
  get azureSpComplete(): boolean {
    return this.azureSpFieldCount() === 3;
  }
  private azureSpFieldCount(): number {
    return [this.azureTenantId, this.azureClientId, this.azureClientSecret]
      .filter(v => !!(v && v.trim())).length;
  }

  /** Infer the Azure auth mode from what actually loaded — a mode is never
   *  stored. A stored API key wins (matching the server's request-time
   *  precedence); an SP trio means Entra service principal; an azure section
   *  configured with neither can only be running on a managed identity. */
  private inferAzureAuthMode(): void {
    this.azureStoredKey = !!this.azureApiKey;
    this.azureStoredSp = !!(this.azureTenantId || this.azureClientId || this.azureClientSecret);
    const azureConfigured =
      this.aiPrimaryProvider === 'azure' || this.codegenProvider === 'azure' || this.embeddingProvider === 'azure';
    if (this.azureStoredKey) this.azureAuthMode = 'key';
    else if (this.azureStoredSp) this.azureAuthMode = 'sp';
    else if (azureConfigured) this.azureAuthMode = 'mi';
    else this.azureAuthMode = 'key';
  }

  /** Default endpoint per provider for the web-search call (mirrors the chat
   *  endpoint defaults — Anthropic Messages, OpenAI Responses). */
  webSearchEndpointFor(provider: string): string {
    const p = provider.toLowerCase();
    if (p === 'anthropic') return 'https://api.anthropic.com/v1/messages';
    if (p === 'openai')    return 'https://api.openai.com/v1/responses';
    return '';
  }

  /** Default model per provider when the user hasn't picked one. These are
   *  picked for SPEED of summarization, not reasoning depth — codex / opus
   *  models add 30-60s per search with no real quality lift. Override via
   *  the Advanced expander if needed. */
  webSearchModelFor(provider: string): string {
    const p = provider.toLowerCase();
    if (p === 'anthropic') return 'claude-sonnet-4-6';
    if (p === 'openai')    return 'gpt-5.5';
    return '';
  }

  onWebSearchProviderChange(): void {
    if (!this.webSearchModel || this.webSearchModel === this.webSearchModelFor(this.prevWebSearchProvider)) {
      this.webSearchModel = this.webSearchModelFor(this.webSearchProvider);
    }
    if (!this.webSearchEndpoint || this.webSearchEndpoint === this.webSearchEndpointFor(this.prevWebSearchProvider)) {
      this.webSearchEndpoint = this.webSearchEndpointFor(this.webSearchProvider);
    }
    // Reset extra-tracker; if the new provider's list also lacks the loaded model
    // (e.g. user switches and the catalog entry doesn't match), re-add it.
    this.extraWebSearchModel = null;
    this.maybeAddExtraModel('webSearch', this.webSearchProvider, this.webSearchModel);
    this.prevWebSearchProvider = this.webSearchProvider;
  }
  private prevWebSearchProvider: 'anthropic' | 'openai' = 'anthropic';

  /** Look up the shared API key for a given provider. Returns empty string for local/bundled providers.
   *  Azure in an Entra mode deliberately returns '' even if the key field still
   *  holds a (masked) value — the slot secret must store an empty apiKey so the
   *  server's request-time resolution falls through to Entra instead of
   *  preserving the old key via the masked round-trip. */
  private keyForProvider(provider: string): string {
    if (provider === 'anthropic') return this.anthropicApiKey;
    if (provider === 'openai') return this.openaiApiKey;
    if (provider === 'azure') return this.azureAuthMode === 'key' ? this.azureApiKey : '';
    return '';  // ollama / ollama-local / tei need no key
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

  /** Returns true if the provider is a bundled (no-key-required) embedding service. */
  private isBundledEmbedding(provider: string): boolean {
    return provider === 'tei' || provider === 'ollama';
  }

  /** Returns true if the provider doesn't require an API key in the shared Keys
   *  panel. Bedrock qualifies: its AWS credentials are optional (blank = the
   *  server's IAM role / default credential chain) and validated separately.
   *  Azure qualifies in the Entra modes: SP-trio coherence is validated
   *  separately at save time, and managed identity stores nothing at all. */
  private noKeyRequired(provider: string): boolean {
    return this.isOllama(provider) || provider === 'tei' || provider === 'bedrock'
      || (provider === 'azure' && this.azureAuthMode !== 'key');
  }

  private endpointFor(provider: string, kind: 'chat' | 'embedding'): string {
    const p = provider.toLowerCase();
    if (kind === 'embedding') {
      if (p === 'openai') return 'https://api.openai.com/v1/embeddings';
      if (p === 'tei') return 'http://tei:80/v1/embeddings';
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
      if (this.noKeyRequired(provider)) return true;
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
      if (this.noKeyRequired(provider)) return;
      if (!this.keyForProvider(provider)) missing.add(provider);
    };
    flagIfMissingKey(this.aiPrimaryProvider, this.aiPrimaryModel);
    flagIfMissingKey(this.codegenProvider, this.codegenModel);
    flagIfMissingKey(this.embeddingProvider, this.embeddingModel);
    if (missing.size > 0) {
      const label = (p: string) => p === 'anthropic' ? 'Anthropic' : p === 'openai' ? 'OpenAI' : p === 'azure' ? 'Azure OpenAI' : p === 'bedrock' ? 'Amazon Bedrock' : p;
      const names = Array.from(missing).map(label).join(' and ');
      this.error = `Enter the ${names} API key on the right — it's required by a section you've selected.`;
      this.success = '';
      return;
    }

    // Bedrock credential coherence: keys are all-or-none (blank = the server's
    // IAM role / default credential chain), and explicit keys need a region so
    // the region stays bound to the credential that owns it.
    const bedrockInUse =
      (this.aiPrimaryProvider === 'bedrock' && aiPrimaryReady) ||
      (this.codegenProvider === 'bedrock' && codegenReady);
    if (bedrockInUse) {
      if (this.bedrockCredsPartial) {
        this.error = 'Bedrock AWS credentials are incomplete — enter both the Access Key ID and Secret Access Key, or leave both blank to use the server\'s IAM role.';
        this.success = '';
        return;
      }
      if (this.awsAccessKeyId.trim() && !this.awsRegion.trim()) {
        this.error = 'Enter the AWS Region for Bedrock (e.g. us-east-1) — required when using explicit AWS keys.';
        this.success = '';
        return;
      }
    }

    // Azure Entra credential coherence: in SP mode the trio is all-or-none
    // (mirrors the Bedrock all-or-none check); managed identity stores nothing
    // and is validated live on the first call.
    const azureInUse =
      (this.aiPrimaryProvider === 'azure' && aiPrimaryReady) ||
      (this.codegenProvider === 'azure' && codegenReady) ||
      (this.embeddingProvider === 'azure' && embeddingReady);
    if (azureInUse && this.azureAuthMode === 'sp' && !this.azureSpComplete) {
      this.error = 'Azure Entra service principal is incomplete — enter the Tenant ID, Client ID, and Client Secret, or switch Azure authentication to API key or managed identity.';
      this.success = '';
      return;
    }

    // Azure has no default endpoint — the URL embeds the customer's resource
    // name, so a ready azure section must have one typed in.
    const azureMissingEndpoint =
      (this.aiPrimaryProvider === 'azure' && aiPrimaryReady && !this.aiPrimaryEndpoint.trim()) ||
      (this.codegenProvider === 'azure' && codegenReady && !this.codegenEndpoint.trim()) ||
      (this.embeddingProvider === 'azure' && embeddingReady && !this.embeddingEndpoint.trim());
    if (azureMissingEndpoint) {
      this.error = 'Enter your Azure OpenAI endpoint (e.g. https://YOUR-RESOURCE.openai.azure.com/openai/v1/chat/completions) in each section set to Azure OpenAI.';
      this.success = '';
      return;
    }

    this.saving = true;
    this.success = '';
    this.error = '';

    const tasks: Promise<any>[] = [];

    // Shared per-provider key store — the durable home for provider keys,
    // independent of which slot uses each provider. This is what makes switching
    // a slot's provider back and forth non-destructive: each provider's key
    // persists on its own. Send only non-empty panel values so an unset provider
    // isn't cleared; masked (••••••••) values are preserved server-side.
    {
      const keysBody: any = {};
      if (this.anthropicApiKey) keysBody.anthropicApiKey = this.anthropicApiKey;
      if (this.openaiApiKey) keysBody.openaiApiKey = this.openaiApiKey;
      // Azure follows the selected auth mode: save the active mode's fields and
      // explicitly CLEAR the other mode's stored fields (the ai-keys merge only
      // preserves fields the request omits — an explicit '' overwrites). This is
      // what makes the mode switch effective: at request time a stored API key
      // always beats Entra config, so a stale key must not survive the switch.
      if (this.azureAuthMode === 'key') {
        if (this.azureApiKey) keysBody.azureApiKey = this.azureApiKey;
        if (this.azureStoredSp) {
          keysBody.azureTenantId = '';
          keysBody.azureClientId = '';
          keysBody.azureClientSecret = '';
        }
      } else {
        if (this.azureAuthMode === 'sp') {
          if (this.azureTenantId) keysBody.azureTenantId = this.azureTenantId.trim();
          if (this.azureClientId) keysBody.azureClientId = this.azureClientId.trim();
          if (this.azureClientSecret) keysBody.azureClientSecret = this.azureClientSecret;
        } else if (this.azureStoredSp) {
          keysBody.azureTenantId = '';
          keysBody.azureClientId = '';
          keysBody.azureClientSecret = '';
        }
        if (this.azureStoredKey) keysBody.azureApiKey = '';
      }
      if (this.awsAccessKeyId) keysBody.awsAccessKeyId = this.awsAccessKeyId;
      if (this.awsSecretAccessKey) keysBody.awsSecretAccessKey = this.awsSecretAccessKey;
      if (this.awsSessionToken) keysBody.awsSessionToken = this.awsSessionToken;
      if (this.awsRegion) keysBody.awsRegion = this.awsRegion;
      if (Object.keys(keysBody).length > 0) {
        tasks.push(this.http.put('/api/v1/secrets/ai-keys', keysBody, { responseType: 'text' }).toPromise());
      }
    }

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

    // Web search is its own independent service — provider, endpoint, model, and
    // its own apiKey copied from the right-hand panel based on the chosen provider
    // (mirrors how Embedding stores its key). When the panel value is masked
    // (••••••••), we still send it — the server's masked-preservation logic
    // keeps any existing key, and if there's nothing to preserve, runWebSearch
    // falls back to the ANTHROPIC_API_KEY / OPENAI_API_KEY env var at request time.
    {
      const wsKey = this.keyForProvider(this.webSearchProvider);
      const body: any = {
        enabled: this.webSearchEnabled ? 'true' : 'false',
        provider: this.webSearchProvider,
        endpoint: useEndpoint(this.webSearchEndpoint, this.webSearchEndpointFor(this.webSearchProvider)),
        model: this.webSearchModel || this.webSearchModelFor(this.webSearchProvider),
        apiKey: wsKey || '',
        maxUses: String(this.webSearchMaxUses || 3)
      };
      if (this.webSearchProvider === 'anthropic') body.version = '2023-06-01';
      tasks.push(this.http.put('/api/v1/secrets/web-search', body, { responseType: 'text' }).toPromise());
    }

    Promise.all(tasks).then(() => {
      if (aiPrimaryReady) this.usingDefaultAiPrimary = false;
      if (codegenReady) this.usingDefaultCodegen = false;
      if (embeddingReady) this.usingDefaultEmbedding = false;
      this.saving = false;
      this.success = 'Configuration saved. Changes take effect on the next AI call.';
      // Mask the just-typed AWS credential values in place — the server stores
      // them and returns ••••••••, so leaving plaintext in the fields (until a
      // full page reload) needlessly exposes them on screen. The masked
      // placeholder round-trips: a later save preserves the stored values.
      const maskIfSet = (v: string) => (v && !/^[•]+$/.test(v.trim())) ? '••••••••' : v;
      this.awsAccessKeyId = maskIfSet(this.awsAccessKeyId);
      this.awsSecretAccessKey = maskIfSet(this.awsSecretAccessKey);
      this.awsSessionToken = maskIfSet(this.awsSessionToken);
      this.azureClientSecret = maskIfSet(this.azureClientSecret);
      // The store now reflects the selected Azure auth mode — the other mode's
      // fields were cleared by this save.
      this.azureStoredKey = this.azureAuthMode === 'key' && !!this.azureApiKey;
      this.azureStoredSp = this.azureAuthMode === 'sp' && this.azureSpFieldCount() > 0;
      // Newly saved AWS credentials may unlock live Bedrock model discovery.
      if (this.awsAccessKeyId || this.awsRegion) this.fetchBedrockModels();
    }).catch(() => {
      this.saving = false;
      this.error = 'Failed to save configuration.';
    });
  }
}
