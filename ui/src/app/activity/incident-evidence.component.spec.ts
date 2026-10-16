/**
 * Evidence card for recovery-agent incidents and their approvals
 * (plans/stories/self-healing-2-evidence-ui.md). The card receives
 * `IncidentEvidence` exactly as serialised by story 1's
 * `IncidentEvidence.toJson` (datrisserver/.../incident/IncidentEvidence.scala):
 * record deltas are percentages, null rates and DQ pass rates are fractions,
 * `agentsThatQueriedLast7d` is omitted (or null) when reads are not audited.
 *
 * DOM contracts pinned here:
 *   - nothing at all is rendered when `evidence` is undefined;
 *   - text (whitespace-collapsed) contains "feeds 2 pipelines", the columns
 *     summary "+1 −1" (U+2212 minus), "30,112 → 29,870 (−0.8 %)" and
 *     "DQ 100 % → 100 %";
 *   - `.ie-null-movers tbody tr`  one row per null-rate mover (top 5);
 *   - `svg.ie-spark`              one per numeric column, each with two
 *                                 `<polyline>`s (baseline vs candidate/real);
 *   - `.ie-check`                 one per `checks[]` entry, carrying
 *                                 `.ie-check-pass` (tick) or `.ie-check-fail`
 *                                 (cross);
 *   - `.ie-verdict`               chip carrying `verdict-<verdict>` for
 *                                 pending | pass | needs-human | fail;
 *   - no `topK` value ever appears in the DOM (text or attributes).
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { IncidentEvidenceComponent } from './incident-evidence/incident-evidence.component';

const SECRET_A = 'ZZ-TOPK-VALUE-ALPHA-7731';
const SECRET_B = 'ZZ-TOPK-VALUE-BRAVO-9904';

function q(p1: number, p25: number, p50: number, p75: number, p99: number) {
  return { p1, p25, p50, p75, p99 };
}

function profile(over: any = {}): any {
  return {
    runId: 'run-1', source: 'landed-sample', recordCount: 30112,
    columns: [
      { name: 'region', inferredType: 'string', nullRate: 0.0, distinct: 2,
        topK: [{ value: SECRET_A, count: 20000 }, { value: SECRET_B, count: 10112 }] },
      { name: 'amount', inferredType: 'number', nullRate: 0.01, distinct: 900, quantiles: q(1, 10, 20, 40, 95) },
      { name: 'qty', inferredType: 'number', nullRate: 0.0, distinct: 50, quantiles: q(1, 2, 3, 5, 9) },
      { name: 'legacy_code', inferredType: 'string', nullRate: 0.02, distinct: 10 },
      { name: 'note', inferredType: 'string', nullRate: 0.3, distinct: 400 },
      { name: 'ship_date', inferredType: 'string', nullRate: 0.05, distinct: 300 },
      { name: 'city', inferredType: 'string', nullRate: 0.1, distinct: 120 }
    ],
    dqFailures: 0, dqPassRate: 1.0, durationMs: 1200, tapVersion: 3, sampledRows: 1000,
    computedAt: '2026-10-15T10:00:00Z',
    ...over
  };
}

function evidence(over: any = {}): any {
  const real = profile({
    runId: 'run-2', recordCount: 29870, tapVersion: 4,
    columns: [
      { name: 'region', inferredType: 'string', nullRate: 0.0, distinct: 2 },
      { name: 'amount', inferredType: 'number', nullRate: 0.04, distinct: 880, quantiles: q(1, 11, 22, 41, 97) },
      { name: 'qty', inferredType: 'number', nullRate: 0.0, distinct: 52, quantiles: q(1, 2, 4, 6, 10) },
      { name: 'note', inferredType: 'string', nullRate: 0.12, distinct: 380 },
      { name: 'ship_date', inferredType: 'string', nullRate: 0.08, distinct: 310 },
      { name: 'city', inferredType: 'string', nullRate: 0.09, distinct: 118 },
      { name: 'channel', inferredType: 'string', nullRate: 0.0, distinct: 3 }
    ]
  });
  return {
    baseline: profile(),
    real,
    diff: {
      columnsAdded: ['channel'],
      columnsRemoved: ['legacy_code'],
      typeChanges: [],
      recordDeltaPct: -0.8037,
      recordDeltaVsMeanOfLastN: 1.2,
      lastN: 5,
      nullRateDeltas: [
        { column: 'note', delta: -0.18 },
        { column: 'amount', delta: 0.03 },
        { column: 'ship_date', delta: 0.03 },
        { column: 'city', delta: -0.01 },
        { column: 'region', delta: 0.0 }
      ],
      quantileShift: [{ column: 'amount', shift: 0.0667 }, { column: 'qty', shift: 0.3333 }],
      dqPassRateDelta: 0.0
    },
    blastRadius: {
      downstreamPipelines: ['orders_daily', 'orders_rollup'],
      downstreamDatasets: [{ kind: 'postgres', coords: 'public.orders' }],
      ucTables: ['main.sales.orders'],
      consumersFromContracts: [],
      lastDownstreamRunAt: '2026-10-15T09:00:00Z',
      agentsThatQueriedLast7d: ['analyst-key', 'reporting-bot', 'ops-assistant']
    },
    thresholds: { maxRecordDeltaPct: 25, allowColumnRemoval: false, allowTypeChange: false, maxNullRateIncrease: 0.1 },
    checks: [
      { name: 'maxRecordDeltaPct', passed: true, observed: '-0.80', limit: '25' },
      { name: 'allowColumnRemoval', passed: false, observed: '1', limit: '0' },
      { name: 'allowTypeChange', passed: true, observed: '0', limit: '0' },
      { name: 'maxNullRateIncrease', passed: true, observed: '0.03', limit: '0.10' },
      { name: 'minDqPassRate', passed: true, observed: '1.000', limit: '0.990' }
    ],
    verdict: 'needs-human',
    computedAt: '2026-10-15T10:05:00Z',
    ...over
  };
}

describe('IncidentEvidenceComponent', () => {
  let fixture: ComponentFixture<IncidentEvidenceComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [IncidentEvidenceComponent]
    }).compileComponents();
    fixture = TestBed.createComponent(IncidentEvidenceComponent);
  });

  function render(ev: any, resourceType: 'tap' | 'pipeline' = 'tap'): HTMLElement {
    fixture.componentRef.setInput('evidence', ev);
    fixture.componentRef.setInput('resourceType', resourceType);
    fixture.componentRef.setInput('resource', 'orders_tap');
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function text(el: HTMLElement): string {
    return (el.textContent || '').replace(/\s+/g, ' ').trim();
  }

  // Acceptance bullet 1
  it('renders nothing when evidence is undefined', () => {
    const el = render(undefined);
    expect(text(el)).toBe('');
    expect(el.querySelectorAll('*').length).toBe(0);
  });

  // Acceptance bullet 1
  it('shows blast radius, columns, rows, DQ, null movers, sparklines and checks from a fixture', () => {
    const el = render(evidence());
    const t = text(el);

    expect(t).toContain('feeds 2 pipelines');
    expect(t).toContain('+1 −1');
    expect(t).toContain('30,112 → 29,870 (−0.8 %)');
    expect(t).toContain('DQ 100 % → 100 %');

    expect(el.querySelectorAll('.ie-null-movers tbody tr').length).toBe(5);

    const sparks = el.querySelectorAll('svg.ie-spark');
    expect(sparks.length).toBe(2); // amount, qty — the numeric columns
    sparks.forEach(s => expect(s.querySelectorAll('polyline').length).toBe(2));

    const checks = Array.from(el.querySelectorAll('.ie-check'));
    expect(checks.length).toBe(5);
    expect(checks.filter(c => c.classList.contains('ie-check-pass')).length).toBe(4);
    const failing = checks.filter(c => c.classList.contains('ie-check-fail'));
    expect(failing.length).toBe(1);
    expect(checks.indexOf(failing[0])).toBe(1); // allowColumnRemoval
    expect(failing[0].classList.contains('ie-check-pass')).toBeFalse();
  });

  // Acceptance bullet 1
  for (const v of ['pending', 'pass', 'needs-human', 'fail']) {
    it(`gives the verdict chip the ${v} class`, () => {
      const el = render(evidence({ verdict: v }));
      const chip = el.querySelector('.ie-verdict');
      expect(chip).withContext('verdict chip').not.toBeNull();
      expect(chip!.classList.contains('verdict-' + v)).toBeTrue();
      for (const other of ['pending', 'pass', 'needs-human', 'fail'].filter(o => o !== v)) {
        expect(chip!.classList.contains('verdict-' + other)).toBeFalse();
      }
    });
  }

  // Acceptance bullet 2
  it('never renders a topK value anywhere in the DOM', () => {
    const el = render(evidence());
    expect(text(el)).toContain('feeds 2 pipelines'); // the card did render
    const html = el.innerHTML;
    expect(html).not.toContain(SECRET_A);
    expect(html).not.toContain(SECRET_B);
    expect(el.textContent || '').not.toContain(SECRET_A);
    expect(el.textContent || '').not.toContain(SECRET_B);
  });

  // Acceptance bullet 3
  it('renders the "not audited" phrase when agentsThatQueriedLast7d is null', () => {
    const ev = evidence();
    ev.blastRadius.agentsThatQueriedLast7d = null;
    const t = text(render(ev));
    expect(t).toContain('not audited');
    expect(t).not.toContain('no agent reads this week');
  });

  // Acceptance bullet 3 — the server omits the field rather than sending null
  it('renders the "not audited" phrase when agentsThatQueriedLast7d is absent', () => {
    const ev = evidence();
    delete ev.blastRadius.agentsThatQueriedLast7d;
    const t = text(render(ev));
    expect(t).toContain('not audited');
  });

  // Acceptance bullet 3
  it('renders "no agent reads this week" when agentsThatQueriedLast7d is empty', () => {
    const ev = evidence();
    ev.blastRadius.agentsThatQueriedLast7d = [];
    const t = text(render(ev));
    expect(t).toContain('no agent reads this week');
    expect(t).not.toContain('not audited');
  });
});
