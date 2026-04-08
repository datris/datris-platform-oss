import { Component, OnInit, OnDestroy, ElementRef, ViewChildren, QueryList } from '@angular/core';
import { Router } from '@angular/router';
import { TapService } from '../tap.service';

@Component({
  selector: 'app-taps',
  templateUrl: './taps.component.html',
  styleUrls: ['./taps.component.css']
})
export class TapsComponent implements OnInit, OnDestroy {
  taps: any[] = [];
  filteredTaps: any[] = [];
  searchQuery = '';
  private refreshInterval: any;

  deleteTarget = '';
  runningTap = '';
  pipelines: any[] = [];
  editingPipeline = '';
  editingName = '';
  editingNameValue = '';
  private editingNameOriginal = '';

  @ViewChildren('nameInput') nameInputs!: QueryList<ElementRef>;

  constructor(private tapService: TapService, private router: Router) { }

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
        this.taps = (data || []).sort((a, b) => (a.name || '').localeCompare(b.name || ''));
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
        (t.targetPipeline || '').toLowerCase().includes(q)
      );
    }
  }

  viewConfigTap = '';
  viewConfigJson = '';
  logsTap = '';
  logsData: any[] = [];
  logsLoading = false;

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
