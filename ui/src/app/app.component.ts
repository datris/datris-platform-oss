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
  requiresApiKey = true; // assume true until server tells us otherwise
  isTrial = false;
  environment = '';
  version = '';

  constructor(private healthService: HealthService, private http: HttpClient) {}

  ngOnInit(): void {
    if (this.hasApiKey) {
      this.healthService.loadHealth();
      this.loadEnvironment();
    } else {
      // Check if server requires API keys — self-hosted with useApiKeys=false won't
      this.http.get<any>('/api/v1/version').subscribe({
        next: (data) => {
          if (data.multiTenant !== 'true') {
            // Self-hosted mode — skip API key prompt
            this.requiresApiKey = false;
            this.hasApiKey = true;
            this.environment = data.environment || '';
            this.version = data.version || '';
            this.healthService.loadHealth();
          }
        },
        error: () => {
          // Server rejected — needs API key
          this.requiresApiKey = true;
        }
      });
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
        this.isTrial = data.multiTenant === 'true';
        this.environment = data.environment || '';
        this.version = data.version || '';
      }
    });
  }
}
