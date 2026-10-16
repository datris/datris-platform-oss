import { Component, EventEmitter, Input, OnChanges, Output } from '@angular/core';
import {
  ColumnStats, EvidenceVerdict, IncidentEvidence, Quantiles, RunProfile, ThresholdCheck
} from '../../incidents.service';

const MINUS = '−';
const SPARK_W = 80;
const SPARK_H = 24;

interface SparkRow {
  column: string;
  baseline: string;   // polyline points
  after: string;      // polyline points
  shift?: number;
}

/** Evidence card for a recovery-agent incident (Activity → Incidents) and,
 *  compact, for the approval that belongs to one. Renders only what the
 *  server computed; never renders a column value (`topK` is not displayed). */
@Component({
  selector: 'app-incident-evidence',
  templateUrl: './incident-evidence.component.html',
  styleUrls: ['./incident-evidence.component.css'],
  standalone: false
})
export class IncidentEvidenceComponent implements OnChanges {
  @Input() evidence?: IncidentEvidence | null;
  @Input() resourceType: 'tap' | 'pipeline' = 'tap';
  @Input() resource = '';
  @Input() revertToVersion?: number | null;
  @Input() compact = false;
  /** "Compare script versions" clicked — the host opens the version-history modal. */
  @Output() compare = new EventEmitter<void>();

  verdict: EvidenceVerdict = 'pending';
  blastLine = '';
  readersLine = '';
  added: string[] = [];
  removed: string[] = [];
  typeChanges: { column: string; from: string; to: string }[] = [];
  hasDiff = false;
  rowsLine = '';
  rowsVsMean = '';
  dqLine = '';
  nullMovers: { column: string; delta: string; up: boolean }[] = [];
  sparks: SparkRow[] = [];
  checks: ThresholdCheck[] = [];
  afterLabel = '';

  ngOnChanges(): void {
    this.compute();
  }

  get canCompare(): boolean {
    return this.resourceType === 'tap' && this.revertToVersion !== undefined && this.revertToVersion !== null;
  }

  verdictLabel(v: string): string {
    return v === 'needs-human' ? 'needs human' : v;
  }

  checkLabel(name: string): string {
    switch (name) {
      case 'maxRecordDeltaPct': return 'Record delta %';
      case 'allowColumnRemoval': return 'Columns removed (allowColumnRemoval)';
      case 'allowTypeChange': return 'Type changes (allowTypeChange)';
      case 'maxNullRateIncrease': return 'Null-rate increase';
      case 'minDqPassRate': return 'DQ pass rate';
      default: return name;
    }
  }

  /** SVG polyline points for p1-p25-p50-p75-p99 on a shared [min, max] scale. */
  points(q: Quantiles, min?: number, max?: number): string {
    const vals = [q.p1, q.p25, q.p50, q.p75, q.p99].map(v => (typeof v === 'number' && isFinite(v) ? v : 0));
    const lo = min ?? Math.min(...vals);
    const hi = max ?? Math.max(...vals);
    const span = hi - lo;
    const pad = 2;
    return vals.map((v, i) => {
      const x = (i * (SPARK_W - 1)) / (vals.length - 1);
      const y = span === 0 ? SPARK_H / 2 : pad + (1 - (v - lo) / span) * (SPARK_H - 2 * pad);
      return round1(x) + ',' + round1(y);
    }).join(' ');
  }

