import { Component, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { DatasetService } from '../dataset.service';

@Component({
  selector: 'app-datasets',
  templateUrl: './datasets.component.html',
  styleUrls: ['./datasets.component.css']
})
export class DatasetsComponent implements OnInit {
  datasets: any[] = [];

  constructor(private datasetService: DatasetService, private router: Router) { }

  ngOnInit(): void {
    this.loadDatasets();
  }

  loadDatasets(): void {
    this.datasetService.getDatasets().subscribe({
      next: (data) => this.datasets = data || [],
      error: (err) => console.error('Failed to load datasets', err)
    });
  }

  viewDataset(name: string): void {
    this.router.navigate(['/datasets', name]);
  }

  deleteDataset(event: Event, name: string): void {
    event.stopPropagation();
    if (!confirm('Delete dataset "' + name + '"? This cannot be undone.')) return;

    this.datasetService.deleteDataset(name).subscribe({
      next: () => this.loadDatasets(),
      error: (err) => alert('Failed to delete dataset: ' + (err.error || err.message))
    });
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
