/**
 * Story: OIDC single sign-on 1 (plans/stories/oidc-sso-login.md) — login.component.spec.ts bullet.
 *
 * Pins: the login page shows a "Sign in with SSO" control only when
 * `/api/v1/version` reports `oidcEnabled === 'true'`; an `ssoError` code in the
 * query string renders a fixed sentence per code (sso_no_account is the "ask an
 * administrator" sentence) and an unknown code renders a generic sentence, never
 * the raw code. The query string is offered both through ActivatedRoute
 * (snapshot + observables) and is otherwise implementation-neutral.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';

import { LoginComponent } from './login.component';
import { AuthService } from '../auth.service';

describe('LoginComponent — Sign in with SSO', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let http: HttpTestingController;

  async function setup(version: Record<string, string>, query: Record<string, string> = {}): Promise<HTMLElement> {
    const route = {
      snapshot: { queryParamMap: convertToParamMap(query), queryParams: query },
      queryParamMap: of(convertToParamMap(query)),
      queryParams: of(query)
    };
    await TestBed.configureTestingModule({
      declarations: [LoginComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { login: jasmine.createSpy('login') } },
        { provide: Router, useValue: { navigate: jasmine.createSpy('navigate') } },
        { provide: ActivatedRoute, useValue: route }
      ]
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/version').flush(version);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function ssoControls(root: HTMLElement): Element[] {
    return Array.from(root.querySelectorAll('a, button')).filter(el => /sign in with sso/i.test(el.textContent || ''));
  }

  function text(root: HTMLElement): string {
    return (root.textContent || '').replace(/\s+/g, ' ').trim();
  }

  afterEach(() => http?.verify());

  it('SSO button hidden when oidcEnabled is false', async () => {
    const root = await setup({ useUserAuth: 'true', oidcEnabled: 'false' });
    expect(root.querySelector('form')).withContext('password form still rendered').not.toBeNull();
    expect(ssoControls(root).length).toBe(0);
  });

  it('SSO button hidden when the server does not report oidcEnabled (older server)', async () => {
    const root = await setup({ useUserAuth: 'true' });
    expect(ssoControls(root).length).toBe(0);
  });

  it('shown when true', async () => {
    const root = await setup({ useUserAuth: 'true', oidcEnabled: 'true' });
    expect(root.querySelector('form')).withContext('password form still rendered').not.toBeNull();
    expect(ssoControls(root).length).toBe(1);
  });

  it('ssoError code renders its sentence and unknown codes render the generic one', async () => {
    // Baseline: no error in the query string.
    let root = await setup({ useUserAuth: 'true', oidcEnabled: 'true' });
    const baseline = text(root);
    expect(baseline).not.toMatch(/administrator/i);
    TestBed.resetTestingModule();

    // Known code: the "ask an administrator" sentence.
    root = await setup({ useUserAuth: 'true', oidcEnabled: 'true' }, { ssoError: 'sso_no_account' });
    const noAccount = text(root);
    expect(noAccount).toMatch(/administrator/i);
    expect(noAccount).not.toContain('sso_no_account');
    const noAccountSentence = noAccount.replace(baseline, '');
    TestBed.resetTestingModule();

    // Unknown code: a generic sentence is shown, the raw code never is.
    root = await setup({ useUserAuth: 'true', oidcEnabled: 'true' }, { ssoError: 'evil_<b>code</b>' });
    const unknown = text(root);
    expect(unknown).not.toContain('evil_');
    expect(unknown.length).withContext('a generic sentence is rendered').toBeGreaterThan(baseline.length);
    expect(unknown).not.toBe(noAccount);
    expect(noAccountSentence.length).toBeGreaterThan(0);
  });
});
