import { Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { DatasetStatusService, DatasetStatusDetail } from '../dataset-status.service';

@Component({
  selector: 'app-dataset-detail',
  templateUrl: './dataset-detail.component.html',
  styleUrls: ['./dataset-detail.component.css']
})
export class DatasetDetailComponent implements OnInit {
  dataset: string | null = '';
  datasetStatusDetails: DatasetStatusDetail[] = [];

  constructor(
    private route: ActivatedRoute,
    private datasetStatusService: DatasetStatusService,
  ) { }

  ngOnInit(): void {
    this.dataset = this.route.snapshot.paramMap.get('dataset');
    let pipelineToken = this.route.snapshot.paramMap.get('pipelineToken')!;

    this.datasetStatusService.getDatasetStatusDetail(pipelineToken).subscribe(data => {
      this.datasetStatusDetails = data;
    });
  }
}
