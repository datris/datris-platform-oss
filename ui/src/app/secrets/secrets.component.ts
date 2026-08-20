import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { SecretsService } from '../secrets.service';

interface SecretField {
  key: string;
  value: string;
}

@Component({
    selector: 'app-secrets',
    templateUrl: './secrets.component.html',
    styleUrls: ['./secrets.component.css'],
    standalone: false
})
export class SecretsComponent implements OnInit {
  // List view
  allNames: string[] = [];
  tapNames: string[] = [];
  activeTab: 'platform' | 'taps' = 'platform';
  loading = false;
  error = '';

  get platformNames(): string[] {
    const tapSet = new Set(this.tapNames);
    return this.allNames.filter(n => !tapSet.has(n));
  }

  get secretNames(): string[] {
    return this.activeTab === 'taps' ? this.tapNames : this.platformNames;
  }

  // Detail view
  selectedSecret = '';
  secretFields: SecretField[] = [];
  detailLoading = false;
  detailError = '';
  // Edit mode
  editing = false;
  editFields: SecretField[] = [];
  saveLoading = false;
  saveError = '';
  saveSuccess = false;

  // Create mode
  creating = false;
  newSecretName = '';
  newFields: SecretField[] = [{ key: '', value: '' }];
  createError = '';
  createdMessage = '';     // "Created tap secret 'stripe-key'"
  createdName = '';        // name of the just-created secret, used to auto-open its detail panel

  // Delete
  confirmDelete = false;
  deleteLoading = false;

  isTrial = false;

  constructor(private secretsService: SecretsService, private http: HttpClient) { }

  ngOnInit(): void {
    this.http.get<any>('/api/v1/version').subscribe({
      next: (data) => { this.isTrial = data.multiTenant === 'true'; }
    });
    this.loadSecrets();
  }

  loadSecrets(): void {
    this.loading = true;
    this.error = '';
    let allDone = false, tapDone = false;
    const finish = () => { if (allDone && tapDone) this.loading = false; };
    this.secretsService.listSecrets().subscribe({
      next: (names) => { this.allNames = names || []; allDone = true; finish(); },
      error: (err) => {
        this.error = err.error || err.message || 'Failed to load secrets';
        allDone = true; finish();
      }
    });
    this.secretsService.listSecrets('tap').subscribe({
      next: (names) => { this.tapNames = names || []; tapDone = true; finish(); },
      error: () => { this.tapNames = []; tapDone = true; finish(); }
    });
  }

  setTab(tab: 'platform' | 'taps'): void {
    if (this.activeTab === tab) return;
    this.activeTab = tab;
    this.selectedSecret = '';
    this.creating = false;
    this.editing = false;
    this.confirmDelete = false;
  }

  selectSecret(name: string): void {
    if (this.selectedSecret === name && !this.editing) {
      this.selectedSecret = '';
      return;
    }
    this.selectedSecret = name;
    this.editing = false;
    this.confirmDelete = false;
    this.saveError = '';
    this.saveSuccess = false;
    this.loadSecretDetail();
  }

  loadSecretDetail(): void {
    this.detailLoading = true;
    this.detailError = '';
    this.secretsService.getSecret(this.selectedSecret).subscribe({
      next: (res) => {
        this.secretFields = Object.entries(res.fields || {}).map(([key, value]) => ({
          key,
          value: value as string
        }));
        this.detailLoading = false;
      },
      error: (err) => {
        this.detailError = err.error || err.message || 'Failed to load secret';
        this.detailLoading = false;
      }
    });
  }

  // Edit
  startEdit(): void {
    this.editing = true;
    this.saveError = '';
    this.saveSuccess = false;
    // Copy current fields — sensitive values show as masked, user re-enters them.
    // Skip the bookkeeping `_type` field; saveSecret preserves it.
    this.editFields = this.secretFields
      .filter(f => f.key !== '_type')
      .map(f => ({
        key: f.key,
        value: f.value === '••••••••' ? '' : f.value
      }));
  }

