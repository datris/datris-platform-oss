import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class HealthService {
  private health: Record<string, { status: string; message?: string }> | null = null;
  private lastLoadedAt = 0;
  private inFlight: Promise<void> | null = null;

  constructor(private http: HttpClient) {}

  /** Fetch the health snapshot, retrying a couple of times so a request fired
   * while the server is still booting doesn't leave the UI permanently in the
   * fail-open "show everything" state. Concurrent callers share one fetch. */
  async loadHealth(): Promise<void> {
    if (this.inFlight) return this.inFlight;
    this.inFlight = this.fetchWithRetry();
    try {
      await this.inFlight;
    } finally {
      this.inFlight = null;
    }
  }

  /** Re-fetch unless a snapshot newer than maxAgeMs exists. Components call
   * this when a health-gated view opens, so gating recovers after a failed
   * bootstrap fetch or a server restart without a full page reload. */
  async refresh(maxAgeMs = 30_000): Promise<void> {
    if (this.health && Date.now() - this.lastLoadedAt < maxAgeMs) return;
    return this.loadHealth();
  }

  private async fetchWithRetry(): Promise<void> {
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        this.health = await firstValueFrom(
          this.http.get<Record<string, { status: string; message?: string }>>('/api/v1/health/services')
        );
        this.lastLoadedAt = Date.now();
        return;
      } catch {
        // Keep any previous snapshot; wait briefly before retrying (2s, 4s).
        await new Promise(resolve => setTimeout(resolve, 2000 * (attempt + 1)));
      }
    }
  }

  isAvailable(service: string): boolean {
    // If health hasn't loaded yet or failed, show everything (self-hosted fallback
    // for servers that predate the health endpoint).
    if (!this.health) return true;
    const entry = this.health[service];
    if (!entry) return true;
    return entry.status === 'up';
  }
}
