import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { FormsModule } from '@angular/forms';
import { AppComponent } from './app.component';
import { PipelinesComponent } from './pipelines/pipelines.component';
import { PipelineCreateComponent } from './pipeline-create/pipeline-create.component';
import { PipelineEditComponent } from './pipeline-edit/pipeline-edit.component';
import { PipelineViewComponent } from './pipeline-view/pipeline-view.component';
import { PipelineStatusComponent } from './pipeline-status/pipeline-status.component';
import { PipelineDetailComponent } from './pipeline-detail/pipeline-detail.component';
import { SearchComponent } from './search/search.component';
import { McpComponent } from './mcp/mcp.component';
import { SecretsComponent } from './secrets/secrets.component';
import { ApiKeyPromptComponent } from './api-key-prompt/api-key-prompt.component';
import { ConfigurationComponent } from './configuration/configuration.component';
import { TapsComponent } from './taps/taps.component';
import { TapCreateComponent } from './tap-create/tap-create.component';
import { TapRunComponent } from './tap-run/tap-run.component';
import { AppRoutingModule } from './app-routing.module';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { apiKeyInterceptor } from './api-key.interceptor';
import { MaterialModule } from './material.module';

@NgModule({
  declarations: [
    AppComponent,
    PipelinesComponent,
    PipelineCreateComponent,
    PipelineEditComponent,
    PipelineViewComponent,
    PipelineStatusComponent,
    PipelineDetailComponent,
    SearchComponent,
    McpComponent,
    SecretsComponent,
    ApiKeyPromptComponent,
    ConfigurationComponent,
    TapsComponent,
    TapCreateComponent,
    TapRunComponent
  ],
  imports: [
    BrowserModule,
    FormsModule,
    AppRoutingModule,
    MaterialModule
  ],
  providers: [provideHttpClient(withInterceptors([apiKeyInterceptor]))],
  bootstrap: [AppComponent]
})
export class AppModule { }
