import { Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { DatasetService } from '../dataset.service';

@Component({
  selector: 'app-dataset-view',
  templateUrl: './dataset-view.component.html',
  styleUrls: ['./dataset-view.component.css']
})
export class DatasetViewComponent implements OnInit {
  name = '';
  config: any = null;
  configJson = '';
  error = '';

  constructor(private route: ActivatedRoute, private datasetService: DatasetService) { }

  ngOnInit(): void {
    this.name = this.route.snapshot.paramMap.get('name') || '';
    this.datasetService.getDataset(this.name).subscribe({
      next: (data) => {
        this.config = data;
        this.configJson = JSON.stringify(data, null, 2);
      },
      error: (err) => {
        this.error = err.error || err.message || 'Failed to load dataset';
      }
    });
  }
}
