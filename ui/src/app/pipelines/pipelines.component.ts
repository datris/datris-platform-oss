import { Component, OnInit, OnDestroy } from '@angular/core';
import { Router } from '@angular/router';
import { PipelineService } from '../pipeline.service';

@Component({
  selector: 'app-pipelines',
  templateUrl: './pipelines.component.html',
  styleUrls: ['./pipelines.component.css']
})
export class PipelinesComponent implements OnInit, OnDestroy {
  pipelines: any[] = [];
  private refreshInterval: any;

  constructor(private pipelineService: PipelineService, private router: Router) { }

  ngOnInit(): void {
    this.loadPipelines();
    this.refreshInterval = setInterval(() => this.loadPipelines(), 5000);
  }

  ngOnDestroy(): void {
    if (this.refreshInterval) {
      clearInterval(this.refreshInterval);
    }
  }

  loadPipelines(): void {
    this.pipelineService.getPipelines().subscribe({
      next: (data) => this.pipelines = data || [],
      error: (err) => console.error('Failed to load pipelines', err)
    });
  }

  viewPipeline(name: string): void {
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
}
