import { Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild, AfterViewChecked } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, Subscription } from 'rxjs';
import {
  ConfigAssistantStateService, ToolCard, AssistantTurn, ShowOnceValue, TOOL_TAB, TAB_LABEL
} from './config-assistant-state.service';
import { SecretsService } from '../secrets.service';

interface StarterPrompt {
  label: string;
  prompt: string;
}

/** Right-rail chat for the Configuration page. Collapsed by default (40px
 *  rail with an "Ask" toggle); expands to ~420px next to the sub-tabs. State
 *  lives in a root singleton so the transcript survives leaving
 *  /configuration and returning. Same layout and CSS-var width handshake as
 *  the Ops and catalog panels, but drives the configuration agent. */
@Component({
    selector: 'app-config-chat-panel',
    templateUrl: './config-chat-panel.component.html',
    styleUrls: ['./config-chat-panel.component.css'],
    standalone: false
})
export class ConfigChatPanelComponent implements OnInit, OnDestroy, AfterViewChecked {
  private static readonly STORAGE_KEY = 'config.chatPanel.expanded';

  expanded = false;

  starterPrompts: StarterPrompt[] = [
    { label: 'What is each AI slot using?', prompt: 'List each AI slot with the provider and model it is using right now.' },
    { label: 'Add a secret for a new tap',  prompt: 'Help me add a secret for a new tap and tell me which fields it needs.' },
    { label: 'Who has admin?',              prompt: 'Which users have the admin role?' },
    { label: 'Run doctor',                  prompt: 'Run doctor and summarize anything that needs attention.' }
  ];

  @ViewChild('composerEl') composerEl?: ElementRef<HTMLTextAreaElement>;
  @ViewChild('scrollContainer') scrollContainer?: ElementRef<HTMLDivElement>;

  private lastTurnCount = 0;
  private wasStreaming = false;
  private scrollPending = false;
  private openSub?: Subscription;

  /** Card ids whose show-once value was just copied (1.5 s "Copied"). */
  private copiedIds = new Set<string>();

  constructor(
    public state: ConfigAssistantStateService,
    private http: HttpClient,
    private secretsService: SecretsService
  ) {}

  ngOnInit(): void {
    const raw = localStorage.getItem(ConfigChatPanelComponent.STORAGE_KEY);
    this.expanded = raw === 'true';
    this.scrollPending = true;
    this.publishWidth();
    // Anything that seeds the draft on the state service emits here so we
    // can expand + focus.
    this.openSub = this.state.openRequested$.subscribe(() => {
      if (!this.expanded) {
        this.expanded = true;
        localStorage.setItem(ConfigChatPanelComponent.STORAGE_KEY, 'true');
        this.publishWidth();
      }
      this.scrollPending = true;
      // Double-rAF: see fillFromStarter for the rationale. The composer's
      // value needs Angular's CD pass to land before scrollHeight is right.
      requestAnimationFrame(() => requestAnimationFrame(() => {
        const el = this.composerEl?.nativeElement;
        if (el) {
          el.focus();
          const len = (this.state.draft || '').length;
          el.setSelectionRange(len, len);
          this.autoGrowComposer();
        }
      }));
    });
  }

  ngOnDestroy(): void {
    this.openSub?.unsubscribe();
    // Leave the CSS var on documentElement — harmless if the page is
    // unmounted, and avoids a layout flash if the panel briefly remounts.
  }

  /** Publish the panel's current rendered width as a CSS variable on the
   *  document so the configuration layout can reserve matching horizontal
   *  space via `padding-right: var(--config-chat-width)`. */
  private publishWidth(): void {
    const px = this.expanded ? '420px' : '40px';
    document.documentElement.style.setProperty('--config-chat-width', px);
  }

  ngAfterViewChecked(): void {
    if (!this.expanded) return;
    const turnCount = this.state.turns.length;
    if (this.scrollPending || turnCount !== this.lastTurnCount || this.state.streaming) {
      this.scrollPending = false;
      this.lastTurnCount = turnCount;
      this.scrollToBottom();
    }
    if (this.wasStreaming && !this.state.streaming) {
      this.wasStreaming = false;
    } else if (this.state.streaming) {
      this.wasStreaming = true;
    }
  }

  /** Cmd+\ (mac) / Ctrl+\ (other) toggles the panel from anywhere within the
   *  Configuration page. Backslash is unused by browsers and editors here so the
   *  shortcut is safe to grab. */
  @HostListener('document:keydown', ['$event'])
  onGlobalKeydown(e: KeyboardEvent): void {
    const isToggle = (e.metaKey || e.ctrlKey) && (e.key === '\\' || e.code === 'Backslash');
    if (isToggle) {
      e.preventDefault();
      this.toggle();
    }
  }

