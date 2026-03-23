import { Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { PipelineStatusService, PipelineStatusDetail } from '../pipeline-status.service';

@Component({
  selector: 'app-pipeline-detail',
  templateUrl: './pipeline-detail.component.html',
  styleUrls: ['./pipeline-detail.component.css']
})
export class PipelineDetailComponent implements OnInit {
  pipeline: string | null = '';
  pipelineStatusDetails: PipelineStatusDetail[] = [];

  constructor(
    private route: ActivatedRoute,
    private pipelineStatusService: PipelineStatusService,
  ) { }

  ngOnInit(): void {
    this.pipeline = this.route.snapshot.paramMap.get('pipeline');
    let pipelineToken = this.route.snapshot.paramMap.get('pipelineToken')!;

    this.pipelineStatusService.getPipelineStatusDetail(pipelineToken).subscribe(data => {
      this.pipelineStatusDetails = data;
    });
  }
}
