/**
 * Story: Assistant schema-proposal card: inline type grid with pull-more-rows
 * (plans/stories/assistant-preview-confirm-schema-ui-card.md), Steps 3-6;
 * unit-level coverage of Acceptance bullets 2-4 (the live-stack parts of
 * those bullets stay manual / e2e).
 *
 * The server emits `schema_proposal {type,id,tap,typeGrid,sampleRowCount,
 * fields,preview}` when `propose_pipeline_schema` returns a proposal.
 * AssistantStateService attaches `schemaProposal = { tap, typeGrid,
 * sampleRowCount, preview, fields (editable copies), limit: 20, loading,
 * submitted, errorMessage }` to the matching tool card (synthesizing it if no
 * tool_use arrived). AssistantComponent renders preview + grid, and
 * `confirmSchemaGrid` / `keepAllStrings` post a fixed-shape reply through the
 * credentials-form resume path; `pullMoreRows` re-runs the tap test with a
 * higher limit and re-proposes without a model turn.
 *
 * New symbols are reached loosely (`as any`) so a missing method fails these
 * specs at runtime instead of breaking compilation of every other spec.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { of, Subject, throwError } from 'rxjs';

import { AssistantComponent } from './assistant.component';
import { AssistantStateService } from './assistant-state.service';
import { AssistantService } from '../assistant.service';
import { SecretsService } from '../secrets.service';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { ApprovalsService } from '../approvals.service';

const TYPES = ['string', 'boolean', 'int', 'bigint', 'float', 'double', 'date', 'timestamp'];

const LONG_VALUE = 'L'.repeat(200);

function fields(): any[] {
  return [
    { name: 'id', type: 'int', samples: ['1', '2'], stringReason: null, note: null },
    { name: 'price', type: 'string', samples: ['9.99', 'N/A'], stringReason: { value: 'N/A', blocked: 'double' }, note: null },
    { name: 'qty', type: 'string', samples: ['3'], stringReason: null, note: 'only 1 typed value' }
  ];
}

function previewRows(n: number): any[] {
  return Array.from({ length: n }, (_, i) => ({ id: String(i + 1), price: `row${i + 1}price`, qty: String(i) }));
}

function proposalEvent(id: string, overrides: any = {}): any {
  return {
    type: 'schema_proposal', id, tap: 'prices_tap', typeGrid: true, sampleRowCount: 20,
    fields: fields(), preview: previewRows(3), ...overrides
  };
}

const INIT = {
  toolCount: 0, toolNames: [], workflowReference: '',
  provider: 'anthropic', model: 'claude-opus-5-5', extendedThinking: false
};

describe('Assistant schema-proposal card (schema_proposal)', () => {
  let fixture: ComponentFixture<AssistantComponent>;
  let el: HTMLElement;
  let state: AssistantStateService;
  let stream: Subject<any>;
  let chatSpy: jasmine.Spy;
  let runTapSpy: jasmine.Spy;
  let proposeSpy: jasmine.Spy;

  beforeEach(() => {
    sessionStorage.removeItem('assistant.chat.transcript');
    stream = new Subject<any>();
    chatSpy = jasmine.createSpy('chat').and.callFake(() => stream.asObservable());
    runTapSpy = jasmine.createSpy('runTap');
    proposeSpy = jasmine.createSpy('proposeSchema');
    TestBed.configureTestingModule({
      declarations: [AssistantComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideRouter([]),
        AssistantStateService,
        { provide: AssistantService, useValue: { init: () => of(INIT), chat: chatSpy } },
        { provide: SecretsService, useValue: { listSecrets: () => of([]) } },
        { provide: TapService, useValue: { generateStatus: () => of({ active: false }), runTap: runTapSpy } },
        { provide: PipelineService, useValue: { proposeSchema: proposeSpy } },
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

  /** Start a turn and end it on a schema_proposal card (the server stops the loop). */
  function proposeAndFinish(id = 'toolu_sp', withToolUse = true, overrides: any = {}): any {
    state.send('build a tap and pipeline from a CSV URL into Postgres');
    if (withToolUse) {
      stream.next({ type: 'tool_use_start', id, name: 'propose_pipeline_schema' });
      stream.next({ type: 'tool_use', id, name: 'propose_pipeline_schema', input: { tap: 'prices_tap', destination: 'postgres' } });
    }
    stream.next(proposalEvent(id, overrides));
    stream.next({ type: 'done' });
    stream.complete();
    return card(id);
  }

  function text(): string {
    return (el.textContent || '').replace(/\s+/g, ' ');
  }

  function selects(): HTMLSelectElement[] {
    return Array.from(el.querySelectorAll('select')) as HTMLSelectElement[];
  }

  /** The type <select> for a grid row: the one whose options are exactly the type list. */
  function typeSelects(): HTMLSelectElement[] {
    return selects().filter(s => {
      const opts = Array.from(s.options).map(o => (o.textContent || '').trim());
      return opts.length === TYPES.length && TYPES.every(t => opts.includes(t));
    });
  }

  async function pickType(row: number, type: string): Promise<void> {
    const sel = typeSelects()[row];
    expect(sel).withContext(`a type dropdown for grid row ${row}`).toBeTruthy();
    const idx = Array.from(sel.options).findIndex(o => (o.textContent || '').trim() === type);
    sel.selectedIndex = idx;
    sel.dispatchEvent(new Event('change'));
    await render();
  }

  function buttonNamed(label: string): HTMLButtonElement | undefined {
    return Array.from(el.querySelectorAll('button')).find(b => (b.textContent || '').trim() === label) as HTMLButtonElement | undefined;
  }

  // ------------------------------------------------------------ state ---

  it('a schema_proposal event with no prior tool_use synthesizes a tool card with schemaProposal', () => {
    const c = proposeAndFinish('toolu_syn', false);
    expect(c).withContext('a tool card must be synthesized for the schema_proposal id').toBeTruthy();
    expect(c.name).toBe('propose_pipeline_schema');
    expect(c.status).not.toBe('running');
    const sp = c.schemaProposal;
    expect(sp).withContext('schemaProposal must be set on the card').toBeTruthy();
    expect(sp.tap).toBe('prices_tap');
    expect(sp.typeGrid).toBeTrue();
    expect(sp.sampleRowCount).toBe(20);
    expect(sp.preview).toEqual(previewRows(3));
    expect(sp.fields.map((f: any) => [f.name, f.type])).toEqual([['id', 'int'], ['price', 'string'], ['qty', 'string']]);
    expect(sp.limit).toBe(20);
    expect(sp.loading).toBeFalsy();
    expect(sp.submitted).toBeFalse();
    expect(sp.errorMessage || '').toBe('');
    expect(state.streaming).toBeFalse();
  });

  it('a schema_proposal after tool_use attaches to the existing card (no duplicate) and fields are editable copies', () => {
    state.send('build a tap and pipeline from a CSV URL into Postgres');
    const evt = proposalEvent('toolu_att');
    stream.next({ type: 'tool_use_start', id: 'toolu_att', name: 'propose_pipeline_schema' });
    stream.next({ type: 'tool_use', id: 'toolu_att', name: 'propose_pipeline_schema', input: { tap: 'prices_tap' } });
    stream.next(evt);
    stream.next({ type: 'done' });
    stream.complete();
    const turn: any = state.turns[state.turns.length - 1];
    const cards = turn.segments.filter((s: any) => s.kind === 'tool' && s.id === 'toolu_att');
    expect(cards.length).toBe(1);
    const sp = cards[0].schemaProposal;
    expect(sp).toBeTruthy();
    sp.fields[1].type = 'double';
    expect(evt.fields[1].type).withContext('editing the card must not mutate the event payload').toBe('string');
  });

  it('a later tool_result for the same id keeps the card\'s proposal state', () => {
    state.send('build a tap and pipeline from a CSV URL into Postgres');
    stream.next({ type: 'tool_use_start', id: 'toolu_tr', name: 'propose_pipeline_schema' });
    stream.next(proposalEvent('toolu_tr'));
    stream.next({ type: 'tool_result', id: 'toolu_tr', name: 'propose_pipeline_schema', result: 'The proposal is shown to the user as a type grid', isError: false });
    stream.next({ type: 'done' });
    stream.complete();
    expect(card('toolu_tr').schemaProposal?.fields?.length).toBe(3);
  });

  // ---------------------------------------------------------- template ---

  it('a pending card renders preview rows and a grid of column, type dropdown (with its reason hint) and samples; the generic tool body is hidden', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    const t = text();
    expect(t).toContain('row1price');
    expect(t).toContain('row3price');
    for (const f of fields()) expect(t).toContain(f.name);
    expect(t).toContain('9.99');
    expect(t).toContain("found 'N/A', would be double");
    expect(t).withContext('a thin-evidence note is shown as the reason').toContain('only 1 typed value');
    const sels = typeSelects();
    expect(sels.length).withContext('one type dropdown per column, offering the dest-types list').toBe(3);
    expect(sels.map(s => (s.options[s.selectedIndex]?.textContent || '').trim())).toEqual(['int', 'string', 'string']);
    expect(buttonNamed('Confirm types')).withContext('Confirm types button').toBeTruthy();
    expect(buttonNamed('Keep all as text')).withContext('Keep all as text button').toBeTruthy();
    expect(buttonNamed('Pull more rows')).withContext('Pull more rows button').toBeTruthy();
    expect(el.querySelector('.tool-body')).withContext('generic tool body must be hidden for a schema-proposal card').toBeNull();
  });

  // Requirement change (user, 2026-11-10): the preview shows every row of the
  // latest tap test (render cap 200, scrollable), not just 5.
  it('the preview table shows every row the event carries, with a showing-N-of-M footer', async () => {
    await mount();
    const c = proposeAndFinish('toolu_rows', true, { preview: previewRows(7) });
    c.expanded = true;
    await render();
    const t = text();
    expect(t).toContain('row5price');
    expect(t).toContain('row7price');
    expect(t).toContain('Showing 7 of 20 rows');
  });

  it('a long preview cell is clipped, not rendered in full', async () => {
    await mount();
    const preview = [{ id: '1', price: LONG_VALUE, qty: '1' }];
    const c = proposeAndFinish('toolu_clip', true, { preview });
    c.expanded = true;
    await render();
    const t = text();
    expect(t).toContain('L'.repeat(40));
    expect(t).not.toContain(LONG_VALUE);
  });

  it('preview columns come from the union of preview row keys', async () => {
    await mount();
    const preview = [{ id: '1', price: '2' }, { id: '2', extra_col: 'only-in-row-two' }];
    const c = proposeAndFinish('toolu_union', true, { preview });
    c.expanded = true;
    await render();
    const t = text();
    expect(t).toContain('extra_col');
    expect(t).toContain('only-in-row-two');
  });

  // Story: Assistant: preview-only schema card is informational
  // (plans/stories/assistant-preview-card-informational.md): a typeGrid false
  // card is a sample-data card with no buttons (replaces "single Looks right").
  it('a vector proposal (typeGrid false) shows the preview as "Sample data from", no buttons, no grid', async () => {
    await mount();
    const c = proposeAndFinish('toolu_vec', true, { typeGrid: false, fields: [], sampleRowCount: 3 });
    c.expanded = true;
    await render();
    const t = text();
    expect(t).toContain('row1price');
    expect(t).toContain('Sample data from prices_tap');
    expect(t).not.toContain('Proposed schema for');
    expect(typeSelects().length).withContext('no type dropdowns for a vector destination').toBe(0);
    const sc = el.querySelector('.schema-card');
    expect(sc).withContext('the sample-data card renders').toBeTruthy();
    expect(sc!.querySelectorAll('button').length).withContext('no buttons on a sample-data card').toBe(0);
    expect(buttonNamed('Looks right')).toBeUndefined();
    expect(buttonNamed('Confirm types')).toBeUndefined();
    expect(buttonNamed('Keep all as text')).toBeUndefined();
    expect(buttonNamed('Pull more rows')).toBeUndefined();
  });

  // --------------------------------------------------------- component ---

  it('changing one type and pressing Confirm types sends the confirmation with the grid types and marks the card submitted', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    await pickType(1, 'double');
    expect(c.schemaProposal.fields[1].type).toBe('double');
    const sendSpy = spyOn(state, 'send').and.callThrough();
    const btn = buttonNamed('Confirm types');
    expect(btn).withContext('Confirm types button').toBeTruthy();
    btn!.click();
    await render();
    expect(sendSpy).toHaveBeenCalledTimes(1);
    const msg: string = sendSpy.calls.mostRecent().args[0];
    expect(msg.startsWith('Confirmed schema for tap `prices_tap`')).withContext(msg).toBeTrue();
    expect(msg).toContain('(20 rows reviewed)');
    expect(msg).toContain('id int, price double, qty string');
    expect(c.schemaProposal.submitted).toBeTrue();
  });

  it('confirmSchemaGrid uses the fixed reply shape', async () => {
    await mount();
    const c = proposeAndFinish();
    const sendSpy = spyOn(state, 'send');
    const comp: any = fixture.componentInstance;
    expect(typeof comp.confirmSchemaGrid).withContext('AssistantComponent.confirmSchemaGrid').toBe('function');
    comp.confirmSchemaGrid(c);
    expect(sendSpy.calls.mostRecent().args[0])
      .toBe('Confirmed schema for tap `prices_tap` (20 rows reviewed): id int, price string, qty string');
  });

  it('keepAllStrings sends the keep-all-text reply and marks the card submitted', async () => {
    await mount();
    const c = proposeAndFinish();
    const sendSpy = spyOn(state, 'send');
    const comp: any = fixture.componentInstance;
    expect(typeof comp.keepAllStrings).withContext('AssistantComponent.keepAllStrings').toBe('function');
    comp.keepAllStrings(c);
    expect(sendSpy).toHaveBeenCalledTimes(1);
    expect(sendSpy.calls.mostRecent().args[0]).toBe('Keep every column as string for tap `prices_tap`.');
    expect(c.schemaProposal.submitted).toBeTrue();
  });

  it('confirmSchemaGrid / keepAllStrings / pullMoreRows on a preview-only card send nothing (never "Preview looks right.")', async () => {
    await mount();
    const c = proposeAndFinish('toolu_vec2', true, { typeGrid: false, fields: [] });
    c.expanded = true;
    await render();
    const sendSpy = spyOn(state, 'send');
    const chatCalls = chatSpy.calls.count();
    const comp: any = fixture.componentInstance;
    comp.confirmSchemaGrid(c);
    comp.keepAllStrings(c);
    comp.pullMoreRows(c);
    expect(sendSpy).not.toHaveBeenCalled();
    expect(sendSpy).not.toHaveBeenCalledWith('Preview looks right.');
    expect(runTapSpy).not.toHaveBeenCalled();
    expect(chatSpy.calls.count()).toBe(chatCalls);
    expect(c.schemaProposal.submitted).toBeFalsy();
  });

  it('a submitted card sends nothing a second time and collapses to a summary (no grid)', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    const sendSpy = spyOn(state, 'send');
    const comp: any = fixture.componentInstance;
    expect(typeof comp.confirmSchemaGrid).withContext('AssistantComponent.confirmSchemaGrid').toBe('function');
    comp.confirmSchemaGrid(c);
    comp.confirmSchemaGrid(c);
    comp.keepAllStrings?.(c);
    expect(sendSpy).toHaveBeenCalledTimes(1);
    await render();
    expect(typeSelects().length).withContext('the grid collapses once submitted').toBe(0);
    expect(buttonNamed('Confirm types')).toBeUndefined();
    expect(text()).toContain('prices_tap');
  });

  it('Pull more rows re-runs the tap test at limit x5, re-proposes, refreshes in place and keeps a user-changed type; no model turn', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    await pickType(1, 'double'); // user changed price: string -> double
    const records = previewRows(100);
    runTapSpy.and.returnValue(of({ status: 'success', records, columns: ['id', 'price', 'qty'] }));
    proposeSpy.and.returnValue(of({
      sampleRowCount: 100,
      fields: [
        { name: 'id', type: 'bigint', samples: ['1', '100'], stringReason: null, note: null },
        { name: 'price', type: 'string', samples: ['9.99', 'N/A', 'n/a'], stringReason: { value: 'n/a', blocked: 'double' }, note: null },
        { name: 'qty', type: 'int', samples: ['3', '4'], stringReason: null, note: null }
      ]
    }));
    const sendSpy = spyOn(state, 'send');
    const chatCalls = chatSpy.calls.count();

    const comp: any = fixture.componentInstance;
    expect(typeof comp.pullMoreRows).withContext('AssistantComponent.pullMoreRows').toBe('function');
    comp.pullMoreRows(c);
    await render();

    expect(runTapSpy).toHaveBeenCalledTimes(1);
    expect(runTapSpy.calls.mostRecent().args).toEqual(['prices_tap', 'test', 100]);
    expect(proposeSpy).toHaveBeenCalledTimes(1);
    expect(proposeSpy.calls.mostRecent().args[0]).toEqual(records);
    expect(proposeSpy.calls.mostRecent().args[1]).toEqual(['id', 'price', 'qty']);

    const sp = c.schemaProposal;
    expect(sp.limit).toBe(100);
    expect(sp.sampleRowCount).toBe(100);
    expect(sp.loading).toBeFalsy();
    expect(sp.submitted).toBeFalse();
    const types = Object.fromEntries(sp.fields.map((f: any) => [f.name, f.type]));
    expect(types['price']).withContext('the type the user changed survives the refresh').toBe('double');
    expect(types['id']).withContext('an untouched column takes the new recommendation').toBe('bigint');
    expect(types['qty']).toBe('int');
    expect(sp.fields.find((f: any) => f.name === 'price').stringReason).toEqual({ value: 'n/a', blocked: 'double' });
    expect(sp.preview.length).withContext('the preview shows every pulled row').toBe(100);
    expect(sp.preview[0]).toEqual(records[0]);
    expect(text()).toContain("found 'n/a', would be double");

    expect(sendSpy).withContext('pull more rows must not start a model turn').not.toHaveBeenCalled();
    expect(chatSpy.calls.count()).toBe(chatCalls);
  });

  it('Pull more rows caps the limit at 500', async () => {
    await mount();
    const c = proposeAndFinish();
    runTapSpy.and.returnValue(of({ status: 'success', records: previewRows(5), columns: ['id', 'price', 'qty'] }));
    proposeSpy.and.returnValue(of({ sampleRowCount: 5, fields: fields() }));
    const comp: any = fixture.componentInstance;
    expect(typeof comp.pullMoreRows).withContext('AssistantComponent.pullMoreRows').toBe('function');
    c.schemaProposal.limit = 200;
    comp.pullMoreRows(c);
    expect(runTapSpy.calls.mostRecent().args[2]).toBe(500);
    expect(c.schemaProposal.limit).toBe(500);
    comp.pullMoreRows(c);
    expect(runTapSpy.calls.mostRecent().args[2]).toBe(500);
  });

  it('a Pull more rows failure shows errorMessage and the card stays actionable', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    runTapSpy.and.returnValue(throwError(() => ({ status: 500, error: { error: 'script exited 1' } })));
    const sendSpy = spyOn(state, 'send');
    const comp: any = fixture.componentInstance;
    expect(typeof comp.pullMoreRows).withContext('AssistantComponent.pullMoreRows').toBe('function');
    comp.pullMoreRows(c);
    await render();
    const sp = c.schemaProposal;
    expect((sp.errorMessage || '').length).withContext('errorMessage is set').toBeGreaterThan(0);
    expect(sp.loading).toBeFalsy();
    expect(sp.submitted).toBeFalse();
    expect(sp.fields.map((f: any) => f.type)).toEqual(['int', 'string', 'string']);
    expect(sendSpy).not.toHaveBeenCalled();
    expect(buttonNamed('Confirm types')?.disabled).withContext('Confirm types is still usable').toBeFalse();
  });

  it('a Pull more rows that returns a failed tap test (status != success) shows errorMessage and keeps the grid', async () => {
    await mount();
    const c = proposeAndFinish();
    runTapSpy.and.returnValue(of({ status: 'failed', error: 'script exited 1' }));
    const comp: any = fixture.componentInstance;
    expect(typeof comp.pullMoreRows).withContext('AssistantComponent.pullMoreRows').toBe('function');
    comp.pullMoreRows(c);
    expect(proposeSpy).not.toHaveBeenCalled();
    expect((c.schemaProposal.errorMessage || '').length).toBeGreaterThan(0);
    expect(c.schemaProposal.fields.length).toBe(3);
  });

  // ------------------------------------------------- review round 1 ---

  it('a typed reply while a card is pending collapses it; its buttons then send nothing', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    state.send('actually use another destination instead');
    stream.complete();
    await render();
    expect(c.schemaProposal.skipped).withContext('the card is marked skipped').toBeTrue();
    expect(typeSelects().length).withContext('the skipped card hides its grid').toBe(0);
    expect(buttonNamed('Confirm types')).toBeUndefined();
    expect(text()).toContain('answered in chat');
    const sendSpy = spyOn(state, 'send');
    const comp: any = fixture.componentInstance;
    comp.confirmSchemaGrid(c);
    comp.keepAllStrings(c);
    comp.pullMoreRows(c);
    expect(sendSpy).not.toHaveBeenCalled();
    expect(runTapSpy).not.toHaveBeenCalled();
  });

  it('the card\'s own Confirm is not marked skipped by the reply it sends', async () => {
    await mount();
    const c = proposeAndFinish();
    (fixture.componentInstance as any).confirmSchemaGrid(c);
    expect(c.schemaProposal.submitted).toBeTrue();
    expect(c.schemaProposal.skipped).toBeFalsy();
  });

  it('a card-only turn sends the proposal as a synthetic propose_pipeline_schema tool call, not prose', async () => {
    // plans/stories/assistant-tool-call-continuity.md, Acceptance 9: the card
    // is replayed server-side as the real call with its waiting text.
    await mount();
    const c = proposeAndFinish('toolu_hist');
    (fixture.componentInstance as any).confirmSchemaGrid(c);
    const messages: any[] = chatSpy.calls.mostRecent().args[0];
    const roles = messages.map(m => m.role);
    expect(roles).withContext('user / assistant / user, never two user turns in a row').toEqual(['user', 'assistant', 'user']);
    const toolCalls: any[] = messages[1].toolCalls || [];
    expect(toolCalls.length).withContext('the proposal travels as a toolCalls entry').toBeGreaterThan(0);
    expect(toolCalls[0].name).toBe('propose_pipeline_schema');
    expect(toolCalls[0].synthetic).toBeTrue();
    expect(toolCalls[0].input).toEqual({ tap: 'prices_tap', destination: 'postgres' });
    const proposed: string = messages[1].content || '';
    expect(proposed).not.toContain('I proposed column types');
    expect(proposed).not.toContain('[Tool call');
    expect(messages[2].content.startsWith('Confirmed schema for tap `prices_tap`')).toBeTrue();
  });

  it('a Pull more rows interrupted by a reload does not leave the restored card disabled', () => {
    const c = proposeAndFinish();
    c.schemaProposal.loading = true;
    (state as any).persist();
    const restored = new AssistantStateService(TestBed.inject(AssistantService));
    const turn: any = restored.turns[restored.turns.length - 1];
    const rc = turn.segments.find((s: any) => s.kind === 'tool' && s.id === 'toolu_sp');
    expect(rc.schemaProposal.loading).toBeFalse();
    expect((rc.schemaProposal.errorMessage || '').length).toBeGreaterThan(0);
  });

  it('Confirm while a turn is streaming changes nothing and sends nothing', async () => {
    await mount();
    const c = proposeAndFinish();
    const sendSpy = spyOn(state, 'send');
    state.streaming = true;
    const comp: any = fixture.componentInstance;
    comp.confirmSchemaGrid(c);
    comp.keepAllStrings(c);
    expect(sendSpy).not.toHaveBeenCalled();
    expect(c.schemaProposal.submitted).toBeFalse();
    expect(c.schemaProposal.summary || '').toBe('');
    state.streaming = false;
  });

  it('a column name with spaces is wrapped in backticks in the confirmation', async () => {
    await mount();
    const c = proposeAndFinish('toolu_sp', true, {
      fields: [
        { name: 'first name', type: 'string', samples: ['a'], stringReason: null, note: null },
        { name: 'qty', type: 'int', samples: ['1'], stringReason: null, note: null }
      ]
    });
    const sendSpy = spyOn(state, 'send');
    (fixture.componentInstance as any).confirmSchemaGrid(c);
    expect(sendSpy.calls.mostRecent().args[0])
      .toBe('Confirmed schema for tap `prices_tap` (20 rows reviewed): `first name` string, qty int');
  });

  it('the reason cell formats the server\'s {value, blocked} stringReason, never [object Object]', async () => {
    await mount();
    const c = proposeAndFinish('toolu_reason', true, {
      fields: [
        { name: 'qty', type: 'string', samples: ['0', '2'], stringReason: { value: 'N/A', blocked: 'int' }, note: null },
        { name: 'id', type: 'int', samples: ['1'], stringReason: null, note: null }
      ]
    });
    c.expanded = true;
    await render();
    const t = text();
    expect(t).toContain("stayed text — found 'N/A', would be int");
    expect(t).not.toContain('[object Object]');
  });

  it('the grid has no Reason column; a reason is a hint under that row\'s type dropdown only', async () => {
    await mount();
    const c = proposeAndFinish();
    c.expanded = true;
    await render();
    const headers = Array.from(el.querySelectorAll('.schema-grid th')).map(h => (h.textContent || '').trim());
    expect(headers).toEqual(['Column', 'Type', 'Samples']);
    const rows = Array.from(el.querySelectorAll('.schema-grid tbody tr'));
    expect(rows.length).toBe(3);
    const typeCell = (r: Element) => r.querySelectorAll('td')[1];
    expect(typeCell(rows[0]).querySelector('.schema-reason')).withContext('no hint for a column with no reason').toBeNull();
    expect(typeCell(rows[1]).querySelector('.schema-reason')?.textContent).toContain("stayed text — found 'N/A', would be double");
    expect(typeCell(rows[2]).querySelector('.schema-reason')?.textContent).toContain('only 1 typed value');
  });

  it('with no reasons at all the grid shows no hints', async () => {
    await mount();
    const c = proposeAndFinish('toolu_noreason', true, {
      fields: [
        { name: 'id', type: 'int', samples: ['1'], stringReason: null, note: null },
        { name: 'qty', type: 'int', samples: ['2'], stringReason: null, note: null }
      ]
    });
    c.expanded = true;
    await render();
    expect(el.querySelectorAll('.schema-reason').length).toBe(0);
    expect(Array.from(el.querySelectorAll('.schema-grid th')).map(h => (h.textContent || '').trim())).toEqual(['Column', 'Type', 'Samples']);
  });

  it('the tool header names the tap being typed', async () => {
    await mount();
    proposeAndFinish();
    await render();
    expect(text()).toContain('Proposing column types for prices_tap');
  });

  it('the tool header of a preview-only (typeGrid false) card says Sampling data for the tap', async () => {
    await mount();
    proposeAndFinish('toolu_sample_hdr', true, { typeGrid: false, fields: [] });
    await render();
    expect(text()).toContain('Sampling data for prices_tap');
    expect(text()).not.toContain('Proposing column types');
  });

  // ------------------------------------------- live feedback 2026-11-10 ---

  it('the initial card shows the 5 event rows of 20 reviewed in the footer', async () => {
    await mount();
    const c = proposeAndFinish('toolu_f5', true, { preview: previewRows(5), sampleRowCount: 20 });
    c.expanded = true;
    await render();
    expect(text()).toContain('Showing 5 of 20 rows');
  });

  it('Pull more rows shows a pulling status while it runs and a result line when done', async () => {
    await mount();
    const c = proposeAndFinish();
    await render();
    const tapResult = new Subject<any>();
    runTapSpy.and.returnValue(tapResult.asObservable());
    proposeSpy.and.returnValue(of({ sampleRowCount: 100, fields: fields() }));
    buttonNamed('Pull more rows')!.click();
    await render();
    expect(text()).toContain('Pulling up to 100 rows');
    expect(buttonNamed('Pull more rows')?.disabled).toBeTrue();
    tapResult.next({ status: 'success', records: previewRows(100), columns: ['id', 'price', 'qty'] });
    tapResult.complete();
    await render();
    expect(text()).not.toContain('Pulling up to');
    expect(text()).toContain('Pulled 100 rows; types re-checked.');
    expect(text()).toContain('100 rows');
    expect(text()).toContain('row100price');
    expect(buttonNamed('Pull more rows')?.disabled).toBeFalse();
  });

  it('a pull of more than 200 rows renders 200 and says so', async () => {
    await mount();
    const c = proposeAndFinish();
    c.schemaProposal.limit = 100;
    runTapSpy.and.returnValue(of({ status: 'success', records: previewRows(300), recordsTruncated: true, columns: ['id', 'price', 'qty'] }));
    proposeSpy.and.returnValue(of({ sampleRowCount: 300, fields: fields() }));
    (fixture.componentInstance as any).pullMoreRows(c);
    await render();
    expect(c.schemaProposal.preview.length).toBe(200);
    expect(text()).toContain('Showing 200 of 300 rows');
    expect(text()).toContain('row200price');
    expect(text()).not.toContain('row201price');
  });

  it('a tap with fewer rows than the default test has nothing to pull, and the card says why', async () => {
    await mount();
    const c = proposeAndFinish('toolu_few', true, { preview: previewRows(5), sampleRowCount: 5 });
    await render();
    expect(c.schemaProposal.exhausted).toBeTrue();
    expect(buttonNamed('Pull more rows')?.disabled).toBeTrue();
    expect(text()).toContain('All 5 rows pulled');
    (fixture.componentInstance as any).pullMoreRows(c);
    expect(runTapSpy).not.toHaveBeenCalled();
  });

  it('recordsTruncated on the event keeps Pull more rows available even with few rows', async () => {
    await mount();
    const c = proposeAndFinish('toolu_trunc', true, { preview: previewRows(5), sampleRowCount: 5, recordsTruncated: true });
    await render();
    expect(c.schemaProposal.exhausted).toBeFalsy();
    expect(buttonNamed('Pull more rows')?.disabled).toBeFalse();
  });

  it('a pull that returns fewer rows than asked for marks the tap exhausted', async () => {
    await mount();
    const c = proposeAndFinish();
    runTapSpy.and.returnValue(of({ status: 'success', records: previewRows(7), recordCount: 7, columns: ['id', 'price', 'qty'] }));
    proposeSpy.and.returnValue(of({ sampleRowCount: 7, fields: fields() }));
    (fixture.componentInstance as any).pullMoreRows(c);
    await render();
    expect(text()).toContain('Pulled 7 rows (all the tap returned); types re-checked.');
    expect(text()).toContain('All 7 rows pulled');
    expect(buttonNamed('Pull more rows')?.disabled).toBeTrue();
  });

  it('at the 500-row cap the button is disabled and says Maximum 500 rows', async () => {
    await mount();
    const c = proposeAndFinish();
    c.schemaProposal.limit = 500;
    await render();
    expect(buttonNamed('Pull more rows')?.disabled).toBeTrue();
    expect(text()).toContain('Maximum 500 rows');
  });

  it('while a turn is streaming the button is disabled and says why', async () => {
    await mount();
    proposeAndFinish();
    state.streaming = true;
    await render();
    expect(buttonNamed('Pull more rows')?.disabled).toBeTrue();
    expect(text()).toContain('Wait for the Assistant to finish');
    state.streaming = false;
  });

  it('the pull-more-rows limit starts from the rows the proposal used', () => {
    const c = proposeAndFinish('toolu_lim', true, { sampleRowCount: 100 });
    expect(c.schemaProposal.limit).toBe(100);
  });

  // ------------------- preview-only card is informational (2026-11-11) ---
  // Story: plans/stories/assistant-preview-card-informational.md, Acceptance 4.

  const MONGO_RAW = JSON.stringify({
    status: 'schema_proposal', typeGrid: false, tap: 'prices_tap', destination: 'mongodb',
    preview: previewRows(3), sampleRowCount: 3,
    next: 'Show the preview rows, ask the one question, then call create_pipeline.'
  });

  /** A MongoDB turn: the server no longer stops on the preview-only card, so the
   *  tool_result (raw JSON) follows and the reply continues in the same turn. */
  function previewOnlyTurn(id = 'toolu_mongo'): any {
    state.send('build a tap and pipeline from a JSON URL into MongoDB');
    stream.next({ type: 'tool_use_start', id, name: 'propose_pipeline_schema' });
    stream.next({ type: 'tool_use', id, name: 'propose_pipeline_schema', input: { tap: 'prices_tap', destination: 'mongodb' } });
    stream.next(proposalEvent(id, { typeGrid: false, fields: [], sampleRowCount: 3 }));
    stream.next({ type: 'tool_result', id, name: 'propose_pipeline_schema', result: MONGO_RAW, isError: false });
    stream.next({ type: 'text_delta', text: 'Do you want data-quality checks, transformations or field protection?' });
    stream.next({ type: 'done' });
    stream.complete();
    return card(id);
  }

  it('a typeGrid false card renders "Sample data from", the preview rows and "3 rows reviewed", with no buttons and no type selects', async () => {
    await mount();
    const c = previewOnlyTurn();
    c.expanded = true;
    await render();
    const t = text();
    expect(t).toContain('Sample data from prices_tap');
    expect(t).toContain('row1price');
    expect(t).toContain('row3price');
    expect(t).toContain('3 rows reviewed');
    const sc = el.querySelector('.schema-card');
    expect(sc).withContext('the sample-data card renders').toBeTruthy();
    expect(sc!.querySelectorAll('button').length).withContext('no <button> inside .schema-card').toBe(0);
    expect(typeSelects().length).toBe(0);
    expect(sc!.querySelectorAll('select').length).toBe(0);
  });

  it('a typed reply while a preview-only card is on screen leaves it rendered (not skipped, no "answered in chat")', async () => {
    await mount();
    const c = previewOnlyTurn();
    c.expanded = true;
    await render();
    stream = new Subject<any>();
    chatSpy.and.callFake(() => stream.asObservable());
    state.send('none');
    stream.next({ type: 'done' });
    stream.complete();
    await render();
    expect(c.schemaProposal.skipped).withContext('a preview-only card is never skipped').toBeFalsy();
    expect(text()).not.toContain('answered in chat');
    expect(text()).toContain('Sample data from prices_tap');
    expect(text()).toContain('row1price');
    expect(el.querySelector('.schema-card')).withContext('the sample-data card stays rendered').toBeTruthy();
  });

  it('a turn with a preview-only card sends it in toolCalls with synthetic falsy and its result text', async () => {
    await mount();
    previewOnlyTurn();
    stream = new Subject<any>();
    chatSpy.and.callFake(() => stream.asObservable());
    state.send('none');
    const messages: any[] = chatSpy.calls.mostRecent().args[0];
    expect(messages.map(m => m.role)).toEqual(['user', 'assistant', 'user']);
    const toolCalls: any[] = messages[1].toolCalls || [];
    const entry = toolCalls.find(tc => tc.name === 'propose_pipeline_schema');
    expect(entry).withContext('the preview-only card travels as a toolCalls entry').toBeTruthy();
    expect(entry.synthetic).withContext('a preview-only card is not synthetic').toBeFalsy();
    expect(entry.result).withContext('its real result travels').toBe(MONGO_RAW);
    expect(entry.input).toEqual({ tap: 'prices_tap', destination: 'mongodb' });
  });

  it('a typed card in the same flow is still sent as synthetic (unchanged)', async () => {
    await mount();
    const c = proposeAndFinish('toolu_typed_hist');
    (fixture.componentInstance as any).confirmSchemaGrid(c);
    const messages: any[] = chatSpy.calls.mostRecent().args[0];
    const entry = (messages[1].toolCalls || []).find((tc: any) => tc.name === 'propose_pipeline_schema');
    expect(entry?.synthetic).toBeTrue();
  });
});

