import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { PipelinesComponent } from './pipelines/pipelines.component';
import { PipelineCreateComponent } from './pipeline-create/pipeline-create.component';
import { PipelineEditComponent } from './pipeline-edit/pipeline-edit.component';
import { PipelineViewComponent } from './pipeline-view/pipeline-view.component';
import { PipelineStatusComponent } from './pipeline-status/pipeline-status.component';
import { PipelineDetailComponent } from './pipeline-detail/pipeline-detail.component';
import { SearchComponent } from './search/search.component';
import { McpComponent } from './mcp/mcp.component';
import { SecretsComponent } from './secrets/secrets.component';

const routes: Routes = [
  { path: '', redirectTo: 'pipelines', pathMatch: 'full' },
  { path: 'pipelines', component: PipelinesComponent },
  { path: 'pipelines/create', component: PipelineCreateComponent },
  { path: 'pipelines/:name/edit', component: PipelineCreateComponent },
  { path: 'pipelines/:name', component: PipelineViewComponent },
  { path: 'ingestion', component: PipelineStatusComponent },
  { path: 'pipeline/:pipelineToken/:pipeline', component: PipelineDetailComponent },
  { path: 'search', component: SearchComponent },
  { path: 'mcp', component: McpComponent },
  { path: 'secrets', component: SecretsComponent }
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule { }
