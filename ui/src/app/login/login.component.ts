import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../auth.service';

/** Fixed sentence per `ssoError` code the server's OIDC callback redirects
 *  with. Unknown codes get SSO_ERROR_GENERIC; the raw code is never shown. */
export const SSO_ERROR_SENTENCES: Record<string, string> = {
  sso_denied: 'Single sign-on was refused for this account. Sign in with your username and password, or contact your administrator.',
  sso_failed: 'Single sign-on could not be completed. Try again, or sign in with your username and password.',
  sso_no_account: 'You signed in at your identity provider, but there is no Datris user for you yet. Ask an administrator to add you.',
  sso_unverified_email: 'Your identity provider has not verified your email address, so it cannot be used to sign in.',
  sso_identity_changed: 'This Datris user is linked to a different identity at your provider. Ask an administrator to remove and re-add the user.'
};
export const SSO_ERROR_GENERIC = 'Single sign-on did not complete. Try again, or sign in with your username and password.';

@Component({
    selector: 'app-login',
    templateUrl: './login.component.html',
    styleUrl: './login.component.css',
    standalone: false
})
export class LoginComponent implements OnInit {
  username = '';
  password = '';
  showPassword = false;
  error = '';
  loading = false;
  ready = false;
  /** True only when /api/v1/version reports oidcEnabled === 'true'. */
  oidcEnabled = false;
  /** Sentence for an `ssoError` in the query string, or '' when none. */
  ssoError = '';
  /** Plain navigation (not an XHR): the server redirects to the identity provider. */
  readonly ssoLoginUrl = '/api/v1/auth/oidc/login';

  constructor(
    private auth: AuthService,
    private router: Router,
    private http: HttpClient,
    private route: ActivatedRoute
  ) {}

  /** Bounce away from /login when user-auth is disabled — the login form is
   *  meaningless in legacy / api-key mode. We can't trust auth.userAuthEnabled
   *  yet because app.component may not have populated it before this route
   *  activates, so fetch /api/v1/version directly. */
  ngOnInit(): void {
    const code = this.route.snapshot?.queryParamMap?.get('ssoError');
    if (code) {
      this.ssoError = Object.prototype.hasOwnProperty.call(SSO_ERROR_SENTENCES, code) ? SSO_ERROR_SENTENCES[code] : SSO_ERROR_GENERIC;
    }
    this.http.get<any>('/api/v1/version').subscribe({
      next: (data) => {
        if (String(data.useUserAuth) !== 'true') {
          this.router.navigate(['/']);
          return;
        }
        this.oidcEnabled = String(data.oidcEnabled) === 'true';
        this.ready = true;
      },
      error: () => {
        // If the version endpoint fails entirely, fail open so the user can
        // still attempt login.
        this.ready = true;
      }
    });
  }

  submit(): void {
    if (!this.username.trim()) {
      this.error = 'Please enter a username';
      return;
    }
    this.loading = true;
    this.error = '';
    this.auth.login(this.username.trim(), this.password).subscribe({
      next: () => {
        this.loading = false;
        this.router.navigate(['/']);
      },
      error: (err) => {
        this.loading = false;
        this.error = err?.error?.error || 'Login failed';
      }
    });
  }
}
