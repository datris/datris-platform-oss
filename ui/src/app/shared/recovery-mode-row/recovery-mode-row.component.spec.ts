/**
 * Story: Self-healing cost loop (plans/stories/self-healing-cost-loop.md) —
 * the per-resource "Self-healing" row on the pipeline page and the tap run
 * page.
 *
 * Contract pinned here:
 * - `RecoveryModeRowComponent`, selector `app-recovery-mode-row`,
 *   `standalone: false`, inputs `resourceType: 'pipeline' | 'tap'` and
 *   `resourceName: string`.
 * - On init it calls `AgentPolicyService.get()` (GET /api/v1/policy) and
 *   renders a `.lineage-row` labelled "Self-healing" with the effective mode
 *   (`policy.overrides['<type>:<name>'].recovery ?? policy.recovery.mode`)
 *   and its source: "override" or "instance default".
 * - `enabled: false` renders "agent policy disabled" and no select.
 * - Admin (`!auth.userAuthEnabled || auth.current()?.role === 'admin'`) gets
 *   `select[name=recoveryOverride]` with options "" (Use instance default),
 *   off, propose, autopilot. A change PUTs /api/v1/policy with the stored
 *   actions and limits and a deep copy of the stored overrides with
 *   `[key].recovery` set, or removed (the whole `[key]` entry when it becomes
 *   empty).
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { RecoveryModeRowComponent } from './recovery-mode-row.component';
import { AuthService } from '../../auth.service';

const LIMITS = { pendingTtlHours: 24, maxPendingPerActor: 50 };
const ACTIONS = { 'tap:run': 'approve', 'pipeline:delete': 'deny' };
const STORED_RECOVERY = { mode: 'propose', verifyMode: 'scratch-first', cooldownHours: 6 };

function policyResponse(overrides: any = {}, enabled = true): any {
  return {
    enabled,
    policy: {
      version: 7,
      actions: ACTIONS,
      overrides,
      limits: LIMITS,
      recovery: STORED_RECOVERY
    },
    recommended: { version: 0, actions: {}, overrides: {}, limits: LIMITS },
    actions: ['pipeline:delete', 'pipeline:update', 'tap:run'],
    pendingCount: 0
  };
}

type Auth = { userAuthEnabled: boolean; role?: 'admin' | 'editor' | 'viewer' };

describe('RecoveryModeRowComponent', () => {
  let fixture: ComponentFixture<RecoveryModeRowComponent>;
  let httpMock: HttpTestingController;

  async function setup(response: any, auth: Auth = { userAuthEnabled: true, role: 'admin' },
                       resourceType: 'pipeline' | 'tap' = 'pipeline', resourceName = 'orders'): Promise<void> {
    await TestBed.configureTestingModule({
      declarations: [RecoveryModeRowComponent],
      imports: [FormsModule],
      schemas: [NO_ERRORS_SCHEMA],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            userAuthEnabled: auth.userAuthEnabled,
            current: () => (auth.role ? { username: 'u', role: auth.role } : null),
            // Deliberately false: the row must not use isAdmin() (false when auth is off).
            isAdmin: () => false,
            canWrite: () => true
          }
        }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(RecoveryModeRowComponent);
    fixture.componentRef.setInput('resourceType', resourceType);
    fixture.componentRef.setInput('resourceName', resourceName);
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges(); // ngOnInit -> GET /api/v1/policy
    httpMock.expectOne(r => r.method === 'GET' && r.url === '/api/v1/policy').flush(response);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    return (el().textContent || '').replace(/\s+/g, ' ');
  }

  /** Rendered text without the select's option labels ("Use instance
   *  default", "autopilot", ...), so mode/source assertions read the display. */
  function displayText(): string {
    const clone = el().cloneNode(true) as HTMLElement;
    clone.querySelectorAll('select, option').forEach(n => n.remove());
    return (clone.textContent || '').replace(/\s+/g, ' ');
  }

  function select(): HTMLSelectElement | null {
    return el().querySelector('select[name=recoveryOverride]');
  }

  /** Matches both plain `value="off"` and `[ngValue]` ("1: off") options. */
  function optionMatches(o: HTMLOptionElement, v: string): boolean {
    return o.value === v || o.value.endsWith(': ' + v);
  }

  function choose(v: string): void {
    const s = select();
    expect(s).withContext('select[name=recoveryOverride] is rendered').toBeTruthy();
    const opts = Array.from(s!.options);
    const idx = opts.findIndex(o => optionMatches(o, v));
    expect(idx).withContext(`option "${v}" exists`).toBeGreaterThanOrEqual(0);
    s!.selectedIndex = idx;
    s!.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  function putBody(): any {
    const req = httpMock.expectOne(r => r.method === 'PUT' && r.url === '/api/v1/policy');
    const body = req.request.body;
    req.flush({ enabled: true, policy: { ...policyResponse().policy, version: 8, overrides: body.overrides } });
    return body;
  }

  afterEach(() => httpMock.verify());

  it('shows the instance default when no override exists', async () => {
    await setup(policyResponse({}));
    const row = el().querySelector('.lineage-row');
    expect(row).withContext('renders a .lineage-row').toBeTruthy();
    const t = displayText();
    expect(t).toContain('Self-healing');
    expect(t).toContain('propose');
    expect(t).toContain('instance default');
  });

  it('shows the override and its source', async () => {
    await setup(policyResponse({ 'pipeline:orders': { recovery: 'off' }, 'pipeline:other': { recovery: 'autopilot' } }));
    const t = displayText();
    expect(t).toContain('Self-healing');
    expect(t).toContain('off');
    expect(t).toContain('override');
    expect(t).not.toContain('instance default');
    expect(t).withContext('another resource\'s override is not shown').not.toContain('autopilot');
  });

  it('uses the tap key for a tap', async () => {
    await setup(policyResponse({ 'tap:prices': { recovery: 'autopilot' }, 'pipeline:prices': { recovery: 'off' } }),
      { userAuthEnabled: true, role: 'admin' }, 'tap', 'prices');
    const t = displayText();
    expect(t).toContain('autopilot');
    expect(t).toContain('override');
  });

  it('an admin changing the select PUTs /api/v1/policy with the stored actions, limits and overrides plus `[key].recovery`', async () => {
    const stored = {
      'pipeline:orders': { 'pipeline:update': 'approve' },
      'tap:prices': { 'tap:run': 'deny', recovery: 'off' }
    };
    await setup(policyResponse(stored));
    const opts = Array.from(select()!.options);
    for (const v of ['', 'off', 'propose', 'autopilot']) {
      expect(opts.some(o => optionMatches(o, v))).withContext(`option ${v || '(instance default)'}`).toBeTrue();
    }
    expect(text()).toContain('Use instance default');

    choose('autopilot');
    const body = putBody();
    expect(body.actions).toEqual(ACTIONS);
    expect(body.limits).toEqual(LIMITS);
    expect(body.overrides).toEqual({
      'pipeline:orders': { 'pipeline:update': 'approve', recovery: 'autopilot' },
      'tap:prices': { 'tap:run': 'deny', recovery: 'off' }
    });
    // Deep copy: the stored policy object was not mutated in place.
    expect(stored['pipeline:orders']).toEqual({ 'pipeline:update': 'approve' });
  });

  it('an admin is detected when user auth is off (isAdmin() is not used)', async () => {
    await setup(policyResponse({}), { userAuthEnabled: false });
    expect(select()).withContext('auth off: everyone is admin').toBeTruthy();
    choose('off');
    const body = putBody();
    expect(body.overrides).toEqual({ 'pipeline:orders': { recovery: 'off' } });
  });

  it('choosing Use instance default removes the key', async () => {
    await setup(policyResponse({
      'pipeline:orders': { recovery: 'off' },
      'pipeline:other': { 'pipeline:update': 'approve' }
    }));
    choose('');
    const body = putBody();
    expect(body.actions).toEqual(ACTIONS);
    expect(body.limits).toEqual(LIMITS);
    expect(Object.keys(body.overrides)).withContext('the emptied entry is removed entirely').not.toContain('pipeline:orders');
    expect(body.overrides).toEqual({ 'pipeline:other': { 'pipeline:update': 'approve' } });
  });

  it('choosing Use instance default keeps the key\'s action entries', async () => {
    await setup(policyResponse({ 'pipeline:orders': { 'pipeline:update': 'approve', recovery: 'off' } }));
    choose('');
    const body = putBody();
    expect(body.overrides).toEqual({ 'pipeline:orders': { 'pipeline:update': 'approve' } });
  });

  // PolicyAPIController.update keeps the STORED recovery overrides unless the
  // body has a `recovery` block or some override carrying `recovery`. Removing
  // the last recovery override therefore only takes effect server-side when
  // the body also carries `recovery` (the stored block, unchanged).
  it('removing the last recovery override sends the stored recovery block so the server applies the removal', async () => {
    await setup(policyResponse({ 'pipeline:orders': { recovery: 'off' } }));
    choose('');
    const body = putBody();
    expect(body.overrides).toEqual({});
    expect(body.recovery).withContext('without it the server keeps the old recovery override').toEqual(STORED_RECOVERY);
  });

  it('the select is absent for a non-admin when auth is on', async () => {
    await setup(policyResponse({ 'pipeline:orders': { recovery: 'off' } }), { userAuthEnabled: true, role: 'editor' });
    expect(select()).toBeNull();
    const t = displayText();
    expect(t).withContext('the mode is still shown read-only').toContain('off');
    expect(t).toContain('override');
  });

  it('disabled policy renders \'agent policy disabled\' and no select', async () => {
    await setup(policyResponse({}, false));
    expect(text()).toContain('agent policy disabled');
    expect(select()).toBeNull();
  });
});
