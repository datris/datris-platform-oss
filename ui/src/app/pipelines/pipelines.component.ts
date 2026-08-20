import { Component, OnInit, OnDestroy, ViewChildren, ElementRef, QueryList, Input, HostListener } from '@angular/core';
import { Router } from '@angular/router';
import { PipelineService } from '../pipeline.service';
import { isColumnDragActive } from '../shared/resizable-columns.directive';
import { PipelineStatusService } from '../pipeline-status.service';
import { TapService } from '../tap.service';
import { AuthService } from '../auth.service';

@Component({
    selector: 'app-pipelines',
    templateUrl: './pipelines.component.html',
    styleUrls: ['./pipelines.component.css'],
    standalone: false
})
export class PipelinesComponent implements OnInit, OnDestroy {
  /** When set, renders only the pipelines that belong to this catalog and hides
   *  the outer page chrome. Used by DataCatalogComponent to embed this component
   *  inside each catalog card. */
  @Input() embedCatalog?: string;

  /** Catalog names available as move targets when embedded. */
  @Input() allCatalogs: string[] = [];

  pipelines: any[] = [];
  filteredPipelines: any[] = [];
  searchQuery = '';
  catalogGroups: Array<{name: string, pipelines: any[], expanded: boolean, deleting?: boolean}> = [];

  /** Identifier of the row whose Move menu is currently open, or '' for none. */
  moveMenuOpen = '';

  get isEmbedded(): boolean { return !!this.embedCatalog; }

  @HostListener('document:click')
  onDocumentClick(): void {
    if (this.moveMenuOpen) this.moveMenuOpen = '';
  }
  showDiagram = false;
  pipelineToTap: { [pipelineName: string]: string } = {};
  editingName = '';
  editingNameValue = '';
  private editingNameOriginal = '';
  @ViewChildren('nameInput') nameInputs?: QueryList<ElementRef<HTMLInputElement>>;
  private refreshInterval: any;

  deleteCatalogTarget = '';

  // Upload modal
  showUploadModal = false;
  uploadPipelineName = '';
  uploadPipelineConfig: any = null;
  uploadFile: File | null = null;
  uploading = false;
  uploadResult = '';
  uploadError = '';

  constructor(private pipelineService: PipelineService, private pipelineStatusService: PipelineStatusService, private tapService: TapService, private router: Router, public auth: AuthService) { }

  ngOnInit(): void {
    this.loadPipelines();
    this.loadTaps();
    this.refreshInterval = setInterval(() => {
      // Pause auto-refresh during any row-level interaction a re-render would
      // destroy: an inline name edit, an open move-to-catalog menu, or an
      // in-flight column-resize drag.
      if (this.editingName || this.moveMenuOpen || isColumnDragActive()) return;
      this.loadPipelines();
      this.loadTaps();
    }, 5000);
  }

  startEditName(event: Event, pipeline: any): void {
    event.stopPropagation();
    this.editingName = pipeline.name;
    this.editingNameValue = pipeline.name;
    this.editingNameOriginal = pipeline.name;
    setTimeout(() => {
      const input = this.nameInputs?.first;
      if (input) input.nativeElement.focus();
    });
  }

  saveEditName(pipeline: any): void {
    const newName = this.editingNameValue.trim();
    const oldName = this.editingNameOriginal;
    this.editingName = '';

    if (!newName || newName === oldName) return;

    // Rename: load full config, POST as new name, delete old config (data only — keep)
    this.pipelineService.getPipeline(oldName).subscribe({
      next: (config) => {
        const renamed = { ...config, name: newName };
        this.pipelineService.createPipeline(renamed).subscribe({
          next: () => {
            this.pipelineService.deletePipeline(oldName).subscribe({
              next: () => this.loadPipelines(),
              error: () => this.loadPipelines()
            });
          },
          error: (err) => {
            alert('Failed to rename: ' + (err.error || err.message));
            this.loadPipelines();
          }
        });
      },
      error: (err) => {
        alert('Failed to load pipeline for rename: ' + (err.error || err.message));
        this.loadPipelines();
      }
    });
  }

  cancelEditName(event: Event): void {
    event.stopPropagation();
    this.editingName = '';
    this.editingNameValue = '';
  }

  moveTargets(): string[] {
    return this.allCatalogs.filter(c => c !== this.embedCatalog);
  }

