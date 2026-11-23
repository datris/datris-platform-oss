/** End of a chat turn that stopped with an `error` event, shared by every
 *  chat that streams AgentLoop events (Assistant, Ops, Configuration,
 *  Catalog, Search).
 *
 *  The server sends a `tool_result` for every tool call it dispatches before
 *  it sends `error`. A tool card still running when `error` arrives was
 *  therefore never run (for example, the chat was stopped because the user
 *  it acts for could not be verified, before the rest of that batch of tool
 *  calls was made), so it must not keep its running spinner. */

/** What a never-run tool card shows. */
export const TOOL_NOT_RUN_MESSAGE = 'Not run: the chat stopped before this tool call was made.';

/** Settle every tool card still running in `segments` as not run. */
export function settleUnrunToolCards(segments: any[]): void {
  for (const s of segments || []) {
    if (s && s.kind === 'tool' && s.status === 'running') {
      s.status = 'error';
      s.isError = true;
      if (!s.result) s.result = TOOL_NOT_RUN_MESSAGE;
    }
  }
}
