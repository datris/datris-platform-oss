/**
 * Story: Assistant checkpoint-2 cards: propose and confirm data-quality checks
 * and transformations (plans/stories/assistant-rule-proposal-cards.md),
 * Steps 6-8; unit-level coverage of Acceptance bullet 5.
 *
 * The server emits `rule_proposal {type,id,kind,pipeline,items:[{text,column}]}`
 * when the model calls the synthetic `propose_pipeline_rules` tool (and then a
 * tool_result with the waiting text). AssistantStateService attaches
 * `ruleProposal = { kind, pipeline, items: [{text, column, enabled}],
 * submitted, skipped?, summary? }` to the matching tool card (synthesizing it
 * if no tool_use arrived). AssistantComponent renders one row per item
 * (checkbox, text input, column tag) with Add rule / Skip / Confirm;
 * `confirmRules` / `skipRules` post a fixed-shape reply via sendSystemReply.
 *
 * New symbols are reached loosely (`as any`) so a missing method fails these
 * specs at runtime instead of breaking compilation of every other spec.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { of, Subject } from 'rxjs';

import { AssistantComponent } from './assistant.component';
import { AssistantStateService } from './assistant-state.service';
import * as stateModule from './assistant-state.service';
import { AssistantService } from '../assistant.service';
import { SecretsService } from '../secrets.service';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { ApprovalsService } from '../approvals.service';

const TOOL = 'propose_pipeline_rules';
const WAITING = 'Proposal card displayed to the user. Their confirmed list arrives as the next user message as numbered lines, or they may reply in prose. Wait for their reply.';

function items(): any[] {
  return [
    { text: 'is never negative', column: 'amount' },
    { text: 'created_at parses as a date', column: 'created_at' },
    { text: 'no two rows share the same id' }
  ];
}

function proposalEvent(id: string, overrides: any = {}): any {
  return { type: 'rule_proposal', id, kind: 'data_quality', pipeline: 'sales_daily', items: items(), ...overrides };
}

// Story assistant-dq-card-on-failure: a data-quality Confirm carries the
// on-failure choice; the card defaults to Reject.
const ALL_CONFIRMED =
  'Confirmed data-quality checks for pipeline `sales_daily` (on failure: reject the run): ' +
  '1. amount: is never negative; 2. created_at parses as a date; 3. no two rows share the same id';

const INIT = {
  toolCount: 0, toolNames: [], workflowReference: '',
  provider: 'anthropic', model: 'claude-opus-5-5', extendedThinking: false
};

describe('Assistant rule-proposal card (rule_proposal)', () => {
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
        provideHttpClient(),
        AssistantStateService,
        { provide: AssistantService, useValue: { init: () => of(INIT), chat: chatSpy } },
        { provide: SecretsService, useValue: { listSecrets: () => of([]) } },
        { provide: TapService, useValue: { generateStatus: () => of({ active: false }) } },
        { provide: PipelineService, useValue: {} },
        { provide: ApprovalsService, useValue: {} }
      ]
    });
    state = TestBed.inject(AssistantStateService);
  });

  afterEach(() => {
    fixture?.destroy();
    sessionStorage.removeItem('assistant.chat.transcript');
  });

  async function mount(): Promise<void> {
    fixture = TestBed.createComponent(AssistantComponent);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function render(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function card(id: string): any {
    const turn: any = state.turns[state.turns.length - 1];
    return turn.segments.find((s: any) => s.kind === 'tool' && s.id === id);
  }

  /** Start a turn and end it on a rule_proposal card (the server stops the loop). */
  function proposeAndFinish(id = 'toolu_rp', withToolUse = true, overrides: any = {}): any {
    state.send('Data quality');
    const evt = proposalEvent(id, overrides);
    if (withToolUse) {
      stream.next({ type: 'tool_use_start', id, name: TOOL });
      stream.next({ type: 'tool_use', id, name: TOOL, input: { kind: evt.kind, pipeline: evt.pipeline, items: evt.items } });
    }
    stream.next(evt);
    stream.next({ type: 'tool_result', id, name: TOOL, result: WAITING, isError: false });
    stream.next({ type: 'done' });
    stream.complete();
    return card(id);
  }

  function text(): string {
    return (el.textContent || '').replace(/\s+/g, ' ');
  }

  function ruleCard(): HTMLElement | null {
    return el.querySelector('.rule-card') as HTMLElement | null;
  }

  function checkboxes(): HTMLInputElement[] {
    const root = ruleCard();
    return root ? Array.from(root.querySelectorAll('input[type="checkbox"]')) as HTMLInputElement[] : [];
  }

  function textInputs(): HTMLInputElement[] {
    const root = ruleCard();
    return root
      ? Array.from(root.querySelectorAll('input:not([type="checkbox"]):not([type="radio"]), textarea')) as HTMLInputElement[]
      : [];
  }

  function buttonNamed(label: string): HTMLButtonElement | undefined {
    return Array.from(el.querySelectorAll('button')).find(b => (b.textContent || '').trim() === label) as HTMLButtonElement | undefined;
  }

  // ------------------------------------------------------------ state ---

  it('a rule_proposal event with no prior tool_use synthesizes a propose_pipeline_rules card with ruleProposal', () => {
    const c = proposeAndFinish('toolu_syn', false);
    expect(c).withContext('a tool card must be synthesized for the rule_proposal id').toBeTruthy();
    expect(c.name).toBe(TOOL);
    expect(c.status).not.toBe('running');
    const rp = c.ruleProposal;
    expect(rp).withContext('ruleProposal must be set on the card').toBeTruthy();
    expect(rp.kind).toBe('data_quality');
    expect(rp.pipeline).toBe('sales_daily');
    expect(rp.items.map((i: any) => i.text)).toEqual(items().map(i => i.text));
    expect(rp.items.map((i: any) => i.column || '')).toEqual(['amount', 'created_at', '']);
    expect(rp.items.every((i: any) => i.enabled === true)).withContext('every item starts ticked').toBeTrue();
    expect(rp.submitted).toBeFalse();
    expect(state.streaming).toBeFalse();
  });

  it('a rule_proposal after tool_use attaches to the existing card (no duplicate) and items are editable copies', () => {
    state.send('Data quality');
    const evt = proposalEvent('toolu_att');
    stream.next({ type: 'tool_use_start', id: 'toolu_att', name: TOOL });
    stream.next({ type: 'tool_use', id: 'toolu_att', name: TOOL, input: { kind: 'data_quality', pipeline: 'sales_daily', items: items() } });
    stream.next(evt);
    stream.next({ type: 'tool_result', id: 'toolu_att', name: TOOL, result: WAITING, isError: false });
    stream.next({ type: 'done' });
    stream.complete();
    const turn: any = state.turns[state.turns.length - 1];
    const cards = turn.segments.filter((s: any) => s.kind === 'tool' && s.id === 'toolu_att');
    expect(cards.length).toBe(1);
    const rp = cards[0].ruleProposal;
    expect(rp).withContext('the later tool_result keeps the proposal state').toBeTruthy();
    rp.items[0].text = 'changed';
    expect(evt.items[0].text).withContext('editing the card must not mutate the event payload').toBe('is never negative');
  });

  // ---------------------------------------------------------- template ---

  it('the card renders a title naming the pipeline and one row (checkbox, text, column tag) per item; the generic body is hidden', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    expect(ruleCard()).withContext('a .rule-card element').toBeTruthy();
    const t = text();
    expect(t).toContain('Proposed data-quality checks');
    expect(t).toContain('sales_daily');
    expect(checkboxes().length).withContext('one checkbox per item').toBe(3);
    expect(checkboxes().every(b => b.checked)).toBeTrue();
    expect(textInputs().length).withContext('one text input per item').toBe(3);
    await fixture.whenStable();
    expect(textInputs().map(i => i.value)).toEqual(items().map(i => i.text));
    expect(t).toContain('amount');
    expect(t).toContain('created_at');
    expect(buttonNamed('Add rule')).withContext('Add rule button').toBeTruthy();
    expect(buttonNamed('Skip')).withContext('Skip button').toBeTruthy();
    expect(buttonNamed('Confirm')).withContext('Confirm button').toBeTruthy();
    expect(el.querySelector('.tool-body')).withContext('generic tool body must be hidden for a rule-proposal card').toBeNull();
  });

  it('a transformation card is titled Proposed transformations', async () => {
    await mount();
    const c = proposeAndFinish('toolu_tr', true, { kind: 'transformation', items: [{ text: 'trim whitespace', column: 'name' }] });
    c.expanded = true;
    await render();
    expect(text()).toContain('Proposed transformations');
    expect(checkboxes().length).toBe(1);
  });

  // --------------------------------------------------------- component ---

  it('Confirm with every row ticked sends the exact confirmed shape and collapses the card', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    const sendSpy = spyOn(state, 'send').and.callThrough();
    const btn = buttonNamed('Confirm');
    expect(btn).withContext('Confirm button').toBeTruthy();
    btn!.click();
    await render();
    expect(sendSpy).toHaveBeenCalledTimes(1);
    expect(sendSpy.calls.mostRecent().args[0]).toBe(ALL_CONFIRMED);
    expect(c.ruleProposal.submitted).toBeTrue();
    expect(c.ruleProposal.skipped).toBeFalsy();
    expect(checkboxes().length).withContext('the rows collapse once submitted').toBe(0);
    expect(buttonNamed('Confirm')).toBeUndefined();
  });

  it('unticking a row, editing a row and Add rule change the reply', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    // Untick row 2 (created_at).
    checkboxes()[1].click();
    await render();
    expect(c.ruleProposal.items[1].enabled).withContext('unticking clears enabled').toBeFalse();
    // Edit row 1.
    const first = textInputs()[0];
    first.value = 'is never below zero';
    first.dispatchEvent(new Event('input'));
    await render();
    expect(c.ruleProposal.items[0].text).toBe('is never below zero');
    // Add a row and type into it.
    const add = buttonNamed('Add rule');
    expect(add).withContext('Add rule button').toBeTruthy();
    add!.click();
    await render();
    expect(c.ruleProposal.items.length).toBe(4);
    expect(c.ruleProposal.items[3].enabled).withContext('an added row starts enabled').toBeTrue();
    const added = textInputs()[3];
    expect(added).withContext('the added row has a text input').toBeTruthy();
    added.value = 'qty is a whole number';
    added.dispatchEvent(new Event('input'));
    await render();

    const sendSpy = spyOn(state, 'send');
    buttonNamed('Confirm')!.click();
    expect(sendSpy).toHaveBeenCalledTimes(1);
    expect(sendSpy.calls.mostRecent().args[0]).toBe(
      'Confirmed data-quality checks for pipeline `sales_daily` (on failure: reject the run): ' +
      '1. amount: is never below zero; 2. no two rows share the same id; 3. qty is a whole number');
  });

  it('confirmRules skips blank rows and does not repeat a column the text already names', async () => {
    await mount();
    const c = proposeAndFinish();
    const comp: any = fixture.componentInstance;
    expect(typeof comp.addRule).withContext('AssistantComponent.addRule').toBe('function');
    comp.addRule(c); // left blank
    const sendSpy = spyOn(state, 'send');
    expect(typeof comp.confirmRules).withContext('AssistantComponent.confirmRules').toBe('function');
    comp.confirmRules(c);
    expect(sendSpy.calls.mostRecent().args[0]).toBe(ALL_CONFIRMED);
  });

  it('confirmRules does not prefix a column the text names mid-sentence (any case)', async () => {
    await mount();
    const c = proposeAndFinish('toolu_mid', true, {
      kind: 'transformation',
      items: [{ text: 'Format the Created_At value as a date', column: 'created_at' }, { text: 'round to 2 places', column: 'amount' }]
    });
    const sendSpy = spyOn(state, 'send');
    (fixture.componentInstance as any).confirmRules(c);
    expect(sendSpy.calls.mostRecent().args[0]).toBe(
      'Confirmed transformations for pipeline `sales_daily`: 1. Format the Created_At value as a date; 2. amount: round to 2 places');
  });

  it('confirmRules prefixes a short column that only appears inside another word; a blank column gives bare text', async () => {
    await mount();
    const c = proposeAndFinish('toolu_tok', true, {
      items: [
        { text: 'Validate that it is unique', column: 'id' },
        { text: 'Update the value to uppercase', column: 'date' },
        { text: 'never empty', column: '   ' }
      ]
    });
    const sendSpy = spyOn(state, 'send');
    (fixture.componentInstance as any).confirmRules(c);
    expect(sendSpy.calls.mostRecent().args[0]).toBe(
      'Confirmed data-quality checks for pipeline `sales_daily` (on failure: reject the run): ' +
      '1. id: Validate that it is unique; 2. date: Update the value to uppercase; 3. never empty');
  });

  it('a transformation card confirms as "Confirmed transformations for pipeline ..."', async () => {
    await mount();
    const c = proposeAndFinish('toolu_tr2', true, { kind: 'transformation', items: [{ text: 'trim whitespace', column: 'name' }, { text: 'lowercase the email column' }] });
    const sendSpy = spyOn(state, 'send');
    (fixture.componentInstance as any).confirmRules(c);
    expect(sendSpy.calls.mostRecent().args[0]).toBe(
      'Confirmed transformations for pipeline `sales_daily`: 1. name: trim whitespace; 2. lowercase the email column');
  });

  it('Confirm is disabled when no ticked row has text', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    for (const b of checkboxes()) b.click();
    await render();
    const btn = buttonNamed('Confirm');
    expect(btn).withContext('Confirm button').toBeTruthy();
    expect(btn!.disabled).toBeTrue();
  });

  it('Skip sends the skip reply and marks the card submitted', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    const sendSpy = spyOn(state, 'send');
    const btn = buttonNamed('Skip');
    expect(btn).withContext('Skip button').toBeTruthy();
    btn!.click();
    expect(sendSpy).toHaveBeenCalledTimes(1);
    expect(sendSpy.calls.mostRecent().args[0]).toBe('No data-quality checks.');
    expect(c.ruleProposal.submitted).toBeTrue();
  });

  it('Skip on a transformation card sends "No transformations."', async () => {
    await mount();
    const c = proposeAndFinish('toolu_tr3', true, { kind: 'transformation' });
    const sendSpy = spyOn(state, 'send');
    const comp: any = fixture.componentInstance;
    expect(typeof comp.skipRules).withContext('AssistantComponent.skipRules').toBe('function');
    comp.skipRules(c);
    expect(sendSpy.calls.mostRecent().args[0]).toBe('No transformations.');
  });

  it('a submitted card sends nothing a second time', async () => {
    await mount();
    const c = proposeAndFinish();
    const sendSpy = spyOn(state, 'send');
    const comp: any = fixture.componentInstance;
    expect(typeof comp.confirmRules).withContext('AssistantComponent.confirmRules').toBe('function');
    comp.confirmRules(c);
    comp.confirmRules(c);
    comp.skipRules?.(c);
    expect(sendSpy).toHaveBeenCalledTimes(1);
  });

  it('Confirm or Skip while a turn is streaming changes nothing and sends nothing', async () => {
    await mount();
    const c = proposeAndFinish();
    const sendSpy = spyOn(state, 'send');
    state.streaming = true;
    const comp: any = fixture.componentInstance;
    expect(typeof comp.confirmRules).withContext('AssistantComponent.confirmRules').toBe('function');
    comp.confirmRules(c);
    comp.skipRules?.(c);
    expect(sendSpy).not.toHaveBeenCalled();
    expect(c.ruleProposal.submitted).toBeFalse();
    state.streaming = false;
  });

  it('a typed reply while the card is pending marks it skipped; its buttons then send nothing', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    state.send('just check the amount column');
    stream.complete();
    await render();
    expect(c.ruleProposal?.skipped).withContext('the card is marked skipped').toBeTrue();
    expect(checkboxes().length).withContext('the skipped card hides its rows').toBe(0);
    expect(text()).toContain('answered in chat');
    const sendSpy = spyOn(state, 'send');
    const comp: any = fixture.componentInstance;
    comp.confirmRules?.(c);
    comp.skipRules?.(c);
    expect(sendSpy).not.toHaveBeenCalled();
  });

  it('the card\'s own Confirm is not marked skipped by the reply it sends', async () => {
    await mount();
    const c = proposeAndFinish();
    (fixture.componentInstance as any).confirmRules(c);
    expect(c.ruleProposal.submitted).toBeTrue();
    expect(c.ruleProposal.skipped).toBeFalsy();
  });

  it('outbound history carries the card as a synthetic propose_pipeline_rules toolCalls entry with no result', async () => {
    await mount();
    const c = proposeAndFinish('toolu_hist');
    (fixture.componentInstance as any).confirmRules(c);
    const messages: any[] = chatSpy.calls.mostRecent().args[0];
    expect(messages.map(m => m.role)).toEqual(['user', 'assistant', 'user']);
    const toolCalls: any[] = messages[1].toolCalls || [];
    const entry = toolCalls.find(t => t.name === TOOL);
    expect(entry).withContext('the proposal travels as a toolCalls entry').toBeTruthy();
    expect(entry.id).toBe('toolu_hist');
    expect(entry.synthetic).toBeTrue();
    expect(entry.result).toBe('');
    expect(messages[2].content).toBe(ALL_CONFIRMED);
  });

  it('a reload keeps a pending card editable', () => {
    const c = proposeAndFinish();
    c.ruleProposal.items[0].text = 'is never below zero';
    (state as any).persist();
    const restored = new AssistantStateService(TestBed.inject(AssistantService));
    const turn: any = restored.turns[restored.turns.length - 1];
    const rc = turn.segments.find((s: any) => s.kind === 'tool' && s.id === 'toolu_rp');
    expect(rc?.ruleProposal).withContext('the restored card keeps its proposal').toBeTruthy();
    expect(rc.ruleProposal.submitted).toBeFalse();
    expect(rc.ruleProposal.items.length).toBe(3);
    expect(rc.ruleProposal.items[0].text).toBe('is never below zero');
    expect(rc.ruleProposal.items.every((i: any) => i.enabled === true)).toBeTrue();
  });
  // -------------------------------------------------------------------
  // Story: Assistant data-quality card: the user chooses warn-only vs
  // reject on failure (plans/stories/assistant-dq-card-on-failure.md),
  // Acceptance bullet 4. A `.rule-on-failure` radio pair (Reject = 'error',
  // checked by default; Warn only = 'warn') on data_quality cards only;
  // `RuleProposalState.onFailure`; `AssistantComponent.setRuleOnFailure`.

  const WARN_CONFIRMED =
    'Confirmed data-quality checks for pipeline `sales_daily` (on failure: warn only): ' +
    '1. amount: is never negative; 2. created_at parses as a date; 3. no two rows share the same id';

  function radios(): HTMLInputElement[] {
    const root = ruleCard();
    return root ? Array.from(root.querySelectorAll('input[type="radio"]')) as HTMLInputElement[] : [];
  }

  function radioFor(value: 'error' | 'warn'): HTMLInputElement | undefined {
    return radios().find(r => r.value === value);
  }

  function restoredCard(id = 'toolu_rp'): any {
    const restored = new AssistantStateService(TestBed.inject(AssistantService));
    const turn: any = restored.turns[restored.turns.length - 1];
    return turn.segments.find((s: any) => s.kind === 'tool' && s.id === id);
  }

  it('on failure: a data_quality card starts with onFailure "error"', () => {
    const c = proposeAndFinish();
    expect(c.ruleProposal.onFailure).withContext('RuleProposalState.onFailure defaults to error').toBe('error');
  });

  it('on failure: a data_quality card renders the two radios in .rule-on-failure with Reject checked', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    const block = el.querySelector('.rule-card .rule-on-failure');
    expect(block).withContext('a .rule-on-failure block on the data-quality card').toBeTruthy();
    expect(radios().length).withContext('two radios').toBe(2);
    const reject = radioFor('error');
    const warn = radioFor('warn');
    expect(reject).withContext("a radio with value 'error'").toBeTruthy();
    expect(warn).withContext("a radio with value 'warn'").toBeTruthy();
    expect(reject!.checked).withContext('Reject is checked by default').toBeTrue();
    expect(warn!.checked).toBeFalse();
    expect(reject!.name).toBe('ruleOnFailure-toolu_rp');
    expect(warn!.name).toBe('ruleOnFailure-toolu_rp');
    const t = (block!.textContent || '').replace(/\s+/g, ' ');
    expect(t).toContain('On failure');
    expect(t).toContain('Reject');
    expect(t).toContain('Warn only');
  });

  it('on failure: a transformation card renders no on-failure radios', async () => {
    await mount();
    const c = proposeAndFinish('toolu_tr_of', true, { kind: 'transformation', items: [{ text: 'trim whitespace', column: 'name' }] });
    c.expanded = true;
    await render();
    expect(ruleCard()).toBeTruthy();
    expect(radios().length).toBe(0);
    expect(el.querySelector('.rule-on-failure')).toBeNull();
  });

  it('on failure: Confirm with the default sends "(on failure: reject the run)"', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    const sendSpy = spyOn(state, 'send');
    buttonNamed('Confirm')!.click();
    expect(sendSpy).toHaveBeenCalledTimes(1);
    expect(sendSpy.calls.mostRecent().args[0]).toBe(ALL_CONFIRMED);
  });

  it('on failure: choosing Warn only sends "(on failure: warn only)"', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    const warn = radioFor('warn');
    expect(warn).withContext("a radio with value 'warn'").toBeTruthy();
    warn!.click();
    await render();
    expect(c.ruleProposal.onFailure).toBe('warn');
    const sendSpy = spyOn(state, 'send');
    buttonNamed('Confirm')!.click();
    expect(sendSpy.calls.mostRecent().args[0]).toBe(WARN_CONFIRMED);
  });

  it('on failure: setRuleOnFailure("warn") then confirmRules sends the warn-only shape', async () => {
    await mount();
    const c = proposeAndFinish();
    const comp: any = fixture.componentInstance;
    expect(typeof comp.setRuleOnFailure).withContext('AssistantComponent.setRuleOnFailure').toBe('function');
    comp.setRuleOnFailure(c, 'warn');
    const sendSpy = spyOn(state, 'send');
    comp.confirmRules(c);
    expect(sendSpy.calls.mostRecent().args[0]).toBe(WARN_CONFIRMED);
  });

  it('on failure: a transformation Confirm shape is unchanged (no parenthetical)', async () => {
    await mount();
    const c = proposeAndFinish('toolu_tr_of2', true, { kind: 'transformation', items: [{ text: 'trim whitespace', column: 'name' }] });
    const sendSpy = spyOn(state, 'send');
    (fixture.componentInstance as any).confirmRules(c);
    expect(sendSpy.calls.mostRecent().args[0]).toBe('Confirmed transformations for pipeline `sales_daily`: 1. name: trim whitespace');
    expect(c.ruleProposal.onFailure).withContext('transformation cards carry no onFailure').toBeUndefined();
  });

  it('on failure: the Warn only choice survives persist() and a new service instance', async () => {
    await mount();
    const c = proposeAndFinish();
    const comp: any = fixture.componentInstance;
    expect(typeof comp.setRuleOnFailure).withContext('AssistantComponent.setRuleOnFailure').toBe('function');
    comp.setRuleOnFailure(c, 'warn');
    (state as any).persist();
    const rc = restoredCard();
    expect(rc?.ruleProposal).toBeTruthy();
    expect(rc.ruleProposal.submitted).toBeFalse();
    expect(rc.ruleProposal.onFailure).toBe('warn');
    expect((stateModule as any).ruleReplyText(rc.ruleProposal)).toBe(WARN_CONFIRMED);
  });

  it('on failure: after Confirm the radios are gone and setRuleOnFailure on the submitted card changes nothing', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    expect(radios().length).withContext('radios before Confirm').toBe(2);
    const comp: any = fixture.componentInstance;
    comp.confirmRules(c);
    await render();
    expect(c.ruleProposal.submitted).toBeTrue();
    expect(radios().length).withContext('radios hidden once submitted').toBe(0);
    expect(typeof comp.setRuleOnFailure).withContext('AssistantComponent.setRuleOnFailure').toBe('function');
    comp.setRuleOnFailure(c, 'warn');
    expect(c.ruleProposal.onFailure).toBe('error');
    expect(c.ruleProposal.summary).toBe(ALL_CONFIRMED);
  });

  it('on failure: setRuleOnFailure while streaming changes nothing', async () => {
    await mount();
    const c = proposeAndFinish();
    const comp: any = fixture.componentInstance;
    expect(typeof comp.setRuleOnFailure).withContext('AssistantComponent.setRuleOnFailure').toBe('function');
    state.streaming = true;
    comp.setRuleOnFailure(c, 'warn');
    state.streaming = false;
    expect(c.ruleProposal.onFailure).toBe('error');
  });

  it('on failure: a restored card with no onFailure confirms as reject', async () => {
    await mount();
    const c = proposeAndFinish();
    delete c.ruleProposal.onFailure; // a card persisted before this change
    (state as any).persist();
    const rc = restoredCard();
    expect(rc?.ruleProposal).toBeTruthy();
    expect((stateModule as any).ruleReplyText(rc.ruleProposal)).toBe(ALL_CONFIRMED);
    // And through the component on the live card.
    const sendSpy = spyOn(state, 'send');
    (fixture.componentInstance as any).confirmRules(c);
    expect(sendSpy.calls.mostRecent().args[0]).toBe(ALL_CONFIRMED);
  });

  // -------------------------------------------------------------------
  // Story: Assistant cards: Clear all on rule proposals, exclusive "None" on
  // multi-select questions (plans/stories/assistant-card-clear-all-exclusive-option.md),
  // Acceptance bullet 1. One toggle button in the rule card's
  // `.schema-actions` row, reading "Clear all" while any row is ticked and
  // "Select all" when none is; `AssistantComponent.toggleAllRules(card)` and
  // `anyRuleTicked(rp)`. Rows are never removed.

  it('clear all: a card with three ticked rows shows Clear all; it unticks every row, disables Confirm and becomes Select all', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    expect(checkboxes().length).toBe(3);
    expect(checkboxes().every(b => b.checked)).toBeTrue();
    const clear = buttonNamed('Clear all');
    expect(clear).withContext('a Clear all button on the rule card').toBeTruthy();
    expect(ruleCard()!.contains(clear!)).withContext('Clear all lives inside .rule-card').toBeTrue();
    expect(buttonNamed('Select all')).withContext('no Select all while rows are ticked').toBeUndefined();
    clear!.click();
    await render();
    expect(checkboxes().length).withContext('rows stay after Clear all').toBe(3);
    expect(checkboxes().every(b => !b.checked)).withContext('every checkbox unticked').toBeTrue();
    expect(c.ruleProposal.items.every((i: any) => i.enabled === false)).toBeTrue();
    expect(c.ruleProposal.items.map((i: any) => i.text)).toEqual(items().map(i => i.text));
    expect(buttonNamed('Confirm')!.disabled).withContext('Confirm disabled with nothing ticked').toBeTrue();
    expect(buttonNamed('Clear all')).toBeUndefined();
    const select = buttonNamed('Select all');
    expect(select).withContext('the button now reads Select all').toBeTruthy();
    select!.click();
    await render();
    expect(checkboxes().length).toBe(3);
    expect(checkboxes().every(b => b.checked)).withContext('Select all ticks every row').toBeTrue();
    expect(c.ruleProposal.items.every((i: any) => i.enabled === true)).toBeTrue();
    expect(buttonNamed('Confirm')!.disabled).withContext('Confirm enabled again').toBeFalse();
    expect(buttonNamed('Clear all')).withContext('and the button reads Clear all again').toBeTruthy();
  });

  it('clear all: after Clear all, ticking one row and Confirm sends only that row', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    const clear = buttonNamed('Clear all');
    expect(clear).withContext('Clear all button').toBeTruthy();
    clear!.click();
    await render();
    checkboxes()[1].click();
    await render();
    const sendSpy = spyOn(state, 'send');
    const confirm = buttonNamed('Confirm')!;
    expect(confirm.disabled).toBeFalse();
    confirm.click();
    expect(sendSpy).toHaveBeenCalledTimes(1);
    expect(sendSpy.calls.mostRecent().args[0]).toBe(
      'Confirmed data-quality checks for pipeline `sales_daily` (on failure: reject the run): 1. created_at parses as a date');
  });

  it('clear all: after Clear all, Add rule then typing enables Confirm and the reply carries the new row only', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    const clear = buttonNamed('Clear all');
    expect(clear).withContext('Clear all button').toBeTruthy();
    clear!.click();
    await render();
    expect(buttonNamed('Confirm')!.disabled).toBeTrue();
    buttonNamed('Add rule')!.click();
    await render();
    expect(c.ruleProposal.items.length).toBe(4);
    const added = textInputs()[3];
    expect(added).withContext('the added row has a text input').toBeTruthy();
    added.value = 'qty is a whole number';
    added.dispatchEvent(new Event('input'));
    await render();
    const confirm = buttonNamed('Confirm')!;
    expect(confirm.disabled).withContext('typing into the added row enables Confirm').toBeFalse();
    const sendSpy = spyOn(state, 'send');
    confirm.click();
    expect(sendSpy).toHaveBeenCalledTimes(1);
    expect(sendSpy.calls.mostRecent().args[0]).toBe(
      'Confirmed data-quality checks for pipeline `sales_daily` (on failure: reject the run): 1. qty is a whole number');
  });

  it('clear all: the transformations card has the same button', async () => {
    await mount();
    const c = proposeAndFinish('toolu_tr_clear', true, { kind: 'transformation', items: [{ text: 'trim whitespace', column: 'name' }] });
    c.expanded = true;
    await render();
    expect(buttonNamed('Clear all')).withContext('Clear all on the transformations card').toBeTruthy();
  });

  it('clear all: toggleAllRules while streaming, on a submitted card and on a skipped card changes no enabled flag', async () => {
    await mount();
    const comp: any = fixture.componentInstance;
    const flags = (c: any) => c.ruleProposal.items.map((i: any) => i.enabled);

    const c = proposeAndFinish();
    expect(typeof comp.toggleAllRules).withContext('AssistantComponent.toggleAllRules').toBe('function');
    // Sanity: the method does work on an open card.
    comp.toggleAllRules(c);
    expect(flags(c)).withContext('toggleAllRules on an open card clears every row').toEqual([false, false, false]);
    comp.toggleAllRules(c);
    expect(flags(c)).toEqual([true, true, true]);

    state.streaming = true;
    comp.toggleAllRules(c);
    state.streaming = false;
    expect(flags(c)).withContext('streaming: no change').toEqual([true, true, true]);

    comp.confirmRules(c);
    expect(c.ruleProposal.submitted).toBeTrue();
    comp.toggleAllRules(c);
    expect(flags(c)).withContext('submitted: no change').toEqual([true, true, true]);

    // The first turn completed the shared stream; the second needs a fresh one.
    stream = new Subject<any>();
    const s = proposeAndFinish('toolu_rp_skip');
    s.ruleProposal.skipped = true;
    comp.toggleAllRules(s);
    expect(flags(s)).withContext('skipped: no change').toEqual([true, true, true]);
  });
});
