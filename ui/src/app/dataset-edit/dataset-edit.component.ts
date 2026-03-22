import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { DatasetService } from '../dataset.service';

@Component({
  selector: 'app-dataset-edit',
  templateUrl: './dataset-edit.component.html',
  styleUrls: ['./dataset-edit.component.css']
})
export class DatasetEditComponent implements OnInit {
  name = '';
  configJson = '';
  saving = false;
  error = '';
  success = '';

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private datasetService: DatasetService
  ) { }

  ngOnInit(): void {
    this.name = this.route.snapshot.paramMap.get('name') || '';
    this.datasetService.getDataset(this.name).subscribe({
      next: (data) => {
        this.configJson = JSON.stringify(data, null, 2);
      },
      error: (err) => {
        this.error = err.error || err.message || 'Failed to load dataset';
      }
    });
  }

  save(): void {
    this.saving = true;
    this.error = '';
    this.success = '';

    let config;
    try {
      config = JSON.parse(this.configJson);
    } catch (e) {
      this.error = 'Invalid JSON';
      this.saving = false;
      return;
    }

    this.datasetService.createDataset(config).subscribe({
      next: () => {
        this.success = 'Dataset saved successfully';
        this.saving = false;
        setTimeout(() => this.router.navigate(['/datasets']), 1000);
      },
      error: (err) => {
        this.error = 'Failed to save: ' + (err.error || err.message);
        this.saving = false;
      }
    });
  }
}
