import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, timeout } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class DiscoveryService {
  constructor(private http: HttpClient) { }

  chat(messages: Array<{role: string, content: string}>): Observable<any> {
    return this.http.post<any>('/api/v1/discover', { messages, mode: 'chat' }).pipe(
      timeout(120000)  // 2 minute timeout for chat
    );
  }

  discover(messages: Array<{role: string, content: string}>, authContext?: string): Observable<any> {
    return this.http.post<any>('/api/v1/discover', { messages, mode: 'discover', authContext: authContext || '' }).pipe(
      timeout(300000)  // 5 minute timeout for LLM discovery
    );
  }

  build(prefix: string, datasets: any[]): Observable<any> {
    return this.http.post<any>('/api/v1/discover/build', { prefix, datasets }).pipe(
      timeout(600000)  // 10 minute timeout for bulk build
    );
  }
}
