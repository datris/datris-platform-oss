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
      if (!this.editingName && !this.editingPipeline) this.loadTaps();
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
}
