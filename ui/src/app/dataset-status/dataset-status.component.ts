import { Component, OnInit, OnDestroy } from '@angular/core';
import { Router } from '@angular/router';
import { DatasetStatusService, DatasetStatus } from '../dataset-status.service';
import { DatasetService } from '../dataset.service';

@Component({
  selector: 'app-dataset-status',
  templateUrl: './dataset-status.component.html',
  styleUrls: ['./dataset-status.component.css']
})
export class DatasetStatusComponent implements OnInit, OnDestroy {
  datasets: DatasetStatus[] = [];
  page: number = 1;
  private refreshInterval: any;

  // Upload modal
  showUploadModal = false;
  allDatasets: any[] = [];
  selectedDataset = '';
  selectedDatasetConfig: any = null;
  uploadFile: File | null = null;
  uploading = false;
  uploadResult = '';
  uploadError = '';

  constructor(
    private datasetStatusService: DatasetStatusService,
    private datasetService: DatasetService,
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

  onRowClick(pipelineToken: string, dataset: string): void {
    this.router.navigate(['/dataset', pipelineToken, dataset]);
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
    this.datasetStatusService.getDatasetStatus(this.page).subscribe(data => {
      this.datasets = data;
    });
  }

  openUploadModal(): void {
    this.showUploadModal = true;
    this.selectedDataset = '';
    this.selectedDatasetConfig = null;
    this.uploadFile = null;
    this.uploading = false;
    this.uploadResult = '';
    this.uploadError = '';
    this.datasetService.getDatasets().subscribe(data => {
      this.allDatasets = data || [];
    });
  }

  closeUploadModal(): void {
    this.showUploadModal = false;
  }

  onDatasetSelected(): void {
    this.selectedDatasetConfig = null;
    if (this.selectedDataset) {
      this.datasetService.getDataset(this.selectedDataset).subscribe(config => {
        this.selectedDatasetConfig = config;
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
    if (!this.uploadFile || !this.selectedDataset) return;
    this.uploading = true;
    this.uploadError = '';
    this.uploadResult = '';

    this.datasetStatusService.uploadFile(this.uploadFile, this.selectedDataset).subscribe({
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
