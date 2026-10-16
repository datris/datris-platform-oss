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

/**
 * Evidence card wiring (plans/stories/self-healing-2-evidence-ui.md).
 *
 * DOM contracts pinned here:
 *   - `li.incident-card app-incident-evidence`  only on incidents carrying
 *                                               `evidence`, bound to it;
 *   - `li.approval-card app-incident-evidence`  only on approvals carrying
 *                                               `incidentEvidence`, with
 *                                               `compact` true, placed before
 *                                               the Approve button;
 *   - an approval without `incidentEvidence` keeps the existing card DOM
 *     (structure snapshot taken before this story).
 */
describe('ActivityComponent: incident evidence card', () => {
  let fixture: ComponentFixture<ActivityComponent>;
  let component: ActivityComponent;

  const EVIDENCE: any = {
    blastRadius: { downstreamPipelines: ['orders_daily'], downstreamDatasets: [], ucTables: [], consumersFromContracts: [] },
    thresholds: { maxRecordDeltaPct: 25, allowColumnRemoval: false, allowTypeChange: false, maxNullRateIncrease: 0.1 },
    checks: [{ name: 'maxRecordDeltaPct', passed: true, observed: '-0.80', limit: '25' }],
    verdict: 'pass',
    computedAt: '2026-10-15T10:05:00Z'
  };

  function incident(id: string, over: any = {}): any {
    return {
      id, kind: 'tap_failure', resourceType: 'tap', resource: 'orders_tap', openedAt: new Date(Date.now() - 600_000).toISOString(),
      state: 'awaiting_approval', trigger: {}, steps: [{ ts: new Date().toISOString(), phase: 'open', summary: 'Tap failed' }],
      aiCalls: 1, actionsTaken: 0, ...over
    };
  }

  function approval(id: string, over: any = {}): any {
    return {
      id, action: 'update_tap', resourceType: 'tap', resource: 'orders_tap', resourceVersion: 4,
      actor: { type: 'system', label: 'recovery-agent' }, reason: 'Fix the date parser',
      request: { method: 'PUT', path: '/api/v1/taps/orders_tap', body: { script: 'print(1)' } },
      createdAt: new Date(Date.now() - 300_000).toISOString(), expiresAt: new Date(Date.now() + 2 * 86_400_000 + 3_600_000).toISOString(),
      state: 'pending', ...over
    };
  }

  /** Tag + classes + own text (digits masked) for every element in the card. */
  function structure(root: Element): string[] {
    const out: string[] = [];
    const walk = (e: Element, depth: number) => {
      const cls = Array.from(e.classList).sort().join('.');
      const own = Array.from(e.childNodes).filter(n => n.nodeType === Node.TEXT_NODE)
        .map(n => n.textContent || '').join(' ').replace(/\s+/g, ' ').trim().replace(/\d+/g, '#');
      out.push('  '.repeat(depth) + e.tagName.toLowerCase() + (cls ? '.' + cls : '') + (own ? ' "' + own + '"' : ''));
      Array.from(e.children).forEach(c => walk(c, depth + 1));
    };
    walk(root, 0);
    return out;
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ActivityComponent],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        { provide: TapService, useValue: {} },
        { provide: PipelineService, useValue: {} },
        { provide: PipelineStatusService, useValue: { getPipelineStatusDetail: () => of([]) } },
        { provide: AuthService, useValue: { canWrite: () => true } },
        { provide: OpsChatContextService, useValue: {} },
        { provide: OpsActionBus, useValue: { events$: of() } },
        { provide: OpsAssistantStateService, useValue: {} },
        { provide: ApprovalsService, useValue: { policyEnabled: () => of(true) } },
        { provide: IncidentsService, useValue: { recoveryEnabled: () => of(true) } },
        { provide: Router, useValue: { navigate: () => Promise.resolve(true) } },
        { provide: ActivatedRoute, useValue: { snapshot: { fragment: null } } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(ActivityComponent);
    component = fixture.componentInstance;
    spyOn(component, 'ngOnInit');
    component.policyEnabled = true;
    component.approvalsLoaded = true;
    component.recoveryEnabled = true;
    component.incidentsLoaded = true;
  });

  // Acceptance bullet 4
  it('renders app-incident-evidence on an open incident with evidence and not on one without', () => {
    component.openIncidents = [incident('inc-with', { evidence: EVIDENCE, verdict: 'pass' }), incident('inc-without')];
    fixture.detectChanges();

    const cards = fixture.nativeElement.querySelectorAll('li.incident-card');
    expect(cards.length).toBe(2);
    const withCard = cards[0].querySelector('app-incident-evidence');
    expect(withCard).withContext('card on incident with evidence').not.toBeNull();
    expect((withCard as any).evidence).toBe(EVIDENCE);
    expect(cards[1].querySelector('app-incident-evidence')).withContext('no card without evidence').toBeNull();
  });

  // Acceptance bullet 4
  it('renders the compact card above the Approve button on a pending approval with incidentEvidence', () => {
    component.pendingApprovals = [approval('ap-with', { incidentId: 'inc-with', incidentEvidence: EVIDENCE })];
    fixture.detectChanges();

    const card: HTMLElement = fixture.nativeElement.querySelector('li.approval-card');
    const ev = card.querySelector('app-incident-evidence');
    expect(ev).withContext('compact evidence card').not.toBeNull();
    expect((ev as any).compact).toBeTrue();
    expect((ev as any).evidence).toBe(EVIDENCE);
    const approve = card.querySelector('.approval-approve')!;
    expect(approve).not.toBeNull();
    expect(ev!.compareDocumentPosition(approve) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(ev!.closest('.approval-actions')).toBeNull();
  });

  // Acceptance bullet 4
  it('renders a pending approval without incidentEvidence exactly as before', () => {
    component.pendingApprovals = [approval('ap-without')];
    fixture.detectChanges();

    const card: HTMLElement = fixture.nativeElement.querySelector('li.approval-card');
    expect(card.querySelector('app-incident-evidence')).toBeNull();
    expect(structure(card)).toEqual(APPROVAL_CARD_SNAPSHOT);
  });
});

// Structure of a pending approval card (no incidentEvidence) before
// self-healing-2-evidence-ui; digits are masked.
const APPROVAL_CARD_SNAPSHOT: string[] = [
  'li.approval-card',
  '  div.approval-head',
  '    span.approval-actor-type.type-system "system"',
  '    span.approval-actor "recovery-agent"',
  '    span.approval-arrow "wants to"',
  '    code.approval-action "update_tap"',
  '    span.approval-resource "on"',
  '      strong "tap orders_tap"',
  '  div.approval-reason "“Fix the date parser”"',
  '  div.approval-times "queued #m ago · expires in #d"',
  '  button.approval-req-toggle "▸ Request"',
  '  div.approval-actions',
  '    button.action-btn.approval-approve "Approve"',
  '    button.action-btn.action-btn-ghost "Reject"'
];
