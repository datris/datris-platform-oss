import { Component, OnInit, OnDestroy, ViewChild, ElementRef } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { TapService } from '../tap.service';
import { SecretsService } from '../secrets.service';

@Component({
  selector: 'app-tap-create',
  templateUrl: './tap-create.component.html',
  styleUrls: ['./tap-create.component.css']
})
export class TapCreateComponent implements OnInit, OnDestroy {
  isEditMode = false;
  step = 1;
  error = '';

  // Step 1 — Describe
  tapName = '';
  description = '';
  secretName = '';
  availableSecrets: string[] = [];
  showCreateSecret = false;
  editingSecret = false;
  newSecretName = '';
  newSecretFields: {key: string, value: string}[] = [{key: '', value: ''}];
  savingSecret = false;

  // Step 1 — Brainstorm chat
  brainstormMessages: Array<{role: string, content: string}> = [];
  brainstormInput = '';
  brainstorming = false;
  suggestedEnvVars: string[] = [];
  @ViewChild('brainstormInputEl') brainstormInputEl?: ElementRef<HTMLInputElement>;

  // Step 2 — Generate
  generating = false;
  script = '';
  scriptPath = '';
  packages: string[] = [];

  // Step 3 — Edit & Test
  testing = false;
  testRecords: any = null;
  testRecordCount = 0;
  testError = '';
  testLogs = '';
  testDataType = '';
  testColumns: string[] = [];
  aiExplanation = '';
  applyingDiagnosis = false;
  diagnosisApplied = false;
  scriptDirty = false;

  // Step 4 — Schedule
  useSchedule = false;
  cronExpression = '';
  cronPreset = 'custom';
  cronPrompt = '';
  generatingCron = false;
  enabled = true;

  // Step 5 — Save
  saving = false;

  // Active subscription for cancellation
  private activeSub: Subscription | null = null;

  constructor(private tapService: TapService, private secretsService: SecretsService, private router: Router, private route: ActivatedRoute) { }

  ngOnInit(): void {
    const name = this.route.snapshot.paramMap.get('name');
    if (name) {
      this.isEditMode = true;
      this.tapService.getTap(name).subscribe({
        next: (tap) => {
          this.tapName = tap.name || '';
          this.description = tap.description || '';
          this.scriptPath = tap.scriptPath || '';
          this.packages = tap.packages || [];
          this.cronExpression = tap.cronExpression || '';
          this.enabled = tap.enabled !== false;
          if (this.cronExpression) {
            this.useSchedule = true;
            if (!['0 0 * * * ?', '0 0 0 * * ?', '0 0 0 ? * MON'].includes(this.cronExpression)) {
              this.cronPreset = 'custom';
            }
          }
          this.script = tap.script || '';
          this.secretName = tap.secretName || '';
        },
        error: () => { this.error = 'Failed to load tap'; }
      });
    }

    // Load tap secrets only
    this.secretsService.listSecrets('tap').subscribe({
      next: (secrets) => this.availableSecrets = secrets || [],
      error: () => {}
    });
  }

  ngOnDestroy(): void {
    this.cancelActive();
  }

  isBusy(): boolean {
    return this.generating || this.testing || this.applyingDiagnosis;
  }

  testPassed(): boolean {
    return this.testRecordCount > 0 && !this.testError;
  }

  private cancelActive(): void {
    if (this.activeSub) {
      this.activeSub.unsubscribe();
      this.activeSub = null;
    }
    this.generating = false;
    this.testing = false;
    this.applyingDiagnosis = false;
    this.brainstorming = false;
  }

  nextStep(): void {
    this.error = '';
    if (this.step === 1) {
      if (!this.tapName.trim()) { this.error = 'Tap name is required'; return; }
      if (!this.description.trim()) { this.error = 'Instruction is required'; return; }
      if (!this.script) { this.error = 'Generate a script first'; return; }
    }
    if (this.step === 2 && this.scriptDirty && !this.testPassed()) {
      this.error = 'Test the script successfully before continuing';
      return;
    }
    this.step++;
  }

