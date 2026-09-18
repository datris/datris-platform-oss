import { Component, OnInit, OnDestroy } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { PipelineStatusService, PipelineStatusDetail, PipelineJobRollup, PipelineResultPage } from '../pipeline-status.service';

@Component({
    selector: 'app-pipeline-detail',
    templateUrl: './pipeline-detail.component.html',
    styleUrls: ['./pipeline-detail.component.css'],
    standalone: false
})
export class PipelineDetailComponent implements OnInit, OnDestroy {
  pipeline: string | null = '';
  pipelineStatusDetails: PipelineStatusDetail[] = [];
  showHistory = false;
  /** The rollup entry for this run; carries `resultUri` when the pipeline is
   *  scratch and the run produced a result (null on older servers). */
  resultJob: PipelineJobRollup | null = null;
  /** The scratch result panel: hidden until "View result", then a page,
   *  or an error message that replaces the grid. Polling never touches it. */
  resultOpen = false;
  resultLoading = false;
  resultPage: PipelineResultPage | null = null;
  resultError = '';
  private resultOffset = 0;
  /** Offsets of the pages before the current one, so Prev steps back
   *  exactly where Next came from (limit is clamped server-side, so the page
   *  size is only known from `returnedCount`). */
  private resultOffsetHistory: number[] = [];
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

  /** The AI fix-suggestion event for this run, if one was recorded. */
  get suggestion(): PipelineStatusDetail | undefined {
    return this.pipelineStatusDetails.find(d => !!d.aiDiagnosis || !!d.aiSuggestion);
  }

  openHistory(): void {
    this.showHistory = true;
  }

  closeHistory(): void {
    this.showHistory = false;
  }

  onHistoryRestored(): void {
    this.loadData();
  }

  /** True when the run carries a scratch result to read back. */
  get hasResult(): boolean {
    return !!this.resultJob?.resultUri;
  }

  /** Column headers for the result grid: the keys of the first record, in order. */
  get resultColumns(): string[] {
    const first = this.resultPage?.records?.[0];
    return first && typeof first === 'object' ? Object.keys(first) : [];
  }

  get canPrev(): boolean {
    return !this.resultLoading && this.resultOffsetHistory.length > 0;
  }

  get canNext(): boolean {
    return !this.resultLoading && !!this.resultPage?.truncated;
  }

  /** First page: offset 0, no limit — the server clamps to its inline cap. */
  viewResult(): void {
    this.resultOpen = true;
    this.resultOffsetHistory = [];
    this.loadResultPage(0);
  }

  nextPage(): void {
    if (!this.canNext || !this.resultPage) return;
    this.resultOffsetHistory.push(this.resultOffset);
    this.loadResultPage(this.resultOffset + this.resultPage.returnedCount);
  }

  prevPage(): void {
    if (!this.canPrev) return;
    this.loadResultPage(this.resultOffsetHistory.pop()!);
  }

  formatCell(value: any): string {
    if (value === null || value === undefined) return '';
    return typeof value === 'object' ? JSON.stringify(value) : String(value);
  }

  private loadResultPage(offset: number): void {
    this.resultLoading = true;
    this.resultError = '';
    const offsetArg = offset > 0 ? offset : undefined;
    this.pipelineStatusService.getPipelineResult(this.pipelineToken, offsetArg).subscribe({
      next: page => {
        this.resultOffset = page?.offset ?? offset;
        this.resultPage = page;
        this.resultLoading = false;
      },
      error: err => {
        this.resultPage = null;
        this.resultLoading = false;
        const status = err?.status;
        if (status === 410) {
          this.resultError = 'This result has expired — run the pipeline again.';
        } else if (status === 404) {
          this.resultError = 'This pipeline has no result — only scratch pipelines return rows.';
        } else {
          this.resultError = 'Could not load the result' + (err?.error?.error ? ': ' + err.error.error : '.');
        }
      }
    });
  }

  private loadData(): void {
    this.pipelineStatusService.getPipelineStatusDetailWithRollup(this.pipelineToken).subscribe(res => {
      this.pipelineStatusDetails = res?.events || [];
      const jobs = res?.rollup?.jobs || [];
      this.resultJob = jobs.find(j => j.pipelineToken === this.pipelineToken) || null;
    });
  }
}
