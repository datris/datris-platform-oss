import { Injectable } from '@angular/core';
import { Subscription } from 'rxjs';
import { AssistantService, AssistantEvent, AssistantInit, AskUserQuestion, ChatMessage, StagedAttachment } from '../assistant.service';
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
  /** Cumulative size of the tool input streamed so far. Only meaningful while
   *  status is 'running' — drives the live "composing (N KB)" counter. */
  inputChars?: number;
  /** When the card appeared (tool_use_start) and when the server actually
   *  began executing the tool (tool_use = input fully streamed). Drive the
   *  live elapsed counter and the "still working" hint, so a slow tool call —
   *  an AI generation, or a provider retrying transient errors server-side —
   *  reads as visible progress instead of a frozen spinner. */
  startedAt?: number;
  executingSince?: number;
  /** Live server-side phase for a running `create_tap` script generation,
   *  polled from /tap/generate/status — replaces the generic "still working"
   *  hint with what the generation is actually doing. */
  genProgress?: { active: boolean; phase?: string; attempt?: number; elapsedSeconds?: number };
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
  /** Populated when the agent called `ask_user`. The UI renders an inline
   *  question card; Submit posts `formatAskUserAnswers` as the next user
   *  message. `selections` are Sets in memory; after a reload (JSON) they
   *  come back as plain objects, so read them through `askUserSelected`. */
  askUser?: AskUserState;
}

export interface AskUserState {
  questions: AskUserQuestion[];
  /** Selected option labels per question index. */
  selections: Record<number, Set<string>>;
  /** "Other" free text per question index. */
  other: Record<number, string>;
  submitted: boolean;
  skipped: boolean;
  /** The posted `header: answer` lines, shown read-only once submitted. */
  summary: string;
}

/** Selected labels for one question, tolerating the shape a reload leaves
 *  behind (a Set serialises to `{}`; an array is accepted too). */
export function askUserSelected(au: AskUserState, index: number): Set<string> {
  const v: any = au.selections ? au.selections[index] : undefined;
  if (v instanceof Set) return v;
  const s = new Set<string>(Array.isArray(v) ? v : []);
  if (au.selections) au.selections[index] = s;
  return s;
}

/** The answer for one question: selected labels in option order, then the
 *  "Other" text when non-empty, joined with ", ". Empty when unanswered. */
export function askUserAnswer(au: AskUserState, index: number): string {
  const q = au.questions[index];
  const selected = askUserSelected(au, index);
  const parts: string[] = [];
  for (const o of q?.options || []) if (selected.has(o.label)) parts.push(o.label);
  for (const l of selected) if (!parts.includes(l)) parts.push(l);
  const other = ((au.other || {})[index] || '').trim();
  if (other) parts.push(other);
  return parts.join(', ');
}

/** One `Header: answer` line per question — the text posted as the user's
 *  next message when a question card is submitted. */
export function formatAskUserAnswers(card: ToolCard): string {
  const au = card.askUser;
  if (!au) return '';
  return au.questions.map((q, i) => `${q.header}: ${askUserAnswer(au, i)}`).join('\n');
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
  /** Files that rode along with this message (for transcript display). The
   *  bytes live server-side keyed by `attachmentId`; this is metadata only. */
  attachments?: StagedAttachment[];
}

export interface AssistantTurn {
  role: 'assistant';
  thinking: string;
  thinkingExpanded: boolean;
  segments: AssistantSegment[];
  done: boolean;
  errorMessage: string;
  /** Last moment the user could SEE progress — turn start, a text delta, a
   *  tool card appearing, or a tool result. Drives the "thinking — 45s"
   *  elapsed on the streaming dots, so a long adaptive-thinking stretch
   *  (which streams nothing visible) doesn't read as a hang. */
  lastVisibleProgressAt?: number;
  /** When the last thinking_delta arrived. The turn's thinking renders as one
   *  block at the TOP of the turn, so mid-turn reasoning (between tool calls)
   *  lands far above where the user is reading — the streaming dots use this
   *  to show the live thinking tail inline while summaries are arriving. */
  lastThinkingDeltaAt?: number;
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
  // /init payload — fetched on first mount, refreshed in the background on
  // later mounts and on New chat (the chat model can change on Configuration).
  loadingInit = false;
  initError = '';
  initInfo: AssistantInit | null = null;
  toolNamesSet = new Set<string>();
  private initLoaded = false;
  private refreshingInit = false;

