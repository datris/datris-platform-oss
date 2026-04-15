import { Component, OnInit, OnDestroy, HostListener } from '@angular/core';
import { Router } from '@angular/router';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { sanitizeLabel } from '../shared/sanitize';

interface CatalogInfo {
  name: string;
  tapCount: number;
  pipelineCount: number;
  expanded?: boolean;
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
  private refreshInterval: any;

  constructor(private tapService: TapService, private pipelineService: PipelineService, private router: Router) {}

  ngOnInit(): void {
    this.loadCatalogs();
    this.refreshInterval = setInterval(() => this.loadCatalogs(), 10000);
  }

  ngOnDestroy(): void {
    if (this.refreshInterval) clearInterval(this.refreshInterval);
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

      // Preserve expanded state
      const prevExpanded = new Set(this.catalogs.filter(c => c.expanded).map(c => c.name));
      this.catalogs = Array.from(catalogMap.values()).sort((a, b) => a.name.localeCompare(b.name));
      // Add Uncataloged at the end if it has any items
      if (uncataloged.tapCount > 0 || uncataloged.pipelineCount > 0) {
        this.catalogs.push(uncataloged);
      }
      for (const cat of this.catalogs) {
        if (prevExpanded.has(cat.name)) cat.expanded = true;
      }
      this.loading = false;
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
  }

  /** Catalog names that an uncataloged item can be moved into. Excludes 'Uncataloged'. */
  moveTargets(): string[] {
    return this.catalogs.filter(c => c.name !== 'Uncataloged').map(c => c.name);
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