  prevStep(): void {
    this.cancelActive();
    if (this.step > 1) this.step--;
    this.error = '';
  }

  sendBrainstorm(): void {
    if (!this.brainstormInput.trim() || this.brainstorming) return;
    const userMsg = { role: 'user', content: this.brainstormInput.trim() };
    this.brainstormMessages.push(userMsg);
    this.brainstormInput = '';
    this.brainstorming = true;
    this.error = '';
    this.activeSub = this.tapService.brainstorm(this.brainstormMessages, this.description).subscribe({
      next: (result) => {
        this.brainstormMessages.push({ role: 'assistant', content: result.reply || '' });
        if (result.description) this.description = result.description;
        if (Array.isArray(result.suggestedEnvVars)) this.suggestedEnvVars = result.suggestedEnvVars;
        this.brainstorming = false;
        // Return focus to the input so the user can keep chatting without grabbing the mouse
        setTimeout(() => this.brainstormInputEl?.nativeElement.focus(), 0);
      },
      error: (err) => {
        this.error = 'Brainstorm failed: ' + (err.error || err.message);
        this.brainstorming = false;
      }
    });
  }

  generateScript(): void {
    this.generating = true;
    this.error = '';
    this.activeSub = this.tapService.generateScript(this.description, this.tapName.trim(), this.scriptPath, this.secretName).subscribe({
      next: (result) => {
        this.script = result.script || '';
        this.scriptPath = result.scriptPath || '';
        this.packages = result.packages || [];
        this.generating = false;
        this.scriptDirty = true;
      },
      error: (err) => {
        this.error = 'Generation failed: ' + (err.error || err.message);
        this.generating = false;
      }
    });
  }

  regenerateScript(): void {
    this.testRecords = null;
    this.testRecordCount = 0;
    this.testError = '';
    this.testLogs = '';
    this.aiExplanation = '';
    this.generateScript();
  }

  testScript(): void {
    this.testing = true;
    this.testError = '';
    this.testLogs = '';
    this.aiExplanation = '';
    this.diagnosisApplied = false;
    this.testRecords = [];
    this.testRecordCount = 0;

    const config = {
      name: this.tapName.trim(),
      description: this.description,
      scriptPath: this.scriptPath,
      packages: this.packages.length > 0 ? this.packages : null,
      secretName: this.secretName || null
    };

    this.activeSub = this.tapService.testTap(config).subscribe({
      next: (result) => {
        this.testRecords = result.records || [];
        this.testRecordCount = result.recordCount || 0;
        this.testError = result.error || '';
        this.testLogs = result.logs || '';
        this.testDataType = result.dataType || '';
        this.testColumns = result.columns || [];
        this.aiExplanation = result.aiExplanation || '';
        this.testing = false;
        if (this.testRecordCount > 0 && !this.testError) {
          this.scriptDirty = false;
        }
      },
      error: (err) => {
        const msg = typeof err.error === 'string' ? err.error : (err.message || 'Unknown error');
        if (msg.includes('timed out')) {
          this.testError = 'Script timed out (5 minute limit). Try fetching less data for testing.';
        } else {
          this.testError = 'Test failed: ' + msg.substring(0, 500);
        }
        this.testing = false;
      }
    });
  }

  applyDiagnosis(): void {
    this.applyingDiagnosis = true;
    this.error = '';
    this.activeSub = this.tapService.fixScript(
      this.tapName.trim(),
      this.script,
      this.aiExplanation,
      this.testLogs,
      this.testError,
      this.scriptPath
    ).subscribe({
      next: (result) => {
        this.script = result.script || this.script;
        this.scriptPath = result.scriptPath || this.scriptPath;
        this.packages = result.packages || this.packages;
        this.aiExplanation = '';
        this.testError = '';
        this.testLogs = '';
        this.testRecords = null;
        this.testRecordCount = 0;
        this.applyingDiagnosis = false;
        this.diagnosisApplied = true;
        this.scriptDirty = true;
      },
      error: (err) => {
        this.error = 'Fix failed: ' + (typeof err.error === 'string' ? err.error : err.message).substring(0, 300);
        this.applyingDiagnosis = false;
      }
    });
  }

