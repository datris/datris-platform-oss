import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { PipelineService } from '../pipeline.service';
import { sanitizeIdentifier } from '../shared/sanitize';

export interface DqTxValue {
  dataQuality: any | null;
  transformation: any | null;
}

@Component({
    selector: 'app-pipeline-dq-tx-editor',
    templateUrl: './pipeline-dq-tx-editor.component.html',
    styleUrls: ['./pipeline-dq-tx-editor.component.css'],
    standalone: false
})
export class PipelineDqTxEditorComponent implements OnChanges {
  @Input() sourceType = 'json';
  @Input() dataQuality: any = null;
  @Input() transformation: any = null;
  @Input() schemaNameDefault = '';
  @Output() change = new EventEmitter<DqTxValue>();

  // Step 5 — Data Quality
  dqValidateHeader = false;
  dqValidationSchema = '';
  dqSchemaMode: 'upload' | 'generate' = 'upload';
  dqSchemaName = '';
  dqSampleData = '';
  dqGenerating = false;
  dqGenerateError = '';
  dqUseAiRule = false;
  dqAiInstruction = '';
  dqAiOnFailureIsError = false;

  // Step 6 — Transformation
  txTrimWhitespace = false;
  txDeduplicate = false;
  txAiInstruction = '';

  private readonly TX_TRIM_TEXT = 'Trim leading/trailing whitespace from all columns.';
  private readonly TX_DEDUP_TEXT = 'Remove duplicate rows.';

  constructor(private pipelineService: PipelineService) { }

  ngOnChanges(_: SimpleChanges): void {
    this.loadFromInputs();
  }

  private loadFromInputs(): void {
    // Reset
    this.dqValidateHeader = false;
    this.dqValidationSchema = '';
    this.dqSchemaMode = 'upload';
    this.dqSchemaName = this.schemaNameDefault || '';
    this.dqSampleData = '';
    this.dqGenerateError = '';
    this.dqUseAiRule = false;
    this.dqAiInstruction = '';
    this.dqAiOnFailureIsError = false;
    this.txTrimWhitespace = false;
    this.txDeduplicate = false;
    this.txAiInstruction = '';

    if (this.dataQuality) {
      const dq = this.dataQuality;
      this.dqValidateHeader = dq.validateFileHeader || !!dq.validationSchema;
      this.dqValidationSchema = dq.validationSchema || '';
      if (dq.aiRule) {
        this.dqUseAiRule = true;
        this.dqAiInstruction = dq.aiRule.instruction || '';
        this.dqAiOnFailureIsError = !!dq.aiRule.onFailureIsError;
      }
    }

    if (this.transformation?.aiTransformation) {
      this.txAiInstruction = this.transformation.aiTransformation.instruction || '';
      this.txTrimWhitespace = this.txAiInstruction.includes('Trim leading/trailing whitespace');
      this.txDeduplicate = this.txAiInstruction.includes('Remove duplicate rows');
    }
  }

  emit(): void {
    const dq: any = {};
    if (this.dqValidateHeader && this.sourceType === 'csv') {
      dq.validateFileHeader = true;
    }
    if (this.dqValidationSchema && (this.sourceType === 'json' || this.sourceType === 'xml')) {
      dq.validationSchema = this.dqValidationSchema;
    }
    if (this.dqUseAiRule && this.dqAiInstruction) {
      dq.aiRule = { instruction: this.dqAiInstruction, onFailureIsError: this.dqAiOnFailureIsError };
    }

    let tx: any = null;
    if (this.txAiInstruction.trim()) {
      tx = { aiTransformation: { instruction: this.txAiInstruction } };
    }

    this.change.emit({
      dataQuality: Object.keys(dq).length > 0 ? dq : null,
      transformation: tx
    });
  }

  onSchemaFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files || input.files.length === 0) return;
    const file = input.files[0];
    this.dqValidationSchema = file.name;
    this.pipelineService.uploadConfigFile(file, 'validation-schema').subscribe({
      next: (resp: any) => {
        this.dqValidationSchema = resp.filename || file.name;
        this.emit();
      },
      error: () => { this.emit(); }
    });
  }

  generateValidationSchema(): void {
    const sanitized = sanitizeIdentifier(this.dqSchemaName);
    if (!sanitized) {
      this.dqGenerateError = 'Schema name is required';
      return;
    }
    this.dqSchemaName = sanitized;
    if (!this.dqSampleData.trim()) {
      this.dqGenerateError = 'Sample data is required';
      return;
    }
    const schemaType = this.sourceType === 'xml' ? 'xsd' : 'json-schema';
    this.dqGenerating = true;
    this.dqGenerateError = '';
    this.pipelineService.generateValidationSchema(schemaType, sanitized, this.dqSampleData).subscribe({
      next: (resp: any) => {
        this.dqValidationSchema = resp.filename;
        this.dqGenerating = false;
        this.emit();
      },
      error: (err: any) => {
        this.dqGenerateError = 'Failed to generate schema: ' + (err.error || err.message);
        this.dqGenerating = false;
      }
    });
  }

  onTxCheckboxChange(): void {
    let instruction = this.txAiInstruction;
    instruction = instruction.replace(this.TX_TRIM_TEXT, '').replace(this.TX_DEDUP_TEXT, '').trim();
    const additions: string[] = [];
    if (this.txTrimWhitespace) additions.push(this.TX_TRIM_TEXT);
    if (this.txDeduplicate) additions.push(this.TX_DEDUP_TEXT);
    if (additions.length > 0) {
      instruction = instruction ? additions.join(' ') + ' ' + instruction : additions.join(' ');
    }
    this.txAiInstruction = instruction;
    this.emit();
  }
}
