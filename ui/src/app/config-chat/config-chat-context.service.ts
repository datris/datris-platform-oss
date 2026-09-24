import { Injectable } from '@angular/core';

/** What the configuration assistant should know about the page: only the
 *  sub-tab that is showing. No settings snapshot — the agent reads current
 *  values through its own tools, so nothing sensitive rides along with every
 *  turn. */
export interface ConfigChatContext {
  tab: string;
}

/** Singleton bridge between the Configuration page and its chat panel.
 *  ConfigurationComponent calls publish() whenever the sub-tab changes; the
 *  state service reads snapshot() before each send and forwards it to the
 *  server. Root-scoped so the value survives leaving the tab and returning. */
@Injectable({ providedIn: 'root' })
export class ConfigChatContextService {
  private current: ConfigChatContext | null = null;

  publish(ctx: ConfigChatContext): void {
    this.current = ctx;
  }

  snapshot(): ConfigChatContext | null {
    return this.current;
  }
}
