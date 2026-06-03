import { Component, OnDestroy, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom, forkJoin, of, Subscription } from 'rxjs';
import { catchError } from 'rxjs/operators';
import type { EChartsOption } from 'echarts';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { PipelineStatusService, PipelineStatus, PipelineStatusDetail } from '../pipeline-status.service';
import { AuthService } from '../auth.service';
import { OpsChatContextService } from '../ops-chat/ops-chat-context.service';
import { OpsActionBus } from '../ops-chat/ops-action-bus.service';
import { OpsAssistantStateService } from '../ops-chat/ops-assistant-state.service';

type Window = '24h' | '7d' | '30d';

interface StatTiles {
  runs: number;
  runsPrev: number;
  success: number;
  failure: number;
  items: number;
  records: number;
  docs: number;
}

interface FailingItem {
  kind: 'tap' | 'pipeline';
  name: string;
  catalog: string | null;
  reason: string;
  timeIso: string | null;
  recovered: boolean;
  failureCount: number;           // # of failures for this tap/pipeline within the window — reconciles the Failures tile (event count) with this pane (1 row per failing item)
  pipelineToken: string | null;  // set for pipeline failures — opens the ingestion detail view
  logs: string | null;            // tap-side stdout/stderr (tap failures only)
  // For pipeline-kind failures: the tap (if any) that feeds this pipeline,
  // so the Re-run button knows what to trigger. Direct-upload pipelines have
  // no associated tap and stay un-rerunnable from this view (the user has to
  // re-upload the source file).
  relatedTapName: string | null;
}

interface SuccessfulItem {
  kind: 'pipeline';               // pipeline jobs only — tap successes are represented downstream as pipeline jobs
  name: string;
  catalog: string | null;
  timeIso: string | null;         // latest successful run within the window
  successCount: number;           // # of successful runs for this pipeline within the window
  totalItems: number;             // sum of recordCount across successful runs
  dataType: string | null;        // 'document' | 'record' | null — drives the label suffix
  pipelineToken: string | null;   // token for the latest successful job — used to lazy-load the event detail when the row is expanded (same mechanism as the Failures pane)
}

interface StaleTap {
  name: string;
  catalog: string | null;
  cadenceLabel: string;
  cadenceMs: number;
  lastRunIso: string | null;
}

interface PipelineVolume {
  name: string;
  catalog: string | null;
  buckets: number[];    // selected window, oldest→newest (hourly for 24h, daily for 7d/30d)
  current: number;      // records in the selected window [now - windowMs, now]
  prior: number;        // records in the equal-length prior window
  deltaPct: number | null;
  sparkOptions: EChartsOption;
}

@Component({
  selector: 'app-activity',
  templateUrl: './activity.component.html',
  styleUrls: ['./activity.component.css']
})
export class ActivityComponent implements OnInit, OnDestroy {
  window: Window = '24h';
  loading = true;
  loadError = '';

  tiles: StatTiles = { runs: 0, runsPrev: 0, success: 0, failure: 0, items: 0, records: 0, docs: 0 };
  failing: FailingItem[] = [];
  successful: SuccessfulItem[] = [];
  stale: StaleTap[] = [];
  pipelineVolumes: PipelineVolume[] = [];

  // Taps with a manual re-run currently in flight. Used to swap the
  // "Re-run" / "Run now" button to a spinner + "Running…" label so the click
  // feels responsive instead of silent. Cleared when the underlying HTTP call
  // returns (success or error — either way, the dashboard reloads with the
  // fresh state, which is the real source of truth for "did it finish").
  runningTaps = new Set<string>();

  // Taps the Ops chat agent has just triggered via `run_tap`. Mirrors
  // runningTaps for UI purposes — the row's button shows a spinner — but
  // we don't have an HTTP response to clear them on, so we drop the whole
  // set on the next dashboard reload (the reloaded data is the source of
  // truth for whether the chat-triggered run is still in flight).
  chatRunningTaps = new Set<string>();
  private actionBusSub?: Subscription;

  runsChartOptions: EChartsOption | null = null;
  itemsChartOptions: EChartsOption | null = null;

  private refreshTimer: any;
  private readonly REFRESH_MS = 30_000;

  constructor(
    private tapService: TapService,
    private pipelineService: PipelineService,
    private pipelineStatusService: PipelineStatusService,
    private router: Router,
    public auth: AuthService,
    private opsContext: OpsChatContextService,
    private opsActionBus: OpsActionBus,
    private opsState: OpsAssistantStateService
  ) {}

  ngOnInit(): void {
    this.loadData();
    this.refreshTimer = setInterval(() => this.loadData(true), this.REFRESH_MS);
    // Chat-triggered tap runs flow through the action bus so the row's
    // button can flip to a spinner immediately, without waiting for the
    // 30s refresh to discover the in-flight run on its own.
    this.actionBusSub = this.opsActionBus.events$.subscribe(evt => {
      if (evt.kind === 'tap-run-started') {
        this.chatRunningTaps.add(evt.tapName);
      }
    });
  }

  ngOnDestroy(): void {
    if (this.refreshTimer) clearInterval(this.refreshTimer);
    this.actionBusSub?.unsubscribe();
  }

