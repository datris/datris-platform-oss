/**
 * Databricks destination: the SQL warehouse is optional when the credentials
 * secret carries a `warehouse` field (the server resolves it at connection
 * time). Snowflake's warehouse stays required.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { of } from 'rxjs';

import { PipelineCreateComponent } from './pipeline-create.component';
import { PipelineService } from '../pipeline.service';
import { SearchService } from '../search.service';
import { HealthService } from '../health.service';
import { TapService } from '../tap.service';

describe('PipelineCreateComponent — Databricks warehouse optional', () => {
  let fixture: ComponentFixture<PipelineCreateComponent>;
  let component: PipelineCreateComponent;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [PipelineCreateComponent],
      imports: [FormsModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA, NO_ERRORS_SCHEMA],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: PipelineService, useValue: {
            getAvailableDestinations: () => of(['postgres', 'snowflake', 'databricks']),
            getPipelines: () => of([]),
            getPipeline: () => of({})
        } },
        { provide: SearchService, useValue: { getPipelines: () => of([]) } },
        { provide: HealthService, useValue: { isAvailable: () => true, refresh: () => Promise.resolve() } },
        { provide: TapService, useValue: { getTaps: () => of([]) } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}), paramMap: convertToParamMap({}) } } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(PipelineCreateComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  function onDatabricksStep(warehouse: string): void {
    component.step = 8;
    component.destType = 'databricks';
    component.dbxCredentialsSecret = 'databricks';
    component.dbxWarehouse = warehouse;
    component.dbxCatalog = 'datris';
    component.dbxSchema = 'default';
    component.dbxTable = 'orders';
  }

  it('does not require the SQL warehouse for Databricks', () => {
    onDatabricksStep('');
    component.nextStep();
    expect(component.error).not.toContain('warehouse');
    const db = component.buildConfig().destination.database;
    expect(db.useDatabricks).toBeTrue();
    expect('warehouse' in db).toBeFalse();
  });

  it('still emits an explicit Databricks warehouse', () => {
    onDatabricksStep(' abc123 ');
    expect(component.buildConfig().destination.database.warehouse).toBe('abc123');
  });

  it('still requires the warehouse for Snowflake', () => {
    component.step = 8;
    component.destType = 'snowflake';
    component.sfCredentialsSecret = 'sf';
    component.sfWarehouse = '';
    component.nextStep();
    expect(component.error).toBe('Warehouse is required');
  });

  it('shows "using the secret\'s warehouse" when the secret has a warehouse field', () => {
    onDatabricksStep('');
    component.checkDbxSecretWarehouse();
    http.expectOne('/api/v1/secrets/databricks').flush({ name: 'databricks', fields: { host: '****', token: '****', httpPath: '****' } });
    expect(component.dbxSecretHasWarehouse).toBeTrue();
    fixture.detectChanges();
    const hint = (fixture.nativeElement as HTMLElement).querySelector('.dbx-warehouse-hint');
    expect(hint?.textContent).toContain("Using the secret's warehouse");
  });

  it('no hint when the secret has no warehouse field or cannot be read', () => {
    onDatabricksStep('');
    component.checkDbxSecretWarehouse();
    http.expectOne('/api/v1/secrets/databricks').flush({ name: 'databricks', fields: { host: '****', token: '****' } });
    expect(component.dbxSecretHasWarehouse).toBeFalse();
    component.checkDbxSecretWarehouse();
    http.expectOne('/api/v1/secrets/databricks').flush('nope', { status: 404, statusText: 'Not Found' });
    expect(component.dbxSecretHasWarehouse).toBeFalse();
  });
});
