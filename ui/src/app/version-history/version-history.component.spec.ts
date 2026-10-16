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
import { of } from 'rxjs';

import { VersionHistoryComponent } from './version-history.component';
import { TapService } from '../tap.service';
import { PipelineService } from '../pipeline.service';

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
          ['getPipelineVersions', 'getPipelineVersion', 'diffPipelineVersions', 'restorePipelineVersion']) }
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
