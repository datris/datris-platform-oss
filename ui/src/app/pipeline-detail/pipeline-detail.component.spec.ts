/**
 * Story: Scratch destination in the UI and the docs
 * (plans/stories/scratch-ui-docs.md) — pipeline-detail.component.spec.ts bullet.
 *
 * Runs the real PipelineStatusService against HttpTestingController so the
 * wire contract is pinned, not a mock:
 *  - loadData() calls GET /api/v1/pipeline/status?pipelinetoken=<tok>&withrollup=true
 *    and still renders the wrapper's `events` array;
 *  - a rollup job with no `resultUri` (old-server shape) renders no
 *    "View result" action and no result panel;
 *  - "View result" calls GET /api/v1/pipeline/result?pipelinetoken=<tok> (no
 *    limit; offset 0 or absent) and renders a `table.results-table` grid;
 *  - Next requests offset = the last returnedCount, Prev goes back to 0;
 *  - Next is disabled when `truncated` is false, Prev at offset 0;
 *  - the footer reads "showing <returnedCount> of <rowCount>, expires at <resultExpiresAt>";
 *  - a 410 replaces the panel body with the expired message.
 *
 * DOM contract (the story names no ids): the action is a <button> whose text
 * matches /view result/i; paging buttons match /^next$/i and /^prev(ious)?$/i;
 * the grid is `table.results-table` like the tap test page.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';

import { PipelineDetailComponent } from './pipeline-detail.component';

const TOKEN = 'tok-scratch-1';

const events = [
  { dateTime: '2026-09-18T10:00:00Z', pipeline: 'answer-now', processName: 'validation', publisherToken: 'pub-1',
    pipelineToken: TOKEN, filename: 'rows.csv', state: 'completed', code: 'info', description: 'Validation passed' },
  { dateTime: '2026-09-18T10:00:05Z', pipeline: 'answer-now', processName: 'end', publisherToken: 'pub-1',
    pipelineToken: TOKEN, filename: 'rows.csv', state: 'completed', code: 'info', description: 'Scratch result: 300 record(s)' }
];

function rollupResponse(job: any) {
  return {
    rollup: { allDone: true, status: 'success', jobs: [job] },
    events
  };
}

const baseJob = {
  pipelineToken: TOKEN, pipeline: 'answer-now', filename: 'rows.csv', status: 'success',
  startedAt: '2026-09-18T10:00:00Z', lastEventAt: '2026-09-18T10:00:05Z', elapsed: '5s', lastError: null
};

const scratchJob = {
  ...baseJob,
  resultUri: 's3://local-data/scratch/answer-now/' + TOKEN + '.jsonl',
  resultRowCount: 300,
  resultExpiresAt: '2026-09-19T10:00:05Z',
  resultTruncated: true
};

function page(offset: number, count: number, rowCount = 300) {
  const records = Array.from({ length: count }, (_, i) => ({ id: String(offset + i + 1), city: 'c' + (offset + i + 1) }));
  return {
    records,
    rowCount,
    returnedCount: count,
    offset,
    truncated: offset + count < rowCount,
    resultUri: scratchJob.resultUri,
    resultExpiresAt: scratchJob.resultExpiresAt
  };
}

describe('PipelineDetailComponent — scratch View result panel', () => {
  let fixture: ComponentFixture<PipelineDetailComponent>;
  let el: HTMLElement;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [PipelineDetailComponent],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ pipeline: 'answer-now', pipelineToken: TOKEN }) } } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(PipelineDetailComponent);
    el = fixture.nativeElement as HTMLElement;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    fixture.destroy();       // clears the 5s polling interval
    http.verify();
  });

  function params(req: TestRequest): URLSearchParams {
    const q = req.request.urlWithParams.split('?')[1] || '';
    return new URLSearchParams(q);
  }

  /** ngOnInit → loadData(); answer the rollup status call with the given job. */
  function init(job: any): void {
    fixture.detectChanges();
    const req = http.expectOne(r => r.method === 'GET' && r.url.startsWith('/api/v1/pipeline/status'));
    const p = params(req);
    expect(p.get('pipelinetoken')).toBe(TOKEN);
    expect(p.get('withrollup')).withContext('run detail now asks for the rollup').toBe('true');
    req.flush(rollupResponse(job));
    fixture.detectChanges();
  }

  function button(re: RegExp): HTMLButtonElement | null {
    return (Array.from(el.querySelectorAll('button')) as HTMLButtonElement[])
      .find(b => re.test((b.textContent || '').trim())) || null;
  }

  function expectResultRequest(): TestRequest {
    return http.expectOne(r => r.method === 'GET' && r.url.startsWith('/api/v1/pipeline/result'));
  }

  /** Click View result and answer the first page (server-clamped to 200 of 300). */
  function openResult(): void {
    const view = button(/view\s*result/i);
    expect(view).withContext('View result button').not.toBeNull();
    view!.click();
    const req = expectResultRequest();
    const p = params(req);
    expect(p.get('pipelinetoken')).toBe(TOKEN);
    expect(p.get('limit')).withContext('first page sends no limit; the server clamps').toBeNull();
    expect(p.get('offset') === null || p.get('offset') === '0').withContext('offset: ' + p.get('offset')).toBeTrue();
    req.flush(page(0, 200));
    fixture.detectChanges();
  }

  it('still renders the event stream from the rollup wrapper', () => {
    init(baseJob);
    const rows = el.querySelectorAll('.table-container > table:not(.results-table) tbody tr');
    expect(rows.length).toBe(2);
    expect(el.textContent).toContain('Validation passed');
  });

  it('no View result action when the rollup carries no resultUri', () => {
    init(baseJob);
    expect(button(/view\s*result/i)).withContext('old-server shape: no action').toBeNull();
    expect(el.querySelector('table.results-table')).toBeNull();
    expect(el.textContent).not.toMatch(/showing\s+\d+\s+of\s+\d+/i);
  });

  it('View result renders the grid from GET /api/v1/pipeline/result', () => {
    init(scratchJob);
    openResult();
    const grid = el.querySelector('table.results-table');
    expect(grid).withContext('table.results-table like the tap test grid').not.toBeNull();
    const headers = Array.from(grid!.querySelectorAll('thead th')).map(th => (th.textContent || '').trim());
    expect(headers).toEqual(['id', 'city']);
    const bodyRows = grid!.querySelectorAll('tbody tr');
    expect(bodyRows.length).toBe(200);
    const firstCells = Array.from(bodyRows[0].querySelectorAll('td')).map(td => (td.textContent || '').trim());
    expect(firstCells).toEqual(['1', 'c1']);
  });

  it('Next requests offset = returnedCount, Prev goes back', () => {
    init(scratchJob);
    openResult();

    const next = button(/^next$/i);
    expect(next).withContext('Next button').not.toBeNull();
    expect(next!.disabled).withContext('Next enabled while truncated').toBeFalse();
    next!.click();
    const req2 = expectResultRequest();
    expect(params(req2).get('offset')).toBe('200');
    expect(params(req2).get('limit')).toBeNull();
    req2.flush(page(200, 100));
    fixture.detectChanges();
    const firstCells = Array.from(el.querySelectorAll('table.results-table tbody tr')[0].querySelectorAll('td'))
      .map(td => (td.textContent || '').trim());
    expect(firstCells[0]).toBe('201');

    const prev = button(/^prev(ious)?$/i);
    expect(prev).withContext('Prev button').not.toBeNull();
    expect(prev!.disabled).toBeFalse();
    prev!.click();
    const req3 = expectResultRequest();
    const off = params(req3).get('offset');
    expect(off === null || off === '0').withContext('offset back to 0, got ' + off).toBeTrue();
    req3.flush(page(0, 200));
    fixture.detectChanges();
    expect(button(/^prev(ious)?$/i)!.disabled).withContext('Prev disabled at offset 0').toBeTrue();
  });

  it('Next is disabled when truncated is false', () => {
    init(scratchJob);
    openResult();
    button(/^next$/i)!.click();
    const req2 = expectResultRequest();
    req2.flush(page(200, 100));   // last page: 200 + 100 == 300 → truncated false
    fixture.detectChanges();
    expect(button(/^next$/i)!.disabled).toBeTrue();
  });

  it('the footer shows returnedCount of rowCount and resultExpiresAt', () => {
    init(scratchJob);
    openResult();
    const text = (el.textContent || '').replace(/\s+/g, ' ');
    expect(text).toMatch(/showing 200 of 300/i);
    expect(text).toMatch(/expires at .*2026-09-19T10:00:05Z/i);
  });

  it('a 410 shows the expired message', () => {
    init(scratchJob);
    const view = button(/view\s*result/i);
    expect(view).not.toBeNull();
    view!.click();
    const req = expectResultRequest();
    req.flush({ error: 'the result expired' }, { status: 410, statusText: 'Gone' });
    fixture.detectChanges();
    const text = (el.textContent || '').replace(/\s+/g, ' ');
    expect(text).toContain('This result has expired — run the pipeline again.');
    expect(el.querySelector('table.results-table')).withContext('panel body replaced by the message').toBeNull();
    // The event stream is still there — polling was not broken by the error.
    expect(el.querySelectorAll('.table-container > table:not(.results-table) tbody tr').length).toBe(2);
  });

  it('a 404 says only Live Read pipelines return rows', () => {
    init(scratchJob);
    button(/view\s*result/i)!.click();
    const req = expectResultRequest();
    req.flush({ error: 'only scratch pipelines have a result' }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();
    const text = (el.textContent || '').replace(/\s+/g, ' ');
    expect(text).toContain('Only Live Read pipelines return rows.');
  });
});
