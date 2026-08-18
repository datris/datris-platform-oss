import { Component, OnInit, OnDestroy, HostListener } from '@angular/core';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { isColumnDragActive } from '../shared/resizable-columns.directive';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { sanitizeLabel } from '../shared/sanitize';
import { AuthService } from '../auth.service';
import { CatalogChatContextService, CatalogSnapshot } from '../catalog-chat/catalog-chat-context.service';
import { CatalogAssistantStateService } from '../catalog-chat/catalog-assistant-state.service';

interface CatalogInfo {
  name: string;
  tapCount: number;
  pipelineCount: number;
  expanded?: boolean;
  tapsExpanded?: boolean;
  pipelinesExpanded?: boolean;
  taps: any[];
  pipelines: any[];
  deleting?: boolean;
}

@Component({
  selector: 'app-data-catalog',
  templateUrl: './data-catalog.component.html',
  styleUrls: ['./data-catalog.component.css']
})
export class DataCatalogComponent implements OnInit, OnDestroy {
  catalogs: CatalogInfo[] = [];
  loading = true;
  showCreateModal = false;
  newCatalogName = '';
  deleteTarget = '';
  // Per-item delete confirm. Key format: "tap:<name>" or "pipeline:<name>".
  deleteItemTarget = '';
  deletingItem = '';
  // Kebab menu state. Key format: "tap:<name>" or "pipeline:<name>".
  menuOpenKey = '';
  moveMenuOpenKey = '';
  movingItem = '';
  moveError = '';
  private moveErrorTimeout: any;
  // Catalog-level bulk move (move all taps + pipelines in one catalog into
  // another). Tracks which catalog's move menu is open and which is mid-move.
  moveCatalogMenuOpen = '';
  movingCatalog = '';
  /** Catalog the user is about to be sent to once a bulk move finishes; the
   *  next loadCatalogs() expands it so the moved items are visible immediately. */
  private pendingAutoExpand = '';
  private refreshInterval: any;
  private changedSub?: Subscription;

  constructor(
    private tapService: TapService,
    private pipelineService: PipelineService,
    private router: Router,
    public auth: AuthService,
    private chatContext: CatalogChatContextService,
    private chatState: CatalogAssistantStateService
  ) {}

  ngOnInit(): void {
    this.loadCatalogs();
    this.refreshInterval = setInterval(() => {
      // Pause auto-refresh during interactions a re-render would destroy: an
      // open move-contents menu, a pending delete confirmation, or a
      // column-resize drag in one of the embedded tables.
      if (this.moveCatalogMenuOpen || this.deleteTarget || isColumnDragActive()) return;
      this.loadCatalogs();
    }, 10000);
    // The curation chat reloads the tree as soon as it moves an item, so the
    // user sees the change without waiting for the 10s tick.
    this.changedSub = this.chatState.changed$.subscribe(() => this.loadCatalogs());
  }

  ngOnDestroy(): void {
    if (this.refreshInterval) clearInterval(this.refreshInterval);
    this.changedSub?.unsubscribe();
    // Save expansion state so the catalog page restores its open sections when
    // the user navigates back (e.g. from an Edit Pipeline wizard).
    this.saveExpandedState();
  }

  /** Publish a compact inventory snapshot for the curation chat panel to
   *  reason against. Excludes the __catalog__ placeholder taps that only exist
   *  to persist empty catalog names. */
  private publishChatSnapshot(): void {
    const snapshot: CatalogSnapshot[] = this.catalogs.map(c => ({
      name: c.name,
      tapCount: c.tapCount,
      pipelineCount: c.pipelineCount,
      taps: c.taps.filter(t => !(t.name || '').startsWith('__catalog__')).map(t => t.name),
      pipelines: c.pipelines.map(p => p.name)
    }));
    const existing = this.chatContext.snapshot();
    this.chatContext.publish({ catalogs: snapshot, focus: existing?.focus ?? null });
  }

  private static readonly STATE_KEY = 'catalog.expanded';

