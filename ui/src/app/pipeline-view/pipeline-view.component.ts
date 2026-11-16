import { Component, OnInit, OnDestroy } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { PipelineService } from '../pipeline.service';
import { AuthService } from '../auth.service';
import { isAllTextDestination } from '../shared/dest-types';
import { LineageService, LineageNeighborhood } from '../lineage.service';
import { httpErrorText } from '../shared/http-error';

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
  unityCatalog: any = null;
  private refreshInterval: any = null;

  constructor(private route: ActivatedRoute, private router: Router, private pipelineService: PipelineService,
              private lineageService: LineageService, public auth: AuthService) { }

  ngOnInit(): void {
    this.name = this.route.snapshot.paramMap.get('name') || '';
    this.loadPipeline();
    this.loadLineage();
    this.loadUnityCatalog();
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

  /** Unity Catalog sync state, loaded once. Fail-soft: no card when the
   *  endpoint is unavailable (older server) or errors. */
  private loadUnityCatalog(): void {
    this.pipelineService.getUnityCatalog(this.name).subscribe({
      next: (s) => this.unityCatalog = s,
      error: () => this.unityCatalog = null
    });
  }

  /** The card shows when the state endpoint says Unity Catalog is on: an explicit
   *  opt-in (enabledBy 'pipeline') or the install default (enabledBy 'default'). */
  showUnityCatalog(): boolean {
    return !!this.unityCatalog?.enabled;
  }

  /** Source fields carrying a `protect` rule, as `name → method` labels for the
   *  "Protected fields" row (drop reads "dropped"; mask shows its preserve). */
  get protectedFields(): Array<{ name: string; label: string }> {
    const fields = this.config?.source?.schemaProperties?.fields;
    if (!Array.isArray(fields)) return [];
    return fields
      .filter((f: any) => f && f.protect && f.protect.method)
      .map((f: any) => {
        const m = f.protect.method;
        const label = m === 'drop' ? 'dropped'
          : (m === 'mask' && f.protect.preserve ? `mask (${f.protect.preserve})` : m);
        return { name: f.name, label };
      });
  }

  /** Display name of the field-protection preset enforced on this pipeline
   *  (`protection.preset`), or null when none is set. Shown whenever the preset
   *  is set, even if every classified field is exempt. */
  get presetLabel(): string | null {
    const id = this.config?.protection?.preset;
    if (!id) return null;
    return PipelineViewComponent.PRESET_LABELS[String(id).trim().toLowerCase()] || String(id);
  }

  /** Fields exempted from the preset (`protection.presetExempt`). */
  get presetExempt(): string[] {
    const ex = this.config?.protection?.presetExempt;
    return Array.isArray(ex) ? ex : [];
  }

  private static readonly PRESET_LABELS: Record<string, string> = {
    'hipaa-safe-harbor': 'HIPAA Safe Harbor'
  };

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
        this.error = httpErrorText(err, 'Failed to load pipeline');
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
          this.error = httpErrorText(err, 'Failed to delete pipeline');
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
          this.error = httpErrorText(err, 'Failed to delete data');
          this.deleteLoading = false;
          this.confirmDelete = false;
        }
      });
    }
  }
}
