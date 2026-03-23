import { Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
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

  constructor(private route: ActivatedRoute, private pipelineService: PipelineService) { }

  ngOnInit(): void {
    this.name = this.route.snapshot.paramMap.get('name') || '';
    this.pipelineService.getPipeline(this.name).subscribe({
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
