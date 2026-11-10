/**
 * Assistant: Retry on a turn that ended with an error. Resends the user
 * message that started the failed turn (a card reply included) instead of
 * making the user type "continue".
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

const INIT = {
  toolCount: 0, toolNames: [], workflowReference: '',
  provider: 'anthropic', model: 'claude-opus-5-5', extendedThinking: false
};

describe('Assistant Retry on an errored turn', () => {
  let fixture: ComponentFixture<AssistantComponent>;
  let el: HTMLElement;
  let state: AssistantStateService;
  let streams: Subject<any>[];
  let chatSpy: jasmine.Spy;

  beforeEach(() => {
    sessionStorage.removeItem('assistant.chat.transcript');
    streams = [];
    chatSpy = jasmine.createSpy('chat').and.callFake(() => {
      const s = new Subject<any>();
      streams.push(s);
      return s.asObservable();
    });
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

  function retryButton(): HTMLButtonElement | undefined {
    return Array.from(el.querySelectorAll('button')).find(b => (b.textContent || '').trim() === 'Retry') as HTMLButtonElement | undefined;
  }

  function failTurn(i: number, before?: (s: Subject<any>) => void): void {
    const s = streams[i];
    before?.(s);
    s.next({ type: 'error', message: 'SSLException: (bad_record_mac) Received fatal alert: bad_record_mac' });
    s.complete();
  }

  it('an errored turn shows Retry; Retry resends the same message and replaces an empty failed turn', async () => {
    await mount();
    state.send('build a tap from a CSV URL');
    failTurn(0);
    fixture.detectChanges();
    const btn = retryButton();
    expect(btn).withContext('Retry button beside the error').toBeTruthy();
    btn!.click();
    fixture.detectChanges();
    expect(chatSpy).toHaveBeenCalledTimes(2);
    const msgs: any[] = chatSpy.calls.mostRecent().args[0];
    expect(msgs.map(m => m.role)).toEqual(['user']);
    expect(msgs[0].content).toBe('build a tap from a CSV URL');
    expect(state.turns.length).withContext('the empty failed turn and its user turn are replaced').toBe(2);
    expect(el.textContent).not.toContain('bad_record_mac');
  });

  it('a failed turn with partial output stays, its error collapses, and the message is resent', async () => {
    await mount();
    state.send('build a tap from a CSV URL');
    failTurn(0, s => s.next({ type: 'text_delta', text: 'Creating the tap now.' }));
    fixture.detectChanges();
    retryButton()!.click();
    fixture.detectChanges();
    const failed: any = state.turns[1];
    expect(failed.retried).toBeTrue();
    expect(state.turns.length).toBe(4);
    expect((state.turns[2] as any).text).toBe('build a tap from a CSV URL');
    expect(el.textContent).toContain('retried below');
    expect(el.textContent).not.toContain('bad_record_mac');
    const msgs: any[] = chatSpy.calls.mostRecent().args[0];
    expect(msgs.map(m => m.role)).toEqual(['user', 'assistant', 'user']);
  });

  it('a card reply is resent as the same text', async () => {
    await mount();
    state.send('first');
    streams[0].next({ type: 'done' });
    streams[0].complete();
    state.send('Confirmed schema for tap `t` (20 rows reviewed): id int');
    failTurn(1);
    state.retryLast();
    const msgs: any[] = chatSpy.calls.mostRecent().args[0];
    expect(msgs[msgs.length - 1].content).toBe('Confirmed schema for tap `t` (20 rows reviewed): id int');
  });

  it('no Retry for a Stop, an older turn, or while streaming', async () => {
    await mount();
    state.send('one');
    failTurn(0);
    state.send('two');
    expect(state.canRetry(state.turns[1])).withContext('older errored turn').toBeFalse();
    expect(state.canRetry(state.turns[3])).withContext('still streaming').toBeFalse();
    state.stop();
    expect(state.canRetry(state.turns[3])).withContext('stopped by the user').toBeFalse();
    fixture.detectChanges();
    expect(retryButton()).toBeUndefined();
  });
});
