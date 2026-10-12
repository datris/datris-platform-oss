/**
 * Story: Show the AI model in use (plans/stories/show-ai-model.md),
 * Acceptance bullets 4, 5 and 6 (tap wizard half).
 *
 * `GET /api/v1/tap/generate/status` and `POST /api/v1/tap/generate` gain
 * `provider` and `model` — the CodeGen slot that actually ran (the primary
 * when no CodeGen slot is set; the server resolves that, the UI just shows
 * what it is told). The wizard folds them into the progress line
 * ("Generating script with Anthropic claude-opus-5-5 — 42s so far...") and
 * shows "Generated with Anthropic claude-opus-5-5" on the finished script.
 * The tap line never mentions thinking. A status/result without the new keys
 * (older server) renders the pre-story text unchanged.
 *
 * Seams: `genProgressLabel()`, `generatedWithLine()` on TapCreateComponent,
 * and `providerDisplayName` in `ui/src/app/shared/provider-label.ts`.
 */
import { ComponentFixture, TestBed, fakeAsync, tick, discardPeriodicTasks, flush } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { of, Subject } from 'rxjs';

import { TapCreateComponent } from './tap-create.component';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { SecretsService } from '../secrets.service';
import { SearchService } from '../search.service';
import { AuthService } from '../auth.service';
import { CodeRepoService } from '../configuration/code-repo/code-repo.service';
import { providerDisplayName } from '../shared/provider-label';

describe('TapCreateComponent — generation model line', () => {
  let fixture: ComponentFixture<TapCreateComponent>;
  let comp: TapCreateComponent;
  let tapSvc: any;
  let status: any;
  let genResult$: Subject<any>;

  beforeEach(async () => {
    status = { active: false };
    genResult$ = new Subject<any>();
    tapSvc = {
      getTaps: () => of([]),
      getTap: () => of({}),
      generateScript: jasmine.createSpy('generateScript').and.callFake(() => genResult$),
      generateStatus: jasmine.createSpy('generateStatus').and.callFake(() => of(status))
    };
    await TestBed.configureTestingModule({
      declarations: [TapCreateComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideRouter([]),
        { provide: TapService, useValue: tapSvc },
        { provide: PipelineService, useValue: { getPipelines: () => of([]) } },
        { provide: SecretsService, useValue: { listSecrets: () => of([]) } },
        { provide: SearchService, useValue: {} },
        { provide: AuthService, useValue: { canWrite: () => true, userAuthEnabled: false, current: () => null } },
        { provide: CodeRepoService, useValue: { get: () => of({ enabled: false }) } }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(TapCreateComponent);
    comp = fixture.componentInstance;
    comp.tapName = 'model-line-probe';
    comp.description = 'return three rows with a and b';
  });

  function fa(body: () => void) {
    return fakeAsync(() => { body(); fixture.destroy(); discardPeriodicTasks(); flush(); });
  }

  /** Start a generation and land one status poll (the poll runs every 3s). */
  function pollOnce(s: any): void {
    status = s;
    comp.generateScript();
    tick(3000);
  }

  // ------------------------------------------------ provider display names ---

  it('providerDisplayName maps every provider id and passes unknown ids through', () => {
    expect(providerDisplayName('anthropic')).toBe('Anthropic');
    expect(providerDisplayName('openai')).toBe('OpenAI');
    expect(providerDisplayName('azure')).toBe('Azure OpenAI');
    expect(providerDisplayName('bedrock')).toBe('Amazon Bedrock');
    expect(providerDisplayName('grok')).toBe('Grok (xAI)');
    expect(providerDisplayName('ollama')).toBe('Ollama');
    expect(providerDisplayName('mystery')).toBe('mystery');
  });

  // ------------------------------------------------------- progress line ---

  it('the progress line names the CodeGen provider and model from the status poll', fa(() => {
    pollOnce({ active: true, phase: 'calling-model', attempt: 1, elapsedSeconds: 42, provider: 'ollama', model: 'qwen3' });
    const label = comp.genProgressLabel();
    expect(label).toContain('Generating script with Ollama qwen3');
    expect(label).toContain('42s so far');
    expect(label.toLowerCase()).not.toContain('thinking');
  }));

  it('Azure, Grok and Bedrock read as "<display name> <model id>"', fa(() => {
    const cases: Array<[string, string, string]> = [
      ['azure', 'my-gpt-deployment', 'Azure OpenAI my-gpt-deployment'],
      ['grok', 'grok-4.7', 'Grok (xAI) grok-4.7'],
      ['bedrock', 'anthropic.claude-opus-5-5', 'Amazon Bedrock anthropic.claude-opus-5-5'],
      ['anthropic', 'claude-opus-5-5', 'Anthropic claude-opus-5-5']
    ];
    for (const [provider, model, expected] of cases) {
      pollOnce({ active: true, phase: 'calling-model', attempt: 1, elapsedSeconds: 10, provider, model });
      expect(comp.genProgressLabel()).withContext(provider).toContain(expected);
    }
  }));

  it('the retrying-format line also names the model', fa(() => {
    pollOnce({ active: true, phase: 'retrying-format', attempt: 2, elapsedSeconds: 75, provider: 'anthropic', model: 'claude-opus-5-5' });
    const label = comp.genProgressLabel();
    expect(label).toContain('Anthropic claude-opus-5-5');
    expect(label.toLowerCase()).not.toContain('thinking');
  }));

  it('a status without provider/model (older server) renders the pre-story text unchanged', fa(() => {
    pollOnce({ active: true, phase: 'calling-model', attempt: 1, elapsedSeconds: 42 });
    expect(comp.genProgressLabel()).toBe('Generating script with AI — 42s so far (typically 1–3 minutes)...');
    pollOnce({ active: true, phase: 'retrying-format', attempt: 2, elapsedSeconds: 75 });
    expect(comp.genProgressLabel()).toBe('Model output needed reformatting — running a second attempt (1m 15s total)...');
  }));

  // ---------------------------------------------------- generated-with line ---

  it('the finished script shows "Generated with <provider> <model>"', fa(() => {
    comp.generateScript();
    genResult$.next({
      script: 'def fetch():\n    return []\n', scriptPath: 'tap-scripts/x.py', packages: [],
      provider: 'anthropic', model: 'claude-opus-5-5'
    });
    expect(comp.generatedWithLine()).toBe('Generated with Anthropic claude-opus-5-5');
    expect(comp.generatedWithLine().toLowerCase()).not.toContain('thinking');
  }));

  it('a result without provider/model (older server) shows no generated-with line', fa(() => {
    comp.generateScript();
    genResult$.next({ script: 'def fetch():\n    return []\n', scriptPath: 'tap-scripts/x.py', packages: [] });
    expect(comp.generatedWithLine()).toBe('');
  }));

  it('switching to bring-your-own code clears the generated-with line', fa(() => {
    comp.generateScript();
    genResult$.next({
      script: 'def fetch():\n    return []\n', scriptPath: 'tap-scripts/x.py', packages: [],
      provider: 'openai', model: 'gpt-5.5'
    });
    expect(comp.generatedWithLine()).toBe('Generated with OpenAI gpt-5.5');
    comp.selectMode('custom');
    expect(comp.generatedWithLine()).toBe('');
  }));
});
