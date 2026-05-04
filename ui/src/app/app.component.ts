import { Component, OnInit, HostListener } from '@angular/core';
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
  helpMenuOpen = false;

  constructor(private healthService: HealthService, private http: HttpClient) {}

  toggleHelpMenu(event: MouseEvent): void {
    event.stopPropagation();
    this.helpMenuOpen = !this.helpMenuOpen;
  }

  @HostListener('document:click')
  closeHelpMenu(): void {
    this.helpMenuOpen = false;
  }

  ngOnInit(): void {
    // Accept API key from URL query param (set by dashboard link)
    const urlKey = new URLSearchParams(window.location.search).get('key');
    if (urlKey) {
      localStorage.setItem('datris-api-key', urlKey);
      this.hasApiKey = true;
      // Clean the URL
      window.history.replaceState({}, '', window.location.pathname);
    }

    if (this.hasApiKey) {
      // Validate the stored API key is still valid
      this.http.get<any>('/api/v1/version').subscribe({
        next: () => {
          this.healthService.loadHealth();
          this.loadEnvironment();
        },
        error: () => {
          // Stored key is invalid — clear it and show the prompt
          localStorage.removeItem('datris-api-key');
          this.hasApiKey = false;
          this.requiresApiKey = true;
        }
      });
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
