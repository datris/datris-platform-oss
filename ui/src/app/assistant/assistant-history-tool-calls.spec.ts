/**
 * Story: Assistant tool-call continuity across turns
 * (plans/stories/assistant-tool-call-continuity.md), Acceptance 7-8.
 *
 * Each prior assistant turn's finished tool cards travel in the outbound
 * history as `ChatMessage.toolCalls: HistoryToolCall[]`
 * (`{ id, name, input, result, isError, truncated?, synthetic? }`), in
 * segment order. Results are cut at HISTORY_RESULT_CAP (1024) chars with
 * `truncated: true`; once a turn's running total passes HISTORY_TURN_CAP
 * (8192), later results go as '' + truncated. Running cards, cards with no
 * `input`, `ask_user` cards (replayed via `askUser`) and the in-progress turn
 * are skipped.
 *
 * Turns are set directly on the state service (the shapes the SSE handler
 * and chat-persistence produce), then `send` is called and the history is
 * read off `chatSpy.calls.mostRecent().args[0]`.
 */
import { TestBed } from '@angular/core/testing';
import { of, Subject } from 'rxjs';

import { AssistantStateService } from './assistant-state.service';
import { AssistantService } from '../assistant.service';

const KEY = 'assistant.chat.transcript';

const INIT = {
  toolCount: 0, toolNames: [], workflowReference: '',
  provider: 'anthropic', model: 'claude-opus-5-5', extendedThinking: false
};

function tool(id: string, name: string, input: any, result: string, extra: any = {}): any {
  return { kind: 'tool', id, name, input, result, isError: false, status: 'ok', expanded: false, ...extra };
}

function assistantTurn(segments: any[]): any {
  return { role: 'assistant', thinking: '', thinkingExpanded: false, segments, done: true, errorMessage: '' };
}

