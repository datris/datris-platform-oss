/**
 * Story: Runner overload protection (plans/stories/runner-overload-protection.md),
 * Acceptance bullet 6, the UI half.
 *
 * In the Run History modal, a run that waited for a tap-runner slot shows a
 * `.log-queued` chip after the duration chip: "queued 12.3s" for
 * `queuedMs: 12300`. A run with `queuedMs: 0`, or a log stored before the
 * change (no `queuedMs`), shows no chip.
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

describe('TapsComponent — queued time in run history', () => {
  let fixture: ComponentFixture<TapsComponent>;
  let el: HTMLElement;

  const logs = [
    { tapName: 't1', runTime: '2026-10-14T10:00:00Z', status: 'success', recordCount: 5, durationMs: 15300, mode: 'run', queuedMs: 12300 },
    { tapName: 't1', runTime: '2026-10-14T09:00:00Z', status: 'success', recordCount: 4, durationMs: 900, mode: 'run', queuedMs: 0 },
    { tapName: 't1', runTime: '2026-10-14T08:00:00Z', status: 'success', recordCount: 3, durationMs: 800, mode: 'run' }
  ];

  beforeEach(async () => {
    const tapSvc = {
      getTaps: () => of([{ name: 't1' }]),
      getPipelines: () => of([]),
      getTapLogs: jasmine.createSpy('getTapLogs').and.returnValue(of(logs)),
      runTap: () => of({})
    };
    await TestBed.configureTestingModule({
      declarations: [TapsComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideRouter([]),
        { provide: TapService, useValue: tapSvc },
        { provide: PipelineService, useValue: { getPipelines: () => of([]) } },
        { provide: AuthService, useValue: { canWrite: () => true } },
        { provide: CodeRepoService, useValue: { get: () => of({ enabled: false }) } }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(TapsComponent);
    el = fixture.nativeElement as HTMLElement;
  });

  function entries(): HTMLElement[] {
    return Array.from(el.querySelectorAll('.log-entry')) as HTMLElement[];
  }

  it('shows "queued 12.3s" only on the run that waited, after the duration chip', fakeAsync(() => {
    fixture.detectChanges();
    tick();
    fixture.componentInstance.viewLogs(new Event('click'), 't1');
    tick();
    fixture.detectChanges();

    const rows = entries();
    expect(rows.length).withContext('three runs listed').toBe(3);

    const chip = rows[0].querySelector('.log-queued');
    expect(chip).withContext('chip on the run with queuedMs 12300').not.toBeNull();
    expect((chip?.textContent || '').trim()).toBe('queued 12.3s');

    const summary = rows[0].querySelector('.log-summary') as HTMLElement;
    const kids = Array.from(summary.children);
    const duration = summary.querySelector('.log-duration') as Element;
    expect(kids.indexOf(chip as Element)).withContext('after the duration chip').toBeGreaterThan(kids.indexOf(duration));

    expect(rows[1].querySelector('.log-queued')).withContext('queuedMs 0 shows nothing').toBeNull();
    expect(rows[2].querySelector('.log-queued')).withContext('absent queuedMs shows nothing').toBeNull();
    expect(el.textContent || '').not.toContain('queued 0.0s');

    fixture.destroy();
    discardPeriodicTasks();
    flush();
  }));
});
