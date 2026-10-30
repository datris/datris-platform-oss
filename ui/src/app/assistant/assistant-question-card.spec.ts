/**
 * Story: Assistant structured question cards (plans/stories/assistant-question-cards.md),
 * Steps 5-8, Acceptance bullet 2.
 *
 * The server emits `ask_user {type,id,questions}` when the agent calls the
 * UI-only `ask_user` tool. AssistantStateService attaches
 * `askUser = { questions, selections, other, submitted, skipped, summary }`
 * to the matching tool card (synthesizing the card if no tool_use arrived),
 * `formatAskUserAnswers(card)` renders `Header: answer` lines, and
 * AssistantComponent.submitAskUser posts them through the same resume path
 * as the credentials form (`state.send`). While a card is pending the generic
 * tool body (input/result JSON) is hidden, like the secret form.
 *
 * New symbols are read loosely (`as any`) so a missing export fails these
 * specs at runtime instead of breaking compilation of every other spec.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { of, Subject } from 'rxjs';

import * as stateModule from './assistant-state.service';
import { AssistantComponent } from './assistant.component';
import { AssistantStateService } from './assistant-state.service';
import { AssistantService } from '../assistant.service';
import { SecretsService } from '../secrets.service';
import { TapService } from '../tap.service';
import { ApprovalsService } from '../approvals.service';

const QUESTIONS = [
  {
    header: 'Source', question: 'Which weather API should the tap call?', multiSelect: false,
    options: [
      { label: 'Open-Meteo', description: 'Free, no key needed' },
      { label: 'NWS', description: 'US only' }
    ]
  },
  {
    header: 'Schedule', question: 'Which runs do you want?', multiSelect: true,
    options: [
      { label: 'Hourly', description: 'Every hour' },
      { label: 'Daily', description: 'Once a day' }
    ]
  }
];

const INIT = {
  toolCount: 0, toolNames: [], workflowReference: '',
  provider: 'anthropic', model: 'claude-opus-5-5', extendedThinking: false
};

function formatAskUserAnswers(card: any): string {
  // Computed key: a static `.formatAskUserAnswers` is a webpack load error while the export is missing.
  const exportName = 'formatAskUserAnswers';
  const fn = (stateModule as any)[exportName];
  expect(typeof fn).withContext('formatAskUserAnswers must be exported from assistant-state.service').toBe('function');
  return fn(card);
}

describe('Assistant question cards (ask_user)', () => {
  let fixture: ComponentFixture<AssistantComponent>;
  let el: HTMLElement;
  let state: AssistantStateService;
  let stream: Subject<any>;
  let chatSpy: jasmine.Spy;

  beforeEach(() => {
    sessionStorage.removeItem('assistant.chat.transcript');
    stream = new Subject<any>();
    chatSpy = jasmine.createSpy('chat').and.callFake(() => stream.asObservable());
    TestBed.configureTestingModule({
      declarations: [AssistantComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideRouter([]),
        AssistantStateService,
        { provide: AssistantService, useValue: { init: () => of(INIT), chat: chatSpy } },
        { provide: SecretsService, useValue: { listSecrets: () => of([]) } },
        { provide: TapService, useValue: { generateStatus: () => of({ active: false }) } },
        { provide: ApprovalsService, useValue: {} }
      ]
    });
    state = TestBed.inject(AssistantStateService);
  });

  afterEach(() => {
    fixture?.destroy();
    sessionStorage.removeItem('assistant.chat.transcript');
  });

  function mount(): void {
    fixture = TestBed.createComponent(AssistantComponent);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  }

  function card(id: string): any {
    const turn: any = state.turns[state.turns.length - 1];
    return turn.segments.find((s: any) => s.kind === 'tool' && s.id === id);
  }

  /** Start a turn and end it on an ask_user card (the server stops the loop). */
  function askAndFinish(id = 'toolu_ask', withToolUse = false): any {
    state.send('I want a tap that pulls weather data');
    if (withToolUse) {
      stream.next({ type: 'tool_use_start', id, name: 'ask_user' });
      stream.next({ type: 'tool_use', id, name: 'ask_user', input: { questions: QUESTIONS } });
    }
    stream.next({ type: 'ask_user', id, questions: QUESTIONS });
    stream.next({ type: 'done' });
    stream.complete();
    return card(id);
  }

  // ------------------------------------------------------------ state ---

  it('an ask_user event with no prior tool_use synthesizes a tool card with askUser', () => {
    const c = askAndFinish('toolu_ask', false);
    expect(c).withContext('a tool card must be synthesized for the ask_user id').toBeTruthy();
    expect(c.name).toBe('ask_user');
    expect(c.status).not.toBe('running');
    expect(c.askUser).withContext('askUser must be set on the card').toBeTruthy();
    expect(c.askUser.questions).toEqual(QUESTIONS);
    expect(c.askUser.submitted).toBeFalse();
    expect(c.askUser.skipped).toBeFalse();
    expect(state.streaming).toBeFalse();
  });

  it('an ask_user event after tool_use attaches askUser to the existing card (no duplicate)', () => {
    askAndFinish('toolu_ask2', true);
    const turn: any = state.turns[state.turns.length - 1];
    const cards = turn.segments.filter((s: any) => s.kind === 'tool' && s.id === 'toolu_ask2');
    expect(cards.length).toBe(1);
    expect(cards[0].askUser?.questions).toEqual(QUESTIONS);
  });

  it('a card-only turn is sent in history as an ask_user tool-call record listing the questions and options', () => {
    const c = askAndFinish('toolu_hist', true);
    c.askUser.submitted = true; // as submitAskUser does before sending
    state.send('Source: NWS\nSchedule: Hourly');
    const history: any[] = chatSpy.calls.mostRecent().args[0];
    expect(history.map((m: any) => m.role)).toEqual(['user', 'assistant', 'user']);
    const asked: string = history[1].content;
    expect(asked).toContain('[Tool call: ask_user');
    for (const q of QUESTIONS) {
      expect(asked).toContain(q.header);
      expect(asked).toContain(q.question);
      for (const o of q.options) {
        expect(asked).toContain(o.label);
        expect(asked).toContain(o.description);
      }
    }
    expect(asked).toContain('submitted the card');
    expect(asked).not.toContain('replied in chat');
    expect(history[2].content).toBe('Source: NWS\nSchedule: Hourly');
  });

  it('a skipped card is recorded in history as answered in chat, not as header lines', () => {
    const c = askAndFinish('toolu_hist_skip', true);
    state.send('actually skip the tap, just do a live read');
    expect(c.askUser.skipped).toBeTrue();
    const history: any[] = chatSpy.calls.mostRecent().args[0];
    expect(history.map((m: any) => m.role)).toEqual(['user', 'assistant', 'user']);
    const asked: string = history[1].content;
    expect(asked).toContain('[Tool call: ask_user');
    expect(asked).toContain('replied in chat instead of submitting the card');
    expect(asked).not.toContain('submitted the card;');
  });

  // ------------------------------------------------------- formatting ---

  function formCard(selections: Record<number, Set<string>>, other: Record<number, string> = {}): any {
    return {
      kind: 'tool', id: 'x', name: 'ask_user', input: {}, result: '', isError: false, status: 'ok', expanded: false,
      askUser: { questions: QUESTIONS, selections, other, submitted: false, skipped: false, summary: '' }
    };
  }

  it('formatAskUserAnswers: single select gives one Header: label line per question', () => {
    const text = formatAskUserAnswers(formCard({ 0: new Set(['NWS']), 1: new Set(['Daily']) }));
    expect(text.split('\n').map(l => l.trim()).filter(l => l)).toEqual(['Source: NWS', 'Schedule: Daily']);
  });

  it('formatAskUserAnswers: multi-select joins labels with ", "', () => {
    const text = formatAskUserAnswers(formCard({ 0: new Set(['Open-Meteo']), 1: new Set(['Hourly', 'Daily']) }));
    expect(text.split('\n').map(l => l.trim()).filter(l => l)).toEqual(['Source: Open-Meteo', 'Schedule: Hourly, Daily']);
  });

  it('formatAskUserAnswers: Other text is the answer, and is combined with labels when both are used', () => {
    const text = formatAskUserAnswers(formCard(
      { 0: new Set<string>(), 1: new Set(['Hourly']) },
      { 0: 'Tomorrow.io', 1: 'every 15 minutes' }
    ));
    const lines = text.split('\n').map(l => l.trim()).filter(l => l);
    expect(lines.length).toBe(2);
    expect(lines[0]).toBe('Source: Tomorrow.io');
    expect(lines[1].startsWith('Schedule: ')).toBeTrue();
    expect(lines[1]).toContain('Hourly');
    expect(lines[1]).toContain('every 15 minutes');
  });

  // --------------------------------------------------------- component ---

  it('submitAskUser sends the formatted answers through state.send and marks the card submitted', () => {
    mount();
    const c = askAndFinish();
    expect(c?.askUser).toBeTruthy();
    c.askUser.selections = { 0: new Set(['NWS']), 1: new Set(['Hourly', 'Daily']) };
    c.askUser.other = {};
    const expected = formatAskUserAnswers(c);
    const sendSpy = spyOn(state, 'send').and.callThrough();

    const comp: any = fixture.componentInstance;
    expect(typeof comp.submitAskUser).withContext('AssistantComponent.submitAskUser').toBe('function');
    comp.submitAskUser(c);

    expect(sendSpy).toHaveBeenCalledTimes(1);
    expect(sendSpy.calls.mostRecent().args[0]).toBe(expected);
    expect(c.askUser.submitted).toBeTrue();
    const lastUser: any = [...state.turns].reverse().find((t: any) => t.role === 'user');
    expect(lastUser.text).toContain('Source: NWS');
    expect(lastUser.text).toContain('Schedule: Hourly, Daily');
  });

  it('skipAskUser marks the card skipped and sends nothing', () => {
    mount();
    const c = askAndFinish();
    const sendSpy = spyOn(state, 'send');
    const comp: any = fixture.componentInstance;
    expect(typeof comp.skipAskUser).withContext('AssistantComponent.skipAskUser').toBe('function');
    comp.skipAskUser(c);
    expect(c.askUser.skipped).toBeTrue();
    expect(c.askUser.submitted).toBeFalse();
    expect(sendSpy).not.toHaveBeenCalled();
  });

  it('a typed reply while a card is pending sends normally and marks the card skipped', () => {
    mount();
    const c = askAndFinish();
    expect(c?.askUser).toBeTruthy();
    state.draft = 'Use Open-Meteo, hourly, into Postgres';
    fixture.componentInstance.send();
    const lastUser: any = [...state.turns].reverse().find((t: any) => t.role === 'user');
    expect(lastUser.text).toBe('Use Open-Meteo, hourly, into Postgres');
    expect(c.askUser.skipped).toBeTrue();
    expect(c.askUser.submitted).toBeFalse();
  });

  it('a pending card renders its questions and hides the generic tool body', () => {
    mount();
    const c = askAndFinish();
    expect(c?.askUser).toBeTruthy();
    c.expanded = true;
    fixture.detectChanges();
    const text = (el.textContent || '').replace(/\s+/g, ' ');
    expect(text).toContain('Which weather API should the tap call?');
    expect(text).toContain('Open-Meteo');
    expect(text).toContain('Free, no key needed');
    expect(el.querySelector('.tool-body')).withContext('generic tool body must be hidden for an ask_user card').toBeNull();
  });
});
