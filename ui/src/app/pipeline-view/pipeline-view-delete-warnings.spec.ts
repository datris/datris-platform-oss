/**
 * Story: Pipeline delete: a table, collection or prefix that could not be
 * removed is reported, and no cleanup can hang the request
 * (plans/stories/pipeline-delete-reports-failed-cleanup.md), Part B, the
 * pipeline page: a delete with warnings navigates to /catalog with them in
 * the router state (the catalog banner shows them); a reset shows them in
 * place. Never an alert.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';

import { PipelineViewComponent } from './pipeline-view.component';
import { PipelineService } from '../pipeline.service';
import { LineageService } from '../lineage.service';
import { AuthService } from '../auth.service';

const NAME = 'p_one';

describe('PipelineViewComponent — delete and reset warnings', () => {
  let fixture: ComponentFixture<PipelineViewComponent>;
  let router: any;
  let pipeSvc: any;

  const warning = 'Could not remove postgres table datris.public.p_one: the store could not be reached. ' +
    'Its pipeline configuration was deleted; remove it in the store.';

  beforeEach(() => {
    router = jasmine.createSpyObj('Router', ['navigate']);
    pipeSvc = {
      getPipeline: () => of({ name: NAME, destination: {} }),
      getUnityCatalog: () => of(null),
      deletePipeline: jasmine.createSpy('deletePipeline').and.returnValue(of({ warnings: [] })),
      deletePipelineData: jasmine.createSpy('deletePipelineData').and.returnValue(of({ warnings: [] }))
    };
    TestBed.configureTestingModule({
      declarations: [PipelineViewComponent],
      schemas: [NO_ERRORS_SCHEMA],
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ name: NAME }) } } },
        { provide: Router, useValue: router },
        { provide: PipelineService, useValue: pipeSvc },
        { provide: LineageService, useValue: { neighborhood: () => of(null) } },
        { provide: AuthService, useValue: { canWrite: () => true } }
      ]
    });
    fixture = TestBed.createComponent(PipelineViewComponent);
    fixture.detectChanges();
  });

  afterEach(() => fixture?.destroy());

  it('a delete with warnings navigates to the catalog with them in the router state', () => {
    spyOn(window, 'alert');
    pipeSvc.deletePipeline.and.returnValue(of({ warnings: [warning] }));
    fixture.componentInstance.deletePipeline(true);
    expect(router.navigate).toHaveBeenCalledWith(['/catalog'], { state: { deletedPipeline: NAME, deleteWarnings: [warning] } });
    expect(window.alert).not.toHaveBeenCalled();
  });

  it('a delete without warnings navigates to the catalog as before', () => {
    fixture.componentInstance.deletePipeline(true);
    expect(router.navigate).toHaveBeenCalledWith(['/catalog']);
  });

  it('a reset shows its warnings in place', () => {
    spyOn(window, 'alert');
    pipeSvc.deletePipelineData.and.returnValue(of({ warnings: [warning] }));
    fixture.componentInstance.deletePipeline(false);
    fixture.detectChanges();
    const box = (fixture.nativeElement as HTMLElement).querySelector('.delete-notices');
    expect(box).withContext('the reset warnings').not.toBeNull();
    expect(box?.textContent || '').toContain(warning);
    expect(router.navigate).not.toHaveBeenCalled();
    expect(window.alert).not.toHaveBeenCalled();
  });

  it('a reset without warnings shows nothing', () => {
    fixture.componentInstance.deletePipeline(false);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.delete-notices')).toBeNull();
  });
});
