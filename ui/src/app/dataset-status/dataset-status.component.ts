import { Component, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { DatasetStatusService, DatasetStatus } from '../dataset-status.service';

@Component({
  selector: 'app-dataset-status',
  templateUrl: './dataset-status.component.html',
  styleUrls: ['./dataset-status.component.css']
})
export class DatasetStatusComponent implements OnInit {
  datasets: DatasetStatus[] = [];
  page: number = 1;

  constructor(
    private datasetStatusService: DatasetStatusService,
    private router: Router
  ) { }

  ngOnInit(): void {
    this.loadData();
  }

  onRowClick(pipelineToken: string, dataset: string): void {
    console.log(pipelineToken)
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
}