  private readExpandedState(): { catalogs: string[]; taps: string[]; pipelines: string[] } {
    try {
      const raw = sessionStorage.getItem(DataCatalogComponent.STATE_KEY);
      if (!raw) return { catalogs: [], taps: [], pipelines: [] };
      const parsed = JSON.parse(raw);
      return {
        catalogs: Array.isArray(parsed.catalogs) ? parsed.catalogs : [],
        taps: Array.isArray(parsed.taps) ? parsed.taps : [],
        pipelines: Array.isArray(parsed.pipelines) ? parsed.pipelines : []
      };
    } catch {
      return { catalogs: [], taps: [], pipelines: [] };
    }
  }

  private saveExpandedState(): void {
    try {
      const state = {
        catalogs: this.catalogs.filter(c => c.expanded).map(c => c.name),
        taps: this.catalogs.filter(c => c.tapsExpanded).map(c => c.name),
        pipelines: this.catalogs.filter(c => c.pipelinesExpanded).map(c => c.name)
      };
      sessionStorage.setItem(DataCatalogComponent.STATE_KEY, JSON.stringify(state));
    } catch {
      // sessionStorage can throw in private mode or when full — ignore.
    }
  }

  loadCatalogs(): void {
    let taps: any[] = [];
    let pipelines: any[] = [];
    let loaded = 0;

    const finish = () => {
      loaded++;
      if (loaded < 2) return;

      const catalogMap = new Map<string, CatalogInfo>();

      const uncataloged: CatalogInfo = { name: 'Uncataloged', tapCount: 0, pipelineCount: 0, taps: [], pipelines: [] };

      for (const tap of taps) {
        // Skip placeholder taps (used to persist empty catalog names)
        if ((tap.name || '').startsWith('__catalog__')) {
          const catName = tap.catalog || tap.name.replace('__catalog__', '');
          if (catName && !catalogMap.has(catName)) {
            catalogMap.set(catName, { name: catName, tapCount: 0, pipelineCount: 0, taps: [], pipelines: [] });
          }
          continue;
        }
        const name = tap.catalog || null;
        if (name) {
          if (!catalogMap.has(name)) {
            catalogMap.set(name, { name, tapCount: 0, pipelineCount: 0, taps: [], pipelines: [] });
          }
          const cat = catalogMap.get(name)!;
          cat.tapCount++;
          cat.taps.push(tap);
        } else {
          uncataloged.tapCount++;
          uncataloged.taps.push(tap);
        }
      }

      for (const pipeline of pipelines) {
        const name = pipeline.catalog || null;
        if (name) {
          if (!catalogMap.has(name)) {
            catalogMap.set(name, { name, tapCount: 0, pipelineCount: 0, taps: [], pipelines: [] });
          }
          const cat = catalogMap.get(name)!;
          cat.pipelineCount++;
          cat.pipelines.push(pipeline);
        } else {
          uncataloged.pipelineCount++;
          uncataloged.pipelines.push(pipeline);
        }
      }

      // Preserve expanded state across reloads. On the first load (fresh
      // component instance — e.g. user navigated back to /catalog from an Edit
      // wizard) seed from sessionStorage; on subsequent 10s refreshes use the
      // current in-memory state so user toggles aren't lost on tick.
      const isFirstLoad = this.catalogs.length === 0;
      let prevExpanded: Set<string>;
      let prevTapsExpanded: Set<string>;
      let prevPipelinesExpanded: Set<string>;
      if (isFirstLoad) {
        const stored = this.readExpandedState();
        prevExpanded = new Set(stored.catalogs);
        prevTapsExpanded = new Set(stored.taps);
        prevPipelinesExpanded = new Set(stored.pipelines);
      } else {
        prevExpanded = new Set(this.catalogs.filter(c => c.expanded).map(c => c.name));
        prevTapsExpanded = new Set(this.catalogs.filter(c => c.tapsExpanded).map(c => c.name));
        prevPipelinesExpanded = new Set(this.catalogs.filter(c => c.pipelinesExpanded).map(c => c.name));
      }
      this.catalogs = Array.from(catalogMap.values()).sort((a, b) => a.name.localeCompare(b.name));
      // Always render Uncataloged — even when empty it's the day-1 home for any
      // tap or pipeline created without an assigned catalog, and its Create Tap /
      // Create Pipeline buttons are the primary new-user entry point.
      this.catalogs.push(uncataloged);
      for (const cat of this.catalogs) {
        if (prevExpanded.has(cat.name)) cat.expanded = true;
        if (prevTapsExpanded.has(cat.name)) cat.tapsExpanded = true;
        if (prevPipelinesExpanded.has(cat.name)) cat.pipelinesExpanded = true;
        // After a bulk move, auto-expand the destination so users see the
        // moved items without having to find the collapsed card.
        if (this.pendingAutoExpand && cat.name === this.pendingAutoExpand) {
          cat.expanded = true;
          cat.tapsExpanded = cat.taps.length > 0;
          cat.pipelinesExpanded = cat.pipelines.length > 0;
        }
      }
      this.pendingAutoExpand = '';
      this.loading = false;
      // Persist current expansion to sessionStorage on every refresh so the
      // state survives tab refresh / unexpected component teardown — ngOnDestroy
      // is the primary save path for clean navigations.
      this.saveExpandedState();
      // Keep the curation chat's inventory snapshot in sync with the tree.
      this.publishChatSnapshot();
    };

    this.tapService.getTaps().subscribe({
      next: (data) => { taps = data || []; finish(); },
      error: () => finish()
    });

    this.pipelineService.getPipelines().subscribe({
      next: (data) => { pipelines = data || []; finish(); },
      error: () => finish()
    });
  }