describe('TapService.runTap / PipelineService.proposeSchema (schema-proposal card)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('runTap(name, "test", testLimit) posts testLimit in the body', () => {
    const tap: any = TestBed.inject(TapService);
    tap.runTap('prices_tap', 'test', 100).subscribe();
    const req = http.expectOne(r => r.url.startsWith('/api/v1/tap/run'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(jasmine.objectContaining({ name: 'prices_tap', mode: 'test', testLimit: 100 }));
    req.flush({ status: 'success', records: [] });
  });

  it('runTap without a testLimit is unchanged', () => {
    const tap: any = TestBed.inject(TapService);
    tap.runTap('prices_tap', 'run').subscribe();
    const req = http.expectOne(r => r.url.startsWith('/api/v1/tap/run'));
    expect(req.request.body.testLimit).toBeUndefined();
    expect(req.request.body).toEqual(jasmine.objectContaining({ name: 'prices_tap', mode: 'run' }));
    req.flush({});
  });

  it('proposeSchema(records, columns) posts both to /api/v1/pipeline/propose-schema', () => {
    const svc: any = TestBed.inject(PipelineService);
    expect(typeof svc.proposeSchema).withContext('PipelineService.proposeSchema').toBe('function');
    const records = [{ id: '1', price: '9.99' }];
    svc.proposeSchema(records, ['id', 'price']).subscribe();
    const req = http.expectOne(r => r.url.startsWith('/api/v1/pipeline/propose-schema'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ records, columns: ['id', 'price'] });
    req.flush({ sampleRowCount: 1, fields: [] });
  });
});
