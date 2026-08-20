import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges } from '@angular/core';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { Observable } from 'rxjs';

@Component({
    selector: 'app-version-history',
    templateUrl: './version-history.component.html',
    styleUrls: ['./version-history.component.css'],
    standalone: false
})
export class VersionHistoryComponent implements OnChanges {
  @Input() entityType: 'tap' | 'pipeline' = 'tap';
  @Input() name = '';
  @Output() closed = new EventEmitter<void>();
  @Output() restored = new EventEmitter<void>();

  versions: any[] = [];
  loading = false;
  error = '';
  successMessage = '';

  // Selected snapshot view
  selectedVersion: number | null = null;
  snapshot: any = null;
  snapshotJson = '';
  snapshotScript = '';
  snapshotLoading = false;

  // Diff state
  diffAgainst: number | null = null;
  diff: any = null;
  diffLoading = false;

  restoring = false;

  constructor(
    private tapService: TapService,
    private pipelineService: PipelineService
  ) { }

  get isTap(): boolean { return this.entityType === 'tap'; }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['name'] && this.name) {
      this.loadVersions();
    }
  }

  private listVersions(): Observable<any[]> {
    return this.isTap
      ? this.tapService.getTapVersions(this.name)
      : this.pipelineService.getPipelineVersions(this.name);
  }

  private getVersion(version: number): Observable<any> {
    return this.isTap
      ? this.tapService.getTapVersion(this.name, version)
      : this.pipelineService.getPipelineVersion(this.name, version);
  }

  private diffVersions(version: number, against: number): Observable<any> {
    return this.isTap
      ? this.tapService.diffTapVersions(this.name, version, against)
      : this.pipelineService.diffPipelineVersions(this.name, version, against);
  }

  private restoreVersion(version: number): Observable<any> {
    return this.isTap
      ? this.tapService.restoreTapVersion(this.name, version)
      : this.pipelineService.restorePipelineVersion(this.name, version);
  }

  loadVersions(): void {
    this.loading = true;
    this.error = '';
    this.selectedVersion = null;
    this.snapshot = null;
    this.diff = null;
    this.diffAgainst = null;
    this.listVersions().subscribe({
      next: (data) => {
        this.versions = data || [];
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.error = 'Failed to load version history: ' + (err.error || err.message || '');
      }
    });
  }

  selectVersion(version: number): void {
    this.successMessage = '';
    this.diff = null;
    this.diffAgainst = null;
    this.selectedVersion = version;
    this.snapshot = null;
    this.snapshotJson = '';
    this.snapshotScript = '';
    this.snapshotLoading = true;
    this.getVersion(version).subscribe({
      next: (snap) => {
        this.snapshot = snap;
        this.snapshotJson = JSON.stringify(snap.config || {}, null, 2);
        this.snapshotScript = snap.script || '';
        this.snapshotLoading = false;
      },
      error: (err) => {
        this.snapshotLoading = false;
        this.error = 'Failed to load snapshot: ' + (err.error || err.message || '');
      }
    });
  }

  onDiffAgainstChange(value: string): void {
    const against = value === '' ? null : parseInt(value, 10);
    this.diffAgainst = against;
    this.diff = null;
    if (against === null || this.selectedVersion === null) return;
    this.diffLoading = true;
    this.diffVersions(this.selectedVersion, against).subscribe({
      next: (d) => {
        this.diff = d;
        this.diffLoading = false;
      },
      error: (err) => {
        this.diffLoading = false;
        this.error = 'Failed to load diff: ' + (err.error || err.message || '');
      }
    });
  }

  /** Other versions available as a "compare against" target for the selected one. */
  otherVersions(): any[] {
    return this.versions.filter(v => v.version !== this.selectedVersion);
  }

  changeClass(change: string): string {
    if (change === 'added') return 'vh-diff-added';
    if (change === 'removed') return 'vh-diff-removed';
    if (change === 'changed') return 'vh-diff-changed';
    return '';
  }

  lineClass(type: string): string {
    if (type === 'add') return 'vh-line-add';
    if (type === 'del') return 'vh-line-del';
    return 'vh-line-ctx';
  }

  linePrefix(type: string): string {
    if (type === 'add') return '+';
    if (type === 'del') return '-';
    return ' ';
  }

  restore(version: number): void {
    if (this.restoring) return;
    const label = this.isTap ? 'tap' : 'pipeline';
    const msg = "Restore " + label + " '" + this.name + "' to version " + version +
      "? This creates a new latest version; nothing is lost.";
    if (!confirm(msg)) return;
    this.restoring = true;
    this.error = '';
    this.successMessage = '';
    this.restoreVersion(version).subscribe({
      next: () => {
        this.restoring = false;
        this.successMessage = 'Restored to version ' + version + '. A new latest version was created.';
        this.selectedVersion = null;
        this.snapshot = null;
        this.diff = null;
        this.diffAgainst = null;
        this.loadVersions();
        this.restored.emit();
      },
      error: (err) => {
        this.restoring = false;
        this.error = 'Failed to restore: ' + (err.error || err.message || '');
      }
    });
  }

  close(): void {
    this.closed.emit();
  }
}
