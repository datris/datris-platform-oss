import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { PipelineService } from '../pipeline.service';

@Component({
    selector: 'app-pipeline-edit',
    templateUrl: './pipeline-edit.component.html',
    styleUrls: ['./pipeline-edit.component.css'],
    standalone: false
})
export class PipelineEditComponent implements OnInit {
  name = '';
  configJson = '';
  saving = false;
  error = '';
  success = '';

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private pipelineService: PipelineService
  ) { }

  ngOnInit(): void {
    this.name = this.route.snapshot.paramMap.get('name') || '';
    this.pipelineService.getPipeline(this.name).subscribe({
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

    this.pipelineService.createPipeline(config).subscribe({
      next: () => {
        this.success = 'Dataset saved successfully';
        this.saving = false;
        setTimeout(() => this.router.navigate(['/pipelines']), 1000);
      },
      error: (err) => {
        this.error = 'Failed to save: ' + (err.error || err.message);
        this.saving = false;
      }
    });
  }
}
