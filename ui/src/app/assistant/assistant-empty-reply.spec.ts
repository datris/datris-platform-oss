/**
 * E2E note (plans/stories/assistant-tool-call-continuity.md): a turn the model
 * ended with no text and no tool call showed as a blank bubble. It now carries
 * a notice, and is still left out of the outbound history.
 */
import { TestBed } from '@angular/core/testing';
import { of, Subject } from 'rxjs';

import { AssistantStateService, EMPTY_REPLY_MESSAGE } from './assistant-state.service';
import { AssistantService } from '../assistant.service';

const KEY = 'assistant.chat.transcript';
const INIT = {
  toolCount: 0, toolNames: [], workflowReference: '',
  provider: 'anthropic', model: 'claude-opus-5-5', extendedThinking: false
};

describe('Assistant empty reply notice', () => {
  let stream: Subject<any>;
  let chatSpy: jasmine.Spy;

  beforeEach(() => {
    sessionStorage.removeItem(KEY);
    stream = new Subject<any>();
    chatSpy = jasmine.createSpy('chat').and.callFake(() => stream.asObservable());
    TestBed.configureTestingModule({
      providers: [
        AssistantStateService,
        { provide: AssistantService, useValue: { init: () => of(INIT), chat: chatSpy } }
      ]
    });
  });

  afterEach(() => sessionStorage.removeItem(KEY));

  function lastTurn(state: AssistantStateService): any {
    return state.turns[state.turns.length - 1];
  }

  it('a turn that ends with nothing shows the empty-reply notice and is left out of the next history', () => {
    const state = TestBed.inject(AssistantStateService);
    state.send('hello');
    stream.next({ type: 'done' });
    expect(lastTurn(state).errorMessage).toBe(EMPTY_REPLY_MESSAGE);
    stream = new Subject<any>();
    state.streaming = false;
    state.send('again');
    const history: any[] = chatSpy.calls.mostRecent().args[0];
    expect(history.map(m => m.role)).toEqual(['user', 'user']);
  });

  it('a turn with text gets no notice', () => {
    const state = TestBed.inject(AssistantStateService);
    state.send('hello');
    stream.next({ type: 'text_delta', text: 'Hi.' });
    stream.next({ type: 'done' });
    expect(lastTurn(state).errorMessage).toBe('');
  });

  it('a turn with only a tool call gets no notice', () => {
    const state = TestBed.inject(AssistantStateService);
    state.send('hello');
    stream.next({ type: 'tool_use_start', id: 't1', name: 'list_taps' });
    stream.next({ type: 'done' });
    expect(lastTurn(state).errorMessage).toBe('');
  });

  it('an error turn keeps its own message', () => {
    const state = TestBed.inject(AssistantStateService);
    state.send('hello');
    stream.next({ type: 'error', message: 'boom' });
    stream.next({ type: 'done' });
    expect(lastTurn(state).errorMessage).toBe('boom');
  });
});