  private compute(): void {
    const ev = this.evidence;
    this.reset();
    if (!ev) return;
    this.verdict = (ev.verdict || 'pending') as EvidenceVerdict;
    this.checks = ev.checks || [];

    // Blast radius
    const br = ev.blastRadius;
    if (br) {
      const parts: string[] = [];
      parts.push(plural((br.downstreamPipelines || []).length, 'pipeline'));
      const uc = (br.ucTables || []).length;
      if (uc > 0) parts.push(plural(uc, 'Unity Catalog table'));
      const ds = (br.downstreamDatasets || []).length;
      if (ds > 0) parts.push(plural(ds, 'dataset'));
      this.blastLine = 'feeds ' + parts.join(', ');
      const agents = br.agentsThatQueriedLast7d;
      if (agents === undefined || agents === null) {
        this.readersLine = 'downstream readers unknown — reads are not audited';
      } else if (agents.length === 0) {
        this.readersLine = 'no agent reads this week';
      } else {
        this.readersLine = plural(agents.length, 'agent identity', 'agent identities') + ' read them this week';
      }
    }

    const before = ev.baseline;
    const after = ev.candidate || ev.real;
    this.afterLabel = ev.candidate ? 'candidate' : ev.real ? 'after fix' : '';
    const d = ev.diff;

    if (d) {
      this.hasDiff = true;
      this.added = d.columnsAdded || [];
      this.removed = d.columnsRemoved || [];
      this.typeChanges = d.typeChanges || [];
      this.nullMovers = (d.nullRateDeltas || []).slice(0, 5).map(n => ({
        column: n.column,
        delta: signed(n.delta * 100, 1) + ' pp',
        up: n.delta > 0
      }));
      if (typeof d.recordDeltaVsMeanOfLastN === 'number' && d.lastN > 0) {
        this.rowsVsMean = 'vs mean of last ' + d.lastN + ': ' + signed(d.recordDeltaVsMeanOfLastN, 1) + ' %';
      }
    }

    if (before && after) {
      let line = fmtInt(before.recordCount) + ' → ' + fmtInt(after.recordCount);
      if (d && typeof d.recordDeltaPct === 'number') line += ' (' + signed(d.recordDeltaPct, 1) + ' %)';
      this.rowsLine = line;
      this.dqLine = 'DQ ' + pct(before.dqPassRate) + ' → ' + pct(after.dqPassRate);
      this.sparks = this.buildSparks(before, after, d?.quantileShift || []);
    } else if (before) {
      this.rowsLine = fmtInt(before.recordCount) + ' (baseline)';
      this.dqLine = 'DQ ' + pct(before.dqPassRate) + ' (baseline)';
    }
  }

  private buildSparks(before: RunProfile, after: RunProfile, shifts: { column: string; shift: number }[]): SparkRow[] {
    const byName = new Map<string, ColumnStats>();
    for (const c of before.columns || []) byName.set(c.name, c);
    const out: SparkRow[] = [];
    for (const a of after.columns || []) {
      const b = byName.get(a.name);
      if (!a.quantiles || !b || !b.quantiles) continue;
      const all = [a.quantiles, b.quantiles].flatMap(q => [q.p1, q.p25, q.p50, q.p75, q.p99]);
      const lo = Math.min(...all);
      const hi = Math.max(...all);
      out.push({
        column: a.name,
        baseline: this.points(b.quantiles, lo, hi),
        after: this.points(a.quantiles, lo, hi),
        shift: shifts.find(s => s.column === a.name)?.shift
      });
    }
    return out;
  }

  shiftText(s?: number): string {
    return typeof s === 'number' ? 'shift ' + signed(s, 2) : '';
  }

  private reset(): void {
    this.verdict = 'pending';
    this.blastLine = '';
    this.readersLine = '';
    this.added = [];
    this.removed = [];
    this.typeChanges = [];
    this.hasDiff = false;
    this.rowsLine = '';
    this.rowsVsMean = '';
    this.dqLine = '';
    this.nullMovers = [];
    this.sparks = [];
    this.checks = [];
    this.afterLabel = '';
  }
}

function round1(n: number): string {
  return (Math.round(n * 10) / 10).toString();
}

function plural(n: number, one: string, many?: string): string {
  return n + ' ' + (n === 1 ? one : (many || one + 's'));
}

function fmtInt(n: number): string {
  return Math.round(n || 0).toLocaleString('en-US');
}

/** Signed number with a typographic minus: "−0.8", "+1.2", "0.0". */
function signed(n: number, digits: number): string {
  const s = Math.abs(n).toFixed(digits);
  if (Number(s) === 0) return s;
  return (n < 0 ? MINUS : '+') + s;
}

/** Fraction → "100 %" / "98.5 %". */
function pct(f: number): string {
  const v = (f ?? 0) * 100;
  const r = Math.round(v * 10) / 10;
  return (Number.isInteger(r) ? r.toFixed(0) : r.toFixed(1)) + ' %';
}
