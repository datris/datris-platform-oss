/**
 * Story: Catalog rename and delete: UI, docs, changelog
 * (plans/stories/catalog-ops-ui-docs.md), Acceptance bullet 7 (Pipelines tab).
 *
 * Pipelines tab group header (non-embedded): the delete icon
 * (`.catalog-delete-btn`) shows on named catalog groups and not on
 * Uncataloged. Its confirm offers `input[type=radio][value=detach]`
 * (default) and `input[type=radio][value=cascade]`, the latter revealing an
 * `input[type=text]` that must equal the group name before the confirm
 * button (`.del-yes`) enables. Confirm calls
 * `PipelineService.deleteCatalog(name, mode, confirm?)` instead of the old
 * per-pipeline `deletePipeline` fan-out. If a shared confirm component is
 * extracted, add it to `declarations` below.
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

describe('PipelinesComponent — catalog group delete', () => {
  let fixture: ComponentFixture<PipelinesComponent>;
  let el: HTMLElement;
  let pipeSvc: any;

  beforeEach(async () => {
    pipeSvc = {
      getPipelines: () => of([
        { name: 'p_one', catalog: 'grp' },
        { name: 'p_two', catalog: 'grp' },
        { name: 'loose_p' }
      ]),
      deletePipeline: jasmine.createSpy('deletePipeline').and.returnValue(of({})),
      deleteCatalog: jasmine.createSpy('deleteCatalog').and.returnValue(of({ detached: ['p_one', 'p_two'], failed: [] }))
    };
    await TestBed.configureTestingModule({
      declarations: [PipelinesComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideRouter([]),
        { provide: PipelineService, useValue: pipeSvc },
        { provide: PipelineStatusService, useValue: {} },
        { provide: TapService, useValue: { getTaps: () => of([]), deleteTap: jasmine.createSpy('deleteTap').and.returnValue(of({})) } },
        { provide: AuthService, useValue: { canWrite: () => true } }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(PipelinesComponent);
    el = fixture.nativeElement as HTMLElement;
  });

  function fa(body: () => void) {
    return fakeAsync(() => { body(); fixture.destroy(); discardPeriodicTasks(); flush(); });
  }

  function settle(): void { fixture.detectChanges(); tick(); fixture.detectChanges(); }

  function header(name: string): HTMLElement {
    const h = (Array.from(el.querySelectorAll('.catalog-group-header')) as HTMLElement[])
      .find(x => (x.querySelector('.catalog-group-name')?.textContent || '').trim() === name);
    expect(h).withContext('group header ' + name).toBeDefined();
    return h!;
  }

  function openDelete(name: string): HTMLElement {
    (header(name).querySelector('.catalog-delete-btn') as HTMLButtonElement).click();
    settle();
    return header(name);
  }

  it('named group shows the delete icon; Uncataloged group does not', fa(() => {
    settle();
    expect(header('grp').querySelector('.catalog-delete-btn')).not.toBeNull();
    expect(header('Uncataloged').querySelector('.catalog-delete-btn'))
      .withContext('no group delete on Uncataloged').toBeNull();
  }));

  it('group delete defaults to "Keep items" and calls deleteCatalog(name, "detach")', fa(() => {
    settle();
    const h = openDelete('grp');
    const detach = h.querySelector('input[type=radio][value=detach]') as HTMLInputElement | null;
    expect(detach).withContext('Keep items radio').not.toBeNull();
    expect(h.querySelector('input[type=radio][value=cascade]')).withContext('Delete items radio').not.toBeNull();
    expect(detach?.checked).withContext('Keep items is the default').toBeTrue();
    (h.querySelector('.del-yes') as HTMLButtonElement).click();
    settle();
    expect(pipeSvc.deleteCatalog).toHaveBeenCalledTimes(1);
    const args = pipeSvc.deleteCatalog.calls.mostRecent().args;
    expect(args[0]).toBe('grp');
    expect(args[1]).toBe('detach');
    expect(args[2]).toBeFalsy();
    expect(pipeSvc.deletePipeline).withContext('no per-pipeline fan-out').not.toHaveBeenCalled();
  }));

  it('group "Delete items and their data" needs the typed name, then calls deleteCatalog(name, "cascade", name)', fa(() => {
    settle();
    const h = openDelete('grp');
    const cascade = h.querySelector('input[type=radio][value=cascade]') as HTMLInputElement | null;
    expect(cascade).withContext('cascade radio').not.toBeNull();
    if (!cascade) return;
    cascade.click();
    settle();
    const text = header('grp').querySelector('input[type=text]') as HTMLInputElement | null;
    expect(text).withContext('type-the-name input').not.toBeNull();
    if (!text) return;
    const yes = () => header('grp').querySelector('.del-yes') as HTMLButtonElement;
    expect(yes().disabled).toBeTrue();
    text.value = 'gr'; text.dispatchEvent(new Event('input')); settle();
    expect(yes().disabled).toBeTrue();
    text.value = 'grp'; text.dispatchEvent(new Event('input')); settle();
    expect(yes().disabled).toBeFalse();
    yes().click();
    settle();
    expect(pipeSvc.deleteCatalog.calls.mostRecent()?.args).toEqual(['grp', 'cascade', 'grp']);
    expect(pipeSvc.deletePipeline).withContext('no per-pipeline fan-out').not.toHaveBeenCalled();
  }));
});
