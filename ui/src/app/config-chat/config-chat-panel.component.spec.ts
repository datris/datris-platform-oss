/**
 * Story: Configuration chat, UI: confirm cards, secret form, show-once values,
 * sub-tab refresh (plans/stories/config-chat-ui-cards.md), Step 2.
 *
 * submitSecretForm shapes one REST call by secret name; the values go straight
 * from the browser to the secrets endpoint and the chat only says "provided".
 * The state service is a spy so these cases pin only the request shapes and
 * the success / error bookkeeping on the card.
 */
import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';

import { ConfigChatPanelComponent } from './config-chat-panel.component';
import { ConfigAssistantStateService, ToolCard, TOOL_TAB } from './config-assistant-state.service';
import { SecretsService } from '../secrets.service';

describe('ConfigChatPanelComponent — secret form submit', () => {
  let panel: ConfigChatPanelComponent;
  let httpMock: HttpTestingController;
  let putSecret: jasmine.Spy;
  let state: jasmine.SpyObj<ConfigAssistantStateService>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    httpMock = TestBed.inject(HttpTestingController);
    putSecret = jasmine.createSpy('putSecret').and.returnValue(of({}));
    state = jasmine.createSpyObj<ConfigAssistantStateService>('state', ['send', 'notifyChanged']);
    panel = new ConfigChatPanelComponent(
      state,
      TestBed.inject(HttpClient),
      { putSecret } as unknown as SecretsService
    );
  });

  afterEach(() => httpMock.verify());

  function formCard(name: string, secretName: string, values: Record<string, string>, input: any = {}): ToolCard {
    return {
      kind: 'tool', id: 'c1', name, input, result: '', isError: false, status: 'ok', expanded: false,
      secretRequest: {
        secretName,
        fieldNames: Object.keys(values),
        reason: '',
        fieldValues: { ...values },
        submitting: false,
        submitted: false,
        cancelled: false,
        errorMessage: ''
      }
    };
  }

  it('ai-keys PUTs exactly the provided fields to /api/v1/secrets/ai-keys', () => {
    const card = formCard('set_provider_credentials', 'ai-keys', { anthropicApiKey: 'sk-ant-1' });

    panel.submitSecretForm(card);

    const req = httpMock.expectOne('/api/v1/secrets/ai-keys');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ anthropicApiKey: 'sk-ant-1' });
    req.flush('ok');
    expect(putSecret).not.toHaveBeenCalled();
    expect(card.secretRequest!.submitted).toBeTrue();
  });

  it('create_repo_token tags the secret repo_token', () => {
    const card = formCard('create_repo_token', 'github', { token: 'ghp_x' });

    panel.submitSecretForm(card);

    expect(putSecret).toHaveBeenCalledOnceWith('github', { token: 'ghp_x', _type: 'repo_token' });
  });

  it("put_secret with type 'tap' tags the secret tap", () => {
    const card = formCard('put_secret', 'weather-api', { api_key: 'k1' }, { name: 'weather-api', type: 'tap' });

    panel.submitSecretForm(card);

    expect(putSecret).toHaveBeenCalledOnceWith('weather-api', { api_key: 'k1', _type: 'tap' });
  });

  it('put_secret with no type stores the fields untagged', () => {
    const card = formCard('put_secret', 'warehouse-creds', { password: 'p1' }, { name: 'warehouse-creds' });

    panel.submitSecretForm(card);

    expect(putSecret).toHaveBeenCalledOnceWith('warehouse-creds', { password: 'p1' });
  });

  it('on success wipes values, marks submitted, notifies the tab and sends provided', () => {
    const card = formCard('put_secret', 'weather-api', { api_key: 'k1', region: 'us' }, { type: 'tap' });

    panel.submitSecretForm(card);

    const sr = card.secretRequest!;
    expect(sr.fieldValues).toEqual({ api_key: '', region: '' });
    expect(sr.submitted).toBeTrue();
    expect(sr.submitting).toBeFalse();
    expect(state.notifyChanged).toHaveBeenCalledOnceWith(TOOL_TAB['put_secret']);
    expect(state.send).toHaveBeenCalledOnceWith('provided');
  });

  it('on a 500 keeps the form editable with an error message', () => {
    putSecret.and.returnValue(throwError(() => ({ status: 500, error: 'Vault unavailable' })));
    const card = formCard('put_secret', 'weather-api', { api_key: 'k1' }, { type: 'tap' });

    panel.submitSecretForm(card);

    const sr = card.secretRequest!;
    expect(sr.submitted).toBeFalse();
    expect(sr.submitting).toBeFalse();
    expect(sr.errorMessage).toContain('Vault unavailable');
    expect(sr.fieldValues).toEqual({ api_key: 'k1' });
    expect(state.send).not.toHaveBeenCalled();
    expect(state.notifyChanged).not.toHaveBeenCalled();
  });

  it('ai-keys 500 leaves the form editable', () => {
    const card = formCard('set_provider_credentials', 'ai-keys', { openaiApiKey: 'sk-1' });

    panel.submitSecretForm(card);
    httpMock.expectOne('/api/v1/secrets/ai-keys').flush('boom', { status: 500, statusText: 'Server Error' });

    expect(card.secretRequest!.submitted).toBeFalse();
    expect(card.secretRequest!.errorMessage).toContain('Save failed');
  });
});
