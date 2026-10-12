/**
 * Story: Show the AI model in use (plans/stories/show-ai-model.md),
 * Acceptance bullets 3 and 6 (Assistant half).
 *
 * The Assistant empty state and its topbar name the CHAT provider and model
 * from `GET /api/v1/assistant/init` (`provider`, `model`, `extendedThinking`,
 * which come from `aiConfigForChat`), e.g.
 * "Anthropic · claude-fable-5-1 · extended thinking on". The line stays in
 * the topbar after the first message, links to Configuration → AI Providers,
 * and is the empty string when `model` is empty (never throws).
 *
 * Seams: `AssistantComponent.modelLine` (getter) and the shared
 * `providerDisplayName` helper in `ui/src/app/shared/provider-label.ts`.
 * Assertions read the `.empty-state` and `.assistant-topbar` text so the
 * exact inner element/class is the implementer's choice.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { AssistantComponent } from './assistant.component';
import { AssistantStateService } from './assistant-state.service';
import { AssistantService } from '../assistant.service';
import { SecretsService } from '../secrets.service';
import { TapService } from '../tap.service';
import { ApprovalsService } from '../approvals.service';

describe('AssistantComponent — model line', () => {
  let fixture: ComponentFixture<AssistantComponent>;
  let el: HTMLElement;

  function setup(init: any): void {
    sessionStorage.removeItem('assistant.chat.transcript');
    const api = {
      init: jasmine.createSpy('init').and.returnValue(of(init))
    };
    TestBed.configureTestingModule({
      declarations: [AssistantComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideRouter([]),
        AssistantStateService,
        { provide: AssistantService, useValue: api },
        { provide: SecretsService, useValue: { listSecrets: () => of([]) } },
        { provide: TapService, useValue: { generateStatus: () => of({ active: false }) } },
        { provide: ApprovalsService, useValue: {} }
      ]
    });
    fixture = TestBed.createComponent(AssistantComponent);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    fixture.detectChanges();
  }

  afterEach(() => {
    fixture?.destroy();
    sessionStorage.removeItem('assistant.chat.transcript');
  });

  const INIT = {
    toolCount: 0, toolNames: [], workflowReference: '',
    provider: 'anthropic', model: 'claude-fable-5-1', extendedThinking: true
  };

  function text(sel: string): string {
    const n = el.querySelector(sel);
    expect(n).withContext(sel).not.toBeNull();
    return (n?.textContent || '').replace(/\s+/g, ' ');
  }

  it('modelLine names the chat provider, model and the thinking flag', () => {
    setup(INIT);
    expect(fixture.componentInstance.modelLine)
      .toBe('Anthropic · claude-fable-5-1 · extended thinking on');
  });

  it('modelLine omits the thinking clause when extendedThinking is false', () => {
    setup({ ...INIT, extendedThinking: false });
    expect(fixture.componentInstance.modelLine).toBe('Anthropic · claude-fable-5-1');
  });

  it('the empty state shows the chat line, never the CodeGen (Opus) model, with the Configuration link', () => {
    setup(INIT);
    const t = text('.empty-state');
    expect(t).toContain('Anthropic · claude-fable-5-1');
    expect(t).toContain('extended thinking on');
    expect(t.toLowerCase()).not.toContain('opus');
    expect(t).toContain('Configuration');
    expect(t).toContain('AI Providers');
  });

  it('the topbar keeps the same line after the first message', () => {
    setup(INIT);
    const state = TestBed.inject(AssistantStateService);
    state.turns.push({ role: 'user', text: 'hello' } as any);
    fixture.detectChanges();
    expect(el.querySelector('.empty-state')).toBeNull();
    const t = text('.assistant-topbar');
    expect(t).toContain('Anthropic · claude-fable-5-1');
    expect(t.toLowerCase()).not.toContain('opus');
    expect(t).toContain('AI Providers');
  });

  it('uses the shared provider names (Ollama, Azure OpenAI, Grok (xAI), Amazon Bedrock)', () => {
    setup({ ...INIT, provider: 'ollama', model: 'qwen3', extendedThinking: false });
    expect(fixture.componentInstance.modelLine).toBe('Ollama · qwen3');
    const st = TestBed.inject(AssistantStateService);
    st.initInfo = { ...INIT, provider: 'azure', model: 'my-gpt-deployment', extendedThinking: false };
    expect(fixture.componentInstance.modelLine).toBe('Azure OpenAI · my-gpt-deployment');
    st.initInfo = { ...INIT, provider: 'grok', model: 'grok-4.7', extendedThinking: false };
    expect(fixture.componentInstance.modelLine).toBe('Grok (xAI) · grok-4.7');
    st.initInfo = { ...INIT, provider: 'bedrock', model: 'anthropic.claude-opus-5-5', extendedThinking: false };
    expect(fixture.componentInstance.modelLine).toBe('Amazon Bedrock · anthropic.claude-opus-5-5');
  });

  it('an empty model yields an empty line and the empty state still renders', () => {
    setup({ ...INIT, model: '' });
    expect(fixture.componentInstance.modelLine).toBe('');
    expect(text('.empty-state')).toContain('What data should we ingest?');
    expect(text('.empty-state')).not.toContain(' · ');
  });
});
