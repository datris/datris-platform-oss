import { Component, ElementRef, OnInit, ViewChild, AfterViewChecked } from '@angular/core';
import { TapService } from '../../tap.service';
import { PipelineService } from '../../pipeline.service';
import { SearchChatStateService, ToolCard, AssistantTurn } from './search-chat-state.service';
import { SearchChatContextService } from './search-chat-context.service';

interface StarterPrompt {
  label: string;
  prompt: string;
}

/** Inline conversational-search panel — the "Chat" sub-panel of the Search
 *  tab. Unlike the Ops chat (a collapsible right rail), this fills the tab
 *  body. Transcript, draft, and catalog scope live in root singletons so they
 *  survive navigating away from /search and back. */
@Component({
    selector: 'app-search-chat-panel',
    templateUrl: './search-chat-panel.component.html',
    styleUrls: ['./search-chat-panel.component.css'],
    standalone: false
})
export class SearchChatPanelComponent implements OnInit, AfterViewChecked {
  /** 'All' plus any named catalogs, plus 'Uncataloged' when bare items exist. */
  catalogOptions: string[] = ['All'];

  starterPrompts: StarterPrompt[] = [
    { label: 'What data do we have?',     prompt: 'What data sources are available here? Give me a short tour of the pipelines and taps and what each holds.' },
    { label: 'Summarize a source',        prompt: 'Pick a data source and summarize what kinds of records it contains and roughly how much data is there.' },
    { label: 'Find records about a topic', prompt: 'Find records related to a topic I care about — ask me what topic, then search the most relevant source.' },
    { label: 'Answer a question',          prompt: 'I have a question I think the data can answer — ask me what it is, then find and answer it with citations.' }
  ];

  @ViewChild('composerEl') composerEl?: ElementRef<HTMLTextAreaElement>;
  @ViewChild('scrollContainer') scrollContainer?: ElementRef<HTMLDivElement>;

  private lastTurnCount = 0;
  private scrollPending = false;

  constructor(
    public state: SearchChatStateService,
    public ctx: SearchChatContextService,
    private tapService: TapService,
    private pipelineService: PipelineService
  ) {}

  ngOnInit(): void {
    this.scrollPending = true;
    this.loadCatalogOptions();
  }

  /** Build the scope dropdown from existing taps + pipelines, mirroring how
   *  the Data Catalog page derives catalogs from the `catalog` field. */
  private loadCatalogOptions(): void {
    let taps: any[] = [];
    let pipelines: any[] = [];
    const rebuild = () => {
      const names = new Set<string>();
      let hasUncataloged = false;
      const consider = (item: any) => {
        const n = (item?.name || '');
        if (n.startsWith('__catalog__')) {
          const placeholder = item.catalog || n.replace('__catalog__', '');
          if (placeholder) names.add(placeholder);
          return;
        }
        const cat = item?.catalog || null;
        if (cat) names.add(cat); else hasUncataloged = true;
      };
      taps.forEach(consider);
      pipelines.forEach(consider);
      const sorted = Array.from(names).sort((a, b) => a.localeCompare(b));
      const opts = ['All', ...sorted];
      if (hasUncataloged) opts.push('Uncataloged');
      this.catalogOptions = opts;
      // If the persisted scope is no longer a valid option, fall back to All.
      if (!this.catalogOptions.includes(this.ctx.catalog)) this.ctx.catalog = 'All';
    };
    this.tapService.getTaps().subscribe({ next: t => { taps = t || []; rebuild(); }, error: () => {} });
    this.pipelineService.getPipelines().subscribe({ next: p => { pipelines = p || []; rebuild(); }, error: () => {} });
  }

  ngAfterViewChecked(): void {
    const turnCount = this.state.turns.length;
    if (this.scrollPending || turnCount !== this.lastTurnCount || this.state.streaming) {
      this.scrollPending = false;
      this.lastTurnCount = turnCount;
      this.scrollToBottom();
    }
  }

  get isEmptyState(): boolean {
    return this.state.turns.length === 0;
  }

  fillFromStarter(p: StarterPrompt): void {
    this.state.draft = p.prompt;
    // Two rAFs: first lets change-detection flush the new draft into the
    // textarea's value, second runs after layout so scrollHeight is correct.
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
      case 'list_pipelines':           return { icon: '📋', label: 'Listing pipelines' };
      case 'list_taps':                return { icon: '📋', label: 'Listing taps' };
      case 'get_pipeline':             return { icon: '🔎', label: 'Inspecting pipeline' + (arg('name') ? ` ${arg('name')}` : '') };
      case 'get_tap':                  return { icon: '🔎', label: 'Inspecting tap' + (arg('name') ? ` ${arg('name')}` : '') };
      case 'list_postgres_databases':  return { icon: '🗄️', label: 'Listing databases' };
      case 'list_postgres_schemas':    return { icon: '🗄️', label: 'Listing schemas' };
      case 'list_postgres_tables':     return { icon: '🗄️', label: 'Listing tables' };
      case 'list_postgres_columns':    return { icon: '🗄️', label: 'Inspecting columns' + (arg('table') ? ` of ${arg('table')}` : '') };
      case 'list_mongodb_databases':   return { icon: '🗄️', label: 'Listing Mongo databases' };
      case 'list_mongodb_collections': return { icon: '🗄️', label: 'Listing collections' };
      case 'query_postgres':           return { icon: '🔢', label: 'Querying SQL' };
      case 'query_mongodb':            return { icon: '🔢', label: 'Querying collection' + (arg('collection') ? ` ${arg('collection')}` : '') };
      case 'query_objectstore':        return { icon: '🗂️', label: 'Reading object store' + (arg('pipeline') ? ` ${arg('pipeline')}` : '') };
      case 'query_natural':            return { icon: '💬', label: 'Asking the table' + (arg('table') ? ` ${arg('table')}` : '') };
      case 'ai_answer':                return { icon: '✨', label: 'Synthesizing answer' };
    }
    if (name.startsWith('search_'))   return { icon: '🔍', label: 'Semantic search (' + name.substring('search_'.length) + ')' };
    if (name.startsWith('list_'))     return { icon: '📋', label: 'Listing ' + name.substring('list_'.length).replace(/_/g, ' ') };
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
