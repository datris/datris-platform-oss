import { Injectable } from '@angular/core';
import { Subscription, Subject, Observable } from 'rxjs';
import { ChatMessage, AssistantEvent } from '../assistant.service';
import { CatalogAssistantService } from './catalog-assistant.service';
import { CatalogChatContextService } from './catalog-chat-context.service';

export interface ToolCard {
  kind: 'tool';
  id: string;
  name: string;
  input: any;
  result: string;
  isError: boolean;
  status: 'running' | 'ok' | 'error';
  expanded: boolean;
}

export interface TextSegment {
  kind: 'text';
  text: string;
}

export interface NoticeSegment {
  kind: 'notice';
  message: string;
}

export type CatalogSegment = TextSegment | ToolCard | NoticeSegment;

export interface UserTurn {
  role: 'user';
  text: string;
}

export interface AssistantTurn {
  role: 'assistant';
  thinking: string;
  thinkingExpanded: boolean;
  segments: CatalogSegment[];
  done: boolean;
  errorMessage: string;
}

export type Turn = UserTurn | AssistantTurn;

/** Singleton state for the Catalog curation chat side panel. Parallel to
 *  OpsAssistantStateService but with its own conversation — the build-mode
 *  Assistant, the Ops chat, the Search chat, and this must not commingle
 *  transcripts. Lives for the browser session so leaving /catalog and
 *  returning preserves the conversation. */
@Injectable({ providedIn: 'root' })
export class CatalogAssistantStateService {
  turns: Turn[] = [];
  draft = '';
  streaming = false;

  private activeSub: Subscription | null = null;

  /** Emits when something outside the panel (a "Describe to Assistant" button
   *  on a catalog card) wants the panel to open. The chat panel subscribes and
   *  expands itself on emit. */
  private openRequestedSubject = new Subject<void>();
  openRequested$: Observable<void> = this.openRequestedSubject.asObservable();

  /** Emits after a successful `set_catalog` tool call so the Catalog page can
   *  reload its tree immediately rather than waiting for the 10s auto-refresh.
   *  Mirrors the OpsActionBus optimistic-refresh pattern. */
  private changedSubject = new Subject<void>();
  changed$: Observable<void> = this.changedSubject.asObservable();

  constructor(
    private api: CatalogAssistantService,
    private context: CatalogChatContextService
  ) {}

  send(text: string): void {
    const trimmed = text.trim();
    if (!trimmed || this.streaming) return;

    this.turns.push({ role: 'user', text: trimmed });
    const assistantTurn: AssistantTurn = {
      role: 'assistant',
      thinking: '',
      thinkingExpanded: false,
      segments: [],
      done: false,
      errorMessage: ''
    };
    this.turns.push(assistantTurn);
    this.draft = '';
    this.streaming = true;

    const messages: ChatMessage[] = [];
    for (const t of this.turns) {
      if (t.role === 'user') {
        messages.push({ role: 'user', content: t.text });
      } else if (t === assistantTurn) {
        continue;
      } else {
        const visible = t.segments
          .filter(s => s.kind === 'text')
          .map(s => (s as TextSegment).text)
          .join('');
        if (visible.trim().length > 0) {
          messages.push({ role: 'assistant', content: visible });
        }
      }
    }

    // Re-inject the current catalog snapshot on every turn — cheapest possible
    // cadence; the inventory the operator is staring at is small.
    const ctx = this.context.snapshot();

    this.activeSub = this.api.chat(messages, ctx).subscribe({
      next: (evt) => this.handleEvent(evt, assistantTurn),
      error: (err) => {
        assistantTurn.errorMessage = err?.message || 'Connection error';
        assistantTurn.done = true;
        this.streaming = false;
      },
      complete: () => {
        assistantTurn.done = true;
        this.streaming = false;
        this.activeSub = null;
      }
    });
  }

  stop(): void {
    if (!this.streaming) return;
    this.activeSub?.unsubscribe();
    this.activeSub = null;
    this.streaming = false;
    const last = this.turns[this.turns.length - 1];
    if (last && last.role === 'assistant') {
      last.done = true;
      if (!last.errorMessage && last.segments.length === 0 && !last.thinking) {
        last.errorMessage = 'Stopped.';
      }
    }
  }

  newChat(): void {
    this.stop();
    this.turns = [];
    this.draft = '';
  }

  /** Convenience used by the "Describe to Assistant" buttons on a catalog
   *  card. Sets the composer draft and signals the panel to expand — the user
   *  reviews and presses send. */
  seedDraft(text: string): void {
    this.draft = text;
    this.openRequestedSubject.next();
  }

  private handleEvent(evt: AssistantEvent, turn: AssistantTurn): void {
    switch (evt.type) {
      case 'iteration_start':
        break;
      case 'thinking_delta':
        turn.thinking += evt.text;
        break;
      case 'text_delta': {
        const tail = turn.segments[turn.segments.length - 1];
        if (tail && tail.kind === 'text') {
          tail.text += evt.text;
        } else {
          turn.segments.push({ kind: 'text', text: evt.text });
        }
        break;
      }
      case 'tool_use_start':
        turn.segments.push({
          kind: 'tool',
          id: evt.id,
          name: evt.name,
          input: null,
          result: '',
          isError: false,
          status: 'running',
          expanded: false
        });
        break;
      case 'tool_use': {
        const card = this.findToolCard(turn, evt.id);
        if (card) card.input = evt.input;
        break;
      }
      case 'tool_result': {
        const card = this.findToolCard(turn, evt.id);
        if (card) {
          card.result = evt.result;
          card.isError = evt.isError;
          card.status = evt.isError ? 'error' : 'ok';
          this.maybeNotifyChanged(card);
        }
        break;
      }
      case 'notice':
        turn.segments.push({ kind: 'notice', message: evt.message });
        break;
      case 'done':
        turn.done = true;
        break;
      case 'error':
        turn.errorMessage = evt.message;
        turn.done = true;
        break;
      case 'secret_request':
        // Curation mode never surfaces the credentials form — the catalog
        // agent organizes existing items, it doesn't create taps from scratch.
        break;
    }
  }

  /** Emit a refresh hint when a successful tool call changed catalog
   *  membership, so the page reloads its tree without waiting for the 10s
   *  poll. Today: only `set_catalog` moves items between catalogs. */
  private maybeNotifyChanged(card: ToolCard): void {
    if (card.status !== 'ok') return;
    if (card.name === 'set_catalog') {
      this.changedSubject.next();
    }
  }

  private findToolCard(turn: AssistantTurn, id: string): ToolCard | undefined {
    for (let i = turn.segments.length - 1; i >= 0; i--) {
      const s = turn.segments[i];
      if (s.kind === 'tool' && s.id === id) return s as ToolCard;
    }
    return undefined;
  }
}
