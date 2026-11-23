/**
 * Story: A chat stops when its user can no longer be verified. A run the
 * server stops ends with the failing tool's `tool_result`, then one `error`
 * and no `done`. Every chat shows the message, ends the turn, and settles
 * tool cards for calls that were never made instead of leaving them running.
 */
import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';

import { AssistantStateService } from '../assistant/assistant-state.service';
import { AssistantService } from '../assistant.service';
import { CatalogAssistantStateService } from '../catalog-chat/catalog-assistant-state.service';
import { CatalogAssistantService } from '../catalog-chat/catalog-assistant.service';
import { ConfigAssistantStateService } from '../config-chat/config-assistant-state.service';
import { ConfigAssistantService } from '../config-chat/config-assistant.service';
import { OpsAssistantStateService } from '../ops-chat/ops-assistant-state.service';
import { OpsAssistantService } from '../ops-chat/ops-assistant.service';
import { SearchChatStateService } from '../search/search-chat/search-chat-state.service';
import { SearchChatService } from '../search/search-chat/search-chat.service';
import { settleUnrunToolCards, TOOL_NOT_RUN_MESSAGE } from './chat-stream-end';

const STOPPED = 'This chat was stopped: the user it was acting for could not be verified. Sign in again to continue.';

const CHATS: { name: string; state: any; api: any }[] = [
  { name: 'Assistant', state: AssistantStateService, api: AssistantService },
  { name: 'Ops', state: OpsAssistantStateService, api: OpsAssistantService },
  { name: 'Configuration', state: ConfigAssistantStateService, api: ConfigAssistantService },
  { name: 'Catalog', state: CatalogAssistantStateService, api: CatalogAssistantService },
  { name: 'Search', state: SearchChatStateService, api: SearchChatService }
];

describe('settleUnrunToolCards', () => {
  it('settles only running tool cards and keeps a result that arrived', () => {
    const segs: any[] = [
      { kind: 'tool', id: 'a', status: 'running', result: '', isError: false },
      { kind: 'tool', id: 'b', status: 'ok', result: 'done', isError: false },
      { kind: 'tool', id: 'c', status: 'error', result: 'failed', isError: true },
      { kind: 'text', text: 'hi' }
    ];
    settleUnrunToolCards(segs);
    expect(segs[0]).toEqual(jasmine.objectContaining({ status: 'error', isError: true, result: TOOL_NOT_RUN_MESSAGE }));
    expect(segs[1]).toEqual(jasmine.objectContaining({ status: 'ok', result: 'done' }));
    expect(segs[2]).toEqual(jasmine.objectContaining({ status: 'error', result: 'failed' }));
    expect(segs[3]).toEqual({ kind: 'text', text: 'hi' });
  });
});

for (const chat of CHATS) {
  describe(`${chat.name} chat: a run stopped with error and no done`, () => {
    let state: any;
    let stream: Subject<any>;

    beforeEach(() => {
      sessionStorage.clear();
      stream = new Subject<any>();
      TestBed.configureTestingModule({
        providers: [{ provide: chat.api, useValue: { chat: () => stream.asObservable() } }]
      });
      state = TestBed.inject(chat.state);
    });

    afterEach(() => sessionStorage.clear());

    it('shows the message, ends the turn, and settles tool cards that never ran', () => {
      state.send('list my pipelines');
      stream.next({ type: 'iteration_start' });
      stream.next({ type: 'tool_use_start', id: 't1', name: 'list_pipelines' });
      stream.next({ type: 'tool_use', id: 't1', name: 'list_pipelines', input: {} });
      stream.next({ type: 'tool_use_start', id: 't2', name: 'list_taps' });
      stream.next({ type: 'tool_use', id: 't2', name: 'list_taps', input: {} });
      stream.next({ type: 'tool_result', id: 't1', name: 'list_pipelines', result: 'Tool failed', isError: true });
      stream.next({ type: 'error', message: STOPPED });
      stream.complete();

      const turn = state.turns[state.turns.length - 1];
      expect(turn.errorMessage).toBe(STOPPED);
      expect(turn.done).toBeTrue();
      expect(state.streaming).toBeFalse();
      const cards = turn.segments.filter((s: any) => s.kind === 'tool');
      expect(cards.map((c: any) => c.status)).toEqual(['error', 'error']);
      expect(cards[0].result).toBe('Tool failed');
      expect(cards[1].result).toBe(TOOL_NOT_RUN_MESSAGE);
      expect(cards.some((c: any) => c.status === 'running')).toBeFalse();
    });

    it('a run that ends with done keeps its cards as they were', () => {
      state.send('list my pipelines');
      stream.next({ type: 'iteration_start' });
      stream.next({ type: 'tool_use_start', id: 't1', name: 'list_pipelines' });
      stream.next({ type: 'tool_use', id: 't1', name: 'list_pipelines', input: {} });
      stream.next({ type: 'tool_result', id: 't1', name: 'list_pipelines', result: '{"count":0}', isError: false });
      stream.next({ type: 'text_delta', text: 'None yet.' });
      stream.next({ type: 'done' });
      stream.complete();

      const turn = state.turns[state.turns.length - 1];
      expect(turn.errorMessage).toBe('');
      const cards = turn.segments.filter((s: any) => s.kind === 'tool');
      expect(cards.map((c: any) => c.status)).toEqual(['ok']);
      expect(cards[0].result).toBe('{"count":0}');
    });
  });
}
