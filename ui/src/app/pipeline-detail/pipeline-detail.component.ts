import { Component, OnInit, OnDestroy } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { PipelineStatusService, PipelineStatusDetail } from '../pipeline-status.service';

@Component({
  selector: 'app-pipeline-detail',
  templateUrl: './pipeline-detail.component.html',
  styleUrls: ['./pipeline-detail.component.css']
})
export class PipelineDetailComponent implements OnInit, OnDestroy {
  pipeline: string | null = '';
  pipelineStatusDetails: PipelineStatusDetail[] = [];
  private pipelineToken = '';
  private refreshInterval: any;

  constructor(
    private route: ActivatedRoute,
    private pipelineStatusService: PipelineStatusService,
  ) { }

  ngOnInit(): void {
    this.pipeline = this.route.snapshot.paramMap.get('pipeline');
    this.pipelineToken = this.route.snapshot.paramMap.get('pipelineToken')!;
    this.loadData();
    this.refreshInterval = setInterval(() => this.loadData(), 5000);
  }

  ngOnDestroy(): void {
    if (this.refreshInterval) {
      clearInterval(this.refreshInterval);
    }
  }

  copyToClipboard(text: string): void {
    navigator.clipboard.writeText(text);
  }

  private loadData(): void {
    this.pipelineStatusService.getPipelineStatusDetail(this.pipelineToken).subscribe(data => {
      this.pipelineStatusDetails = data;
    });
  }
}
