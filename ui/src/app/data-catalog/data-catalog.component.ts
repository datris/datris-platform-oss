import { Component, OnInit, OnDestroy } from '@angular/core';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';

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
  private refreshInterval: any;

  constructor(private tapService: TapService, private pipelineService: PipelineService) {}

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

  private sanitizeName(name: string): string {
    return name.toLowerCase().trim()
      .replace(/\s+/g, '_').replace(/[^a-z0-9_]/g, '')
      .replace(/_+/g, '_').replace(/^_|_$/g, '');
  }

  createCatalog(): void {
    const name = this.sanitizeName(this.newCatalogName);
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

    // Always delete the placeholder tap too
    const placeholderName = '__catalog__' + catalog.name;
    let remaining = catalog.taps.length + catalog.pipelines.length + 1; // +1 for placeholder

    const done = () => {
      remaining--;
      if (remaining <= 0) {
        catalog.deleting = false;
        this.loadCatalogs();
      }
    };

    // Delete placeholder tap
    this.tapService.deleteTap(placeholderName).subscribe({ next: done, error: done });

    for (const tap of catalog.taps) {
      this.tapService.deleteTap(tap.name).subscribe({ next: done, error: done });
    }
    for (const pipeline of catalog.pipelines) {
      this.pipelineService.deletePipeline(pipeline.name).subscribe({ next: done, error: done });
    }
  }
}
