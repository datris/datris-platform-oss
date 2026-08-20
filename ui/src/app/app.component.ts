import { Component, OnInit, HostListener } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router, NavigationEnd } from '@angular/router';
import { filter } from 'rxjs/operators';
import { HealthService } from './health.service';
import { AuthService, CurrentUser } from './auth.service';

@Component({
    selector: 'app-root',
    templateUrl: './app.component.html',
    styleUrl: './app.component.css',
    host: { '(document:click)': 'closeUserMenu()' },
    standalone: false
})
export class AppComponent implements OnInit {
  title = 'pipeline-ui';
  hasApiKey = !!localStorage.getItem('datris-api-key');
  requiresApiKey = false;
  isTrial = false;
  environment = '';
  version = '';
  helpMenuOpen = false;

  // user-auth
  useUserAuth = false;
  currentUser: CurrentUser | null = null;
  showSetPassword = false;
  isLoginRoute = false;
  bootstrapped = false;
  userMenuOpen = false;

  constructor(
    private healthService: HealthService,
    private http: HttpClient,
    private auth: AuthService,
    private router: Router
  ) {}

  toggleHelpMenu(event: MouseEvent): void {
    event.stopPropagation();
    this.helpMenuOpen = !this.helpMenuOpen;
  }

  @HostListener('document:click')
  closeHelpMenu(): void {
    this.helpMenuOpen = false;
  }

  /** Last time we re-probed /me after the tab regained visibility. */
  private lastSessionProbe = 0;

  /** A session can die while the tab is hidden. The server's stale-cookie 401
   *  is one-shot (it clears the cookie), and once the cookie is gone requests
   *  degrade to the anonymous legacy path and never 401 again — so whichever
   *  code path spends that 401 decides whether the user sees the login screen.
   *  Instead of relying on every fetch()/SSE path to mirror the interceptor,
   *  re-probe /me whenever the user comes back to the tab: /me 401s without a
   *  live session regardless of the anonymous fallback. Throttled so rapid
   *  alt-tabbing doesn't spam probes. */
  @HostListener('document:visibilitychange')
  recheckSessionOnReturn(): void {
    if (document.visibilityState !== 'visible') return;
    if (!this.auth.userAuthEnabled || !this.bootstrapped || this.isLoginRoute) return;
    const now = Date.now();
    if (now - this.lastSessionProbe < 30_000) return;
    this.lastSessionProbe = now;
    this.auth.refreshMe().subscribe(user => {
      if (!user) this.router.navigate(['/login']);
    });
  }

  ngOnInit(): void {
    // Track whether we're on the login route so the chrome can hide.
    this.router.events.pipe(filter(e => e instanceof NavigationEnd))
      .subscribe(() => { this.isLoginRoute = this.router.url.startsWith('/login'); });

    // Accept API key from URL query param (set by dashboard link)
    const urlKey = new URLSearchParams(window.location.search).get('key');
    if (urlKey) {
      localStorage.setItem('datris-api-key', urlKey);
      this.hasApiKey = true;
      window.history.replaceState({}, '', window.location.pathname);
    }

    // Probe /version to figure out which auth mode the server is in.
    this.http.get<any>('/api/v1/version').subscribe({
      next: (data) => {
        this.environment = data.environment || '';
        this.version = data.version || '';
        this.isTrial = data.multiTenant === 'true';
        this.useUserAuth = data.useUserAuth === 'true';
        this.auth.userAuthEnabled = this.useUserAuth;

        if (this.useUserAuth) {
          // User-auth mode: probe /me, redirect to /login on 401.
          // The session cookie is sufficient auth for the UI; no x-api-key
          // is sent on requests from logged-in browsers — the server's
          // APIKeyValidator bypasses the key check when UserContext is set.
          this.auth.refreshMe().subscribe(user => {
            this.currentUser = user;
            this.bootstrapped = true;
            if (!user) {
              this.router.navigate(['/login']);
            } else {
              if (user.mustSetPassword) this.showSetPassword = true;
              this.healthService.loadHealth();
            }
          });
          // Keep currentUser in sync for header/logout.
          this.auth.user().subscribe(u => {
            this.currentUser = u;
            this.showSetPassword = !!u?.mustSetPassword;
          });
        } else {
          // Legacy x-api-key mode (or self-hosted with no auth).
          this.bootstrapped = true;
          if (this.hasApiKey) {
            this.healthService.loadHealth();
          } else if (this.isTrial) {
            // Trial = multi-tenant; api key is required to identify the tenant.
            this.requiresApiKey = true;
          } else {
            // Self-hosted, no auth: skip the api-key prompt and show the app.
            // Preserves the legacy bypass that existed before user-auth was added.
            this.hasApiKey = true;
            this.healthService.loadHealth();
          }
        }
      },
      error: () => {
        // /version failed → server requires an x-api-key (legacy mode with useApiKeys=true).
        this.bootstrapped = true;
        this.requiresApiKey = true;
      }
    });
  }

  onApiKeySet(): void {
    this.hasApiKey = true;
    this.requiresApiKey = false;
    this.healthService.loadHealth();
    this.loadEnvironment();
  }

  onPasswordSet(): void {
    this.showSetPassword = false;
    this.healthService.loadHealth();
  }

  logout(): void {
    this.userMenuOpen = false;
    this.auth.logout().subscribe(() => {
      this.currentUser = null;
      this.router.navigate(['/login']);
    });
  }

  toggleUserMenu(event: Event): void {
    event.stopPropagation();
    this.userMenuOpen = !this.userMenuOpen;
  }

  closeUserMenu(): void {
    this.userMenuOpen = false;
  }

  isAdmin(): boolean {
    return this.currentUser?.role === 'admin';
  }

  /** True when the user opened this window/tab as a popout view (e.g. from the
   *  Agent Monitor "pop out" button). Popout windows skip the top nav so the
   *  embedded content gets the full viewport. */
  get isPopout(): boolean {
    return window.location.pathname.startsWith('/popout/');
  }

  /** True when the main app chrome should be visible. */
  showChrome(): boolean {
    if (this.isPopout) return false;
    if (this.isLoginRoute) return false;
    if (this.useUserAuth) return !!this.currentUser;
    return this.hasApiKey;
  }

  private loadEnvironment(): void {
    this.http.get<any>('/api/v1/version').subscribe({
      next: (data) => {
        this.isTrial = data.multiTenant === 'true';
        this.environment = data.environment || '';
        this.version = data.version || '';
      }
    });
  }

}
