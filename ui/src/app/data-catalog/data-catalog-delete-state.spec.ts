/**
 * Story: Pipeline delete: a table, collection or prefix that could not be
 * removed is reported (plans/stories/pipeline-delete-reports-failed-cleanup.md),
 * Part B: a delete from the pipeline page lands on the catalog with its
 * warnings in the navigation state; the catalog shows them in the warnings
 * banner once.
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

describe('DataCatalogComponent — delete warnings from the pipeline page', () => {
  let fixture: ComponentFixture<DataCatalogComponent>;
  let component: any;

  const warning = 'Could not remove postgres table datris.public.p_a: the store could not be reached. ' +
    'Its pipeline configuration was deleted; remove it in the store.';

  beforeEach(async () => {
    sessionStorage.removeItem('catalog.expanded');
    await TestBed.configureTestingModule({
      declarations: [DataCatalogComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideRouter([]),
        { provide: TapService, useValue: { getTaps: () => of([]), deleteTap: () => of({}), createOrUpdateTap: () => of({}) } },
        { provide: PipelineService, useValue: { getPipelines: () => of([]) } },
        { provide: AuthService, useValue: { canWrite: () => true } },
        { provide: CatalogAssistantStateService, useValue: { changed$: new Subject<void>(), seedDraft: () => {} } }
      ]
    }).compileComponents();
  });

  afterEach(() => history.replaceState({}, ''));

  function fa(body: () => void) {
    return fakeAsync(() => { body(); fixture.destroy(); discardPeriodicTasks(); flush(); });
  }

  it('shows the warnings from the navigation state in the banner, once', fa(() => {
    history.replaceState({ deletedPipeline: 'p_a', deleteWarnings: [warning] }, '');
    fixture = TestBed.createComponent(DataCatalogComponent);
    component = fixture.componentInstance as any;
    fixture.detectChanges(); tick(); fixture.detectChanges();
    expect(component.opWarnings.map((w: any) => w.message)).toEqual([warning]);
    expect(component.opWarningsLabel).toBe("Deleting pipeline 'p_a'");
    expect(history.state && history.state.deleteWarnings).toBeUndefined();
  }));

  it('no state, no banner', fa(() => {
    fixture = TestBed.createComponent(DataCatalogComponent);
    component = fixture.componentInstance as any;
    fixture.detectChanges(); tick(); fixture.detectChanges();
    expect(component.opWarnings.length).toBe(0);
  }));
});
