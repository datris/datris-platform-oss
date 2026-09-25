/**
 * Story: Configuration chat, UI: panel, transport, state, context, host wiring
 * (plans/stories/config-chat-ui-panel.md), Acceptance bullet 1.
 *
 * ConfigAssistantStateService is the singleton behind the Configuration
 * side panel: it owns the chat sessionId (rotated by newChat so stale
 * confirmation tokens die with the transcript), forwards the current sub-tab
 * context on every send, emits changed$ {tab} after a completed mutating
 * config tool, and stores confirm_request / secret_request payloads on the
 * matching tool card for the cards story to render.
 */
import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';

import { ConfigAssistantStateService } from './config-assistant-state.service';
import { ConfigAssistantService } from './config-assistant.service';
import { ConfigChatContextService } from './config-chat-context.service';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

describe('ConfigAssistantStateService', () => {
  let state: ConfigAssistantStateService;
  let context: ConfigChatContextService;
  let chatSpy: jasmine.Spy;
  let stream: Subject<any>;

  beforeEach(() => {
    stream = new Subject<any>();
    chatSpy = jasmine.createSpy('chat').and.callFake(() => stream.asObservable());

    TestBed.configureTestingModule({
      providers: [
        { provide: ConfigAssistantService, useValue: { chat: chatSpy } }
      ]
    });
    state = TestBed.inject(ConfigAssistantStateService);
    context = TestBed.inject(ConfigChatContextService);
  });

  /** Push SSE-shaped events into the open stream (typed loosely so the spec
   *  does not depend on how the AssistantEvent union is extended). */
  function emit(evt: any): void {
    stream.next(evt);
  }

  function lastCard(id: string): any {
    const turn: any = state.turns[state.turns.length - 1];
    return turn.segments.find((s: any) => s.kind === 'tool' && s.id === id);
  }

  function runTool(id: string, name: string, result: string, isError = false): void {
    emit({ type: 'tool_use_start', id, name });
    emit({ type: 'tool_use', id, name, input: {} });
    emit({ type: 'tool_result', id, name, result, isError });
  }

  it('sessionId is a UUID and newChat rotates it', () => {
    const first = state.sessionId;
    expect(first).toMatch(UUID_RE);

    state.newChat();

    expect(state.sessionId).toMatch(UUID_RE);
    expect(state.sessionId).not.toBe(first);
  });

  it('send posts context {tab} and sessionId', () => {
    context.publish({ tab: 'secrets' });

    state.send('What secrets exist?');

    expect(chatSpy).toHaveBeenCalledTimes(1);
    const [messages, ctx, sessionId] = chatSpy.calls.mostRecent().args;
    expect(messages).toEqual([{ role: 'user', content: 'What secrets exist?' }]);
    expect(ctx).toEqual({ tab: 'secrets' });
    expect(sessionId).toBe(state.sessionId);
    expect(sessionId).toMatch(UUID_RE);
  });

  it("changed$ emits {tab:'users'} on ok tool_result for set_user_role", () => {
    const seen: Array<{ tab: string }> = [];
    state.changed$.subscribe(c => seen.push(c));

    state.send('Make bob an editor');
    runTool('t1', 'set_user_role', JSON.stringify({ username: 'bob', role: 'editor' }));

    expect(seen).toEqual([{ tab: 'users' }]);
  });

  it('changed$ does not emit for needs_confirmation, secret_request, error, or read tools', () => {
    const seen: Array<{ tab: string }> = [];
    state.changed$.subscribe(c => seen.push(c));

    state.send('Do several things');
    // Mutating tool proposed, not yet confirmed.
    runTool('a', 'set_user_role',
      JSON.stringify({ status: 'needs_confirmation', summary: 'Change bob to editor', token: 'tok-1' }));
    // Mutating tool that opened the secret form instead of writing.
    runTool('b', 'put_secret',
      JSON.stringify({ status: 'secret_request', secretName: 'tap-x', fieldNames: ['apiKey'], reason: 'r' }));
    // Mutating tool that failed.
    runTool('c', 'delete_user', 'User not found', true);
    // Read tool that succeeded.
    runTool('d', 'list_users', JSON.stringify([{ username: 'bob', role: 'editor' }]));
    runTool('e', 'get_ai_providers', JSON.stringify({ codegen: { provider: 'p', model: 'm' } }));

    expect(seen).toEqual([]);
  });

  it('confirm_request attaches {summary, token, decided:false} to the card', () => {
    state.send('Change the codegen slot');
    emit({ type: 'tool_use_start', id: 'c1', name: 'set_ai_provider_slot' });
    emit({ type: 'tool_use', id: 'c1', name: 'set_ai_provider_slot', input: { slot: 'codegen' } });
    emit({ type: 'tool_result', id: 'c1', name: 'set_ai_provider_slot',
      result: JSON.stringify({ status: 'needs_confirmation' }), isError: false });
    emit({ type: 'confirm_request', id: 'c1', tool: 'set_ai_provider_slot',
      summary: 'Set codegen to provider p, model m', token: 'tok-abc' });

    const card = lastCard('c1');
    expect(card).toBeDefined();
    expect(card.confirm).toEqual(jasmine.objectContaining({
      summary: 'Set codegen to provider p, model m',
      token: 'tok-abc',
      decided: false
    }));
  });

  it('secret_request attaches fieldValues keyed by fieldNames', () => {
    state.send('Add a secret for a new tap');
    emit({ type: 'tool_use_start', id: 's1', name: 'put_secret' });
    emit({ type: 'tool_use', id: 's1', name: 'put_secret', input: { name: 'tap-weather' } });
    emit({ type: 'secret_request', id: 's1', secretName: 'tap-weather',
      fieldNames: ['apiKey', 'baseUrl'], reason: 'Weather API credentials' });

    const card = lastCard('s1');
    expect(card).toBeDefined();
    expect(card.secretRequest).toBeDefined();
    expect(card.secretRequest.secretName).toBe('tap-weather');
    expect(card.secretRequest.fieldNames).toEqual(['apiKey', 'baseUrl']);
    expect(card.secretRequest.fieldValues).toEqual({ apiKey: '', baseUrl: '' });
  });
});

