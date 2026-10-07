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
  // Story: CodeGen scripts 2 (plans/stories/codegen-script-git-storage.md):
  // repository-backed scripts carry repoPath / commitSha / drift / headSha,
  // pull adopts the head version, and regenerate takes storage / overwrite.

  it('codegenScripts passes repository fields through', () => {
    let body: any;
    service.codegenScripts('orders').subscribe(r => (body = r));
    http.expectOne('/api/v1/pipelines/orders/codegen-scripts').flush({
      pipeline: 'orders',
      scripts: [
        {
          kind: 'transformation', instruction: 'x', script: 'print(1)', status: 'ready', origin: 'save', storage: 'github',
          repoPath: 'taps/pipelines/orders/transformation.py', commitSha: 'a'.repeat(40), drift: true, headSha: 'b'.repeat(40)
        }
      ]
    });
    const tx = body.scripts[0];
    expect(tx.storage).toBe('github');
    expect(tx.repoPath).toBe('taps/pipelines/orders/transformation.py');
    expect(tx.drift).toBeTrue();
    expect(tx.headSha).toBe('b'.repeat(40));
  });

  it('pullCodegenScript POSTs to /api/v1/pipelines/{name}/codegen-scripts/{kind}/pull', () => {
    let body: any;
    service.pullCodegenScript('orders v2', 'transformation').subscribe(r => (body = r));
    const req = http.expectOne('/api/v1/pipelines/orders%20v2/codegen-scripts/transformation/pull');
    expect(req.request.method).toBe('POST');
    req.flush({ kind: 'transformation', instruction: 'x', script: 'print(3)', status: 'ready', origin: 'repository', storage: 'github', commitSha: 'c'.repeat(40), drift: false });
    expect(body.origin).toBe('repository');
    expect(body.commitSha).toBe('c'.repeat(40));
  });

  it('a pull on a built-in script surfaces the 400 through error', () => {
    let err: any;
    service.pullCodegenScript('orders', 'dataQuality').subscribe({ next: () => fail('expected an error'), error: e => (err = e) });
    http.expectOne('/api/v1/pipelines/orders/codegen-scripts/dataQuality/pull')
      .flush({ error: 'The dataQuality script for pipeline orders is not stored in the code repository' }, { status: 400, statusText: 'Bad Request' });
    expect(err.status).toBe(400);
    expect(err.error.error).toContain('code repository');
  });

  it('regenerateCodegenScript sends storage and overwrite as query parameters', () => {
    service.regenerateCodegenScript('orders', 'transformation', { storage: 'github', overwrite: true }).subscribe();
    const req = http.expectOne(r => r.url === '/api/v1/pipelines/orders/codegen-scripts/transformation/regenerate');
    expect(req.request.method).toBe('POST');
    expect(req.request.params.get('storage')).toBe('github');
    expect(req.request.params.get('overwrite')).toBe('true');
    req.flush({ kind: 'transformation', instruction: 'x', status: 'ready', storage: 'github' });
  });

  it('regenerateCodegenScript without options sends no storage or overwrite', () => {
    service.regenerateCodegenScript('orders', 'transformation').subscribe();
    const req = http.expectOne('/api/v1/pipelines/orders/codegen-scripts/transformation/regenerate');
    expect(req.request.params.has('storage')).toBeFalse();
    expect(req.request.params.has('overwrite')).toBeFalse();
    req.flush({ kind: 'transformation', instruction: 'x', status: 'ready' });
  });
});
