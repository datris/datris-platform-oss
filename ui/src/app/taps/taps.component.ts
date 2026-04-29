import { Component, OnInit, OnDestroy, ElementRef, ViewChildren, QueryList } from '@angular/core';
import { Router } from '@angular/router';
import { TapService } from '../tap.service';
import { AuthService } from '../auth.service';

@Component({
  selector: 'app-taps',
  templateUrl: './taps.component.html',
  styleUrls: ['./taps.component.css']
})
export class TapsComponent implements OnInit, OnDestroy {
  taps: any[] = [];
  filteredTaps: any[] = [];
  searchQuery = '';
  catalogGroups: Array<{name: string, taps: any[], expanded: boolean, deleting?: boolean, running?: boolean}> = [];
  private refreshInterval: any;

  deleteTarget = '';
  deleteCatalogTarget = '';
  runCatalogTarget = '';
  runningTap = '';
  pipelines: any[] = [];
  editingPipeline = '';
  editingName = '';
  editingNameValue = '';
  private editingNameOriginal = '';

  @ViewChildren('nameInput') nameInputs!: QueryList<ElementRef>;

  constructor(private tapService: TapService, private router: Router, public auth: AuthService) { }

  ngOnInit(): void {
    this.loadTaps();
    this.loadPipelines();
    this.refreshInterval = setInterval(() => {
      if (!this.editingName && !this.editingPipeline && !this.editingCronTap) this.loadTaps();
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

  loadTaps(): void {
    this.tapService.getTaps().subscribe({
      next: (data) => {
        this.taps = (data || []).filter(t => !(t.name || '').startsWith('__catalog__')).sort((a, b) => (a.name || '').localeCompare(b.name || ''));
        this.filterTaps();
      },
      error: (err) => console.error('Failed to load taps', err)
    });
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
            this.scriptEditorError = 'Test failed: ' + (typeof err.error === 'string' ? err.error : err.message).substring(0, 300);
          }
        });
      },
      error: (err: any) => {
        this.scriptEditorSaving = false;
        this.scriptEditorError = 'Failed to save: ' + (typeof err.error === 'string' ? err.error : err.message).substring(0, 200);
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

    // Rename: create with new name, delete old
    const updated = { ...tap, name: newName };
    this.tapService.createOrUpdateTap(updated).subscribe({
      next: () => {
        this.tapService.deleteTap(oldName).subscribe({
          next: () => this.loadTaps(),
          error: () => this.loadTaps()
        });
      },
      error: (err) => {
        alert('Failed to rename: ' + (err.error || err.message));
        this.loadTaps();
      }
    });
  }

  cancelEditName(event: Event): void {
    event.stopPropagation();
    this.editingName = '';
    this.editingNameValue = '';
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
      error: (err) => alert('Failed to update pipeline: ' + (err.error || err.message))
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
    this.deleteTarget = '';
  }

  confirmDelete(event: Event): void {
    event.stopPropagation();
    const name = this.deleteTarget;
    this.tapService.deleteTap(name).subscribe({
      next: () => { this.deleteTarget = ''; this.loadTaps(); },
      error: (err) => { alert('Failed to delete: ' + (err.error || err.message)); this.deleteTarget = ''; }
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

  deleteCatalogTaps(group: {name: string, taps: any[], deleting?: boolean}): void {
    group.deleting = true;
    this.deleteCatalogTarget = '';
    const names = group.taps.map(t => t.name);
    // Also delete the catalog placeholder if it exists
    if (group.name !== 'Uncataloged') {
      names.push('__catalog__' + group.name);
    }
    let completed = 0;
    for (const name of names) {
      this.tapService.deleteTap(name).subscribe({
        next: () => { completed++; if (completed === names.length) { group.deleting = false; this.loadTaps(); } },
        error: () => { completed++; if (completed === names.length) { group.deleting = false; this.loadTaps(); } }
      });
    }
  }

  getStatusClass(status: string): string {
    if (status === 'success') return 'status-success';
    if (status === 'failure') return 'status-failure';
    if (status === 'running') return 'status-running';
    return 'status-none';
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
        this.cronEditError = 'Failed to generate CRON: ' + (typeof err.error === 'string' ? err.error : err.message || '').substring(0, 200);
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
        this.cronEditError = 'Failed to save: ' + (err.error || err.message);
      }
    });
  }
}
