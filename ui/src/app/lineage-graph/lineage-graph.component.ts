import { Component, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';
import type { EChartsOption } from 'echarts';
import {
  LineageEdge, LineageGraph, LineageNeighborhood, LineageNode, LineageNodeType, LineageService
} from '../lineage.service';
import { AuthService } from '../auth.service';

/** Interactive lineage graph (plan L2): the whole config-derived graph
 *  rendered as a left-to-right DAG (Source → Tap → Pipeline → Dataset →
 *  Catalog), filterable by catalog / tag / name, click a node for its
 *  neighborhood, freshness and recent recorded runs. All data comes from
 *  the lineage endpoints — no query logic lives here, only layout. */

const LAYER: Record<LineageNodeType, number> = { source: 0, tap: 1, pipeline: 2, dataset: 3, catalog: 4 };
const COLOR: Record<LineageNodeType, string> = {
  source: '#94a3b8',
  tap: '#00b4ff',
  pipeline: '#a78bfa',
  dataset: '#00e5a0',
  catalog: '#fbbf24'
};
// Layout units. echarts fits the whole bounding box into the canvas, so only
// the ratio matters: ~8.5:1 keeps a ~240px layer gap next to a ~28px row gap
// once the canvas height below is sized to the tallest layer.
const LAYER_GAP = 460;
const ROW_GAP = 54;
const ROW_PX = 28;
const MIN_CHART_PX = 560;

interface Positioned { node: LineageNode; x: number; y: number; }

@Component({
  selector: 'app-lineage-graph',
  templateUrl: './lineage-graph.component.html',
  styleUrls: ['./lineage-graph.component.css'],
  standalone: false
})
export class LineageGraphComponent implements OnInit, OnDestroy {
  loading = true;
  error = '';
  graph: LineageGraph | null = null;
  chartOptions: EChartsOption | null = null;
  /** Canvas height in px, sized so rows never overlap after echarts fits the graph. */
  chartHeight = MIN_CHART_PX;

  // Filters
  catalogs: string[] = [];
  tags: string[] = [];
  catalogFilter = '';
  tagFilter = '';
  nameFilter = '';
  visibleCount = 0;

  // Selection
  selectedId = '';
  neighborhood: LineageNeighborhood | null = null;
  neighborhoodLoading = false;

  readonly legend: { type: LineageNodeType; color: string; label: string }[] = [
    { type: 'source', color: COLOR.source, label: 'Source' },
    { type: 'tap', color: COLOR.tap, label: 'Tap' },
    { type: 'pipeline', color: COLOR.pipeline, label: 'Pipeline' },
    { type: 'dataset', color: COLOR.dataset, label: 'Dataset' },
    { type: 'catalog', color: COLOR.catalog, label: 'Catalog' }
  ];

  private sub = new Subscription();

  constructor(private lineageService: LineageService,
              private route: ActivatedRoute,
              private router: Router,
              public auth: AuthService) { }

  ngOnInit(): void {
    this.sub.add(this.route.queryParamMap.subscribe(q => {
      const focus = q.get('focus') || '';
      const catalog = q.get('catalog') || '';
      if (catalog) this.catalogFilter = catalog;
      if (this.graph) {
        if (focus) this.select(focus);
        this.render();
      } else {
        this.load(focus);
      }
    }));
  }

  ngOnDestroy(): void { this.sub.unsubscribe(); }

  load(focus = ''): void {
    this.loading = true;
    this.error = '';
    this.lineageService.graph().subscribe({
      next: g => {
        this.graph = g;
        this.catalogs = Array.from(new Set(g.nodes.map(n => n.catalog).filter((c): c is string => !!c))).sort();
        this.tags = Array.from(new Set(g.nodes.flatMap(n => n.tags || []))).sort();
        this.loading = false;
        this.render();
        if (focus) this.select(focus);
      },
      error: e => {
        this.loading = false;
        this.error = e?.error?.error || e?.message || 'Could not load the lineage graph';
      }
    });
  }

  // ---------------------------------------------------------------- filters

  clearFilters(): void {
    this.catalogFilter = '';
    this.tagFilter = '';
    this.nameFilter = '';
    this.render();
  }

  hasFilters(): boolean {
    return !!(this.catalogFilter || this.tagFilter || this.nameFilter.trim());
  }

  /** Nodes to draw: the ones matching every active filter, plus everything
   *  transitively upstream and downstream of them so the sub-graph stays
   *  connected (a catalog filter still shows the sources feeding it). */
  private visibleNodes(): LineageNode[] {
    const g = this.graph;
    if (!g) return [];
    if (!this.hasFilters()) return g.nodes;
    const name = this.nameFilter.trim().toLowerCase();
    const seeds = g.nodes.filter(n =>
      (!this.catalogFilter || n.catalog === this.catalogFilter || n.id === 'catalog:' + this.catalogFilter) &&
      (!this.tagFilter || (n.tags || []).includes(this.tagFilter)) &&
      (!name || n.name.toLowerCase().includes(name))
    );
    const fwd = new Map<string, string[]>();
    const back = new Map<string, string[]>();
    for (const e of g.edges) {
      fwd.set(e.from, [...(fwd.get(e.from) || []), e.to]);
      back.set(e.to, [...(back.get(e.to) || []), e.from]);
    }
    const keep = new Set<string>(seeds.map(s => s.id));
    const walk = (start: string, next: Map<string, string[]>) => {
      const stack = [start];
      while (stack.length) {
        const cur = stack.pop() as string;
        for (const n of next.get(cur) || []) {
          if (!keep.has(n)) { keep.add(n); stack.push(n); }
        }
      }
    };
    for (const s of seeds) { walk(s.id, fwd); walk(s.id, back); }
    return g.nodes.filter(n => keep.has(n.id));
  }

  // ----------------------------------------------------------------- layout

  /** Layered DAG layout: layer by node type, order within a layer by the
   *  mean position of already-placed neighbors (one left-to-right pass,
   *  one right-to-left pass) so edges mostly run straight across. */
  private layout(nodes: LineageNode[], edges: LineageEdge[]): Positioned[] {
    const ids = new Set(nodes.map(n => n.id));
    const es = edges.filter(e => ids.has(e.from) && ids.has(e.to));
    const layers: LineageNode[][] = [[], [], [], [], []];
    for (const n of nodes) layers[LAYER[n.type] ?? 2].push(n);
    for (const l of layers) l.sort((a, b) => a.name.localeCompare(b.name));

    const pos = new Map<string, number>();
    const ins = new Map<string, string[]>();
    const outs = new Map<string, string[]>();
    for (const e of es) {
      ins.set(e.to, [...(ins.get(e.to) || []), e.from]);
      outs.set(e.from, [...(outs.get(e.from) || []), e.to]);
    }
    const place = (layer: LineageNode[]) => layer.forEach((n, i) => pos.set(n.id, i));
    const bary = (n: LineageNode, nbrs: Map<string, string[]>): number => {
      const ps = (nbrs.get(n.id) || []).map(id => pos.get(id)).filter((p): p is number => p !== undefined);
      return ps.length ? ps.reduce((a, b) => a + b, 0) / ps.length : pos.get(n.id) ?? 0;
    };
    layers.forEach(place);
    for (let i = 1; i < layers.length; i++) {
      layers[i].sort((a, b) => bary(a, ins) - bary(b, ins) || a.name.localeCompare(b.name));
      place(layers[i]);
    }
    for (let i = layers.length - 2; i >= 0; i--) {
      layers[i].sort((a, b) => bary(a, outs) - bary(b, outs) || a.name.localeCompare(b.name));
      place(layers[i]);
    }

    const tallest = Math.max(1, ...layers.map(l => l.length));
    const out: Positioned[] = [];
    layers.forEach((layer, li) => {
      const offset = ((tallest - layer.length) * ROW_GAP) / 2;
      layer.forEach((n, i) => out.push({ node: n, x: li * LAYER_GAP, y: offset + i * ROW_GAP }));
    });
    return out;
  }

  render(): void {
    const g = this.graph;
    if (!g) return;
    const nodes = this.visibleNodes();
    this.visibleCount = nodes.length;
    const placed = this.layout(nodes, g.edges);
    const rows = Math.max(1, ...Object.values(placed.reduce((m, p) => { m[p.x] = (m[p.x] || 0) + 1; return m; }, {} as Record<number, number>)));
    this.chartHeight = Math.max(MIN_CHART_PX, rows * ROW_PX + 120);
    const ids = new Set(nodes.map(n => n.id));
    const selected = this.selectedId;
    const related = new Set<string>();
    if (selected && this.neighborhood) {
      related.add(selected);
      for (const n of this.neighborhood.upstream) related.add(n.id);
      for (const n of this.neighborhood.downstream) related.add(n.id);
    }
    const dim = (id: string) => related.size > 0 && !related.has(id);

    const data = placed.map(p => {
      const n = p.node;
      const color = COLOR[n.type] || COLOR.pipeline;
      return {
        id: n.id,
        name: n.id,
        value: n.name,
        x: p.x,
        y: p.y,
        symbol: n.type === 'source' ? 'circle' : n.type === 'catalog' ? 'diamond' : 'roundRect',
        symbolSize: n.type === 'source' ? 12 : n.type === 'catalog' ? 18 : [14, 14],
        itemStyle: {
          color: n.historical ? 'transparent' : color,
          borderColor: color,
          borderWidth: n.historical ? 1.5 : (n.id === selected ? 3 : 0),
          borderType: n.historical ? 'dashed' : 'solid',
          opacity: dim(n.id) ? 0.25 : 1
        },
        label: {
          show: true,
          position: 'right',
          formatter: this.shortLabel(n),
          color: dim(n.id) ? '#64748b' : '#e2e8f0',
          fontSize: 11,
          fontWeight: n.id === selected ? 'bold' : 'normal'
        }
      };
    });

    const links = g.edges.filter(e => ids.has(e.from) && ids.has(e.to)).map(e => ({
      source: e.from,
      target: e.to,
      lineStyle: {
        type: e.historical ? 'dashed' : 'solid',
        color: e.historical ? '#f59e0b' : 'rgba(148, 163, 184, 0.55)',
        width: related.has(e.from) && related.has(e.to) ? 2 : 1.2,
        curveness: 0.15,
        opacity: related.size > 0 && !(related.has(e.from) && related.has(e.to)) ? 0.2 : 1
      }
    }));

    this.chartOptions = {
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'item',
        backgroundColor: '#0f1525',
        borderColor: 'rgba(99, 179, 237, 0.25)',
        textStyle: { color: '#f0f4ff', fontSize: 12 },
        formatter: (p: any) => {
          if (p.dataType === 'edge') return p.data.lineStyle?.type === 'dashed' ? 'historical' : '';
          const n = nodes.find(x => x.id === p.data.id);
          if (!n) return '';
          const bits = [`<b>${this.escape(n.name)}</b>`, n.type + (n.historical ? ' · historical' : '')];
          if (n.catalog) bits.push('catalog: ' + this.escape(n.catalog));
          if (n.tags?.length) bits.push('tags: ' + n.tags.map(t => this.escape(t)).join(', '));
          return bits.join('<br/>');
        }
      },
      animation: false,
      series: [{
        type: 'graph',
        layout: 'none',
        roam: true,
        zoom: 1,
        center: undefined,
        edgeSymbol: ['none', 'arrow'],
        edgeSymbolSize: 7,
        draggable: false,
        data: data as any,
        links: links as any,
        emphasis: { focus: 'adjacency', lineStyle: { width: 2.5 } },
        lineStyle: { curveness: 0.15 }
      }]
    };
  }

  private shortLabel(n: LineageNode): string {
    const name = n.type === 'dataset' ? n.name.replace(/^[a-z]+:/, '') : n.name;
    return name.length > 30 ? name.slice(0, 28) + '…' : name;
  }

  private escape(s: string): string {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  // -------------------------------------------------------------- selection

  onChartClick(ev: any): void {
    if (ev?.dataType === 'node' && ev.data?.id) this.select(ev.data.id);
  }

  select(id: string): void {
    const node = this.graph?.nodes.find(n => n.id === id);
    if (!node) return;
    this.selectedId = id;
    this.neighborhood = null;
    this.neighborhoodLoading = true;
    this.lineageService.neighborhood(node.type, node.name, { runs: 10 }).subscribe({
      next: n => { this.neighborhood = n; this.neighborhoodLoading = false; this.render(); },
      error: () => { this.neighborhoodLoading = false; this.render(); }
    });
    this.render();
  }

  clearSelection(): void {
    this.selectedId = '';
    this.neighborhood = null;
    this.render();
  }

  selectedNode(): LineageNode | null {
    return this.graph?.nodes.find(n => n.id === this.selectedId) || null;
  }

  typeColor(type: string): string {
    return COLOR[type as LineageNodeType] || COLOR.pipeline;
  }

  displayName(n: LineageNode): string {
    return n.type === 'dataset' ? n.name.replace(/^[a-z]+:/, '') : n.name;
  }

  datasetKind(n: LineageNode): string {
    const m = /^([a-z]+):/.exec(n.name);
    return m ? m[1] : '';
  }

  upstreamOf(nb: LineageNeighborhood): LineageNode[] {
    return nb.upstream.filter(n => n.id !== nb.node.id);
  }

  downstreamOf(nb: LineageNeighborhood): LineageNode[] {
    return nb.downstream.filter(n => n.id !== nb.node.id);
  }

  runDetailLink(run: { runId: string; pipeline?: string }): any[] {
    return ['/pipeline', run.runId, run.pipeline || this.neighborhood?.node.name || ''];
  }

  formatWhen(iso?: string): string {
    if (!iso) return '';
    const d = new Date(iso);
    return isNaN(d.getTime()) ? iso : d.toLocaleString();
  }

  formatDuration(ms: number): string {
    if (!ms || ms < 0) return '';
    if (ms < 1000) return ms + ' ms';
    const s = Math.round(ms / 1000);
    return s < 60 ? s + ' s' : Math.floor(s / 60) + 'm ' + (s % 60) + 's';
  }

  trackById(_: number, n: LineageNode): string { return n.id; }
}
