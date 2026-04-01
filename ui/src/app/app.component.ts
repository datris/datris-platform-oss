import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { HealthService } from './health.service';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrl: './app.component.css'
})
export class AppComponent implements OnInit {
  title = 'pipeline-ui';
  hasApiKey = !!localStorage.getItem('datris-api-key');
  isTrial = false;

  constructor(private healthService: HealthService, private http: HttpClient) {}

  ngOnInit(): void {
    if (this.hasApiKey) {
      this.healthService.loadHealth();
      this.loadEnvironment();
    }
  }

  onApiKeySet(): void {
    this.hasApiKey = true;
    this.healthService.loadHealth();
    this.loadEnvironment();
  }

  private loadEnvironment(): void {
    this.http.get<any>('/api/v1/version').subscribe({
      next: (data) => {
        this.isTrial = (data.environment || '').startsWith('trial-');
      }
    });
  }
}
