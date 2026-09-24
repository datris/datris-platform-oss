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
