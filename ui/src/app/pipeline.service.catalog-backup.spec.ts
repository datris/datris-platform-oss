/**
 * Story: Catalog backup and restore: CLI commands, Catalog-tab controls, docs
 * (plans/stories/catalog-backup-restore-ui-cli.md), Step 1; Acceptance bullet 3
 * (skip-state checkbox -> restoreState=false in the FormData).
 *
 * Pins the PipelineService methods the story prescribes:
 *   exportCatalog(name)                                   -> GET  /api/v1/catalog/{name}/export (blob)
 *   restoreCatalog(name, file, mode, confirm?, restoreState = true)
 *                                                         -> POST /api/v1/catalog/{name}/restore (FormData)
 * restoreCatalog uses observe:'response' so a 207 resolves through `next`;
 * a 409 surfaces through `error` with the server's JSON body (`clashes`).
 *
 * Methods are reached through `as any` so this spec compiles before the
 * implementation exists and fails at runtime ("is not a function").
 */
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { PipelineService } from './pipeline.service';

describe('PipelineService — catalog export/restore', () => {
  let service: any;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(PipelineService) as any;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function path(url: string): string {
    const i = url.indexOf('?');
    return i >= 0 ? url.substring(0, i) : url;
  }

  function zipFile(): File {
    return new File([new Uint8Array([0x50, 0x4b, 0x03, 0x04])], 'sales.zip', { type: 'application/zip' });
  }

  it('exportCatalog GETs /api/v1/catalog/{name}/export as a blob', () => {
    expect(typeof service.exportCatalog).withContext('PipelineService.exportCatalog').toBe('function');
    service.exportCatalog('sales').subscribe();
    const req = http.expectOne(r => path(r.urlWithParams) === '/api/v1/catalog/sales/export');
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob([new Uint8Array([0x50, 0x4b])], { type: 'application/zip' }));
  });

  it('restoreCatalog POSTs FormData (file, mode) to /api/v1/catalog/{name}/restore', () => {
    expect(typeof service.restoreCatalog).withContext('PipelineService.restoreCatalog').toBe('function');
    service.restoreCatalog('sales2', zipFile(), 'new').subscribe();
    const req = http.expectOne(r => path(r.urlWithParams) === '/api/v1/catalog/sales2/restore');
    expect(req.request.method).toBe('POST');
    const fd = req.request.body as FormData;
    expect(fd instanceof FormData).withContext('body is FormData').toBeTrue();
    expect(fd.get('file')).withContext('file part').not.toBeNull();
    const mode = fd.get('mode') ?? req.request.params.get('mode') ?? new URLSearchParams(req.request.urlWithParams.split('?')[1] || '').get('mode');
    expect(mode).toBe('new');
    req.flush({ restored: { taps: [], pipelines: [] }, failed: [] });
  });

  it('restoreCatalog with restoreState=false sends restoreState=false in the FormData', () => {
    expect(typeof service.restoreCatalog).withContext('PipelineService.restoreCatalog').toBe('function');
    service.restoreCatalog('sales2', zipFile(), 'new', undefined, false).subscribe();
    const req = http.expectOne(r => path(r.urlWithParams) === '/api/v1/catalog/sales2/restore');
    const fd = req.request.body as FormData;
    expect(fd instanceof FormData).withContext('body is FormData').toBeTrue();
    expect(fd.get('restoreState')).toBe('false');
    req.flush({ restored: { taps: [], pipelines: [] }, failed: [] });
  });

  it('restoreCatalog without restoreState sends nothing or true', () => {
    expect(typeof service.restoreCatalog).withContext('PipelineService.restoreCatalog').toBe('function');
    service.restoreCatalog('sales2', zipFile(), 'new').subscribe();
    const req = http.expectOne(r => path(r.urlWithParams) === '/api/v1/catalog/sales2/restore');
    const fd = req.request.body as FormData;
    const v = fd instanceof FormData ? fd.get('restoreState') : 'not-formdata';
    expect(v === null || v === 'true').withContext('restoreState was ' + v).toBeTrue();
    req.flush({ restored: { taps: [], pipelines: [] }, failed: [] });
  });

  it('restoreCatalog overwrite sends confirm', () => {
    expect(typeof service.restoreCatalog).withContext('PipelineService.restoreCatalog').toBe('function');
    service.restoreCatalog('sales2', zipFile(), 'overwrite', 'sales2').subscribe();
    const req = http.expectOne(r => path(r.urlWithParams) === '/api/v1/catalog/sales2/restore');
    const fd = req.request.body as FormData;
    const q = new URLSearchParams(req.request.urlWithParams.split('?')[1] || '');
    expect(fd.get('mode') ?? q.get('mode')).toBe('overwrite');
    expect(fd.get('confirm') ?? q.get('confirm')).toBe('sales2');
    req.flush({ restored: { taps: [], pipelines: [] }, failed: [] });
  });

  it('restoreCatalog resolves a 207 through next with failed[]', () => {
    expect(typeof service.restoreCatalog).withContext('PipelineService.restoreCatalog').toBe('function');
    let res: any;
    let errored = false;
    service.restoreCatalog('sales2', zipFile(), 'new').subscribe({ next: (r: any) => res = r, error: () => errored = true });
    const req = http.expectOne(r => path(r.urlWithParams) === '/api/v1/catalog/sales2/restore');
    req.flush({ restored: { taps: [], pipelines: ['p'] }, failed: [{ name: 't', error: 'boom' }] },
      { status: 207, statusText: 'Multi-Status' });
    expect(errored).withContext('207 must not error').toBeFalse();
    const body = res && res.body !== undefined ? res.body : res;
    expect(body.failed).toEqual([{ name: 't', error: 'boom' }]);
  });

  it('restoreCatalog surfaces a 409 through error with clashes', () => {
    expect(typeof service.restoreCatalog).withContext('PipelineService.restoreCatalog').toBe('function');
    let err: any;
    service.restoreCatalog('sales2', zipFile(), 'new').subscribe({ error: (e: any) => err = e });
    const req = http.expectOne(r => path(r.urlWithParams) === '/api/v1/catalog/sales2/restore');
    req.flush({ error: 'exists', clashes: ["tap 't' already exists"] }, { status: 409, statusText: 'Conflict' });
    expect(err?.status).toBe(409);
    expect(err?.error?.clashes).toEqual(["tap 't' already exists"]);
  });
});
