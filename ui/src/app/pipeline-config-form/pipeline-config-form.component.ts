import { Component, EventEmitter, Input, Output } from '@angular/core';

@Component({
  selector: 'app-pipeline-config-form',
  templateUrl: './pipeline-config-form.component.html',
  styleUrls: ['./pipeline-config-form.component.css']
})
export class PipelineConfigFormComponent {
  @Input() configJson = '';
  @Input() error = '';
  @Input() rows = 25;
  @Output() configJsonChange = new EventEmitter<string>();

  onChange(value: string): void {
    this.configJson = value;
    this.configJsonChange.emit(value);
  }
}
