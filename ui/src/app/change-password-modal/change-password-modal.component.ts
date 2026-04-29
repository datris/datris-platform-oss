import { Component, EventEmitter, Input, Output } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../auth.service';

@Component({
  selector: 'app-change-password-modal',
  templateUrl: './change-password-modal.component.html',
  styleUrl: './change-password-modal.component.css'
})
export class ChangePasswordModalComponent {
  /** True for the forced first-login flow — hides the current-password field and disables close. */
  @Input() forced = false;
  @Output() done = new EventEmitter<void>();
  @Output() cancel = new EventEmitter<void>();

  currentPassword = '';
  newPassword = '';
  confirmPassword = '';
  error = '';
  loading = false;
  showCurrent = false;
  showNew = false;
  showConfirm = false;

  constructor(private auth: AuthService, private router: Router) {}

  signOut(): void {
    this.auth.logout().subscribe(() => {
      this.router.navigate(['/login']);
    });
  }

  submit(): void {
    if (this.newPassword.length < 5) {
      this.error = 'Password must be at least 5 characters';
      return;
    }
    if (this.newPassword !== this.confirmPassword) {
      this.error = 'Passwords do not match';
      return;
    }
    this.loading = true;
    this.error = '';
    this.auth.changePassword(this.currentPassword, this.newPassword).subscribe({
      next: () => {
        this.loading = false;
        this.done.emit();
      },
      error: (err) => {
        this.loading = false;
        this.error = err?.error?.error || 'Failed to set password';
      }
    });
  }
}
