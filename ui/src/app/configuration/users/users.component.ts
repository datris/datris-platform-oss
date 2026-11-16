import { Component, OnDestroy, OnInit } from '@angular/core';
import { Subscription, forkJoin, of } from 'rxjs';
import { catchError, filter } from 'rxjs/operators';
import { ConfigAssistantStateService } from '../../config-chat/config-assistant-state.service';
import { AuthService, CatalogGrantLevel, UserListItem } from '../../auth.service';
import { TapService } from '../../tap.service';
import { PipelineService } from '../../pipeline.service';
import { sanitizeCatalogName, tapCatalogName } from '../../shared/sanitize';

/** Editable copy of one user's catalog grants (Scoped access). */
interface GrantDraft {
  grants: { catalog: string; level: CatalogGrantLevel }[];
  newCatalog: string;
  newCatalogName: string;
  newLevel: CatalogGrantLevel;
  error: string;
  saved: boolean;
}

@Component({
    selector: 'app-users',
    templateUrl: './users.component.html',
    styleUrl: './users.component.css',
    standalone: false
})
export class UsersComponent implements OnInit, OnDestroy {
  private chatSub?: Subscription;

  users: UserListItem[] = [];
  loading = false;
  error = '';

  // Add-user form
  showAdd = false;
  newUsername = '';
  newRole: 'admin' | 'editor' | 'viewer' = 'viewer';
  newPassword = '';
  showNewPassword = false;

  // Catalog-scoped access (admin only): catalog names offered by the grants
  // picker, and a per-user editable copy of the stored grants.
  knownCatalogs: string[] = [];
  drafts: Record<string, GrantDraft> = {};
  readonly grantLevels: CatalogGrantLevel[] = ['view', 'edit', 'own'];
  readonly NEW_CATALOG = '__new__';

  constructor(
    public auth: AuthService,
    private chatState: ConfigAssistantStateService,
    private tapService: TapService,
    private pipelineService: PipelineService
  ) {}

  ngOnInit(): void {
    // Reload when the configuration chat changes this sub-tab's setting.
    this.chatSub = this.chatState.changed$
      .pipe(filter(e => e.tab === 'users'))
      .subscribe(() => this.refresh());
    this.refresh();
    if (this.isAdmin()) this.loadCatalogs();
  }

  isAdmin(): boolean {
    return this.auth.isAdmin();
  }

  ngOnDestroy(): void {
    this.chatSub?.unsubscribe();
  }

  refresh(): void {
    this.loading = true;
    this.auth.listUsers().subscribe({
      next: (users) => { this.users = users; this.resetDrafts(); this.loading = false; },
      error: (err) => { this.error = err?.error?.error || 'Failed to load users'; this.loading = false; }
    });
  }

  addUser(): void {
    const name = this.newUsername.trim().toLowerCase();
    if (!name) { this.error = 'Username is required'; return; }
    this.auth.createUser(name, this.newRole, this.newPassword || undefined).subscribe({
      next: () => {
        this.showAdd = false;
        this.newUsername = '';
        this.newPassword = '';
        this.showNewPassword = false;
        this.newRole = 'viewer';
        this.error = '';
        this.refresh();
      },
      error: (err) => { this.error = err?.error?.error || 'Failed to create user'; }
    });
  }

  /** Generate a 16-char password from an unambiguous alphabet (no I/l/1/0/O).
   *  Uses crypto.getRandomValues so the result is cryptographically random.
   *  Auto-reveals so the admin can copy/share before clicking Create — once
   *  the user is created the password is hashed and unrecoverable. */
  generatePassword(): void {
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789-_!#$%&';
    const buf = new Uint32Array(16);
    crypto.getRandomValues(buf);
    let pw = '';
    for (let i = 0; i < buf.length; i++) {
      pw += chars[buf[i] % chars.length];
    }
    this.newPassword = pw;
    this.showNewPassword = true;
  }

  setRole(user: UserListItem, role: string): void {
    if (role === user.role) return;
    this.auth.patchUser(user.username, { role }).subscribe({
      next: () => this.refresh(),
      error: (err) => { this.error = this.formatError(err, 'Failed to change role'); this.refresh(); }
    });
  }

  // ── Catalog-scoped access ─────────────────────────────────────────────

  /** Admin rows (and the built-in admin) can never be restricted. */
  canRestrict(user: UserListItem): boolean {
    return user.role !== 'admin' && user.username !== 'admin';
  }

