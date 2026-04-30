import { Component, OnInit, OnDestroy, ViewChild, ElementRef } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { SecretsService } from '../secrets.service';
import { SearchService } from '../search.service';
import { AuthService } from '../auth.service';
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
  tapType: 'structured' | 'document' = 'structured';
  secretName = '';
  catalog = '';
  availableCatalogs: string[] = [];
  showNewCatalog = false;
  newCatalogName = '';
  availableSecrets: string[] = [];
  existingTapNames: string[] = [];
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
  injectedPrompts: string[] = [];
  @ViewChild('brainstormInputEl') brainstormInputEl?: ElementRef<HTMLInputElement>;

  // Step 2 — Generate
  generating = false;
  script = '';
  scriptMissing = false;   // set when getTap returns scriptMissing=true — the script object is gone from MinIO even though scriptPath is set
  scriptPath = '';
  packages: string[] = [];

  // BYO: user pastes their own Python script instead of having the LLM generate one.
  bringYourOwnCode = false;
  userScript = '';
  storingUserScript = false;
  useMyCodeSuccess = '';

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
  private static readonly MAX_AUTO_FIX_ATTEMPTS = 3;
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

  // Post-run review: after a test passes, ask the LLM whether the script output
  // contains signals that the script itself should change (rate-limit / burst
  // warnings, deprecations, pagination hints, schema/auth warnings). If so,
  // the reviewer regenerates the script; the UI swaps it in and auto-retests.
  // Runs before the perf optimizer. If the reviewer rewrites the script, the
  // optimizer is skipped (correctness beats perf).
  private static readonly MAX_REVIEW_ATTEMPTS = 1;
  reviewing = false;
  reviewAttempts = 0;
  reviewChanges: string[] = [];
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

  // Iteration history: carry prior fix/optimize/review attempts into the next
  // AI call so the model has continuity within a single wizard session. Without
  // this, each fix is stateless — the model can chase its tail across
  // iterations, re-trying strategies that already failed or unwinding constraints
  // it just introduced. Capped at MAX_HISTORY_DEPTH; older entries fall off.
  private static readonly MAX_HISTORY_DEPTH = 3;
  iterationHistory: Array<{
    attempt: number;
    trigger: string;
    scriptDigest: string;
    outcome: string;
    recordCount: number;
    durationMs: number;
    error?: string;
    diagnosis?: string;
    appliedChange?: string;
  }> = [];
  // What kicked off the next test we're about to record. Set when fix/optimize/
  // review fires; consumed by testScript()'s next: handler to attribute the
  // outcome correctly. Defaults to 'user-test' for direct user-triggered tests.
  private lastTrigger: string = 'user-test';
  private lastAppliedChange: string | undefined = undefined;

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

  // Document-tap generate modal state
  availableVectorStores: string[] = [];
  loadingVectorStores = false;
  selectedVectorStore: string = '';
  pgvectorSchema = 'public';
  chunkStrategy: 'recursive' | 'fixed' | 'sentence' | 'paragraph' | 'none' = 'recursive';
  chunkSize = 500;
  chunkOverlap = 50;

  // Save
  saving = false;

  // Step 5 — Run (only reachable when targetPipeline is set)
  runningTap = false;
  runError = '';
  targetPipelineConfig: any = null;

  // Active subscription for cancellation
  private activeSub: Subscription | null = null;

  constructor(private tapService: TapService, private pipelineService: PipelineService, private secretsService: SecretsService, private searchService: SearchService, private router: Router, private route: ActivatedRoute, private auth: AuthService) { }

  /** Whether the current user can navigate to /configuration. Mirrors the
   *  top-nav Configuration link gate so the prompt-fragment chips don't
   *  offer a link that would just bounce the user. */
  get canSeeConfig(): boolean {
    return !this.auth.userAuthEnabled || this.auth.current()?.role === 'admin';
  }

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
          this.scriptMissing = tap.scriptMissing === true;
          this.secretName = tap.secretName || '';
          this.catalog = tap.catalog || '';
          this.targetPipeline = tap.targetPipeline || '';
          this.tapType = (tap.tapType === 'document') ? 'document' : 'structured';
        },
        error: () => { this.error = 'Failed to load tap'; }
      });
    }

    // Load available catalogs + existing tap names (for the overwrite warning)
    this.tapService.getTaps().subscribe({
      next: (taps) => {
        const cats = new Set<string>();
        (taps || []).forEach((t: any) => { if (t.catalog) cats.add(t.catalog); });
        this.availableCatalogs = Array.from(cats).sort();
        this.existingTapNames = (taps || []).map((t: any) => (t.name || '')).filter((n: string) => n.length > 0);
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
    this.stopActive();
  }

  /**
   * Cancel whatever async operation is currently in flight from any of the
   * inline spinner banners. Reads which operations were active first so we
   * can attach operation-specific cleanup (e.g. capping auto-fix for a
   * cancelled test) after the flags are cleared. No-ops when nothing is
   * running.
   */
  stopActive(): void {
    const wasTesting = this.testing;
    const wasApplyingDiagnosis = this.applyingDiagnosis;
    const wasRunningTap = this.runningTap;
    const wasOptimizing = this.optimizing;

    this.cancelActive();
    // cancelActive doesn't touch these — clear them here too.
    this.runningTap = false;
    this.optimizing = false;

    // Cap the auto-fix chain so a cancelled test/fix doesn't silently resume
    // with another attempt when the next subscription fires.
    if (wasTesting || wasApplyingDiagnosis) {
      this.autoFixAttempts = TapCreateComponent.MAX_AUTO_FIX_ATTEMPTS;
    }
    if (wasTesting) {
      this.testError = 'Test cancelled.';
    }
    if (wasRunningTap) {
      this.runError = 'Run cancelled.';
    }
    if (wasOptimizing) {
      // Prevent the next successful test from re-triggering optimize.
      this.optimizingSkipped = true;
      this.previousPassingTest = null;
    }
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
      if (this.bringYourOwnCode) {
        if (!this.userScript.trim()) { this.error = 'Paste your Python script first'; return; }
        if (!this.script) { this.error = 'Click Use My Code to upload the script before continuing'; return; }
      } else {
        if (!this.description.trim()) { this.error = 'Instruction is required'; return; }
        if (!this.script) { this.error = 'Generate a script first'; return; }
      }
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
    this.activeSub = this.tapService.brainstorm(this.brainstormMessages, this.description, this.tapType).subscribe({
      next: (result) => {
        this.brainstormMessages.push({ role: 'assistant', content: result.reply || '' });
        if (result.description) this.description = result.description;
        if (Array.isArray(result.suggestedEnvVars)) this.suggestedEnvVars = result.suggestedEnvVars;
        if (Array.isArray(result.injectedPrompts)) this.injectedPrompts = result.injectedPrompts;
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
    this.activeSub = this.tapService.generateScript(this.description, this.tapName.trim(), this.scriptPath, this.secretName, this.tapType).subscribe({
      next: (result) => {
        this.script = result.script || '';
        this.scriptPath = result.scriptPath || '';
        this.packages = result.packages || [];
        if (Array.isArray(result.injectedPrompts)) this.injectedPrompts = result.injectedPrompts;
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

  selectMode(mode: 'structured' | 'document' | 'custom'): void {
    if (mode === 'custom') {
      this.bringYourOwnCode = true;
      // Leave tapType as-is so the backend still knows whether this tap feeds a
      // structured pipeline or a document one. Default to structured when nothing
      // meaningful has been set yet.
      if (this.tapType !== 'structured' && this.tapType !== 'document') {
        this.tapType = 'structured';
      }
    } else {
      this.bringYourOwnCode = false;
      this.tapType = mode;
      this.userScript = '';
      this.useMyCodeSuccess = '';
    }
    this.error = '';
  }

  useMyCode(): void {
    const src = (this.userScript || '').trim();
    if (!src) { this.error = 'Paste your Python script first.'; return; }
    if (!this.tapName.trim()) { this.error = 'Tap name is required before uploading a script.'; return; }
    this.storingUserScript = true;
    this.error = '';
    this.useMyCodeSuccess = '';
    this.activeSub = this.tapService.storeScript(this.tapName.trim(), this.userScript, this.scriptPath).subscribe({
      next: (result) => {
        this.script = this.userScript;
        this.scriptPath = result.scriptPath || '';
        this.packages = [];
        this.scriptDirty = true;
        this.storingUserScript = false;
        if (!this.description.trim()) this.description = 'User-provided tap script.';
        this.useMyCodeSuccess = 'Script uploaded to MinIO' + (this.scriptPath ? ` (${this.scriptPath})` : '') + '. You can continue to the next step.';
      },
      error: (err) => {
        this.storingUserScript = false;
        this.error = 'Failed to upload script: ' + (err.error || err.message);
      }
    });
  }

  /** True when the entered Tap Name matches an existing tap and we're not in edit mode.
   *  Used to surface an overwrite warning before the user commits the create flow. */
  get tapNameCollides(): boolean {
    if (this.isEditMode) return false;
    const name = this.tapName.trim();
    if (!name) return false;
    return this.existingTapNames.includes(name);
  }

  /** Invalidate the uploaded copy when the user edits the textarea after a Use My Code.
   *  Forces another upload so step 2 tests the exact script they see. */
  onUserScriptChange(): void {
    if (this.useMyCodeSuccess) this.useMyCodeSuccess = '';
    if (this.script && this.script !== this.userScript) this.script = '';
  }

  /** User-initiated test. Resets auto-fix, review, and auto-optimize counters, then delegates. */
  runTest(): void {
    this.autoFixAttempts = 0;
    this.optimizeAttempts = 0;
    this.optimizingSkipped = false;
    this.optimizeChanges = [];
    this.optimizeDurationMs = 0;
    this.optimizeRegressionReverted = false;
    this.previousPassingTest = null;
    this.reviewAttempts = 0;
    this.reviewChanges = [];
    // Fresh user-driven session: clear the iteration history so the next AI
    // calls aren't influenced by attempts from a prior debugging arc.
    this.iterationHistory = [];
    this.lastTrigger = 'user-test';
    this.lastAppliedChange = undefined;
    this.testScript();
  }

  /** Append an iteration outcome to history with depth capping. Called from
   *  testScript()'s next: handler after each test result is in. */
  private recordIteration(outcome: 'passed' | 'failed' | 'regressed', durationMs: number): void {
    this.iterationHistory.push({
      attempt: this.iterationHistory.length + 1,
      trigger: this.lastTrigger,
      scriptDigest: (this.script || '').substring(0, 1500),
      outcome,
      recordCount: this.testRecordCount || 0,
      durationMs,
      error: (this.testError || '').substring(0, 800),
      diagnosis: (this.aiExplanation || '').substring(0, 800),
      appliedChange: this.lastAppliedChange
    });
    if (this.iterationHistory.length > TapCreateComponent.MAX_HISTORY_DEPTH) {
      this.iterationHistory = this.iterationHistory.slice(-TapCreateComponent.MAX_HISTORY_DEPTH);
    }
    // Reset the "what brought us here" markers so the NEXT iteration only
    // attributes a trigger if a fix/optimize/review fires before then.
    this.lastTrigger = 'user-test';
    this.lastAppliedChange = undefined;
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
        // Record this iteration's outcome in history BEFORE deciding whether
        // to auto-fix/optimize/review — the next AI call needs to see this
        // attempt in its context. Outcome may be upgraded to 'regressed'
        // below if the regression-revert branch fires.
        const failed = !!this.testError || this.testRecordCount === 0;
        this.recordIteration(failed ? 'failed' : 'passed', lastDurationMs);
        // Auto-fix: on a failed test with an actionable diagnosis, apply the
        // fix and re-test. Capped at MAX_AUTO_FIX_ATTEMPTS to avoid loops.
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
          // Mark the just-recorded iteration as a regression so the next AI
          // call understands "passed but reverted" and doesn't repeat the
          // optimization that lost too much speed.
          if (this.iterationHistory.length > 0) {
            this.iterationHistory[this.iterationHistory.length - 1].outcome = 'regressed';
          }
          this.revertOptimization();
          return;
        }
        // Record the optimized run's duration for the banner
        if (!failed && this.previousPassingTest) {
          this.optimizeDurationMs = lastDurationMs;
        }
        // Post-test flow: review first (functional signals in script output),
        // then optimize (perf). If the reviewer rewrites the script we re-test
        // and stop — correctness-from-output beats speed.
        const succeeded = !failed;
        if (succeeded && this.reviewAttempts < TapCreateComponent.MAX_REVIEW_ATTEMPTS) {
          this.reviewAttempts++;
          this.reviewScript(lastDurationMs);
        } else if (succeeded && this.optimizeAttempts < TapCreateComponent.MAX_AUTO_OPTIMIZE_ATTEMPTS &&
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
      this.scriptPath,
      this.iterationHistory
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
        // Mark what triggered the next test, summarizing the optimizer's
        // claimed changes so the next AI call (if it runs) knows what was
        // tried.
        this.lastTrigger = 'auto-optimize';
        this.lastAppliedChange = 'Optimize: ' + (changes.length > 0 ? changes.join('; ').substring(0, 200) : 'perf rewrite');
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

  /** Review script output for functional signals (rate limits, deprecations,
   *  pagination, schema/auth warnings). If the reviewer regenerates the script,
   *  swap it in and auto-retest. Falls back to the perf optimizer when the
   *  reviewer returns unchanged. */
  reviewScript(previousDurationMs: number): void {
    this.reviewing = true;
    this.reviewChanges = [];
    this.activeSub = this.tapService.reviewScript(
      this.tapName.trim(),
      this.script,
      this.testRecordCount,
      previousDurationMs,
      this.testLogs,
      this.scriptPath,
      this.iterationHistory
    ).subscribe({
      next: (result) => {
        const rewritten = !!result?.rewritten;
        const newScript = result?.script || this.script;
        const changes: string[] = result?.changes || [];
        this.reviewing = false;
        if (!rewritten || newScript === this.script) {
          // Output was clean — hand off to the perf optimizer.
          if (this.optimizeAttempts < TapCreateComponent.MAX_AUTO_OPTIMIZE_ATTEMPTS &&
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
              durationMs: previousDurationMs
            };
            this.optimizeAttempts++;
            this.optimizeScript(previousDurationMs);
          }
          return;
        }
        // Reviewer regenerated the script based on output signals. Swap it
        // in, record the changes, and auto-retest. Do NOT also run the
        // optimizer — correctness-from-output outranks perf on this pass.
        // optimizingSkipped=true makes the post-retest branch in testScript()
        // fall through without invoking optimize.
        this.script = newScript;
        this.scriptPath = result.scriptPath || this.scriptPath;
        this.packages = result.packages || this.packages;
        this.reviewChanges = changes;
        this.scriptDirty = true;
        this.optimizingSkipped = true;
        // Mark what triggered the next test so the iteration history captures
        // "this attempt was a reviewer rewrite addressing <changes>".
        this.lastTrigger = 'auto-review';
        this.lastAppliedChange = 'Review rewrite: ' + (changes.length > 0 ? changes.join('; ').substring(0, 200) : 'output-signal-based regen');
        this.testScript();
      },
      error: (err) => {
        // Silent failure — user still has the working original script.
        const msg = typeof err.error === 'string' ? err.error : (err.message || '');
        console.warn('Review failed: ' + msg.substring(0, 300));
        this.reviewing = false;
      }
    });
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
    // Mark what triggered the next test so its iteration record reflects
    // "this attempt was an auto-fix applying <diagnosis>".
    this.lastTrigger = 'auto-fix';
    this.lastAppliedChange = 'Apply fix for diagnosis: ' + (explanation || '').substring(0, 200);
    this.activeSub = this.tapService.fixScript(
      this.tapName.trim(),
      this.script,
      explanation,
      this.testLogs,
      this.testError,
      this.scriptPath,
      this.iterationHistory
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

  getDocumentPreview(): Array<{filename: string, uri: string, sizeLabel: string}> {
    if (!this.testRecords || !Array.isArray(this.testRecords)) return [];
    return this.testRecords.slice(0, 100).map((d: any) => {
      const b64 = typeof d?.content === 'string' ? d.content : '';
      // base64 length × 3/4 approximates decoded byte size; strip trailing "="
      const bytes = b64 ? Math.floor((b64.replace(/=+$/, '').length * 3) / 4) : 0;
      return {
        filename: d?.filename || '(no filename)',
        uri: d?.uri || '',
        sizeLabel: this.formatBytes(bytes)
      };
    });
  }

  private formatBytes(bytes: number): string {
    if (!bytes) return '—';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    return (bytes / (1024 * 1024 * 1024)).toFixed(1) + ' GB';
  }

  getPreviewRows(): any[] {
    if (!this.testRecords || !Array.isArray(this.testRecords)) return [];
    return this.testRecords.slice(0, 100);
  }

  save(): void {
    this.saving = true;
    this.error = '';

    const writeTapConfig = (scriptPathToUse: string) => {
      const config: any = {
        name: this.tapName.trim(),
        description: this.description,
        scriptPath: scriptPathToUse,
        packages: this.packages.filter(p => p.trim()).length > 0 ? this.packages.filter(p => p.trim()) : null,
        secretName: this.secretName || null,
        targetPipeline: this.targetPipeline || null,
        cronExpression: this.useSchedule && this.cronExpression ? this.cronExpression : null,
        enabled: this.enabled,
        tapType: this.tapType,
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
    };

    // Always push the in-memory script to MinIO before saving the tap config.
    // The script textarea has no change handler, so user edits live only in
    // browser memory until something pushes them. Without this, save can
    // commit a scriptPath whose file in MinIO doesn't match what the user
    // sees (or doesn't exist at all, after a regression auto-revert).
    if (this.script && this.script.trim()) {
      this.tapService.storeScript(this.tapName.trim(), this.script, this.scriptPath).subscribe({
        next: (result) => {
          this.scriptPath = result.scriptPath || this.scriptPath;
          this.scriptDirty = false;
          writeTapConfig(this.scriptPath);
        },
        error: (err) => {
          this.error = 'Save failed (could not store script): ' + (err.error || err.message);
          this.saving = false;
        }
      });
    } else {
      writeTapConfig(this.scriptPath);
    }
  }

  // ---------- Step 5 — Run the tap ----------

  runTapNow(): void {
    if (this.runningTap) return;
    this.runError = '';
    this.runningTap = true;
    this.tapService.runTap(this.tapName.trim(), 'run').subscribe({
      next: (resp: any) => {
        this.runningTap = false;
        // /tap/run returns HTTP 200 even when the script failed or no records
        // landed — the outcome is in the response body via `persisted` /
        // `persistedReason` / `error`. Navigating away on raw HTTP success
        // throws away the diagnostic and leaves the user with "screen
        // disappeared, no ingestion, no idea why."
        if (resp && resp.persisted === false) {
          const reason = resp.persistedReason;
          const errMsg = resp.error;
          let msg = 'Run did not persist any records.';
          if (errMsg) {
            msg = errMsg;
          } else if (reason === 'no_target_pipeline') {
            msg = 'Run did not persist: no target pipeline configured. Set one in the previous step and re-run.';
          } else if (reason === 'no_records') {
            msg = 'Run did not persist: the script returned 0 records.';
          } else if (reason === 'run_error') {
            msg = 'Run failed during script execution. Check the script and try again.';
          } else if (reason === 'test_mode') {
            msg = 'Run was rejected as test mode — try again or reload.';
          } else if (reason) {
            msg = 'Run did not persist: ' + reason;
          }
          this.runError = msg;
          return;
        }
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

  // For document taps only: once a compatible pipeline is selected in the attach
  // modal, we show a destination card instead of column-match output. Keeps the
  // selection informative (e.g. "pgvector → public.10q_collection") without
  // pretending the tap's synthetic columns need to line up with schema fields.
  selectedPipelineDestination: string = '';

  openAttachModal(): void {
    this.error = '';
    this.selectedPipelineName = '';
    this.columnMatchResult = null;
    this.selectedPipelineDestination = '';
    this.showAttachModal = true;
    this.loadingPipelines = true;
    this.pipelineService.getPipelines().subscribe({
      next: (pipelines) => {
        const all = pipelines || [];
        this.availablePipelines = this.tapType === 'document'
          ? all.filter(p => this.isDocumentCompatiblePipeline(p))
          : all;
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
    if (!pipeline) {
      this.columnMatchResult = null;
      this.selectedPipelineDestination = '';
      return;
    }

    if (this.tapType === 'document') {
      // Document taps: skip column matching, show destination summary
      this.columnMatchResult = { match: true, missing: [], extra: [] };
      this.selectedPipelineDestination = this.describeVectorDestination(pipeline);
      return;
    }

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

  /**
   * True when a pipeline can accept the output of a document tap:
   * unstructured source + at least one vector-store destination.
   * Mirrors the server-side DocumentTapValidator predicate.
   */
  private isDocumentCompatiblePipeline(p: any): boolean {
    const hasUnstructuredSource = !!p?.source?.fileAttributes?.unstructuredAttributes;
    const d = p?.destination || {};
    const hasVectorDest = !!(d.qdrant || d.weaviate || d.pgvector || d.milvus || d.chroma);
    return hasUnstructuredSource && hasVectorDest;
  }

  private describeVectorDestination(p: any): string {
    const d = p?.destination || {};
    if (d.qdrant) return 'Qdrant → ' + (d.qdrant.collectionName || '?');
    if (d.weaviate) return 'Weaviate → ' + (d.weaviate.className || '?');
    if (d.milvus) return 'Milvus → ' + (d.milvus.collectionName || '?');
    if (d.chroma) return 'Chroma → ' + (d.chroma.collectionName || '?');
    if (d.pgvector) return 'pgvector → ' + (d.pgvector.schemaName || 'public') + '.' + (d.pgvector.tableName || '?');
    return '';
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
    if (this.tapType === 'document') {
      this.availableVectorStores = [];
      this.selectedVectorStore = '';
      this.pgvectorSchema = 'public';
      this.chunkStrategy = 'recursive';
      this.chunkSize = 500;
      this.chunkOverlap = 50;
      this.loadingVectorStores = true;
      this.tapService.getAvailableVectorStores().subscribe({
        next: (stores) => {
          this.availableVectorStores = stores || [];
          // Preselect when exactly one; otherwise let the user pick.
          if (this.availableVectorStores.length === 1) {
            this.selectedVectorStore = this.availableVectorStores[0];
          } else if (this.availableVectorStores.includes('pgvector')) {
            this.selectedVectorStore = 'pgvector';
          } else if (this.availableVectorStores.length > 0) {
            this.selectedVectorStore = this.availableVectorStores[0];
          }
          this.loadingVectorStores = false;
        },
        error: () => {
          this.availableVectorStores = [];
          this.loadingVectorStores = false;
        }
      });
    } else {
      this.loadExistingDestNames();
    }
    this.showGenerateModal = true;
  }

  /** Label for the destination-name field on the document-tap generate modal. */
  vectorDestLabel(): string {
    switch (this.selectedVectorStore) {
      case 'pgvector': return 'Table name';
      case 'weaviate': return 'Class name';
      default: return 'Collection name';
    }
  }

  chunkStrategyHint(): string {
    switch (this.chunkStrategy) {
      case 'recursive': return 'Splits on paragraphs, then lines, then sentences, then spaces — preserves context best. Recommended default.';
      case 'fixed': return 'Fixed-size sliding window over characters. Fastest, least context-aware.';
      case 'sentence': return 'Breaks on sentence boundaries, merged up to chunk size.';
      case 'paragraph': return 'Breaks on paragraph boundaries, merged up to chunk size.';
      case 'none': return 'No chunking — each document becomes one vector. Only use with short docs.';
      default: return '';
    }
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

    if (this.tapType === 'document') {
      if (!this.selectedVectorStore) {
        this.generateError = 'Pick a vector store to continue.';
        return;
      }
      const name = (this.generatedDestName || '').trim();
      if (!name) {
        this.generateError = this.vectorDestLabel() + ' is required.';
        return;
      }
      if (this.chunkSize <= 0 || this.chunkOverlap < 0 || this.chunkOverlap >= this.chunkSize) {
        this.generateError = 'Chunk overlap must be >= 0 and strictly less than chunk size.';
        return;
      }
    } else if (this.destNameConflict()) {
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
    const config = this.tapType === 'document'
      ? this.buildDocumentPipelineConfig()
      : this.buildPipelineConfigFromTap(fields);
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

  /**
   * Build an unstructured + vector-store pipeline config for a document tap.
   * fileExtension is set to "*" because document taps deliver mixed filetypes
   * (PDF, DOCX, HTML, etc.); the platform's TextExtractorUtil picks the
   * extractor from each document's actual filename at runtime. The value just
   * needs to be non-null to satisfy PipelineValidatorUtil.
   * embeddingSecretName is left null so the server fills it with the env
   * default at pipeline-read time via EmbeddingUtil.
   */
  private buildDocumentPipelineConfig(): any {
    const destName = (this.generatedDestName || '').trim();
    const chunking = {
      strategy: this.chunkStrategy,
      chunkSize: this.chunkSize,
      chunkOverlap: this.chunkOverlap
    };
    const destination: any = {};
    switch (this.selectedVectorStore) {
      case 'pgvector':
        destination.pgvector = {
          tableName: destName,
          schemaName: (this.pgvectorSchema || 'public').trim() || 'public',
          chunking
        };
        break;
      case 'weaviate':
        destination.weaviate = { className: destName, chunking };
        break;
      case 'qdrant':
        destination.qdrant = { collectionName: destName, chunking };
        break;
      case 'milvus':
        destination.milvus = { collectionName: destName, chunking };
        break;
      case 'chroma':
        destination.chroma = { collectionName: destName, chunking };
        break;
    }
    return {
      name: this.generatedPipelineName,
      source: {
        fileAttributes: {
          unstructuredAttributes: {
            fileExtension: '*',
            preserveFilename: true
          }
        }
      },
      destination
    };
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
