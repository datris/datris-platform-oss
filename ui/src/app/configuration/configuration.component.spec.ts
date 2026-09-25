/**
 * Story: Editing a secret keeps the values you did not change
 * (plans/stories/secrets-edit-preserves-sensitive-fields.md) — review follow-up.
 *
 * The server now preserves a stored sensitive value when a PUT sends "" for
 * it, so the Configuration tab can no longer clear a slot secret's apiKey by
 * sending an empty string. In an Azure Entra mode (service principal /
 * managed identity) the slot PUT must OMIT apiKey so the stored key is
 * removed and request-time resolution falls through to Entra.
 */
import { TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of, Subject } from 'rxjs';

import { ConfigurationComponent } from './configuration.component';
import { ModelCatalogService } from '../model-catalog.service';
import { AuthService } from '../auth.service';
import { ConfigChatContextService } from '../config-chat/config-chat-context.service';
import { ConfigAssistantStateService } from '../config-chat/config-assistant-state.service';

describe('ConfigurationComponent — Azure Entra mode omits the slot apiKey', () => {
  let component: ConfigurationComponent;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ConfigurationComponent],
      imports: [FormsModule],
      schemas: [NO_ERRORS_SCHEMA],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { queryParamMap: of(convertToParamMap({})) } },
        { provide: ModelCatalogService, useValue: { fetch: () => new Promise(() => { /* never resolves */ }) } },
        { provide: AuthService, useValue: { current: () => ({ role: 'admin' }) } }
      ]
    }).compileComponents();

    // Do not call fixture.detectChanges(): ngOnInit issues a fan of GETs that
    // are irrelevant here. Drive save() on the bare instance instead.
    component = TestBed.createComponent(ConfigurationComponent).componentInstance;
    httpMock = TestBed.inject(HttpTestingController);

    component.aiPrimaryProvider = 'azure';
    component.aiPrimaryModel = 'gpt-5.6-sol';
    component.aiPrimaryEndpoint = 'https://example-resource.openai.azure.com/openai/v1/chat/completions';
    // A previously stored key is still showing masked in the panel.
    component.azureApiKey = '••••••••';
  });

  function aiPrimaryPutBody(): Record<string, string> {
    component.save();
    const req = httpMock.expectOne(r => r.method === 'PUT' && r.url === '/api/v1/secrets/ai-primary');
    return req.request.body as Record<string, string>;
  }

  it('service-principal mode save omits apiKey from the ai-primary PUT', () => {
    component.azureAuthMode = 'sp';
    component.azureTenantId = '00000000-0000-0000-0000-000000000001';
    component.azureClientId = '00000000-0000-0000-0000-000000000002';
    component.azureClientSecret = 'sp-secret';

    const body = aiPrimaryPutBody();
    expect(body['provider']).toBe('azure');
    expect(Object.prototype.hasOwnProperty.call(body, 'apiKey'))
      .withContext('an empty apiKey would be preserved server-side; the key must be omitted').toBeFalse();
  });

  it('managed-identity mode save omits apiKey from the ai-primary PUT', () => {
    component.azureAuthMode = 'mi';

    const body = aiPrimaryPutBody();
    expect(Object.prototype.hasOwnProperty.call(body, 'apiKey')).toBeFalse();
  });

  it('API-key mode save still sends the (masked) apiKey so the server preserves it', () => {
    component.azureAuthMode = 'key';

    const body = aiPrimaryPutBody();
    expect(body['apiKey']).toBe('••••••••');
  });
});

/**
 * Story: Configuration chat, UI: panel, transport, state, context, host wiring
 * (plans/stories/config-chat-ui-panel.md), Acceptance bullet 2.
 *
 * The Configuration page hosts the "Ask" side panel for admins only (or
 * everyone when user auth is off), never on trial; every sub-tab change is
 * published to ConfigChatContextService; ?tab=code-repo deep-links work.
 */
