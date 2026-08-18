import { AfterViewInit, Directive, ElementRef, Input, OnDestroy } from '@angular/core';

/**
 * Makes a table's columns drag-resizable:
 *
 *   <table appResizableColumns="taps-embed">
 *
 * Every header cell except the last (which absorbs leftover space) gets an
 * invisible grip on its right edge. Dragging moves the shared edge between a
 * column and its right neighbor (zero-sum, so fixed layout honors both widths
 * exactly). Widths are written to the table's <col> elements when a colgroup
 * exists — in fixed layout <col> widths outrank header-cell widths — and to
 * the th elements otherwise. Widths persist per table key in localStorage, so
 * a layout survives reloads and applies to every table sharing the key (e.g.
 * the taps table in each catalog card).
 */
@Directive({ selector: 'table[appResizableColumns]' })
export class ResizableColumnsDirective implements AfterViewInit, OnDestroy {
  @Input('appResizableColumns') tableKey = '';

  private static readonly MinColPx = 60;

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

  /** The elements whose `width` style actually controls column sizing: the
   *  colgroup's <col>s when present (they outrank th widths in fixed layout),
   *  the header cells otherwise. */
  private widthTargets(): HTMLElement[] {
    const cols = Array.from(
      this.el.nativeElement.querySelectorAll(':scope > colgroup > col')
    ) as HTMLElement[];
    const cells = this.headerCells();
    return cols.length === cells.length ? cols : cells;
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
      const onDown = (e: MouseEvent) => this.startDrag(e, i);
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
   *  its currently rendered width so only the dragged edge moves. */
  private freezeWidths(): void {
    const table = this.el.nativeElement;
    if (table.dataset['colwidthsFrozen'] === 'true') { return; }
    const targets = this.widthTargets();
    const cells = this.headerCells();
    cells.forEach((th, i) => {
      targets[i].style.width = th.getBoundingClientRect().width + 'px';
    });
    table.style.tableLayout = 'fixed';
    table.style.width = '100%';
    table.dataset['colwidthsFrozen'] = 'true';
  }

  private startDrag(e: MouseEvent, index: number): void {
    e.preventDefault();
    e.stopPropagation();
    this.freezeWidths();
    const cells = this.headerCells();
    const targets = this.widthTargets();
    if (index + 1 >= cells.length) { return; }
    // Zero-sum resize against the next column: the dragged column grows, its
    // right neighbor shrinks, and the total stays constant — otherwise a
    // 100%-wide fixed table rescales every column and the drag is a no-op.
    const startX = e.clientX;
    const startWidth = cells[index].getBoundingClientRect().width;
    const neighborStart = cells[index + 1].getBoundingClientRect().width;
    const min = ResizableColumnsDirective.MinColPx;
    const onMove = (ev: MouseEvent) => {
      const dx = Math.max(min - startWidth, Math.min(neighborStart - min, ev.clientX - startX));
      targets[index].style.width = (startWidth + dx) + 'px';
      targets[index + 1].style.width = (neighborStart - dx) + 'px';
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
      const targets = this.widthTargets();
      widths.forEach((w, i) => {
        if (typeof w === 'number' && w > 0) { targets[i].style.width = w + 'px'; }
      });
      const table = this.el.nativeElement;
      table.style.tableLayout = 'fixed';
      table.style.width = '100%';
      table.dataset['colwidthsFrozen'] = 'true';
    } catch { /* ignore unreadable stored state */ }
  }
}
