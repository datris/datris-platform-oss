/**
 * Story: Scratch destination in the UI and the docs
 * (plans/stories/scratch-ui-docs.md) — pipelines.component.spec.ts bullet.
 *
 * Pins: `getDestinations()` returns "Scratch" for `{ destination: { scratch: {} } }`;
 * `isScratch(ds)` is true for that shape and false for a postgres pipeline;
 * both destination cells (the catalog-embedded table — the live one — and the
 * full table) render a `span.scratch-badge` reading "Scratch" on the scratch
 * row and none on the postgres row.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';

import { PipelinesComponent } from './pipelines.component';
import { PipelineService } from '../pipeline.service';
import { PipelineStatusService } from '../pipeline-status.service';
import { TapService } from '../tap.service';
import { AuthService } from '../auth.service';

const scratchPipeline = {
  name: 'answer-now',
  catalog: 'demo',
  source: { fileAttributes: { csvAttributes: { delimiter: ',' } } },
  destination: { schemaProperties: { fields: [{ name: 'id', type: 'string' }] }, scratch: {} }
};

const postgresPipeline = {
  name: 'orders',
  catalog: 'demo',
  source: { fileAttributes: { csvAttributes: { delimiter: ',' } } },
  destination: {
    schemaProperties: { fields: [{ name: 'id', type: 'string' }] },
    database: { dbName: 'datris', schema: 'public', table: 'orders', usePostgres: true }
  }
};

describe('PipelinesComponent — Live Read badge', () => {
  let fixture: ComponentFixture<PipelinesComponent>;
  let component: PipelinesComponent;
  let el: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [PipelinesComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: PipelineService, useValue: { getPipelines: () => of([scratchPipeline, postgresPipeline]) } },
        { provide: PipelineStatusService, useValue: {} },
        { provide: TapService, useValue: { getTaps: () => of([]) } },
        { provide: AuthService, useValue: { canWrite: () => false } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(PipelinesComponent);
    component = fixture.componentInstance;
    el = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => fixture.destroy());

  function rowFor(name: string): HTMLElement {
    const row = (Array.from(el.querySelectorAll('tbody tr')) as HTMLElement[])
      .find(tr => (tr.textContent || '').includes(name));
    expect(row).withContext('row for ' + name).toBeDefined();
    return row!;
  }

  it('getDestinations lists Live Read', () => {
    expect(component.getDestinations(scratchPipeline)).toBe('Live Read');
    expect(component.getDestinations(postgresPipeline)).toBe('PostgreSQL');
    const c = component as any;
    expect(c.isScratch(scratchPipeline)).toBeTrue();
    expect(c.isScratch(postgresPipeline)).toBeFalse();
  });

  it('a pipeline with destination.scratch shows the Live Read badge (embedded catalog table)', () => {
    component.embedCatalog = 'demo';
    fixture.detectChanges();
    const badge = rowFor('answer-now').querySelector('.scratch-badge');
    expect(badge).withContext('span.scratch-badge in the embedded destination cell').not.toBeNull();
    expect((badge!.textContent || '').trim()).toBe('Live Read');
  });

  it('a pipeline with destination.scratch shows the Live Read badge (full table)', () => {
    fixture.detectChanges();
    component.catalogGroups.forEach(g => g.expanded = true);
    fixture.detectChanges();
    const badge = rowFor('answer-now').querySelector('.scratch-badge');
    expect(badge).withContext('span.scratch-badge in the full-table destination cell').not.toBeNull();
    expect((badge!.textContent || '').trim()).toBe('Live Read');
  });

  it('a postgres pipeline shows no Live Read badge', () => {
    component.embedCatalog = 'demo';
    fixture.detectChanges();
    expect(rowFor('orders').querySelector('.scratch-badge')).toBeNull();
    expect((rowFor('orders').textContent || '')).toContain('PostgreSQL');

    // Full table too.
    fixture.destroy();
    fixture = TestBed.createComponent(PipelinesComponent);
    component = fixture.componentInstance;
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    component.catalogGroups.forEach(g => g.expanded = true);
    fixture.detectChanges();
    expect(rowFor('orders').querySelector('.scratch-badge')).toBeNull();
  });
});

/**
 * Story: Pipeline rename keeps its data
 * (plans/stories/pipeline-rename-keeps-data.md) — pipelines.component.spec.ts bullets.
 *
 * Pins: `saveEditName` makes one `PipelineService.renamePipeline(old, new)`
 * call (POST /api/v1/pipelines/{old}/rename, body {newName}) and none of the
 * old GET + createPipeline + deletePipeline chain; a refused rename alerts the
 * server's `error` text and reloads the list; a blank or unchanged name sends
 * nothing.
 */
