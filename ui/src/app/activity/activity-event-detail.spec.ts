/**
 * Ops activity view: a failed run's terminal event carries plain words in
 * `description` and the full stack trace in the optional `detail` field
 * (plans/stories/run-status-message-not-stacktrace.md, Resolved details (c)).
 *
 * DOM contracts pinned here:
 *   - `.exp-desc`             the description, shown as before;
 *   - `.exp-detail-toggle`    "Stack trace" button, only on events with `detail`;
 *   - `pre.exp-detail`        the trace, hidden until that event's toggle is clicked
 *                             (per-event open state).
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { of } from 'rxjs';

import { ActivityComponent } from './activity.component';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { PipelineStatusService, PipelineStatusDetail } from '../pipeline-status.service';
import { AuthService } from '../auth.service';
import { OpsChatContextService } from '../ops-chat/ops-chat-context.service';
import { OpsActionBus } from '../ops-chat/ops-action-bus.service';
import { OpsAssistantStateService } from '../ops-chat/ops-assistant-state.service';
import { ApprovalsService } from '../approvals.service';
import { IncidentsService } from '../incidents.service';

function ev(over: Partial<PipelineStatusDetail>): PipelineStatusDetail {
  return {
    dateTime: '2026-10-05 10:00:00', pipeline: 'orders', processName: 'JobRunner', publisherToken: '',
    pipelineToken: 'tok-1', filename: 'orders.csv', state: 'end', code: 'info', description: '',
    ...over
  };
}

describe('ActivityComponent: event detail (stack trace) toggle', () => {
  let fixture: ComponentFixture<ActivityComponent>;
  let component: ActivityComponent;

  const TRACE = 'java.lang.IllegalStateException: schema mismatch\n\tat ai.datris.util.DataUtil$.evolveSchema(DataUtil.scala:175)';
  const events = [
    ev({ dateTime: '2026-10-05 10:00:00', state: 'begin', description: 'Data received' }),
    ev({ dateTime: '2026-10-05 10:00:01', code: 'error', description: 'Process completed, error: schema mismatch', detail: TRACE }),
    ev({ dateTime: '2026-10-05 10:00:02', code: 'error', description: 'Process completed, error: second', detail: 'trace two' })
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ActivityComponent],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        { provide: TapService, useValue: {} },
        { provide: PipelineService, useValue: {} },
        { provide: PipelineStatusService, useValue: { getPipelineStatusDetail: () => of(events) } },
        { provide: AuthService, useValue: { canWrite: () => true } },
        { provide: OpsChatContextService, useValue: {} },
        { provide: OpsActionBus, useValue: { events$: of() } },
        { provide: OpsAssistantStateService, useValue: {} },
        { provide: ApprovalsService, useValue: { policyEnabled: () => of(false) } },
        { provide: IncidentsService, useValue: { recoveryEnabled: () => of(false) } },
        { provide: Router, useValue: { navigate: () => Promise.resolve(true) } },
        { provide: ActivatedRoute, useValue: { snapshot: { fragment: null } } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(ActivityComponent);
    component = fixture.componentInstance;
    // No data load or refresh timer; the test seeds one failing pipeline row.
    spyOn(component, 'ngOnInit');
    (component as any).failing = [{
      kind: 'pipeline', name: 'orders', catalog: null, reason: 'Process completed, error: schema mismatch',
      timeIso: null, recovered: false, failureCount: 1, pipelineToken: 'tok-1', logs: null, relatedTapName: null
    }];
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.failing-row') as HTMLElement).click();
    fixture.detectChanges();
  });

  it('shows the description and a collapsed "Stack trace" toggle only on events with detail', () => {
    const el: HTMLElement = fixture.nativeElement;
    const descs = Array.from(el.querySelectorAll('td.exp-desc')).map(d => d.textContent || '');
    expect(descs.length).toBe(3);
    expect(descs[1]).toContain('Process completed, error: schema mismatch');
    expect(descs[1]).not.toContain('\tat ai.datris');

    const toggles = el.querySelectorAll('.exp-detail-toggle');
    expect(toggles.length).toBe(2);
    expect((toggles[0].textContent || '').trim()).toBe('Stack trace');
    expect(el.querySelector('pre.exp-detail')).toBeNull();
  });

  it('opens one event\'s trace without opening the others, and closes it again', () => {
    const el: HTMLElement = fixture.nativeElement;
    (el.querySelectorAll('.exp-detail-toggle')[0] as HTMLElement).click();
    fixture.detectChanges();

    const pres = el.querySelectorAll('pre.exp-detail');
    expect(pres.length).toBe(1);
    expect(pres[0].textContent).toBe(TRACE);
    expect(component.isExpanded((component as any).failing[0])).toBeTrue();

    (el.querySelectorAll('.exp-detail-toggle')[0] as HTMLElement).click();
    fixture.detectChanges();
    expect(el.querySelector('pre.exp-detail')).toBeNull();
  });
});
