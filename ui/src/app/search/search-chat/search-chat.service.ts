import { Injectable } from '@angular/core';
import { Observable, Observer } from 'rxjs';
import { Router } from '@angular/router';
import { AuthService } from '../../auth.service';
import { AssistantEvent, ChatMessage } from '../../assistant.service';
import { SearchChatContext } from './search-chat-context.service';

/** Transport for the conversational Search chat. Parallel to AssistantService
 *  and OpsAssistantService but POSTs to `/api/v1/search-chat/chat` and carries
 *  an optional catalog `context` scope. The endpoint shares the SSE event shape
 *  with the other chat modes — same parser, same AssistantEvent union — so the
 *  panel renders tool cards with the same logic. */
@Injectable({ providedIn: 'root' })
export class SearchChatService {
  constructor(private auth: AuthService, private router: Router) {}

  chat(messages: ChatMessage[], context: SearchChatContext | null): Observable<AssistantEvent> {
    return new Observable<AssistantEvent>((observer: Observer<AssistantEvent>) => {
      const controller = new AbortController();
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream'
      };
      // Mirror the apiKeyInterceptor: attach x-api-key only when user-auth is
      // off. The native fetch() used for SSE bypasses the Angular interceptor.
      if (!this.auth.userAuthEnabled) {
        const apiKey = localStorage.getItem('datris-api-key');
        if (apiKey) headers['x-api-key'] = apiKey;
      }

      const body: any = { messages };
      if (context) body.context = context;

      fetch('/api/v1/search-chat/chat', {
        method: 'POST',
        credentials: 'include',
        headers,
        body: JSON.stringify(body),
        signal: controller.signal
      }).then(async (response) => {
        if (!response.ok) {
          // A 401 means the session timed out mid-chat. This fetch() bypasses
          // the Angular interceptors, so mirror authErrorInterceptor.
          if (response.status === 401 && this.auth.userAuthEnabled) {
            this.auth.clearUser();
            this.router.navigate(['/login']);
          }
          const text = await response.text().catch(() => '');
          observer.next({ type: 'error', message: 'HTTP ' + response.status + ': ' + text.substring(0, 400) });
          observer.complete();
          return;
        }
        if (!response.body) {
          observer.next({ type: 'error', message: 'No response body for streaming chat' });
          observer.complete();
          return;
        }
        await this.readSseStream(response.body, observer);
      }).catch((err) => {
        if (err && err.name === 'AbortError') return;
        observer.next({ type: 'error', message: (err && err.message) || 'Network error' });
        observer.complete();
      });

      return () => {
        try { controller.abort(); } catch { /* ignore */ }
      };
    });
  }

  private async readSseStream(body: ReadableStream<Uint8Array>, observer: Observer<AssistantEvent>): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let sep = buffer.indexOf('\n\n');
        while (sep !== -1) {
          const frame = buffer.substring(0, sep);
          buffer = buffer.substring(sep + 2);
          this.dispatchFrame(frame, observer);
          sep = buffer.indexOf('\n\n');
        }
      }
      if (buffer.trim().length > 0) this.dispatchFrame(buffer, observer);
    } finally {
      observer.complete();
    }
  }

  private dispatchFrame(frame: string, observer: Observer<AssistantEvent>): void {
    const lines = frame.split('\n');
    for (const line of lines) {
      if (line.startsWith('data:')) {
        const payload = line.substring(5).trim();
        if (!payload || payload === '[DONE]') continue;
        try {
          const evt = JSON.parse(payload) as AssistantEvent;
          observer.next(evt);
        } catch {
          // Malformed event line — skip.
        }
      }
    }
  }
}
