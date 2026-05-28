import { Injectable } from '@angular/core';
import { Subscription, Subject, Observable } from 'rxjs';
import { ChatMessage, AssistantEvent } from '../assistant.service';
import { OpsAssistantService } from './ops-assistant.service';
import { OpsChatContextService } from './ops-chat-context.service';
import { OpsActionBus } from './ops-action-bus.service';

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

export type OpsSegment = TextSegment | ToolCard | NoticeSegment;

export interface UserTurn {
  role: 'user';
  text: string;
}

export interface AssistantTurn {
  role: 'assistant';
  thinking: string;
  thinkingExpanded: boolean;
  segments: OpsSegment[];
  done: boolean;
  errorMessage: string;
}

export type Turn = UserTurn | AssistantTurn;

/** Singleton state for the Ops chat side panel. Parallel to
 *  AssistantStateService but with its own conversation/state — the build-mode
 *  Assistant tab and the Ops chat must not commingle their transcripts.
 *  Lives for the browser session so navigating between Activity and
 *  Ingestion (or away from /ops and back) preserves the conversation. */
@Injectable({ providedIn: 'root' })
export class OpsAssistantStateService {
  turns: Turn[] = [];
  draft = '';
  streaming = false;

  private activeSub: Subscription | null = null;

  /** Emits when something outside the panel (an Ask-about-this button on
   *  the dashboard) wants the panel to open. The chat panel subscribes
   *  and expands itself on emit. Kept here rather than on a separate bus
   *  because the chat panel already injects this service. */
  private openRequestedSubject = new Subject<void>();
  openRequested$: Observable<void> = this.openRequestedSubject.asObservable();

  constructor(
    private api: OpsAssistantService,
    private context: OpsChatContextService,
    private actionBus: OpsActionBus
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

    // Re-inject the current dashboard snapshot on every turn. The plan's
    // "dumb but safe" cadence — if it turns out to waste tokens we can
    // optimize later, but a stale agent guessing at a stale snapshot is
    // worse than a few extra system tokens per message.
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

  /** Convenience used by Ask-about-this buttons on the dashboard. Sets the
   *  composer draft and signals the panel to expand — the user reviews and
   *  presses send. */
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
          this.maybeNotifyActionBus(card);
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
        // Ops mode doesn't surface the credentials form — the ops agent
        // should not be creating taps from scratch. If the agent does call
        // request_tap_secret_from_user anyway, swallow the form metadata
        // and let the user see the tool card in its raw form.
        break;
    }
  }

  /** Emit a dashboard-side hint when a successful tool call would change
   *  what the dashboard shows. Today: only `run_tap` — tap row should
   *  flip to a spinner so the user can see the chat's action immediately
   *  rather than waiting for the 30s auto-refresh. */
  private maybeNotifyActionBus(card: ToolCard): void {
    if (card.status !== 'ok') return;
    if (card.name === 'run_tap') {
      const tapName = card.input?.name;
      if (typeof tapName === 'string' && tapName.length > 0) {
        this.actionBus.emit({ kind: 'tap-run-started', tapName });
      }
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
