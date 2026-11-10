/** Automatic retry of a failed model call, shared by every chat that streams
 *  AgentLoop events (Assistant, Ops, Configuration, Catalog, Search).
 *
 *  The server retries a transient provider error (or an overload) on its own
 *  and sends `retrying {attempt, max, reason}` before each retry. What the
 *  failed attempt already streamed — text, thinking, tool_use cards — is void
 *  (no tool from it ran: tools execute only after a complete response), so the
 *  turn drops back to where the current model call started and shows a
 *  transient status line until the retried call streams something. */

/** Where the current model call's output starts within a turn. */
export interface AttemptMark {
  segments: number;
  thinking: number;
}

/** The parts of a chat turn the retry handling touches. */
interface RetryableTurn {
  thinking: string;
  segments: any[];
  attemptMark?: AttemptMark;
}

/** The retry status is a notice segment flagged so it can be removed. */
export interface RetryNoticeSegment {
  kind: 'notice';
  message: string;
  retryStatus: true;
}

/** `iteration_start`: a new model call begins here. */
export function markAttemptStart(turn: RetryableTurn): void {
  turn.attemptMark = { segments: turn.segments.length, thinking: turn.thinking.length };
}

/** `retrying`: discard the failed attempt's output and show the status. */
export function discardFailedAttempt(turn: RetryableTurn, attempt: number, max: number, reason?: string): void {
  const mark = turn.attemptMark || { segments: turn.segments.length, thinking: turn.thinking.length };
  turn.segments.splice(mark.segments);
  turn.thinking = turn.thinking.slice(0, mark.thinking);
  const what = reason === 'provider overloaded' ? 'The AI provider is busy' : 'Connection problem';
  const status: RetryNoticeSegment = { kind: 'notice', message: `${what}, retrying (${attempt} of ${max})…`, retryStatus: true };
  turn.segments.push(status);
}

/** Any later event: the retried call is streaming (or the turn ended), so the
 *  status line goes. */
export function clearRetryStatus(turn: RetryableTurn): void {
  if (!turn.segments.some(s => s && s.retryStatus)) return;
  for (let i = turn.segments.length - 1; i >= 0; i--) {
    if (turn.segments[i] && turn.segments[i].retryStatus) turn.segments.splice(i, 1);
  }
}

/** One handler for all chats: call first in handleEvent. Returns true when
 *  the event was a `retrying` event (fully handled). */
export function handleRetryEvent(evt: { type: string } & any, turn: RetryableTurn): boolean {
  if (evt.type === 'retrying') {
    discardFailedAttempt(turn, evt.attempt, evt.max, evt.reason);
    return true;
  }
  clearRetryStatus(turn);
  if (evt.type === 'iteration_start') markAttemptStart(turn);
  return false;
}
