import { Component, OnInit } from '@angular/core';
import { AuthService, UserListItem } from '../../auth.service';

@Component({
  selector: 'app-users',
  templateUrl: './users.component.html',
  styleUrl: './users.component.css'
})
export class UsersComponent implements OnInit {
  users: UserListItem[] = [];
  loading = false;
  error = '';

  // Add-user form
  showAdd = false;
  newUsername = '';
  newRole: 'admin' | 'editor' | 'viewer' = 'viewer';
  newPassword = '';
  showNewPassword = false;

  constructor(private auth: AuthService) {}

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading = true;
    this.auth.listUsers().subscribe({
      next: (users) => { this.users = users; this.loading = false; },
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