  toggleMoveMenu(name: string, event: Event): void {
    event.stopPropagation();
    this.moveMenuOpen = this.moveMenuOpen === name ? '' : name;
  }

  /** ngFor trackBy used in the embedded rows so the 5s refresh interval
   *  doesn't destroy the row DOM (which would close any open Move menu and
   *  reset inline-edit state). Identity by name keeps the same <tr> in place. */
  trackByPipelineName(_index: number, p: any): string {
    return p?.name || '';
  }

  moveToCatalog(pipeline: any, targetCatalog: string, event: Event): void {
    event.stopPropagation();
    this.moveMenuOpen = '';
    const catalogValue = targetCatalog === 'Uncataloged' ? null : targetCatalog;
    const updated = { ...pipeline, catalog: catalogValue };
    this.pipelineService.createPipeline(updated).subscribe({
      next: () => this.loadPipelines(),
      error: (err) => alert('Failed to move: ' + (err.error || err.message))
    });
  }

  loadTaps(): void {
    this.tapService.getTaps().subscribe({
      next: (taps) => {
        const map: { [pipelineName: string]: string } = {};
        (taps || []).forEach((t: any) => {
          if (t.targetPipeline) map[t.targetPipeline] = t.name;
        });
        this.pipelineToTap = map;
      },
      error: () => {}
    });
  }

  goToTap(event: Event, tapName: string): void {
    event.stopPropagation();
    this.router.navigate(['/taps', tapName, 'edit']);
  }

  ngOnDestroy(): void {
    if (this.refreshInterval) {
      clearInterval(this.refreshInterval);
    }
  }

  loadPipelines(): void {
    this.pipelineService.getPipelines().subscribe({
      next: (data) => {
        this.pipelines = (data || []).sort((a, b) => (a.name || '').localeCompare(b.name || ''));
        this.filterPipelines();
      },
      error: (err) => console.error('Failed to load pipelines', err)
    });
  }

  filterPipelines(): void {
    const q = this.searchQuery.toLowerCase().trim();
    if (!q) {
      this.filteredPipelines = this.pipelines;
    } else {
      this.filteredPipelines = this.pipelines.filter(p =>
        (p.name || '').toLowerCase().includes(q) ||
        (p.catalog || '').toLowerCase().includes(q) ||
        this.getSourceType(p).toLowerCase().includes(q) ||
        this.getDestinations(p).toLowerCase().includes(q)
      );
    }
    this.buildCatalogGroups();
  }

  private buildCatalogGroups(): void {
    const prevExpanded = new Set(this.catalogGroups.filter(g => g.expanded).map(g => g.name));

    // When embedded inside a catalog card, render exactly one group containing
    // just that catalog's pipelines. Always expanded; the parent catalog card
    // controls expansion at the outer level.
    if (this.isEmbedded) {
      const target = this.embedCatalog!;
      const matches = this.filteredPipelines.filter(p =>
        target === 'Uncataloged' ? !p.catalog : p.catalog === target
      );
      this.catalogGroups = [{ name: target, pipelines: matches, expanded: true }];
      return;
    }

    const map = new Map<string, any[]>();
    for (const p of this.filteredPipelines) {
      const cat = p.catalog || 'Uncataloged';
      if (!map.has(cat)) map.set(cat, []);
      map.get(cat)!.push(p);
    }
    const named = Array.from(map.entries())
      .filter(([name]) => name !== 'Uncataloged')
      .sort(([a], [b]) => a.localeCompare(b));
    const uncataloged = map.get('Uncataloged');

    this.catalogGroups = named.map(([name, pipelines]) => ({
      name, pipelines, expanded: prevExpanded.has(name)
    }));
    if (uncataloged && uncataloged.length > 0) {
      this.catalogGroups.push({
        name: 'Uncataloged', pipelines: uncataloged,
        expanded: prevExpanded.has('Uncataloged')
      });
    }
  }

  viewPipeline(event: Event, name: string): void {
    event.stopPropagation();
    this.router.navigate(['/pipelines', name]);
  }

  editPipeline(event: Event, name: string): void {
    event.stopPropagation();
    this.router.navigate(['/pipelines', name, 'edit']);
  }

  deleteTarget = '';

