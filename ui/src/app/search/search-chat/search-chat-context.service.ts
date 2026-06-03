import { Injectable } from '@angular/core';

/** Catalog scope the conversational Search agent should prefer. `catalog` is
 *  one of: 'All' (no scoping), a named catalog, or the literal 'Uncataloged'
 *  (pipelines/taps with no catalog assigned). */
export interface SearchChatContext {
  catalog: string;
}

/** Singleton holder for the Search chat's catalog scope. The chat panel writes
 *  the dropdown selection here; the state service reads snapshot() before each
 *  user message and forwards it to the server. Kept in a root singleton so the
 *  selection — like the conversation itself — survives navigating away from the
 *  Search tab and back. */
@Injectable({ providedIn: 'root' })
export class SearchChatContextService {
  private static readonly KEY = 'search.chat.scope';

  /** 'All' means no scoping. */
  catalog = 'All';

  constructor() {
    try {
      const saved = sessionStorage.getItem(SearchChatContextService.KEY);
      if (saved) this.catalog = saved;
    } catch { /* ignore */ }
    // Persist on refresh so the chosen scope survives a reload like the
    // transcript does. (The dropdown writes `catalog` directly via ngModel.)
    window.addEventListener('beforeunload', () => {
      try { sessionStorage.setItem(SearchChatContextService.KEY, this.catalog); } catch { /* ignore */ }
    });
  }

  snapshot(): SearchChatContext {
    return { catalog: this.catalog };
  }
}
