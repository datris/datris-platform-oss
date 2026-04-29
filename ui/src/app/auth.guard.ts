import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/** Bounces unauthenticated requests to /login when user-auth is enabled.
  * When user-auth is disabled, lets everything through (legacy x-api-key flow). */
export const authGuard: CanActivateFn = (route) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (!auth.userAuthEnabled) return true;
  if (!auth.current()) {
    router.navigate(['/login']);
    return false;
  }

  // Role-based route gating: hide Configuration / Secrets from non-admins.
  const path = route.routeConfig?.path;
  const adminOnly = path === 'configuration' || path === 'secrets';
  if (adminOnly && !auth.isAdmin()) {
    router.navigate(['/']);
    return false;
  }
  return true;
};
