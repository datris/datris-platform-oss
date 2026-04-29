import { Component, OnInit, OnDestroy, ViewChildren, ElementRef, QueryList } from '@angular/core';
import { Router } from '@angular/router';
import { PipelineService } from '../pipeline.service';
import { PipelineStatusService } from '../pipeline-status.service';
import { TapService } from '../tap.service';
import { AuthService } from '../auth.service';

@Component({
  selector: 'app-pipelines',
  templateUrl: './pipelines.component.html',
  styleUrls: ['./pipelines.component.css']
})
export class PipelinesComponent implements OnInit, OnDestroy {
  pipelines: any[] = [];
  filteredPipelines: any[] = [];
  searchQuery = '';
  catalogGroups: Array<{name: string, pipelines: any[], expanded: boolean, deleting?: boolean}> = [];
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
      // Pause auto-refresh while the user is editing a row name so the input
      // doesn't get destroyed mid-edit when the table re-renders.
      if (this.editingName) return;
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
