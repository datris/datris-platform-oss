import { Injectable } from '@angular/core';

/** Compact snapshot of the Catalog page the curation assistant should reason
 *  against. DataCatalogComponent publishes this on every loadCatalogs(); the
 *  chat panel re-injects it as a leading user message on every turn (same
 *  ship-the-simple-version cadence as the Ops chat). The names let the agent
 *  move/rename specific taps and pipelines without re-listing them. */
export interface CatalogChatContext {
  catalogs: CatalogSnapshot[];
  /** Set when the user clicked "Describe to Assistant" on a specific catalog
   *  card — the agent should treat that catalog as the focus of the request. */
  focus?: { kind: 'catalog'; name: string } | null;
}

export interface CatalogSnapshot {
  name: string;          // catalog name, or 'Uncataloged'
  tapCount: number;
  pipelineCount: number;
  taps: string[];        // tap names (placeholder __catalog__ taps excluded)
  pipelines: string[];   // pipeline names
}

/** Singleton bridge between the Catalog page and the curation chat panel.
 *  DataCatalogComponent calls publish() after each load; the chat panel reads
 *  snapshot() before each user message and forwards it to the server. Kept in
 *  a root singleton so the snapshot — like the conversation — survives leaving
 *  the Catalog tab and returning. */
@Injectable({ providedIn: 'root' })
export class CatalogChatContextService {
  private current: CatalogChatContext | null = null;

  publish(ctx: CatalogChatContext): void {
    this.current = ctx;
  }

  /** Set the focused catalog without disturbing the inventory. Called by the
   *  "Describe to Assistant" button so the next turn names that catalog. */
  setFocus(name: string): void {
    if (this.current) this.current.focus = { kind: 'catalog', name };
    else this.current = { catalogs: [], focus: { kind: 'catalog', name } };
  }

  clearFocus(): void {
    if (this.current) this.current.focus = null;
  }

  snapshot(): CatalogChatContext | null {
    return this.current;
  }
}
