/**
 * Story: Environment-aware recovery defaults
 * (plans/stories/self-healing-6-env-defaults-learn-notes.md) — the Agent
 * Policy page's read-only Recovery panel and its "Apply recommended recovery
 * settings" button (manual Acceptance bullet; spec named in the story's Files).
 *
 * Contract pinned here:
 * - GET /api/v1/policy gains `recoveryRecommended` and `environmentLabel`;
 *   `policy.recovery` is the stored block.
 * - The panel heading reads "Recovery (set via API or the Configuration
 *   assistant)" and shows the current recovery mode and the recommended mode.
 * - The button "Apply recommended recovery settings" is shown to admins only
 *   (AuthService: `!userAuthEnabled || current()?.role === 'admin'`).
 * - Clicking it asks window.confirm(...) with a message naming the mode it
 *   will set; on OK it PUTs /api/v1/policy with the stored actions,
 *   overrides and limits and `recovery` = `recoveryRecommended`; on Cancel
 *   nothing is sent.
 *
 * Story 7: Recovery mode controls
 * (plans/stories/self-healing-7-recovery-mode-controls.md) makes the panel
 * editable. Contract pinned here for the implementer:
 * - Panel heading is exactly "Recovery" (the "(set via API or the
 *   Configuration assistant)" suffix is gone).
 * - Component state: `recoveryDraft: { mode, verifyMode,
 *   allowSchemaDriftActions }`, reset from `policy.recovery` in applyPolicy
 *   (defaults off / scratch-first / false); `savingRecovery`; getter
 *   `recoveryDirty`; method `saveRecovery()`.
 * - Constants `recoveryModes` (values off, propose, autopilot) and
 *   `verifyModes` (values scratch-first, real), each entry
 *   `{ value: string; meaning: string }` (extra keys such as `label` are
 *   fine). All three mode meanings appear in the rendered page text (e.g. in
 *   the option labels and/or the hint line).
 * - DOM: `select[name=recoveryMode]` and `select[name=recoveryVerifyMode]`
 *   bound with [(ngModel)] to the draft; options carry plain `value="off"`
 *   etc. (not [ngValue]) so `select.value` reads the mode string;
 *   `input[type=checkbox][name=allowSchemaDriftActions]`. All three are
 *   `[disabled]="!isAdmin || savingRecovery"`.
 * - A button whose text includes "Save recovery settings", disabled when
 *   `!isAdmin || savingRecovery || loading || !recoveryDirty`.
 * - Non-admin text: "Only an admin can change recovery settings".
 * - saveRecovery(): switching to autopilot from a non-autopilot stored mode
 *   asks window.confirm('The recovery agent will act within your action
 *   matrix without asking. Switch recovery to autopilot?'); cancel sends
 *   nothing. Otherwise one PUT /api/v1/policy with the stored actions,
 *   overrides, limits and `recovery = { ...stored.recovery, ...recoveryDraft }`.
 *   Success text "Recovery set to <mode>, verify <verifyMode> (version N)."
 *   Errors surface `err.error.error` in `p.error`.
 * - The Mode row of `.recovery-table` (first cell "Mode") shows the STORED
 *   mode, not the draft.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';

import { AgentPolicyComponent } from './agent-policy.component';
import { AuthService } from '../../auth.service';

const STORED_RECOVERY = {
  mode: 'off', maxAiCallsPerIncident: 12, maxActionsPerIncident: 3, maxRuntimeMinutes: 15,
  maxOpenIncidents: 10, cooldownHours: 6, maxRecordDeltaPct: 25, allowColumnRemoval: false,
  allowTypeChange: false, allowSchemaDriftActions: false, maxNullRateIncrease: 0.1, verifyMode: 'scratch-first'
};

const RECOMMENDED_RECOVERY = {
  ...STORED_RECOVERY,
  mode: 'autopilot', maxRecordDeltaPct: 50, allowColumnRemoval: true, allowTypeChange: true
};

function policyResponse(): any {
  return {
    enabled: true,
    policy: {
      version: 3,
      actions: { 'tap:run': 'approve' },
      overrides: {},
      limits: { pendingTtlHours: 24, maxPendingPerActor: 50 },
      recovery: STORED_RECOVERY
    },
    recommended: { version: 0, actions: {}, overrides: {}, limits: { pendingTtlHours: 24, maxPendingPerActor: 50 } },
    actions: ['tap:create', 'tap:run'],
    pendingCount: 0,
    recoveryRecommended: RECOMMENDED_RECOVERY,
    environmentLabel: 'dev'
  };
}

const PANEL_TITLE = 'Recovery';
const APPLY_TEXT = 'Apply recommended recovery settings';

describe('AgentPolicyComponent: recovery recommendation panel', () => {
  let fixture: ComponentFixture<AgentPolicyComponent>;
  let httpMock: HttpTestingController;

  async function setup(role: 'admin' | 'editor' | 'viewer'): Promise<void> {
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
            current: () => ({ username: 'u', role }),
            isAdmin: () => role === 'admin'
          }
        }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(AgentPolicyComponent);
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges(); // ngOnInit -> GET /api/v1/policy
    httpMock.expectOne(r => r.method === 'GET' && r.url === '/api/v1/policy').flush(policyResponse());
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent || '';
  }

  function applyButton(): HTMLButtonElement | undefined {
    const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')) as HTMLButtonElement[];
    return buttons.find(b => (b.textContent || '').includes(APPLY_TEXT));
  }

  afterEach(() => httpMock.verify());

  it('renders the read-only Recovery panel with the current mode and the recommendation', async () => {
    await setup('admin');
    const t = text();
    expect(t).toContain(PANEL_TITLE);
    const headings = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('h4')).map(h => (h.textContent || '').trim());
    expect(headings).withContext('the panel heading is exactly "Recovery"').toContain(PANEL_TITLE);
    expect(t).withContext('current recovery mode shown').toContain('off');
    expect(t).withContext('recommended recovery mode shown').toContain('autopilot');
    expect(applyButton()).withContext('admins see the Apply button').toBeTruthy();
  });

  it('hides the Apply button for a non-admin user', async () => {
    await setup('editor');
    expect(text()).toContain(PANEL_TITLE);
    expect(applyButton()).toBeUndefined();
  });

  it('Apply confirms naming the mode, then PUTs the stored policy with recovery = recoveryRecommended', async () => {
    await setup('admin');
    const confirmSpy = spyOn(window, 'confirm').and.returnValue(true);
    applyButton()!.click();
    fixture.detectChanges();

    expect(confirmSpy).toHaveBeenCalledTimes(1);
    expect(String(confirmSpy.calls.mostRecent().args[0])).toContain('autopilot');

    const req = httpMock.expectOne(r => r.method === 'PUT' && r.url === '/api/v1/policy');
    const body = req.request.body as any;
    expect(body.recovery).toEqual(RECOMMENDED_RECOVERY);
    expect(body.actions).withContext('the stored action matrix is kept').toEqual({ 'tap:run': 'approve' });
    expect(body.limits).toEqual({ pendingTtlHours: 24, maxPendingPerActor: 50 });
    req.flush({ enabled: true, policy: { ...policyResponse().policy, version: 4, recovery: RECOMMENDED_RECOVERY } });
  });

  it('Apply sends nothing when the confirm dialog is cancelled', async () => {
    await setup('admin');
    spyOn(window, 'confirm').and.returnValue(false);
    applyButton()!.click();
    fixture.detectChanges();
    httpMock.expectNone(r => r.method === 'PUT' && r.url === '/api/v1/policy');
  });

  it('Apply after a Save sends the just-saved actions and limits, not the ones first loaded', async () => {
    await setup('admin');
    const cmp = fixture.componentInstance;
    cmp.setMode('tap:create', 'deny');
    cmp.pendingTtlHours = 48;
    cmp.save();
    const saveReq = httpMock.expectOne(r => r.method === 'PUT' && r.url === '/api/v1/policy');
    expect(saveReq.request.body.recovery).withContext('a plain Save does not send recovery').toBeUndefined();
    const saved = {
      ...policyResponse().policy,
      version: 4,
      actions: { 'tap:run': 'approve', 'tap:create': 'deny' },
      limits: { pendingTtlHours: 48, maxPendingPerActor: 50 }
    };
    saveReq.flush({ enabled: true, policy: saved });
    fixture.detectChanges();

    spyOn(window, 'confirm').and.returnValue(true);
    applyButton()!.click();
    fixture.detectChanges();
    const req = httpMock.expectOne(r => r.method === 'PUT' && r.url === '/api/v1/policy');
    const body = req.request.body as any;
    expect(body.actions).toEqual({ 'tap:run': 'approve', 'tap:create': 'deny' });
    expect(body.limits).toEqual({ pendingTtlHours: 48, maxPendingPerActor: 50 });
    expect(body.recovery).toEqual(RECOMMENDED_RECOVERY);
    req.flush({ enabled: true, policy: { ...saved, version: 5, recovery: RECOMMENDED_RECOVERY } });
  });
});

describe('AgentPolicyComponent: recovery mode controls', () => {
  let fixture: ComponentFixture<AgentPolicyComponent>;
  let httpMock: HttpTestingController;

  async function setup(role: 'admin' | 'editor' | 'viewer'): Promise<void> {
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
            current: () => ({ username: 'u', role }),
            isAdmin: () => role === 'admin'
          }
        }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(AgentPolicyComponent);
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    httpMock.expectOne(r => r.method === 'GET' && r.url === '/api/v1/policy').flush(policyResponse());
    fixture.detectChanges();
    await settle();
  }

  /** ngModel writes to the DOM asynchronously. */
  async function settle(): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  /** Untyped handle so the spec compiles before the new members exist. */
  function cmp(): any {
    return fixture.componentInstance as any;
  }

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    return root().textContent || '';
  }

  function modeSelect(): HTMLSelectElement | null {
    return root().querySelector('select[name=recoveryMode]');
  }

  function verifySelect(): HTMLSelectElement | null {
    return root().querySelector('select[name=recoveryVerifyMode]');
  }

  function driftCheckbox(): HTMLInputElement | null {
    return root().querySelector('input[type=checkbox][name=allowSchemaDriftActions]');
  }

  function saveRecoveryButton(): HTMLButtonElement | undefined {
    const buttons = Array.from(root().querySelectorAll('button')) as HTMLButtonElement[];
    return buttons.find(b => (b.textContent || '').includes('Save recovery settings'));
  }

  function applyButton(): HTMLButtonElement | undefined {
    const buttons = Array.from(root().querySelectorAll('button')) as HTMLButtonElement[];
    return buttons.find(b => (b.textContent || '').includes(APPLY_TEXT));
  }

  /** The "Current" cell of a `.recovery-table` row by its first-cell label. */
  function currentCell(label: string): string | undefined {
    const rows = Array.from(root().querySelectorAll('.recovery-table tbody tr')) as HTMLTableRowElement[];
    const row = rows.find(r => (r.cells[0]?.textContent || '').trim() === label);
    return row ? (row.cells[1]?.textContent || '').trim() : undefined;
  }

  function isPut(r: any): boolean {
    return r.method === 'PUT' && r.url === '/api/v1/policy';
  }

  afterEach(() => httpMock.verify());

  it('renders the Mode, Verify mode and Schema drift controls with the stored values', async () => {
    await setup('admin');
    expect(modeSelect()).withContext('select[name=recoveryMode]').toBeTruthy();
    expect(verifySelect()).withContext('select[name=recoveryVerifyMode]').toBeTruthy();
    expect(driftCheckbox()).withContext('checkbox[name=allowSchemaDriftActions]').toBeTruthy();
    expect(modeSelect()!.value).toBe('off');
    expect(verifySelect()!.value).toBe('scratch-first');
    expect(driftCheckbox()!.checked).toBe(false);

    const modes = cmp().recoveryModes as { value: string; meaning: string }[];
    expect(Array.isArray(modes)).withContext('recoveryModes constant').toBeTrue();
    expect((modes || []).map(m => m.value)).toEqual(['off', 'propose', 'autopilot']);
    const verifies = cmp().verifyModes as { value: string; meaning: string }[];
    expect((verifies || []).map(m => m.value)).toEqual(['scratch-first', 'real']);
    const t = text();
    for (const m of modes || []) {
      expect(typeof m.meaning === 'string' && m.meaning.length > 0).withContext(`meaning for ${m.value}`).toBeTrue();
      expect(t).withContext(`page shows the meaning of ${m.value}`).toContain(m.meaning);
    }

    expect(cmp().recoveryDirty).withContext('nothing edited yet').toBe(false);
    expect(saveRecoveryButton()).withContext('Save recovery settings button').toBeTruthy();
    expect(saveRecoveryButton()!.disabled).withContext('Save disabled when nothing is dirty').toBeTrue();
  });

  it('disables the controls and the Save for a non-admin', async () => {
    await setup('editor');
    expect(modeSelect()).toBeTruthy();
    expect(verifySelect()).toBeTruthy();
    expect(driftCheckbox()).toBeTruthy();
    expect(modeSelect()!.disabled).toBe(true);
    expect(verifySelect()!.disabled).toBe(true);
    expect(driftCheckbox()!.disabled).toBe(true);
    expect(saveRecoveryButton()).toBeTruthy();
    expect(saveRecoveryButton()!.disabled).toBeTrue();
    expect(text()).toContain('Only an admin can change recovery settings');
    expect(applyButton()).withContext('story-6: no Apply for a non-admin').toBeUndefined();
  });

  it('Save PUTs recovery.mode = propose with actions, overrides, limits and verifyMode unchanged', async () => {
    await setup('admin');
    const confirmSpy = spyOn(window, 'confirm').and.returnValue(true);
    expect(cmp().recoveryDraft).withContext('recoveryDraft state').toBeTruthy();
    cmp().recoveryDraft.mode = 'propose';
    fixture.detectChanges();
    expect(cmp().recoveryDirty).toBe(true);
    expect(typeof cmp().saveRecovery).withContext('saveRecovery()').toBe('function');
    cmp().saveRecovery();
    fixture.detectChanges();

    expect(confirmSpy).not.toHaveBeenCalled();
    const req = httpMock.expectOne(isPut);
    const body = req.request.body as any;
    expect(body.recovery.mode).toBe('propose');
    expect(body.recovery.verifyMode).toBe('scratch-first');
    expect(body.recovery.allowSchemaDriftActions).toBe(false);
    expect(body.recovery.maxRecordDeltaPct).withContext('thresholds echoed').toBe(25);
    expect(body.actions).toEqual({ 'tap:run': 'approve' });
    expect(body.overrides).toEqual({});
    expect(body.limits).toEqual({ pendingTtlHours: 24, maxPendingPerActor: 50 });

    req.flush({ enabled: true, policy: { ...policyResponse().policy, version: 4, recovery: { ...STORED_RECOVERY, mode: 'propose' } } });
    await settle();
    expect(currentCell('Mode')).toBe('propose');
    const success = root().querySelector('.success')?.textContent || '';
    expect(success).toContain('propose');
    expect(success).toContain('version 4');
    expect(cmp().recoveryDirty).toBe(false);
    expect(saveRecoveryButton()!.disabled).withContext('Save disabled again after saving').toBeTrue();
  });

  it('switching to autopilot asks for confirmation naming the action matrix', async () => {
    await setup('admin');
    const confirmSpy = spyOn(window, 'confirm').and.returnValue(true);
    cmp().recoveryDraft.mode = 'autopilot';
    cmp().saveRecovery();
    fixture.detectChanges();

    expect(confirmSpy).toHaveBeenCalledTimes(1);
    const msg = String(confirmSpy.calls.mostRecent().args[0]);
    expect(msg).toContain('without asking');
    expect(msg).toContain('action matrix');
    const req = httpMock.expectOne(isPut);
    expect((req.request.body as any).recovery.mode).toBe('autopilot');
    req.flush({ enabled: true, policy: { ...policyResponse().policy, version: 4, recovery: { ...STORED_RECOVERY, mode: 'autopilot' } } });
  });

  it('cancelling the autopilot confirmation sends nothing', async () => {
    await setup('admin');
    const confirmSpy = spyOn(window, 'confirm').and.returnValue(false);
    cmp().recoveryDraft.mode = 'autopilot';
    cmp().saveRecovery();
    await settle();

    expect(confirmSpy).toHaveBeenCalledTimes(1);
    httpMock.expectNone(isPut);
    expect(cmp().recoveryDraft.mode).toBe('autopilot');
    expect(currentCell('Mode')).toBe('off');
  });

  it('a server error is shown as the error text', async () => {
    await setup('admin');
    spyOn(window, 'confirm').and.returnValue(true);
    cmp().recoveryDraft.verifyMode = 'real';
    cmp().saveRecovery();
    fixture.detectChanges();

    const req = httpMock.expectOne(isPut);
    expect((req.request.body as any).recovery.verifyMode).toBe('real');
    req.flush({ error: 'recovery.verifyMode must be scratch-first or real' }, { status: 400, statusText: 'Bad Request' });
    await settle();
    expect((root().querySelector('.error')?.textContent || '').trim()).toBe('recovery.verifyMode must be scratch-first or real');
    expect(currentCell('Mode')).toBe('off');
  });
});
