import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { PipelineService } from '../pipeline.service';
import { AuthService } from '../auth.service';

/** Destination-side typing dialog: shows the types inferred on demand from
 *  landed data (with per-column sample values as evidence), lets the user
 *  adjust any column, and applies — which migrates the landed table first and
 *  then writes the typed config as a new pipeline version. Stateless on the
 *  server: closing without applying leaves nothing behind. */
@Component({
    selector: 'app-dest-types-dialog',
    templateUrl: './dest-types-dialog.component.html',
    styleUrls: ['./dest-types-dialog.component.css'],
    standalone: false
})
export class DestTypesDialogComponent implements OnInit {
  @Input() pipeline = '';
  @Output() closed = new EventEmitter<void>();
  /** Emitted after a successful apply so the opener can refresh its rows
   *  (the badge clears itself once the config carries types). */
  @Output() applied = new EventEmitter<void>();

  loading = true;
  proposal: any = null;
  /** Editable copy of the proposed fields: {name, type, samples, stringReason}. */
  fields: any[] = [];
  types = ['string', 'boolean', 'int', 'bigint', 'float', 'double', 'date', 'timestamp'];

  applying = false;
  appliedResult: any = null;
  error = '';

  constructor(private pipelineService: PipelineService, public auth: AuthService) {}

  ngOnInit(): void {
    this.pipelineService.getDestTypes(this.pipeline).subscribe({
      next: (proposal) => {
        this.proposal = proposal;
        this.fields = (proposal?.fields || []).map((f: any) => ({ ...f }));
        this.loading = false;
      },
      error: (err) => {
        this.error = err.error?.error || err.error || err.message || 'Failed to infer types';
        this.loading = false;
      }
    });
  }

  hasTypedField(): boolean {
    return this.fields.some(f => f.type && f.type.toLowerCase() !== 'string');
  }

  samplesFor(f: any): string {
    return (f.samples || []).join(', ');
  }

  apply(): void {
    this.applying = true;
    this.error = '';
    const fields = this.fields.map(f => ({ name: f.name, type: f.type }));
    this.pipelineService.applyDestTypes(this.pipeline, fields).subscribe({
      next: (result) => {
        this.applying = false;
        this.appliedResult = result;
        this.applied.emit();
      },
      error: (err) => {
        this.applying = false;
        this.error = err.error?.error || err.error || err.message || 'Apply failed';
      }
    });
  }

  close(): void {
    this.closed.emit();
  }
}
