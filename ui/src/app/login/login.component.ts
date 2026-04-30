import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { AuthService } from '../auth.service';

@Component({
  selector: 'app-login',
  templateUrl: './login.component.html',
  styleUrl: './login.component.css'
})
export class LoginComponent implements OnInit {
  username = '';
  password = '';
  error = '';
  loading = false;
  ready = false;

  constructor(private auth: AuthService, private router: Router, private http: HttpClient) {}

  /** Bounce away from /login when user-auth is disabled — the login form is
   *  meaningless in legacy / api-key mode. We can't trust auth.userAuthEnabled
   *  yet because app.component may not have populated it before this route
   *  activates, so fetch /api/v1/version directly. */
  ngOnInit(): void {
    this.http.get<any>('/api/v1/version').subscribe({
      next: (data) => {
        if (String(data.useUserAuth) !== 'true') {
          this.router.navigate(['/']);
          return;
        }
        this.ready = true;
      },
      error: () => {
        // If the version endpoint fails entirely, fail open so the user can
        // still attempt login.
        this.ready = true;
      }
    });
  }

  submit(): void {
    if (!this.username.trim()) {
      this.error = 'Please enter a username';
      return;
    }
    this.loading = true;
    this.error = '';
    this.auth.login(this.username.trim(), this.password).subscribe({
      next: () => {
        this.loading = false;
        this.router.navigate(['/']);
      },
      error: (err) => {
        this.loading = false;
        this.error = err?.error?.error || 'Login failed';
      }
    });
  }
}
