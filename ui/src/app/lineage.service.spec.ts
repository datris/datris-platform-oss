/**
 * Story: CodeGen scripts 1 (plans/stories/codegen-script-pinning.md), Step 6.
 *
 * Pins the two LineageService methods the lineage column panel uses for the
 * stored CodeGen transformation script:
 *   codegenScripts(pipeline)                 -> GET  /api/v1/pipelines/{name}/codegen-scripts
 *   regenerateCodegenScript(pipeline, kind)  -> POST /api/v1/pipelines/{name}/codegen-scripts/{kind}/regenerate
 * The pipeline name and kind are URL-encoded path segments. A failed
 * regenerate (502, current script unchanged) surfaces through `error` with
 * the server's JSON body intact.
 */
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { LineageService } from './lineage.service';

describe('LineageService — CodeGen scripts', () => {
  let service: LineageService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(LineageService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('codegenScripts GETs /api/v1/pipelines/{name}/codegen-scripts', () => {
    let body: any;
    service.codegenScripts('orders v2').subscribe(r => (body = r));
    const req = http.expectOne('/api/v1/pipelines/orders%20v2/codegen-scripts');
    expect(req.request.method).toBe('GET');
    req.flush({
      pipeline: 'orders v2',
      scripts: [
        { kind: 'transformation', instruction: 'x', script: 'print(1)', generatedAt: '2026-10-06T00:00:00Z', model: 'm', modelIsCurrent: true, status: 'ready', origin: 'save', storage: 'minio' }
      ]
    });
    expect(body.scripts.length).toBe(1);
    expect(body.scripts[0].script).toBe('print(1)');
    expect(body.scripts[0].status).toBe('ready');
  });

  it('regenerateCodegenScript POSTs to /api/v1/pipelines/{name}/codegen-scripts/{kind}/regenerate', () => {
    let body: any;
    service.regenerateCodegenScript('orders', 'transformation').subscribe(r => (body = r));
    const req = http.expectOne('/api/v1/pipelines/orders/codegen-scripts/transformation/regenerate');
    expect(req.request.method).toBe('POST');
    req.flush({ kind: 'transformation', instruction: 'x', script: 'print(2)', generatedAt: '2026-10-07T00:00:00Z', model: 'm2', status: 'ready', origin: 'regenerate' });
    expect(body.generatedAt).toBe('2026-10-07T00:00:00Z');
    expect(body.origin).toBe('regenerate');
  });

  it('a failed regenerate surfaces the server error through error', () => {
    let err: any;
    service.regenerateCodegenScript('orders', 'dataQuality').subscribe({ next: () => fail('expected an error'), error: e => (err = e) });
    const req = http.expectOne('/api/v1/pipelines/orders/codegen-scripts/dataQuality/regenerate');
    req.flush({ error: 'CodeGen script was not regenerated (the current script is unchanged): model unreachable' }, { status: 502, statusText: 'Bad Gateway' });
    expect(err.status).toBe(502);
    expect(err.error.error).toContain('current script is unchanged');
  });
});
