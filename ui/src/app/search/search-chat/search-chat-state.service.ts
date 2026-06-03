import { Injectable } from '@angular/core';
import { Subscription } from 'rxjs';
import { ChatMessage, AssistantEvent } from '../../assistant.service';
import { SearchChatService } from './search-chat.service';
import { SearchChatContextService } from './search-chat-context.service';
import { loadChatState, saveChatState, clearChatState } from '../../shared/chat-persistence';

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

export type SearchSegment = TextSegment | ToolCard | NoticeSegment;

export interface UserTurn {
  role: 'user';
  text: string;
}

export interface AssistantTurn {
  role: 'assistant';
  thinking: string;
  thinkingExpanded: boolean;
  segments: SearchSegment[];
  done: boolean;
  errorMessage: string;
}

export type Turn = UserTurn | AssistantTurn;

/** Singleton state for the conversational Search chat. Parallel to
 *  OpsAssistantStateService but with its own conversation — the Search chat,
 *  the Ops chat, and the build-mode Assistant must not commingle transcripts.
 *  Lives for the browser session so navigating away from the Search tab and
 *  back preserves the conversation, draft, and catalog scope. */
@Injectable({ providedIn: 'root' })
export class SearchChatStateService {
  turns: Turn[] = [];
  draft = '';
  streaming = false;

  private activeSub: Subscription | null = null;

  /** sessionStorage key for this tab's transcript (see chat-persistence). */
  private static readonly STORAGE_KEY = 'search.chat.transcript';

  constructor(
    private api: SearchChatService,
    private context: SearchChatContextService
  ) {
    const restored = loadChatState(SearchChatStateService.STORAGE_KEY);
    if (restored) {
      this.turns = restored.turns;
      this.draft = restored.draft;
    }
    // Catch a mid-stream refresh: beforeunload snapshots whatever's on screen
    // (partial text, draft) so it survives the reload. Terminal transitions
    // also persist explicitly below, so this is the belt-and-suspenders save.
    window.addEventListener('beforeunload', () => this.persist());
  }

  private persist(): void {
    saveChatState(SearchChatStateService.STORAGE_KEY, this.turns, this.draft);
  }

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
    this.persist();

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

    // Forward the catalog scope only when it actually narrows things — 'All'
    // means no scoping, so we send null and the server runs unscoped.
    const snap = this.context.snapshot();
    const ctx = snap.catalog && snap.catalog !== 'All' ? snap : null;

    this.activeSub = this.api.chat(messages, ctx).subscribe({
      next: (evt) => this.handleEvent(evt, assistantTurn),
      error: (err) => {
        assistantTurn.errorMessage = err?.message || 'Connection error';
        assistantTurn.done = true;
        this.streaming = false;
        this.persist();
      },
      complete: () => {
        assistantTurn.done = true;
        this.streaming = false;
        this.activeSub = null;
        this.persist();
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
    this.persist();
  }

  newChat(): void {
    this.stop();
    this.turns = [];
    this.draft = '';
    clearChatState(SearchChatStateService.STORAGE_KEY);
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
        // The read-only search agent has no credential-collecting tools, so a
        // secret_request shouldn't occur. If one slips through, ignore the
        // form metadata — the raw tool card still renders.
        break;
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
