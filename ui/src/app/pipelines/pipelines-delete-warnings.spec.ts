/**
 * Story: Pipeline delete: a table, collection or prefix that could not be
 * removed is reported, and no cleanup can hang the request
 * (plans/stories/pipeline-delete-reports-failed-cleanup.md), Part B,
 * `pipelines.component.spec.ts` bullet (a new file next to it, so the
 * existing spec is untouched).
 *
 * The component is live: `/pipelines` redirects to `/catalog`, but the
 * catalog cards embed `<app-pipelines [embedCatalog]>`. Pins:
 * `confirmDelete(event, true)` (delete) and `confirmDelete(event, false)`
 * (reset) render the returned warnings inline in the component, in the
 * embedded and the full table, and never `alert()` them. The reset body is
 * given as the text `deletePipelineData` receives today; a service that
 * parses it first gives the component an object, which works the same.
 */
import { ComponentFixture, TestBed, fakeAsync, tick, flush, discardPeriodicTasks } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { PipelinesComponent } from './pipelines.component';
import { PipelineService } from '../pipeline.service';
import { PipelineStatusService } from '../pipeline-status.service';
import { TapService } from '../tap.service';
import { AuthService } from '../auth.service';

describe('PipelinesComponent — delete and reset warnings', () => {
  let fixture: ComponentFixture<PipelinesComponent>;
  let component: PipelinesComponent;
  let el: HTMLElement;
  let pipeSvc: any;

  const deleteWarning = 'Could not remove mongodb collection analytics.orders: the store could not be reached. ' +
    'Its pipeline configuration was deleted; remove it in the store.';
  const resetWarning = 'Could not remove postgres table datris.public.p_one: the store did not answer in time. ' +
    'Run the reset again once the store is available.';

  beforeEach(async () => {
    pipeSvc = {
      getPipelines: () => of([{ name: 'p_one', catalog: 'grp' }, { name: 'p_two', catalog: 'grp' }]),
      deletePipeline: jasmine.createSpy('deletePipeline').and.returnValue(of({ warnings: [deleteWarning] })),
      deletePipelineData: jasmine.createSpy('deletePipelineData').and.returnValue(of(JSON.stringify({ warnings: [resetWarning] }))),
      deleteCatalog: jasmine.createSpy('deleteCatalog').and.returnValue(of({ detached: [], failed: [] }))
    };
    await TestBed.configureTestingModule({
      declarations: [PipelinesComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideRouter([]),
        { provide: PipelineService, useValue: pipeSvc },
        { provide: PipelineStatusService, useValue: {} },
        { provide: TapService, useValue: { getTaps: () => of([]), deleteTap: () => of({}) } },
        { provide: AuthService, useValue: { canWrite: () => true } }
      ]
    }).compileComponents();
  });

  function create(embedded: boolean): void {
    fixture = TestBed.createComponent(PipelinesComponent);
    component = fixture.componentInstance;
    if (embedded) component.embedCatalog = 'grp';
    el = fixture.nativeElement as HTMLElement;
  }

  function fa(body: () => void) {
    return fakeAsync(() => { body(); fixture.destroy(); discardPeriodicTasks(); flush(); });
  }

  function settle(): void { fixture.detectChanges(); tick(); fixture.detectChanges(); }

  function run(embedded: boolean, deleteConfig: boolean, expected: string): void {
    create(embedded);
    settle();
    (component as any).deleteTarget = 'p_one';
    component.confirmDelete(new Event('click'), deleteConfig);
    settle();
    const called = deleteConfig ? pipeSvc.deletePipeline : pipeSvc.deletePipelineData;
    expect(called).toHaveBeenCalledWith('p_one');
    expect(el.textContent || '').withContext((embedded ? 'embedded' : 'full') + ' table shows the warning').toContain(expected);
  }

  it('delete shows returned warnings and never calls alert for them', fa(() => {
    const alert = spyOn(window, 'alert');
    run(true, true, deleteWarning);
    fixture.destroy();
    run(false, true, deleteWarning);
    expect(alert).not.toHaveBeenCalled();
  }));

  it('reset shows returned warnings and never calls alert for them', fa(() => {
    const alert = spyOn(window, 'alert');
    run(true, false, resetWarning);
    fixture.destroy();
    run(false, false, resetWarning);
    expect(alert).not.toHaveBeenCalled();
  }));
});
