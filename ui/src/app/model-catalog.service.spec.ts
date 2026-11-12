/**
 * Story: Claude Fable 5.2 as a selectable model (Anthropic and Bedrock, chat
 * and CodeGen) (plans/stories/claude-fable-5-2-model-option.md), Acceptance
 * bullet 1.
 *
 * With the catalog request failing and no cached copy in localStorage,
 * fetch() returns the baked-in FALLBACK. In the four Claude lists the new
 * Fable 5.2 entry sits directly above Fable 5.1, Opus 5.5 stays first and is
 * the only recommended entry, and the new entry carries no `recommended`.
 */
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { ModelCatalogService, ModelCatalog, ModelOption } from './model-catalog.service';

describe('ModelCatalogService — fallback carries Claude Fable 5.2', () => {
  const CACHE_KEY = 'datris.modelCatalog';
  let catalog: ModelCatalog;

  beforeEach(async () => {
    localStorage.removeItem(CACHE_KEY);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    const service = TestBed.inject(ModelCatalogService);
    const http = TestBed.inject(HttpTestingController);

    const pending = service.fetch();
    http.expectOne('/api/v1/ai/model-catalog')
      .flush('unavailable', { status: 503, statusText: 'Service Unavailable' });
    catalog = await pending;
    http.verify();
  });

  afterEach(() => localStorage.removeItem(CACHE_KEY));

  const lists: Array<{ section: 'aiPrimary' | 'codegen'; provider: string; id: string; next: string; opus: string }> = [
    { section: 'aiPrimary', provider: 'anthropic', id: 'claude-fable-5-2', next: 'claude-fable-5-1', opus: 'claude-opus-5-5' },
    { section: 'codegen', provider: 'anthropic', id: 'claude-fable-5-2', next: 'claude-fable-5-1', opus: 'claude-opus-5-5' },
    { section: 'aiPrimary', provider: 'bedrock', id: 'anthropic.claude-fable-5-2', next: 'anthropic.claude-fable-5-1', opus: 'anthropic.claude-opus-5-5' },
    { section: 'codegen', provider: 'bedrock', id: 'anthropic.claude-fable-5-2', next: 'anthropic.claude-fable-5-1', opus: 'anthropic.claude-opus-5-5' },
  ];

  for (const l of lists) {
    const name = `${l.section}.${l.provider}`;

    it(`${name} has ${l.id} labelled "Claude Fable 5.2" immediately before ${l.next}`, () => {
      const models: ModelOption[] = catalog[l.section][l.provider];
      const values = models.map(m => m.value);
      const i = values.indexOf(l.id);
      expect(i).withContext(`${l.id} missing from ${name}: ${values.join(', ')}`).toBeGreaterThanOrEqual(0);
      expect(models[i]?.label).toBe('Claude Fable 5.2');
      expect(values[i + 1]).withContext(`entry after ${l.id} in ${name}`).toBe(l.next);
    });

    it(`${name} keeps ${l.opus} first and as the only recommended entry; Fable 5.2 not recommended`, () => {
      const models: ModelOption[] = catalog[l.section][l.provider];
      expect(models[0].value).toBe(l.opus);
      expect(models.filter(m => m.recommended === true).map(m => m.value)).toEqual([l.opus]);
      const added = models.find(m => m.value === l.id);
      expect(added).withContext(`${l.id} missing from ${name}`).toBeDefined();
      expect(added && Object.prototype.hasOwnProperty.call(added, 'recommended'))
        .withContext(`${l.id} must not carry a recommended key`).toBeFalse();
    });
  }
});