describe('ConfigurationComponent — configuration chat panel host', () => {
  let httpMock: HttpTestingController;

  function setup(opts: { role?: string; tab?: string } = {}) {
    TestBed.configureTestingModule({
      declarations: [ConfigurationComponent],
      imports: [FormsModule],
      schemas: [NO_ERRORS_SCHEMA],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: { queryParamMap: of(convertToParamMap(opts.tab ? { tab: opts.tab } : {})) }
        },
        { provide: ModelCatalogService, useValue: { fetch: () => new Promise(() => { /* never resolves */ }) } },
        { provide: AuthService, useValue: { current: () => ({ role: opts.role ?? 'admin' }) } }
      ]
    });
    const fixture = TestBed.createComponent(ConfigurationComponent);
    httpMock = TestBed.inject(HttpTestingController);
    return fixture;
  }

  /** Run ngOnInit and answer the /api/v1/version probe that sets
   *  environment / useUserAuth, then re-render. */
  function init(fixture: any, version: Record<string, string>) {
    fixture.detectChanges();
    httpMock.expectOne('/api/v1/version').flush(version);
    fixture.detectChanges();
  }

  function panel(fixture: any): Element | null {
    return (fixture.nativeElement as HTMLElement).querySelector('app-config-chat-panel');
  }

  it('panel hidden when useUserAuth and role editor', () => {
    const fixture = setup({ role: 'editor' });
    init(fixture, { environment: 'dev', useUserAuth: 'true' });

    expect((fixture.componentInstance as any).canSeeChat).toBeFalse();
    expect(panel(fixture)).toBeNull();
  });

  it('panel hidden on trial environment', () => {
    const fixture = setup({ role: 'admin' });
    init(fixture, { environment: 'trial-abc123', useUserAuth: 'false' });

    expect((fixture.componentInstance as any).canSeeChat).toBeFalse();
    expect(panel(fixture)).toBeNull();
  });

  it('panel shown for admin', () => {
    const fixture = setup({ role: 'admin' });
    init(fixture, { environment: 'dev', useUserAuth: 'true' });

    expect((fixture.componentInstance as any).canSeeChat).toBeTrue();
    expect(panel(fixture)).not.toBeNull();
  });

  it('setting activeTab publishes {tab} to ConfigChatContextService', () => {
    const fixture = setup({ role: 'admin' });
    const ctx = TestBed.inject(ConfigChatContextService);
    const publish = spyOn(ctx, 'publish').and.callThrough();

    fixture.componentInstance.activeTab = 'users';

    expect(publish).toHaveBeenCalledWith({ tab: 'users' });
    expect(ctx.snapshot()).toEqual({ tab: 'users' });

    fixture.componentInstance.activeTab = 'secrets';
    expect(ctx.snapshot()).toEqual({ tab: 'secrets' });
  });

  it('?tab=code-repo selects code-repo', () => {
    const fixture = setup({ role: 'admin', tab: 'code-repo' });
    init(fixture, { environment: 'dev', useUserAuth: 'false' });

    expect(fixture.componentInstance.activeTab).toBe('code-repo');
    expect(TestBed.inject(ConfigChatContextService).snapshot()).toEqual({ tab: 'code-repo' });
  });
});

/**
 * Story: Configuration chat, UI: confirm cards, secret form, show-once values,
 * sub-tab refresh (plans/stories/config-chat-ui-cards.md), Acceptance bullet 2.
 *
 * The host page listens to the chat state: a "Show me" click (showMe$) switches
 * the sub-tab, and a completed change to the AI Providers sub-tab
 * (changed$ {tab:'ai-providers'}) reloads the form via loadConfig() instead of
 * a page refresh. The state service is faked with plain Subjects so these
 * cases exercise only the host's subscriptions.
 */
describe('ConfigurationComponent — configuration chat show me and refresh', () => {
  let httpMock: HttpTestingController;
  let showMe: Subject<string>;
  let changed: Subject<{ tab: string }>;

  function setup() {
    showMe = new Subject<string>();
    changed = new Subject<{ tab: string }>();
    TestBed.configureTestingModule({
      declarations: [ConfigurationComponent],
      imports: [FormsModule],
      schemas: [NO_ERRORS_SCHEMA],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { queryParamMap: of(convertToParamMap({})) } },
        { provide: ModelCatalogService, useValue: { fetch: () => new Promise(() => { /* never resolves */ }) } },
        { provide: AuthService, useValue: { current: () => ({ role: 'admin' }) } },
        {
          provide: ConfigAssistantStateService,
          useValue: {
            showMe$: showMe.asObservable(),
            changed$: changed.asObservable(),
            openRequested$: new Subject<void>().asObservable()
          }
        }
      ]
    });
    const fixture = TestBed.createComponent(ConfigurationComponent);
    httpMock = TestBed.inject(HttpTestingController);
    return fixture;
  }

  function init(fixture: any) {
    fixture.detectChanges();
    httpMock.expectOne('/api/v1/version').flush({ environment: 'dev', useUserAuth: 'true', useApiKeys: 'true' });
    fixture.detectChanges();
  }

  it('showMe$ sets activeTab', () => {
    const fixture = setup();
    init(fixture);
    const ctx = TestBed.inject(ConfigChatContextService);
    expect(fixture.componentInstance.activeTab).toBe('ai-providers');

    showMe.next('secrets');

    expect(fixture.componentInstance.activeTab).toBe('secrets');
    // Through the setter, so the chat context follows.
    expect(ctx.snapshot()).toEqual({ tab: 'secrets' });
  });

  it("changed$ {tab:'ai-providers'} calls loadConfig", () => {
    const fixture = setup();
    const loadConfig = spyOn(fixture.componentInstance, 'loadConfig');
    init(fixture);
    loadConfig.calls.reset();

    changed.next({ tab: 'users' });
    expect(loadConfig).not.toHaveBeenCalled();

    changed.next({ tab: 'ai-providers' });
    expect(loadConfig).toHaveBeenCalledTimes(1);
  });
});
