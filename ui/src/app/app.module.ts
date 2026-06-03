import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { FormsModule } from '@angular/forms';
import { AppComponent } from './app.component';
import { PipelinesComponent } from './pipelines/pipelines.component';
import { PipelineCreateComponent } from './pipeline-create/pipeline-create.component';
import { PipelineEditComponent } from './pipeline-edit/pipeline-edit.component';
import { PipelineConfigFormComponent } from './pipeline-config-form/pipeline-config-form.component';
import { PipelineDqTxEditorComponent } from './pipeline-dq-tx-editor/pipeline-dq-tx-editor.component';
import { PipelineViewComponent } from './pipeline-view/pipeline-view.component';
import { PipelineStatusComponent } from './pipeline-status/pipeline-status.component';
import { PipelineDetailComponent } from './pipeline-detail/pipeline-detail.component';
import { SearchComponent } from './search/search.component';
import { SearchChatPanelComponent } from './search/search-chat/search-chat-panel.component';
import { McpComponent } from './mcp/mcp.component';
import { SecretsComponent } from './secrets/secrets.component';
import { ApiKeyPromptComponent } from './api-key-prompt/api-key-prompt.component';
import { ConfigurationComponent } from './configuration/configuration.component';
import { TapPromptListComponent } from './configuration/tap-prompts/tap-prompt-list.component';
import { TapsComponent } from './taps/taps.component';
import { TapCreateComponent } from './tap-create/tap-create.component';
import { TapRunComponent } from './tap-run/tap-run.component';
import { DataCatalogComponent } from './data-catalog/data-catalog.component';
import { AgentMonitorComponent } from './agent-monitor/agent-monitor.component';
import { AssistantComponent } from './assistant/assistant.component';
import { McpShellComponent } from './mcp-shell/mcp-shell.component';
import { OpsShellComponent } from './ops-shell/ops-shell.component';
import { ActivityComponent } from './activity/activity.component';
import { OpsChatPanelComponent } from './ops-chat/ops-chat-panel.component';
import { LoginComponent } from './login/login.component';
import { ChangePasswordModalComponent } from './change-password-modal/change-password-modal.component';
import { UsersComponent } from './configuration/users/users.component';
import { KeysComponent } from './configuration/keys/keys.component';
import { AppRoutingModule } from './app-routing.module';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { apiKeyInterceptor } from './api-key.interceptor';
import { authErrorInterceptor } from './auth-error.interceptor';
import { MaterialModule } from './material.module';
import { NgxEchartsModule } from 'ngx-echarts';

@NgModule({
  declarations: [
    AppComponent,
    PipelinesComponent,
    PipelineCreateComponent,
    PipelineEditComponent,
    PipelineConfigFormComponent,
    PipelineDqTxEditorComponent,
    PipelineViewComponent,
    PipelineStatusComponent,
    PipelineDetailComponent,
    SearchComponent,
    SearchChatPanelComponent,
    McpComponent,
    SecretsComponent,
    ApiKeyPromptComponent,
    ConfigurationComponent,
    TapPromptListComponent,
    TapsComponent,
    TapCreateComponent,
    TapRunComponent,
    DataCatalogComponent,
    AgentMonitorComponent,
    AssistantComponent,
    McpShellComponent,
    OpsShellComponent,
    ActivityComponent,
    OpsChatPanelComponent,
    LoginComponent,
    ChangePasswordModalComponent,
    UsersComponent,
    KeysComponent
  ],
  imports: [
    BrowserModule,
    FormsModule,
    AppRoutingModule,
    MaterialModule,
    NgxEchartsModule.forRoot({ echarts: () => import('echarts') })
  ],
  providers: [provideHttpClient(withInterceptors([apiKeyInterceptor, authErrorInterceptor]))],
  bootstrap: [AppComponent]
})
export class AppModule { }
