import { AfterViewInit, Directive, ElementRef, Input, OnDestroy } from '@angular/core';

/**
 * Makes a table's columns drag-resizable:
 *
 *   <table appResizableColumns="taps-embed">
 *
 * Every header cell except the last (which absorbs leftover space) gets an
 * invisible grip on its right edge; dragging it sets explicit pixel widths,
 * switching the table to fixed layout on first use. Widths persist per table
 * key in localStorage, so a layout survives reloads and applies to every
 * table sharing the key (e.g. the taps table in each catalog card).
 */
@Directive({ selector: 'table[appResizableColumns]' })
export class ResizableColumnsDirective implements AfterViewInit, OnDestroy {
  @Input('appResizableColumns') tableKey = '';

  private cleanups: Array<() => void> = [];

  constructor(private el: ElementRef<HTMLTableElement>) { }

  private get storageKey(): string {
    return 'datris-colwidths-' + (this.tableKey || 'table');
  }

  ngAfterViewInit(): void {
    // Defer one tick so header cells rendered by structural directives exist.
    setTimeout(() => this.init());
  }

  ngOnDestroy(): void {
    this.cleanups.forEach(fn => fn());
    this.cleanups = [];
  }

  private headerCells(): HTMLTableCellElement[] {
    const row = this.el.nativeElement.tHead?.rows?.[0];
    return row ? Array.from(row.cells) : [];
  }

  private init(): void {
    const cells = this.headerCells();
    if (cells.length === 0) { return; }
    this.restore(cells);
    cells.forEach((th, i) => {
      if (i === cells.length - 1) { return; }
      th.style.position = 'relative';
      const grip = document.createElement('span');
      grip.style.cssText =
        'position:absolute;top:0;right:-4px;width:8px;height:100%;cursor:col-resize;user-select:none;z-index:2;';
      const onEnter = () => { grip.style.background = 'rgba(0, 229, 160, 0.35)'; };
      const onLeave = () => { grip.style.background = 'transparent'; };
      const onDown = (e: MouseEvent) => this.startDrag(e, th);
      grip.addEventListener('mouseenter', onEnter);
      grip.addEventListener('mouseleave', onLeave);
      grip.addEventListener('mousedown', onDown);
      th.appendChild(grip);
      this.cleanups.push(() => {
        grip.removeEventListener('mouseenter', onEnter);
        grip.removeEventListener('mouseleave', onLeave);
        grip.removeEventListener('mousedown', onDown);
      });
    });
  }

  /** Before the first drag the browser owns the layout; pin every column at
   *  its current width so only the dragged one moves. */
  private freezeWidths(): void {
    const table = this.el.nativeElement;
    if (table.style.tableLayout === 'fixed') { return; }
    this.headerCells().forEach(th => {
      th.style.width = th.getBoundingClientRect().width + 'px';
    });
    table.style.tableLayout = 'fixed';
    table.style.width = '100%';
  }

  private static readonly MinColPx = 60;

  private startDrag(e: MouseEvent, th: HTMLTableCellElement): void {
    e.preventDefault();
    e.stopPropagation();
    this.freezeWidths();
    // Zero-sum resize against the next column: with every column pinned and
    // the table at fixed layout / 100% width, growing one column alone just
    // makes the browser rescale everything back — visually a no-op. Moving
    // the shared edge (this column grows, its right neighbor shrinks) keeps
    // the total constant, so the browser honors both widths exactly.
    const neighbor = th.nextElementSibling as HTMLTableCellElement | null;
    if (!neighbor) { return; }
    const startX = e.clientX;
    const startWidth = th.getBoundingClientRect().width;
    const neighborStart = neighbor.getBoundingClientRect().width;
    const min = ResizableColumnsDirective.MinColPx;
    const onMove = (ev: MouseEvent) => {
      const dx = Math.max(min - startWidth, Math.min(neighborStart - min, ev.clientX - startX));
      th.style.width = (startWidth + dx) + 'px';
      neighbor.style.width = (neighborStart - dx) + 'px';
    };
    const onUp = () => {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      document.body.style.cursor = '';
      this.save();
    };
    document.body.style.cursor = 'col-resize';
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  }

  private save(): void {
    try {
      const widths = this.headerCells().map(th => Math.round(th.getBoundingClientRect().width));
      localStorage.setItem(this.storageKey, JSON.stringify(widths));
    } catch { /* storage unavailable — resizing still works for the session */ }
  }

  private restore(cells: HTMLTableCellElement[]): void {
    try {
      const raw = localStorage.getItem(this.storageKey);
      if (!raw) { return; }
      const widths: number[] = JSON.parse(raw);
      // A column-count mismatch means the table changed shape since the save;
      // stale widths would land on the wrong columns, so start fresh.
      if (!Array.isArray(widths) || widths.length !== cells.length) { return; }
      cells.forEach((th, i) => {
        if (typeof widths[i] === 'number' && widths[i] > 0) { th.style.width = widths[i] + 'px'; }
      });
      const table = this.el.nativeElement;
      table.style.tableLayout = 'fixed';
      table.style.width = '100%';
    } catch { /* ignore unreadable stored state */ }
  }
}
