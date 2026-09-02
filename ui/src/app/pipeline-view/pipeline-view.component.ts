import { Component, OnInit, OnDestroy } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { PipelineService } from '../pipeline.service';
import { AuthService } from '../auth.service';
import { isAllTextDestination } from '../shared/dest-types';
import { LineageService, LineageNeighborhood } from '../lineage.service';

@Component({
    selector: 'app-pipeline-view',
    templateUrl: './pipeline-view.component.html',
    styleUrls: ['./pipeline-view.component.css'],
    standalone: false
})
export class PipelineViewComponent implements OnInit, OnDestroy {
  name = '';
  config: any = null;
  configJson = '';
  error = '';
  copySuccess = false;
  confirmDelete = false;
  deleteLoading = false;
  showDestTypes = false;
  lineage: LineageNeighborhood | null = null;
  private refreshInterval: any = null;

  constructor(private route: ActivatedRoute, private router: Router, private pipelineService: PipelineService,
              private lineageService: LineageService, public auth: AuthService) { }

  ngOnInit(): void {
    this.name = this.route.snapshot.paramMap.get('name') || '';
    this.loadPipeline();
    this.loadLineage();
    this.refreshInterval = setInterval(() => this.loadPipeline(), 3000);
  }

  /** Loaded once — the server caches the graph ~1 minute anyway. Fail-soft:
   *  no lineage panel when the endpoint is unavailable. */
  private loadLineage(): void {
    this.lineageService.neighborhood('pipeline', this.name).subscribe({
      next: (n) => this.lineage = n,
      error: () => this.lineage = null
    });
  }

  upstreamNodes(): any[] {
    return (this.lineage?.upstream || []).filter(n => n.type === 'tap' || n.type === 'source');
  }

  downstreamNodes(): any[] {
    return (this.lineage?.downstream || []).filter(n => n.type === 'dataset');
  }

  ngOnDestroy(): void {
    if (this.refreshInterval) {
      clearInterval(this.refreshInterval);
    }
  }

  private loadPipeline(): void {
    this.pipelineService.getPipeline(this.name).subscribe({
      next: (data) => {
        const newJson = JSON.stringify(data, null, 2);
        if (newJson !== this.configJson) {
          this.config = data;
          this.configJson = newJson;
        }
      },
      error: (err) => {
        this.error = err.error || err.message || 'Failed to load pipeline';
      }
    });
  }

  /** "Stored as text" banner condition — computed from the loaded config, so
   *  it clears on the next refresh after types are applied. */
  isAllText(): boolean {
    return isAllTextDestination(this.config);
  }

  copyConfig(): void {
    navigator.clipboard.writeText(this.configJson).then(() => {
      this.copySuccess = true;
      setTimeout(() => this.copySuccess = false, 2000);
    });
  }

  editPipeline(): void {
    this.router.navigate(['/pipelines', this.name, 'edit']);
  }

  promptDelete(): void {
    this.confirmDelete = true;
  }

  cancelDelete(): void {
    this.confirmDelete = false;
  }

  deletePipeline(deleteConfig: boolean): void {
    this.deleteLoading = true;
    if (deleteConfig) {
      // Delete config + data
      this.pipelineService.deletePipeline(this.name).subscribe({
        next: () => {
          this.router.navigate(['/catalog']);
        },
        error: (err) => {
          this.error = err.error || err.message || 'Failed to delete pipeline';
          this.deleteLoading = false;
          this.confirmDelete = false;
        }
      });
    } else {
      // Delete data only (keep config)
      this.pipelineService.deletePipelineData(this.name).subscribe({
        next: () => {
          this.deleteLoading = false;
          this.confirmDelete = false;
          this.error = '';
        },
        error: (err) => {
          this.error = err.error || err.message || 'Failed to delete data';
          this.deleteLoading = false;
          this.confirmDelete = false;
        }
      });
    }
  }
}
