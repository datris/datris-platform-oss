import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { PipelineService } from '../pipeline.service';

@Component({
  selector: 'app-pipeline-view',
  templateUrl: './pipeline-view.component.html',
  styleUrls: ['./pipeline-view.component.css']
})
export class PipelineViewComponent implements OnInit {
  name = '';
  config: any = null;
  configJson = '';
  error = '';
  copySuccess = false;
  confirmDelete = false;
  deleteLoading = false;

  constructor(private route: ActivatedRoute, private router: Router, private pipelineService: PipelineService) { }

  ngOnInit(): void {
    this.name = this.route.snapshot.paramMap.get('name') || '';
    this.pipelineService.getPipeline(this.name).subscribe({
      next: (data) => {
        this.config = data;
        this.configJson = JSON.stringify(data, null, 2);
      },
      error: (err) => {
        this.error = err.error || err.message || 'Failed to load pipeline';
      }
    });
  }

  copyConfig(): void {
    navigator.clipboard.writeText(this.configJson).then(() => {
      this.copySuccess = true;
      setTimeout(() => this.copySuccess = false, 2000);
    });
  }

  editPipeline(): void {
    this.router.navigate(['/pipelines', this.name, 'edit']);
  }

  promptDelete(): void {
    this.confirmDelete = true;
  }

  cancelDelete(): void {
    this.confirmDelete = false;
  }

  deletePipeline(): void {
    this.deleteLoading = true;
    this.pipelineService.deletePipeline(this.name).subscribe({
      next: () => {
        this.router.navigate(['/pipelines']);
      },
      error: (err) => {
        this.error = err.error || err.message || 'Failed to delete pipeline';
        this.deleteLoading = false;
        this.confirmDelete = false;
      }
    });
  }
}
