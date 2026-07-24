import { Component, OnInit } from '@angular/core';
import { TapPromptService, TapPromptFragment } from './tap-prompt.service';

/** Reserved prompt-fragment key holding the approved data-sources registry.
 *  The server injects this fragment whole into the Assistant and tap
 *  brainstormer system prompts (never keyword-matched like other fragments). */
const REGISTRY_KEY = 'data-sources';
const LENGTH_WARN_CHARS = 4000;

@Component({
  selector: 'app-data-sources',
  templateUrl: './data-sources.component.html',
  styleUrls: ['./data-sources.component.css']
})
export class DataSourcesComponent implements OnInit {
  content = '';
  enabled = true;
  loading = true;
  saving = false;
  success = '';
  error = '';

  private savedContent = '';
  private savedEnabled = true;

  constructor(private prompts: TapPromptService) {}

  ngOnInit(): void {
    this.prompts.get(REGISTRY_KEY).subscribe({
      next: (f) => {
        this.content = f.content || '';
        this.enabled = f.enabled !== false;
        this.savedContent = this.content;
        this.savedEnabled = this.enabled;
        this.loading = false;
      },
      error: (err) => {
        // 404 = no registry configured yet — start with an empty editor.
        if (err && err.status !== 404) {
          this.error = 'Failed to load the data sources registry.';
        }
        this.loading = false;
      }
    });
  }

  get dirty(): boolean {
    return this.content !== this.savedContent || this.enabled !== this.savedEnabled;
  }

  get contentLength(): number {
    return this.content.length;
  }

  get lengthWarning(): boolean {
    return this.contentLength > LENGTH_WARN_CHARS;
  }

  save(): void {
    this.saving = true;
    this.success = '';
    this.error = '';
    const fragment: TapPromptFragment = {
      key: REGISTRY_KEY,
      aliases: [],
      content: this.content,
      enabled: this.enabled
    };
    this.prompts.save(fragment).subscribe({
      next: () => {
        this.savedContent = this.content;
        this.savedEnabled = this.enabled;
        this.saving = false;
        this.success = 'Data sources saved. The Assistant picks up the change on its next message.';
      },
      error: () => {
        this.saving = false;
        this.error = 'Failed to save data sources.';
      }
    });
  }

  revert(): void {
    this.content = this.savedContent;
    this.enabled = this.savedEnabled;
    this.success = '';
    this.error = '';
  }
}