  useSuggestedEnvVars(): void {
    if (this.suggestedEnvVars.length === 0) return;
    this.showCreateSecret = true;
    this.editingSecret = false;
    this.secretName = '';
    this.newSecretFields = this.suggestedEnvVars.map(k => ({key: k, value: ''}));
  }

  onSecretChange(value: string): void {
    this.editingSecret = false;
    if (value === '__create__') {
      this.showCreateSecret = true;
      this.secretName = '';
    } else {
      this.showCreateSecret = false;
      this.secretName = value;
    }
  }

  editSecret(): void {
    this.editingSecret = true;
    this.showCreateSecret = false;
    this.newSecretFields = [{key: '', value: ''}];
    this.secretsService.getSecret(this.secretName).subscribe({
      next: (data) => {
        const fields = data.fields || {};
        this.newSecretFields = Object.entries(fields)
          .filter(([k]) => k !== '_type')
          .map(([k, v]) => ({key: k, value: v as string}));
        if (this.newSecretFields.length === 0) {
          this.newSecretFields = [{key: '', value: ''}];
        }
      },
      error: () => { this.error = 'Failed to load secret'; this.editingSecret = false; }
    });
  }

  addSecretField(): void {
    this.newSecretFields.push({key: '', value: ''});
  }

  removeSecretField(index: number): void {
    this.newSecretFields.splice(index, 1);
  }

  saveNewSecret(): void {
    const name = this.editingSecret ? this.secretName : this.newSecretName.trim();
    if (!name) { this.error = 'Secret name is required'; return; }
    const fields: Record<string, string> = {};
    this.newSecretFields.filter(f => f.key.trim()).forEach(f => fields[f.key.trim()] = f.value);
    if (Object.keys(fields).length === 0) { this.error = 'At least one key-value pair is required'; return; }
    // Auto-tag as tap secret
    fields['_type'] = 'tap';

    this.savingSecret = true;
    this.error = '';
    this.secretsService.putSecret(name, fields).subscribe({
      next: () => {
        this.secretName = name;
        if (!this.availableSecrets.includes(name)) {
          this.availableSecrets.push(name);
          this.availableSecrets.sort();
        }
        this.showCreateSecret = false;
        this.editingSecret = false;
        this.newSecretName = '';
        this.newSecretFields = [{key: '', value: ''}];
        this.savingSecret = false;
      },
      error: (err) => {
        this.error = 'Failed to save secret: ' + (typeof err.error === 'string' ? err.error : err.message).substring(0, 200);
        this.savingSecret = false;
      }
    });
  }

  cancelCreateSecret(): void {
    this.showCreateSecret = false;
    this.editingSecret = false;
    if (!this.editingSecret) this.secretName = '';
    this.newSecretName = '';
    this.newSecretFields = [{key: '', value: ''}];
  }

  generateCron(): void {
    this.generatingCron = true;
    this.error = '';
    this.tapService.generateCron(this.cronPrompt).subscribe({
      next: (result) => {
        this.cronExpression = result.cronExpression || '';
        this.generatingCron = false;
      },
      error: (err) => {
        this.error = 'Failed to generate CRON: ' + (typeof err.error === 'string' ? err.error : err.message).substring(0, 200);
        this.generatingCron = false;
      }
    });
  }

