/**
 * Story: Editing a secret keeps the values you did not change
 * (plans/stories/secrets-edit-preserves-sensitive-fields.md) — review follow-up.
 *
 * The server now preserves a stored sensitive value when a PUT sends "" for
 * it, so the Configuration tab can no longer clear a slot secret's apiKey by
 * sending an empty string. In an Azure Entra mode (service principal /
 * managed identity) the slot PUT must OMIT apiKey so the stored key is
 * removed and request-time resolution falls through to Entra.
 */
import { TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';

import { ConfigurationComponent } from './configuration.component';
import { ModelCatalogService } from '../model-catalog.service';
import { AuthService } from '../auth.service';

describe('ConfigurationComponent — Azure Entra mode omits the slot apiKey', () => {
  let component: ConfigurationComponent;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ConfigurationComponent],
      imports: [FormsModule],
      schemas: [NO_ERRORS_SCHEMA],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { queryParamMap: of(convertToParamMap({})) } },
        { provide: ModelCatalogService, useValue: { fetch: () => new Promise(() => { /* never resolves */ }) } },
        { provide: AuthService, useValue: { current: () => ({ role: 'admin' }) } }
      ]
    }).compileComponents();

    // Do not call fixture.detectChanges(): ngOnInit issues a fan of GETs that
    // are irrelevant here. Drive save() on the bare instance instead.
    component = TestBed.createComponent(ConfigurationComponent).componentInstance;
    httpMock = TestBed.inject(HttpTestingController);

    component.aiPrimaryProvider = 'azure';
    component.aiPrimaryModel = 'gpt-5.6-sol';
    component.aiPrimaryEndpoint = 'https://example-resource.openai.azure.com/openai/v1/chat/completions';
    // A previously stored key is still showing masked in the panel.
    component.azureApiKey = '••••••••';
  });

  function aiPrimaryPutBody(): Record<string, string> {
    component.save();
    const req = httpMock.expectOne(r => r.method === 'PUT' && r.url === '/api/v1/secrets/ai-primary');
    return req.request.body as Record<string, string>;
  }

  it('service-principal mode save omits apiKey from the ai-primary PUT', () => {
    component.azureAuthMode = 'sp';
    component.azureTenantId = '00000000-0000-0000-0000-000000000001';
    component.azureClientId = '00000000-0000-0000-0000-000000000002';
    component.azureClientSecret = 'sp-secret';

    const body = aiPrimaryPutBody();
    expect(body['provider']).toBe('azure');
    expect(Object.prototype.hasOwnProperty.call(body, 'apiKey'))
      .withContext('an empty apiKey would be preserved server-side; the key must be omitted').toBeFalse();
  });

  it('managed-identity mode save omits apiKey from the ai-primary PUT', () => {
    component.azureAuthMode = 'mi';

    const body = aiPrimaryPutBody();
    expect(Object.prototype.hasOwnProperty.call(body, 'apiKey')).toBeFalse();
  });

  it('API-key mode save still sends the (masked) apiKey so the server preserves it', () => {
    component.azureAuthMode = 'key';

    const body = aiPrimaryPutBody();
    expect(body['apiKey']).toBe('••••••••');
  });
});
