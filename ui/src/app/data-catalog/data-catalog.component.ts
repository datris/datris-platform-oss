import { Component, OnInit, OnDestroy, HostListener } from '@angular/core';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { isColumnDragActive } from '../shared/resizable-columns.directive';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { caseTwinMessage, findCaseTwin, sanitizeCatalogName } from '../shared/sanitize';
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
  /** Names of the __catalog__ placeholder taps whose `catalog` FIELD is this
   *  catalog. A legacy or suffixed placeholder can be named differently from
   *  the catalog, so cleanup goes by field, never by '__catalog__' + name. */
  placeholders?: string[];
  deleting?: boolean;
}

interface CatalogOpFailure {
  name: string;
  error: string;
}

/** Server message for a member that moved to another catalog while a catalog
 *  rename/delete was running. The UI tells the user to refresh. */
const CONCURRENT_MOVE_PHRASE = 'no longer in catalog';

/** Shown when the server has no catalog by the card's name: another client
 *  renamed or deleted it after this page loaded. Nothing is written; the
 *  list is reloaded so the stale card disappears. */
const CATALOG_NOT_FOUND_MESSAGE =
  'Catalog not found. It may have been renamed or deleted elsewhere; the list has been refreshed.';

@Component({
    selector: 'app-data-catalog',
    templateUrl: './data-catalog.component.html',
    styleUrls: ['./data-catalog.component.css'],
    standalone: false
})
export class DataCatalogComponent implements OnInit, OnDestroy {
  catalogs: CatalogInfo[] = [];
  loading = true;
  showCreateModal = false;
  newCatalogName = '';
  deleteTarget = '';
  /** Two-mode catalog delete: 'detach' keeps the items (they move to
   *  Uncataloged), 'cascade' deletes them and their data and requires the
   *  catalog name typed into `confirmText`. */
  deleteMode: 'detach' | 'cascade' = 'detach';
  confirmText = '';
  // Inline catalog rename: the card being edited, the draft value, and the
  // card whose rename request is in flight.
  renameTarget = '';
  renameValue = '';
  renamingCatalog = '';
  /** Per-item failures from the last catalog rename/delete (a 207 body's
   *  `failed[]`). Persistent until dismissed or the next catalog operation. */
  opFailures: CatalogOpFailure[] = [];
  opFailuresLabel = '';
  /** Follow-up notes from the last cascade delete (`warnings[]`), e.g. a
   *  pipeline whose Unity Catalog table Datris could not drop. Persistent
   *  until dismissed or the next catalog operation. */
  opWarnings: { pipeline: string; message: string }[] = [];
  opWarningsLabel = '';
  /** API keys whose catalog scope still names a catalog that was just renamed.
   *  Persistent until dismissed: the user has to update them by hand. */
  affectedKeys: string[] = [];
  affectedKeysCatalog = '';
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
      if (this.moveCatalogMenuOpen || this.deleteTarget || this.renameTarget || isColumnDragActive()) return;
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
          if (catName) {
            const cat = catalogMap.get(catName)!;
            (cat.placeholders = cat.placeholders || []).push(tap.name);
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
    const name = sanitizeCatalogName(this.newCatalogName);
    if (!name) return;
    // A name that differs from an existing catalog only by case would create
    // a second catalog next to it (names are case-sensitive). Refuse it and
    // keep the typed value so the user can fix it in place.
    const caseTwin = findCaseTwin(name, this.catalogs
      .map(c => c.name)
      .filter(n => n !== 'Uncataloged'));
    if (caseTwin) {
      this.newCatalogName = name;
      this.showMoveError(caseTwinMessage(caseTwin));
      return;
    }
    // Exact match: the catalog already exists, nothing to create.
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

  openDelete(catalogName: string, event: MouseEvent): void {
    event.stopPropagation();
    this.clearMoveError();
    this.deleteTarget = catalogName;
    this.deleteMode = 'detach';
    this.confirmText = '';
  }

  cancelDelete(): void {
    this.deleteTarget = '';
    this.deleteMode = 'detach';
    this.confirmText = '';
  }

  /** Delete button state: cascade needs the exact (case-sensitive) name typed. */
  canConfirmDelete(catalog: CatalogInfo): boolean {
    if (catalog.deleting) return false;
    return this.deleteMode === 'detach' || this.confirmText === catalog.name;
  }

  /** Delete a named catalog through the server. "Keep items" (detach) moves
   *  every member to Uncataloged; "Delete items and their data" (cascade)
   *  deletes them. Uncataloged is not a catalog and cannot be deleted here. */
  deleteCatalog(catalog: CatalogInfo): void {
    if (catalog.name === 'Uncataloged') return;
    const mode = this.deleteMode;
    if (mode === 'cascade' && this.confirmText !== catalog.name) return;
    catalog.deleting = true;
    this.clearOpBanners();
    const finish = () => {
      catalog.deleting = false;
      this.cancelDelete();
      this.loadCatalogs();
    };
    this.pipelineService.deleteCatalog(catalog.name, mode, mode === 'cascade' ? this.confirmText : undefined).subscribe({
      next: (res) => {
        this.clearMoveError();
        this.showOpFailures(`Deleting catalog '${catalog.name}'`, res && res.failed);
        this.showOpWarnings(`Deleting catalog '${catalog.name}'`, res && res.warnings);
        finish();
      },
      error: (err) => {
        if (err && err.status === 404) {
          // The catalog is gone on the server (renamed or deleted elsewhere).
          // This card's item list is stale, so nothing is written from it.
          catalog.deleting = false;
          this.cancelDelete();
          this.showMoveError(CATALOG_NOT_FOUND_MESSAGE);
          this.loadCatalogs();
          return;
        }
        this.showMoveError(this.errText(err));
        finish();
      }
    });
  }

  // ── Catalog rename ─────────────────────────────────────────────────────

  startRename(catalog: CatalogInfo, event?: MouseEvent): void {
    if (event) event.stopPropagation();
    if (catalog.name === 'Uncataloged') return;
    this.clearMoveError();
    this.renameTarget = catalog.name;
    this.renameValue = catalog.name;
    // Focus the editor once it renders so the user can type straight away.
    setTimeout(() => {
      const input = document.querySelector('input.rename-catalog-input') as HTMLInputElement | null;
      if (input) { input.focus(); input.select(); }
    });
  }

  cancelRename(): void {
    this.renameTarget = '';
    this.renameValue = '';
  }

  /** Blur applies the label rule so the user sees the name that will be sent. */
  sanitizeRenameValue(): void {
    if (this.renameTarget) this.renameValue = sanitizeCatalogName(this.renameValue || '');
  }

  commitRename(catalog: CatalogInfo): void {
    // One request per catalog at a time; Enter and the check mark both land here.
    if (this.renamingCatalog === catalog.name) return;
    const newName = sanitizeCatalogName(this.renameValue || '');
    if (!newName) {
      this.showMoveError('A catalog name needs at least one letter, digit, _ or -.');
      return;
    }
    if (newName === catalog.name) {
      this.cancelRename();
      return;
    }
    const others = this.catalogs
      .map(c => c.name)
      .filter(n => n !== 'Uncataloged' && n !== catalog.name);
    // A rename never merges: combining catalogs is what "Move all contents"
    // is for. The server refuses an existing target too (409); this saves the
    // round trip.
    if (others.includes(newName)) {
      this.renameValue = newName;
      this.showMoveError(`'${newName}' already exists. To combine catalogs, use "Move all contents" instead.`);
      return;
    }
    // Catalog names compare case-sensitively on the server, so a rename to a
    // name that differs from another catalog only by case would create a
    // second catalog next to it. Refuse it here.
    const caseTwin = findCaseTwin(newName, others);
    if (caseTwin) {
      this.renameValue = newName;
      this.showMoveError(caseTwinMessage(caseTwin));
      return;
    }
    const oldName = catalog.name;
    // Keep the editor and the draft open until the server answers: on a
    // refused rename (400/409) the user fixes the name in place instead of
    // reopening and retyping it.
    this.renameValue = newName;
    this.clearOpBanners();
    this.renamingCatalog = oldName;
    this.pendingAutoExpand = catalog.expanded ? newName : '';
    this.pipelineService.renameCatalog(oldName, newName).subscribe({
      next: (res) => {
        this.clearMoveError();
        if (this.renamingCatalog === oldName) this.renamingCatalog = '';
        // The user may have moved on to another card's editor meanwhile.
        if (this.renameTarget === oldName) this.cancelRename();
        this.showOpFailures(`Renaming catalog '${oldName}' to '${newName}'`, res && res.failed);
        const keys: string[] = (res && Array.isArray(res.affectedKeys)) ? res.affectedKeys : [];
        if (keys.length > 0) {
          this.affectedKeys = keys;
          this.affectedKeysCatalog = oldName;
        }
        this.loadCatalogs();
      },
      error: (err) => {
        if (this.renamingCatalog === oldName) this.renamingCatalog = '';
        this.pendingAutoExpand = '';
        if (err && err.status === 404) {
          // The catalog is gone on the server (renamed or deleted elsewhere).
          // This card's item list is stale, so nothing is written from it.
          if (this.renameTarget === oldName) this.cancelRename();
          this.showMoveError(CATALOG_NOT_FOUND_MESSAGE);
          this.loadCatalogs();
          return;
        }
        // 400 (name rule) and 409 (target already exists, e.g. created
        // elsewhere since the page loaded): show the server message and keep
        // the editor open with the draft. On 409 reload so the list shows the
        // catalog that now exists.
        this.showMoveError(this.errText(err));
        if (err && err.status === 409) this.loadCatalogs();
      }
    });
  }

  private clearOpBanners(): void {
    this.opFailures = [];
    this.opFailuresLabel = '';
    this.opWarnings = [];
    this.opWarningsLabel = '';
  }

  private showOpWarnings(label: string, warnings: any): void {
    const list = Array.isArray(warnings)
      ? warnings.map((w: any) => ({ pipeline: String(w && w.pipeline || ''), message: String(w && w.message || '') }))
      : [];
    this.opWarnings = list;
    this.opWarningsLabel = list.length > 0 ? label : '';
  }

  dismissOpWarnings(): void {
    this.opWarnings = [];
    this.opWarningsLabel = '';
  }

  private showOpFailures(label: string, failed: any): void {
    const list: CatalogOpFailure[] = Array.isArray(failed)
      ? failed.map((f: any) => ({ name: String(f && f.name || ''), error: String(f && f.error || 'unknown error') }))
      : [];
    this.opFailures = list;
    this.opFailuresLabel = list.length > 0 ? label : '';
  }

  /** True when a failure says the item moved to another catalog mid-operation. */
  get hasConcurrentMoveFailure(): boolean {
    return this.opFailures.some(f => (f.error || '').toLowerCase().includes(CONCURRENT_MOVE_PHRASE));
  }

  dismissOpFailures(): void {
    this.clearOpBanners();
  }

  dismissAffectedKeys(): void {
    this.affectedKeys = [];
    this.affectedKeysCatalog = '';
  }

  deleteTap(name: string): void {
    const key = 'tap:' + name;
    this.deletingItem = key;
    this.deleteItemTarget = '';
    this.tapService.deleteTap(name).subscribe({
      next: () => { this.deletingItem = ''; this.clearMoveError(); this.loadCatalogs(); },
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

  /** Error bodies are JSON ({"error": "..."}), which Angular parses into an
   *  object — concatenating it renders "[object Object]" and hides the remedy. */
  private errText(err: any): string {
    if (err && err.error && typeof err.error.error === 'string') return err.error.error;
    if (err && typeof err.error === 'string' && err.error) return err.error;
    return (err && err.message) || 'unknown error';
  }

  /** Clear the transient error banner and its auto-dismiss timer. Called when
   *  an action succeeds or a new rename/delete starts, so an earlier refusal
   *  is not left on screen. The affected-keys banner is separate and stays. */
  private clearMoveError(): void {
    this.moveError = '';
    if (this.moveErrorTimeout) {
      clearTimeout(this.moveErrorTimeout);
      this.moveErrorTimeout = null;
    }
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
      next: () => { this.movingItem = ''; this.clearMoveError(); this.loadCatalogs(); },
      // A refused save (e.g. HTTP 400 on a tap whose stored cron is not a valid
      // 6-field Quartz expression) must show its remedy, not vanish into a
      // silent reload. The body is {"error": "..."}, parsed into an object.
      error: (err) => { this.movingItem = ''; this.showMoveError(this.errText(err)); this.loadCatalogs(); }
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
      next: () => { this.movingItem = ''; this.clearMoveError(); this.loadCatalogs(); },
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
    this.moveAll(source, targetCatalog);
  }

  /** Relabel every tap and pipeline in `source` into `targetCatalog`, one
   *  save per item. When every save succeeds and the source is a named
   *  catalog, its placeholder(s) are removed by catalog FIELD so no empty card
   *  remains; on any failure the placeholder is left alone and the list
   *  reloads. */
  private moveAll(source: CatalogInfo, targetCatalog: string): void {
    const realTaps = source.taps.filter(t => !(t.name || '').startsWith('__catalog__'));
    const total = realTaps.length + source.pipelines.length;
    const removeSourcePlaceholders = () => {
      const placeholders = source.name === 'Uncataloged' ? [] : (source.placeholders || []);
      let left = placeholders.length;
      if (left === 0) {
        this.movingCatalog = '';
        this.loadCatalogs();
        return;
      }
      const one = () => {
        left--;
        if (left <= 0) {
          this.movingCatalog = '';
          this.loadCatalogs();
        }
      };
      for (const name of placeholders) {
        this.tapService.deleteTap(name).subscribe({ next: one, error: one });
      }
    };

    // Moving to the Uncataloged pseudo-catalog means clearing the catalog
    // assignment on each item; the server stores no literal "Uncataloged".
    const catalogValue = targetCatalog === 'Uncataloged' ? null : targetCatalog;

    this.movingCatalog = source.name;
    this.pendingAutoExpand = targetCatalog;
    if (total === 0) {
      removeSourcePlaceholders();
      return;
    }
    let completed = 0;
    let failed = 0;
    const done = (ok: boolean) => {
      completed++;
      if (!ok) failed++;
      if (completed === total) {
        if (failed === 0) {
          this.clearMoveError();
          removeSourcePlaceholders();
        } else {
          this.movingCatalog = '';
          this.loadCatalogs();
        }
      }
    };

    for (const tap of realTaps) {
      this.tapService.createOrUpdateTap({ ...tap, catalog: catalogValue }).subscribe({
        next: () => done(true),
        // Same as the single-tap move: surface a refused save (e.g. the 400 on
        // an unparseable stored cron) rather than counting it as done silently.
        error: (err) => { this.showMoveError(this.errText(err)); done(false); }
      });
    }
    for (const pipeline of source.pipelines) {
      this.pipelineService.createPipeline({ ...pipeline, catalog: catalogValue }).subscribe({
        next: () => done(true),
        error: (err) => { this.showMoveError(this.errText(err)); done(false); }
      });
    }
  }

  deletePipelineItem(name: string): void {
    const key = 'pipeline:' + name;
    this.deletingItem = key;
    this.deleteItemTarget = '';
    this.pipelineService.deletePipeline(name).subscribe({
      next: () => { this.deletingItem = ''; this.clearMoveError(); this.loadCatalogs(); },
      error: () => { this.deletingItem = ''; this.loadCatalogs(); }
    });
  }
}
