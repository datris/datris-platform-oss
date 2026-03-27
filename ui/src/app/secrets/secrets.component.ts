import { Component, OnInit } from '@angular/core';
import { SecretsService } from '../secrets.service';

interface SecretField {
  key: string;
  value: string;
}

@Component({
  selector: 'app-secrets',
  templateUrl: './secrets.component.html',
  styleUrls: ['./secrets.component.css']
})
export class SecretsComponent implements OnInit {
  // List view
  secretNames: string[] = [];
  loading = false;
  error = '';

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

  // Delete
  confirmDelete = false;
  deleteLoading = false;

  constructor(private secretsService: SecretsService) { }

  ngOnInit(): void {
    this.loadSecrets();
  }

  loadSecrets(): void {
    this.loading = true;
    this.error = '';
    this.secretsService.listSecrets().subscribe({
      next: (names) => {
        this.secretNames = names;
        this.loading = false;
      },
      error: (err) => {
        this.error = err.error || err.message || 'Failed to load secrets';
        this.loading = false;
      }
    });
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
    // Copy current fields — sensitive values show as masked, user re-enters them
    this.editFields = this.secretFields.map(f => ({
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

    this.createError = '';
    this.secretsService.putSecret(this.newSecretName.trim(), fields).subscribe({
      next: () => {
        this.creating = false;
        this.loadSecrets();
      },
      error: (err) => {
        this.createError = err.error || err.message || 'Failed to create';
      }
    });
  }
}
