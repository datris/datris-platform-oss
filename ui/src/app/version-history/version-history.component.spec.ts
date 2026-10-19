/**
 * Version-history modal preselect inputs
 * (plans/stories/self-healing-2-evidence-ui.md): when both
 * `preselectVersion` and `preselectAgainst` are set, the modal opens on that
 * diff — the evidence card's "Compare script versions" lands straight on the
 * pre-incident vs current script. Existing callers pass neither and behave
 * as before.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';

import { VersionHistoryComponent } from './version-history.component';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';
import { LineageService } from '../lineage.service';

/** LineageService's script-version methods (story self-healing-5a). Untyped
 *  so the spec compiles before the methods exist. */
const SCRIPT_METHODS = ['scriptVersions', 'scriptVersion', 'diffScriptVersions', 'restoreScriptVersion'];

describe('VersionHistoryComponent: preselected diff', () => {
  let fixture: ComponentFixture<VersionHistoryComponent>;
  let tapService: jasmine.SpyObj<TapService>;

  beforeEach(async () => {
    tapService = jasmine.createSpyObj<TapService>('TapService',
      ['getTapVersions', 'getTapVersion', 'diffTapVersions', 'restoreTapVersion']);
    tapService.getTapVersions.and.returnValue(of([
      { version: 4, savedAt: '2026-10-15T10:00:00Z' },
      { version: 3, savedAt: '2026-10-14T10:00:00Z' }
    ]));
    tapService.getTapVersion.and.callFake((_n: string, v: number) => of({ version: v, config: {}, script: '' }));
    tapService.diffTapVersions.and.returnValue(of({ changes: [], scriptDiff: [] }));

    await TestBed.configureTestingModule({
      declarations: [VersionHistoryComponent],
      schemas: [NO_ERRORS_SCHEMA],
      providers: [
        { provide: TapService, useValue: tapService },
        { provide: PipelineService, useValue: jasmine.createSpyObj('PipelineService',
          ['getPipelineVersions', 'getPipelineVersion', 'diffPipelineVersions', 'restorePipelineVersion']) },
        { provide: LineageService, useValue: jasmine.createSpyObj('LineageService', SCRIPT_METHODS) }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(VersionHistoryComponent);
  });

  // Acceptance bullet 5
  it('calls diffTapVersions(name, 3, 4) once on init when preselectVersion=3 and preselectAgainst=4', () => {
    fixture.componentRef.setInput('entityType', 'tap');
    fixture.componentRef.setInput('name', 'orders_tap');
    fixture.componentRef.setInput('preselectVersion', 3);
    fixture.componentRef.setInput('preselectAgainst', 4);
    fixture.detectChanges();
    fixture.detectChanges();

    expect(tapService.diffTapVersions).toHaveBeenCalledTimes(1);
    expect(tapService.diffTapVersions).toHaveBeenCalledWith('orders_tap', 3, 4);
  });

  // Acceptance bullet 5 (existing callers unchanged)
  it('does not diff anything on init when no preselect inputs are passed', () => {
    fixture.componentRef.setInput('entityType', 'tap');
    fixture.componentRef.setInput('name', 'orders_tap');
    fixture.detectChanges();

    expect(tapService.getTapVersions).toHaveBeenCalledTimes(1);
    expect(tapService.diffTapVersions).not.toHaveBeenCalled();
  });
});

/**
 * Version history for stored CodeGen scripts
 * (plans/stories/self-healing-5a-script-versions.md), Acceptance bullet 6:
 * `entityType="script"` with `[kind]` lists, reads, diffs and restores
 * through LineageService (scriptVersions / scriptVersion /
 * diffScriptVersions / restoreScriptVersion, each (pipeline, kind, ...)),
 * shows the script diff and snapshot and no config block; tap and pipeline
 * modes call the same services as before.
 */
describe('VersionHistoryComponent: script mode', () => {
  let fixture: ComponentFixture<VersionHistoryComponent>;
  let component: VersionHistoryComponent;
  let tapService: jasmine.SpyObj<TapService>;
  let pipelineService: jasmine.SpyObj<PipelineService>;
  let lineage: any;

  const versions = [
    { version: 2, createdAt: '2026-10-16T10:00:00Z', createdBy: 'todd', changeNote: null, origin: 'regenerate', isLive: true },
    { version: 1, createdAt: '2026-10-15T10:00:00Z', createdBy: 'system', changeNote: '(seeded from pre-versioning state)', origin: 'save', isLive: false }
  ];

  beforeEach(async () => {
    tapService = jasmine.createSpyObj<TapService>('TapService',
      ['getTapVersions', 'getTapVersion', 'diffTapVersions', 'restoreTapVersion']);
    pipelineService = jasmine.createSpyObj<PipelineService>('PipelineService',
      ['getPipelineVersions', 'getPipelineVersion', 'diffPipelineVersions', 'restorePipelineVersion']);
    tapService.getTapVersions.and.returnValue(of([]));
    pipelineService.getPipelineVersions.and.returnValue(of([]));
    lineage = jasmine.createSpyObj('LineageService', SCRIPT_METHODS);
    lineage.scriptVersions.and.returnValue(of(versions));
    lineage.scriptVersion.and.callFake((_p: string, _k: string, v: number) =>
      of({ version: v, script: "print('v" + v + "')", fingerprint: 'abc', generatedAt: '2026-10-16T10:00:00Z' }));
    lineage.diffScriptVersions.and.returnValue(of({
      from: 1, to: 2,
      script: {
        before: "import json\nprint('v1')",
        after: "import json\nprint('v2')",
        lines: [
          { type: 'ctx', text: 'import json' },
          { type: 'del', text: "print('v1')" },
          { type: 'add', text: "print('v2')" }
        ]
      }
    }));
    lineage.restoreScriptVersion.and.returnValue(of({ kind: 'transformation', version: 3, origin: 'restore-version' }));

    await TestBed.configureTestingModule({
      declarations: [VersionHistoryComponent],
      schemas: [NO_ERRORS_SCHEMA],
      providers: [
        { provide: TapService, useValue: tapService },
        { provide: PipelineService, useValue: pipelineService },
        { provide: LineageService, useValue: lineage }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(VersionHistoryComponent);
    component = fixture.componentInstance;
  });

  function openScript(): void {
    fixture.componentRef.setInput('entityType', 'script');
    fixture.componentRef.setInput('kind', 'transformation');
    fixture.componentRef.setInput('name', 'orders');
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent || '';
  }

  function subtitles(): string[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.vh-subtitle'))
      .map(e => (e.textContent || '').trim());
  }

  it('lists through LineageService.scriptVersions(pipeline, kind) and not the tap or pipeline services', () => {
    openScript();
    expect(lineage.scriptVersions).toHaveBeenCalledWith('orders', 'transformation');
    expect(tapService.getTapVersions).not.toHaveBeenCalled();
    expect(pipelineService.getPipelineVersions).not.toHaveBeenCalled();
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('tr.vh-row');
    expect(rows.length).toBe(2);
    expect(text()).toContain('(seeded from pre-versioning state)');
  });

  it('titles the modal with the pipeline and kind and says the definition version does not change', () => {
    openScript();
    const title = (fixture.nativeElement as HTMLElement).querySelector('.modal-title')?.textContent || '';
    expect(title.trim()).toBe('Script history: orders / transformation');
    expect(text()).toContain("the pipeline's definition version does not change");
  });

  it('shows the selected version as a script snapshot with no config block', () => {
    openScript();
    component.selectVersion(2);
    fixture.detectChanges();
    expect(lineage.scriptVersion).toHaveBeenCalledWith('orders', 'transformation', 2);
    expect(text()).toContain("print('v2')");
    expect(subtitles().some(s => s.startsWith('Config'))).toBeFalse();
    expect(subtitles()).toContain('Script');
  });

  it('diff renders the script lines and no config changes', () => {
    openScript();
    component.selectVersion(2);
    component.onDiffAgainstChange('1');
    fixture.detectChanges();
    expect(lineage.diffScriptVersions).toHaveBeenCalledWith('orders', 'transformation', 2, 1);
    const el = fixture.nativeElement as HTMLElement;
    const lines = el.querySelectorAll('.vh-script-line');
    expect(lines.length).toBe(3);
    expect(el.querySelectorAll('.vh-script-line.vh-line-del').length).toBe(1);
    expect(el.querySelectorAll('.vh-script-line.vh-line-add').length).toBe(1);
    expect(el.querySelector('.vh-line-add')?.textContent).toContain("print('v2')");
    expect(subtitles().some(s => s.startsWith('Config'))).toBeFalse();
    expect(el.querySelector('.vh-diff-list')).toBeNull();
  });

  it('restores through LineageService.restoreScriptVersion and shows the server error text on a refusal', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    openScript();
    component.restore(1);
    expect(lineage.restoreScriptVersion).toHaveBeenCalledWith('orders', 'transformation', 1);
    expect(pipelineService.restorePipelineVersion).not.toHaveBeenCalled();

    const reason = 'script version 1 was generated for a different instruction or source schema than pipeline orders has now; nothing was changed';
    lineage.restoreScriptVersion.and.returnValue(throwError(() => new HttpErrorResponse({ status: 422, error: { error: reason } })));
    component.restore(1);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.vh-error')?.textContent).toContain(reason);
  });

  it('pipeline mode still lists through PipelineService and never LineageService', () => {
    fixture.componentRef.setInput('entityType', 'pipeline');
    fixture.componentRef.setInput('name', 'orders');
    fixture.detectChanges();
    expect(pipelineService.getPipelineVersions).toHaveBeenCalledWith('orders');
    expect(lineage.scriptVersions).not.toHaveBeenCalled();
    expect(tapService.getTapVersions).not.toHaveBeenCalled();
  });

  it('tap mode still lists through TapService and never LineageService', () => {
    fixture.componentRef.setInput('entityType', 'tap');
    fixture.componentRef.setInput('name', 'orders_tap');
    fixture.detectChanges();
    expect(tapService.getTapVersions).toHaveBeenCalledWith('orders_tap');
    expect(lineage.scriptVersions).not.toHaveBeenCalled();
    expect(pipelineService.getPipelineVersions).not.toHaveBeenCalled();
  });
});
