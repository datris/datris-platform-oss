import { Component, OnDestroy, OnInit } from '@angular/core';
import { Subscription } from 'rxjs';
import { filter } from 'rxjs/operators';
import { ConfigAssistantStateService } from '../../config-chat/config-assistant-state.service';
import { CodeRepoService, CodeRepoConfig, CodeRepoTestResult } from './code-repo.service';
import { SecretsService } from '../../secrets.service';

const DEFAULTS: CodeRepoConfig = {
  provider: 'github',
  repo: '',
  apiBaseUrl: 'https://api.github.com',
  branch: 'main',
  pathPrefix: 'taps/',
  authSecretName: '',
  commitAuthor: 'Datris <bot@datris.ai>',
  commitMessageTemplate: 'tap({name}): {action} via Datris',
  enabled: false
};

@Component({
    selector: 'app-code-repo',
    templateUrl: './code-repo.component.html',
    styleUrls: ['./code-repo.component.css'],
    standalone: false
})
export class CodeRepoComponent implements OnInit, OnDestroy {
  private chatSub?: Subscription;

  config: CodeRepoConfig = { ...DEFAULTS };
  availableSecrets: string[] = [];
  loading = true;
  saving = false;
  testing = false;
  testResult: CodeRepoTestResult | null = null;
  success = '';
  error = '';

  showCreateSecret = false;
  // Sensible default so the browser's credential autofill (which sees a
  // text+password input pair and assumes a login form) never names the
  // secret. Field stays editable.
  newSecretName = 'github';
  newSecretToken = '';
  savingSecret = false;

  constructor(private codeRepo: CodeRepoService, private secretsService: SecretsService, private chatState: ConfigAssistantStateService) {}

  ngOnInit(): void {
    // Reload when the configuration chat changes this sub-tab's setting.
    this.chatSub = this.chatState.changed$
      .pipe(filter(e => e.tab === 'code-repo'))
      .subscribe(() => this.reloadAll());
    this.load();
    this.loadSecrets();
  }

  ngOnDestroy(): void {
    this.chatSub?.unsubscribe();
  }

  /** Settings and the repo-token list; both can change from the chat. */
  private reloadAll(): void {
    this.load();
    this.loadSecrets();
  }

  load(): void {
    this.codeRepo.get().subscribe({
      next: (cfg) => {
        this.config = { ...DEFAULTS, ...cfg };
        this.loading = false;
      },
      error: () => {
        this.error = 'Failed to load the code repository configuration.';
        this.loading = false;
      }
    });
  }

  loadSecrets(): void {
    this.secretsService.listSecrets('repo_token').subscribe({
      next: (secrets) => this.availableSecrets = secrets || [],
      error: () => this.availableSecrets = []
    });
  }

  test(): void {
    this.testing = true;
    this.testResult = null;
    this.error = '';
    this.codeRepo.test(this.config).subscribe({
      next: (result) => {
        this.testResult = result;
        this.testing = false;
      },
      error: () => {
        this.testResult = { ok: false, error: 'Connection test request failed.' };
        this.testing = false;
      }
    });
  }

  save(): void {
    this.saving = true;
    this.success = '';
    this.error = '';
    this.codeRepo.put(this.config).subscribe({
      next: (saved) => {
        this.config = { ...DEFAULTS, ...saved };
        this.success = this.config.enabled
          ? 'Saved. New tap scripts will be stored in ' + this.config.repo + '.'
          : 'Saved. Tap scripts use built-in storage.';
        this.saving = false;
      },
      error: (err) => {
        this.error = (err && err.error && err.error.error) || 'Save failed.';
        this.saving = false;
      }
    });
  }

  createSecret(): void {
    if (!this.newSecretName || !this.newSecretToken) { return; }
    this.savingSecret = true;
    this.secretsService.putSecret(this.newSecretName, {
      token: this.newSecretToken,
      _type: 'repo_token'
    }).subscribe({
      next: () => {
        this.config.authSecretName = this.newSecretName;
        this.showCreateSecret = false;
        this.newSecretName = 'github';
        this.newSecretToken = '';
        this.savingSecret = false;
        this.loadSecrets();
      },
      error: (err) => {
        this.error = (err && err.error && err.error.error) || 'Failed to save the token secret.';
        this.savingSecret = false;
      }
    });
  }
}
