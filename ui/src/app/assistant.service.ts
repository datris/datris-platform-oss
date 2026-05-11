import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, Observer } from 'rxjs';
import { AuthService } from './auth.service';

/** Init payload returned by GET /api/v1/assistant/init. The UI uses this to
 *  render the empty-state hint, drive friendly-label mapping for tool cards,
 *  and warn when the configured provider is Ollama (no reasoning visibility). */
export interface AssistantInit {
  toolCount: number;
  toolNames: string[];
  workflowReference: string;
  provider: string;
  model: string;
  extendedThinking: boolean;
}

/** A chat-role message in the conversation history sent to the agent. */
export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

/** SSE event types streamed back from POST /api/v1/assistant/chat. The agent
 *  loop emits these in order; the component renders each one into the
 *  document-style chat layout. */
export type AssistantEvent =
  | { type: 'iteration_start' }
  | { type: 'thinking_delta'; text: string }
  | { type: 'text_delta'; text: string }
  | { type: 'tool_use_start'; id: string; name: string }
  | { type: 'tool_use'; id: string; name: string; input: any }
  | { type: 'tool_result'; id: string; name: string; result: string; isError: boolean }
  /** Synthetic event: agent called `request_tap_secret_from_user`. The UI renders
   *  an inline credentials form on the matching tool card. Values never enter
   *  the chat — they go straight to /api/v1/secrets on submit. */
  | { type: 'secret_request'; id: string; secretName: string; fieldNames: string[]; reason: string }
  | { type: 'done' }
  | { type: 'error'; message: string };

@Injectable({ providedIn: 'root' })
export class AssistantService {
  constructor(private http: HttpClient, private auth: AuthService) { }

  /** Warm the MCP client caches server-side and grab the tool catalog. */
  init(): Observable<AssistantInit> {
    return this.http.get<AssistantInit>('/api/v1/assistant/init');
  }

  /** Open a streaming chat connection. Uses `fetch` with a ReadableStream reader
   *  rather than the native EventSource because EventSource does not support
   *  POST bodies — the conversation history goes in the request body.
   *
   *  Returns an Observable<AssistantEvent>; unsubscribing aborts the fetch and
   *  cancels the server-side agent loop (the AssistantAPIController's
   *  emitter.onCompletion / onError flips the cancellation flag).
   */
  chat(messages: ChatMessage[]): Observable<AssistantEvent> {
    return new Observable<AssistantEvent>((observer: Observer<AssistantEvent>) => {
      const controller = new AbortController();
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream'
      };
      // Match the api-key interceptor's behavior: only attach x-api-key when
      // user-auth is OFF. With user-auth on, the session cookie does the work.
      if (!this.auth.userAuthEnabled) {
        const apiKey = localStorage.getItem('datris-api-key');
        if (apiKey) headers['x-api-key'] = apiKey;
      }

      fetch('/api/v1/assistant/chat', {
        method: 'POST',
        credentials: 'include',
        headers,
        body: JSON.stringify({ messages }),
        signal: controller.signal
      }).then(async (response) => {
        if (!response.ok) {
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
        if (err && err.name === 'AbortError') {
          // Clean unsubscribe — no event needed.
          return;
        }
        observer.next({ type: 'error', message: (err && err.message) || 'Network error' });
        observer.complete();
      });

      return () => {
        try { controller.abort(); } catch { /* ignore */ }
      };
    });
  }

  /** Parse a Server-Sent Events stream line-by-line and forward parsed events to
   *  the observer. SSE events are framed as:
   *
   *      event: text_delta\n
   *      data: {"type":"text_delta","text":"..."}\n
   *      \n
   *
   *  We mostly only need the `data:` payload — its `type` field matches the
   *  event name and tells us what kind of AssistantEvent it is.
   */
  private async readSseStream(body: ReadableStream<Uint8Array>, observer: Observer<AssistantEvent>): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        // SSE frames are separated by blank lines (\n\n). Process all complete
        // frames currently in the buffer; leave any partial frame for the next chunk.
        let sep = buffer.indexOf('\n\n');
        while (sep !== -1) {
          const frame = buffer.substring(0, sep);
          buffer = buffer.substring(sep + 2);
          this.dispatchFrame(frame, observer);
          sep = buffer.indexOf('\n\n');
        }
      }
      // Flush any trailing partial frame just in case (typically empty).
      if (buffer.trim().length > 0) this.dispatchFrame(buffer, observer);
    } finally {
      observer.complete();
    }
  }

  private dispatchFrame(frame: string, observer: Observer<AssistantEvent>): void {
    // A frame is one or more `field: value` lines. We only care about `data:`.
    const lines = frame.split('\n');
    for (const line of lines) {
      if (line.startsWith('data:')) {
        const payload = line.substring(5).trim();
        if (!payload || payload === '[DONE]') continue;
        try {
          const evt = JSON.parse(payload) as AssistantEvent;
          observer.next(evt);
        } catch {
          // Malformed event line — skip. Could happen if the server flushes mid-write.
        }
      }
    }
  }
}
