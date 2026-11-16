/**
 * Story: Catalog-scoped user access (UI, Assistant, MCP, docs)
 * (plans/stories/catalog-scoped-user-access-ui-assistant.md), Step 4 —
 * the pipeline wizard's catalog field for a restricted user.
 *
 * The wizard reads the editable list from AuthService (`isRestricted()`,
 * `editableCatalogs()`, backed by the /me `restricted` + `catalogGrants`
 * fields), never from list contents. For a restricted user the step-1
 * catalog <select> offers only edit/own catalogs (no Uncataloged, no
 * "+ Create New Catalog", no inline "New catalog name" input); one editable
 * catalog is pre-selected, several leave the field empty and required.
 */
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of } from 'rxjs';

import { PipelineCreateComponent } from './pipeline-create.component';
import { PipelineService } from '../pipeline.service';
import { SearchService } from '../search.service';
import { HealthService } from '../health.service';
import { TapService } from '../tap.service';
import { AuthService } from '../auth.service';

type Level = 'view' | 'edit' | 'own';

describe('PipelineCreateComponent — restricted user catalog field', () => {
  let fixture: ComponentFixture<PipelineCreateComponent>;
  let component: any;
  let el: HTMLElement;

  function authFor(grants: Record<string, Level>): any {
    const me = { username: 'bob', role: 'editor', mustSetPassword: false, restricted: true, catalogGrants: grants };
    return {
      current: () => me,
      user: () => of(me),
      isAdmin: () => false,
      canWrite: () => true,
      isViewer: () => false,
      isRestricted: () => true,
      editableCatalogs: () => Object.keys(grants).filter(k => grants[k] === 'edit' || grants[k] === 'own').sort(),
      userAuthEnabled: true
    };
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [PipelineCreateComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: PipelineService, useValue: {
            getAvailableDestinations: () => of(['postgres', 'mongodb', 'objectstore']),
            // The lists a restricted session sees: its granted catalogs only.
            getPipelines: () => of([{ name: 'p_sales', catalog: 'Sales' }, { name: 'p_arch', catalog: 'Archive' }]),
            getPipeline: () => of({}),
            generateSchema: () => of({}),
            createPipeline: () => of('ok')
        } },
        { provide: SearchService, useValue: { getPipelines: () => of([]) } },
        { provide: HealthService, useValue: { isAvailable: () => true, refresh: () => Promise.resolve() } },
        { provide: TapService, useValue: { getTaps: () => of([
            { name: '__catalog__Marketing', catalog: 'Marketing' },
            { name: 't_arch', catalog: 'Archive' }
          ]) } },
        { provide: AuthService, useValue: authFor({}) },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}), paramMap: convertToParamMap({}) } } }
      ]
    }).compileComponents();
  });

  /** Per-test grants: swap the AuthService before the component is created. */
  function setup(grants: Record<string, Level>): void {
    TestBed.overrideProvider(AuthService, { useValue: authFor(grants) });
  }

  function create(): void {
    fixture = TestBed.createComponent(PipelineCreateComponent);
    component = fixture.componentInstance as any;
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    tick();
    fixture.detectChanges();
  }

  /** The step-1 catalog <select>: the one whose options include a granted catalog. */
  function catalogSelect(): HTMLSelectElement {
    const selects = Array.from(el.querySelectorAll('select')) as HTMLSelectElement[];
    const s = selects.find(x => Array.from(x.options).some(o => ['Sales', 'Marketing'].includes((o.textContent || '').trim())));
    expect(s).withContext('catalog select on step 1').toBeDefined();
    return s!;
  }

  function optionLabels(s: HTMLSelectElement): string[] {
    return Array.from(s.options).map(o => (o.textContent || '').trim());
  }

  /** Options that name a catalog (a blank "Choose a catalog" placeholder is allowed). */
  function catalogOptions(s: HTMLSelectElement): string[] {
    return Array.from(s.options).filter(o => o.value !== '' && o.value !== '__new__')
      .map(o => (o.textContent || '').trim());
  }

  function expectNoNewCatalogPath(s: HTMLSelectElement): void {
    const labels = optionLabels(s);
    expect(labels).withContext('no Uncataloged option').not.toContain('Uncataloged');
    expect(Array.from(s.options).some(o => o.value === '__new__')).withContext('no "+ Create New Catalog" option').toBeFalse();
    expect(labels.some(l => /create new catalog/i.test(l))).withContext('no create-new label').toBeFalse();
  }

  it('one editable catalog is pre-selected and the new-catalog input is hidden', fakeAsync(() => {
    setup({ Sales: 'edit', Archive: 'view' });
    create();
    expect(component.catalog).withContext('the only editable catalog is pre-selected').toBe('Sales');
    const s = catalogSelect();
    expect(catalogOptions(s)).toEqual(['Sales']);
    expectNoNewCatalogPath(s);
    // Even if something flips the inline-create flag, no input renders.
    component.onCatalogChange('__new__');
    fixture.detectChanges();
    expect(el.querySelector('.new-catalog-row')).withContext('inline new-catalog row hidden').toBeNull();
    expect(el.querySelector('input[placeholder="New catalog name"]')).withContext('"New catalog name" input hidden').toBeNull();
  }));

  it('two editable catalogs leave the field required', fakeAsync(() => {
    setup({ Sales: 'edit', Marketing: 'own', Archive: 'view' });
    create();
    expect(component.catalog || '').withContext('nothing pre-selected with several editable catalogs').toBe('');
    const s = catalogSelect();
    expect(catalogOptions(s).sort()).toEqual(['Marketing', 'Sales']);
    expect(s.required || s.getAttribute('aria-required') === 'true')
      .withContext('catalog select is required').toBeTrue();
    expectNoNewCatalogPath(s);
  }));

  it('a view-only catalog is not offered', fakeAsync(() => {
    setup({ Sales: 'edit', Marketing: 'own', Archive: 'view' });
    create();
    const s = catalogSelect();
    expect(optionLabels(s)).withContext('view-only Archive is visible in lists but not a filing target').not.toContain('Archive');
    expect(component.availableCatalogs || []).not.toContain('Archive');
  }));
});
