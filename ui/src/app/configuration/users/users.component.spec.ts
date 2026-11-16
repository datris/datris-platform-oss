/**
 * Story: Catalog-scoped user access (UI, Assistant, MCP, docs)
 * (plans/stories/catalog-scoped-user-access-ui-assistant.md), Step 2 —
 * Configuration -> Users grants editor.
 *
 * DOM contract pinned here (the story leaves names open; keep them or update
 * this spec):
 *  - Each user row has `input[type=checkbox].scoped-access-toggle`, checked
 *    when the user is `restricted`. It is disabled (or absent) on rows whose
 *    role is admin and on the built-in `admin` row.
 *  - A restricted row renders `.grants-editor` with one `.grant-row` per
 *    stored grant, a catalog picker `select.grant-catalog` (names from
 *    TapService.getTaps + PipelineService.getPipelines, the tap-create
 *    source), a level picker `select.grant-level` (view|edit|own),
 *    `button.add-grant`, `button.remove-grant` per row and
 *    `button.save-grants`.
 *  - Save calls `AuthService.patchUser(username, { restricted, catalogGrants })`
 *    with the full catalog -> level map.
 *  - None of these controls render unless the current user is an admin.
 */
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { of, Subject } from 'rxjs';

import { UsersComponent } from './users.component';
import { AuthService } from '../../auth.service';
import { ConfigAssistantStateService } from '../../config-chat/config-assistant-state.service';
import { TapService } from '../../tap.service';
import { PipelineService } from '../../pipeline.service';