describe('PipelinesComponent — server-side rename', () => {
  let component: PipelinesComponent;
  let svc: any;
  const row = { name: 'orders', catalog: 'demo' };

  beforeEach(async () => {
    svc = {
      getPipelines: jasmine.createSpy('getPipelines').and.returnValue(of([])),
      getPipeline: jasmine.createSpy('getPipeline').and.returnValue(of({ ...postgresPipeline })),
      createPipeline: jasmine.createSpy('createPipeline').and.returnValue(of({})),
      deletePipeline: jasmine.createSpy('deletePipeline').and.returnValue(of({ warnings: [] })),
      renamePipeline: jasmine.createSpy('renamePipeline').and.returnValue(
        of({ name: 'orders_v2', previousName: 'orders', version: 4, repointedTaps: [], failed: [], warnings: [] })
      )
    };
    await TestBed.configureTestingModule({
      declarations: [PipelinesComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: PipelineService, useValue: svc },
        { provide: PipelineStatusService, useValue: {} },
        { provide: TapService, useValue: { getTaps: () => of([]) } },
        { provide: AuthService, useValue: { canWrite: () => true } }
      ]
    }).compileComponents();
    // No detectChanges: ngOnInit (and its refresh interval) never runs, so
    // every getPipelines call below is a reload made by saveEditName.
    component = TestBed.createComponent(PipelinesComponent).componentInstance;
  });

  function rename(to: string): void {
    component.startEditName(new Event('click'), row);
    component.editingNameValue = to;
    component.saveEditName(row);
  }

  it('saveEditName calls renamePipeline(old, new) and never createPipeline or deletePipeline', () => {
    rename('  orders_v2  ');
    expect(svc.renamePipeline).toHaveBeenCalledOnceWith('orders', 'orders_v2');
    expect(svc.createPipeline).not.toHaveBeenCalled();
    expect(svc.deletePipeline).not.toHaveBeenCalled();
    expect(svc.getPipeline).not.toHaveBeenCalled();
    expect(svc.getPipelines).toHaveBeenCalled();
    expect(component.editingName).toBe('');
  });

  it('a rename error shows the server error text and reloads', () => {
    const alertSpy = spyOn(window, 'alert');
    svc.renamePipeline.and.returnValue(
      throwError(() => ({ status: 409, error: { error: "Pipeline 'orders_v2' already exists" } }))
    );
    rename('orders_v2');
    expect(svc.renamePipeline).toHaveBeenCalledOnceWith('orders', 'orders_v2');
    expect(alertSpy).toHaveBeenCalledTimes(1);
    expect(alertSpy.calls.mostRecent().args[0]).toContain("Pipeline 'orders_v2' already exists");
    expect(svc.getPipelines).toHaveBeenCalled();
    expect(svc.createPipeline).not.toHaveBeenCalled();
    expect(svc.deletePipeline).not.toHaveBeenCalled();
  });

  it('an unchanged or blank name sends nothing', () => {
    for (const value of ['orders', '  orders  ', '', '   ']) {
      rename(value);
    }
    expect(svc.renamePipeline).not.toHaveBeenCalled();
    expect(svc.getPipeline).not.toHaveBeenCalled();
    expect(svc.createPipeline).not.toHaveBeenCalled();
    expect(svc.deletePipeline).not.toHaveBeenCalled();
    expect(component.editingName).toBe('');
  });

  it('PipelineService.renamePipeline posts {newName} to /api/v1/pipelines/{name}/rename', () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const real: any = TestBed.inject(PipelineService);
    const http = TestBed.inject(HttpTestingController);
    let body: any;
    expect(typeof real.renamePipeline).toBe('function');
    real.renamePipeline('my orders', 'orders_v2').subscribe((b: any) => (body = b));
    const call = http.expectOne('/api/v1/pipelines/my%20orders/rename');
    expect(call.request.method).toBe('POST');
    expect(call.request.body).toEqual({ newName: 'orders_v2' });
    call.flush({ name: 'orders_v2', previousName: 'my orders' });
    expect(body.name).toBe('orders_v2');
    http.verify();
  });
});
