/**
 * Story: Assistant tool-call continuity across turns
 * (plans/stories/assistant-tool-call-continuity.md), review round 1:
 * cross-turn budget (HISTORY_TOTAL_CAP, newest turn first), per-turn input
 * budget, a larger cap for the discovery listings, line-break cuts, and
 * redaction of credential-like input keys.
 */
import { TestBed } from '@angular/core/testing';
import { of, Subject } from 'rxjs';

import {
  AssistantStateService,
  HISTORY_LIST_RESULT_CAP,
  HISTORY_REDACTED,
  HISTORY_RESULT_CAP,
  HISTORY_TOTAL_CAP,
  HISTORY_TURN_INPUT_CAP,
  capHistoryToolCalls,
  redactForHistory,
  toolCallsForHistory
} from './assistant-state.service';
import { AssistantService, HistoryToolCall } from '../assistant.service';

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

function size(calls: HistoryToolCall[]): number {
  return calls.reduce((n, c) => n + c.result.length + JSON.stringify(c.input).length, 0);
}

describe('Assistant history: replay budgets and redaction', () => {

  // ------------------------------------------------ listings + line cuts ---

  it('list_pipelines and list_taps results get the larger listing cap', () => {
    const big = 'x'.repeat(10000);
    for (const name of ['list_pipelines', 'list_taps']) {
      const calls = toolCallsForHistory(assistantTurn([tool('t', name, {}, big)]))!;
      expect(calls[0].result.length).withContext(name).toBe(HISTORY_LIST_RESULT_CAP);
      expect(calls[0].truncated).toBeTrue();
    }
    const other = toolCallsForHistory(assistantTurn([tool('t', 'test_tap', {}, big)]))!;
    expect(other[0].result.length).toBe(HISTORY_RESULT_CAP);
  });

  it('a listing that fits the listing cap is sent whole', () => {
    const listing = JSON.stringify({ names: ['a', 'b'], taps: Array.from({ length: 30 }, (_, i) => ({ name: 't' + i, cron: '0 6 * * *' })) }, null, 2);
    expect(listing.length).toBeGreaterThan(HISTORY_RESULT_CAP);
    expect(listing.length).toBeLessThan(HISTORY_LIST_RESULT_CAP);
    const calls = toolCallsForHistory(assistantTurn([tool('t', 'list_taps', {}, listing)]))!;
    expect(calls[0].result).toBe(listing);
    expect(calls[0].truncated).toBeFalsy();
  });

  it('a truncated result ends at the last line break in the window\'s last 20%', () => {
    const lines = Array.from({ length: 200 }, (_, i) => `  "line${i}": ${i},`).join('\n');
    const calls = toolCallsForHistory(assistantTurn([tool('t', 'test_tap', {}, lines)]))!;
    const r = calls[0].result;
    expect(r.length).toBeLessThanOrEqual(HISTORY_RESULT_CAP);
    expect(r.length).toBeGreaterThanOrEqual(Math.ceil(HISTORY_RESULT_CAP * 0.8));
    expect(lines.startsWith(r)).toBeTrue();
    expect(lines.charAt(r.length)).toBe('\n');
    expect(calls[0].truncated).toBeTrue();
  });

  it('a line break early in the window does not shorten the cut', () => {
    const text = 'head\n' + 'y'.repeat(5000);
    const calls = toolCallsForHistory(assistantTurn([tool('t', 'test_tap', {}, text)]))!;
    expect(calls[0].result.length).toBe(HISTORY_RESULT_CAP);
  });

  // ------------------------------------------------------- redaction ---

  it('credential-like input keys are redacted at any depth; name keys are kept', () => {
    const input = {
      name: 'rates_tap',
      secret_name: 'rates/creds',
      secretName: 'x',
      field_names: ['api_key'],
      password: 'p@ss',
      api_key: 'k1',
      apiKey: 'k2',
      nested: { access_token: 't', list: [{ client_secret: 's', private_key: 'pk', ok: 1 }] },
      credentials: { user: 'u', pass: 'v' },
      passwd: 'w',
      instruction: 'fetch rows'
    };
    const r = redactForHistory(input);
    expect(r.name).toBe('rates_tap');
    expect(r.secret_name).toBe('rates/creds');
    expect(r.secretName).toBe('x');
    expect(r.field_names).toEqual(['api_key']);
    expect(r.password).toBe(HISTORY_REDACTED);
    expect(r.api_key).toBe(HISTORY_REDACTED);
    expect(r.apiKey).toBe(HISTORY_REDACTED);
    expect(r.nested.access_token).toBe(HISTORY_REDACTED);
    expect(r.nested.list[0].client_secret).toBe(HISTORY_REDACTED);
    expect(r.nested.list[0].private_key).toBe(HISTORY_REDACTED);
    expect(r.nested.list[0].ok).toBe(1);
    expect(r.credentials).toBe(HISTORY_REDACTED);
    expect(r.passwd).toBe(HISTORY_REDACTED);
    expect(r.instruction).toBe('fetch rows');
    expect(input.password).withContext('the card itself is not changed').toBe('p@ss');
  });

  it('replayed inputs are redacted, including inside a truncated input', () => {
    const calls = toolCallsForHistory(assistantTurn([
      tool('t1', 'create_secret', { name: 's', values: { token: 'abc123' } }, 'ok'),
      tool('t2', 'create_tap', { password: 'hunter2', instruction: 'i'.repeat(5000) }, 'ok')
    ]))!;
    expect(calls[0].input).toEqual({ name: 's', values: { token: HISTORY_REDACTED } });
    expect(JSON.stringify(calls)).not.toContain('abc123');
    expect(JSON.stringify(calls)).not.toContain('hunter2');
  });

  // --------------------------------------------------- per-turn input ---

  it('inputs past the per-turn input budget are omitted', () => {
    const segs = Array.from({ length: 10 }, (_, i) => tool('t' + i, 'create_tap', { instruction: 'i'.repeat(3000) }, 'ok'));
    const calls = toolCallsForHistory(assistantTurn(segs))!;
    const inputChars = calls.reduce((n, c) => n + JSON.stringify(c.input).length, 0);
    expect(inputChars).toBeLessThanOrEqual(HISTORY_TURN_INPUT_CAP + 64);
    expect(calls[calls.length - 1].input).toEqual({ _inputOmitted: true });
    expect(calls[0].input._truncatedInput).toBeDefined();
  });

  // ------------------------------------------------------ total cap ---

  it('capHistoryToolCalls keeps the newest turns and degrades older ones to name-only', () => {
    const turn = (n: number): HistoryToolCall[] =>
      Array.from({ length: 4 }, (_, i) => ({ id: `c${n}_${i}`, name: 'test_tap', input: { tap: 'x' }, result: 'r'.repeat(1024), isError: false }));
    const perTurn: (HistoryToolCall[] | undefined)[] = [];
    for (let n = 0; n < 20; n++) { perTurn.push(undefined); perTurn.push(turn(n)); }
    capHistoryToolCalls(perTurn);
    const kept = perTurn.filter(c => !!c) as HistoryToolCall[][];
    const total = kept.reduce((n, c) => n + size(c), 0);
    expect(total).toBeLessThanOrEqual(HISTORY_TOTAL_CAP);
    // The newest turn keeps its bytes, the oldest is name-only.
    expect(kept[kept.length - 1][0].result.length).toBe(1024);
    const oldest = kept[0][0];
    expect(oldest).toEqual({ id: 'c0_0', name: 'test_tap', input: {}, result: '', isError: false, truncated: true });
    // Once a turn is degraded, every older turn is too.
    const degraded = kept.map(c => c[0].result === '');
    const firstKept = degraded.indexOf(false);
    expect(degraded.slice(firstKept).every(d => !d)).toBeTrue();
    expect(degraded.slice(0, firstKept).every(d => d)).toBeTrue();
    // Every call survives (pairing holds).
    expect(kept.every(c => c.length === 4)).toBeTrue();
  });

  it('a degraded synthetic call stays synthetic', () => {
    const perTurn: (HistoryToolCall[] | undefined)[] = [
      [{ id: 's', name: 'propose_pipeline_schema', input: { tap: 't' }, result: '', isError: false, synthetic: true }],
      [{ id: 'b', name: 'test_tap', input: {}, result: 'r'.repeat(HISTORY_TOTAL_CAP), isError: false }]
    ];
    capHistoryToolCalls(perTurn);
    expect(perTurn[0]![0].synthetic).toBeTrue();
    expect(perTurn[0]![0].input).toEqual({});
  });

  describe('through send', () => {
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

    it('a 30-turn chat sends at most HISTORY_TOTAL_CAP of replayed tool calls, newest turns intact', () => {
      const turns: any[] = [];
      for (let n = 0; n < 30; n++) {
        turns.push({ role: 'user', text: 'ask ' + n });
        turns.push(assistantTurn(Array.from({ length: 6 }, (_, i) =>
          tool(`t${n}_${i}`, 'create_tap', { instruction: 'i'.repeat(1900) }, 'r'.repeat(1024)))));
      }
      const state = TestBed.inject(AssistantStateService);
      state.turns = turns;
      state.send('next');
      const history: any[] = chatSpy.calls.mostRecent().args[0];
      const all: HistoryToolCall[] = history.flatMap(m => m.toolCalls || []);
      expect(all.length).withContext('every call still travels').toBe(180);
      expect(size(all)).toBeLessThanOrEqual(HISTORY_TOTAL_CAP);
      const last = history.filter(m => m.role === 'assistant').pop();
      expect(last.toolCalls[0].result.length).toBe(1024);
      const first = history.find(m => m.role === 'assistant');
      expect(first.toolCalls[0]).toEqual(jasmine.objectContaining({ input: {}, result: '', truncated: true }));
    });
  });
});
