import { Component, OnInit } from '@angular/core';
import { TapPromptService, TapPromptFragment } from './tap-prompt.service';

interface FragmentForm {
  key: string;
  aliases: string;
  content: string;
  enabled: boolean;
  originalKey: string | null;
}

const STARTER_FRAGMENTS: TapPromptFragment[] = [
  {
    key: 'AWS',
    aliases: ['S3', 'EC2', 'Lambda', 'DynamoDB', 'boto3'],
    content:
      'When fetching data from AWS services, prefer boto3 with IAM credentials over long-lived access keys. ' +
      'Never hardcode credentials. Use os.environ.get("AWS_ACCESS_KEY_ID") / "AWS_SECRET_ACCESS_KEY" / ' +
      '"AWS_REGION". Respect regional endpoints.',
    enabled: true,
  },
  {
    key: 'Polygon',
    aliases: ['polygon.io'],
    content:
      'Use the `requests` library with `os.environ.get("POLYGON_API_KEY")` and pass auth via the ' +
      '`Authorization: Bearer {key}` header (query param `?apiKey=` also works but prefer the header).',
    enabled: true,
  },
  {
    key: 'Stripe',
    aliases: ['stripe.com'],
    content:
      'Use the stripe Python SDK (pip install stripe). Set stripe.api_key = os.environ.get("STRIPE_API_KEY"). ' +
      'Use auto_paging_iter() for list endpoints to transparently handle pagination. ' +
      'Restrict access with a restricted key that has read-only permissions on the resources being fetched.',
    enabled: true,
  },
  {
    key: 'SEC EDGAR',
    aliases: ['EDGAR', 'sec.gov'],
    content:
      'SEC EDGAR requires a User-Agent header identifying the requester (e.g. "CompanyName contact@email"). ' +
      'Rate limit: 10 requests per second. Use https://data.sec.gov/ for JSON APIs, https://www.sec.gov/ ' +
      'for document downloads. CIK values must be zero-padded to 10 digits.',
    enabled: true,
  },
];

@Component({
  selector: 'app-tap-prompt-list',
  templateUrl: './tap-prompt-list.component.html',
  styleUrls: ['./tap-prompt-list.component.css'],
})
export class TapPromptListComponent implements OnInit {
  fragments: TapPromptFragment[] = [];
  loading = true;
  error = '';
  success = '';

  showForm = false;
  form: FragmentForm = this.emptyForm();
  saving = false;
  suggesting = false;
  importStatus = '';
  deleteTarget = '';
  deletingKey = '';
  confirmLoadExamples = false;
  loadingExamples = false;