  createCatalog(): void {
    const name = sanitizeLabel(this.newCatalogName);
    if (!name) return;
    // Check if catalog already exists
    if (this.catalogs.some(c => c.name === name)) {
      this.showCreateModal = false;
      this.newCatalogName = '';
      return;
    }
    // Create a placeholder tap to persist the catalog name
    const placeholder: any = {
      name: '__catalog__' + name,
      description: 'Catalog placeholder',
      catalog: name,
      enabled: false
    };
    this.tapService.createOrUpdateTap(placeholder).subscribe({
      next: () => {
        this.showCreateModal = false;
        this.newCatalogName = '';
        this.loadCatalogs();
      },
      error: () => {
        this.showCreateModal = false;
        this.newCatalogName = '';
      }
    });
  }

  deleteCatalog(catalog: CatalogInfo): void {
    catalog.deleting = true;
    this.deleteTarget = '';

    // Uncataloged is a pseudo-catalog; it has no placeholder tap to clean up.
    const hasPlaceholder = catalog.name !== 'Uncataloged';
    let remaining = catalog.taps.length + catalog.pipelines.length + (hasPlaceholder ? 1 : 0);

    if (remaining === 0) {
      catalog.deleting = false;
      this.loadCatalogs();
      return;
    }

    const done = () => {
      remaining--;
      if (remaining <= 0) {
        catalog.deleting = false;
        this.loadCatalogs();
      }
    };

    if (hasPlaceholder) {
      const placeholderName = '__catalog__' + catalog.name;
      this.tapService.deleteTap(placeholderName).subscribe({ next: done, error: done });
    }

    for (const tap of catalog.taps) {
      this.tapService.deleteTap(tap.name).subscribe({ next: done, error: done });
    }
    for (const pipeline of catalog.pipelines) {
      this.pipelineService.deletePipeline(pipeline.name).subscribe({ next: done, error: done });
    }
  }

  deleteTap(name: string): void {
    const key = 'tap:' + name;
    this.deletingItem = key;
    this.deleteItemTarget = '';
    this.tapService.deleteTap(name).subscribe({
      next: () => { this.deletingItem = ''; this.loadCatalogs(); },
      error: () => { this.deletingItem = ''; this.loadCatalogs(); }
    });
  }

  toggleMenu(key: string, event: MouseEvent): void {
    event.stopPropagation();
    if (this.menuOpenKey === key) {
      this.menuOpenKey = '';
      this.moveMenuOpenKey = '';
    } else {
      this.menuOpenKey = key;
      this.moveMenuOpenKey = '';
    }
  }

