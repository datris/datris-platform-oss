import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';

export const apiKeyInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);

  // Always send credentials (session cookie) for /api requests.
  let outgoing = req.url.startsWith('/api/')
    ? req.clone({ withCredentials: true })
    : req;

  // Only attach x-api-key when user-auth is OFF — otherwise a stale localStorage key
  // would bypass role checks server-side.
  if (!auth.userAuthEnabled) {
    const apiKey = localStorage.getItem('datris-api-key');
    if (apiKey && outgoing.url.startsWith('/api/')) {
      outgoing = outgoing.clone({ setHeaders: { 'x-api-key': apiKey } });
    }
  }
  return next(outgoing);
};
