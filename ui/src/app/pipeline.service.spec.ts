/**
 * Story: Catalog rename and delete: UI, docs, changelog
 * (plans/stories/catalog-ops-ui-docs.md), Steps 1 and 4; supports Acceptance
 * bullets 2-6.
 *
 * Pins the two PipelineService methods the story prescribes:
 *   renameCatalog(name, newName)          -> PUT    /api/v1/catalog/{name}  body {newName}
 *   deleteCatalog(name, mode, confirm?)   -> DELETE /api/v1/catalog/{name}?mode=<mode>[&confirm=<name>]
 * The catalog name is URL-encoded as a path segment. Both return the parsed
 * JSON body; a 207 (per-item failures) must resolve through `next`, not
 * `error`, so the component can render `failed[]`. Error statuses (404 for
 * the older-server fallback, 409 clash, 400 label rule) surface through
 * `error` with the server's JSON body intact.
 *
 * Methods are reached through `as any` so this spec compiles before the
 * implementation exists and fails at runtime ("is not a function").
 */
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { PipelineService } from './pipeline.service';

describe('PipelineService — catalog rename/delete', () => {
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

  function query(url: string): URLSearchParams {
    const i = url.indexOf('?');
    return new URLSearchParams(i >= 0 ? url.substring(i + 1) : '');
  }

  function path(url: string): string {
    const i = url.indexOf('?');
    return i >= 0 ? url.substring(0, i) : url;
  }

  it('renameCatalog PUTs {newName} to /api/v1/catalog/{name}', () => {
    expect(typeof service.renameCatalog).withContext('PipelineService.renameCatalog').toBe('function');
    let body: any;
    service.renameCatalog('e2e_a', 'e2e_c').subscribe((b: any) => body = b);
    const req = http.expectOne(r => path(r.urlWithParams) === '/api/v1/catalog/e2e_a');
    expect(req.request.method).toBe('PUT');
    const sent = typeof req.request.body === 'string' ? JSON.parse(req.request.body) : req.request.body;
    expect(sent).toEqual({ newName: 'e2e_c' });
    req.flush({ renamed: ['t1', 'p1'], failed: [], affectedKeys: [], placeholder: 'created' });
    expect(body.renamed).toEqual(['t1', 'p1']);
    expect(body.failed).toEqual([]);
  });

  it('renameCatalog URL-encodes the catalog name as a path segment', () => {
    expect(typeof service.renameCatalog).withContext('PipelineService.renameCatalog').toBe('function');
    service.renameCatalog('My Cat/1', 'my_cat').subscribe();
    const req = http.expectOne(r => path(r.urlWithParams) === '/api/v1/catalog/' + encodeURIComponent('My Cat/1'));
    expect(req.request.method).toBe('PUT');
    req.flush({ renamed: [], failed: [], affectedKeys: [] });
  });

  it('renameCatalog resolves a 207 through next with failed[] and affectedKeys', () => {
    expect(typeof service.renameCatalog).withContext('PipelineService.renameCatalog').toBe('function');
    let body: any;
    let errored = false;
    service.renameCatalog('e2e_e', 'e2e_f').subscribe({ next: (b: any) => body = b, error: () => errored = true });
    const req = http.expectOne(r => path(r.urlWithParams) === '/api/v1/catalog/e2e_e');
    req.flush(
      { renamed: ['a'], failed: [{ name: 'b', error: 'no longer in catalog' }], affectedKeys: ['ops-key'] },
      { status: 207, statusText: 'Multi-Status' }
    );
    expect(errored).withContext('207 must not error').toBeFalse();
    expect(body.failed).toEqual([{ name: 'b', error: 'no longer in catalog' }]);
    expect(body.affectedKeys).toEqual(['ops-key']);
  });

  it('renameCatalog surfaces a 409 "already exists" body through error', () => {
    expect(typeof service.renameCatalog).withContext('PipelineService.renameCatalog').toBe('function');
    let err: any;
    service.renameCatalog('e2e_c', 'e2e_b').subscribe({ next: () => fail('409 must not resolve'), error: (e: any) => err = e });
    http.expectOne(r => path(r.urlWithParams) === '/api/v1/catalog/e2e_c').flush(
      { error: "Catalog 'e2e_b' already exists. Use move to merge catalogs." },
      { status: 409, statusText: 'Conflict' }
    );
    expect(err.status).toBe(409);
    expect(err.error.error).toContain('already exists');
  });

  it('deleteCatalog(name, "detach") sends DELETE with mode=detach and no confirm', () => {
    expect(typeof service.deleteCatalog).withContext('PipelineService.deleteCatalog').toBe('function');
    let body: any;
    service.deleteCatalog('e2e_b', 'detach').subscribe((b: any) => body = b);
    const req = http.expectOne(r => path(r.urlWithParams) === '/api/v1/catalog/e2e_b');
    expect(req.request.method).toBe('DELETE');
    const q = query(req.request.urlWithParams);
    expect(q.get('mode')).toBe('detach');
    expect(q.has('confirm')).withContext('detach sends no confirm').toBeFalse();
    req.flush({ detached: ['t1', 'p1'], failed: [] });
    expect(body.detached).toEqual(['t1', 'p1']);
  });

  it('deleteCatalog(name, "cascade", confirm) sends mode=cascade and the encoded confirm', () => {
    expect(typeof service.deleteCatalog).withContext('PipelineService.deleteCatalog').toBe('function');
    service.deleteCatalog('a b&c', 'cascade', 'a b&c').subscribe();
    const req = http.expectOne(r => path(r.urlWithParams) === '/api/v1/catalog/' + encodeURIComponent('a b&c'));
    expect(req.request.method).toBe('DELETE');
    const q = query(req.request.urlWithParams);
    expect(q.get('mode')).toBe('cascade');
    expect(q.get('confirm')).withContext('confirm round-trips through URL encoding').toBe('a b&c');
    req.flush({ deleted: [], failed: [] });
  });

  it('deleteCatalog resolves a 207 through next with failed[]', () => {
    expect(typeof service.deleteCatalog).withContext('PipelineService.deleteCatalog').toBe('function');
    let body: any;
    let errored = false;
    service.deleteCatalog('e2e_d', 'cascade', 'e2e_d').subscribe({ next: (b: any) => body = b, error: () => errored = true });
    http.expectOne(r => path(r.urlWithParams) === '/api/v1/catalog/e2e_d').flush(
      { deleted: ['p1'], failed: [{ name: 't1', error: 'capability denied' }] },
      { status: 207, statusText: 'Multi-Status' }
    );
    expect(errored).withContext('207 must not error').toBeFalse();
    expect(body.deleted).toEqual(['p1']);
    expect(body.failed).toEqual([{ name: 't1', error: 'capability denied' }]);
  });

  it('deleteCatalog surfaces a 404 (older server) through error', () => {
    expect(typeof service.deleteCatalog).withContext('PipelineService.deleteCatalog').toBe('function');
    let err: any;
    service.deleteCatalog('old', 'detach').subscribe({ next: () => fail('404 must not resolve'), error: (e: any) => err = e });
    http.expectOne(r => path(r.urlWithParams) === '/api/v1/catalog/old').flush(
      { error: 'Not Found' }, { status: 404, statusText: 'Not Found' }
    );
    expect(err.status).toBe(404);
  });
});
