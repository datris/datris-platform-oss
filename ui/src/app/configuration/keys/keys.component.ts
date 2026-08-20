import { Component, OnInit } from '@angular/core';
import {
  KeysService,
  KeyRow,
  IssueKeyResponse,
  RotateKeyResponse,
  CapabilityResource,
  KeyTemplate
} from './keys.service';

/** Admin-only Keys tab inside Configuration. Lists every API key the platform
 *  recognizes, lets the operator issue scoped keys, rotate values, and revoke.
 *
 *  Values are server-generated and shown ONCE in a modal at issue / rotate
 *  time — same UX as Stripe restricted keys and GitHub fine-grained PATs.
 *  Listing this view never returns key values. */
@Component({
    selector: 'app-keys',
    templateUrl: './keys.component.html',
    styleUrl: './keys.component.css',
    standalone: false
})
export class KeysComponent implements OnInit {
  keys: KeyRow[] = [];
  loading = false;
  error = '';

  // Capability metadata for the issue wizard.
  capabilityCatalog: CapabilityResource[] = [];
  templates: KeyTemplate[] = [];

  // Issue wizard state.
  showWizard = false;
  wizardStep: 1 | 2 = 1;
  wizardLabel = '';
  wizardTemplate = '';                 // selected template name, '' for blank
  wizardCapabilities: string[] = [];   // one capability string per row
  wizardError = '';
  wizardSubmitting = false;

  // Show-once modal — appears after issue or rotate. The value is in plaintext
  // here, then disappears as soon as the modal is dismissed.
  shownKey: { label: string; value: string; isNew: boolean } | null = null;
  copied = false;

  // Revoke confirmation modal.
  revokeTarget: KeyRow | null = null;
  revokeBusy = false;

  constructor(private keysService: KeysService) {}

  ngOnInit(): void {
    this.refresh();
    this.keysService.capabilities().subscribe({
      next: (resp) => { this.capabilityCatalog = resp.resources; },
      error: () => { /* non-fatal — wizard falls back to free text */ }
    });
    this.keysService.templates().subscribe({
      next: (resp) => { this.templates = resp.templates; },
      error: () => { /* non-fatal — blank template is still available */ }
    });
  }

  // ------- list -------

  refresh(): void {
    this.loading = true;
    this.error = '';
    this.keysService.list().subscribe({
      next: (resp) => { this.keys = resp.keys || []; this.loading = false; },
      error: (err) => {
        this.error = err?.error?.error || 'Failed to load keys';
        this.loading = false;
      }
    });
  }

  hasLegacyKeys(): boolean {
    return this.keys.some(k => k.isLegacyFullAccess && !k.revoked);
  }

  // ------- issue wizard -------

  openWizard(): void {
    this.showWizard = true;
    this.wizardStep = 1;
    this.wizardLabel = '';
    this.wizardTemplate = '';
    this.wizardCapabilities = [];
    this.wizardError = '';
  }

  closeWizard(): void {
    this.showWizard = false;
  }

  applyTemplate(name: string): void {
    this.wizardTemplate = name;
    if (!name) {
      this.wizardCapabilities = [];
      return;
    }
    const t = this.templates.find(x => x.name === name);
    if (t) this.wizardCapabilities = [...t.capabilities];
  }

  addCapabilityRow(): void {
    this.wizardCapabilities = [...this.wizardCapabilities, ''];
  }

  updateCapabilityRow(index: number, value: string): void {
    this.wizardCapabilities = this.wizardCapabilities.map((c, i) => i === index ? value : c);
  }

  removeCapabilityRow(index: number): void {
    this.wizardCapabilities = this.wizardCapabilities.filter((_, i) => i !== index);
  }

  goToReview(): void {
    this.wizardError = '';
    const labelOk = /^[a-z0-9_-]{1,64}$/.test(this.wizardLabel);
    if (!labelOk) {
      this.wizardError = 'Label must be 1-64 chars of lowercase letters, digits, dash, underscore';
      return;
    }
    if (this.keys.some(k => k.label === this.wizardLabel && !k.revoked)) {
      this.wizardError = "A key with label '" + this.wizardLabel + "' already exists";
      return;
    }
    const cleaned = this.wizardCapabilities.map(c => c.trim()).filter(c => c.length > 0);
    if (cleaned.length === 0) {
      this.wizardError = 'At least one capability is required';
      return;
    }
    this.wizardCapabilities = cleaned;
    this.wizardStep = 2;
  }

  submitIssue(): void {
    this.wizardError = '';
    this.wizardSubmitting = true;
    this.keysService.issue(this.wizardLabel, this.wizardCapabilities).subscribe({
      next: (resp: IssueKeyResponse) => {
        this.wizardSubmitting = false;
        this.showWizard = false;
        this.shownKey = { label: resp.label, value: resp.value, isNew: true };
        this.copied = false;
        this.refresh();
      },
      error: (err) => {
        this.wizardSubmitting = false;
        this.wizardError = err?.error?.error || 'Failed to issue key';
      }
    });
  }

  // ------- rotate -------

  rotate(row: KeyRow): void {
    if (!confirm("Rotate '" + row.label + "'?\n\nThe current value will stop working immediately. Anyone using it will need the new value.")) {
      return;
    }
    this.keysService.rotate(row.label).subscribe({
      next: (resp: RotateKeyResponse) => {
        this.shownKey = { label: resp.label, value: resp.value, isNew: false };
        this.copied = false;
        this.refresh();
      },
      error: (err) => {
        this.error = err?.error?.error || 'Failed to rotate key';
      }
    });
  }

  // ------- revoke -------

  askRevoke(row: KeyRow): void {
    this.revokeTarget = row;
  }

  cancelRevoke(): void {
    this.revokeTarget = null;
  }

  confirmRevoke(): void {
    if (!this.revokeTarget) return;
    const label = this.revokeTarget.label;
    this.revokeBusy = true;
    this.keysService.revoke(label).subscribe({
      next: () => {
        this.revokeBusy = false;
        this.revokeTarget = null;
        this.refresh();
      },
      error: (err) => {
        this.revokeBusy = false;
        this.error = err?.error?.error || 'Failed to revoke key';
      }
    });
  }

  // ------- show-once modal helpers -------

  dismissShownKey(): void {
    this.shownKey = null;
    this.copied = false;
  }

  copyValue(): void {
    if (!this.shownKey) return;
    navigator.clipboard.writeText(this.shownKey.value).then(() => {
      this.copied = true;
      setTimeout(() => { this.copied = false; }, 2000);
    });
  }

  // ------- formatting -------

  statusLabel(row: KeyRow): string {
    if (row.revoked) return 'revoked';
    if (row.isLegacyFullAccess) return 'legacy';
    return 'active';
  }

  trackByLabel(_: number, row: KeyRow): string {
    return row.label;
  }

  trackByIndex(i: number): number {
    return i;
  }
}
