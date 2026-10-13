import { Component, OnInit, OnDestroy, ElementRef, ViewChildren, QueryList, Input, HostListener } from '@angular/core';
import { Router } from '@angular/router';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { AuthService } from '../auth.service';
import { CodeRepoService } from '../configuration/code-repo/code-repo.service';
import { isColumnDragActive } from '../shared/resizable-columns.directive';
import { httpErrorText } from '../shared/http-error';

@Component({
    selector: 'app-taps',
    templateUrl: './taps.component.html',
    styleUrls: ['./taps.component.css'],
    standalone: false
})
export class TapsComponent implements OnInit, OnDestroy {
  /** When set, renders only the taps that belong to this catalog and hides the
   *  outer page chrome (heading, search bar, catalog-group folder rows). Used
   *  by DataCatalogComponent to embed this component inside each catalog card. */
  @Input() embedCatalog?: string;

  /** Catalog names available as move targets when embedded. Passed in by the
   *  parent so the embedded component doesn't have to know about empty
   *  catalogs (which only the parent enumerates via __catalog__ placeholders). */
  @Input() allCatalogs: string[] = [];

  taps: any[] = [];
  filteredTaps: any[] = [];
  searchQuery = '';
  catalogGroups: Array<{name: string, taps: any[], expanded: boolean, deleting?: boolean, running?: boolean}> = [];

  /** Identifier of the row whose Move menu is currently open, or '' for none. */
  moveMenuOpen = '';

  get isEmbedded(): boolean { return !!this.embedCatalog; }

