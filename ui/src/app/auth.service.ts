import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, of, tap } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

export interface CurrentUser {
  username: string;
  role: 'admin' | 'editor' | 'viewer';
  mustSetPassword: boolean;
}

export interface UserListItem {
  username: string;
  role: 'admin' | 'editor' | 'viewer';
  createdAt: string;
  lastLoginAt: string | null;
  mustSetPassword: boolean;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private currentUser$ = new BehaviorSubject<CurrentUser | null>(null);
  private bootstrapped = false;
  /** True when the server has user-auth enabled. Driven by /api/v1/version. */
  userAuthEnabled = false;

  constructor(private http: HttpClient) {}

  user(): Observable<CurrentUser | null> {
    return this.currentUser$.asObservable();
  }

  current(): CurrentUser | null {
    return this.currentUser$.value;
  }

  /** Drop the cached user — used when the server reports the session is gone
   *  (e.g. a 401 caught by authErrorInterceptor after a timeout). */
  clearUser(): void {
    this.currentUser$.next(null);
  }

  /** Probe /me. 200 → set user; 401 → null user. */
  refreshMe(): Observable<CurrentUser | null> {
    return this.http.get<CurrentUser>('/api/v1/auth/me', { withCredentials: true }).pipe(
      tap(u => this.currentUser$.next(u)),
      map(u => u),
      catchError(() => {
        this.currentUser$.next(null);
        return of(null);
      })
    );
  }

  login(username: string, password: string): Observable<CurrentUser> {
    return this.http.post<CurrentUser>('/api/v1/auth/login',
      { username, password },
      { withCredentials: true }
    ).pipe(tap(u => this.currentUser$.next(u)));
  }

  logout(): Observable<void> {
    return this.http.post<void>('/api/v1/auth/logout', {}, { withCredentials: true }).pipe(
      tap(() => this.currentUser$.next(null))
    );
  }

  changePassword(currentPassword: string, newPassword: string): Observable<void> {
    return this.http.post<void>('/api/v1/auth/change-password',
      { currentPassword, newPassword },
      { withCredentials: true }
    ).pipe(tap(() => {
      const u = this.currentUser$.value;
      if (u) this.currentUser$.next({ ...u, mustSetPassword: false });
    }));
  }

  listUsers(): Observable<UserListItem[]> {
    return this.http.get<UserListItem[]>('/api/v1/auth/users', { withCredentials: true });
  }

  createUser(username: string, role: string, password?: string): Observable<void> {
    return this.http.post<void>('/api/v1/auth/users',
      { username, role, password: password || '' },
      { withCredentials: true });
  }

  patchUser(username: string, body: { role?: string; resetPassword?: boolean }): Observable<void> {
    return this.http.patch<void>('/api/v1/auth/users/' + encodeURIComponent(username),
      body, { withCredentials: true });
  }

  deleteUser(username: string): Observable<void> {
    return this.http.delete<void>('/api/v1/auth/users/' + encodeURIComponent(username),
      { withCredentials: true });
  }

  isAdmin(): boolean {
    return this.current()?.role === 'admin';
  }

  /** Editor + admin can create/update/delete. Viewer cannot. */
  canWrite(): boolean {
    const role = this.current()?.role;
    if (this.userAuthEnabled) return role === 'admin' || role === 'editor';
    // Legacy x-api-key mode has no role concept — any authenticated user can write.
    return true;
  }

  isViewer(): boolean {
    return this.userAuthEnabled && this.current()?.role === 'viewer';
  }

  hasBootstrapped(): boolean {
    return this.bootstrapped;
  }

  markBootstrapped(): void {
    this.bootstrapped = true;
  }
}
