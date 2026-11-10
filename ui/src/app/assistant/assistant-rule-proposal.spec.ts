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

const ALL_CONFIRMED =
  'Confirmed data-quality checks for pipeline `sales_daily`: ' +
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
      ? Array.from(root.querySelectorAll('input:not([type="checkbox"]), textarea')) as HTMLInputElement[]
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
      'Confirmed data-quality checks for pipeline `sales_daily`: ' +
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
});
