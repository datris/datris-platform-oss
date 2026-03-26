import { Component, OnInit, OnDestroy } from '@angular/core';
import { Router } from '@angular/router';
import { PipelineStatusService, PipelineStatus } from '../pipeline-status.service';
import { PipelineService } from '../pipeline.service';

@Component({
  selector: 'app-pipeline-status',
  templateUrl: './pipeline-status.component.html',
  styleUrls: ['./pipeline-status.component.css']
})
export class PipelineStatusComponent implements OnInit, OnDestroy {
  pipelines: PipelineStatus[] = [];
  page: number = 1;
  private refreshInterval: any;

  // Clear all
  confirmClear = false;
  clearing = false;

  // Upload modal
  showUploadModal = false;
  allPipelines: any[] = [];
  selectedPipeline = '';
  selectedPipelineConfig: any = null;
  uploadFile: File | null = null;
  uploading = false;
  uploadResult = '';
  uploadError = '';

  constructor(
    private pipelineStatusService: PipelineStatusService,
    private pipelineService: PipelineService,
    private router: Router
  ) { }

  ngOnInit(): void {
    this.loadData();
    this.refreshInterval = setInterval(() => this.loadData(), 5000);
  }

  ngOnDestroy(): void {
    if (this.refreshInterval) {
      clearInterval(this.refreshInterval);
    }
  }

  promptClear(): void {
    this.confirmClear = true;
  }

  cancelClear(): void {
    this.confirmClear = false;
  }

  clearAll(): void {
    this.clearing = true;
    this.pipelineStatusService.clearAllStatus().subscribe({
      next: () => {
        this.clearing = false;
        this.confirmClear = false;
        this.pipelines = [];
        this.page = 1;
        this.loadData();
      },
      error: () => {
        this.clearing = false;
        this.confirmClear = false;
      }
    });
  }

  onRowClick(pipelineToken: string, pipeline: string): void {
    this.router.navigate(['/pipeline', pipelineToken, pipeline]);
  }

  onNextPage(): void {
    this.page++;
    this.loadData();
  }

  onPreviousPage(): void {
    if (this.page > 1) {
      this.page--;
      this.loadData();
    }
  }

  loadData(): void {
    this.pipelineStatusService.getPipelineStatus(this.page).subscribe(data => {
      this.pipelines = data;
    });
  }

  openUploadModal(): void {
    this.showUploadModal = true;
    this.selectedPipeline = '';
    this.selectedPipelineConfig = null;
    this.uploadFile = null;
    this.uploading = false;
    this.uploadResult = '';
    this.uploadError = '';
    this.pipelineService.getPipelines().subscribe(data => {
      this.allPipelines = data || [];
    });
  }

  closeUploadModal(): void {
    this.showUploadModal = false;
  }

  onPipelineSelected(): void {
    this.selectedPipelineConfig = null;
    if (this.selectedPipeline) {
      this.pipelineService.getPipeline(this.selectedPipeline).subscribe(config => {
        this.selectedPipelineConfig = config;
      });
    }
  }

  getSourceType(config: any): string {
    if (!config?.source?.fileAttributes) return 'Unknown';
    const fa = config.source.fileAttributes;
    if (fa.unstructuredAttributes) return 'Document (' + fa.unstructuredAttributes.fileExtension + ')';
    if (fa.csvAttributes) return 'CSV';
    if (fa.jsonAttributes) return 'JSON';
    if (fa.xmlAttributes) return 'XML';
    if (fa.xlsAttributes) return 'Excel';
    return 'File';
  }

  getDestinations(config: any): string {
    if (!config?.destination) return '';
    const dests: string[] = [];
    if (config.destination.database?.usePostgres) dests.push('PostgreSQL');
    if (config.destination.database?.useMongoDB) dests.push('MongoDB');
    if (config.destination.objectStore) dests.push('Object Store');
    if (config.destination.kafka) dests.push('Kafka');
    if (config.destination.activeMQ) dests.push('ActiveMQ');
    if (config.destination.qdrant) dests.push('Qdrant');
    if (config.destination.weaviate) dests.push('Weaviate');
    if (config.destination.milvus) dests.push('Milvus');
    if (config.destination.chroma) dests.push('Chroma');
    if (config.destination.pgvector) dests.push('pgvector');
    return dests.join(', ');
  }

  getSchemaFields(config: any): string {
    if (!config?.source?.schemaProperties?.fields) return 'None (unstructured)';
    return config.source.schemaProperties.fields.map((f: any) => f.name + ' (' + f.type + ')').join(', ');
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.uploadFile = input.files[0];
    }
  }

  upload(): void {
    if (!this.uploadFile || !this.selectedPipeline) return;
    this.uploading = true;
    this.uploadError = '';
    this.uploadResult = '';

    this.pipelineStatusService.uploadFile(this.uploadFile, this.selectedPipeline).subscribe({
      next: (result) => {
        this.uploadResult = result || 'Upload submitted successfully';
        this.uploading = false;
        setTimeout(() => {
          this.closeUploadModal();
          this.loadData();
        }, 1500);
      },
      error: (err) => {
        this.uploadError = err.error || err.message || 'Upload failed';
        this.uploading = false;
      }
    });
  }
}
