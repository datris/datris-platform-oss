import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'app-configuration',
  templateUrl: './configuration.component.html',
  styleUrls: ['./configuration.component.css']
})
export class ConfigurationComponent implements OnInit {
  aiProvider = 'anthropic';
  aiModel = '';
  aiApiKey = '';
  embeddingApiKey = '';

  models: Record<string, { value: string; label: string }[]> = {
    anthropic: [
      { value: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6 (recommended)' },
      { value: 'claude-opus-4-6', label: 'Claude Opus 4.6' },
      { value: 'claude-haiku-4-5', label: 'Claude Haiku 4.5' },
    ],
    openai: [
      { value: 'gpt-4.1', label: 'GPT-4.1 (recommended)' },
      { value: 'gpt-5', label: 'GPT-5' },
      { value: 'o3', label: 'o3' },
    ],
  };

  environment = '';
  version = '';
  isTrial = false;
  usingDatrisDefaults = true;
  saving = false;
  resetting = false;
  success = '';
  error = '';
  loading = true;

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    // Get environment name
    this.http.get<any>('/api/v1/version').subscribe({
      next: (data) => {
        this.environment = data.environment || '';
        this.version = data.version || '';
        this.isTrial = this.environment.startsWith('trial-');
        this.loadConfig();
      },
      error: () => { this.loading = false; }
    });
  }

  get modelOptions(): { value: string; label: string }[] {
    return this.models[this.aiProvider] || [];
  }

  get showEmbeddingKey(): boolean {
    return this.aiProvider !== 'openai';
  }

  onProviderChange(): void {
    const options = this.modelOptions;
    if (options.length > 0 && !options.find(m => m.value === this.aiModel)) {
      this.aiModel = options[0].value;
    }
  }

  loadConfig(): void {
    // Read the AI provider secret from Vault via the Secrets API.
    // In trial/multi-tenant mode this scopes to the user's tenant vault path,
    // so an empty result means they're using the shared Datris-managed keys.
    this.aiApiKey = '';
    this.embeddingApiKey = '';
    this.usingDatrisDefaults = true;

    this.http.get<any>('/api/v1/secrets').subscribe({
      next: (secrets) => {
        // Find the AI provider secret (anthropic or openai)
        const providers = ['anthropic', 'openai'];
        for (const provider of providers) {
          if (secrets && secrets.includes(provider)) {
            this.aiProvider = provider;
            this.usingDatrisDefaults = false;
            this.http.get<any>('/api/v1/secrets/' + provider).subscribe({
              next: (data) => {
                if (data && data.fields) {
                  this.aiModel = data.fields.model || '';
                  this.aiApiKey = data.fields.apiKey || '';
                }
                this.loading = false;
              },
              error: () => { this.loading = false; }
            });

            this.http.get<any>('/api/v1/secrets/embedding').subscribe({
              next: (data) => {
                if (data && data.fields) {
                  this.embeddingApiKey = data.fields.apiKey || '';
                }
              }
            });
            return;
          }
        }
        this.loading = false;
      },
      error: () => { this.loading = false; }
    });
  }

  resetToDatrisDefaults(): void {
    if (!this.isTrial) return;
    this.resetting = true;
    this.success = '';
    this.error = '';

    const deletes = ['anthropic', 'openai', 'embedding'].map(name =>
      this.http.delete('/api/v1/secrets/' + name, { responseType: 'text' }).toPromise().catch(() => null)
    );

    Promise.all(deletes).then(() => {
      this.aiApiKey = '';
      this.embeddingApiKey = '';
      this.usingDatrisDefaults = true;
      this.resetting = false;
      this.success = 'Reverted to Datris-managed AI keys.';
      this.loadConfig();
    });
  }

  save(): void {
    this.saving = true;
    this.success = '';
    this.error = '';

    const endpoint = this.aiProvider === 'anthropic'
      ? 'https://api.anthropic.com/v1/messages'
      : 'https://api.openai.com/v1/chat/completions';

    const embeddingKey = this.aiProvider === 'openai' ? this.aiApiKey : this.embeddingApiKey;

    // Save AI provider secret
    const aiBody: any = {};
    aiBody.endpoint = endpoint;
    aiBody.model = this.aiModel;
    aiBody.apiKey = this.aiApiKey;

    this.http.put('/api/v1/secrets/' + this.aiProvider, aiBody, { responseType: 'text' }).subscribe({
      next: () => {
        // Save embedding secret
        const embBody: any = {};
        embBody.endpoint = 'https://api.openai.com/v1/embeddings';
        embBody.model = 'text-embedding-3-small';
        embBody.apiKey = embeddingKey;

        this.http.put('/api/v1/secrets/embedding', embBody, { responseType: 'text' }).subscribe({
          next: () => {
            // Delete the old provider secret if switching providers
            const oldProvider = this.aiProvider === 'anthropic' ? 'openai' : 'anthropic';
            this.http.delete('/api/v1/secrets/' + oldProvider, { responseType: 'text' }).subscribe({
              next: () => {},
              error: () => {} // ignore if doesn't exist
            });

            this.usingDatrisDefaults = false;
            this.success = 'Configuration saved. Changes take effect on the next pipeline run.';
            this.saving = false;
          },
          error: () => {
            this.error = 'Failed to save embedding configuration.';
            this.saving = false;
          }
        });
      },
      error: () => {
        this.error = 'Failed to save AI configuration.';
        this.saving = false;
      }
    });
  }
}