  /** Catalog names from taps + pipelines (the tap-create source). Placeholder
   *  taps keep empty catalogs listed; the unassigned group is not grantable. */
  private loadCatalogs(): void {
    forkJoin({
      taps: this.tapService.getTaps().pipe(catchError(() => of([] as any[]))),
      pipelines: this.pipelineService.getPipelines().pipe(catchError(() => of([] as any[])))
    }).subscribe(({ taps, pipelines }) => {
      const names = new Set<string>();
      (taps || []).forEach((t: any) => { const c = tapCatalogName(t); if (c) names.add(c); });
      (pipelines || []).forEach((p: any) => { if (p && p.catalog) names.add(p.catalog); });
      names.delete('Uncataloged');
      this.knownCatalogs = Array.from(names).sort((a, b) => a.localeCompare(b));
    });
  }

  private resetDrafts(): void {
    const next: Record<string, GrantDraft> = {};
    for (const u of this.users) {
      const stored = u.catalogGrants || {};
      next[u.username] = {
        grants: Object.keys(stored).map(c => ({ catalog: c, level: stored[c] })),
        newCatalog: '',
        newCatalogName: '',
        newLevel: 'view',
        error: '',
        saved: false
      };
    }
    this.drafts = next;
  }

  draftFor(user: UserListItem): GrantDraft {
    if (!this.drafts[user.username]) {
      this.drafts[user.username] = { grants: [], newCatalog: '', newCatalogName: '', newLevel: 'view', error: '', saved: false };
    }
    return this.drafts[user.username];
  }

  /** Catalogs the picker offers for this user: known names not yet granted. */
  pickableCatalogs(user: UserListItem): string[] {
    const granted = new Set(this.draftFor(user).grants.map(g => g.catalog));
    return this.knownCatalogs.filter(c => !granted.has(c));
  }

  /** Stored grants exist but the flag is off: the server ignores them. */
  hasDormantGrants(user: UserListItem): boolean {
    return !user.restricted && !!user.catalogGrants && Object.keys(user.catalogGrants).length > 0;
  }

  setRestricted(user: UserListItem, restricted: boolean): void {
    if (!this.canRestrict(user) || !!user.restricted === restricted) return;
    const draft = this.draftFor(user);
    this.auth.patchUser(user.username, { restricted }).subscribe({
      next: () => this.refresh(),
      error: (err) => { draft.error = this.formatError(err, 'Failed to change scoped access'); this.refresh(); }
    });
  }

  addGrant(user: UserListItem): void {
    const draft = this.draftFor(user);
    const name = draft.newCatalog === this.NEW_CATALOG
      ? sanitizeCatalogName(draft.newCatalogName || '')
      : draft.newCatalog;
    if (!name) { draft.error = 'Choose a catalog'; return; }
    if (name === 'Uncataloged') { draft.error = 'Uncataloged cannot be granted'; return; }
    const existing = draft.grants.find(g => g.catalog === name);
    if (existing) existing.level = draft.newLevel;
    else draft.grants.push({ catalog: name, level: draft.newLevel });
    draft.newCatalog = '';
    draft.newCatalogName = '';
    draft.newLevel = 'view';
    draft.error = '';
    draft.saved = false;
  }

  removeGrant(user: UserListItem, index: number): void {
    const draft = this.draftFor(user);
    draft.grants.splice(index, 1);
    draft.saved = false;
  }

  /** Save the full catalog -> level map (the server replaces it whole). */
  saveGrants(user: UserListItem): void {
    const draft = this.draftFor(user);
    const catalogGrants: Record<string, CatalogGrantLevel> = {};
    for (const g of draft.grants) catalogGrants[g.catalog] = g.level;
    this.auth.patchUser(user.username, { restricted: !!user.restricted, catalogGrants }).subscribe({
      next: () => { this.refresh(); },
      error: (err) => { draft.error = this.formatError(err, 'Failed to save catalog grants'); }
    });
  }

  resetPassword(user: UserListItem): void {
    if (!confirm(`Reset password for ${user.username}? They'll be prompted to set a new one on next login.`)) return;
    this.auth.patchUser(user.username, { resetPassword: true }).subscribe({
      next: () => this.refresh(),
      error: (err) => { this.error = this.formatError(err, 'Failed to reset password'); }
    });
  }

  deleteUser(user: UserListItem): void {
    if (!confirm(`Delete user ${user.username}? This cannot be undone.`)) return;
    this.auth.deleteUser(user.username).subscribe({
      next: () => this.refresh(),
      error: (err) => { this.error = this.formatError(err, 'Failed to delete user'); }
    });
  }

  /** Pull a server-supplied error string out of a HttpErrorResponse, falling back to the
    * status text + code so the user sees something specific (e.g. 405 vs 401 vs 409). */
  private formatError(err: any, fallback: string): string {
    if (err?.error?.error) return err.error.error;
    if (typeof err?.error === 'string' && err.error) return err.error;
    if (err?.status) return `${fallback} (HTTP ${err.status}${err.statusText ? ': ' + err.statusText : ''})`;
    return fallback;
  }
}