  /** Close the Move menu when the user clicks outside it. The menu's own
   *  click handlers call stopPropagation, so in-menu clicks won't reach here. */
  @HostListener('document:click')
  onDocumentClick(): void {
    if (this.moveMenuOpen) this.moveMenuOpen = '';
    if (this.enableMenuOpen && !this.enableBusy) this.closeEnableMenu();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.enableMenuOpen && !this.enableBusy) this.closeEnableMenu();
  }

  // ── Enable menu on the "Disabled" badge (embedded table) ──────────────
  /** Tap whose Enable menu is open, '' for none. One at a time. */
  enableMenuOpen = '';
  /** Action in flight in the open menu; the menu stays open until it ends. */
  enableBusy: '' | 'enable' | 'test' = '';
  /** Error from the last menu action, shown inside the menu. */
  enableError = '';

  toggleEnableMenu(tap: any, event: Event): void {
    event.stopPropagation();
    if (event.type === 'keydown') event.preventDefault();
    if (!this.auth.canWrite() || this.enableBusy) return;
    if (this.enableMenuOpen === tap.name) {
      this.closeEnableMenu();
      return;
    }
    this.moveMenuOpen = '';
    this.enableMenuOpen = tap.name;
    this.enableError = '';
  }

  // ── Dependency hints and Run now (embedded table) ────────────────────
  /** Per tap of the embedded catalog: its enable-order level (1 = reads no
   *  other tap's tables) and the sources that have not landed data yet. */
  depInfo: { [name: string]: { level: number, blockedBy: string[] } } = {};
  /** Tap whose Run now is in flight, '' for none. */
  runNowBusy = '';
  /** Outcome line of the last Run now, per tap. */
  runNowResult: { [name: string]: { text: string, error: boolean } } = {};

  /** Load the catalog's enable order (once per loadTaps). Hints only: a
   *  failure leaves the rows as they were. */
  private loadEnableOrder(): void {
    const catalog = this.embedCatalog;
    if (!catalog || catalog === 'Uncataloged') return;
    try {
      this.pipelineService.enableOrder(catalog).subscribe({
        next: (order: any) => {
          const info: { [name: string]: { level: number, blockedBy: string[] } } = {};
          for (const lvl of (order && order.levels) || []) {
            for (const t of (lvl && lvl.taps) || []) {
              if (t && t.name) info[t.name] = { level: Number(lvl.level), blockedBy: Array.isArray(t.blockedBy) ? t.blockedBy : [] };
            }
          }
          this.depInfo = info;
        },
        error: () => { this.depInfo = {}; }
      });
    } catch {
      this.depInfo = {};
    }
  }

  /** "needs: x, y" while sources have not landed data, else "Level N". */
  depHint(tap: any): string {
    const d = tap && this.depInfo[tap.name];
    if (!d) return '';
    if (d.blockedBy.length > 0) return 'needs: ' + d.blockedBy.join(', ');
    return isFinite(d.level) ? 'Level ' + d.level : '';
  }

  depBlockedBy(tap: any): string[] {
    const d = tap && this.depInfo[tap.name];
    return d ? d.blockedBy : [];
  }

  /** A real run makes sense once the current script has passed a test and
   *  the tap feeds a pipeline. */
  canRunNow(tap: any): boolean {
    return !!tap && tap.lastTestRunStatus === 'success' && !!tap.targetPipeline;
  }

  /** One real run (mode=run) without enabling the tap or touching its
   *  schedule: lands the tap's data so the taps that read it can test. */
  runNow(tap: any, event?: Event): void {
    if (event) event.stopPropagation();
    if (this.runNowBusy || !tap) return;
    const name = tap.name;
    this.runNowBusy = name;
    delete this.runNowResult[name];
    this.tapService.runTap(name, 'run').subscribe({
      next: (res: any) => {
        this.runNowBusy = '';
        const status = String((res && res.status) || (res && res.error ? 'error' : 'success'));
        const parts = ['Run now: ' + status];
        if (res && res.recordCount != null) parts.push(res.recordCount + ' records');
        if (res && res.persistedReason) parts.push(String(res.persistedReason));
        if (res && res.error) parts.push(String(res.error).substring(0, 300));
        this.runNowResult[name] = { text: parts.join(', '), error: !!(res && res.error) || status === 'error' };
        if (this.enableMenuOpen === name) this.closeEnableMenu();
        this.loadTaps();
      },
      error: (err: any) => {
        this.runNowBusy = '';
        this.runNowResult[name] = { text: 'Run now failed: ' + httpErrorText(err, 'unknown error').substring(0, 300), error: true };
        if (this.enableMenuOpen === name) this.closeEnableMenu();
        this.loadTaps();
      }
    });
  }

  closeEnableMenu(): void {
    this.enableMenuOpen = '';
    this.enableBusy = '';
    this.enableError = '';
  }

  /** A scheduled tap with no passing test on record. Mirrors the server's
   *  test-before-cron gate (TapCronGate) as far as the list fields allow:
   *  a cron and lastTestRunStatus other than success. The server still
   *  decides; a refusal comes back as a 409 shown in the menu. */
  needsTestBeforeEnable(tap: any): boolean {
    return !!(tap && tap.cronExpression) &&
      String((tap && tap.lastTestRunStatus) || '').toLowerCase() !== 'success';
  }

  enableTap(tap: any, event?: Event): void {
    if (event) event.stopPropagation();
    if (this.enableBusy) return;
    this.enableBusy = 'enable';
    this.enableError = '';
    this.saveEnabled(tap.name, 'Could not enable: ');
  }

  /** Run a test (mode=test, synchronous) and enable the tap only when it
   *  passes. A failed test leaves the tap disabled and shows the error. */
  testThenEnable(tap: any, event?: Event): void {
    if (event) event.stopPropagation();
    if (this.enableBusy) return;
    this.enableBusy = 'test';
    this.enableError = '';
    this.tapService.runTap(tap.name, 'test').subscribe({
      next: (res: any) => {
        const passed = res && !res.error && String(res.status || 'success').toLowerCase() === 'success';
        if (!passed) {
          this.enableBusy = '';
          this.enableError = 'Test failed: ' + String((res && res.error) || 'unknown error').substring(0, 300);
          this.loadTaps();
          return;
        }
        this.saveEnabled(tap.name, 'Test passed, but the tap could not be enabled: ');
      },
      error: (err: any) => {
        this.enableBusy = '';
        this.enableError = 'Test failed: ' + httpErrorText(err, 'unknown error').substring(0, 300);
      }
    });
  }

  /** Save enabled=true on the full stored tap (fetched fresh so no field is
   *  dropped and a just-written test stamp is kept). */
  private saveEnabled(name: string, errorPrefix: string): void {
    const fail = (err: any) => {
      this.enableBusy = '';
      this.enableError = errorPrefix + httpErrorText(err, 'unknown error');
    };
    this.tapService.getTap(name).subscribe({
      next: (stored: any) => {
        const updated = { ...(stored || {}), name, enabled: true };
        // GET /tap adds the script text; POST /tap does not take it.
        delete (updated as any).script;
        this.tapService.createOrUpdateTap(updated).subscribe({
          next: () => {
            this.closeEnableMenu();
            this.loadTaps();
          },
          error: fail
        });
      },
      error: fail
    });
  }

  /** A refused save (400 malformed cron, 409 test-before-cron gate) comes back
   *  as a JSON body {"error": "..."}, which Angular parses into an object —
   *  concatenating it renders "[object Object]" and hides the message,
   *  including the corrected cron to resend. Unwrap it first. */
  errText(err: any): string {
    return (err && err.error && err.error.error) || (err && err.error) || (err && err.message) || 'unknown error';
  }
  private refreshInterval: any;

  deleteTarget = '';
  // Name of the tap whose delete is in flight — server-side cleanup (script
  // store, ledger, staged objects) can take seconds, so the confirm widget
  // shows progress and locks instead of sitting idle.
  deleting = '';
  deleteCatalogTarget = '';
  /** Two-mode group delete: 'detach' keeps the items (they move to
   *  Uncataloged), 'cascade' deletes them and their data and requires the
   *  catalog name typed into `deleteCatalogConfirm`. */
  deleteCatalogMode: 'detach' | 'cascade' = 'detach';
  deleteCatalogConfirm = '';
  runCatalogTarget = '';
  runningTap = '';
  pipelines: any[] = [];
  editingPipeline = '';
  editingName = '';
  editingNameValue = '';
  private editingNameOriginal = '';

  @ViewChildren('nameInput') nameInputs!: QueryList<ElementRef>;

  /** True when the tenant has an enabled code repository — gates the
   *  per-tap "move script storage" action. */
  repoConfigured = false;
  /** Tap currently being migrated between storage backends, or '' for none. */
  migratingTap = '';

  constructor(private tapService: TapService, private pipelineService: PipelineService, private router: Router, public auth: AuthService, private codeRepoService: CodeRepoService) { }

  ngOnInit(): void {
    this.loadTaps();
    this.codeRepoService.get().subscribe({
      next: (cfg) => this.repoConfigured = !!(cfg && cfg.enabled && cfg.repo),
      error: () => this.repoConfigured = false
    });
    this.loadPipelines();
    this.refreshInterval = setInterval(() => {
      // Pause auto-refresh during any row-level interaction a re-render would
      // destroy: inline edits, an open move-to-catalog menu, a pending delete
      // confirmation, or an in-flight column-resize drag.
      if (!this.editingName && !this.editingPipeline && !this.editingCronTap &&
          !this.moveMenuOpen && !this.enableMenuOpen && !this.deleteTarget && !this.deleteCatalogTarget && !isColumnDragActive()) this.loadTaps();
    }, 5000);
  }

  loadPipelines(): void {
    this.tapService.getPipelines().subscribe({
      next: (data) => this.pipelines = (data || []).sort((a, b) => (a.name || '').localeCompare(b.name || '')),
      error: () => {}
    });
  }

  ngOnDestroy(): void {
    if (this.refreshInterval) {
      clearInterval(this.refreshInterval);
    }
  }

  /** Move a tap's script between built-in storage and the code repository. */
  migrateStorage(event: Event, tap: any): void {
    event.stopPropagation();
    if (this.migratingTap) { return; }
    const target: 'github' | 'minio' = tap.scriptStorage === 'github' ? 'minio' : 'github';
    this.migratingTap = tap.name;
    this.codeRepoService.migrateStorage(tap.name, target).subscribe({
      next: () => {
        this.migratingTap = '';
        this.loadTaps();
      },
      error: (err) => {
        this.migratingTap = '';
        alert('Move failed: ' + ((err.error && err.error.error) || err.message));
      }
    });
  }

  loadTaps(): void {
    this.tapService.getTaps().subscribe({
      next: (data) => {
        this.taps = (data || []).filter(t => !(t.name || '').startsWith('__catalog__')).sort((a, b) => (a.name || '').localeCompare(b.name || ''));
        this.filterTaps();
      },
      error: (err) => console.error('Failed to load taps', err)
    });
    if (this.isEmbedded) this.loadEnableOrder();
  }

  filterTaps(): void {
    const q = this.searchQuery.toLowerCase().trim();
    if (!q) {
      this.filteredTaps = this.taps;
    } else {
      this.filteredTaps = this.taps.filter(t =>
        (t.name || '').toLowerCase().includes(q) ||
        (t.description || '').toLowerCase().includes(q) ||
        (t.targetPipeline || '').toLowerCase().includes(q) ||
        (t.catalog || '').toLowerCase().includes(q)
      );
    }
    this.buildCatalogGroups();
  }

  private buildCatalogGroups(): void {
    const prevExpanded = new Set(this.catalogGroups.filter(g => g.expanded).map(g => g.name));

    // When embedded inside a catalog card, render exactly one group containing
    // just that catalog's taps. The group is always expanded — the parent
    // catalog card is what controls expansion at the outer level.
    if (this.isEmbedded) {
      const target = this.embedCatalog!;
      const matches = this.filteredTaps.filter(t =>
        target === 'Uncataloged' ? !t.catalog : t.catalog === target
      );
      this.catalogGroups = [{ name: target, taps: matches, expanded: true }];
      return;
    }

    const map = new Map<string, any[]>();
    for (const tap of this.filteredTaps) {
      const cat = tap.catalog || 'Uncataloged';
      if (!map.has(cat)) map.set(cat, []);
      map.get(cat)!.push(tap);
    }
    // Named catalogs first (sorted), Uncataloged last
    const named = Array.from(map.entries())
      .filter(([name]) => name !== 'Uncataloged')
      .sort(([a], [b]) => a.localeCompare(b));
    const uncataloged = map.get('Uncataloged');

    this.catalogGroups = named.map(([name, taps]) => ({
      name, taps, expanded: prevExpanded.has(name)
    }));
    if (uncataloged && uncataloged.length > 0) {
      this.catalogGroups.push({
        name: 'Uncataloged', taps: uncataloged,
        expanded: prevExpanded.has('Uncataloged')
      });
    }
  }

  // Version history modal state
  historyTap = '';

  viewConfigTap = '';
  viewConfigJson = '';
  logsTap = '';
  logsData: any[] = [];
  logsLoading = false;

  // Script editor modal state
  scriptEditorTap: any = null;
  scriptEditorContent = '';
  scriptEditorPackages: string[] = [];
  scriptEditorError = '';
  scriptEditorSaving = false;

  // Cron edit modal state
  editingCronTap: any = null;
  cronEditValue = '';
  cronEditPreset = 'custom';
  cronPrompt = '';
  generatingCron = false;
  cronEditError = '';
  savingCron = false;

  viewConfig(event: Event, name: string): void {
    event.stopPropagation();
    this.tapService.getTap(name).subscribe({
      next: (tap) => {
        this.viewConfigJson = JSON.stringify(tap, null, 2);
        this.viewConfigTap = name;
      },
      error: () => alert('Failed to load tap config')
    });
  }

  // Document ledger modal
  ledgerTap = '';
  ledgerEntries: any[] = [];
  ledgerLoading = false;
  ledgerClearing = false;

  viewLedger(event: Event, name: string): void {
    event.stopPropagation();
    this.ledgerTap = name;
    this.ledgerEntries = [];
    this.ledgerLoading = true;
    this.tapService.getTapLedger(name).subscribe({
      next: (entries) => {
        this.ledgerEntries = (entries || []).sort((a: any, b: any) =>
          (b.lastSeenAt || '').localeCompare(a.lastSeenAt || '')
        );
        this.ledgerLoading = false;
      },
      error: () => { this.ledgerLoading = false; alert('Failed to load ledger'); }
    });
  }

  closeLedger(): void {
    this.ledgerTap = '';
    this.ledgerEntries = [];
  }

  deleteLedgerEntry(uri: string): void {
    if (!this.ledgerTap || !uri) return;
    this.tapService.deleteLedgerEntry(this.ledgerTap, uri).subscribe({
      next: () => this.ledgerEntries = this.ledgerEntries.filter(e => e.uri !== uri),
      error: () => alert('Failed to delete entry')
    });
  }

  clearLedger(): void {
    if (!this.ledgerTap || this.ledgerClearing) return;
    if (!confirm('Clear the entire ledger for ' + this.ledgerTap + '? The next run will re-process every document.')) return;
    this.ledgerClearing = true;
    this.tapService.clearLedger(this.ledgerTap).subscribe({
      next: () => { this.ledgerEntries = []; this.ledgerClearing = false; },
      error: () => { this.ledgerClearing = false; alert('Failed to clear ledger'); }
    });
  }

  // Incremental-sync state modal
  stateTap = '';
  stateJson = '';
  stateUpdatedAt = '';
  stateUpdatedBy = '';
  stateLoading = false;
  stateResetting = false;

  viewState(event: Event, name: string): void {
    event.stopPropagation();
    this.stateTap = name;
    this.stateJson = '';
    this.stateUpdatedAt = '';
    this.stateUpdatedBy = '';
    this.stateLoading = true;
    this.tapService.getTapState(name).subscribe({
      next: (res) => {
        this.stateJson = (res && res.state) ? JSON.stringify(res.state, null, 2) : '';
        this.stateUpdatedAt = (res && res.updatedAt) || '';
        this.stateUpdatedBy = (res && res.updatedBy) || '';
        this.stateLoading = false;
      },
      error: () => { this.stateLoading = false; alert('Failed to load sync state'); }
    });
  }

  closeState(): void {
    this.stateTap = '';
    this.stateJson = '';
    this.stateUpdatedAt = '';
    this.stateUpdatedBy = '';
    this.stateResetting = false;
  }

  resetState(): void {
    if (!this.stateTap || this.stateResetting) return;
    if (!confirm('Reset sync state for ' + this.stateTap + '? The next run will re-fetch everything from scratch.')) return;
    this.stateResetting = true;
    this.tapService.resetTapState(this.stateTap).subscribe({
      next: () => { this.stateJson = ''; this.stateUpdatedAt = ''; this.stateUpdatedBy = ''; this.stateResetting = false; },
      error: () => { this.stateResetting = false; alert('Failed to reset sync state'); }
    });
  }

  viewLogs(event: Event, name: string): void {
    event.stopPropagation();
    this.logsTap = name;
    this.logsLoading = true;
    this.logsData = [];
    this.tapService.getTapLogs(name).subscribe({
      next: (logs) => { this.logsData = logs || []; this.logsLoading = false; },
      error: () => { this.logsLoading = false; alert('Failed to load run history'); }
    });
  }

  closeLogs(): void {
    this.logsTap = '';
    this.logsData = [];
  }

  closeViewConfig(): void {
    this.viewConfigTap = '';
    this.viewConfigJson = '';
  }

  openHistory(event: Event, name: string): void {
    event.stopPropagation();
    this.historyTap = name;
  }

  closeHistory(): void {
    this.historyTap = '';
  }

  onHistoryRestored(): void {
    this.loadTaps();
  }

  openScriptEditor(event: Event, tap: any): void {
    event.stopPropagation();
    this.scriptEditorTap = tap;
    this.scriptEditorContent = '';
    this.scriptEditorPackages = [];
    this.scriptEditorError = '';
    this.scriptEditorSaving = false;
    // Load the script
    this.tapService.getTap(tap.name).subscribe({
      next: (fullTap) => {
        this.scriptEditorContent = fullTap.script || '';
        this.scriptEditorPackages = fullTap.packages || [];
      },
      error: () => { this.scriptEditorError = 'Failed to load script'; }
    });
  }

  closeScriptEditor(): void {
    this.scriptEditorTap = null;
    this.scriptEditorContent = '';
    this.scriptEditorPackages = [];
    this.scriptEditorError = '';
    this.scriptEditorSaving = false;
  }

  saveAndTestScript(): void {
    if (!this.scriptEditorTap || this.scriptEditorSaving) return;
    this.scriptEditorSaving = true;
    this.scriptEditorError = '';

    const tapName = this.scriptEditorTap.name;

    // Save the script
    this.tapService.storeScript(tapName, this.scriptEditorContent).subscribe({
      next: () => {
        // Test the script
        const config = {
          name: tapName,
          description: this.scriptEditorTap.description,
          scriptPath: this.scriptEditorTap.scriptPath,
          packages: this.scriptEditorPackages.length > 0 ? this.scriptEditorPackages : null,
          secretName: this.scriptEditorTap.secretName || null
        };
        this.tapService.testTap(config).subscribe({
          next: (result) => {
            this.scriptEditorSaving = false;
            if (result.recordCount > 0 && !result.error) {
              this.closeScriptEditor();
              this.loadTaps();
            } else {
              this.scriptEditorError = result.error || 'Test returned 0 records';
            }
          },
          error: (err: any) => {
            this.scriptEditorSaving = false;
            this.scriptEditorError = 'Test failed: ' + httpErrorText(err).substring(0, 300);
          }
        });
      },
      error: (err: any) => {
        this.scriptEditorSaving = false;
        this.scriptEditorError = 'Failed to save: ' + httpErrorText(err).substring(0, 200);
      }
    });
  }

  editTap(event: Event, name: string): void {
    event.stopPropagation();
    this.router.navigate(['/taps', name, 'edit']);
  }

  startEditName(event: Event, tap: any): void {
    event.stopPropagation();
    this.editingName = tap.name;
    this.editingNameValue = tap.name;
    this.editingNameOriginal = tap.name;
    setTimeout(() => {
      const input = this.nameInputs?.first;
      if (input) input.nativeElement.focus();
    });
  }

  saveEditName(tap: any): void {
    const newName = this.editingNameValue.trim();
    const oldName = this.editingNameOriginal;
    this.editingName = '';

    if (!newName || newName === oldName) return;

    // Rename a tap is three things on the server:
    //   1. Load the full config (the list endpoint omits the script body).
    //   2. Re-store the script under the new tap name so the file actually
    //      exists at a path tied to the new tap. Without this step, the new
    //      tap config inherits the OLD scriptPath, and step 3's deleteTap
    //      wipes the file out — leaving the renamed tap pointing at nothing
    //      ("script is missing from object storage").
    //   3. Create-or-update the tap config under the new name with the new
    //      scriptPath, then delete the old tap (which cleans up the old
    //      script file we no longer reference).
    this.tapService.getTap(oldName).subscribe({
      next: (fullTap) => {
        const finishRename = (newScriptPath: string | null) => {
          const updated = { ...fullTap, name: newName, scriptPath: newScriptPath };
          this.tapService.createOrUpdateTap(updated).subscribe({
            next: () => {
              this.tapService.deleteTap(oldName).subscribe({
                next: () => this.loadTaps(),
                error: () => this.loadTaps()
              });
            },
            error: (err) => {
              alert('Failed to rename: ' + this.errText(err));
              this.loadTaps();
            }
          });
        };

        const script = (fullTap && fullTap.script) ? fullTap.script : '';
        if (script) {
          // Pass null for oldScriptPath so the server doesn't delete the
          // old file yet — deleteTap on the old name will clean it up.
          this.tapService.storeScript(newName, script, undefined).subscribe({
            next: (res) => finishRename((res && res.scriptPath) || null),
            error: (err) => {
              alert('Failed to copy script to new name: ' + this.errText(err));
              this.loadTaps();
            }
          });
        } else {
          // No script content (e.g. tap with no generated script yet) — just
          // rename the config with no scriptPath.
          finishRename(null);
        }
      },
      error: (err) => {
        alert('Failed to load tap for rename: ' + this.errText(err));
        this.loadTaps();
      }
    });
  }

  cancelEditName(event: Event): void {
    event.stopPropagation();
    this.editingName = '';
    this.editingNameValue = '';
  }

  /** Target catalogs available for a move from this row's current catalog.
   *  Excludes the catalog the row already lives in. Uncataloged is included
   *  as a target — selecting it clears the row's catalog assignment. */
  moveTargets(): string[] {
    return this.allCatalogs.filter(c => c !== this.embedCatalog);
  }

  toggleMoveMenu(tapName: string, event: Event): void {
    event.stopPropagation();
    this.moveMenuOpen = this.moveMenuOpen === tapName ? '' : tapName;
  }

  /** ngFor trackBy used in the embedded rows so the 5s refresh interval
   *  doesn't destroy the row DOM (which would close any open Move menu and
   *  reset inline-edit state). Identity by name keeps the same <tr> in place. */
  trackByTapName(_index: number, tap: any): string {
    return tap?.name || '';
  }

  moveToCatalog(tap: any, targetCatalog: string, event: Event): void {
    event.stopPropagation();
    this.moveMenuOpen = '';
    // The Uncataloged pseudo-catalog is "no catalog assignment" — store as null
    // so the server treats it as unassigned, not as a literal catalog named
    // "Uncataloged".
    const catalogValue = targetCatalog === 'Uncataloged' ? null : targetCatalog;
    const updated = { ...tap, catalog: catalogValue };
    this.tapService.createOrUpdateTap(updated).subscribe({
      next: () => this.loadTaps(),
      error: (err) => alert('Failed to move: ' + this.errText(err))
    });
  }

  openPipelineDropdown(event: Event, name: string): void {
    event.stopPropagation();
    this.editingPipeline = this.editingPipeline === name ? '' : name;
  }

  setPipeline(event: Event, tap: any, pipelineName: string): void {
    event.stopPropagation();
    tap.targetPipeline = pipelineName || null;
    this.editingPipeline = '';
    this.tapService.createOrUpdateTap(tap).subscribe({
      next: () => this.loadTaps(),
      error: (err) => alert('Failed to update pipeline: ' + this.errText(err))
    });
  }

  runTap(event: Event, name: string): void {
    event.stopPropagation();
    this.router.navigate(['/taps', name, 'run']);
  }

  promptDelete(event: Event, name: string): void {
    event.stopPropagation();
    this.deleteTarget = name;
  }

  cancelDelete(event: Event): void {
    event.stopPropagation();
    if (this.deleting) { return; }
    this.deleteTarget = '';
  }

  confirmDelete(event: Event): void {
    event.stopPropagation();
    if (this.deleting) { return; }
    const name = this.deleteTarget;
    this.deleting = name;
    this.tapService.deleteTap(name).subscribe({
      next: () => { this.deleting = ''; this.deleteTarget = ''; this.loadTaps(); },
      error: (err) => { alert('Failed to delete: ' + this.errText(err)); this.deleting = ''; this.deleteTarget = ''; }
    });
  }

  runCatalogTaps(group: {name: string, taps: any[], running?: boolean}): void {
    group.running = true;
    this.runCatalogTarget = '';
    const tapsWithPipeline = group.taps.filter(t => t.targetPipeline);
    let completed = 0;
    const total = tapsWithPipeline.length;
    if (total === 0) { group.running = false; return; }

    for (const tap of tapsWithPipeline) {
      this.tapService.runTap(tap.name, 'run').subscribe({
        next: () => { completed++; if (completed === total) { group.running = false; this.loadTaps(); } },
        error: () => { completed++; if (completed === total) { group.running = false; this.loadTaps(); } }
      });
    }
  }

  openCatalogDelete(name: string, event: Event): void {
    event.stopPropagation();
    this.deleteCatalogTarget = name;
    this.deleteCatalogMode = 'detach';
    this.deleteCatalogConfirm = '';
  }

  cancelCatalogDelete(event?: Event): void {
    if (event) event.stopPropagation();
    this.deleteCatalogTarget = '';
    this.deleteCatalogMode = 'detach';
    this.deleteCatalogConfirm = '';
  }

  /** Cascade needs the exact (case-sensitive) catalog name typed. */
  canConfirmCatalogDelete(group: {name: string, deleting?: boolean}): boolean {
    if (group.deleting) return false;
    return this.deleteCatalogMode === 'detach' || this.deleteCatalogConfirm === group.name;
  }

  /** Delete a whole catalog through the server: "Keep items" moves every tap
   *  and pipeline in it to Uncataloged; "Delete items and their data"
   *  deletes them. Uncataloged is not a catalog and has no group delete. */
  deleteCatalogTaps(group: {name: string, taps: any[], deleting?: boolean}): void {
    if (group.name === 'Uncataloged') return;
    const mode = this.deleteCatalogMode;
    if (mode === 'cascade' && this.deleteCatalogConfirm !== group.name) return;
    const confirm = mode === 'cascade' ? this.deleteCatalogConfirm : undefined;
    group.deleting = true;
    const finish = () => { group.deleting = false; this.cancelCatalogDelete(); this.loadTaps(); };
    this.pipelineService.deleteCatalog(group.name, mode, confirm).subscribe({
      next: (res) => {
        const failed: any[] = (res && Array.isArray(res.failed)) ? res.failed : [];
        if (failed.length > 0) {
          alert(`Deleting catalog '${group.name}': ${failed.length} item(s) could not be changed:\n` +
            failed.map(f => `${f && f.name}: ${f && f.error}`).join('\n'));
        }
        finish();
      },
      error: (err) => {
        if (err && err.status === 404) {
          // The catalog is gone on the server (renamed or deleted elsewhere).
          // The cached group is stale, so nothing is written from it.
          group.deleting = false;
          this.cancelCatalogDelete();
          alert('Catalog not found. It may have been renamed or deleted elsewhere; refresh the page.');
          return;
        }
        alert('Failed to delete catalog: ' + ((err && err.error && err.error.error) || (err && err.message) || 'unknown error'));
        finish();
      }
    });
  }

  getStatusClass(status: string): string {
    if (status === 'success') return 'status-success';
    if (status === 'failure' || status === 'error' || status === 'timed_out') return 'status-failure';
    if (status === 'warning') return 'status-warning';
    if (status === 'no_records') return 'status-no-records';
    if (status === 'running' || status === 'processing') return 'status-running';
    return 'status-none';
  }

  /** Aggregate status from the downstream pipeline rollup, if present.
   *  Overrides the tap-script `status` field in the Run History row so a
   *  run where the tap fetched 28 docs but 1 chunking/embedding job failed
   *  shows up as `error` / `warning`, not the misleading `success` that
   *  came from the tap-script step alone. */
  rollupStatus(log: any): string | null {
    return log?.pipelineRollup?.status || null;
  }

  jobSuccessCount(log: any): number {
    const jobs = log?.pipelineRollup?.jobs || [];
    return jobs.filter((j: any) => j.status === 'success').length;
  }

  jobFailCount(log: any): number {
    const jobs = log?.pipelineRollup?.jobs || [];
    return jobs.filter((j: any) => j.status === 'error' || j.status === 'timed_out').length;
  }

  formatTime(time: string): string {
    if (!time) return '-';
    return time;
  }

  openEditCron(event: Event, tap: any): void {
    event.stopPropagation();
    this.editingCronTap = tap;
    this.cronEditValue = tap.cronExpression || '';
    this.cronEditPreset = this.detectPreset(this.cronEditValue);
    this.cronPrompt = '';
    this.cronEditError = '';
  }

  closeEditCron(): void {
    this.editingCronTap = null;
    this.cronEditValue = '';
    this.cronPrompt = '';
    this.cronEditError = '';
    this.generatingCron = false;
    this.savingCron = false;
  }

  setCronPreset(preset: string): void {
    this.cronEditPreset = preset;
    switch (preset) {
      case 'hourly': this.cronEditValue = '0 0 * * * ?'; break;
      case 'daily': this.cronEditValue = '0 0 0 * * ?'; break;
      case 'weekdays': this.cronEditValue = '0 0 0 ? * MON-FRI'; break;
      case 'weekly': this.cronEditValue = '0 0 0 ? * MON'; break;
      case 'custom': /* leave value as-is */ break;
    }
  }

  private detectPreset(expr: string): string {
    switch ((expr || '').trim()) {
      case '0 0 * * * ?': return 'hourly';
      case '0 0 0 * * ?': return 'daily';
      case '0 0 0 ? * MON-FRI': return 'weekdays';
      case '0 0 0 ? * MON': return 'weekly';
      default: return 'custom';
    }
  }

  generateCronFromPrompt(): void {
    if (!this.cronPrompt.trim()) return;
    this.generatingCron = true;
    this.cronEditError = '';
    this.tapService.generateCron(this.cronPrompt).subscribe({
      next: (result) => {
        this.cronEditValue = result.cronExpression || '';
        this.cronEditPreset = this.detectPreset(this.cronEditValue);
        this.generatingCron = false;
      },
      error: (err) => {
        this.cronEditError = 'Failed to generate CRON: ' + httpErrorText(err).substring(0, 200);
        this.generatingCron = false;
      }
    });
  }

  validateCron(expr: string): string | null {
    if (!expr || !expr.trim()) return 'CRON expression is required';
    const parts = expr.trim().split(/\s+/);
    if (parts.length < 6 || parts.length > 7) return 'CRON must have 6 or 7 fields (got ' + parts.length + ')';
    const [sec, min, hour, dom, mon, dow] = parts;
    const domQ = dom === '?';
    const dowQ = dow === '?';
    if (domQ === dowQ) return "Exactly one of day-of-month or day-of-week must be '?' (Quartz rule)";
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

  cronEditValidationError(): string {
    if (!this.cronEditValue.trim()) return '';
    return this.validateCron(this.cronEditValue) || '';
  }

  describeCron(expr: string): string {
    if (!expr || !expr.trim()) return '';
    const parts = expr.trim().split(/\s+/);
    if (parts.length < 6) return expr;
    const [sec, min, hour, dom, mon, dow] = parts;

    // Frequency phrase from day-of-month / day-of-week
    let when = '';
    if (dow === 'MON-FRI') when = 'every weekday';
    else if (dow === 'SAT,SUN' || dow === 'SUN,SAT') when = 'every weekend day';
    else if (dow === 'MON') when = 'every Monday';
    else if (dow === 'TUE') when = 'every Tuesday';
    else if (dow === 'WED') when = 'every Wednesday';
    else if (dow === 'THU') when = 'every Thursday';
    else if (dow === 'FRI') when = 'every Friday';
    else if (dow === 'SAT') when = 'every Saturday';
    else if (dow === 'SUN') when = 'every Sunday';
    else if (dow !== '?' && dow !== '*') when = `on ${dow}`;
    else if (dom !== '?' && dom !== '*') when = `on day ${dom} of the month`;
    else when = 'every day';

    // Time phrase
    if (hour === '*' && (min === '0' || min === '*')) {
      return min === '0' ? `${when}, every hour` : `${when}, every minute`;
    }
    if (hour.includes('/')) {
      const [, interval] = hour.split('/');
      return `${when}, every ${interval} hour${parseInt(interval) > 1 ? 's' : ''}`;
    }
    if (hour !== '*' && hour !== '?' && !hour.includes(',')) {
      const h = parseInt(hour);
      const m = parseInt(min) || 0;
      if (!isNaN(h)) {
        const ampm = h >= 12 ? 'PM' : 'AM';
        const h12 = h === 0 ? 12 : h > 12 ? h - 12 : h;
        const mStr = m < 10 ? '0' + m : '' + m;
        const timeStr = h === 0 && m === 0 ? 'midnight' : h === 12 && m === 0 ? 'noon' : `${h12}:${mStr} ${ampm}`;
        return `${when} at ${timeStr}`;
      }
    }
    return expr;
  }

  saveCron(): void {
    if (!this.editingCronTap) return;
    const newCron = this.cronEditValue.trim() || null;
    if (newCron && this.validateCron(newCron)) return;
    this.savingCron = true;
    this.cronEditError = '';
    const updated = { ...this.editingCronTap, cronExpression: newCron };
    this.tapService.createOrUpdateTap(updated).subscribe({
      next: () => {
        this.savingCron = false;
        this.closeEditCron();
        this.loadTaps();
      },
      error: (err) => {
        this.savingCron = false;
        // A refused save (400 malformed cron, 409 test-before-cron gate) comes
        // back as a JSON body {"error": "..."}, which Angular parses into an
        // object — concatenating it renders "[object Object]" and hides the
        // message (including the 6-field cron to resend). Unwrap it first.
        this.cronEditError = 'Failed to save: ' + this.errText(err);
      }
    });
  }
}
