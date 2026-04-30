import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { AuthService } from '../auth.service';

@Component({
  selector: 'app-getting-started',
  templateUrl: './getting-started.component.html',
  styleUrls: ['./getting-started.component.css']
})
export class GettingStartedComponent implements OnInit {
  isTrial = false;
  useUserAuth = false;

  constructor(private http: HttpClient, private auth: AuthService) { }

  ngOnInit(): void {
    this.http.get<any>('/api/v1/version').subscribe({
      next: (data) => {
        this.isTrial = data.multiTenant === 'true';
        this.useUserAuth = String(data.useUserAuth) === 'true';
      }
    });
  }

  isAdmin(): boolean {
    return this.auth.current()?.role === 'admin';
  }

  /** Mirrors the top-nav Configuration link's gate: visible in legacy no-auth mode,
   *  visible to admins when user-auth is on. */
  get canSeeConfig(): boolean {
    return !this.useUserAuth || this.isAdmin();
  }
}
