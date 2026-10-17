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

const PANEL_TITLE = 'Recovery (set via API or the Configuration assistant)';
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
