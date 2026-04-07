import { Component, OnInit, OnDestroy } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { TapService } from '../tap.service';

@Component({
  selector: 'app-tap-run',
  templateUrl: './tap-run.component.html',
  styleUrls: ['./tap-run.component.css']
})
export class TapRunComponent implements OnInit, OnDestroy {
  tapName = '';
  description = '';
  targetPipeline = '';
  loading = true;
  running = false;
  hasRun = false;
  pushToPipeline = false;

  status = '';
  recordCount = 0;
  records: any = null;
  logs = '';
  error = '';

  private runSub: Subscription | null = null;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private tapService: TapService
  ) { }

  ngOnInit(): void {
    this.tapName = this.route.snapshot.paramMap.get('name') || '';
    if (this.tapName) {
      this.loadTap();
    }
  }

  ngOnDestroy(): void {
    if (this.runSub) {
      this.runSub.unsubscribe();
    }
  }

  loadTap(): void {
    this.tapService.getTap(this.tapName).subscribe({
      next: (tap) => {
        this.description = tap.description || '';
        this.targetPipeline = tap.targetPipeline || '';
        this.loading = false;
      },
      error: () => {
        this.error = 'Failed to load tap';
        this.loading = false;
      }
    });
  }

  runTap(): void {
    this.running = true;
    this.hasRun = false;
    this.status = '';
    this.error = '';
    this.logs = '';
    this.records = null;
    this.recordCount = 0;

    this.runSub = this.tapService.runTap(this.tapName, this.pushToPipeline).subscribe({
      next: (result) => {
        this.status = result.status || 'unknown';
        this.recordCount = result.recordCount || 0;
        this.records = result.records || [];
        this.logs = result.logs || '';
        this.error = result.error || '';
        this.running = false;
        this.hasRun = true;
      },
      error: (err) => {
        this.status = 'failure';
        this.error = typeof err.error === 'string' ? err.error.substring(0, 500) : (err.message || 'Unknown error');
        this.running = false;
        this.hasRun = true;
      }
    });
  }

  getColumns(): string[] {
    if (!this.records || !Array.isArray(this.records) || this.records.length === 0) return [];
    return Object.keys(this.records[0]);
  }

  getPreviewRows(): any[] {
    if (!this.records || !Array.isArray(this.records)) return [];
    return this.records.slice(0, 20);
  }

  backToTaps(): void {
    this.router.navigate(['/taps']);
  }
}
