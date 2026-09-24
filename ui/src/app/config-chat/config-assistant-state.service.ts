import { Injectable } from '@angular/core';
import { Subscription, Subject, Observable } from 'rxjs';
import { ChatMessage, AssistantEvent } from '../assistant.service';
import { ConfigAssistantService } from './config-assistant.service';
import { ConfigChatContextService } from './config-chat-context.service';

/** A mutating tool the server wants the user to approve. The card shows the
 *  summary with Confirm / Cancel; `decide()` settles it exactly once. */
export interface ConfirmRequest {
  tool: string;
  summary: string;
  token: string;
  decided: boolean;
  decision?: 'confirmed' | 'cancelled';
}

/** A value the server returns once (new user's temporary password, new API
 *  key). Shown on the card with a Copy button; never stored in the model's
 *  transcript (the server redacts its own copy). */
export interface ShowOnceValue {
  label: string;
  value: string;
}

/** Inline secret form the server asked for instead of taking values in chat.
 *  Same shape as the build-mode assistant's form minus the new/existing
 *  selector. */
export interface SecretRequest {
  secretName: string;
  fieldNames: string[];
  reason: string;
  fieldValues: Record<string, string>;
  submitting: boolean;
  submitted: boolean;
  cancelled: boolean;
  errorMessage: string;
}

export interface ToolCard {
  kind: 'tool';
  id: string;
  name: string;
  input: any;
  result: string;
  isError: boolean;
  status: 'running' | 'ok' | 'error';
  expanded: boolean;
  confirm?: ConfirmRequest;
  secretRequest?: SecretRequest;
}

export interface TextSegment {
  kind: 'text';
  text: string;
}

export interface NoticeSegment {
  kind: 'notice';
  message: string;
}

export type ConfigSegment = TextSegment | ToolCard | NoticeSegment;

export interface UserTurn {
  role: 'user';
  text: string;
}

export interface AssistantTurn {
  role: 'assistant';
  thinking: string;
  thinkingExpanded: boolean;
  segments: ConfigSegment[];
  done: boolean;
  errorMessage: string;
}

export type Turn = UserTurn | AssistantTurn;

/** Which Configuration sub-tab a completed mutating tool changed. Read tools
 *  are absent on purpose: they never trigger a reload. Exported for the cards
 *  story (Confirm re-posts, "Show me" links). */
export const TOOL_TAB: Readonly<Record<string, string>> = {
  set_ai_provider_slot: 'ai-providers',
  set_provider_credentials: 'ai-providers',
  put_secret: 'secrets',
  delete_secret: 'secrets',
  set_data_sources_fragment: 'data-sources',
  set_code_repo: 'code-repo',
  create_repo_token: 'code-repo',
  delete_repo_token: 'code-repo',
  create_user: 'users',
  set_user_role: 'users',
  reset_user_password: 'users',
  delete_user: 'users',
  issue_api_key: 'keys',
  rotate_api_key: 'keys',
  revoke_api_key: 'keys',
  set_agent_policy: 'agent-policy',
  use_recommended_policy: 'agent-policy'
};

/** Display names for the Configuration sub-tabs, used by "Show me" links. */
export const TAB_LABEL: Readonly<Record<string, string>> = {
  'ai-providers': 'AI Providers',
  'users': 'Users',
  'secrets': 'Secrets',
  'keys': 'API-Keys',
  'data-sources': 'Data Sources',
  'code-repo': 'Code Repository',
  'audit-log': 'Audit Log',
  'agent-policy': 'Agent Policy',
  'doctor': 'Doctor'
};

/** Tools whose ok result carries a value shown once, and where to find it. */
const SHOW_ONCE: Readonly<Record<string, ShowOnceValue>> = {
  create_user: { label: 'Temporary password', value: 'temporaryPassword' },
  issue_api_key: { label: 'API key', value: 'value' },
  rotate_api_key: { label: 'API key', value: 'value' }
};

/** Result statuses that mean the tool did not change anything yet. */
const NOT_DONE_STATUSES = new Set(['needs_confirmation', 'secret_request', 'pending_approval', 'policy_denied']);

/** Singleton state for the Configuration chat side panel. Parallel to the
 *  Ops and catalog chat state services but with its own conversation and a
 *  `sessionId` the server scopes confirmation tokens by. Lives for the
 *  browser session so leaving /configuration and returning preserves the
 *  conversation. */
@Injectable({ providedIn: 'root' })
export class ConfigAssistantStateService {
  turns: Turn[] = [];
  draft = '';
  streaming = false;

  /** Rotated by newChat() so confirmation tokens issued in a cleared
   *  transcript can no longer be used. */
  sessionId: string = crypto.randomUUID();

  private activeSub: Subscription | null = null;

  /** Emits when something outside the panel wants the panel to open. The
   *  chat panel subscribes and expands itself on emit. */
  private openRequestedSubject = new Subject<void>();
  openRequested$: Observable<void> = this.openRequestedSubject.asObservable();

  /** Emits `{tab}` after a mutating tool completed, so the matching sub-tab
   *  can reload without a page refresh. */
  private changedSubject = new Subject<{ tab: string }>();
  changed$: Observable<{ tab: string }> = this.changedSubject.asObservable();

