import { Component, OnInit, OnDestroy, ViewChild, ElementRef } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { SecretsService } from '../secrets.service';
import { SearchService } from '../search.service';
import { sanitizeLabel } from '../shared/sanitize';

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
  // Auto-fix: on a failed test with an AI diagnosis, automatically apply the
  // fix and re-test, up to MAX_AUTO_FIX_ATTEMPTS. Reset to 0 whenever the user
  // clicks Test Script themselves; increment each time we auto-apply.
  private static readonly MAX_AUTO_FIX_ATTEMPTS = 2;
  autoFixAttempts = 0;
  // Test-only sample cap. Cron/manual runs are always unlimited; this only
  // affects the test invocation. User-editable; defaults to 20.
  limitTestSample = true;
  testSampleLimit = 20;

  // Auto-optimize: after a test passes, send the working script back to the
  // LLM with timing context for a perf rewrite, then re-test. One pass per
  // successful test; user can Revert. Regression auto-reverts.
  private static readonly MAX_AUTO_OPTIMIZE_ATTEMPTS = 1;
  private static readonly OPTIMIZE_REGRESSION_THRESHOLD = 1.2;
  optimizing = false;
  optimizeAttempts = 0;
  optimizingSkipped = false;
  optimizeChanges: string[] = [];
  optimizeDurationMs = 0;
  optimizeRegressionReverted = false;
  private previousPassingTest: {
    script: string;
    scriptPath: string;
    packages: string[];
    testRecords: any;
    testRecordCount: number;
    testLogs: string;
    testDataType: string;
    testColumns: string[];
    durationMs: number;
  } | null = null;

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
  generatedDestName = '';
  existingDestNames: string[] = [];

  // Save
  saving = false;

  // Step 5 — Run (only reachable when targetPipeline is set)
  runningTap = false;
  runError = '';
  targetPipelineConfig: any = null;

  // Active subscription for cancellation
  private activeSub: Subscription | null = null;

  constructor(private tapService: TapService, private pipelineService: PipelineService, private secretsService: SecretsService, private searchService: SearchService, private router: Router, private route: ActivatedRoute) { }

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

  stopTest(): void {
    this.cancelActive();
    // Cap auto-fix so an in-flight fix-and-retry chain doesn't resume.
    this.autoFixAttempts = TapCreateComponent.MAX_AUTO_FIX_ATTEMPTS;
    this.testError = 'Test cancelled.';
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

  /** User-initiated test. Resets auto-fix and auto-optimize counters, then delegates. */
  runTest(): void {
    this.autoFixAttempts = 0;
    this.optimizeAttempts = 0;
    this.optimizingSkipped = false;
    this.optimizeChanges = [];
    this.optimizeDurationMs = 0;
    this.optimizeRegressionReverted = false;
    this.previousPassingTest = null;
    this.testScript();
  }

  testScript(): void {
    this.testing = true;
    this.testError = '';
    this.testLogs = '';
    this.aiExplanation = '';
    this.diagnosisApplied = false;
    this.testRecords = [];
    this.testRecordCount = 0;

    const config: any = {
      name: this.tapName.trim(),
      description: this.description,
      scriptPath: this.scriptPath,
      packages: this.packages.length > 0 ? this.packages : null,
      secretName: this.secretName || null
    };
    if (this.limitTestSample && this.testSampleLimit > 0) {
      config.testLimit = this.testSampleLimit;
    }

    this.activeSub = this.tapService.testTap(config).subscribe({
      next: (result) => {
        this.testRecords = result.records || [];
        this.testRecordCount = result.recordCount || 0;
        this.testError = result.error || '';
        this.testLogs = result.logs || '';
        this.testDataType = result.dataType || '';
        this.testColumns = (result.columns && result.columns.length > 0)
          ? result.columns
          : (this.testRecords.length > 0 ? Object.keys(this.testRecords[0]) : []);
        this.aiExplanation = result.aiExplanation || '';
        this.testing = false;
        const lastDurationMs = result.durationMs || 0;
        if (this.testRecordCount > 0 && !this.testError) {
          this.scriptDirty = false;
        }
        // Auto-fix: on a failed test with an actionable diagnosis, apply the
        // fix and re-test. Capped at MAX_AUTO_FIX_ATTEMPTS to avoid loops.
        const failed = !!this.testError || this.testRecordCount === 0;
        if (failed && this.aiExplanation && this.autoFixAttempts < TapCreateComponent.MAX_AUTO_FIX_ATTEMPTS) {
          this.autoFixAttempts++;
          this.applyDiagnosis();
          return;
        }
        // Regression check: if this test was triggered by an auto-optimize, the
        // previous passing snapshot is stored. If the optimized script is
        // materially slower, auto-revert.
        if (!failed && this.previousPassingTest &&
            lastDurationMs > this.previousPassingTest.durationMs * TapCreateComponent.OPTIMIZE_REGRESSION_THRESHOLD) {
          this.optimizeDurationMs = lastDurationMs;
          this.optimizeRegressionReverted = true;
          this.revertOptimization();
          return;
        }
        // Record the optimized run's duration for the banner
        if (!failed && this.previousPassingTest) {
          this.optimizeDurationMs = lastDurationMs;
        }
        // Auto-optimize: on a successful test, send the working script back
        // to the LLM for a perf rewrite, then re-test. One pass only.
        const succeeded = !failed;
        if (succeeded && this.optimizeAttempts < TapCreateComponent.MAX_AUTO_OPTIMIZE_ATTEMPTS &&
            !this.optimizingSkipped && !this.previousPassingTest) {
          this.previousPassingTest = {
            script: this.script,
            scriptPath: this.scriptPath,
            packages: [...this.packages],
            testRecords: this.testRecords,
            testRecordCount: this.testRecordCount,
            testLogs: this.testLogs,
            testDataType: this.testDataType,
            testColumns: [...this.testColumns],
            durationMs: lastDurationMs
          };
          this.optimizeAttempts++;
          this.optimizeScript(lastDurationMs);
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

  optimizeScript(previousDurationMs: number): void {
    this.optimizing = true;
    this.optimizeChanges = [];
    this.activeSub = this.tapService.optimizeScript(
      this.tapName.trim(),
      this.script,
      this.testRecordCount,
      previousDurationMs,
      this.testLogs,
      this.scriptPath
    ).subscribe({
      next: (result) => {
        const newScript = result.script || this.script;
        const changes: string[] = result.changes || [];
        // If the AI returned the same script or no changes, skip the re-test.
        if (changes.length === 0 || newScript === this.script) {
          this.optimizing = false;
          this.optimizingSkipped = true;
          this.previousPassingTest = null;
          return;
        }
        this.script = newScript;
        this.scriptPath = result.scriptPath || this.scriptPath;
        this.packages = result.packages || this.packages;
        this.optimizeChanges = changes;
        this.scriptDirty = true;
        this.optimizing = false;
        // Re-test to measure the new timing. The regression guard in testScript()
        // will auto-revert if the optimized script is materially slower.
        this.testScript();
      },
      error: (err) => {
        // Silent failure — user still has the working original script.
        const msg = typeof err.error === 'string' ? err.error : (err.message || '');
        console.warn('Optimize failed: ' + msg.substring(0, 300));
        this.optimizing = false;
        this.optimizingSkipped = true;
        this.previousPassingTest = null;
      }
    });
  }

  optimizeBeforeMs(): number {
    return this.previousPassingTest ? this.previousPassingTest.durationMs : 0;
  }

  optimizeSpeedup(): number {
    const before = this.optimizeBeforeMs();
    if (!before || !this.optimizeDurationMs) return 1;
    return before / this.optimizeDurationMs;
  }

  formatMs(ms: number): string {
    if (!ms) return '0ms';
    if (ms < 1000) return ms + 'ms';
    const secs = ms / 1000;
    if (secs < 60) return secs.toFixed(1) + 's';
    const mins = Math.floor(secs / 60);
    const remSecs = Math.round(secs - mins * 60);
    return mins + 'm ' + remSecs + 's';
  }

  revertOptimization(): void {
    if (!this.previousPassingTest) return;
    const snap = this.previousPassingTest;
    this.script = snap.script;
    this.scriptPath = snap.scriptPath;
    this.packages = [...snap.packages];
    this.testRecords = snap.testRecords;
    this.testRecordCount = snap.testRecordCount;
    this.testLogs = snap.testLogs;
    this.testDataType = snap.testDataType;
    this.testColumns = [...snap.testColumns];
    this.scriptDirty = false;
    this.testError = '';
    this.aiExplanation = '';
    this.previousPassingTest = null;
    this.optimizingSkipped = true;
    this.optimizeChanges = [];
  }

  applyDiagnosis(): void {
    this.applyingDiagnosis = true;
    this.error = '';
    const explanation = this.aiExplanation;
    // Wipe the diagnosis panel immediately so its inline indicator doesn't
    // duplicate the top-level applyingDiagnosis indicator during the fix call.
    this.aiExplanation = '';
    this.activeSub = this.tapService.fixScript(
      this.tapName.trim(),
      this.script,
      explanation,
      this.testLogs,
      this.testError,
      this.scriptPath
    ).subscribe({
      next: (result) => {
        this.script = result.script || this.script;
        this.scriptPath = result.scriptPath || this.scriptPath;
        this.packages = result.packages || this.packages;
        this.testError = '';
        this.testLogs = '';
        this.testRecords = null;
        this.testRecordCount = 0;
        this.applyingDiagnosis = false;
        this.diagnosisApplied = true;
        this.scriptDirty = true;
        this.testScript();
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

  confirmNewCatalog(): void {
    const name = sanitizeLabel(this.newCatalogName);
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
    return this.testRecords.slice(0, 100);
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
          this.loadTargetPipelineConfig();
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

  scriptCopied = false;
  copyScript(): void {
    if (!this.script) return;
    // navigator.clipboard is async; in non-secure contexts it's undefined.
    // Fall back to a temp textarea + execCommand for that case.
    const done = () => {
      this.scriptCopied = true;
      setTimeout(() => { this.scriptCopied = false; }, 1500);
    };
    try {
      if (navigator?.clipboard?.writeText) {
        navigator.clipboard.writeText(this.script).then(done, () => this.copyScriptFallback(done));
      } else {
        this.copyScriptFallback(done);
      }
    } catch {
      this.copyScriptFallback(done);
    }
  }
  private copyScriptFallback(done: () => void): void {
    const ta = document.createElement('textarea');
    ta.value = this.script;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); done(); } catch {} finally { document.body.removeChild(ta); }
  }

  loadTargetPipelineConfig(): void {
    this.targetPipelineConfig = null;
    if (!this.targetPipeline) return;
    this.pipelineService.getPipeline(this.targetPipeline).subscribe({
      next: (config) => { this.targetPipelineConfig = config; },
      error: () => { this.targetPipelineConfig = null; }
    });
  }

  /** Human-readable destination description for the linked pipeline. */
  targetPipelineDestination(): string {
    const dest = this.targetPipelineConfig?.destination;
    if (!dest) return '';
    const db = dest.database;
    if (db?.usePostgres) {
      const schema = db.schema || 'public';
      return 'PostgreSQL → ' + (db.dbName || 'datris') + '.' + schema + '.' + (db.table || '?');
    }
    if (db?.useMongoDB) {
      return 'MongoDB → ' + (db.dbName || 'datris') + ' / ' + (db.table || '?');
    }
    if (dest.objectStore) {
      return 'Object Store → ' + (dest.objectStore.prefixKey || '?');
    }
    if (dest.kafka) {
      return 'Kafka → ' + (dest.kafka.topic || '?');
    }
    if (dest.activeMQ) {
      return 'ActiveMQ → ' + (dest.activeMQ.queueName || '?');
    }
    if (dest.restEndpoint) {
      return 'REST → ' + (dest.restEndpoint.url || '?');
    }
    if (dest.qdrant) return 'Qdrant → ' + (dest.qdrant.collectionName || '?');
    if (dest.weaviate) return 'Weaviate → ' + (dest.weaviate.className || '?');
    if (dest.milvus) return 'Milvus → ' + (dest.milvus.collectionName || '?');
    if (dest.chroma) return 'Chroma → ' + (dest.chroma.collectionName || '?');
    if (dest.pgvector) return 'pgvector → ' + (dest.pgvector.schemaName || 'public') + '.' + (dest.pgvector.tableName || '?');
    return '';
  }

  targetPipelineTruncate(): boolean {
    return !!this.targetPipelineConfig?.destination?.database?.truncateBeforeWrite;
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
    this.generatedDestName = this.generatedPipelineName.replace(/-/g, '_');
    this.generatedFields = [];
    this.existingDestNames = [];
    this.loadExistingDestNames();
    this.showGenerateModal = true;
  }

  private loadExistingDestNames(): void {
    const dataType = (this.testDataType || 'json').toLowerCase();
    if (dataType === 'csv') {
      this.searchService.getPostgresTables('datris', 'public').subscribe({
        next: (tables) => { this.existingDestNames = tables || []; },
        error: () => { this.existingDestNames = []; }
      });
    } else {
      this.searchService.getMongoCollections().subscribe({
        next: (collections) => { this.existingDestNames = collections || []; },
        error: () => { this.existingDestNames = []; }
      });
    }
  }

  destNameConflict(): boolean {
    const name = (this.generatedDestName || '').trim();
    if (!name) return false;
    return this.existingDestNames.includes(name);
  }

  destLabel(): string {
    const t = (this.testDataType || 'json').toLowerCase();
    return t === 'csv' ? 'Table name' : 'Collection name';
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
    const explicit = (this.generatedDestName || '').trim();
    if (explicit) return explicit;
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
    if (this.destNameConflict()) {
      this.generateError = 'A ' + this.destLabel().toLowerCase() + ' named "' + this.generatedDestName.trim() + '" already exists. Pick a different name.';
      return;
    }
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

    // For JSON/XML, the validator requires a single `_json`/`_xml` string field
     // (the document lands as an opaque string and is parsed downstream).
     // CSV uses the discovered per-column schema.
    const sourceFields = dataType === 'csv'
      ? fields
      : [{ name: dataType === 'xml' ? '_xml' : '_json', type: 'string' }];

    const source: any = {
      schemaProperties: {
        fields: sourceFields
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
