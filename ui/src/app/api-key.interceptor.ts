import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';

export const apiKeyInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);

  // Always send credentials (session cookie) for /api requests so the server
  // can authenticate the user via UserContext + RoleEnforcementInterceptor.
  let outgoing = req.url.startsWith('/api/')
    ? req.clone({ withCredentials: true })
    : req;

  // Attach x-api-key only when user-auth is OFF. With user-auth on, the
  // session cookie is the authentication layer — the server's APIKeyValidator
  // bypasses the key check when UserContext is set, so sending a stale or
  // missing key here is unnecessary (and historically caused wedge bugs
  // when localStorage drifted out of sync with Vault). For programmatic
  // clients (CLI, MCP) that don't have a session, the key is the only
  // identity they can present and remains required.
  if (!auth.userAuthEnabled) {
    const apiKey = localStorage.getItem('datris-api-key');
    if (apiKey && outgoing.url.startsWith('/api/')) {
      outgoing = outgoing.clone({ setHeaders: { 'x-api-key': apiKey } });
    }
  }
  return next(outgoing);
};
