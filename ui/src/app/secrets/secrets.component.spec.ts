/**
 * Story: Editing a secret keeps the values you did not change
 * (plans/stories/secrets-edit-preserves-sensitive-fields.md) — secrets.component.spec.ts bullet.
 *
 * Pins: `startEdit()` copies the server's `••••••••` mask into `editFields`
 * verbatim (today it maps it to '' and the server then writes '' as a real
 * value); `saveSecret()` sends that mask verbatim for sensitive fields the user
 * did not touch; `clearEditField(i)` drops the row so the key is absent from the
 * payload handed to `SecretsService.putSecret`; `_type` is still carried across.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of } from 'rxjs';

import { SecretsComponent } from './secrets.component';
import { SecretsService } from '../secrets.service';

const MASK = '••••••••';

describe('SecretsComponent — editing preserves untouched sensitive fields', () => {
  let fixture: ComponentFixture<SecretsComponent>;
  let component: SecretsComponent;
  let secretsService: {
    listSecrets: jasmine.Spy;
    getSecret: jasmine.Spy;
    putSecret: jasmine.Spy;
    deleteSecret: jasmine.Spy;
  };

  const storedSecret = {
    name: 'throwaway-s3',
    fields: {
      AWS_ACCESS_KEY_ID: MASK,
      AWS_SECRET_ACCESS_KEY: MASK,
      REGION: 'us-east-1',
      _type: 'tap'
    }
  };

  beforeEach(async () => {
    secretsService = {
      listSecrets: jasmine.createSpy('listSecrets').and.returnValue(of(['throwaway-s3'])),
      getSecret: jasmine.createSpy('getSecret').and.returnValue(of(storedSecret)),
      putSecret: jasmine.createSpy('putSecret').and.returnValue(of({ status: 'ok' })),
      deleteSecret: jasmine.createSpy('deleteSecret').and.returnValue(of({ status: 'ok' }))
    };
    await TestBed.configureTestingModule({
      declarations: [SecretsComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SecretsService, useValue: secretsService }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(SecretsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();

    // Open the stored secret's detail panel, then enter edit mode.
    component.selectSecret('throwaway-s3');
    fixture.detectChanges();
    component.startEdit();
    fixture.detectChanges();
  });

  function lastPutPayload(): Record<string, string> {
    expect(secretsService.putSecret).withContext('putSecret called').toHaveBeenCalledTimes(1);
    const [name, payload] = secretsService.putSecret.calls.mostRecent().args as [string, Record<string, string>];
    expect(name).toBe('throwaway-s3');
    return payload;
  }

  function editValue(key: string): string | undefined {
    return component.editFields.find(f => f.key === key)?.value;
  }

  it('startEdit leaves the mask in editFields for sensitive fields', () => {
    expect(editValue('AWS_ACCESS_KEY_ID')).toBe(MASK);
    expect(editValue('AWS_SECRET_ACCESS_KEY')).toBe(MASK);
    expect(editValue('REGION')).toBe('us-east-1');
    expect(component.editFields.some(f => f.key === '_type')).withContext('_type is not an editable row').toBeFalse();
  });

  it('saveSecret sends the mask verbatim for untouched sensitive fields', () => {
    // User adds one field and touches nothing else — the 2026-09-16 "add a REGION" scenario.
    component.addEditField();
    const added = component.editFields[component.editFields.length - 1];
    added.key = 'ENDPOINT';
    added.value = 'https://s3.example.test';

    component.saveSecret();

    const payload = lastPutPayload();
    expect(payload['AWS_ACCESS_KEY_ID']).toBe(MASK);
    expect(payload['AWS_SECRET_ACCESS_KEY']).toBe(MASK);
    expect(payload['REGION']).toBe('us-east-1');
    expect(payload['ENDPOINT']).toBe('https://s3.example.test');
  });

  it('a sensitive field the user re-types is sent with the new value', () => {
    const f = component.editFields.find(x => x.key === 'AWS_SECRET_ACCESS_KEY')!;
    f.value = 'rotated-secret-value';

    component.saveSecret();

    const payload = lastPutPayload();
    expect(payload['AWS_SECRET_ACCESS_KEY']).toBe('rotated-secret-value');
    expect(payload['AWS_ACCESS_KEY_ID']).toBe(MASK);
  });

  it('clearEditField(index) makes the key absent from the putSecret payload', () => {
    const idx = component.editFields.findIndex(f => f.key === 'AWS_ACCESS_KEY_ID');
    expect(idx).toBeGreaterThanOrEqual(0);

    component.clearEditField(idx);
    fixture.detectChanges();

    expect(component.editFields.some(f => f.key === 'AWS_ACCESS_KEY_ID')).toBeFalse();

    component.saveSecret();

    const payload = lastPutPayload();
    expect(Object.prototype.hasOwnProperty.call(payload, 'AWS_ACCESS_KEY_ID'))
      .withContext('cleared key must be omitted, not sent as ""').toBeFalse();
    expect(payload['AWS_SECRET_ACCESS_KEY']).toBe(MASK);
    expect(payload['REGION']).toBe('us-east-1');
  });

  it('_type is still preserved on the payload after an edit', () => {
    component.saveSecret();

    const payload = lastPutPayload();
    expect(payload['_type']).toBe('tap');
  });

  it('_type is still preserved even after clearing a field', () => {
    component.clearEditField(component.editFields.findIndex(f => f.key === 'REGION'));
    component.saveSecret();

    const payload = lastPutPayload();
    expect(payload['_type']).toBe('tap');
    expect(Object.prototype.hasOwnProperty.call(payload, 'REGION')).toBeFalse();
  });
});