  setWindow(w: Window): void {
    if (this.window === w) return;
    this.window = w;
    this.loadData();
  }

  private windowMs(): number {
    return this.window === '24h' ? 24 * 3600_000
      : this.window === '7d' ? 7 * 86400_000
      : 30 * 86400_000;
  }

  successRate(): number {
    if (this.tiles.runs === 0) return 0;
    return Math.round((this.tiles.success / this.tiles.runs) * 100);
  }

  runsDeltaPct(): number | null {
    if (this.tiles.runsPrev === 0) return null;
    return Math.round(((this.tiles.runs - this.tiles.runsPrev) / this.tiles.runsPrev) * 100);
  }

  async loadData(silent = false): Promise<void> {
    if (!silent) {
      this.loading = true;
      this.loadError = '';
    }

    try {
      // Pull tap logs covering both the current window and the prior window so the
      // "vs prior" delta on the Runs tile is correct. Indexed time-range scan on
      // the server — single round trip regardless of tap count.
      const sinceMs = Date.now() - 2 * this.windowMs();

      const { taps, pipelines, jobsFirstPage, tapLogs } = await firstValueFrom(forkJoin({
        taps: this.tapService.getTaps().pipe(catchError(() => of([] as any[]))),
        pipelines: this.pipelineService.getPipelines().pipe(catchError(() => of([] as any[]))),
        jobsFirstPage: this.pipelineStatusService.getPipelineStatus(1).pipe(catchError(() => of([] as PipelineStatus[]))),
        tapLogs: this.tapService.getAllTapLogsSince(sinceMs).pipe(catchError(() => of([] as any[])))
      }));

      // Page through pipeline status until we cover the prior window too (for the
      // "vs prior" delta), capped to avoid runaway requests on chatty tenants.
      // Phase 4 will replace this with a server-side rollup.
      const jobs = await this.fetchPipelineJobsForWindow(jobsFirstPage || []);

      // Tap-side failures: tap log entries whose status indicates failure. A tap
      // success that pushed to a pipeline is represented downstream by its child
      // pipeline_token rows (in `jobs`), so we only surface failures here to
      // avoid double-counting a successful tap as both 1 tap + N pipeline jobs.
      const tapFailures = (tapLogs || []).filter((l: any) =>
        l && (l.status === 'failure' || l.status === 'error' || l.status === 'timed_out')
      );

      this.computeTiles(jobs, tapFailures);
      this.computeFailing(taps || [], pipelines || [], jobs, tapFailures);
      this.computeSuccessful(pipelines || [], jobs);
      this.computeStale(taps || []);
      this.computeCharts(jobs, tapFailures);
      this.computePipelineVolumes(pipelines || [], jobs);
      // Fresh data landed — chat-triggered optimistic spinners can clear,
      // since the row's actual state is now reflected in the lists below.
      this.chatRunningTaps.clear();
      this.publishOpsContext();
    } catch (err: any) {
      this.loadError = err?.message || 'Failed to load activity';
    } finally {
      this.loading = false;
    }
  }

  /** Push a compact dashboard snapshot to the chat panel's context service.
   *  Top-N caps keep the system prompt bounded — the chat doesn't need
   *  every row, just enough to ground its answers in the current state. */
  private publishOpsContext(): void {
    const TOP_FAILING = 20;
    const TOP_STALE = 10;
    const TOP_VOLUMES = 15;
    this.opsContext.publish({
      window: this.window,
      failingItems: this.failing.slice(0, TOP_FAILING).map(f => ({
        kind: f.kind,
        name: f.name,
        catalog: f.catalog,
        reason: f.reason,
        timeIso: f.timeIso,
        recovered: f.recovered,
        failureCount: f.failureCount,
        relatedTapName: f.relatedTapName,
        pipelineToken: f.pipelineToken
      })),
      staleTaps: this.stale.slice(0, TOP_STALE).map(s => ({
        name: s.name,
        catalog: s.catalog,
        cadenceLabel: s.cadenceLabel,
        lastRunIso: s.lastRunIso
      })),
      // Volumes sorted by largest |deltaPct| first so the chat sees
      // anomalies — over- and under-volume — before the long tail of
      // pipelines running at their usual rate.
      pipelineVolumes: this.pipelineVolumes
        .slice()
        .sort((a, b) => {
          const da = a.deltaPct == null ? -1 : Math.abs(a.deltaPct);
          const db = b.deltaPct == null ? -1 : Math.abs(b.deltaPct);
          return db - da;
        })
        .slice(0, TOP_VOLUMES)
        .map(v => ({
          name: v.name,
          catalog: v.catalog,
          current: v.current,
          prior: v.prior,
          deltaPct: v.deltaPct
        }))
    });
  }