  promptDelete(event: Event, name: string): void {
    event.stopPropagation();
    this.deleteTarget = name;
  }

  cancelDelete(event: Event): void {
    event.stopPropagation();
    this.deleteTarget = '';
  }

  deleteCatalogPipelines(group: {name: string, pipelines: any[], deleting?: boolean}): void {
    group.deleting = true;
    this.deleteCatalogTarget = '';
    const names = group.pipelines.map(p => p.name);
    let completed = 0;
    for (const name of names) {
      this.pipelineService.deletePipeline(name).subscribe({
        next: () => { completed++; if (completed === names.length) { group.deleting = false; this.loadPipelines(); } },
        error: () => { completed++; if (completed === names.length) { group.deleting = false; this.loadPipelines(); } }
      });
    }
  }

  confirmDelete(event: Event, deleteConfig: boolean): void {
    event.stopPropagation();
    const name = this.deleteTarget;
    if (deleteConfig) {
      this.pipelineService.deletePipeline(name).subscribe({
        next: () => { this.deleteTarget = ''; this.loadPipelines(); },
        error: (err) => { alert('Failed to delete: ' + (err.error || err.message)); this.deleteTarget = ''; }
      });
    } else {
      this.pipelineService.deletePipelineData(name).subscribe({
        next: () => { this.deleteTarget = ''; },
        error: (err) => { alert('Failed to delete data: ' + (err.error || err.message)); this.deleteTarget = ''; }
      });
    }
  }

  getSourceType(dataset: any): string {
    if (!dataset.source) return '';
    if (dataset.source.fileAttributes?.unstructuredAttributes) return 'Unstructured';
    if (dataset.source.fileAttributes?.csvAttributes) return 'CSV';
    if (dataset.source.fileAttributes?.jsonAttributes) return 'JSON';
    if (dataset.source.fileAttributes?.xmlAttributes) return 'XML';
    if (dataset.source.databaseAttributes) return 'Database Pull';
    if (dataset.source.kafkaAttributes) return 'Kafka';
    return 'File';
  }

  getDestinations(dataset: any): string {
    if (!dataset.destination) return '';
    const dests: string[] = [];
    if (dataset.destination.database?.usePostgres) dests.push('PostgreSQL');
    if (dataset.destination.database?.useMongoDB) dests.push('MongoDB');
    if (dataset.destination.database?.useSnowflake) dests.push('Snowflake');
    if (dataset.destination.database?.useDatabricks) dests.push('Databricks');
    if (dataset.destination.objectStore) dests.push('Object Store');
    if (dataset.destination.kafka) dests.push('Kafka');
    if (dataset.destination.activeMQ) dests.push('ActiveMQ');
    if (dataset.destination.restEndpoint) dests.push('REST');
    if (dataset.destination.qdrant) dests.push('Qdrant');
    if (dataset.destination.weaviate) dests.push('Weaviate');
    if (dataset.destination.milvus) dests.push('Milvus');
    if (dataset.destination.chroma) dests.push('Chroma');
    if (dataset.destination.pgvector) dests.push('pgvector');
    return dests.join(', ');
  }

  // Upload modal
  openUploadModal(event: Event, name: string): void {
    event.stopPropagation();
    this.uploadPipelineName = name;
    this.uploadPipelineConfig = null;
    this.uploadFile = null;
    this.uploading = false;
    this.uploadResult = '';
    this.uploadError = '';
    this.showUploadModal = true;
    this.pipelineService.getPipeline(name).subscribe({
      next: (config) => this.uploadPipelineConfig = config,
      error: () => {}
    });
  }

  closeUploadModal(): void {
    this.showUploadModal = false;
  }

  onUploadFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.uploadFile = input.files[0];
    }
  }

  uploadAndProcess(): void {
    if (!this.uploadFile || !this.uploadPipelineName) return;
    this.uploading = true;
    this.uploadError = '';
    this.uploadResult = '';
    this.pipelineStatusService.uploadFile(this.uploadFile, this.uploadPipelineName).subscribe({
      next: () => {
        this.uploadResult = 'File uploaded successfully! Go to the Ingestion tab to monitor progress.';
        this.uploading = false;
      },
      error: (err) => {
        this.uploadError = err.error || err.message || 'Upload failed';
        this.uploading = false;
      }
    });
  }
}