  constructor(private svc: TapPromptService) {}

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading = true;
    this.svc.list().subscribe({
      next: (frags) => {
        this.fragments = frags || [];
        this.loading = false;
      },
      error: () => {
        this.error = 'Failed to load prompt fragments.';
        this.loading = false;
      },
    });
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.showForm = true;
    this.error = '';
    this.success = '';
  }

  openEdit(fragment: TapPromptFragment): void {
    this.form = {
      key: fragment.key,
      aliases: (fragment.aliases || []).join(', '),
      content: fragment.content,
      enabled: fragment.enabled,
      originalKey: fragment.key,
    };
    this.showForm = true;
    this.error = '';
    this.success = '';
  }

  cancelForm(): void {
    this.showForm = false;
    this.form = this.emptyForm();
  }

  suggest(): void {
    const key = this.form.key.trim();
    if (!key) {
      this.error = 'Enter a Key first — the suggestion uses it as context.';
      return;
    }
    const aliases = this.form.aliases
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
    this.suggesting = true;
    this.error = '';
    this.svc.suggest(key, aliases, this.form.content).subscribe({
      next: (res) => {
        this.form.content = res.content || this.form.content;
        this.suggesting = false;
      },
      error: () => {
        this.suggesting = false;
        this.error = 'Suggestion failed. Check that an AI provider is configured.';
      },
    });
  }

  save(): void {
    const key = this.form.key.trim();
    if (!key) {
      this.error = 'Key is required.';
      return;
    }
    if (!this.form.content.trim()) {
      this.error = 'Content is required.';
      return;
    }
    const aliases = this.form.aliases
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
    const fragment: TapPromptFragment = {
      key,
      aliases,
      content: this.form.content,
      enabled: this.form.enabled,
    };
    this.saving = true;
    this.svc.save(fragment).subscribe({
      next: () => {
        this.saving = false;
        this.showForm = false;
        this.success = `Saved fragment "${key}".`;
        this.error = '';
        this.reload();
      },
      error: () => {
        this.saving = false;
        this.error = 'Failed to save fragment.';
      },
    });
  }

  toggleEnabled(fragment: TapPromptFragment): void {
    const updated: TapPromptFragment = { ...fragment, enabled: !fragment.enabled };
    this.svc.save(updated).subscribe({
      next: () => this.reload(),
      error: () => (this.error = `Failed to toggle "${fragment.key}".`),
    });
  }

  askDelete(fragment: TapPromptFragment): void {
    this.deleteTarget = fragment.key;
    this.error = '';
    this.success = '';
  }

  cancelDelete(): void {
    this.deleteTarget = '';
  }

  confirmDelete(fragment: TapPromptFragment): void {
    this.deletingKey = fragment.key;
    this.svc.delete(fragment.key).subscribe({
      next: () => {
        this.success = `Deleted "${fragment.key}".`;
        this.deletingKey = '';
        this.deleteTarget = '';
        this.reload();
      },
      error: () => {
        this.error = `Failed to delete "${fragment.key}".`;
        this.deletingKey = '';
      },
    });
  }

  askLoadExamples(): void {
    this.confirmLoadExamples = true;
    this.error = '';
    this.success = '';
  }

  cancelLoadExamples(): void {
    this.confirmLoadExamples = false;
  }

  loadExamples(): void {
    const existingKeys = new Set(this.fragments.map((f) => f.key.toLowerCase()));
    const toAdd = STARTER_FRAGMENTS.filter((f) => !existingKeys.has(f.key.toLowerCase()));
    if (toAdd.length === 0) {
      this.success = 'All example fragments already exist.';
      this.confirmLoadExamples = false;
      return;
    }
    this.loadingExamples = true;
    let remaining = toAdd.length;
    let failed = 0;
    const finish = () => {
      this.loadingExamples = false;
      this.confirmLoadExamples = false;
      this.finishBulk(toAdd.length - failed, failed, 'loaded');
    };
    toAdd.forEach((f) => {
      this.svc.save(f).subscribe({
        next: () => {
          remaining--;
          if (remaining === 0) finish();
        },
        error: () => {
          failed++;
          remaining--;
          if (remaining === 0) finish();
        },
      });
    });
  }

  exportFragments(): void {
    const json = JSON.stringify(this.fragments, null, 2);
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    const date = new Date().toISOString().slice(0, 10);
    a.href = url;
    a.download = `datris-tap-prompts-${date}.json`;
    a.click();
    URL.revokeObjectURL(url);
  }

  onImportFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files && input.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      let parsed: unknown;
      try {
        parsed = JSON.parse(reader.result as string);
      } catch {
        this.error = 'Import failed: file is not valid JSON.';
        input.value = '';
        return;
      }
      if (!Array.isArray(parsed)) {
        this.error = 'Import failed: JSON must be an array of fragments.';
        input.value = '';
        return;
      }
      const valid = parsed.filter(
        (f: any) => f && typeof f.key === 'string' && typeof f.content === 'string'
      ) as TapPromptFragment[];
      const skipped = parsed.length - valid.length;
      if (valid.length === 0) {
        this.error = 'Import failed: no valid fragments found.';
        input.value = '';
        return;
      }
      let remaining = valid.length;
      let failed = 0;
      valid.forEach((f) => {
        this.svc.save(f).subscribe({
          next: () => {
            remaining--;
            if (remaining === 0) this.finishBulk(valid.length - failed, failed + skipped, 'imported');
          },
          error: () => {
            failed++;
            remaining--;
            if (remaining === 0) this.finishBulk(valid.length - failed, failed + skipped, 'imported');
          },
        });
      });
    };
    reader.readAsText(file);
    input.value = '';
  }

  triggerImport(): void {
    const input = document.getElementById('tap-prompt-import-input') as HTMLInputElement | null;
    if (input) input.click();
  }

  get contentLength(): number {
    return (this.form.content || '').length;
  }

  aliasesPreview(aliases: string[] | null | undefined): string {
    const list = aliases || [];
    if (list.length === 0) return '—';
    const joined = list.join(', ');
    return joined.length > 60 ? joined.slice(0, 60) + '…' : joined;
  }

  contentPreview(content: string | null | undefined): string {
    const text = (content || '').trim();
    if (text.length === 0) return '';
    const firstLine = text.split('\n')[0];
    return firstLine.length > 80 ? firstLine.slice(0, 80) + '…' : firstLine;
  }

  private finishBulk(ok: number, skipped: number, verb: string): void {
    const parts = [`${ok} ${verb}`];
    if (skipped > 0) parts.push(`${skipped} skipped`);
    this.success = parts.join(', ') + '.';
    this.reload();
  }

  private emptyForm(): FragmentForm {
    return { key: '', aliases: '', content: '', enabled: true, originalKey: null };
  }
}
