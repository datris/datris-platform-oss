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
        this.newRole = 'viewer';
        this.error = '';
        this.refresh();
      },
      error: (err) => { this.error = err?.error?.error || 'Failed to create user'; }
    });
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
