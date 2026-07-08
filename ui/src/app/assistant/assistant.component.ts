import { Component, OnInit, ViewChild, ElementRef, AfterViewInit, AfterViewChecked } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { AssistantStateService, AssistantTurn, ToolCard, TextSegment } from './assistant-state.service';
import { SecretsService } from '../secrets.service';

interface StarterPrompt {
  label: string;
  prompt: string;
}

@Component({
  selector: 'app-assistant',
  templateUrl: './assistant.component.html',
  styleUrls: ['./assistant.component.css']
})
export class AssistantComponent implements OnInit, AfterViewInit, AfterViewChecked {
  // Static UI config — fine to keep in the component.
  starterPrompts: StarterPrompt[] = [
    { label: 'SEC filings',  prompt: 'Find a source of US public-company SEC filings and build a tap and pipeline.' },
    { label: 'Weather data', prompt: 'Pull current weather data from a public API and create a pipeline for it.' },
    { label: 'Taxi trips',   prompt: 'Ingest the most recent NYC taxi trip data into a pipeline.' },
    { label: 'GitHub repos', prompt: 'Find a way to pull the most-starred GitHub repos of the past week and pipe them into Datris.' }
  ];

  @ViewChild('composerEl') composerEl?: ElementRef<HTMLTextAreaElement>;
  @ViewChild('scrollContainer') scrollContainer?: ElementRef<HTMLDivElement>;

  private scrollPending = false;
  private lastTurnCount = 0;
  private wasStreaming = false;

  /** Names of existing tap secrets — used by the credentials form's
   *  "Use existing secret" dropdown. Loaded lazily the first time a form
   *  shows up. */
  existingTapSecrets: string[] = [];
  private existingTapSecretsLoaded = false;

  constructor(public state: AssistantStateService, private secretsService: SecretsService, private route: ActivatedRoute) { }

  ngOnInit(): void {
    this.state.ensureInit();
    this.scrollPending = true;

    // When the user navigated in from a specific catalog (e.g. clicked
    // "Describe to Assistant" inside a catalog card), start a fresh chat so
    // the new request isn't grafted onto an unrelated prior conversation.
    // Seed the composer with an instruction that names the catalog so the
    // model assigns it on the create_tap / create_pipeline calls it makes.
    const catalogParam = this.route.snapshot.queryParamMap.get('catalog');
    if (catalogParam) {
      this.state.newChat();
      // Named catalog: short context prefix that names the catalog so the
      // model carries it onto the create_tap / create_pipeline calls. For
      // Uncataloged there's nothing to anchor, so leave the composer empty.
      const seed = catalogParam === 'Uncataloged'
        ? ''
        : `In the "${catalogParam}" catalog: `;
      this.state.draft = seed;
      requestAnimationFrame(() => {
        const el = this.composerEl?.nativeElement;
        if (el) {
          el.focus();
          el.setSelectionRange(seed.length, seed.length);
          this.autoGrowComposer();
        }
      });
    }
  }

  /** Ensure the existing tap secrets list is loaded for the form dropdown.
   *  Idempotent — first call fetches, subsequent calls no-op. */
  private ensureTapSecretsLoaded(): void {
    if (this.existingTapSecretsLoaded) return;
    this.existingTapSecretsLoaded = true;
    this.secretsService.listSecrets('tap').subscribe({
      next: (names) => { this.existingTapSecrets = names || []; },
      error: () => { this.existingTapSecrets = []; }
    });
  }

  ngAfterViewInit(): void {
    // First paint after navigating to (or back to) the tab: jump to the bottom
    // of any existing conversation so the user sees the latest state, and
    // resize the composer in case the user had draft text in progress before
    // they navigated away.
    this.scrollToBottom();
    requestAnimationFrame(() => this.autoGrowComposer());
  }

  ngAfterViewChecked(): void {
    // Two reasons to auto-scroll:
    //   1. We just navigated in and there's an existing conversation.
    //   2. A new turn arrived since the last check.
    const turnCount = this.state.turns.length;
    if (this.scrollPending || turnCount !== this.lastTurnCount || this.state.streaming) {
      this.scrollPending = false;
      this.lastTurnCount = turnCount;
      this.scrollToBottom();
    }
    // When a stream finishes, drop focus back into the composer so the user
    // can immediately type a follow-up. Skip if they've already moved focus
    // into another field (e.g., a credentials form rendered inside a turn).
    if (this.wasStreaming && !this.state.streaming) {
      this.wasStreaming = false;
      const active = document.activeElement as HTMLElement | null;
      const tag = active?.tagName;
      const inField = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || !!active?.isContentEditable;
      if (!inField) {
        requestAnimationFrame(() => this.composerEl?.nativeElement.focus());
      }
    } else if (this.state.streaming) {
      this.wasStreaming = true;
    }
  }

