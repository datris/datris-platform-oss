/**
 * Story: Pipeline delete: an explicit deleteData=false keeps the destination
 * data (plans/stories/pipeline-delete-keep-data.md), review finding 1.
 *
 * The MCP tab playground's delete_pipeline mirrors the MCP server:
 * keep_data -> deleteData=false; keep_config -> deleteData=true&deleteConfig=false;
 * both -> an error and no request; neither -> only the pipeline.
 * Flags typed into the playground arrive as text, so 'true' counts as set.
 */
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { McpService } from './mcp.service';

describe('McpService — delete_pipeline playground flags', () => {
  let service: McpService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(McpService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  for (const v of [true, 'true', 'TRUE']) {
    it(`keep_data=${JSON.stringify(v)} sends deleteData=false and no deleteConfig`, () => {
      service.executeTool('delete_pipeline', { pipeline: 'orders v2', keep_data: v }).subscribe();
      const req = http.expectOne('/api/v1/pipeline?pipeline=orders%20v2&deleteData=false');
      expect(req.request.method).toBe('DELETE');
      expect(req.request.url).not.toContain('deleteConfig');
      req.flush({ warnings: [], kept: ['postgres table datris.public.orders'] });
    });

    it(`keep_config=${JSON.stringify(v)} sends the reset pair`, () => {
      service.executeTool('delete_pipeline', { pipeline: 'orders', keep_config: v }).subscribe();
      const req = http.expectOne('/api/v1/pipeline?pipeline=orders&deleteData=true&deleteConfig=false');
      expect(req.request.method).toBe('DELETE');
      req.flush({ warnings: [] });
    });
  }

  it('keep_data with keep_config is an error and nothing is called', () => {
    let err: any;
    service.executeTool('delete_pipeline', { pipeline: 'orders', keep_data: 'true', keep_config: true })
      .subscribe({ error: e => (err = e) });
    http.expectNone(() => true);
    expect(err?.error?.error).toContain('keep_data');
    expect(err?.error?.error).toContain('keep_config');
  });

  it('no flags, false or blank flags send only the pipeline', () => {
    for (const p of [{}, { keep_data: false, keep_config: 'false' }, { keep_data: '', keep_config: '' }]) {
      service.executeTool('delete_pipeline', { pipeline: 'orders', ...p }).subscribe();
      const req = http.expectOne('/api/v1/pipeline?pipeline=orders');
      expect(req.request.method).toBe('DELETE');
      req.flush({ warnings: [] });
    }
  });
});