  setCronPreset(preset: string): void {
    this.cronPreset = preset;
    switch (preset) {
      case 'hourly': this.cronExpression = '0 0 * * * ?'; break;
      case 'daily': this.cronExpression = '0 0 0 * * ?'; break;
      case 'weekly': this.cronExpression = '0 0 0 ? * MON'; break;
      case 'custom': this.cronExpression = ''; break;
    }
  }

  describeCron(expr: string): string {
    if (!expr || !expr.trim()) return '';
    const parts = expr.trim().split(/\s+/);
    if (parts.length < 6) return expr;

    const [sec, min, hour, dom, mon, dow] = parts;

    // Common presets
    if (sec === '0' && min === '0' && hour === '0' && dom === '*' && mon === '*' && dow === '?')
      return 'Every day at midnight';
    if (sec === '0' && min === '0' && hour === '*' && dom === '*' && mon === '*' && dow === '?')
      return 'Every hour';
    if (sec === '0' && min === '0' && hour === '0' && dom === '?' && mon === '*' && dow === 'MON')
      return 'Every Monday at midnight';

    // Build a readable description
    const describePart = (val: string, unit: string): string => {
      if (val === '*' || val === '?') return '';
      if (val.includes('/')) {
        const [, interval] = val.split('/');
        return `every ${interval} ${unit}${parseInt(interval) > 1 ? 's' : ''}`;
      }
      if (val.includes(',')) return `${unit}s ${val}`;
      return `${unit} ${val}`;
    };

    const pieces: string[] = [];
    const hourDesc = describePart(hour, 'hour');
    const minDesc = describePart(min, 'minute');
    const domDesc = describePart(dom, 'day');
    const monDesc = describePart(mon, 'month');
    const dowDesc = dow !== '?' && dow !== '*' ? `on ${dow}` : '';

    if (hour !== '*' && hour !== '?' && !hour.includes('/')) {
      const h = parseInt(hour);
      const m = parseInt(min) || 0;
      const ampm = h >= 12 ? 'PM' : 'AM';
      const h12 = h === 0 ? 12 : h > 12 ? h - 12 : h;
      const mStr = m < 10 ? '0' + m : '' + m;
      pieces.push(`at ${h12}:${mStr} ${ampm}`);
    } else {
      if (hourDesc) pieces.push(hourDesc);
      if (minDesc) pieces.push(minDesc);
    }

    if (domDesc) pieces.push(domDesc);
    if (monDesc) pieces.push(monDesc);
    if (dowDesc) pieces.push(dowDesc);

    return pieces.length > 0 ? pieces.join(', ') : expr;
  }

  addPackage(): void {
    this.packages.push('');
  }

  removePackage(index: number): void {
    this.packages.splice(index, 1);
  }

  trackByIndex(index: number): number {
    return index;
  }

  getTestColumns(): string[] {
    if (!this.testRecords || this.testRecords.length === 0) return [];
    return Object.keys(this.testRecords[0]);
  }

  getPreviewRows(): any[] {
    if (!this.testRecords || !Array.isArray(this.testRecords)) return [];
    return this.testRecords.slice(0, 10);
  }

  save(): void {
    this.saving = true;
    this.error = '';

    const config: any = {
      name: this.tapName.trim(),
      description: this.description,
      scriptPath: this.scriptPath,
      packages: this.packages.filter(p => p.trim()).length > 0 ? this.packages.filter(p => p.trim()) : null,
      secretName: this.secretName || null,
      cronExpression: this.useSchedule && this.cronExpression ? this.cronExpression : null,
      enabled: this.enabled,
      lastTestRunDataType: this.testDataType || null,
      lastTestRunColumns: this.testColumns.length > 0 ? this.testColumns : null,
      lastTestRunRecordCount: this.testRecordCount || 0
    };

    this.tapService.createOrUpdateTap(config).subscribe({
      next: () => this.router.navigate(['/taps']),
      error: (err) => {
        this.error = 'Save failed: ' + (err.error || err.message);
        this.saving = false;
      }
    });
  }
}