describe('Assistant history: prior tool calls travel as toolCalls', () => {
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

  function sendWith(turns: any[], text = 'next'): any[] {
    const state = TestBed.inject(AssistantStateService);
    state.turns = turns;
    state.send(text);
    expect(chatSpy).toHaveBeenCalled();
    return chatSpy.calls.mostRecent().args[0];
  }

  it('finished tool cards become toolCalls in segment order', () => {
    const history = sendWith([
      { role: 'user', text: 'get me data' },
      assistantTurn([
        tool('t1', 'list_pipelines', {}, '[{"name":"p1"}]'),
        { kind: 'text', text: 'Nothing covers it. ' },
        tool('t2', 'list_taps', { filter: 'x' }, '[]', { isError: true, status: 'error' }),
        { kind: 'text', text: 'Shall I build one?' }
      ])
    ]);
    expect(history.map(m => m.role)).toEqual(['user', 'assistant', 'user']);
    const a = history[1];
    expect(a.content).toBe('Nothing covers it. Shall I build one?');
    expect(Array.isArray(a.toolCalls)).withContext('assistant message must carry toolCalls').toBeTrue();
    expect(a.toolCalls.map((c: any) => c.name)).toEqual(['list_pipelines', 'list_taps']);
    expect(a.toolCalls[0]).toEqual(jasmine.objectContaining({ id: 't1', input: {}, result: '[{"name":"p1"}]', isError: false }));
    expect(a.toolCalls[1]).toEqual(jasmine.objectContaining({ id: 't2', input: { filter: 'x' }, result: '[]', isError: true }));
    expect(a.toolCalls[0].truncated).toBeFalsy();
    expect(history[2].content).toBe('next');
  });

  it('a tool-only turn (no text) is still sent as an assistant message with toolCalls', () => {
    const history = sendWith([
      { role: 'user', text: 'get me data' },
      assistantTurn([tool('t1', 'list_taps', {}, '[]')])
    ]);
    expect(history.map(m => m.role)).toEqual(['user', 'assistant', 'user']);
    expect(history[1].toolCalls?.length).toBe(1);
  });

  it('results are cut at 1024 chars with truncated: true', () => {
    const big = 'r'.repeat(3000);
    const history = sendWith([
      { role: 'user', text: 'a' },
      assistantTurn([tool('t1', 'list_taps', {}, big), tool('t2', 'list_pipelines', {}, 'short')])
    ]);
    const calls = history[1].toolCalls;
    expect(calls[0].result.length).toBe(1024);
    expect(calls[0].result).toBe(big.slice(0, 1024));
    expect(calls[0].truncated).toBeTrue();
    expect(calls[1].result).toBe('short');
    expect(calls[1].truncated).toBeFalsy();
  });

  it('results after the 8 KB turn cap are empty and truncated', () => {
    const segs: any[] = [];
    for (let i = 0; i < 12; i++) segs.push(tool('t' + i, 'test_tap', { tap: 'x' + i }, 'z'.repeat(1024)));
    const history = sendWith([{ role: 'user', text: 'a' }, assistantTurn(segs)]);
    const calls: any[] = history[1].toolCalls;
    expect(calls.length).toBe(12);
    // 8 full results reach 8192; everything after must be empty + truncated.
    for (let i = 0; i < 8; i++) expect(calls[i].result.length).withContext('call ' + i).toBe(1024);
    for (let i = 9; i < 12; i++) {
      expect(calls[i].result).withContext('call ' + i).toBe('');
      expect(calls[i].truncated).withContext('call ' + i).toBeTrue();
    }
    const total = calls.reduce((n, c) => n + c.result.length, 0);
    expect(total).toBeLessThanOrEqual(8192 + 1024);
  });

  it('an input over 2 KB is replaced by { _truncatedInput: <first 2 KB of the JSON> }', () => {
    const input = { instruction: 'i'.repeat(5000) };
    const history = sendWith([{ role: 'user', text: 'a' }, assistantTurn([tool('t1', 'create_tap', input, 'ok')])]);
    const sent = history[1].toolCalls[0].input;
    expect(typeof sent._truncatedInput).toBe('string');
    expect(sent._truncatedInput.length).toBeLessThanOrEqual(2048);
    expect(JSON.stringify(input).startsWith(sent._truncatedInput)).toBeTrue();
  });

  it('a running card and the in-progress turn are skipped', () => {
    const state = TestBed.inject(AssistantStateService);
    state.turns = [
      { role: 'user', text: 'a' },
      assistantTurn([tool('t1', 'list_taps', {}, '[]'), tool('t2', 'create_tap', { name: 'x' }, '', { status: 'running' })])
    ] as any;
    state.send('b');
    // The in-progress (new) turn gets a card before the next send.
    stream.next({ type: 'tool_use_start', id: 'live1', name: 'list_pipelines' });
    stream.next({ type: 'tool_use', id: 'live1', name: 'list_pipelines', input: {} });
    const first: any[] = chatSpy.calls.mostRecent().args[0];
    expect(first[1].toolCalls.map((c: any) => c.id)).toEqual(['t1']);
    expect(first.length).toBe(3);
    expect(first.flatMap((m: any) => m.toolCalls || []).some((c: any) => c.id === 'live1')).toBeFalse();
  });

  it('an ask_user card is not duplicated into toolCalls', () => {
    const questions = [{ header: 'Source', question: 'Which?', multiSelect: false, options: [{ label: 'A', description: 'a' }, { label: 'B', description: 'b' }] }];
    const history = sendWith([
      { role: 'user', text: 'a' },
      assistantTurn([
        tool('t1', 'list_taps', {}, '[]'),
        tool('ask1', 'ask_user', { questions }, '', {
          askUser: { questions, selections: { 0: ['A'] }, other: {}, submitted: true, skipped: false, summary: '' }
        })
      ])
    ], 'Source: A');
    const a = history[1];
    expect(a.askUser).toEqual({ questions, submitted: true });
    expect(a.toolCalls.map((c: any) => c.name)).toEqual(['list_taps']);
  });

  it('a schema-proposal card goes as synthetic with an empty result', () => {
    const history = sendWith([
      { role: 'user', text: 'a' },
      assistantTurn([tool('sp1', 'propose_pipeline_schema', { tap: 'x' }, '{"schema_proposal":{}}', {
        schemaProposal: { tap: 'x', typeGrid: true, sampleRowCount: 3, preview: [], fields: [], limit: 20, loading: false, submitted: true, errorMessage: '' }
      })])
    ]);
    const c = history[1].toolCalls[0];
    expect(c.name).toBe('propose_pipeline_schema');
    expect(c.synthetic).toBeTrue();
    expect(c.result).toBe('');
  });

  it('a secret-request card goes as synthetic with an empty result', () => {
    const history = sendWith([
      { role: 'user', text: 'a' },
      assistantTurn([tool('sr1', 'request_tap_secret_from_user', { secret_name: 's' }, 'raw', {
        secretRequest: { secretName: 's', fieldNames: ['k'], reason: '', mode: 'new', selectedExisting: '', fieldValues: {}, submitting: false, submitted: true, cancelled: false, errorMessage: '' }
      })])
    ]);
    const c = history[1].toolCalls[0];
    expect(c.synthetic).toBeTrue();
    expect(c.result).toBe('');
  });

  it('a turn with no text and no finished tool cards is still left out', () => {
    const history = sendWith([
      { role: 'user', text: 'a' },
      assistantTurn([tool('t1', 'list_taps', {}, '', { status: 'running' })])
    ]);
    expect(history.map(m => m.role)).toEqual(['user', 'user']);
  });

  // ---------------------------------------------- restored transcript ---

  it('a restored transcript with old-style cards sends toolCalls; a card with no input is skipped and the send succeeds', () => {
    sessionStorage.setItem(KEY, JSON.stringify({
      draft: '',
      turns: [
        { role: 'user', text: 'get me data' },
        {
          role: 'assistant', thinking: '', thinkingExpanded: false, done: true, errorMessage: '',
          segments: [
            { kind: 'tool', id: 'old1', name: 'list_pipelines', input: {}, result: '[]', isError: false, status: 'ok', expanded: false },
            { kind: 'tool', id: 'old2', name: 'list_taps', result: '[]', isError: false, status: 'ok', expanded: false },
            { kind: 'tool', id: 'old3', name: 'create_tap', input: { name: 'rates' }, result: 'created', isError: false, status: 'running', expanded: false },
            { kind: 'text', text: 'Built the tap.' }
          ]
        }
      ]
    }));
    const state = TestBed.inject(AssistantStateService);
    expect(state.turns.length).toBe(2);
    expect(() => state.send('also land it in Postgres')).not.toThrow();
    const history: any[] = chatSpy.calls.mostRecent().args[0];
    expect(history.map(m => m.role)).toEqual(['user', 'assistant', 'user']);
    // old3 was mid-stream at reload: loadChatState marks it finished, so it travels.
    expect(history[1].toolCalls.map((c: any) => c.id)).toEqual(['old1', 'old3']);
    expect(history[1].content).toBe('Built the tap.');
  });
});
