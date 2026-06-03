/** Per-tab persistence for chat transcripts so a browser refresh doesn't wipe
 *  the conversation. Shared by the Search chat and the build-mode Assistant —
 *  both keep `{ turns, draft }` in a root singleton state service, which
 *  survives in-app navigation but not a full page reload.
 *
 *  sessionStorage (not localStorage) is deliberate: the transcript should
 *  survive a refresh of THIS tab but not leak into other tabs or linger after
 *  the tab is closed.
 *
 *  The turns are plain serializable objects (text/tool/notice segments). We
 *  keep this `any`-typed because the two callers have slightly different Turn
 *  shapes (the Assistant adds an inline secret-request form to tool cards);
 *  the on-disk shape is whatever each service already holds. */

interface PersistedChat {
  turns: any[];
  draft: string;
}

/** Largest tool-result string we keep per tool card. Results can be big (full
 *  query dumps); capping keeps the whole transcript well under the ~5MB
 *  sessionStorage quota. The live in-memory copy is untouched — only what we
 *  write to storage is trimmed. */
const MAX_PERSISTED_RESULT = 4000;

export function loadChatState(key: string): PersistedChat | null {
  try {
    const raw = sessionStorage.getItem(key);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || !Array.isArray(parsed.turns)) return null;
    // A turn left mid-stream when the page reloaded is frozen — its SSE
    // connection is gone. Force it done so it never renders as "still
    // streaming," and make sure no card is stuck on the running spinner.
    for (const t of parsed.turns) {
      if (t && t.role === 'assistant') {
        t.done = true;
        if (Array.isArray(t.segments)) {
          for (const s of t.segments) {
            if (s && s.kind === 'tool' && s.status === 'running') {
              s.status = s.isError ? 'error' : 'ok';
            }
          }
        }
      }
    }
    return { turns: parsed.turns, draft: typeof parsed.draft === 'string' ? parsed.draft : '' };
  } catch {
    return null;
  }
}

export function saveChatState(key: string, turns: any[], draft: string): void {
  try {
    const slim = turns.map((t) => {
      if (t && t.role === 'assistant' && Array.isArray(t.segments)) {
        return {
          ...t,
          segments: t.segments.map((s: any) =>
            s && s.kind === 'tool' && typeof s.result === 'string' && s.result.length > MAX_PERSISTED_RESULT
              ? { ...s, result: s.result.slice(0, MAX_PERSISTED_RESULT) + '\n…[truncated]' }
              : s
          )
        };
      }
      return t;
    });
    sessionStorage.setItem(key, JSON.stringify({ turns: slim, draft }));
  } catch {
    // Quota exceeded or serialization failure — skip persistence rather than
    // breaking the chat.
  }
}

export function clearChatState(key: string): void {
  try {
    sessionStorage.removeItem(key);
  } catch {
    /* ignore */
  }
}
