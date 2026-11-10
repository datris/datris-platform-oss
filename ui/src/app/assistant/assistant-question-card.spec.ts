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
import { AssistantStateService, askUserForHistory } from './assistant-state.service';
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

  it('a card-only turn is sent in history as an assistant message carrying the card as data, never as prose', () => {
    const c = askAndFinish('toolu_hist', true);
    c.askUser.submitted = true; // as submitAskUser does before sending
    state.send('Source: NWS\nSchedule: Hourly');
    const history: any[] = chatSpy.calls.mostRecent().args[0];
    expect(history.map((m: any) => m.role)).toEqual(['user', 'assistant', 'user']);
    expect(history[1].askUser).toEqual({ questions: QUESTIONS, submitted: true });
    // The model copied the old bracketed text record back as prose on later
    // turns; the card must not appear in the assistant text at all.
    expect(history[1].content).not.toContain('ask_user');
    expect(history[1].content).not.toContain('Tool call');
    for (const q of QUESTIONS) expect(history[1].content).not.toContain(q.question);
    expect(history[2].content).toBe('Source: NWS\nSchedule: Hourly');
  });

  it('a skipped card is recorded in history with submitted false', () => {
    const c = askAndFinish('toolu_hist_skip', true);
    state.send('actually skip the tap, just do a live read');
    expect(c.askUser.skipped).toBeTrue();
    const history: any[] = chatSpy.calls.mostRecent().args[0];
    expect(history.map((m: any) => m.role)).toEqual(['user', 'assistant', 'user']);
    expect(history[1].askUser).toEqual({ questions: QUESTIONS, submitted: false });
    expect(history[1].content).not.toContain('ask_user');
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

  it('a multi-question card shows one tab at a time; picking a single-select answer advances', () => {
    mount();
    const c = askAndFinish('toolu_tabs');
    c.expanded = true;
    fixture.detectChanges();
    const tabs = Array.from(el.querySelectorAll('.ask-tab')).map(t => (t.textContent || '').replace(/\s+/g, ' ').trim());
    expect(tabs).toEqual(['1Source', '2Schedule']);
    let text = (el.textContent || '').replace(/\s+/g, ' ');
    expect(text).toContain('Which weather API should the tap call?');
    expect(text).not.toContain('Which runs do you want?');
    expect(el.querySelector('.ask-actions .secret-btn-save')?.textContent?.trim()).toBe('Next');

    fixture.componentInstance.toggleAskUserOption(c, 0, 'Open-Meteo');
    fixture.detectChanges();
    expect(fixture.componentInstance.askUserActive(c)).toBe(1);
    text = (el.textContent || '').replace(/\s+/g, ' ');
    expect(text).toContain('Which runs do you want?');
    expect(text).not.toContain('Which weather API should the tap call?');
    const first = el.querySelector('.ask-tab') as HTMLElement;
    expect(first.classList).toContain('ask-tab-answered');
    expect(first.querySelector('.ask-tab-mark')?.textContent?.trim()).toBe('✓');

    const submit = Array.from(el.querySelectorAll('.ask-actions button')).find(b => b.textContent?.trim() === 'Submit') as HTMLButtonElement;
    expect(submit).withContext('Submit shows on the last tab').toBeTruthy();
    expect(submit.disabled).withContext('Submit stays disabled until every tab is answered').toBeTrue();

    fixture.componentInstance.toggleAskUserOption(c, 1, 'Daily');
    fixture.detectChanges();
    expect(fixture.componentInstance.askUserActive(c)).withContext('multi-select does not auto-advance').toBe(1);
    expect((Array.from(el.querySelectorAll('.ask-actions button')).find(b => b.textContent?.trim() === 'Submit') as HTMLButtonElement).disabled).toBeFalse();

    (el.querySelector('.ask-tab') as HTMLElement).click();
    fixture.detectChanges();
    expect(fixture.componentInstance.askUserActive(c)).withContext('clicking a chip returns to that tab').toBe(0);
  });

  it('askUserActive clamps a stale persisted index', () => {
    mount();
    const c = askAndFinish('toolu_clamp');
    c.askUser.active = 99;
    expect(fixture.componentInstance.askUserActive(c)).toBe(1);
    c.askUser.active = -3;
    expect(fixture.componentInstance.askUserActive(c)).toBe(0);
  });

  it('a question without options renders only a text box and is answered by typing', () => {
    mount();
    const qs = [
      QUESTIONS[0],
      { header: 'Locations', question: 'Which places should it cover?', multiSelect: false, options: [] }
    ];
    state.send('I want a tap that pulls weather data');
    stream.next({ type: 'ask_user', id: 'toolu_free', questions: qs });
    stream.next({ type: 'done' });
    stream.complete();
    const c = card('toolu_free');
    c.expanded = true;
    fixture.componentInstance.setAskUserActive(c, 1);
    fixture.detectChanges();
    expect(el.querySelector('.ask-options')).withContext('no option buttons for a free-text question').toBeNull();
    const box = el.querySelector('.ask-other') as HTMLInputElement;
    expect(box.placeholder).toBe('Type your answer');
    expect(fixture.componentInstance.askUserAnswered(c, 1)).toBeFalse();
    c.askUser.other[1] = 'Boston, MA; Denver, CO';
    expect(fixture.componentInstance.askUserAnswered(c, 1)).toBeTrue();
    c.askUser.selections[0] = new Set(['NWS']);
    expect(formatAskUserAnswers(c)).toBe('Source: NWS\nLocations: Boston, MA; Denver, CO');
    expect(askUserForHistory(c.askUser)).toEqual({ questions: qs, submitted: false });
  });

  it('a seven-option question renders seven option rows plus the Other input', () => {
    mount();
    const labels = ['MongoDB', 'PostgreSQL', 'Object store', 'Snowflake', 'Databricks', 'SQL Server', 'Live Read'];
    const qs = [{
      header: 'Destination', question: 'Where should the rows land?', multiSelect: false,
      options: labels.map(label => ({ label, description: 'd' }))
    }];
    state.send('I want a pipeline from a CSV');
    stream.next({ type: 'ask_user', id: 'toolu_seven', questions: qs });
    stream.next({ type: 'done' });
    stream.complete();
    const c = card('toolu_seven');
    c.expanded = true;
    fixture.detectChanges();
    const rows = Array.from(el.querySelectorAll('.ask-options .ask-option'));
    expect(rows.length).withContext('every option is its own row').toBe(7);
    expect(rows.map(r => (r.querySelector('.ask-option-label')?.textContent || '').trim())).toEqual(labels);
    expect(el.querySelectorAll('.ask-other').length).withContext('the Other input is still shown').toBe(1);
  });

  // -------------------------------------------------------------------
  // Story: Assistant cards: Clear all on rule proposals, exclusive "None" on
  // multi-select questions (plans/stories/assistant-card-clear-all-exclusive-option.md),
  // Acceptance bullets 2-3. On a multiSelect question an option flagged
  // `exclusive: true` (or, without the flag, a label matching the narrow
  // None/Skip/No change regex) clears the others when ticked and is cleared
  // when another is ticked. `isExclusiveAskOption(question, option)` is an
  // exported pure helper in assistant-state.service.

  function extrasQuestion(multiSelect: boolean, flag: boolean, firstLabel = 'None'): any {
    const first: any = { label: firstLabel, description: 'Nothing extra' };
    if (flag) first.exclusive = true;
    return {
      header: 'Extras', question: 'Which extras do you want?', multiSelect,
      options: [first, { label: 'A', description: 'a' }, { label: 'B', description: 'b' }, { label: 'C', description: 'c' }]
    };
  }

  function askWith(id: string, qs: any[]): any {
    state.send('I want a pipeline from a CSV');
    stream.next({ type: 'ask_user', id, questions: qs });
    stream.next({ type: 'done' });
    stream.complete();
    return card(id);
  }

  function selectedOf(c: any, index = 0): string[] {
    return Array.from(stateModule.askUserSelected(c.askUser, index)).sort();
  }

  function pick(c: any, index: number, ...labels: string[]): void {
    for (const l of labels) fixture.componentInstance.toggleAskUserOption(c, index, l);
  }

  it('exclusive: A then None (flagged) leaves only None', () => {
    mount();
    const c = askWith('toolu_ex1', [extrasQuestion(true, true)]);
    pick(c, 0, 'A', 'None');
    expect(selectedOf(c)).toEqual(['None']);
  });

  it('exclusive: None (flagged) then B leaves only B', () => {
    mount();
    const c = askWith('toolu_ex2', [extrasQuestion(true, true)]);
    pick(c, 0, 'None', 'B');
    expect(selectedOf(c)).toEqual(['B']);
  });

  it('exclusive: A then B still combine', () => {
    mount();
    const c = askWith('toolu_ex3', [extrasQuestion(true, true)]);
    pick(c, 0, 'A', 'B');
    expect(selectedOf(c)).toEqual(['A', 'B']);
  });

  it('exclusive: label "None" without the flag behaves the same (label fallback)', () => {
    mount();
    const c = askWith('toolu_ex4', [extrasQuestion(true, false)]);
    pick(c, 0, 'A', 'None');
    expect(selectedOf(c)).withContext('A then None').toEqual(['None']);
    pick(c, 0, 'B');
    expect(selectedOf(c)).withContext('None then B').toEqual(['B']);
    pick(c, 0, 'C');
    expect(selectedOf(c)).withContext('B then C combine').toEqual(['B', 'C']);
  });

  it('exclusive: a label "No schedule" without the flag is not exclusive', () => {
    mount();
    const c = askWith('toolu_ex5', [extrasQuestion(true, false, 'No schedule')]);
    pick(c, 0, 'A', 'No schedule');
    expect(selectedOf(c)).toEqual(['A', 'No schedule']);
    pick(c, 0, 'B');
    expect(selectedOf(c)).toEqual(['A', 'B', 'No schedule']);
  });

  it('exclusive: the same options on a single-select question keep radio behaviour (one selection, auto-advance)', () => {
    mount();
    const c = askWith('toolu_ex6', [extrasQuestion(false, true), QUESTIONS[1]]);
    pick(c, 0, 'A');
    expect(selectedOf(c)).toEqual(['A']);
    expect(fixture.componentInstance.askUserActive(c)).withContext('a single-select pick advances').toBe(1);
    pick(c, 0, 'None');
    expect(selectedOf(c)).toEqual(['None']);
    pick(c, 0, 'B');
    expect(selectedOf(c)).toEqual(['B']);
  });

  it('exclusive: the submitted answer line after A then None is "Extras: None"', () => {
    mount();
    const c = askWith('toolu_ex7', [extrasQuestion(true, true)]);
    pick(c, 0, 'A', 'None');
    const sendSpy = spyOn(state, 'send');
    (fixture.componentInstance as any).submitAskUser(c);
    expect(sendSpy).toHaveBeenCalledTimes(1);
    expect(sendSpy.calls.mostRecent().args[0]).toBe('Extras: None');
  });

  function isExclusiveAskOption(q: any, o: any): boolean {
    const exportName = 'isExclusiveAskOption';
    const fn = (stateModule as any)[exportName];
    expect(typeof fn).withContext('isExclusiveAskOption must be exported from assistant-state.service').toBe('function');
    return typeof fn === 'function' ? fn(q, o) : (undefined as any);
  }

  it('isExclusiveAskOption: flag true is exclusive on multiSelect only', () => {
    const multi = { header: 'H', question: 'Q?', multiSelect: true, options: [] };
    const single = { header: 'H', question: 'Q?', multiSelect: false, options: [] };
    expect(isExclusiveAskOption(multi, { label: 'Leave it', description: '', exclusive: true })).toBe(true);
    expect(isExclusiveAskOption(single, { label: 'Leave it', description: '', exclusive: true })).toBe(false);
    expect(isExclusiveAskOption(single, { label: 'None', description: '' })).withContext('label fallback is multi-select only').toBe(false);
  });

  it('isExclusiveAskOption: label fallback matches the narrow none/skip/no change set only', () => {
    const multi = { header: 'H', question: 'Q?', multiSelect: true, options: [] };
    for (const label of ['none', 'None of the above', 'Skip', 'No change', 'no thanks', '  None  ']) {
      expect(isExclusiveAskOption(multi, { label, description: '' })).withContext(`"${label}" is exclusive`).toBe(true);
    }
    for (const label of ['No schedule', 'Nonexistent', 'Skip weekends']) {
      expect(isExclusiveAskOption(multi, { label, description: '' })).withContext(`"${label}" is not exclusive`).toBe(false);
    }
  });
});
