import { Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild, AfterViewChecked } from '@angular/core';
import { Subscription } from 'rxjs';
import { OpsAssistantStateService, ToolCard, AssistantTurn } from './ops-assistant-state.service';

interface StarterPrompt {
  label: string;
  prompt: string;
}

/** Right-rail chat panel for the Ops shell. Collapsed by default (40px rail
 *  with an "Ask" toggle); expands to ~420px split-pane next to the dashboard.
 *  The component lives in the shell so transcript state survives
 *  Activity ↔ Ingestion navigation. */
@Component({
  selector: 'app-ops-chat-panel',
  templateUrl: './ops-chat-panel.component.html',
  styleUrls: ['./ops-chat-panel.component.css']
})
export class OpsChatPanelComponent implements OnInit, OnDestroy, AfterViewChecked {
  private static readonly STORAGE_KEY = 'ops.chatPanel.expanded';

  expanded = false;

  starterPrompts: StarterPrompt[] = [
    { label: 'Why is it failing?',     prompt: 'Why is the most recent failure failing? Walk me through the root cause.' },
    { label: 'Re-run latest failures', prompt: 'Re-run the taps for the most recent unrecovered failures.' },
    { label: 'What\'s stale?',         prompt: 'Which taps look stale — overdue relative to their schedule — and what should I do about them?' },
    { label: 'Volume anomalies',       prompt: 'Which pipelines look unusually quiet or unusually busy today versus their 7-day average?' }
  ];

  @ViewChild('composerEl') composerEl?: ElementRef<HTMLTextAreaElement>;
  @ViewChild('scrollContainer') scrollContainer?: ElementRef<HTMLDivElement>;

  private lastTurnCount = 0;
  private wasStreaming = false;
  private scrollPending = false;
  private openSub?: Subscription;

  constructor(public state: OpsAssistantStateService) {}

  ngOnInit(): void {
    const raw = localStorage.getItem(OpsChatPanelComponent.STORAGE_KEY);
    this.expanded = raw === 'true';
    this.scrollPending = true;
    this.publishWidth();
    // Dashboard Ask-about-this buttons seed the draft on the state service
    // and emit here so we can expand + focus. The service also drives this
    // for any future programmatic openers.
    this.openSub = this.state.openRequested$.subscribe(() => {
      if (!this.expanded) {
        this.expanded = true;
        localStorage.setItem(OpsChatPanelComponent.STORAGE_KEY, 'true');
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
    // Leave the CSS var on documentElement — harmless if no other ops view
    // is mounted, and avoids a layout flash if the panel briefly remounts
    // (route change, hot reload).
  }

  /** Publish the panel's current rendered width as a CSS variable on the
   *  document so the ops-shell layout can reserve matching horizontal space
   *  via `padding-right: var(--ops-chat-width)`. Keeps the layout
   *  perfectly in sync with the panel's expand/collapse transitions. */
  private publishWidth(): void {
    const px = this.expanded ? '420px' : '40px';
    document.documentElement.style.setProperty('--ops-chat-width', px);
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

  /** Cmd+\ (mac) / Ctrl+\ (other) toggles the panel from anywhere within
   *  the Ops shell. Backslash is unused by browsers and editors in this
   *  context so the shortcut is safe to grab. */
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
    localStorage.setItem(OpsChatPanelComponent.STORAGE_KEY, String(this.expanded));
    this.publishWidth();
    if (this.expanded) {
      this.scrollPending = true;
      requestAnimationFrame(() => this.composerEl?.nativeElement.focus());
    }
  }

  /** Called by the dashboard's "Ask about this" buttons. Expands the
   *  panel if collapsed, seeds the composer with the row-specific prompt,
   *  focuses the textarea, and resizes it. */
  openWithPrompt(prompt: string): void {
    this.state.seedDraft(prompt);
    if (!this.expanded) {
      this.expanded = true;
      localStorage.setItem(OpsChatPanelComponent.STORAGE_KEY, 'true');
    }
    requestAnimationFrame(() => {
      const el = this.composerEl?.nativeElement;
      if (el) {
        el.focus();
        el.setSelectionRange(prompt.length, prompt.length);
        this.autoGrowComposer();
      }
    });
  }

  get isEmptyState(): boolean {
    return this.state.turns.length === 0;
  }

  fillFromStarter(p: StarterPrompt): void {
    this.state.draft = p.prompt;
    // Two rAFs: the first lets Angular's change-detection cycle flush the
    // new draft into the textarea's `value`; the second runs after layout
    // is committed so `scrollHeight` reflects the actual wrapped content
    // height. Without this, autoGrow measures the textarea's OLD content
    // and sizes for one line — which clips the seeded prompt's second line.
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
    switch (name) {
      case 'run_tap':                return { icon: '▶️', label: 'Running tap' + (arg('name') ? ` ${arg('name')}` : '') };
      case 'get_pipeline_status':    return { icon: '🔎', label: 'Checking pipeline status' };
      case 'get_job_status':         return { icon: '🔎', label: 'Checking job status' };
      case 'kill_job':               return { icon: '🛑', label: 'Killing job' };
      case 'list_taps':              return { icon: '📋', label: 'Listing taps' };
      case 'list_pipelines':         return { icon: '📋', label: 'Listing pipelines' };
      case 'list_tap_secrets':       return { icon: '🔐', label: 'Listing tap secrets' };
      case 'get_tap_logs':           return { icon: '📄', label: 'Reading tap logs' + (arg('name') ? ` for ${arg('name')}` : '') };
      case 'get_tap':                return { icon: '🔎', label: 'Inspecting tap' + (arg('name') ? ` ${arg('name')}` : '') };
      case 'get_pipeline':           return { icon: '🔎', label: 'Inspecting pipeline' + (arg('name') ? ` ${arg('name')}` : '') };
      case 'update_tap':             return { icon: '✏️', label: 'Updating tap' + (arg('name') ? ` ${arg('name')}` : '') };
      case 'update_secret':          return { icon: '🔐', label: 'Updating secret' + (arg('name') ? ` ${arg('name')}` : '') };
      case 'wait_seconds':           return { icon: '⏳', label: 'Waiting' + (input?.seconds ? ` ${input.seconds}s` : '') };
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