  private scrollToBottom(): void {
    const el = this.scrollContainer?.nativeElement;
    if (el) el.scrollTop = el.scrollHeight;
  }

  // -------- Empty-state / state accessors --------

  get isEmptyState(): boolean {
    return !this.state.loadingInit && !this.state.initError && this.state.turns.length === 0;
  }

  get reasoningLabel(): string {
    return (this.state.initInfo?.provider || '').toLowerCase() === 'openai' ? '💭 Reasoning' : '💭 Thinking';
  }

  // -------- Composer actions --------

  fillFromStarter(p: StarterPrompt): void {
    this.state.draft = p.prompt;
    requestAnimationFrame(() => {
      this.composerEl?.nativeElement.focus();
      this.autoGrowComposer();
    });
  }

  onComposerKeydown(e: KeyboardEvent): void {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      if (this.state.streaming) return;
      this.send();
    }
  }

  /** Resize the composer textarea to fit its content. Called on every input
   *  event. Caps at max-height (CSS) — the textarea scrolls beyond that.
   *  Resets to single-line when the draft is empty. */
  autoGrowComposer(): void {
    const el = this.composerEl?.nativeElement;
    if (!el) return;
    // Setting to 'auto' first lets scrollHeight reflect the actual content
    // height; otherwise the previous explicit height clamps the measurement.
    el.style.height = 'auto';
    el.style.height = el.scrollHeight + 'px';
  }

  send(): void {
    this.state.send(this.state.draft);
    requestAnimationFrame(() => {
      this.composerEl?.nativeElement.focus();
      // Snap back to single-line after sending.
      this.autoGrowComposer();
    });
  }

  stop(): void {
    this.state.stop();
  }

  // -------- File attachment (drag-drop + picker) --------

  /** Highlight state for the composer drop zone. */
  dragOver = false;

  onFileSelected(e: Event): void {
    const input = e.target as HTMLInputElement;
    const file = input.files && input.files[0];
    if (file) this.state.stageFile(file);
    // Reset so re-selecting the same file fires `change` again.
    input.value = '';
  }

  onDragOver(e: DragEvent): void {
    if (this.state.streaming) return;
    e.preventDefault();
    this.dragOver = true;
  }

  onDragLeave(e: DragEvent): void {
    e.preventDefault();
    this.dragOver = false;
  }

  onDrop(e: DragEvent): void {
    e.preventDefault();
    this.dragOver = false;
    if (this.state.streaming) return;
    const file = e.dataTransfer?.files && e.dataTransfer.files[0];
    if (file) this.state.stageFile(file);
  }

  newChat(): void {
    this.state.newChat();
    requestAnimationFrame(() => this.composerEl?.nativeElement.focus());
  }

  // -------- Friendly labels for tool cards --------

  toolLabel(card: ToolCard): { icon: string; label: string } {
    const name = card.name;
    const input = card.input || {};
    const arg = (k: string): string => {
      const v = input[k];
      return typeof v === 'string' && v.length > 0 ? v : '';
    };

    switch (name) {
      case 'web_search':            return { icon: '🔍', label: 'Searching the web' + (arg('query') ? ` for ${arg('query')}` : '') };
      case 'create_tap':            return { icon: '🛠',  label: 'Creating tap' + (arg('name') ? ` ${arg('name')}` : '') };
      case 'update_tap':            return { icon: '✏️', label: 'Updating tap' + (arg('name') ? ` ${arg('name')}` : '') };
      case 'test_tap':              return { icon: '▶️', label: 'Testing tap' + (arg('name') ? ` ${arg('name')}` : '') };
      case 'run_tap':               return { icon: '▶️', label: 'Running tap' + (arg('name') ? ` ${arg('name')}` : '') };
      case 'create_pipeline':       return { icon: '🛠',  label: 'Creating pipeline' + (arg('name') ? ` ${arg('name')}` : '') };
      case 'get_pipeline':          return { icon: '🔎', label: 'Inspecting pipeline' + (arg('name') ? ` ${arg('name')}` : '') };
      case 'create_tap_secret':     return { icon: '🔐', label: 'Creating secret' + (arg('name') ? ` ${arg('name')}` : '') };
      case 'update_secret':         return { icon: '🔐', label: 'Updating secret' + (arg('name') ? ` ${arg('name')}` : '') };
      case 'list_taps':             return { icon: '📋', label: 'Listing existing taps' };
      case 'list_pipelines':        return { icon: '📋', label: 'Listing existing pipelines' };
      case 'list_tap_secrets':      return { icon: '🔐', label: 'Checking existing tap secrets' };
      case 'get_tap_secret_fields': return { icon: '🔐', label: 'Inspecting secret' + (arg('name') ? ` ${arg('name')}` : '') };
      case 'query_postgres':        return { icon: '🔎', label: 'Querying Postgres' };
      case 'query_mongodb':         return { icon: '🔎', label: 'Querying MongoDB' };
      case 'query_natural':         return { icon: '🔎', label: 'Answering with RAG' };
    }
    if (name.startsWith('list_postgres_')) {
      return { icon: '📋', label: 'Listing Postgres ' + name.substring('list_postgres_'.length) };
    }
    if (name.startsWith('list_mongodb_')) {
      return { icon: '📋', label: 'Listing MongoDB ' + name.substring('list_mongodb_'.length) };
    }
    if (name.startsWith('search_')) {
      return { icon: '🔎', label: 'Searching ' + name.substring('search_'.length) };
    }
    if (name.startsWith('delete_')) {
      return { icon: '🗑',  label: 'Deleting ' + (arg('name') || name.substring('delete_'.length)) };
    }
    return { icon: '▸', label: 'Called ' + name };
  }

  toolStatusIcon(card: ToolCard): string {
    if (card.status === 'running') return '…';
    if (card.status === 'ok') return '✓';
    return '✕';
  }

  toggleToolCard(card: ToolCard): void {
    card.expanded = !card.expanded;
  }

  toggleThinking(turn: AssistantTurn): void {
    turn.thinkingExpanded = !turn.thinkingExpanded;
  }

  /** True while any tool card in the turn is still running — its own dots
   *  cover the activity, so the turn-level streaming indicator hides. */
  hasRunningTool(turn: AssistantTurn): boolean {
    return turn.segments.some(s => s.kind === 'tool' && s.status === 'running');
  }

  /** Live tail of the streaming reasoning, flattened to one line for the
   *  collapsed thinking row — shows WHAT the model is working through. */
  thinkingTicker(turn: AssistantTurn): string {
    const flat = turn.thinking.replace(/\s+/g, ' ').trim();
    return flat.length > 90 ? '…' + flat.slice(-90) : flat;
  }

  /** Human-readable size of the tool input streamed so far. */
  inputSize(card: ToolCard): string {
    const n = card.inputChars || 0;
    return n < 1024 ? n + ' B' : (n / 1024).toFixed(1) + ' KB';
  }

  formatJson(value: any): string {
    if (value === null || value === undefined) return '';
    try {
      const str = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
      const MAX = 8000;
      if (str.length > MAX) return str.substring(0, MAX) + '\n…[truncated]';
      return str;
    } catch {
      return String(value);
    }
  }

  // -------- "Open tap / pipeline" links at end of turn --------

  createdTaps(turn: AssistantTurn): string[] {
    return this.collectSuccessNames(turn, 'create_tap');
  }

  createdPipelines(turn: AssistantTurn): string[] {
    return this.collectSuccessNames(turn, 'create_pipeline');
  }

  private collectSuccessNames(turn: AssistantTurn, toolName: string): string[] {
    const names: string[] = [];
    for (const s of turn.segments) {
      if (s.kind === 'tool' && s.name === toolName && s.status === 'ok') {
        const n = s.input?.name;
        if (typeof n === 'string' && n.length > 0 && !names.includes(n)) names.push(n);
      }
    }
    return names;
  }

  trackByIndex(index: number): number {
    return index;
  }

  // -------- Credentials form on `request_tap_secret_from_user` cards --------

  /** Called when the template needs the dropdown list — kicks off the secrets
   *  fetch on first render of any secret form. */
  onSecretFormVisible(): void {
    this.ensureTapSecretsLoaded();
  }

  /** Submit the credentials form on a tool card. Stores the secret via the
   *  existing /api/v1/secrets endpoint (values go straight to Vault — never
   *  through chat), then auto-sends a follow-up user message so the agent
   *  knows to resume. */
  submitSecretForm(card: ToolCard): void {
    const sr = card.secretRequest;
    if (!sr || sr.submitting || sr.submitted) return;
    sr.errorMessage = '';

    if (sr.mode === 'existing') {
      if (!sr.selectedExisting) {
        sr.errorMessage = 'Pick a secret to continue.';
        return;
      }
      sr.submitted = true;
      this.state.draft = '';
      // Resume the conversation. The agent reads this as a normal user reply.
      this.sendSystemReply(`Use the existing tap secret \`${sr.selectedExisting}\` for this — its values are already in Vault.`);
      return;
    }

    // mode === 'new'
    const name = (sr.secretName || '').trim();
    if (!name) {
      sr.errorMessage = 'Missing secret name.';
      return;
    }
    const fields: Record<string, string> = {};
    for (const key of sr.fieldNames) {
      const value = (sr.fieldValues[key] || '').trim();
      if (!value) {
        sr.errorMessage = `Field \`${key}\` is required.`;
        return;
      }
      fields[key] = value;
    }
    // Auto-tag as tap secret so the platform's ownership rules apply.
    fields['_type'] = 'tap';

    sr.submitting = true;
    this.secretsService.putSecret(name, fields).subscribe({
      next: () => {
        sr.submitting = false;
        sr.submitted = true;
        // Wipe in-memory values so they aren't lurking in component state.
        for (const k of Object.keys(sr.fieldValues)) sr.fieldValues[k] = '';
        // Refresh the dropdown cache so the new secret shows up in any
        // subsequent credentials form.
        this.existingTapSecretsLoaded = false;
        this.sendSystemReply(`I saved the tap secret \`${name}\` with fields ${sr.fieldNames.map(f => '`' + f + '`').join(', ')}. The values are stored in Vault — use this secret name when creating the tap.`);
      },
      error: (err) => {
        sr.submitting = false;
        sr.errorMessage = 'Save failed: ' + (typeof err?.error === 'string' ? err.error : (err?.message || 'unknown'));
      }
    });
  }

  /** Whether a credentials-form field should be masked (rendered as
   *  type="password") by default. Heuristic on the field NAME — not on the
   *  value.
   *
   *  Default-mask policy: anything the agent put in a credentials form is
   *  likely sensitive. Even fields that "look like identifiers" (e.g.
   *  AWS_ACCESS_KEY_ID) are half of a credential pair and shouldn't be
   *  shoulder-readable. Only allow-list truly non-sensitive operational
   *  details: regions, hostnames, ports, endpoints, user-agents, database
   *  names. Everything else stays masked. */
  isSecretField(name: string): boolean {
    const n = (name || '').toLowerCase();
    // Non-sensitive identifier shapes — operational details, not secrets.
    if (n === 'region' || n.endsWith('_region')) return false;
    if (n === 'host' || n.endsWith('_host') || n === 'hostname' || n.endsWith('_hostname')) return false;
    if (n === 'port' || n.endsWith('_port')) return false;
    if (n.includes('endpoint') || n.includes('url')) return false;
    if (n.includes('user_agent') || n.includes('useragent')) return false;
    if (n === 'database' || n.endsWith('_database') || n === 'db_name' || n === 'dbname') return false;
    if (n === 'schema' || n.endsWith('_schema')) return false;
    if (n === 'bucket' || n.endsWith('_bucket')) return false;
    if (n === 'project' || n.endsWith('_project') || n === 'project_id') return false;
    if (n === 'account' || n.endsWith('_account_id')) return false;
    // Default: mask. AWS_ACCESS_KEY_ID, USERNAME, EMAIL, anything else — all
    // treated as potentially sensitive until the user reveals.
    return true;
  }

  cancelSecretForm(card: ToolCard): void {
    const sr = card.secretRequest;
    if (!sr || sr.submitted) return;
    sr.cancelled = true;
    for (const k of Object.keys(sr.fieldValues)) sr.fieldValues[k] = '';
    this.sendSystemReply(`I'd rather not provide credentials right now — stop here.`);
  }

  /** Send a follow-up message on the user's behalf to resume the agent loop.
   *  Bypasses the composer so it shows up as a normal user turn in the chat. */
  private sendSystemReply(text: string): void {
    this.state.draft = text;
    this.state.send(text);
  }
}