  openMoveSubmenu(key: string, event: MouseEvent): void {
    event.stopPropagation();
    this.moveMenuOpenKey = this.moveMenuOpenKey === key ? '' : key;
  }

  @HostListener('document:click')
  closeMenus(): void {
    this.menuOpenKey = '';
    this.moveMenuOpenKey = '';
    this.moveCatalogMenuOpen = '';
  }

  /** ngFor trackBy so the 10s catalog refresh reuses each card's DOM
   *  (and the embedded <app-taps>/<app-pipelines> components inside) instead
   *  of destroying them — preserves inline-edit state, expansion state, and
   *  any open menus across the auto-refresh tick. */
  trackByCatalogName(_index: number, cat: CatalogInfo): string {
    return cat.name;
  }

  /** Catalog names that an uncataloged item can be moved into. Excludes 'Uncataloged'. */
  moveTargets(): string[] {
    return this.catalogs.filter(c => c.name !== 'Uncataloged').map(c => c.name);
  }

  /** All catalog names (including 'Uncataloged'), passed to embedded
   *  TapsComponent / PipelinesComponent so each row can offer Move-to-catalog
   *  targets. Empty catalogs are included via the __catalog__ placeholder taps
   *  that loadCatalogs already enumerates. */
  get allCatalogNames(): string[] {
    return this.catalogs.map(c => c.name);
  }

  /** Open the in-page curation chat focused on this catalog, with a seeded
   *  prompt the user can edit and send. Replaces the old bounce-out to the
   *  /assistant tab — the chat now lives beside the tree so the catalog stays
   *  in view while the assistant works. */
  describeToAssistant(catalogName: string, event: MouseEvent): void {
    event.stopPropagation();
    this.chatContext.setFocus(catalogName);
    const prompt = catalogName === 'Uncataloged'
      ? 'Look at what\'s in Uncataloged and suggest how to group it into catalogs.'
      : `Describe the "${catalogName}" catalog — what's in it and how it's organized.`;
    this.chatState.seedDraft(prompt);
  }

  createTapInCatalog(catalogName: string, event: MouseEvent): void {
    event.stopPropagation();
    this.router.navigate(['/catalog/taps/create'], { queryParams: { catalog: catalogName } });
  }

  createPipelineInCatalog(catalogName: string, event: MouseEvent): void {
    event.stopPropagation();
    this.router.navigate(['/catalog/pipelines/create'], { queryParams: { catalog: catalogName } });
  }

  editTap(name: string, event: MouseEvent): void {
    event.stopPropagation();
    this.menuOpenKey = '';
    this.router.navigate(['/taps', name, 'edit']);
  }

  editPipeline(name: string, event: MouseEvent): void {
    event.stopPropagation();
    this.menuOpenKey = '';
    this.router.navigate(['/pipelines', name, 'edit']);
  }

  deleteTapFromMenu(name: string, event: MouseEvent): void {
    event.stopPropagation();
    this.menuOpenKey = '';
    this.deleteItemTarget = 'tap:' + name;
  }

  deletePipelineFromMenu(name: string, event: MouseEvent): void {
    event.stopPropagation();
    this.menuOpenKey = '';
    this.deleteItemTarget = 'pipeline:' + name;
  }

  /**
   * Check whether the target catalog already contains an item (tap or pipeline)
   * with the given name. Returns a human-readable clash description, or null if
   * no clash. The UI should block the move on any non-null return.
   *
   * Rationale: moving is purely a metadata relabel, so it will never fail at the
   * database layer — tap names and pipeline names are each globally unique.
   * But a catalog is a curated grouping the user browses, and having two items
   * with the same name (whether same type or cross-type) in the same catalog is
   * confusing. Block the move and force the user to rename first.
   */
  private findMoveClash(targetCatalog: string, itemName: string): string | null {
    const cat = this.catalogs.find(c => c.name === targetCatalog);
    if (!cat) return null;
    const tapClash = cat.taps.find(t => t.name === itemName && !(t.name || '').startsWith('__catalog__'));
    if (tapClash) return 'A tap named "' + itemName + '" already exists in catalog "' + targetCatalog + '".';
    const pipelineClash = cat.pipelines.find(p => p.name === itemName);
    if (pipelineClash) return 'A pipeline named "' + itemName + '" already exists in catalog "' + targetCatalog + '".';
    return null;
  }

