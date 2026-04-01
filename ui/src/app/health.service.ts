import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class HealthService {
  private health: Record<string, { status: string; message?: string }> | null = null;

  constructor(private http: HttpClient) {}

  async loadHealth(): Promise<void> {
    try {
      this.health = await firstValueFrom(
        this.http.get<Record<string, { status: string; message?: string }>>('/api/v1/health/services')
      );
    } catch {
      this.health = null;
    }
  }

  isAvailable(service: string): boolean {
    // If health hasn't loaded yet or failed, show everything (self-hosted fallback)
    if (!this.health) return true;
    const entry = this.health[service];
    if (!entry) return true;
    return entry.status === 'up';
  }
}
