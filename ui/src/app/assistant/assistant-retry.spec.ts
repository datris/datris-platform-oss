/**
 * Assistant: automatic retry of a failed model call. The server retries a
 * transient provider error itself and sends `retrying {attempt, max, reason}`
 * before each retry; the turn drops what the failed attempt streamed (back to
 * the iteration's start), shows a transient status, and the retried stream
 * replaces it. Completed tool cards from earlier iterations stay. No button.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { of, Subject } from 'rxjs';

import { AssistantComponent } from './assistant.component';
import { AssistantStateService } from './assistant-state.service';
import { AssistantService } from '../assistant.service';
import { SecretsService } from '../secrets.service';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { ApprovalsService } from '../approvals.service';
import { handleRetryEvent } from '../shared/chat-retry';

const INIT = {
  toolCount: 0, toolNames: [], workflowReference: '',
  provider: 'anthropic', model: 'claude-opus-5-5', extendedThinking: false
};

describe('Assistant automatic retry (retrying event)', () => {
  let fixture: ComponentFixture<AssistantComponent>;
  let el: HTMLElement;
  let state: AssistantStateService;
  let stream: Subject<any>;

  beforeEach(() => {
    sessionStorage.removeItem('assistant.chat.transcript');
    stream = new Subject<any>();
    TestBed.configureTestingModule({
      declarations: [AssistantComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideRouter([]),
        AssistantStateService,
        { provide: AssistantService, useValue: { init: () => of(INIT), chat: () => stream.asObservable() } },
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
    fixture.detectChanges();
  }

  function turn(): any {
    return state.turns[state.turns.length - 1];
  }

  function text(): string {
    return (el.textContent || '').replace(/\s+/g, ' ');
  }

  /** Iteration 1 completes a tool call; iteration 2 starts streaming. */
  function firstIterationDone(): void {
    state.send('build a tap from a CSV URL');
    stream.next({ type: 'iteration_start' });
    stream.next({ type: 'text_delta', text: 'Creating the tap. ' });
    stream.next({ type: 'tool_use_start', id: 't1', name: 'create_tap' });
    stream.next({ type: 'tool_use', id: 't1', name: 'create_tap', input: { name: 'x' } });
    stream.next({ type: 'tool_result', id: 't1', name: 'create_tap', result: '{"ok":true}', isError: false });
    stream.next({ type: 'iteration_start' });
  }

  it('drops the failed attempt\'s partial output, keeps earlier tool cards, and shows a status', async () => {
    await mount();
    firstIterationDone();
    stream.next({ type: 'thinking_delta', text: 'half a thought' });
    stream.next({ type: 'text_delta', text: 'Now testing the t' });
    stream.next({ type: 'tool_use_start', id: 't2', name: 'test_tap' });
    stream.next({ type: 'retrying', attempt: 1, max: 2, reason: 'connection problem' });
    fixture.detectChanges();
    const segs = turn().segments;
    expect(segs.some((s: any) => s.kind === 'tool' && s.id === 't1' && s.status === 'ok')).withContext('completed card kept').toBeTrue();
    expect(segs.some((s: any) => s.kind === 'tool' && s.id === 't2')).withContext('failed attempt card dropped').toBeFalse();
    expect(turn().thinking).not.toContain('half a thought');
    expect(text()).not.toContain('Now testing the t');
    expect(text()).toContain('Connection problem, retrying (1 of 2)');
    expect(text()).toContain('Creating the tap.');
  });

  it('the retried stream replaces the status', async () => {
    await mount();
    firstIterationDone();
    stream.next({ type: 'text_delta', text: 'partial' });
    stream.next({ type: 'retrying', attempt: 1, max: 2, reason: 'connection problem' });
    stream.next({ type: 'text_delta', text: 'Tap tested.' });
    stream.next({ type: 'done' });
    stream.complete();
    fixture.detectChanges();
    expect(text()).not.toContain('retrying (1 of 2)');
    expect(text()).not.toContain('partial');
    expect(text()).toContain('Tap tested.');
    expect(Array.from(el.querySelectorAll('button')).some(b => (b.textContent || '').trim() === 'Retry'))
      .withContext('no Retry button').toBeFalse();
  });

  it('a second retry replaces the first status; exhausted retries end with the error, no button', async () => {
    await mount();
    firstIterationDone();
    stream.next({ type: 'retrying', attempt: 1, max: 2, reason: 'connection problem' });
    stream.next({ type: 'text_delta', text: 'again partial' });
    stream.next({ type: 'retrying', attempt: 2, max: 2, reason: 'connection problem' });
    fixture.detectChanges();
    expect(text()).toContain('retrying (2 of 2)');
    expect(text()).not.toContain('retrying (1 of 2)');
    expect(text()).not.toContain('again partial');
    stream.next({ type: 'error', message: 'SSLException: bad_record_mac' });
    stream.complete();
    fixture.detectChanges();
    expect(text()).not.toContain('retrying (2 of 2)');
    expect(text()).toContain('SSLException: bad_record_mac');
    expect(Array.from(el.querySelectorAll('button')).some(b => (b.textContent || '').trim() === 'Retry')).toBeFalse();
  });

  it('an overload retry says the provider is busy', () => {
    const t: any = { thinking: '', segments: [] };
    handleRetryEvent({ type: 'iteration_start' }, t);
    handleRetryEvent({ type: 'retrying', attempt: 1, max: 2, reason: 'provider overloaded' }, t);
    expect(t.segments[0].message).toBe('The AI provider is busy, retrying (1 of 2)…');
  });

  it('a persisted transcript without the retry fields still loads and renders', async () => {
    sessionStorage.setItem('assistant.chat.transcript', JSON.stringify({
      turns: [
        { role: 'user', text: 'hello' },
        { role: 'assistant', thinking: '', thinkingExpanded: false, done: true, errorMessage: 'SSLException: old', segments: [{ kind: 'text', text: 'Earlier reply.' }] }
      ],
      draft: ''
    }));
    const restored = new AssistantStateService(TestBed.inject(AssistantService));
    expect(restored.turns.length).toBe(2);
    expect((restored.turns[1] as any).segments[0].text).toBe('Earlier reply.');
    sessionStorage.removeItem('assistant.chat.transcript');
  });
});