  toggle(): void {
    this.expanded = !this.expanded;
    localStorage.setItem(ConfigChatPanelComponent.STORAGE_KEY, String(this.expanded));
    this.publishWidth();
    if (this.expanded) {
      this.scrollPending = true;
      requestAnimationFrame(() => this.composerEl?.nativeElement.focus());
    }
  }

  get isEmptyState(): boolean {
    return this.state.turns.length === 0;
  }

  fillFromStarter(p: StarterPrompt): void {
    this.state.draft = p.prompt;
    // Two rAFs: the first lets Angular's change-detection flush the new draft
    // into the textarea's `value`; the second runs after layout is committed
    // so `scrollHeight` reflects the actual wrapped content height. Without
    // this, autoGrow measures the OLD content and sizes for one line.
    requestAnimationFrame(() => requestAnimationFrame(() => {
      this.composerEl?.nativeElement.focus();
      this.autoGrowComposer();
    }));
  }

  onComposerKeydown(e: KeyboardEvent): void {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      if (this.state.streaming) return;
      this.send();
    }
  }

  autoGrowComposer(): void {
    const el = this.composerEl?.nativeElement;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = el.scrollHeight + 'px';
  }

  send(): void {
    this.state.send(this.state.draft);
    requestAnimationFrame(() => {
      this.composerEl?.nativeElement.focus();
      this.autoGrowComposer();
    });
  }

  stop(): void {
    this.state.stop();
  }

  newChat(): void {
    this.state.newChat();
    requestAnimationFrame(() => this.composerEl?.nativeElement.focus());
  }

  // ------- confirm cards -------

  confirm(card: ToolCard): void {
    this.state.decide(card, true);
  }

  cancel(card: ToolCard): void {
    this.state.decide(card, false);
  }

  // ------- secret form -------

  /** Store the requested secret straight from the browser (one REST call by
   *  secret name), then tell the agent only "provided". Values never enter
   *  the transcript or the composer draft. */
  submitSecretForm(card: ToolCard): void {
    const sr = card.secretRequest;
    if (!sr || sr.submitting || sr.submitted || sr.cancelled) return;
    sr.errorMessage = '';
    const fields: Record<string, string> = {};
    for (const key of sr.fieldNames) {
      const value = (sr.fieldValues[key] || '').trim();
      if (!value) {
        sr.errorMessage = `Field \`${key}\` is required.`;
        return;
      }
      fields[key] = value;
    }

    let call: Observable<unknown>;
    if (sr.secretName === 'ai-keys') {
      // Only the provided fields; the server merges and keeps the rest.
      call = this.http.put('/api/v1/secrets/ai-keys', fields, { responseType: 'text' });
    } else if (card.name === 'create_repo_token') {
      call = this.secretsService.putSecret(sr.secretName, { ...fields, _type: 'repo_token' });
    } else {
      const type = card.input?.type;
      const body = (type === 'tap' || type === undefined || type === null || type === '')
        ? { ...fields, _type: 'tap' }
        : fields;
      call = this.secretsService.putSecret(sr.secretName, body);
    }

    sr.submitting = true;
    call.subscribe({
      next: () => {
        sr.submitting = false;
        sr.submitted = true;
        this.wipe(sr.fieldValues);
        const tab = TOOL_TAB[card.name];
        if (tab) this.state.notifyChanged(tab);
        this.state.send('provided');
      },
      error: (err) => {
        sr.submitting = false;
        sr.errorMessage = 'Save failed: ' + (typeof err?.error === 'string' ? err.error : (err?.error?.error || err?.message || 'unknown'));
      }
    });
  }

  cancelSecretForm(card: ToolCard): void {
    const sr = card.secretRequest;
    if (!sr || sr.submitting || sr.submitted || sr.cancelled) return;
    this.wipe(sr.fieldValues);
    sr.cancelled = true;
    this.state.send("I'd rather not provide that now — stop here.");
  }

  private wipe(values: Record<string, string>): void {
    for (const k of Object.keys(values)) values[k] = '';
  }

  /** Whether a credentials-form field is masked. Same allow-list as the
   *  Assistant's form (assistant.component.ts isSecretField): only plainly
   *  operational fields are shown; everything else is masked. */
  isSecretField(name: string): boolean {
    const n = (name || '').toLowerCase();
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
    return true;
  }

  // ------- show-once values -------

  showOnce(card: ToolCard): ShowOnceValue | null {
    return this.state.showOnce(card);
  }

  copy(card: ToolCard): void {
    const v = this.state.showOnce(card);
    if (!v) return;
    navigator.clipboard.writeText(v.value).then(() => {
      this.copiedIds.add(card.id);
      setTimeout(() => this.copiedIds.delete(card.id), 1500);
    });
  }

  isCopied(card: ToolCard): boolean {
    return this.copiedIds.has(card.id);
  }

  // ------- "Show me" -------

  /** The sub-tab a card links to: a completed mutation, or a secret form once
   *  submitted. Null for reads, proposals and open forms. */
  showMeTab(card: ToolCard): string | null {
    if (card.secretRequest) {
      return card.secretRequest.submitted ? (TOOL_TAB[card.name] || null) : null;
    }
    if (card.confirm) return null;
    return this.state.changedTab(card);
  }

  tabLabel(tab: string): string {
    return TAB_LABEL[tab] || tab;
  }

  showMe(tab: string): void {
    this.state.requestShow(tab);
  }

  toggleToolCard(card: ToolCard): void {
    card.expanded = !card.expanded;
  }

  toggleThinking(turn: AssistantTurn): void {
    turn.thinkingExpanded = !turn.thinkingExpanded;
  }

  toolLabel(card: ToolCard): { icon: string; label: string } {
    const name = card.name;
    const input = card.input || {};
    const arg = (k: string): string => {
      const v = input[k];
      return typeof v === 'string' && v.length > 0 ? v : '';
    };
    const named = (verb: string, k: string): string => verb + (arg(k) ? ` ${arg(k)}` : '');
    switch (name) {
      // Reads.
      case 'get_ai_providers':          return { icon: '🔎', label: 'Reading AI providers' };
      case 'list_secrets':              return { icon: '📋', label: 'Listing secrets' };
      case 'get_secret_fields':         return { icon: '🔎', label: named('Reading fields of secret', 'name') };
      case 'get_data_sources_fragment': return { icon: '🔎', label: 'Reading data sources' };
      case 'get_code_repo':             return { icon: '🔎', label: 'Reading code repository settings' };
      case 'test_code_repo_connection': return { icon: '🔌', label: 'Testing code repository connection' };
      case 'list_users':                return { icon: '📋', label: 'Listing users' };
      case 'list_api_keys':             return { icon: '📋', label: 'Listing API keys' };
      case 'list_key_templates':        return { icon: '📋', label: 'Listing API key templates' };
      case 'get_capability_catalog':    return { icon: '📋', label: 'Reading capability catalog' };
      case 'get_agent_policy':          return { icon: '🔎', label: 'Reading agent policy' };
      case 'query_audit_log':           return { icon: '📄', label: 'Querying audit log' };
      case 'get_audit_facets':          return { icon: '📄', label: 'Reading audit log facets' };
      case 'run_doctor':                return { icon: '🩺', label: 'Running doctor' };
      // Mutations (each waits for Confirm, or opens a secret form).
      case 'set_ai_provider_slot':      return { icon: '✏️', label: named('Proposing: change slot', 'slot') };
      case 'set_provider_credentials':  return { icon: '🔑', label: named('Requesting credentials for', 'provider') };
      case 'put_secret':                return { icon: '🔑', label: named('Saving secret', 'name') };
      case 'delete_secret':             return { icon: '🗑️', label: named('Deleting secret', 'name') };
      case 'set_data_sources_fragment': return { icon: '✏️', label: 'Proposing: change data sources' };
      case 'set_code_repo':             return { icon: '✏️', label: 'Proposing: change code repository' };
      case 'create_repo_token':         return { icon: '🔑', label: named('Creating repository token', 'name') };
      case 'delete_repo_token':         return { icon: '🗑️', label: named('Deleting repository token', 'name') };
      case 'create_user':               return { icon: '👤', label: named('Creating user', 'username') };
      case 'set_user_role':             return { icon: '👤', label: named('Changing role of user', 'username') };
      case 'reset_user_password':       return { icon: '👤', label: named('Resetting password of user', 'username') };
      case 'delete_user':               return { icon: '🗑️', label: named('Deleting user', 'username') };
      case 'issue_api_key':             return { icon: '🔑', label: named('Issuing API key', 'label') };
      case 'rotate_api_key':            return { icon: '🔑', label: named('Rotating API key', 'label') };
      case 'revoke_api_key':            return { icon: '🗑️', label: named('Revoking API key', 'label') };
      case 'set_agent_policy':          return { icon: '✏️', label: 'Proposing: change agent policy' };
      case 'use_recommended_policy':    return { icon: '✏️', label: 'Proposing: use recommended agent policy' };
    }
    return { icon: '▸', label: 'Called ' + name };
  }

  toolStatusIcon(card: ToolCard): string {
    if (card.status === 'running') return '…';
    if (card.status === 'ok') return '✓';
    return '✕';
  }

  formatJson(value: any): string {
    if (value === null || value === undefined) return '';
    try {
      const str = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
      const MAX = 6000;
      if (str.length > MAX) return str.substring(0, MAX) + '\n…[truncated]';
      return str;
    } catch {
      return String(value);
    }
  }

  trackByIndex(index: number): number {
    return index;
  }

  private scrollToBottom(): void {
    const el = this.scrollContainer?.nativeElement;
    if (el) el.scrollTop = el.scrollHeight;
  }
}