describe('UsersComponent — catalog grants editor', () => {
  let fixture: ComponentFixture<UsersComponent>;
  let el: HTMLElement;
  let auth: any;
  let currentRole: 'admin' | 'editor' | 'viewer';
  let usersData: any[];

  beforeEach(async () => {
    currentRole = 'admin';
    usersData = [
      { username: 'admin', role: 'admin', createdAt: '', lastLoginAt: null, mustSetPassword: false },
      { username: 'alice', role: 'admin', createdAt: '', lastLoginAt: null, mustSetPassword: false },
      { username: 'bob', role: 'editor', createdAt: '', lastLoginAt: null, mustSetPassword: false,
        restricted: true, catalogGrants: { Sales: 'edit' } },
      { username: 'carol', role: 'viewer', createdAt: '', lastLoginAt: null, mustSetPassword: false,
        restricted: false }
    ];
    auth = {
      listUsers: jasmine.createSpy('listUsers').and.callFake(() => of(usersData)),
      patchUser: jasmine.createSpy('patchUser').and.returnValue(of(undefined)),
      createUser: jasmine.createSpy('createUser').and.returnValue(of(undefined)),
      deleteUser: jasmine.createSpy('deleteUser').and.returnValue(of(undefined)),
      current: () => ({ username: 'root', role: currentRole, mustSetPassword: false }),
      user: () => of({ username: 'root', role: currentRole, mustSetPassword: false }),
      isAdmin: () => currentRole === 'admin',
      canWrite: () => currentRole !== 'viewer',
      isViewer: () => currentRole === 'viewer',
      isRestricted: () => false,
      editableCatalogs: () => [],
      userAuthEnabled: true
    };

    await TestBed.configureTestingModule({
      declarations: [UsersComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        { provide: AuthService, useValue: auth },
        { provide: ConfigAssistantStateService, useValue: { changed$: new Subject<any>() } },
        { provide: TapService, useValue: { getTaps: () => of([
            { name: '__catalog__Sales', catalog: 'Sales' },
            { name: 't_arch', catalog: 'Archive' }
          ]) } },
        { provide: PipelineService, useValue: { getPipelines: () => of([
            { name: 'p_mkt', catalog: 'Marketing' },
            { name: 'p_none' }
          ]) } }
      ]
    }).compileComponents();
  });

  function create(): void {
    fixture = TestBed.createComponent(UsersComponent);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    tick();
    fixture.detectChanges();
  }

  function row(username: string): HTMLElement {
    const rows = Array.from(el.querySelectorAll('tr')) as HTMLElement[];
    const r = rows.find(x => {
      const first = x.querySelector('td');
      return !!first && (first.textContent || '').trim().split(/\s+/)[0] === username;
    });
    expect(r).withContext('row for ' + username).toBeDefined();
    return r!;
  }

  /** The grants editor may render inside the row or in a row right after it. */
  function editorFor(username: string): HTMLElement | null {
    const r = row(username);
    const inside = r.querySelector('.grants-editor') as HTMLElement | null;
    if (inside) return inside;
    const next = r.nextElementSibling as HTMLElement | null;
    return next ? (next.querySelector('.grants-editor') as HTMLElement | null) : null;
  }

  function choose(select: HTMLSelectElement, value: string): void {
    const opt = Array.from(select.options).find(o => o.value === value || (o.textContent || '').trim() === value);
    expect(opt).withContext(`option ${value} in ${select.className}`).toBeDefined();
    if (!opt) return;
    select.value = opt.value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    tick();
    fixture.detectChanges();
  }

  it('grants editor is rendered for admins only', fakeAsync(() => {
    create();
    expect(row('bob').querySelector('.scoped-access-toggle')).withContext('admin sees the toggle').not.toBeNull();
    expect(editorFor('bob')).withContext('admin sees the grants editor for a restricted user').not.toBeNull();
    fixture.destroy();

    currentRole = 'editor';
    create();
    expect(el.querySelector('.scoped-access-toggle')).withContext('non-admin sees no toggle').toBeNull();
    expect(el.querySelector('.grants-editor')).withContext('non-admin sees no grants editor').toBeNull();
  }));

  it('admin rows cannot be made restricted', fakeAsync(() => {
    create();
    for (const name of ['admin', 'alice']) {
      const toggle = row(name).querySelector('.scoped-access-toggle') as HTMLInputElement | null;
      if (toggle) {
        expect(toggle.disabled).withContext(`toggle disabled on admin row ${name}`).toBeTrue();
        toggle.click();
        fixture.detectChanges();
        tick();
      }
      expect(editorFor(name)).withContext(`no grants editor on admin row ${name}`).toBeNull();
    }
    const restrictCalls = auth.patchUser.calls.allArgs().filter((a: any[]) => a[1] && a[1].restricted === true);
    expect(restrictCalls).withContext('no restricted:true PATCH for an admin row').toEqual([]);
    // A non-admin row's toggle is live.
    const carol = row('carol').querySelector('.scoped-access-toggle') as HTMLInputElement | null;
    expect(carol).withContext('toggle on a non-admin row').not.toBeNull();
    expect(carol?.disabled).toBeFalse();
  }));

  it('save sends restricted + catalogGrants map', fakeAsync(() => {
    create();
    const editor = editorFor('bob');
    expect(editor).withContext('grants editor for bob').not.toBeNull();
    if (!editor) return;
    expect(editor.querySelectorAll('.grant-row').length).withContext('one row per stored grant').toBe(1);

    const catSel = editor.querySelector('select.grant-catalog') as HTMLSelectElement | null;
    const lvlSel = editor.querySelector('select.grant-level') as HTMLSelectElement | null;
    expect(catSel).withContext('catalog picker').not.toBeNull();
    expect(lvlSel).withContext('level picker').not.toBeNull();
    if (!catSel || !lvlSel) return;
    const offered = Array.from(catSel.options).map(o => (o.textContent || '').trim());
    expect(offered).withContext('picker offers catalogs from taps and pipelines').toEqual(jasmine.arrayContaining(['Archive', 'Marketing']));
    expect(offered).withContext('Uncataloged is not a grantable catalog').not.toContain('Uncataloged');

    choose(catSel, 'Archive');
    choose(lvlSel, 'view');
    (editor.querySelector('button.add-grant') as HTMLButtonElement).click();
    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    const save = (editorFor('bob') as HTMLElement).querySelector('button.save-grants') as HTMLButtonElement | null;
    expect(save).withContext('save button').not.toBeNull();
    save?.click();
    tick();

    expect(auth.patchUser).toHaveBeenCalled();
    const [username, body] = auth.patchUser.calls.mostRecent().args;
    expect(username).toBe('bob');
    expect(body.restricted).toBeTrue();
    expect(body.catalogGrants).toEqual({ Sales: 'edit', Archive: 'view' });
  }));
});