  private showMoveError(msg: string): void {
    this.moveError = msg;
    if (this.moveErrorTimeout) clearTimeout(this.moveErrorTimeout);
    this.moveErrorTimeout = setTimeout(() => { this.moveError = ''; }, 6000);
  }

  moveTapToCatalog(tap: any, targetCatalog: string, event: MouseEvent): void {
    event.stopPropagation();
    this.menuOpenKey = '';
    this.moveMenuOpenKey = '';
    const clash = this.findMoveClash(targetCatalog, tap.name);
    if (clash) {
      this.showMoveError(clash + ' Rename one of them first.');
      return;
    }
    const key = 'tap:' + tap.name;
    this.movingItem = key;
    const updated = { ...tap, catalog: targetCatalog };
    this.tapService.createOrUpdateTap(updated).subscribe({
      next: () => { this.movingItem = ''; this.loadCatalogs(); },
      error: () => { this.movingItem = ''; this.loadCatalogs(); }
    });
  }

  movePipelineToCatalog(pipeline: any, targetCatalog: string, event: MouseEvent): void {
    event.stopPropagation();
    this.menuOpenKey = '';
    this.moveMenuOpenKey = '';
    const clash = this.findMoveClash(targetCatalog, pipeline.name);
    if (clash) {
      this.showMoveError(clash + ' Rename one of them first.');
      return;
    }
    const key = 'pipeline:' + pipeline.name;
    this.movingItem = key;
    const updated = { ...pipeline, catalog: targetCatalog };
    this.pipelineService.createPipeline(updated).subscribe({
      next: () => { this.movingItem = ''; this.loadCatalogs(); },
      error: () => { this.movingItem = ''; this.loadCatalogs(); }
    });
  }

  // ── Catalog-level bulk move ────────────────────────────────────────────
  // Move every tap and pipeline from one catalog into another in one click,
  // from the catalog header. Targets exclude the source catalog and the
  // Uncataloged pseudo-catalog (use the wizard to unassign instead).

  catalogMoveTargets(currentCatalogName: string): string[] {
    return this.catalogs
      .filter(c => c.name !== currentCatalogName)
      .map(c => c.name);
  }

  toggleMoveCatalogMenu(catalogName: string, event: MouseEvent): void {
    event.stopPropagation();
    this.moveCatalogMenuOpen = this.moveCatalogMenuOpen === catalogName ? '' : catalogName;
  }

  moveCatalogContents(source: CatalogInfo, targetCatalog: string, event: MouseEvent): void {
    event.stopPropagation();
    this.moveCatalogMenuOpen = '';

    const realTaps = source.taps.filter(t => !(t.name || '').startsWith('__catalog__'));
    const total = realTaps.length + source.pipelines.length;
    if (total === 0) return;

    // Moving to the Uncataloged pseudo-catalog means clearing the catalog
    // assignment on each item; the server stores no literal "Uncataloged".
    const catalogValue = targetCatalog === 'Uncataloged' ? null : targetCatalog;

    this.movingCatalog = source.name;
    this.pendingAutoExpand = targetCatalog;
    let completed = 0;
    const done = () => {
      completed++;
      if (completed === total) {
        this.movingCatalog = '';
        this.loadCatalogs();
      }
    };

    for (const tap of realTaps) {
      this.tapService.createOrUpdateTap({ ...tap, catalog: catalogValue }).subscribe({
        next: done, error: done
      });
    }
    for (const pipeline of source.pipelines) {
      this.pipelineService.createPipeline({ ...pipeline, catalog: catalogValue }).subscribe({
        next: done, error: done
      });
    }
  }

  deletePipelineItem(name: string): void {
    const key = 'pipeline:' + name;
    this.deletingItem = key;
    this.deleteItemTarget = '';
    this.pipelineService.deletePipeline(name).subscribe({
      next: () => { this.deletingItem = ''; this.loadCatalogs(); },
      error: () => { this.deletingItem = ''; this.loadCatalogs(); }
    });
  }
}