/**
 * Story: Configuration chat, UI: confirm cards, secret form, show-once values,
 * sub-tab refresh (plans/stories/config-chat-ui-cards.md), Acceptance bullet 1.
 *
 * Pure-state half of the cards story: decide(card, ok) turns a proposal into
 * the confirm / cancel follow-up turn exactly once; showOnce(card) surfaces a
 * new user's temporary password or a new API key's value from the tool result
 * and keeps it until newChat(); requestShow(tab) / notifyChanged(tab) drive the
 * "Show me" link and sub-tab refresh.
 *
 * The new members are reached through an `any` alias so a missing member fails
 * its own case at runtime instead of breaking compilation of the whole suite.
 *
 * NOTE (grep acceptance, not a test): the bracket-confirm and bracket-cancel
 * token strings must appear exactly once each in the non-spec
 * ui/src/app/config-chat/*.ts sources. This spec builds them from pieces
 * (OPEN + 'confirm ') in assertions; only the two Acceptance-mandated test
 * names below spell them out.
 */
describe('ConfigAssistantStateService — confirm cards, show-once, show me', () => {
  const OPEN = '[';
  let state: ConfigAssistantStateService;
  let s: any;
  let chatSpy: jasmine.Spy;
  let stream: Subject<any>;

  beforeEach(() => {
    stream = new Subject<any>();
    chatSpy = jasmine.createSpy('chat').and.callFake(() => stream.asObservable());

    TestBed.configureTestingModule({
      providers: [
        { provide: ConfigAssistantService, useValue: { chat: chatSpy } }
      ]
    });
    state = TestBed.inject(ConfigAssistantStateService);
    s = state;
  });

  function emit(evt: any): void {
    stream.next(evt);
  }

  /** Close the current turn the way the server does, and arm a fresh stream
   *  for the next send. */
  function finishTurn(): void {
    emit({ type: 'done' });
    stream.complete();
    stream = new Subject<any>();
  }

  function cardById(id: string): any {
    for (const t of state.turns as any[]) {
      if (t.role !== 'assistant') continue;
      const c = t.segments.find((seg: any) => seg.kind === 'tool' && seg.id === id);
      if (c) return c;
    }
    return undefined;
  }

  /** Last user message the most recent chat() call carried. */
  function lastSentUserText(): string {
    const [messages] = chatSpy.calls.mostRecent().args;
    const users = (messages as Array<{ role: string; content: string }>).filter(m => m.role === 'user');
    return users[users.length - 1].content;
  }

  /** A turn that ends with a set_data_sources_fragment proposal awaiting
   *  Confirm / Cancel. Returns the card. */
  function proposal(token = 'tok-abc'): any {
    state.send('Turn the data sources fragment off');
    emit({ type: 'tool_use_start', id: 'p1', name: 'set_data_sources_fragment' });
    emit({ type: 'tool_use', id: 'p1', name: 'set_data_sources_fragment', input: { enabled: false } });
    emit({ type: 'tool_result', id: 'p1', name: 'set_data_sources_fragment',
      result: JSON.stringify({ status: 'needs_confirmation', token }), isError: false });
    emit({ type: 'confirm_request', id: 'p1', tool: 'set_data_sources_fragment',
      summary: 'Disable the data sources prompt fragment', token });
    finishTurn();
    const card = cardById('p1');
    expect(card?.confirm?.decided).toBeFalse();
    return card;
  }

  /** A settled tool card with the given name / result. */
  function toolCard(id: string, name: string, result: any, isError = false): any {
    state.send('Do ' + name);
    const text = typeof result === 'string' ? result : JSON.stringify(result);
    emit({ type: 'tool_use_start', id, name });
    emit({ type: 'tool_use', id, name, input: {} });
    emit({ type: 'tool_result', id, name, result: text, isError });
    finishTurn();
    return cardById(id);
  }

  it('decide(true) marks the card decided and sends [confirm <token>]', () => {
    const card = proposal('tok-abc');
    const before = chatSpy.calls.count();

    s.decide(card, true);

    expect(card.confirm.decided).toBeTrue();
    expect(card.confirm.decision).toBe('confirmed');
    expect(chatSpy.calls.count()).toBe(before + 1);
    expect(lastSentUserText()).toBe(OPEN + 'confirm tok-abc]');
    // Same chat session, so the server can match the token.
    expect(chatSpy.calls.mostRecent().args[2]).toBe(state.sessionId);
  });

  it('decide(false) sends [cancel <token>] and no changed$', () => {
    const card = proposal('tok-xyz');
    const seen: Array<{ tab: string }> = [];
    state.changed$.subscribe(c => seen.push(c));
    const before = chatSpy.calls.count();

    s.decide(card, false);

    expect(card.confirm.decided).toBeTrue();
    expect(card.confirm.decision).toBe('cancelled');
    expect(chatSpy.calls.count()).toBe(before + 1);
    expect(lastSentUserText()).toBe(OPEN + 'cancel tok-xyz]');
    expect(seen).toEqual([]);
  });

  it('a second decide on the same card is a no-op', () => {
    const card = proposal('tok-once');

    s.decide(card, true);
    expect(card.confirm.decision).toBe('confirmed');
    const afterFirst = chatSpy.calls.count();
    // Let the confirm turn finish so the streaming guard in send() is not
    // what blocks the repeat.
    finishTurn();
    const turnsAfterFirst = state.turns.length;

    s.decide(card, true);
    s.decide(card, false);

    expect(chatSpy.calls.count()).toBe(afterFirst);
    expect(state.turns.length).toBe(turnsAfterFirst);
    expect(card.confirm.decided).toBeTrue();
    expect(card.confirm.decision).toBe('confirmed');
  });

  it('showOnce returns temporaryPassword for create_user and value for issue_api_key, null for reads', () => {
    const user = toolCard('u1', 'create_user', { username: 'carol', role: 'editor', temporaryPassword: 'Tmp-Pa55-xyz' });
    const issued = toolCard('k1', 'issue_api_key', { id: 'key-1', label: 'chat-test', value: 'dk_live_abc123' });
    const rotated = toolCard('k2', 'rotate_api_key', { id: 'key-1', value: 'dk_live_def456' });
    const read = toolCard('r1', 'list_users', [{ username: 'carol', role: 'editor', temporaryPassword: 'nope' }]);
    const readKeys = toolCard('r2', 'list_api_keys', { value: 'nope' });
    const failed = toolCard('e1', 'create_user', 'User already exists', true);

    expect(s.showOnce(user)).toEqual({ label: 'Temporary password', value: 'Tmp-Pa55-xyz' });
    expect(s.showOnce(issued)).toEqual({ label: 'API key', value: 'dk_live_abc123' });
    expect(s.showOnce(rotated)).toEqual({ label: 'API key', value: 'dk_live_def456' });
    expect(s.showOnce(read)).toBeNull();
    expect(s.showOnce(readKeys)).toBeNull();
    expect(s.showOnce(failed)).toBeNull();
  });

  it('show-once value survives a later send()', () => {
    const issued = toolCard('k1', 'issue_api_key', { id: 'key-1', label: 'chat-test', value: 'dk_live_abc123' });
    const resultBefore = issued.result;

    state.send('Thanks, now list the keys');
    emit({ type: 'text_delta', text: 'Here they are.' });
    finishTurn();

    const same = cardById('k1');
    expect(same).toBe(issued);
    expect(same.result).toBe(resultBefore);
    expect(s.showOnce(same)).toEqual({ label: 'API key', value: 'dk_live_abc123' });
  });

  it('requestShow emits on showMe$', () => {
    const seen: string[] = [];
    s.showMe$.subscribe((t: string) => seen.push(t));

    s.requestShow('keys');

    expect(seen).toEqual(['keys']);
  });

  it('notifyChanged emits {tab}', () => {
    const seen: Array<{ tab: string }> = [];
    state.changed$.subscribe(c => seen.push(c));

    s.notifyChanged('ai-providers');

    expect(seen).toEqual([{ tab: 'ai-providers' }]);
  });
});
