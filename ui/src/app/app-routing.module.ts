import { NgModule } from '@angular/core';
import { RouterModule, Routes, Router, UrlTree } from '@angular/router';
import { inject } from '@angular/core';
import { PipelinesComponent } from './pipelines/pipelines.component';
import { PipelineCreateComponent } from './pipeline-create/pipeline-create.component';
import { PipelineEditComponent } from './pipeline-edit/pipeline-edit.component';
import { PipelineViewComponent } from './pipeline-view/pipeline-view.component';
import { PipelineStatusComponent } from './pipeline-status/pipeline-status.component';
import { PipelineDetailComponent } from './pipeline-detail/pipeline-detail.component';
import { SearchComponent } from './search/search.component';
import { McpComponent } from './mcp/mcp.component';
import { ConfigurationComponent } from './configuration/configuration.component';
import { TapsComponent } from './taps/taps.component';
import { TapCreateComponent } from './tap-create/tap-create.component';
import { TapRunComponent } from './tap-run/tap-run.component';
import { GettingStartedComponent } from './getting-started/getting-started.component';
import { DataCatalogComponent } from './data-catalog/data-catalog.component';
import { DiscoveryComponent } from './discovery/discovery.component';
import { AgentMonitorComponent } from './agent-monitor/agent-monitor.component';
import { LoginComponent } from './login/login.component';
import { authGuard } from './auth.guard';

const routes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: '', redirectTo: 'getting-started', pathMatch: 'full' },
  { path: 'getting-started', component: GettingStartedComponent, canActivate: [authGuard] },
  { path: 'pipelines', component: PipelinesComponent, canActivate: [authGuard] },
  { path: 'pipelines/create', component: PipelineCreateComponent, canActivate: [authGuard] },
  { path: 'pipelines/:name/edit', component: PipelineCreateComponent, canActivate: [authGuard] },
  { path: 'pipelines/:name', component: PipelineViewComponent, canActivate: [authGuard] },
  { path: 'data-catalog', component: DataCatalogComponent, canActivate: [authGuard] },
  { path: 'discovery', component: DiscoveryComponent, canActivate: [authGuard] },
  { path: 'taps', component: TapsComponent, canActivate: [authGuard] },
  { path: 'taps/create', component: TapCreateComponent, canActivate: [authGuard] },
  { path: 'taps/:name/edit', component: TapCreateComponent, canActivate: [authGuard] },
  { path: 'taps/:name/run', component: TapRunComponent, canActivate: [authGuard] },
  { path: 'ingestion', component: PipelineStatusComponent, canActivate: [authGuard] },
  { path: 'pipeline/:pipelineToken/:pipeline', component: PipelineDetailComponent, canActivate: [authGuard] },
  { path: 'search', component: SearchComponent, canActivate: [authGuard] },
  { path: 'mcp', component: McpComponent, canActivate: [authGuard] },
  { path: 'agent-monitor', component: AgentMonitorComponent, canActivate: [authGuard] },
  { path: 'configuration', component: ConfigurationComponent, canActivate: [authGuard] },
  // Secrets moved into Configuration as a sub-tab (v1.6.16+). Preserve the
  // legacy /secrets URL for bookmarks, getting-started links, and external docs.
  // Use a UrlTree-returning function so the ?tab=secrets query param survives
  // the redirect (Angular's string redirectTo URL-encodes the '?').
  {
    path: 'secrets',
    canActivate: [authGuard],
    redirectTo: (): UrlTree =>
      inject(Router).createUrlTree(['/configuration'], { queryParams: { tab: 'secrets' } })
  }
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule { }
