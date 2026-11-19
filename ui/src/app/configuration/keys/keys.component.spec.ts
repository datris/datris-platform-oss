/**
 * Story: API keys, a scope the server does not apply can no longer be put on a
 * key, and stored ones stop granting (plans/stories/api-key-inert-scopes.md),
 * Acceptance, keys.component.spec.ts.
 *
 * DOM contract pinned here (the story leaves names open; keep them or update
 * this spec):
 *  - `KeyRow.inertCapabilities?: string[]` from GET /keys.
 *  - A capability chip whose string is in the row's `inertCapabilities` carries
 *    the class `cap-inert` (beside `cap-chip`) and a non-empty `title`
 *    explaining why it grants nothing. Other chips do not carry `cap-inert`.
 *  - `.inert-banner` renders (beside `.legacy-banner`) when any non-revoked row
 *    has a non-empty `inertCapabilities`, and not when every list is empty.
 *  - A 400 from POST /keys shows the server's `error` text in `.wizard-error`.
 */
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { of, Subject, throwError } from 'rxjs';

import { KeysComponent } from './keys.component';
import { KeysService } from './keys.service';
import { ConfigAssistantStateService } from '../../config-chat/config-assistant-state.service';

describe('KeysComponent — scopes the platform does not apply', () => {
  let fixture: ComponentFixture<KeysComponent>;
  let component: KeysComponent;
  let el: HTMLElement;
  let rows: any[];
  let keysService: any;

  const issueMessage =
    "Invalid capability 'job:read:owner=self': scope 'owner' is not applied on job:read. " +
    "Supported scopes for job:read: none. Grant 'job:read' unscoped or leave it out.";

  beforeEach(async () => {
    rows = [];
    keysService = {
      list: jasmine.createSpy('list').and.callFake(() => of({ keys: rows })),
      issue: jasmine.createSpy('issue').and.callFake(() =>
        throwError(() => new HttpErrorResponse({ status: 400, error: { error: issueMessage } }))),
      revoke: jasmine.createSpy('revoke').and.returnValue(of({ status: 'revoked' })),
      rotate: jasmine.createSpy('rotate').and.returnValue(of({ label: 'x', value: 'v', rotatedAt: '' })),
      capabilities: () => of({ resources: [] }),
      templates: () => of({ templates: [] })
    };

    await TestBed.configureTestingModule({
      declarations: [KeysComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        { provide: KeysService, useValue: keysService },
        { provide: ConfigAssistantStateService, useValue: { changed$: new Subject<any>() } }
      ]
    }).compileComponents();
  });

  function create(): void {
    fixture = TestBed.createComponent(KeysComponent);
    component = fixture.componentInstance;
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    tick();
    fixture.detectChanges();
  }

  function rowEl(label: string): HTMLElement {
    const trs = Array.from(el.querySelectorAll('tbody tr')) as HTMLElement[];
    const r = trs.find(x => ((x.querySelector('td')?.textContent) || '').trim() === label);
    expect(r).withContext('row for ' + label).toBeDefined();
    return r!;
  }

  function chip(label: string, cap: string): HTMLElement {
    const chips = Array.from(rowEl(label).querySelectorAll('.cap-chip')) as HTMLElement[];
    const c = chips.find(x => (x.textContent || '').trim() === cap);
    expect(c).withContext(`chip ${cap} on ${label}`).toBeDefined();
    return c!;
  }

  it('a row with inertCapabilities marks those chips and shows the banner', fakeAsync(() => {
    rows = [
      { label: 'ik_old', capabilities: ['job:read:owner=self', 'pipeline:read'],
        inertCapabilities: ['job:read:owner=self'], isLegacyFullAccess: false, revoked: false },
      { label: 'rag', capabilities: ['search:vector:collection=docs'],
        inertCapabilities: [], isLegacyFullAccess: false, revoked: false }
    ];
    create();

    const inert = chip('ik_old', 'job:read:owner=self');
    expect(inert.classList.contains('cap-inert')).withContext('inert chip is marked').toBeTrue();
    expect((inert.getAttribute('title') || '').trim().length).withContext('marked chip has a title').toBeGreaterThan(0);

    expect(chip('ik_old', 'pipeline:read').classList.contains('cap-inert')).withContext('applied chip is not marked').toBeFalse();
    expect(chip('rag', 'search:vector:collection=docs').classList.contains('cap-inert')).toBeFalse();

    expect(el.querySelector('.inert-banner')).withContext('banner shown').not.toBeNull();
  }));

  it("no banner when every row's list is empty", fakeAsync(() => {
    rows = [
      { label: 'rag', capabilities: ['pipeline:read', 'search:vector:collection=docs'],
        inertCapabilities: [], isLegacyFullAccess: false, revoked: false },
      { label: 'legacy', capabilities: [], inertCapabilities: [], isLegacyFullAccess: true, revoked: false }
    ];
    create();
    expect(el.querySelector('.inert-banner')).withContext('no banner').toBeNull();
    expect(el.querySelector('.cap-inert')).withContext('no marked chip').toBeNull();
  }));

  it('a 400 from issue shows the server\'s message in the wizard', fakeAsync(() => {
    create();
    component.openWizard();
    component.wizardLabel = 'ik_bad';
    component.wizardCapabilities = ['job:read:owner=self'];
    component.goToReview();
    fixture.detectChanges();
    expect(component.wizardStep).toBe(2);

    component.submitIssue();
    tick();
    fixture.detectChanges();

    expect(keysService.issue).toHaveBeenCalledWith('ik_bad', ['job:read:owner=self']);
    expect(component.showWizard).withContext('the wizard stays open').toBeTrue();
    const err = el.querySelector('.wizard-error') as HTMLElement | null;
    expect(err).withContext('wizard error shown').not.toBeNull();
    expect((err?.textContent || '').trim()).toBe(issueMessage);
  }));
});
