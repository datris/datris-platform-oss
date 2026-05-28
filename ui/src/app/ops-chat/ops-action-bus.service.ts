import { Injectable } from '@angular/core';
import { Subject, Observable } from 'rxjs';

/** Tap-level event emitted when the Ops chat triggers an action that the
 *  Activity dashboard should reflect immediately (without waiting for the
 *  30s auto-refresh). Today only `run_tap` produces these; future ops
 *  tools that affect dashboard rows can reuse the same channel. */
export interface OpsTapAction {
  kind: 'tap-run-started';
  tapName: string;
}

/** Singleton event bus from the chat panel → dashboard. The chat panel
 *  observes its own tool_result stream and emits here when it sees an ops
 *  tool succeed; the Activity component subscribes and pushes the tap name
 *  into its `runningTaps` set so the row's button swaps to a spinner.
 *
 *  The spinner is intentionally optimistic — the next dashboard refresh
 *  (every 30s, or sooner if the user kicks one off) will overwrite the
 *  optimistic state with whatever the platform actually reports. We do
 *  NOT track "finished" events here; the data is the source of truth. */
@Injectable({ providedIn: 'root' })
export class OpsActionBus {
  private subject = new Subject<OpsTapAction>();

  events$: Observable<OpsTapAction> = this.subject.asObservable();

  emit(event: OpsTapAction): void {
    this.subject.next(event);
  }
}
