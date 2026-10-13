/**
 * Story: Catalog backup and restore: CLI commands, Catalog-tab controls, docs
 * (plans/stories/catalog-backup-restore-ui-cli.md), Acceptance bullet 5:
 * "the restored card appears with its taps marked disabled".
 *
 * The embedded taps table (inside a Catalog-tab card) shows a `.disabled-badge`
 * ("Disabled") on every tap row whose `enabled` is false, and none on rows
 * that are enabled or carry no `enabled` field.
 */
import { ComponentFixture, TestBed, fakeAsync, tick, flush, discardPeriodicTasks } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { TapsComponent } from './taps.component';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { AuthService } from '../auth.service';
import { CodeRepoService } from '../configuration/code-repo/code-repo.service';

describe('TapsComponent — disabled badge in the embedded table', () => {
  let fixture: ComponentFixture<TapsComponent>;
  let el: HTMLElement;

  beforeEach(async () => {
    const tapSvc = {
      getTaps: () => of([
        { name: '__catalog__grp', catalog: 'grp', enabled: false },
        { name: 'tap_off', catalog: 'grp', enabled: false },
        { name: 'tap_on', catalog: 'grp', enabled: true },
        { name: 'tap_unset', catalog: 'grp' }
      ]),
      getPipelines: () => of([])
    };
    await TestBed.configureTestingModule({
      declarations: [TapsComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideRouter([]),
        { provide: TapService, useValue: tapSvc },
        { provide: PipelineService, useValue: { getPipelines: () => of([]) } },
        { provide: AuthService, useValue: { canWrite: () => false } },
        { provide: CodeRepoService, useValue: { get: () => of({ enabled: false }) } }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(TapsComponent);
    fixture.componentInstance.embedCatalog = 'grp';
    el = fixture.nativeElement as HTMLElement;
  });

  function row(name: string): HTMLElement {
    const r = (Array.from(el.querySelectorAll('tr')) as HTMLElement[])
      .find(x => (x.querySelector('.embed-name-link')?.textContent || '').trim() === name);
    expect(r).withContext('row for ' + name).toBeDefined();
    return r!;
  }

  it('marks only taps with enabled === false as Disabled', fakeAsync(() => {
    fixture.detectChanges();
    tick();
    fixture.detectChanges();
    const off = row('tap_off').querySelector('.disabled-badge');
    expect(off).withContext('badge on a disabled tap').not.toBeNull();
    expect((off?.textContent || '').trim()).toBe('Disabled');
    expect(row('tap_on').querySelector('.disabled-badge')).withContext('no badge on an enabled tap').toBeNull();
    expect(row('tap_unset').querySelector('.disabled-badge')).withContext('no badge without an enabled field').toBeNull();
    expect(el.querySelectorAll('.disabled-badge').length).withContext('placeholder is not listed').toBe(1);
    fixture.destroy();
    discardPeriodicTasks();
    flush();
  }));
});
