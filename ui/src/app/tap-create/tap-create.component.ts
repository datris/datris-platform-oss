import { Component, OnInit, OnDestroy, ViewChild, ElementRef } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
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
  catalog = '';
  availableCatalogs: string[] = [];
  showNewCatalog = false;
  newCatalogName = '';
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

  // Step 4 — Pipeline link (attach to existing or generate new)
  targetPipeline = '';
  showAttachModal = false;
  loadingPipelines = false;
  availablePipelines: any[] = [];
  selectedPipelineName = '';
  columnMatchResult: { match: boolean; missing: string[]; extra: string[] } | null = null;

  showGenerateModal = false;
  generatedPipelineName = '';
  generatingPipeline = false;
  generatedFields: Array<{name: string, type: string}> = [];
  generateError = '';
  generatedTruncate = false;

  // Save
  saving = false;

  // Step 5 — Run (only reachable when targetPipeline is set)
  runningTap = false;
  runError = '';

  // Active subscription for cancellation
  private activeSub: Subscription | null = null;

  constructor(private tapService: TapService, private pipelineService: PipelineService, private secretsService: SecretsService, private router: Router, private route: ActivatedRoute) { }

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
          this.catalog = tap.catalog || '';
          this.targetPipeline = tap.targetPipeline || '';
        },
        error: () => { this.error = 'Failed to load tap'; }
      });
    }

    // Load available catalogs
    this.tapService.getTaps().subscribe({
      next: (taps) => {
        const cats = new Set<string>();
        (taps || []).forEach((t: any) => { if (t.catalog) cats.add(t.catalog); });
        this.availableCatalogs = Array.from(cats).sort();
      },
      error: () => {}
    });

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
    if (this.step === 3 && this.useSchedule) {
      const cronErr = this.validateCron(this.cronExpression);
      if (cronErr) { this.error = cronErr; return; }
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
        // Return focus to the input so the user can keep chatting without grabbing the mouse.
        // Use requestAnimationFrame so Angular has time to flip [disabled]="brainstorming"
        // back to false — focus() against a still-disabled input silently no-ops.
        requestAnimationFrame(() => this.brainstormInputEl?.nativeElement.focus());
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

  onCatalogChange(value: string): void {
    if (value === '__new__') {
      this.showNewCatalog = true;
      this.newCatalogName = '';
      this.catalog = '';
    } else {
      this.showNewCatalog = false;
    }
  }

  private sanitizeName(name: string): string {
    return name.toLowerCase().trim()
      .replace(/\s+/g, '_').replace(/[^a-z0-9_]/g, '')
      .replace(/_+/g, '_').replace(/^_|_$/g, '');
  }

  confirmNewCatalog(): void {
    const name = this.sanitizeName(this.newCatalogName);
    if (!name) return;
    this.catalog = name;
    if (!this.availableCatalogs.includes(name)) {
      this.availableCatalogs.push(name);
      this.availableCatalogs.sort();
    }
    this.showNewCatalog = false;
    this.newCatalogName = '';
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
      case 'weekdays': this.cronExpression = '0 0 0 ? * MON-FRI'; break;
      case 'weekly': this.cronExpression = '0 0 0 ? * MON'; break;
      case 'custom': this.cronExpression = ''; break;
    }
  }

  /**
   * Validate a Quartz CRON expression. Returns null if valid, or an error string.
   * Quartz format: seconds minutes hours day-of-month month day-of-week [year]
   * - 6 or 7 space-separated fields
   * - day-of-month and day-of-week: exactly one must be '?' (Quartz disallows specifying both)
   * - Each field must contain only allowed characters and (where applicable) numeric values in range
   */
  validateCron(expr: string): string | null {
    if (!expr || !expr.trim()) return 'CRON expression is required';
    const parts = expr.trim().split(/\s+/);
    if (parts.length < 6 || parts.length > 7) return 'CRON must have 6 or 7 fields (got ' + parts.length + ')';

    const [sec, min, hour, dom, mon, dow] = parts;

    // Quartz: exactly one of day-of-month / day-of-week must be '?'
    const domQ = dom === '?';
    const dowQ = dow === '?';
    if (domQ === dowQ) return "Exactly one of day-of-month or day-of-week must be '?' (Quartz rule)";

    // Per-field range checks (only for plain numeric values; allow * , - / ? L W # and named values)
    const ranges: Array<[string, string, number, number]> = [
      ['second', sec, 0, 59],
      ['minute', min, 0, 59],
      ['hour', hour, 0, 23],
      ['day-of-month', dom, 1, 31],
      ['month', mon, 1, 12],
      ['day-of-week', dow, 1, 7],
    ];
    const allowedChars = /^[0-9*?,\-/LW#A-Z]+$/i;
    for (const [name, val, lo, hi] of ranges) {
      if (!allowedChars.test(val)) return "Invalid characters in " + name + " field: '" + val + "'";
      // Extract any plain integers and bounds-check them
      const nums = val.match(/\d+/g);
      if (nums) {
        for (const n of nums) {
          const v = parseInt(n, 10);
          if (v < lo || v > hi) return name + " value " + v + " out of range (" + lo + "-" + hi + ")";
        }
      }
    }
    return null;
  }

  cronError(): string {
    if (!this.useSchedule) return '';
    return this.validateCron(this.cronExpression) || '';
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
      targetPipeline: this.targetPipeline || null,
      cronExpression: this.useSchedule && this.cronExpression ? this.cronExpression : null,
      enabled: this.enabled,
      lastTestRunDataType: this.testDataType || null,
      lastTestRunColumns: this.testColumns.length > 0 ? this.testColumns : null,
      lastTestRunRecordCount: this.testRecordCount || 0,
      catalog: this.catalog || null
    };

    this.tapService.createOrUpdateTap(config).subscribe({
      next: () => {
        this.saving = false;
        // If a pipeline is linked, advance to step 5 so the user can optionally
        // run the tap and push data to the pipeline before leaving. Otherwise
        // there's nothing meaningful to do on step 5 — go straight to /taps.
        if (this.targetPipeline) {
          this.step = 5;
        } else {
          this.router.navigate(['/taps']);
        }
      },
      error: (err) => {
        this.error = 'Save failed: ' + (err.error || err.message);
        this.saving = false;
      }
    });
  }

  // ---------- Step 5 — Run the tap ----------

  runTapNow(): void {
    if (this.runningTap) return;
    this.runError = '';
    this.runningTap = true;
    this.tapService.runTap(this.tapName.trim(), true).subscribe({
      next: () => {
        this.runningTap = false;
        this.router.navigate(['/taps']);
      },
      error: (err) => {
        this.runError = 'Run failed: ' + (err.error || err.message || 'unknown');
        this.runningTap = false;
      }
    });
  }

  finishWithoutRun(): void {
    this.router.navigate(['/taps']);
  }

  // ---------- Pipeline link: Attach to existing ----------

  openAttachModal(): void {
    this.error = '';
    this.selectedPipelineName = '';
    this.columnMatchResult = null;
    this.showAttachModal = true;
    this.loadingPipelines = true;
    this.pipelineService.getPipelines().subscribe({
      next: (pipelines) => {
        this.availablePipelines = pipelines || [];
        this.loadingPipelines = false;
      },
      error: () => {
        this.error = 'Failed to load pipelines';
        this.loadingPipelines = false;
      }
    });
  }

  closeAttachModal(): void {
    this.showAttachModal = false;
  }

  onPipelineSelected(): void {
    const pipeline = this.availablePipelines.find(p => p.name === this.selectedPipelineName);
    if (!pipeline) { this.columnMatchResult = null; return; }

    const pipelineFields: string[] = (pipeline.source && pipeline.source.schemaProperties && pipeline.source.schemaProperties.fields || [])
      .map((f: any) => f.name);
    const tapCols = this.testColumns || [];
    const missing = pipelineFields.filter(f => !tapCols.includes(f));
    const extra = tapCols.filter(c => !pipelineFields.includes(c));
    this.columnMatchResult = {
      match: missing.length === 0 && extra.length === 0,
      missing,
      extra
    };
  }

  confirmAttach(): void {
    if (!this.columnMatchResult || !this.columnMatchResult.match) return;
    this.targetPipeline = this.selectedPipelineName;
    this.showAttachModal = false;
  }

  detachPipeline(): void {
    this.targetPipeline = '';
  }

  // ---------- Pipeline link: Generate new ----------

  openGenerateModal(): void {
    this.error = '';
    this.generateError = '';
    this.generatedPipelineName = this.derivePipelineName(this.tapName);
    this.generatedFields = [];
    this.showGenerateModal = true;
  }

  closeGenerateModal(): void {
    if (this.generatingPipeline) return;
    this.showGenerateModal = false;
  }

  derivePipelineName(tapName: string): string {
    let base = (tapName || '').trim().toLowerCase();
    if (base.endsWith('-tap')) base = base.substring(0, base.length - 4);
    return base;
  }

  /** Map dataType → destination table name (SQL-safe: dashes → underscores). */
  private derivedTableName(): string {
    return this.generatedPipelineName.replace(/-/g, '_');
  }

  destinationDescription(): string {
    const t = (this.testDataType || 'json').toLowerCase();
    const name = this.derivedTableName();
    if (t === 'csv') return 'Postgres → public.' + name;
    return 'MongoDB → collection ' + name;
  }

  confirmGenerate(): void {
    if (this.generatingPipeline) return;
    this.generateError = '';
    this.generatingPipeline = true;

    // All-string schema by default. Tap output shape is unstable across runs
    // (yfinance NaN-contamination promotes int columns to float, REST APIs
    // change types between calls, etc.) — inferring narrow types from a small
    // test sample produces hard-to-debug COPY failures in production. Storing
    // everything as text guarantees the data lands identical to what the tap
    // returned. Users can promote individual columns to richer types in the
    // pipeline editor once they're confident about the data shape.
    const fields = (this.testColumns || []).map(name => ({ name, type: 'string' }));
    this.generatedFields = fields;
    const config = this.buildPipelineConfigFromTap(fields);
    this.pipelineService.createPipeline(config).subscribe({
      next: () => {
        this.targetPipeline = this.generatedPipelineName;
        this.generatingPipeline = false;
        this.showGenerateModal = false;
      },
      error: (err) => {
        this.generateError = 'Pipeline create failed: ' + (err.error || err.message || 'unknown');
        this.generatingPipeline = false;
      }
    });
  }

  private buildPipelineConfigFromTap(fields: Array<{name: string, type: string}>): any {
    const dataType = (this.testDataType || 'json').toLowerCase();
    const tableName = this.derivedTableName();

    const source: any = {
      schemaProperties: {
        fields: fields
      },
      fileAttributes: {} as any
    };

    if (dataType === 'csv') {
      source.fileAttributes.csvAttributes = { delimiter: ',', header: true, encoding: 'UTF-8' };
    } else if (dataType === 'xml') {
      source.fileAttributes.xmlAttributes = { everyRowContainsObject: false, encoding: 'UTF-8' };
    } else {
      // json or text — store as json document
      source.fileAttributes.jsonAttributes = { everyRowContainsObject: false, encoding: 'UTF-8' };
    }

    const destination: any = { database: {} };
    if (dataType === 'csv') {
      destination.database = {
        dbName: 'DATABASE_NAME',
        schema: 'public',
        table: tableName,
        usePostgres: true,
        truncateBeforeWrite: this.generatedTruncate
      };
    } else {
      destination.database = {
        dbName: 'DATABASE_NAME',
        table: tableName,
        useMongoDB: true,
        truncateBeforeWrite: this.generatedTruncate
      };
    }

    return {
      name: this.generatedPipelineName,
      source,
      destination
    };
  }

}