  // Pulls additional pages until either (a) entries fall outside [now - 2×window,
  // now], or (b) we hit MAX_PAGES. Each page is 20 entries server-side.
  private async fetchPipelineJobsForWindow(firstPage: PipelineStatus[]): Promise<PipelineStatus[]> {
    const MAX_PAGES = 10;
    const cutoff = Date.now() - 2 * this.windowMs();
    const all: PipelineStatus[] = [...firstPage];

    // If the first page is already partial or its oldest entry predates the cutoff,
    // there's nothing more to fetch in the window.
    let oldest = this.oldestEntryMs(firstPage);
    if (firstPage.length < 20 || (oldest != null && oldest < cutoff)) return all;

    for (let page = 2; page <= MAX_PAGES; page++) {
      const next = await firstValueFrom(
        this.pipelineStatusService.getPipelineStatus(page).pipe(catchError(() => of([] as PipelineStatus[])))
      );
      if (!next || next.length === 0) break;
      all.push(...next);
      oldest = this.oldestEntryMs(next);
      if (next.length < 20 || (oldest != null && oldest < cutoff)) break;
    }
    return all;
  }

  private oldestEntryMs(jobs: PipelineStatus[]): number | null {
    let oldest: number | null = null;
    for (const j of jobs) {
      const t = this.parseTime(j.endTime) ?? this.parseTime(j.startTime);
      if (t == null) continue;
      if (oldest == null || t < oldest) oldest = t;
    }
    return oldest;
  }

  // Tiles merge two sources without double-counting:
  //   - Pipeline summary entries (one per ingest job; carries recordCount/dataType)
  //   - Tap-failure logs (taps that never reached the pipeline — pure pre-push failures)
  // A successful tap run is represented downstream by its child pipeline jobs in
  // `jobs`, so we never count successful tap logs here.
  private computeTiles(jobs: PipelineStatus[], tapFailures: any[]): void {
    const now = Date.now();
    const winMs = this.windowMs();
    const winStart = now - winMs;
    const prevStart = winStart - winMs;

    let runs = 0;
    let runsPrev = 0;
    let success = 0;
    let failure = 0;
    let records = 0;
    let docs = 0;

    for (const j of jobs) {
      const t = this.parseTime(j.endTime) ?? this.parseTime(j.startTime);
      if (t == null) continue;
      if (t >= winStart && t <= now) {
        runs++;
        const s = (j.status || '').toLowerCase();
        if (s === 'success') success++;
        else if (s === 'error' || s === 'failure' || s === 'timed_out') failure++;
        const count = Number(j.recordCount || 0);
        if (j.dataType === 'document') docs += count;
        else records += count;
      } else if (t >= prevStart && t < winStart) {
        runsPrev++;
      }
    }

    for (const log of tapFailures) {
      const t = this.parseTime(log.runTime);
      if (t == null) continue;
      if (t >= winStart && t <= now) {
        runs++;
        failure++;
      } else if (t >= prevStart && t < winStart) {
        runsPrev++;
      }
    }

    this.tiles = { runs, runsPrev, success, failure, items: records + docs, records, docs };
  }

