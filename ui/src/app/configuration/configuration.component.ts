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
  trialAiProvider = '';
  trialAiModel = '';
  trialEmbeddingModel = '';
  saving = false;
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
        if (!this.isTrial) {
          this.loadConfig();
        } else {
          this.loadTrialConfig();
        }
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

  loadTrialConfig(): void {
    // Read AI provider secret to show what's being used
    const providers = ['anthropic', 'openai'];
    for (const provider of providers) {
      this.http.get<any>('/api/v1/secrets/' + provider).subscribe({
        next: (data) => {
          if (data && data.fields && data.fields.model) {
            this.trialAiProvider = provider.charAt(0).toUpperCase() + provider.slice(1);
            this.trialAiModel = data.fields.model;
          }
        },
        error: () => {} // secret doesn't exist for this provider
      });
    }
    this.http.get<any>('/api/v1/secrets/embedding').subscribe({
      next: (data) => {
        if (data && data.fields && data.fields.model) {
          this.trialEmbeddingModel = data.fields.model;
        }
      },
      error: () => {}
    });
    this.loading = false;
  }

  loadConfig(): void {
    // Read the AI provider secret from Vault via the Secrets API
    this.http.get<any>('/api/v1/secrets').subscribe({
      next: (secrets) => {
        // Find the AI provider secret (anthropic or openai)
        const providers = ['anthropic', 'openai'];
        for (const provider of providers) {
          if (secrets && secrets.includes(provider)) {
            this.aiProvider = provider;
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