  cancelEdit(): void {
    this.editing = false;
    this.saveError = '';
    this.loadSecretDetail();
  }

  addEditField(): void {
    this.editFields.push({ key: '', value: '' });
  }

  removeEditField(index: number): void {
    this.editFields.splice(index, 1);
  }

  saveSecret(): void {
    const fields: Record<string, string> = {};
    for (const f of this.editFields) {
      if (f.key.trim()) {
        fields[f.key.trim()] = f.value;
      }
    }
    if (Object.keys(fields).length === 0) {
      this.saveError = 'At least one field is required';
      return;
    }
    // Preserve the bookkeeping `_type` across edits so tap secrets don't
    // accidentally become platform secrets after being saved.
    const originalType = this.secretFields.find(f => f.key === '_type')?.value;
    if (originalType) fields['_type'] = originalType;

    this.saveLoading = true;
    this.saveError = '';
    this.secretsService.putSecret(this.selectedSecret, fields).subscribe({
      next: () => {
        this.saveLoading = false;
        this.saveSuccess = true;
        this.editing = false;
        this.loadSecretDetail();
        setTimeout(() => this.saveSuccess = false, 3000);
      },
      error: (err) => {
        this.saveError = err.error || err.message || 'Failed to save';
        this.saveLoading = false;
      }
    });
  }

  // Delete
  promptDelete(): void {
    this.confirmDelete = true;
  }

  cancelDelete(): void {
    this.confirmDelete = false;
  }

  doDelete(): void {
    this.deleteLoading = true;
    this.secretsService.deleteSecret(this.selectedSecret).subscribe({
      next: () => {
        this.deleteLoading = false;
        this.confirmDelete = false;
        this.selectedSecret = '';
        this.secretFields = [];
        this.loadSecrets();
      },
      error: (err) => {
        this.detailError = err.error || err.message || 'Failed to delete';
        this.deleteLoading = false;
        this.confirmDelete = false;
      }
    });
  }

  // Create
  showCreate(): void {
    this.creating = true;
    this.newSecretName = '';
    this.newFields = [{ key: '', value: '' }];
    this.createError = '';
    this.selectedSecret = '';
  }

  cancelCreate(): void {
    this.creating = false;
    this.createError = '';
  }

  addNewField(): void {
    this.newFields.push({ key: '', value: '' });
  }

  removeNewField(index: number): void {
    this.newFields.splice(index, 1);
  }

  createSecret(): void {
    if (!this.newSecretName.trim()) {
      this.createError = 'Secret name is required';
      return;
    }
    const fields: Record<string, string> = {};
    for (const f of this.newFields) {
      if (f.key.trim()) {
        fields[f.key.trim()] = f.value;
      }
    }
    if (Object.keys(fields).length === 0) {
      this.createError = 'At least one field is required';
      return;
    }
    // Tag with _type=tap when created from the Taps sub-tab so list/filter
    // and agent-ownership checks pick it up.
    if (this.activeTab === 'taps') fields['_type'] = 'tap';

    this.createError = '';
    const name = this.newSecretName.trim();
    const kind = this.activeTab === 'taps' ? 'tap' : 'platform';
    this.secretsService.putSecret(name, fields).subscribe({
      next: () => {
        this.creating = false;
        this.createdMessage = `Created ${kind} secret: ${name}`;
        this.createdName = name;
        this.loadSecrets();
        // Auto-open the detail panel for the new secret so the user can see it landed.
        this.selectedSecret = name;
        this.loadSecretDetail();
        // Clear the banner after a few seconds.
        setTimeout(() => {
          if (this.createdName === name) {
            this.createdMessage = '';
            this.createdName = '';
          }
        }, 4000);
      },
      error: (err) => {
        this.createError = err.error || err.message || 'Failed to create';
      }
    });
  }

  // Hide the `_type` bookkeeping field from the detail view — it's implementation
  // detail the user doesn't care about.
  displayedFields(): SecretField[] {
    return this.secretFields.filter(f => f.key !== '_type');
  }
}