  // Surfaces any tap or pipeline that had a failure within the window. Items
  // whose most-recent run is now healthy get a "recovered" flag, so the panel
  // doubles as a history view without losing visibility into current state.
  private computeFailing(taps: any[], pipelines: any[], jobs: PipelineStatus[], tapFailures: any[]): void {
    const now = Date.now();
    const winStart = now - this.windowMs();
    const items: FailingItem[] = [];

    // Tap-side: most recent failure per tap within the window. Tap is "recovered"
    // when its current lastRunStatus is success (TapRunner only updates that field
    // on mode='run', so it's a reliable per-tap "current state" signal).
    const tapByName = new Map<string, any>();
    for (const t of taps) tapByName.set(t.name, t);

    const latestTapFailure = new Map<string, any>();
    const tapFailureCount = new Map<string, number>();
    for (const log of tapFailures) {
      const t = this.parseTime(log.runTime);
      if (t == null || t < winStart || t > now) continue;
      tapFailureCount.set(log.tapName, (tapFailureCount.get(log.tapName) ?? 0) + 1);
      const cur = latestTapFailure.get(log.tapName);
      const curT = cur ? this.parseTime(cur.runTime) ?? 0 : -1;
      if (t > curT) latestTapFailure.set(log.tapName, log);
    }

    latestTapFailure.forEach((log, tapName) => {
      const tap = tapByName.get(tapName);
      // Tap is "recovered" when its current lastRunStatus is no longer a failure.
      // Both `success` (records landed) and `no_records` (ran cleanly, source empty
      // — legitimate for polling/incremental taps) count as healthy outcomes.
      // Without no_records here, a tap that succeeds with deduped-empty results
      // after a click-to-rerun would still appear "unrecovered" and the user sees
      // no visible change in the panel.
      const lastStatus = (tap?.lastRunStatus || '').toLowerCase();
      const recovered = lastStatus === 'success' || lastStatus === 'no_records';
      items.push({
        kind: 'tap',
        name: tapName,
        catalog: tap?.catalog || null,
        reason: log.error || 'Run failed',
        timeIso: log.runTime,
        recovered,
        failureCount: tapFailureCount.get(tapName) ?? 1,
        pipelineToken: null,
        logs: log.logs || null,
        relatedTapName: null
      });
    });

    // Build a reverse index: pipeline name → tap that most-recently fed it.
    // Used for pipeline-kind failures so the Re-run button can trigger the
    // source tap. If multiple taps target the same pipeline (rare but allowed),
    // the one with the most recent successful or attempted run wins — that's
    // the one most likely to reproduce/resolve the failure on re-run.
    const tapForPipeline = new Map<string, any>();
    for (const t of taps) {
      const target = t?.targetPipeline;
      if (!target) continue;
      const existing = tapForPipeline.get(target);
      if (!existing) {
        tapForPipeline.set(target, t);
        continue;
      }
      const existingMs = this.parseTime(existing.lastRunTime) ?? 0;
      const candidateMs = this.parseTime(t.lastRunTime) ?? 0;
      if (candidateMs > existingMs) tapForPipeline.set(target, t);
    }

    // Pipeline-side: most recent failure per pipeline within the window. Pipeline
    // is "recovered" when its overall latest job (anywhere in the pulled jobs set,
    // including outside the window) is success/warning.
    const latestJobByPipeline = new Map<string, PipelineStatus>();
    const latestFailureByPipeline = new Map<string, PipelineStatus>();
    const pipelineFailureCount = new Map<string, number>();
    for (const j of jobs) {
      if (!j.pipeline) continue;
      const t = this.parseTime(j.endTime) ?? this.parseTime(j.startTime);
      if (t == null) continue;

      const curLatest = latestJobByPipeline.get(j.pipeline);
      const curLatestT = curLatest
        ? (this.parseTime(curLatest.endTime) ?? this.parseTime(curLatest.startTime) ?? 0)
        : -1;
      if (t > curLatestT) latestJobByPipeline.set(j.pipeline, j);

      if (t < winStart || t > now) continue;
      const s = (j.status || '').toLowerCase();
      if (s !== 'error' && s !== 'failure' && s !== 'timed_out') continue;

      pipelineFailureCount.set(j.pipeline, (pipelineFailureCount.get(j.pipeline) ?? 0) + 1);

      const curFail = latestFailureByPipeline.get(j.pipeline);
      const curFailT = curFail
        ? (this.parseTime(curFail.endTime) ?? this.parseTime(curFail.startTime) ?? 0)
        : -1;
      if (t > curFailT) latestFailureByPipeline.set(j.pipeline, j);
    }

    const pipelineByName = new Map<string, any>();
    for (const p of pipelines) pipelineByName.set(p.name, p);

    latestFailureByPipeline.forEach((j, name) => {
      const latest = latestJobByPipeline.get(name);
      const latestStatus = (latest?.status || '').toLowerCase();
      const recovered = latestStatus === 'success' || latestStatus === 'warning';
      const relatedTap = tapForPipeline.get(name);
      items.push({
        kind: 'pipeline',
        name,
        catalog: pipelineByName.get(name)?.catalog || null,
        reason: 'Ingest failed',
        timeIso: j.endTime || j.startTime || null,
        recovered,
        failureCount: pipelineFailureCount.get(name) ?? 1,
        pipelineToken: j.pipelineToken || null,
        relatedTapName: relatedTap?.name || null,
        logs: null
      });
    });

    // Unrecovered first (still need attention), recovered after. Each group by
    // recency desc.
    items.sort((a, b) => {
      if (a.recovered !== b.recovered) return a.recovered ? 1 : -1;
      return (this.parseTime(b.timeIso) ?? 0) - (this.parseTime(a.timeIso) ?? 0);
    });
    this.failing = items;
  }

  unrecoveredCount(): number {
    return this.failing.filter(f => !f.recovered).length;
  }

  // Mirrors computeFailing for the happy path: one row per pipeline with at
  // least one successful job in the window. Tap successes that pushed to a
  // pipeline already appear here as their child pipeline job, so we don't
  // surface tap rows separately (which would double-count the same run).
  private computeSuccessful(pipelines: any[], jobs: PipelineStatus[]): void {
    const now = Date.now();
    const winStart = now - this.windowMs();

    const latestSuccess = new Map<string, PipelineStatus>();
    const successCount = new Map<string, number>();
    const itemsTotal = new Map<string, number>();
    const dataType = new Map<string, string | null>();

    for (const j of jobs) {
      if (!j.pipeline) continue;
      if ((j.status || '').toLowerCase() !== 'success') continue;
      const t = this.parseTime(j.endTime) ?? this.parseTime(j.startTime);
      if (t == null || t < winStart || t > now) continue;

      successCount.set(j.pipeline, (successCount.get(j.pipeline) ?? 0) + 1);
      itemsTotal.set(j.pipeline, (itemsTotal.get(j.pipeline) ?? 0) + Number(j.recordCount || 0));
      if (j.dataType) dataType.set(j.pipeline, j.dataType);

      const cur = latestSuccess.get(j.pipeline);
      const curT = cur ? (this.parseTime(cur.endTime) ?? this.parseTime(cur.startTime) ?? 0) : -1;
      if (t > curT) latestSuccess.set(j.pipeline, j);
    }

    const pipelineByName = new Map<string, any>();
    for (const p of pipelines) pipelineByName.set(p.name, p);

    const items: SuccessfulItem[] = [];
    latestSuccess.forEach((j, name) => {
      items.push({
        kind: 'pipeline',
        name,
        catalog: pipelineByName.get(name)?.catalog || null,
        timeIso: j.endTime || j.startTime || null,
        successCount: successCount.get(name) ?? 1,
        totalItems: itemsTotal.get(name) ?? 0,
        dataType: dataType.get(name) ?? null,
        pipelineToken: j.pipelineToken || null
      });
    });

    // Most recent first, then by items descending as a tiebreaker so a busy
    // pipeline that just ran sorts above a one-off that finished a moment later.
    items.sort((a, b) => {
      const ta = this.parseTime(a.timeIso) ?? 0;
      const tb = this.parseTime(b.timeIso) ?? 0;
      if (tb !== ta) return tb - ta;
      return b.totalItems - a.totalItems;
    });
    this.successful = items;
  }

