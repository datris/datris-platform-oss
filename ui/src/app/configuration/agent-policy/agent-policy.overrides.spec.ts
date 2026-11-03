/**
 * Story: Self-healing cost loop (plans/stories/self-healing-cost-loop.md) —
 * per-override Recovery select in the Agent Policy override editor.
 *
 * Today `applyPolicy` maps a server override `{ recovery: 'off' }` into the
 * action entries as action "recovery" / mode "off", which the action and mode
 * selects cannot display (a blank row). Contract pinned here:
 * - `OverrideRow` gains `recovery?: RecoveryMode | ''`; `applyPolicy` splits
 *   `recovery` out of the action entries.
 * - Each override renders `select[name="override-recovery-{{i}}"]` with
 *   options "" (inherit), off, propose, autopilot.
 * - `collectOverrides` (used by Save) emits `recovery` into the override
 *   object when set and omits it when cleared.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';

import { AgentPolicyComponent } from './agent-policy.component';
import { AuthService } from '../../auth.service';

const LIMITS = { pendingTtlHours: 24, maxPendingPerActor: 50 };

function policyResponse(overrides: any): any {
  return {
    enabled: true,
    policy: {
      version: 3,
      actions: { 'tap:run': 'approve' },
      overrides,
      limits: LIMITS,
      recovery: { mode: 'propose', verifyMode: 'scratch-first' }
    },
    recommended: { version: 0, actions: {}, overrides: {}, limits: LIMITS },
    actions: ['pipeline:delete', 'pipeline:update', 'tap:create', 'tap:run'],
    pendingCount: 0
  };
}

describe('AgentPolicyComponent: per-override recovery mode', () => {
  let fixture: ComponentFixture<AgentPolicyComponent>;
  let httpMock: HttpTestingController;

  async function setup(overrides: any): Promise<void> {
    await TestBed.configureTestingModule({
      declarations: [AgentPolicyComponent],
      imports: [FormsModule],
      schemas: [NO_ERRORS_SCHEMA],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Router, useValue: { navigate: jasmine.createSpy('navigate') } },
        {
          provide: AuthService,
          useValue: {
            userAuthEnabled: true,
            current: () => ({ username: 'u', role: 'admin' }),
            isAdmin: () => true
          }
        }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(AgentPolicyComponent);
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges(); // ngOnInit -> GET /api/v1/policy
    httpMock.expectOne(r => r.method === 'GET' && r.url === '/api/v1/policy').flush(policyResponse(overrides));
    fixture.detectChanges();
    await fixture.whenStable(); // let ngModel write select values
    fixture.detectChanges();
  }

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  /** Matches both plain `value="off"` and `[ngValue]` ("1: off") options. */
  function optionMatches(o: HTMLOptionElement, v: string): boolean {
    return o.value === v || o.value.endsWith(': ' + v);
  }

  function recoverySelect(i: number): HTMLSelectElement | null {
    return el().querySelector(`select[name="override-recovery-${i}"]`);
  }

  function selectedValue(s: HTMLSelectElement): string {
    const o = s.options[s.selectedIndex];
    if (!o) return '';
    const m = /^\d+: (.*)$/.exec(o.value);
    return m ? m[1] : o.value;
  }

  function choose(s: HTMLSelectElement, v: string): void {
    const idx = Array.from(s.options).findIndex(o => optionMatches(o, v));
    expect(idx).withContext(`option "${v}" exists`).toBeGreaterThanOrEqual(0);
    s.selectedIndex = idx;
    s.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  function savePutBody(): any {
    const saveBtn = Array.from(el().querySelectorAll('button')).find(b => (b.textContent || '').trim() === 'Save') as HTMLButtonElement;
    expect(saveBtn).withContext('the Save button').toBeTruthy();
    saveBtn.click();
    fixture.detectChanges();
    const req = httpMock.expectOne(r => r.method === 'PUT' && r.url === '/api/v1/policy');
    const body = req.request.body;
    req.flush({ enabled: true, policy: { ...policyResponse(body.overrides).policy, version: 4 } });
    return body;
  }

  afterEach(() => httpMock.verify());

  it('an override with only a recovery entry renders no blank action row and the recovery select shows the mode', async () => {
    await setup({ 'pipeline:orders': { recovery: 'off' } });
    const cmp: any = fixture.componentInstance;
    expect(cmp.overrides.length).toBe(1);
    expect(cmp.overrides[0].resource).toBe('pipeline:orders');
    expect(cmp.overrides[0].entries.some((e: any) => e.action === 'recovery'))
      .withContext('recovery is not an action entry').toBeFalse();
    expect(cmp.overrides[0].recovery).toBe('off');

    const actionSelects = Array.from(el().querySelectorAll('select[name^="override-action-0-"]')) as HTMLSelectElement[];
    for (const s of actionSelects) {
      expect(selectedValue(s)).withContext('no action row with a blank/unknown action').not.toBe('');
    }
    expect(actionSelects.length).withContext('no action rows for a recovery-only override').toBe(0);

    const rs = recoverySelect(0);
    expect(rs).withContext('select[name="override-recovery-0"] is rendered').toBeTruthy();
    for (const v of ['', 'off', 'propose', 'autopilot']) {
      expect(Array.from(rs!.options).some(o => optionMatches(o, v))).withContext(`option ${v || '(inherit)'}`).toBeTrue();
    }
    expect(selectedValue(rs!)).toBe('off');
  });

  it('saving sends `overrides[res].recovery` alongside the action entries', async () => {
    await setup({
      'pipeline:orders': { 'pipeline:delete': 'deny', recovery: 'propose' },
      'tap:prices': { 'tap:run': 'approve' }
    });
    expect(selectedValue(recoverySelect(0)!)).toBe('propose');
    expect(selectedValue(recoverySelect(1)!)).withContext('no recovery set → inherit').toBe('');

    choose(recoverySelect(1)!, 'autopilot');
    const body = savePutBody();
    expect(body.overrides).toEqual({
      'pipeline:orders': { 'pipeline:delete': 'deny', recovery: 'propose' },
      'tap:prices': { 'tap:run': 'approve', recovery: 'autopilot' }
    });
  });

  it('a recovery-only override survives a save unchanged', async () => {
    await setup({ 'pipeline:orders': { recovery: 'off' } });
    const body = savePutBody();
    expect(body.overrides).toEqual({ 'pipeline:orders': { recovery: 'off' } });
  });

  it('clearing the recovery select drops `recovery` from the PUT body', async () => {
    await setup({
      'pipeline:orders': { 'pipeline:delete': 'deny', recovery: 'off' },
      'tap:prices': { recovery: 'autopilot' }
    });
    choose(recoverySelect(0)!, '');
    const body = savePutBody();
    expect(body.overrides['pipeline:orders']).toEqual({ 'pipeline:delete': 'deny' });
    expect(body.overrides['tap:prices']).toEqual({ recovery: 'autopilot' });
  });
});
