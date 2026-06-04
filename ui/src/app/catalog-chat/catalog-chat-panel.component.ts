import { Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild, AfterViewChecked } from '@angular/core';
import { Subscription } from 'rxjs';
import { CatalogAssistantStateService, ToolCard, AssistantTurn } from './catalog-assistant-state.service';

interface StarterPrompt {
  label: string;
  prompt: string;
}

/** Right-rail curation chat for the Catalog page. Collapsed by default (40px
 *  rail with an "Ask" toggle); expands to ~420px split-pane next to the
 *  catalog tree. State lives in a root singleton so the transcript survives
 *  leaving /catalog and returning. Mirrors OpsChatPanelComponent — same
 *  layout, same CSS-var width handshake — but drives the curation agent. */
@Component({
  selector: 'app-catalog-chat-panel',
  templateUrl: './catalog-chat-panel.component.html',
  styleUrls: ['./catalog-chat-panel.component.css']
})
export class CatalogChatPanelComponent implements OnInit, OnDestroy, AfterViewChecked {
  private static readonly STORAGE_KEY = 'catalog.chatPanel.expanded';

  expanded = false;

  starterPrompts: StarterPrompt[] = [
    { label: 'What\'s here?',        prompt: 'Summarize what each catalog contains and what\'s sitting in Uncataloged.' },
    { label: 'Organize Uncataloged', prompt: 'Look at what\'s in Uncataloged and suggest how to group it into catalogs. Don\'t move anything yet — just propose.' },
    { label: 'Group by source',      prompt: 'Suggest catalogs that group my taps and pipelines by data source or domain. Propose first; I\'ll tell you which moves to make.' },
    { label: 'Tidy up',              prompt: 'Point out catalogs that look redundant, near-empty, or inconsistently named, and suggest how to consolidate them.' }
  ];

  @ViewChild('composerEl') composerEl?: ElementRef<HTMLTextAreaElement>;
  @ViewChild('scrollContainer') scrollContainer?: ElementRef<HTMLDivElement>;

  private lastTurnCount = 0;
  private wasStreaming = false;
  private scrollPending = false;
  private openSub?: Subscription;

  constructor(public state: CatalogAssistantStateService) {}

  ngOnInit(): void {
    const raw = localStorage.getItem(CatalogChatPanelComponent.STORAGE_KEY);
    this.expanded = raw === 'true';
    this.scrollPending = true;
    this.publishWidth();
    // "Describe to Assistant" buttons on a catalog card seed the draft on the
    // state service and emit here so we can expand + focus.
    this.openSub = this.state.openRequested$.subscribe(() => {
      if (!this.expanded) {
        this.expanded = true;
        localStorage.setItem(CatalogChatPanelComponent.STORAGE_KEY, 'true');
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
    // Leave the CSS var on documentElement — harmless if the catalog page is
    // unmounted, and avoids a layout flash if the panel briefly remounts.
  }

  /** Publish the panel's current rendered width as a CSS variable on the
   *  document so the catalog layout can reserve matching horizontal space via
   *  `padding-right: var(--catalog-chat-width)`. */
  private publishWidth(): void {
    const px = this.expanded ? '420px' : '40px';
    document.documentElement.style.setProperty('--catalog-chat-width', px);
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
   *  Catalog page. Backslash is unused by browsers and editors here so the
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
    localStorage.setItem(CatalogChatPanelComponent.STORAGE_KEY, String(this.expanded));
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
      case 'set_catalog': {
        const target = arg('tap') || arg('pipeline');
        const cat = arg('catalog');
        const label = cat
          ? `Moving ${target || 'item'} → ${cat}`
          : `Clearing catalog on ${target || 'item'}`;
        return { icon: '📁', label };
      }
      case 'list_taps':       return { icon: '📋', label: 'Listing taps' };
      case 'list_pipelines':  return { icon: '📋', label: 'Listing pipelines' };
      case 'get_tap':         return { icon: '🔎', label: 'Inspecting tap' + (arg('name') ? ` ${arg('name')}` : '') };
      case 'get_pipeline':    return { icon: '🔎', label: 'Inspecting pipeline' + (arg('name') ? ` ${arg('name')}` : '') };
      case 'get_tap_logs':    return { icon: '📄', label: 'Reading tap logs' + (arg('name') ? ` for ${arg('name')}` : '') };
      case 'update_tap':      return { icon: '✏️', label: 'Updating tap' + (arg('name') ? ` ${arg('name')}` : '') };
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