  itemsLabel(s: SuccessfulItem): string {
    if (s.totalItems === 0) return '0 items';
    const formatted = this.formatNumber(s.totalItems);
    if (s.dataType === 'document') return `${formatted} doc${s.totalItems === 1 ? '' : 's'}`;
    return `${formatted} record${s.totalItems === 1 ? '' : 's'}`;
  }

  private computeStale(taps: any[]): void {
    const now = Date.now();
    const items: StaleTap[] = [];

    for (const t of taps) {
      if (!t.enabled) continue;
      if (!t.cronExpression) continue;
      const cadence = this.parseCronCadenceMs(t.cronExpression);
      if (cadence == null) continue;
      const lastMs = this.parseTime(t.lastRunTime);
      if (lastMs == null) continue;
      const sinceLast = now - lastMs;
      if (sinceLast > cadence * 2) {
        items.push({
          name: t.name,
          catalog: t.catalog || null,
          cadenceLabel: this.cronLabel(t.cronExpression, cadence),
          cadenceMs: cadence,
          lastRunIso: t.lastRunTime
        });
      }
    }

    items.sort((a, b) => (this.parseTime(a.lastRunIso) ?? 0) - (this.parseTime(b.lastRunIso) ?? 0));
    this.stale = items;
  }

  // ── Charts ────────────────────────────────────────────────────────────────
  // 24h → 24 hourly buckets, 7d/30d → daily buckets. Buckets are anchored to
  // local-time boundaries so "today" lines up with the user's wall clock.
  private computeCharts(jobs: PipelineStatus[], tapFailures: any[]): void {
    const bucketCount = this.window === '24h' ? 24 : (this.window === '7d' ? 7 : 30);
    const bucketMs = this.window === '24h' ? 3600_000 : 86400_000;
    const anchors = this.bucketAnchors(bucketCount, bucketMs);
    const labels = anchors.map(a => this.bucketLabel(a, this.window === '24h' ? 'hour' : 'day'));

    const successData = new Array(bucketCount).fill(0);
    const warningData = new Array(bucketCount).fill(0);
    const errorData = new Array(bucketCount).fill(0);
    const itemsData = new Array(bucketCount).fill(0);

    const firstAnchor = anchors[0];
    const lastAnchor = anchors[anchors.length - 1] + bucketMs;

    for (const j of jobs) {
      const t = this.parseTime(j.endTime) ?? this.parseTime(j.startTime);
      if (t == null || t < firstAnchor || t >= lastAnchor) continue;
      const idx = Math.floor((t - firstAnchor) / bucketMs);
      const s = (j.status || '').toLowerCase();
      if (s === 'success') successData[idx]++;
      else if (s === 'warning') warningData[idx]++;
      else if (s === 'error' || s === 'failure' || s === 'timed_out') errorData[idx]++;
      else successData[idx]++; // treat unknown/processing as success-ish to avoid scary red
      itemsData[idx] += Number(j.recordCount || 0);
    }

    for (const log of tapFailures) {
      const t = this.parseTime(log.runTime);
      if (t == null || t < firstAnchor || t >= lastAnchor) continue;
      const idx = Math.floor((t - firstAnchor) / bucketMs);
      errorData[idx]++;
    }

    this.runsChartOptions = this.buildRunsChartOptions(labels, successData, warningData, errorData);
    this.itemsChartOptions = this.buildItemsChartOptions(labels, itemsData);
  }

  private bucketAnchors(count: number, bucketMs: number): number[] {
    // For hourly: floor now to top of hour. For daily: floor to midnight (local).
    const now = new Date();
    let end: Date;
    if (bucketMs === 3600_000) {
      end = new Date(now.getFullYear(), now.getMonth(), now.getDate(), now.getHours());
    } else {
      end = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    }
    const endMs = end.getTime();
    const anchors: number[] = [];
    for (let i = count - 1; i >= 0; i--) anchors.push(endMs - i * bucketMs);
    return anchors;
  }

  private bucketLabel(anchorMs: number, granularity: 'hour' | 'day'): string {
    const d = new Date(anchorMs);
    if (granularity === 'hour') {
      const h = d.getHours();
      return (h < 10 ? '0' : '') + h + ':00';
    }
    const m = d.getMonth() + 1;
    const day = d.getDate();
    return m + '/' + day;
  }

