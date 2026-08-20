import { Component, EventEmitter, Output } from '@angular/core';

@Component({
    selector: 'app-api-key-prompt',
    templateUrl: './api-key-prompt.component.html',
    styleUrl: './api-key-prompt.component.css',
    standalone: false
})
export class ApiKeyPromptComponent {
  @Output() keySet = new EventEmitter<void>();

  apiKey = '';
  error = '';

  connect(): void {
    const key = this.apiKey.trim();
    if (!key) {
      this.error = 'Please enter your API key';
      return;
    }

    this.error = '';
    localStorage.setItem('datris-api-key', key);
    this.keySet.emit();
  }
}
