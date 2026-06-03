import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/** Catches 401s on /api/* calls that happen AFTER bootstrap — i.e. a session
 *  that timed out while the user was active. The one-time bootstrap probe in
 *  AppComponent and the route-activation authGuard can't see these, so without
 *  this the UI just wedges (failed subscriptions, half-loaded pages) instead of
 *  returning the user to the login screen.
 *
 *  Only acts when user-auth is enabled; the legacy x-api-key flow has no
 *  server-side session to expire. */
export const authErrorInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return next(req).pipe(
    catchError((err: unknown) => {
      if (
        auth.userAuthEnabled &&
        err instanceof HttpErrorResponse &&
        err.status === 401 &&
        req.url.startsWith('/api/') &&
        // Don't hijack the login POST — a 401 there means "bad credentials" and
        // the login form surfaces it inline. Everything else means the session
        // is gone and we should send the user back to login.
        !req.url.startsWith('/api/v1/auth/login') &&
        router.url !== '/login'
      ) {
        auth.clearUser();
        router.navigate(['/login']);
      }
      return throwError(() => err);
    })
  );
};
