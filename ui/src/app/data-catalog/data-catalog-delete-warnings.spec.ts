/**
 * Story: Pipeline delete: a table, collection or prefix that could not be
 * removed is reported, and no cleanup can hang the request
 * (plans/stories/pipeline-delete-reports-failed-cleanup.md), Part B,
 * `data-catalog.component.spec.ts` bullet (a new file next to it, so the
 * existing spec is untouched).
 *
 * Pins: `deletePipelineItem(name)` feeds the body's `warnings` into the
 * existing warnings banner (`showOpWarnings`, `.op-failures-banner.warn`)
 * with the label "Deleting pipeline '<name>'", one entry per warning whose
 * `message` is the warning text. Without warnings the banner stays empty.
 */
import { ComponentFixture, TestBed, fakeAsync, tick, flush, discardPeriodicTasks } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { of, Subject } from 'rxjs';

import { DataCatalogComponent } from './data-catalog.component';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { AuthService } from '../auth.service';
import { CatalogAssistantStateService } from '../catalog-chat/catalog-assistant-state.service';

describe('DataCatalogComponent — single pipeline delete warnings', () => {
  let fixture: ComponentFixture<DataCatalogComponent>;
  let component: any;
  let el: HTMLElement;
  let pipeSvc: any;
  let deleteBody: any;

  const warning = 'Could not remove postgres table datris.public.p_a: the store could not be reached. ' +
    'Its pipeline configuration was deleted; remove it in the store.';

  beforeEach(async () => {
    sessionStorage.removeItem('catalog.expanded');
    deleteBody = { warnings: [] };
    pipeSvc = {
      getPipelines: jasmine.createSpy('getPipelines').and.callFake(() => of([{ name: 'p_a', catalog: 'e2e_a' }])),
      deletePipeline: jasmine.createSpy('deletePipeline').and.callFake(() => of(deleteBody)),
      createPipeline: jasmine.createSpy('createPipeline').and.returnValue(of('ok')),
      renameCatalog: jasmine.createSpy('renameCatalog').and.returnValue(of({ renamed: [], failed: [], affectedKeys: [] })),
      deleteCatalog: jasmine.createSpy('deleteCatalog').and.returnValue(of({ detached: [], failed: [] }))
    };
    await TestBed.configureTestingModule({
      declarations: [DataCatalogComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideRouter([]),
        {
          provide: TapService, useValue: {
            getTaps: () => of([{ name: '__catalog__e2e_a', catalog: 'e2e_a', description: 'Catalog placeholder' }]),
            deleteTap: () => of({}),
            createOrUpdateTap: () => of({})
          }
        },
        { provide: PipelineService, useValue: pipeSvc },
        { provide: AuthService, useValue: { canWrite: () => true } },
        { provide: CatalogAssistantStateService, useValue: { changed$: new Subject<void>(), seedDraft: () => {} } }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(DataCatalogComponent);
    component = fixture.componentInstance as any;
    el = fixture.nativeElement as HTMLElement;
  });

  function fa(body: () => void) {
    return fakeAsync(() => { body(); fixture.destroy(); discardPeriodicTasks(); flush(); });
  }

  function settle(): void { fixture.detectChanges(); tick(); fixture.detectChanges(); }

  it('a single pipeline delete with warnings fills the warnings banner', fa(() => {
    spyOn(window, 'alert');
    settle();
    deleteBody = { warnings: [warning] };
    component.deletePipelineItem('p_a');
    settle();
    expect(pipeSvc.deletePipeline).toHaveBeenCalledWith('p_a');
    expect(component.opWarnings.length).toBe(1);
    expect(component.opWarnings[0].message).toBe(warning);
    expect(component.opWarningsLabel).toBe("Deleting pipeline 'p_a'");
    const banner = el.querySelector('.op-failures-banner.warn') as HTMLElement;
    expect(banner).withContext('the warnings banner').not.toBeNull();
    expect(banner.textContent || '').toContain(warning);
    expect(window.alert).not.toHaveBeenCalled();
  }));

  it('without warnings the banner stays empty', fa(() => {
    settle();
    deleteBody = { warnings: [] };
    component.deletePipelineItem('p_a');
    settle();
    expect(pipeSvc.deletePipeline).toHaveBeenCalledWith('p_a');
    expect(component.opWarnings.length).toBe(0);
    expect(component.opWarningsLabel).toBe('');
    expect(el.querySelector('.op-failures-banner.warn')).toBeNull();
  }));
});