  /** Emits a sub-tab id when a card's "Show me" link is clicked; the
   *  Configuration page switches to it. */
  private showMeSubject = new Subject<string>();
  showMe$: Observable<string> = this.showMeSubject.asObservable();

  constructor(
    private api: ConfigAssistantService,
    private context: ConfigChatContextService
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

    // The sub-tab showing right now, re-read on every turn.
    const ctx = this.context.snapshot();

    this.activeSub = this.api.chat(messages, ctx, this.sessionId).subscribe({
      next: (evt) => this.handleEvent(evt, assistantTurn),
      error: (err) => {
        assistantTurn.errorMessage = err?.message || 'Connection error';
        assistantTurn.done = true;
        this.streaming = false;
      },
      complete: () => {
        // The server always sends `done` (or `error`) before closing. A stream
        // that ends without either was cut by something in between — a proxy
        // idle timeout, a dropped network — so say so instead of ending silently.
        if (!assistantTurn.done && !assistantTurn.errorMessage) {
          assistantTurn.errorMessage = 'The connection closed before the reply finished. A tool call that was running may still complete on the server — check the Audit Log sub-tab for its outcome, then continue the chat.';
        }
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
    this.sessionId = crypto.randomUUID();
  }

  /** Set the composer draft and signal the panel to expand — the user
   *  reviews and presses send. */
  seedDraft(text: string): void {
    this.draft = text;
    this.openRequestedSubject.next();
  }

  /** Settle a confirmation card: Confirm sends the confirm token, Cancel the
   *  cancel token, as the next user message in this session. Only once per
   *  card; ignored while a reply is streaming (the buttons are disabled then). */
  decide(card: ToolCard, ok: boolean): void {
    const c = card.confirm;
    if (!c || c.decided || this.streaming) return;
    c.decided = true;
    c.decision = ok ? 'confirmed' : 'cancelled';
    // Keep whatever the user was typing; send() clears the draft.
    const draft = this.draft;
    this.send((ok ? '[confirm ' : '[cancel ') + c.token + ']');
    this.draft = draft;
  }

  /** Tell the sub-tab that owns `tab` to reload (used after the browser
   *  stores a secret itself, which produces no tool_result). */
  notifyChanged(tab: string): void {
    this.changedSubject.next({ tab });
  }

  /** Ask the Configuration page to switch to `tab`. */
  requestShow(tab: string): void {
    this.showMeSubject.next(tab);
  }

  /** The once-only value on an ok create_user / issue_api_key /
   *  rotate_api_key card, or null. */
  showOnce(card: ToolCard): ShowOnceValue | null {
    if (card.status !== 'ok' || card.isError) return null;
    const spec = SHOW_ONCE[card.name];
    if (!spec) return null;
    let parsed: any;
    try {
      parsed = JSON.parse(card.result);
    } catch {
      return null;
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
    const value = parsed[spec.value];
    if (typeof value !== 'string' || value.length === 0) return null;
    return { label: spec.label, value };
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
        turn.segments.push(this.newCard(evt.id, evt.name, null, 'running'));
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
      case 'confirm_request': {
        let card = this.findToolCard(turn, evt.id);
        if (!card) {
          card = this.newCard(evt.id, evt.tool, null, 'ok');
          turn.segments.push(card);
        }
        card.confirm = { tool: evt.tool, summary: evt.summary, token: evt.token, decided: false };
        break;
      }
      case 'secret_request': {
        // No tool_result follows a secret form (the loop pauses for the
        // user), so the card is settled here; synthesize one if needed.
        let card = this.findToolCard(turn, evt.id);
        if (!card) {
          card = this.newCard(evt.id, 'put_secret', { name: evt.secretName }, 'ok');
          turn.segments.push(card);
        }
        card.status = 'ok';
        const fieldValues: Record<string, string> = {};
        for (const f of evt.fieldNames) fieldValues[f] = '';
        card.secretRequest = {
          secretName: evt.secretName,
          fieldNames: evt.fieldNames,
          reason: evt.reason,
          fieldValues,
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

  private newCard(id: string, name: string, input: any, status: ToolCard['status']): ToolCard {
    return { kind: 'tool', id, name, input, result: '', isError: false, status, expanded: false };
  }

  /** Emit `{tab}` when a mutating tool actually changed something. */
  private maybeNotifyChanged(card: ToolCard): void {
    const tab = this.changedTab(card);
    if (tab) this.notifyChanged(tab);
  }

  /** The sub-tab a settled card changed: ok status, a mapped tool, and a
   *  result that is not a proposal / form / denial. Unparseable results count
   *  as done. Null otherwise. */
  changedTab(card: ToolCard): string | null {
    if (card.status !== 'ok' || card.isError) return null;
    const tab = TOOL_TAB[card.name];
    if (!tab) return null;
    let status: unknown;
    try {
      const parsed = JSON.parse(card.result);
      status = parsed && typeof parsed === 'object' ? parsed.status : undefined;
    } catch {
      status = undefined;
    }
    if (typeof status === 'string' && NOT_DONE_STATUSES.has(status)) return null;
    return tab;
  }

  private findToolCard(turn: AssistantTurn, id: string): ToolCard | undefined {
    for (let i = turn.segments.length - 1; i >= 0; i--) {
      const s = turn.segments[i];
      if (s.kind === 'tool' && s.id === id) return s as ToolCard;
    }
    return undefined;
  }
}