  // Conversation state.
  turns: Turn[] = [];
  draft = '';
  streaming = false;

  /** Files staged for the next message. Persist across turns (the agent may
   *  ask a clarifying question before using the file), then auto-clear once a
   *  create_pipeline / upload_data succeeds. Bytes live server-side; this is
   *  metadata only. */
  activeAttachments: StagedAttachment[] = [];
  /** Transient staging state for the composer's drop zone. */
  attachmentUploading = false;
  attachmentError = '';

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

  /** Fetches /init the first time (with the loading state). Later calls —
   *  every re-mount of the tab — refresh it in the background so the model
   *  line follows a Configuration → AI Providers change. */
  ensureInit(): void {
    if (this.initLoaded) { this.refreshInit(); return; }
    if (this.loadingInit) return;
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

  /** Background re-fetch of /init once the first load succeeded: never
   *  touches loadingInit/initError (no "Connecting…" flash); on error keeps
   *  the previous payload. One request at a time. */
  refreshInit(): void {
    if (!this.initLoaded || this.refreshingInit) return;
    this.refreshingInit = true;
    this.api.init().subscribe({
      next: (info) => {
        this.initInfo = info;
        this.toolNamesSet = new Set(info.toolNames || []);
        this.refreshingInit = false;
      },
      error: (err) => {
        console.warn('Assistant: could not refresh /init; keeping the previous model info', err);
        this.refreshingInit = false;
      }
    });
  }

  /** Send a new user message. Pushes a user turn + an empty assistant turn,
   *  opens the SSE stream, and dispatches events to mutate the assistant turn
   *  as they arrive. The subscription is held by the service so navigating
   *  away from the tab does NOT cancel it. */
  /** Stage a dropped/selected file. Resolves to the handle; the caller may
   *  ignore it (state is updated here). Rejects surface via `attachmentError`. */
  stageFile(file: File): void {
    this.attachmentError = '';
    this.attachmentUploading = true;
    this.api.stageAttachment(file).subscribe({
      next: (att) => {
        this.attachmentUploading = false;
        // De-dupe by filename — re-dropping the same file replaces the prior stage.
        this.activeAttachments = this.activeAttachments.filter(a => a.filename !== att.filename).concat(att);
      },
      error: (err) => {
        this.attachmentUploading = false;
        this.attachmentError = 'Could not attach file: ' +
          (err?.error?.error || err?.message || 'unknown');
      }
    });
  }

  removeAttachment(attachmentId: string): void {
    this.activeAttachments = this.activeAttachments.filter(a => a.attachmentId !== attachmentId);
  }

  clearAttachments(): void {
    this.activeAttachments = [];
    this.attachmentError = '';
  }

  /** Mark every pending `ask_user` card skipped. A card's own Submit marks it
   *  submitted before sending, so it is not affected. */
  private skipPendingAskUser(): void {
    for (const t of this.turns) {
      if (t.role !== 'assistant') continue;
      for (const s of t.segments) {
        if (s.kind === 'tool' && s.askUser && !s.askUser.submitted && !s.askUser.skipped) {
          s.askUser.skipped = true;
        }
      }
    }
  }

  send(text: string): void {
    const trimmed = text.trim();
    // Allow sending a bare file with no text — the attachment carries the intent.
    if (this.streaming) return;
    if (!trimmed && this.activeAttachments.length === 0) return;

    // A question card still waiting for answers is superseded by whatever
    // the user sends now (a typed reply, or another form's reply): collapse it.
    this.skipPendingAskUser();

    const sentAttachments = this.activeAttachments.slice();
    this.turns.push({ role: 'user', text: trimmed, attachments: sentAttachments.length ? sentAttachments : undefined });
    const assistantTurn: AssistantTurn = {
      role: 'assistant',
      thinking: '',
      thinkingExpanded: false,
      segments: [],
      done: false,
      errorMessage: '',
      lastVisibleProgressAt: Date.now()
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
        // An attachment-only turn has empty text; send a short non-empty
        // placeholder so the wire content is never an empty string (which can
        // 400 on replay). The server appends the file descriptor to the
        // current turn regardless.
        const content = t.text || (t.attachments?.length ? '(see attached file)' : '');
        messages.push({ role: 'user', content });
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

    const outboundAttachments = sentAttachments.map(a => ({ attachmentId: a.attachmentId }));
    this.activeSub = this.api.chat(messages, outboundAttachments).subscribe({
      next: (evt) => this.handleEvent(evt, assistantTurn),
      error: (err) => {
        assistantTurn.errorMessage = err?.message || 'Connection error';
        assistantTurn.done = true;
        this.streaming = false;
        this.persist();
      },
      complete: () => {
        // The server always sends `done` (or `error`) before closing. A stream
        // that ends without either was cut by something in between — a proxy
        // idle timeout, a dropped network — so say so instead of ending silently.
        if (!assistantTurn.done && !assistantTurn.errorMessage) {
          assistantTurn.errorMessage = 'The connection closed before the reply finished. A tool call that was running may still complete on the server — check the Ops tab for its outcome, then continue the chat.';
        }
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

  /** Clear the conversation. Called by the New chat button and the catalog
   *  deep-link; also refreshes /init. */
  newChat(): void {
    this.stop();
    this.turns = [];
    this.draft = '';
    this.clearAttachments();
    clearChatState(AssistantStateService.STORAGE_KEY);
    // Reflect the current chat model (it may have changed on Configuration).
    this.refreshInit();
  }

  private handleEvent(evt: AssistantEvent, turn: AssistantTurn): void {
    // Anything the user can see counts as visible progress: streamed text, a
    // tool card appearing, a result landing, a thinking ticker updating.
    // Only iteration boundaries don't (nothing on screen changes).
    if (evt.type !== 'iteration_start') turn.lastVisibleProgressAt = Date.now();
    switch (evt.type) {
      case 'iteration_start':
        break;
      case 'thinking_delta':
        turn.thinking += evt.text;
        turn.lastThinkingDeltaAt = Date.now();
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
          expanded: false,
          startedAt: Date.now()
        });
        break;
      case 'input_delta': {
        const card = this.findToolCard(turn, evt.id);
        if (card) card.inputChars = (card.inputChars || 0) + evt.chars;
        break;
      }
      case 'tool_use': {
        const card = this.findToolCard(turn, evt.id);
        if (card) {
          card.input = evt.input;
          // Input fully streamed — the server is now executing the tool.
          card.executingSince = Date.now();
        }
        break;
      }
      case 'tool_result': {
        const card = this.findToolCard(turn, evt.id);
        if (card) {
          card.result = evt.result;
          card.isError = evt.isError;
          card.status = evt.isError ? 'error' : 'ok';
          // Drop the staged attachment only once the data has actually been
          // LOADED (upload_data) — not after create_pipeline. The load step
          // needs the same attachment, and create_pipeline can report ok at the
          // tool level while still needing a retry, so clearing there would
          // strip the file out from under the very next call.
          if (!evt.isError && evt.name === 'upload_data' && this.activeAttachments.length) {
            this.activeAttachments = [];
          }
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
      case 'ask_user': {
        // Same shape as secret_request: synthesize the card if no tool_use
        // arrived, then attach the question-card state.
        let card = this.findToolCard(turn, evt.id);
        if (!card) {
          card = {
            kind: 'tool',
            id: evt.id,
            name: 'ask_user',
            input: { questions: evt.questions },
            result: '',
            isError: false,
            status: 'ok',
            expanded: false
          };
          turn.segments.push(card);
        }
        card.status = 'ok';
        card.askUser = {
          questions: evt.questions || [],
          selections: {},
          other: {},
          submitted: false,
          skipped: false,
          summary: ''
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