  private buildRunsChartOptions(labels: string[], success: number[], warning: number[], error: number[]): EChartsOption {
    return {
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        backgroundColor: 'rgba(15, 23, 42, 0.95)',
        borderColor: 'rgba(99, 179, 237, 0.25)',
        textStyle: { color: '#f0f4ff', fontSize: 12 }
      },
      legend: {
        data: ['Success', 'Warning', 'Error'],
        textStyle: { color: '#94a3b8', fontSize: 11 },
        right: 0,
        top: 0
      },
      grid: { left: 30, right: 10, top: 28, bottom: 24 },
      xAxis: {
        type: 'category',
        data: labels,
        axisLine: { lineStyle: { color: 'rgba(99, 179, 237, 0.18)' } },
        axisLabel: { color: '#94a3b8', fontSize: 10 }
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: 'rgba(99, 179, 237, 0.08)' } },
        axisLabel: { color: '#94a3b8', fontSize: 10 }
      },
      series: [
        { name: 'Success', type: 'bar', stack: 'runs', data: success, itemStyle: { color: '#4ade80' }, barMaxWidth: 30 },
        { name: 'Warning', type: 'bar', stack: 'runs', data: warning, itemStyle: { color: '#fbbf24' }, barMaxWidth: 30 },
        { name: 'Error', type: 'bar', stack: 'runs', data: error, itemStyle: { color: '#f87171' }, barMaxWidth: 30 }
      ]
    };
  }

  private buildItemsChartOptions(labels: string[], items: number[]): EChartsOption {
    return {
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'axis',
        backgroundColor: 'rgba(15, 23, 42, 0.95)',
        borderColor: 'rgba(99, 179, 237, 0.25)',
        textStyle: { color: '#f0f4ff', fontSize: 12 },
        valueFormatter: (v: any) => this.formatNumber(Number(v))
      },
      grid: { left: 40, right: 10, top: 16, bottom: 24 },
      xAxis: {
        type: 'category',
        data: labels,
        boundaryGap: false,
        axisLine: { lineStyle: { color: 'rgba(99, 179, 237, 0.18)' } },
        axisLabel: { color: '#94a3b8', fontSize: 10 }
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: 'rgba(99, 179, 237, 0.08)' } },
        axisLabel: { color: '#94a3b8', fontSize: 10, formatter: (v: number) => this.formatNumber(v) }
      },
      series: [{
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 4,
        data: items,
        itemStyle: { color: '#00b4ff' },
        lineStyle: { color: '#00b4ff', width: 2 },
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(0, 180, 255, 0.35)' },
              { offset: 1, color: 'rgba(0, 180, 255, 0.02)' }
            ]
          }
        }
      }]
    };
  }

  // ── Per-pipeline volume table (always 7-day) ──────────────────────────────
  // Aggregates pipeline-summary recordCount by pipeline name for the selected
  // window (24h/7d/30d), so the table agrees with the tiles and charts above it.
  // `current` is the rolling selected window; `prior` is the equal-length window
  // before it; the sparkline buckets mirror the chart granularity (hourly for
  // 24h, daily for 7d/30d). Covers tap-fed AND direct-upload pipelines uniformly.
  private computePipelineVolumes(pipelines: any[], jobs: PipelineStatus[]): void {
    const now = Date.now();
    const winMs = this.windowMs();
    const winStart = now - winMs;
    const prevStart = winStart - winMs;

    // Sparkline buckets match computeCharts(): 24h → 24 hourly, 7d/30d → daily.
    const bucketCount = this.window === '24h' ? 24 : (this.window === '7d' ? 7 : 30);
    const bucketMs = this.window === '24h' ? 3600_000 : 86400_000;
    const anchors = this.bucketAnchors(bucketCount, bucketMs);
    const firstAnchor = anchors[0];
    const lastAnchor = anchors[anchors.length - 1] + bucketMs;

    const byPipeline = new Map<string, { current: number; prior: number; buckets: number[] }>();
    for (const p of pipelines) {
      if (p.name) byPipeline.set(p.name, { current: 0, prior: 0, buckets: new Array(bucketCount).fill(0) });
    }

    for (const j of jobs) {
      if (!j.pipeline) continue;
      const agg = byPipeline.get(j.pipeline);
      if (!agg) continue;
      const t = this.parseTime(j.endTime) ?? this.parseTime(j.startTime);
      if (t == null) continue;
      const records = Number(j.recordCount || 0);
      // Rolling current vs prior window for the headline numbers.
      if (t >= winStart && t <= now) agg.current += records;
      else if (t >= prevStart && t < winStart) agg.prior += records;
      // Anchored buckets for the trend sparkline.
      if (t >= firstAnchor && t < lastAnchor) {
        agg.buckets[Math.floor((t - firstAnchor) / bucketMs)] += records;
      }
    }

    const volumes: PipelineVolume[] = [];
    for (const p of pipelines) {
      const agg = byPipeline.get(p.name) || { current: 0, prior: 0, buckets: new Array(bucketCount).fill(0) };
      const deltaPct = agg.prior > 0 ? Math.round(((agg.current - agg.prior) / agg.prior) * 100) : null;
      volumes.push({
        name: p.name,
        catalog: p.catalog || null,
        buckets: agg.buckets,
        current: agg.current,
        prior: agg.prior,
        deltaPct,
        sparkOptions: this.buildSparkOptions(agg.buckets)
      });
    }

    // Default sort: highest current-window volume first, so busy pipelines surface.
    volumes.sort((a, b) => b.current - a.current);
    this.pipelineVolumes = volumes;
  }

  private buildSparkOptions(daily: number[]): EChartsOption {
    const hasData = daily.some(v => v > 0);
    return {
      backgroundColor: 'transparent',
      grid: { left: 0, right: 0, top: 2, bottom: 2 },
      xAxis: { type: 'category', show: false, data: daily.map((_, i) => i) },
      yAxis: { type: 'value', show: false, min: 0 },
      tooltip: { show: false },
      series: [{
        type: 'line',
        smooth: true,
        symbol: 'none',
        data: daily,
        lineStyle: { color: hasData ? '#00b4ff' : 'rgba(148, 163, 184, 0.4)', width: 1.5 },
        areaStyle: hasData ? {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(0, 180, 255, 0.35)' },
              { offset: 1, color: 'rgba(0, 180, 255, 0.02)' }
            ]
          }
        } : undefined
      }]
    };
  }

  deltaClass(d: number | null): string {
    if (d == null) return '';
    if (d <= -50) return 'delta-bad';
    if (d <= -20) return 'delta-warn';
    return '';
  }

  // ── Cron cadence heuristic ────────────────────────────────────────────────
  // Covers the presets in tap-create (`hourly`, `daily`, `weekdays`, `weekly`)
  // plus `0 0 */N * * ?` for every-N-hours. Returns null for cadences the
  // heuristic can't classify — those taps are skipped from staleness checks.
  private parseCronCadenceMs(cron: string): number | null {
    const c = cron.trim();
    if (c === '0 0 * * * ?') return 3600_000;          // hourly
    if (c === '0 0 0 * * ?') return 86400_000;          // daily
    if (c === '0 0 0 ? * MON-FRI') return 86400_000;    // daily weekdays
    if (c === '0 0 0 ? * MON') return 7 * 86400_000;    // weekly

    // every-N-hours: e.g. "0 0 */2 * * ?"
    const m = /^0\s+0\s+\*\/(\d+)\s+\*\s+\*\s+\?$/.exec(c);
    if (m) {
      const n = Number(m[1]);
      if (n > 0 && n <= 24) return n * 3600_000;
    }
    return null;
  }

  private cronLabel(_cron: string, cadenceMs: number): string {
    if (cadenceMs === 3600_000) return 'hourly';
    if (cadenceMs === 86400_000) return 'daily';
    if (cadenceMs === 7 * 86400_000) return 'weekly';
    const h = Math.round(cadenceMs / 3600_000);
    return `every ${h}h`;
  }

  // ── Time helpers ──────────────────────────────────────────────────────────
  private parseTime(t: string | null | undefined): number | null {
    if (!t) return null;
    const n = Date.parse(t);
    return isNaN(n) ? null : n;
  }

  formatRelative(iso: string | null): string {
    const t = this.parseTime(iso);
    if (t == null) return '—';
    const diff = Date.now() - t;
    if (diff < 60_000) return 'just now';
    if (diff < 3600_000) return `${Math.floor(diff / 60_000)}m ago`;
    if (diff < 86400_000) return `${Math.floor(diff / 3600_000)}h ago`;
    return `${Math.floor(diff / 86400_000)}d ago`;
  }

  formatNumber(n: number): string {
    if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
    if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
    return String(n);
  }

  // ── Actions ───────────────────────────────────────────────────────────────
  openTap(name: string): void {
    this.router.navigate(['/taps', name, 'edit']);
  }

  openPipeline(name: string): void {
    this.router.navigate(['/pipelines', name]);
  }

  // In-place expansion of the failure row — shows per-job error/warning events
  // for pipeline failures (lazy-fetched via getPipelineStatusDetail) and the
  // tap script's logs/error for tap failures. One row open at a time.
  expandedKey: string | null = null;
  detailsCache = new Map<string, PipelineStatusDetail[]>();
  detailsLoading = new Set<string>();
  detailsErrors = new Map<string, string>();

  rowKey(f: FailingItem): string {
    return f.kind === 'pipeline'
      ? 'pipeline:' + (f.pipelineToken || f.name)
      : 'tap:' + f.name + ':' + (f.timeIso || '');
  }

  isExpanded(f: FailingItem): boolean {
    return this.expandedKey === this.rowKey(f);
  }

  toggleExpand(f: FailingItem): void {
    const key = this.rowKey(f);
    if (this.expandedKey === key) {
      this.expandedKey = null;
      return;
    }
    this.expandedKey = key;
    if (f.kind === 'pipeline' && f.pipelineToken
        && !this.detailsCache.has(key) && !this.detailsLoading.has(key)) {
      this.detailsLoading.add(key);
      this.detailsErrors.delete(key);
      this.pipelineStatusService.getPipelineStatusDetail(f.pipelineToken).subscribe({
        next: (details) => {
          this.detailsCache.set(key, details || []);
          this.detailsLoading.delete(key);
        },
        error: (err) => {
          this.detailsErrors.set(key, err?.message || 'Failed to load detail');
          this.detailsLoading.delete(key);
        }
      });
    }
  }

  // Returns all events for an expanded pipeline row in execution order (oldest
  // first), so the reader can see how the job progressed before it failed —
  // begin → processing steps → end. Error/warning rows are visually highlighted
  // in the template via the code column.
  jobEvents(f: FailingItem): PipelineStatusDetail[] {
    const all = this.detailsCache.get(this.rowKey(f)) || [];
    return all
      .slice()
      .sort((a, b) => (Date.parse(a.dateTime) || 0) - (Date.parse(b.dateTime) || 0));
  }

  isDetailsLoading(f: FailingItem): boolean {
    return this.detailsLoading.has(this.rowKey(f));
  }

  detailsErrorFor(f: FailingItem): string | undefined {
    return this.detailsErrors.get(this.rowKey(f));
  }

  isTapRunning(name: string): boolean {
    return this.runningTaps.has(name) || this.chatRunningTaps.has(name);
  }

  // Successes pane uses the same expansion mechanics as Failures so the user's
  // muscle memory carries over: click a row to see the job's event trail.
  // Shares `expandedKey` / `detailsCache` / `detailsLoading` / `detailsErrors`
  // with the failures pane so only ONE row across both panes is open at a
  // time — opening a success collapses an open failure, and vice versa.

  successRowKey(s: SuccessfulItem): string {
    return 'success:' + (s.pipelineToken || s.name);
  }

  isSuccessExpanded(s: SuccessfulItem): boolean {
    return this.expandedKey === this.successRowKey(s);
  }

  toggleSuccessExpand(s: SuccessfulItem): void {
    const key = this.successRowKey(s);
    if (this.expandedKey === key) {
      this.expandedKey = null;
      return;
    }
    this.expandedKey = key;
    if (s.pipelineToken && !this.detailsCache.has(key) && !this.detailsLoading.has(key)) {
      this.detailsLoading.add(key);
      this.detailsErrors.delete(key);
      this.pipelineStatusService.getPipelineStatusDetail(s.pipelineToken).subscribe({
        next: (details) => {
          this.detailsCache.set(key, details || []);
          this.detailsLoading.delete(key);
        },
        error: (err) => {
          this.detailsErrors.set(key, err?.message || 'Failed to load detail');
          this.detailsLoading.delete(key);
        }
      });
    }
  }

  successJobEvents(s: SuccessfulItem): PipelineStatusDetail[] {
    const all = this.detailsCache.get(this.successRowKey(s)) || [];
    return all.slice().sort((a, b) => (Date.parse(a.dateTime) || 0) - (Date.parse(b.dateTime) || 0));
  }

  isSuccessDetailsLoading(s: SuccessfulItem): boolean {
    return this.detailsLoading.has(this.successRowKey(s));
  }

  successDetailsErrorFor(s: SuccessfulItem): string | undefined {
    return this.detailsErrors.get(this.successRowKey(s));
  }

  /** Ask-about-this handler used by failure / volume rows. Hands a
   *  row-specific prompt to the chat panel's state service — seedDraft
   *  also emits openRequested$, so the panel expands and focuses itself. */
  askAboutFailure(f: FailingItem, event: Event): void {
    event.stopPropagation();
    const subject = f.kind === 'tap' ? `tap \`${f.name}\`` : `pipeline \`${f.name}\``;
    const prompt = f.recovered
      ? `What happened with ${subject}? It's recovered now, but I want to know what went wrong and whether to expect it again.`
      : `Why is ${subject} failing, and what should I do about it?`;
    this.opsState.seedDraft(prompt);
  }

  askAboutVolume(v: PipelineVolume, event: Event): void {
    event.stopPropagation();
    const direction = v.deltaPct != null && v.deltaPct < 0 ? 'low' : 'high';
    const pct = v.deltaPct != null ? `${v.deltaPct >= 0 ? '+' : ''}${v.deltaPct}%` : 'an unusual amount';
    const prompt = `Pipeline \`${v.name}\` is ${pct} this ${this.window} versus the prior ${this.window} (${direction} volume). Is this expected, and what should I check?`;
    this.opsState.seedDraft(prompt);
  }

  // trackBy keys so the 30s auto-refresh doesn't rebuild every <li> (which
  // would reset scrollTop on the panel-list-scroll containers and yank the
  // user back to the top of an expanded failure / long success list).
  trackByFailing = (_i: number, f: FailingItem): string => this.rowKey(f);
  trackBySuccess = (_i: number, s: SuccessfulItem): string => s.kind + ':' + s.name;
  trackByVolume  = (_i: number, v: PipelineVolume): string => v.name;
  trackByStale   = (_i: number, s: StaleTap): string => s.name;

  runTap(name: string, event: Event): void {
    event.stopPropagation();
    // Guard against double-click while a run is already in flight — the
    // button's disabled state covers it visually, but a second tap that
    // sneaks through (e.g. keyboard repeat) would fire a duplicate /tap/run.
    if (this.runningTaps.has(name)) return;
    this.runningTaps.add(name);
    this.tapService.runTap(name, 'run').subscribe({
      next: () => {
        this.runningTaps.delete(name);
        this.loadData(true);
      },
      error: () => {
        this.runningTaps.delete(name);
        this.loadData(true);
      }
    });
  }
}
