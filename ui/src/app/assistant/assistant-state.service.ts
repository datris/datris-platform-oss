import { Injectable } from '@angular/core';
import { Subscription } from 'rxjs';
import { AssistantService, AssistantEvent, AssistantInit, ChatMessage } from '../assistant.service';
import { loadChatState, saveChatState, clearChatState } from '../shared/chat-persistence';

/** Tool invocation inside an assistant turn. */
export interface ToolCard {
  kind: 'tool';
  id: string;
  name: string;
  input: any;
  result: string;
  isError: boolean;
  status: 'running' | 'ok' | 'error';
  expanded: boolean;
  /** Populated when the agent called `request_tap_secret_from_user`. The UI
   *  renders an inline credentials form using these fields. */
  secretRequest?: {
    secretName: string;
    fieldNames: string[];
    reason: string;
    // Form state. Form starts in "new" mode by default; user can flip to
    // "existing" via the dropdown.
    mode: 'new' | 'existing';
    selectedExisting: string;
    fieldValues: Record<string, string>;
    submitting: boolean;
    submitted: boolean;
    cancelled: boolean;
    errorMessage: string;
  };
}

export interface TextSegment {
  kind: 'text';
  text: string;
}

/** Transient system message rendered as a small inline pill — not part of
 *  the assistant's textual response. Emitted by the server when something
 *  about the system behavior changes mid-stream (e.g., model fallback). */
export interface NoticeSegment {
  kind: 'notice';
  message: string;
}

export type AssistantSegment = TextSegment | ToolCard | NoticeSegment;

export interface UserTurn {
  role: 'user';
  text: string;
}

export interface AssistantTurn {
  role: 'assistant';
  thinking: string;
  thinkingExpanded: boolean;
  segments: AssistantSegment[];
  done: boolean;
  errorMessage: string;
}

export type Turn = UserTurn | AssistantTurn;

/** Singleton state for the Assistant tab. Lives for the lifetime of the
 *  browser session — survives route changes so navigating away from the
 *  Assistant tab does not destroy the conversation or interrupt an in-flight
 *  stream. The component is a thin view layer; everything stateful lives here.
 *
 *  We deliberately do NOT use BehaviorSubjects / observables for the state
 *  fields. Angular change detection re-reads the fields on each tick, so plain
 *  properties work fine and the template stays readable. */
@Injectable({ providedIn: 'root' })
export class AssistantStateService {
  // /init payload — fetched once per browser session, then cached.
  loadingInit = false;
  initError = '';
  initInfo: AssistantInit | null = null;
  toolNamesSet = new Set<string>();
  private initLoaded = false;

  // Conversation state.
  turns: Turn[] = [];
  draft = '';
  streaming = false;

  // Active SSE subscription. Held by the service so route changes don't
  // unsubscribe and cancel the server-side agent loop.
  private activeSub: Subscription | null = null;

  /** sessionStorage key for this tab's transcript (see chat-persistence). */
  private static readonly STORAGE_KEY = 'assistant.chat.transcript';

  constructor(private api: AssistantService) {
    const restored = loadChatState(AssistantStateService.STORAGE_KEY);
    if (restored) {
      this.turns = restored.turns;
      this.draft = restored.draft;
    }
    // Catch a mid-stream refresh: snapshot whatever's on screen so it survives
    // the reload. Terminal transitions persist explicitly too.
    window.addEventListener('beforeunload', () => this.persist());
  }

  private persist(): void {
    saveChatState(AssistantStateService.STORAGE_KEY, this.turns, this.draft);
  }

  /** Idempotent: fetches /init the first time, no-ops thereafter. Component
   *  calls this on mount; subsequent mounts see the cached payload. */
  ensureInit(): void {
    if (this.initLoaded || this.loadingInit) return;
    this.loadingInit = true;
    this.initError = '';
    this.api.init().subscribe({
      next: (info) => {
        this.initInfo = info;
        this.toolNamesSet = new Set(info.toolNames || []);
        this.loadingInit = false;
        this.initLoaded = true;
      },
      error: (err) => {
        this.initError = 'Could not initialize assistant: ' + (err?.error?.error || err?.message || 'unknown');
        this.loadingInit = false;
      }
    });
  }

  /** Send a new user message. Pushes a user turn + an empty assistant turn,
   *  opens the SSE stream, and dispatches events to mutate the assistant turn
   *  as they arrive. The subscription is held by the service so navigating
   *  away from the tab does NOT cancel it. */
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

    // Build outbound history. Skip the in-progress assistant turn; flatten
    // each prior assistant turn's text segments into one string. Tool calls
    // don't go in the conversation log on the client side — the agent loop
    // preserves them server-side for continuity within a single run.
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

    this.activeSub = this.api.chat(messages).subscribe({
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

  /** Cancel the in-flight stream. The component's Stop button calls this. */
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

  /** Clear the conversation. Used by an explicit "New chat" affordance if we
   *  add one later — not wired into the UI today. */
  newChat(): void {
    this.stop();
    this.turns = [];
    this.draft = '';
    clearChatState(AssistantStateService.STORAGE_KEY);
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
      case 'secret_request': {
        // No corresponding tool_use card may exist yet — synthesize one if
        // necessary. Either way, attach the form metadata so the template
        // renders the inline credentials form on this card.
        let card = this.findToolCard(turn, evt.id);
        if (!card) {
          card = {
            kind: 'tool',
            id: evt.id,
            name: 'request_tap_secret_from_user',
            input: { name: evt.secretName, fields: evt.fieldNames, reason: evt.reason },
            result: '',
            isError: false,
            status: 'ok',
            expanded: false
          };
          turn.segments.push(card);
        }
        card.status = 'ok';
        const initialFieldValues: Record<string, string> = {};
        for (const f of evt.fieldNames) initialFieldValues[f] = '';
        card.secretRequest = {
          secretName: evt.secretName,
          fieldNames: evt.fieldNames,
          reason: evt.reason,
          mode: 'new',
          selectedExisting: '',
          fieldValues: initialFieldValues,
          submitting: false,
          submitted: false,
          cancelled: false,
          errorMessage: ''
        };
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
