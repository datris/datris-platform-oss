import { inject, NgModule } from '@angular/core';
import { Router, RouterModule, Routes, UrlTree } from '@angular/router';
import { PipelineCreateComponent } from './pipeline-create/pipeline-create.component';
import { PipelineViewComponent } from './pipeline-view/pipeline-view.component';
import { PipelineStatusComponent } from './pipeline-status/pipeline-status.component';
import { PipelineDetailComponent } from './pipeline-detail/pipeline-detail.component';
import { SearchComponent } from './search/search.component';
import { McpComponent } from './mcp/mcp.component';
import { ConfigurationComponent } from './configuration/configuration.component';
import { TapCreateComponent } from './tap-create/tap-create.component';
import { TapRunComponent } from './tap-run/tap-run.component';
import { DataCatalogComponent } from './data-catalog/data-catalog.component';
import { AgentMonitorComponent } from './agent-monitor/agent-monitor.component';
import { AssistantComponent } from './assistant/assistant.component';
import { McpShellComponent } from './mcp-shell/mcp-shell.component';
import { OpsShellComponent } from './ops-shell/ops-shell.component';
import { ActivityComponent } from './activity/activity.component';
import { LoginComponent } from './login/login.component';
import { authGuard } from './auth.guard';

const routes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: '', redirectTo: 'assistant', pathMatch: 'full' },

  // Legacy URL redirects (placed first so full-match exact-path redirects win
  // over the parametrized detail routes below — e.g. /pipelines/create must
  // hit the redirect, not pipelines/:name).
  { path: 'agent', redirectTo: 'mcp', pathMatch: 'full' },
  { path: 'agent/activity', redirectTo: 'mcp/activity', pathMatch: 'full' },
  { path: 'agent/chat', redirectTo: 'assistant', pathMatch: 'full' },
  { path: 'agent/connect', redirectTo: 'mcp/connect', pathMatch: 'full' },
  { path: 'agent-monitor', redirectTo: 'mcp/activity', pathMatch: 'full' },
  { path: 'data-catalog', redirectTo: 'catalog', pathMatch: 'full' },
  { path: 'pipelines', redirectTo: 'catalog', pathMatch: 'full' },
  { path: 'pipelines/create', redirectTo: 'catalog/pipelines/create', pathMatch: 'full' },
  { path: 'taps', redirectTo: 'catalog', pathMatch: 'full' },
  { path: 'taps/create', redirectTo: 'catalog/taps/create', pathMatch: 'full' },
  { path: 'ingestion', redirectTo: 'ops/ingestion', pathMatch: 'full' },
  { path: 'data', redirectTo: 'ops', pathMatch: 'full' },
  { path: 'data/ingestion', redirectTo: 'ops/ingestion', pathMatch: 'full' },
  { path: 'data/search', redirectTo: 'search', pathMatch: 'full' },
  { path: 'getting-started', redirectTo: 'assistant', pathMatch: 'full' },

  // Assistant — top-level, no shell
  { path: 'assistant', component: AssistantComponent, canActivate: [authGuard] },

  // MCP shell — Activity / Connect sub-tabs. The shell component restores the
  // last-active sub-tab from localStorage when the user lands at bare `/mcp`,
  // so no static default redirect is needed here.
  {
    path: 'mcp',
    component: McpShellComponent,
    canActivate: [authGuard],
    children: [
      { path: 'activity', component: AgentMonitorComponent },
      { path: 'connect', component: McpComponent }
    ]
  },

  // Catalog — list + nested create flows
  { path: 'catalog', component: DataCatalogComponent, canActivate: [authGuard] },
  { path: 'catalog/taps/create', component: TapCreateComponent, canActivate: [authGuard] },
  { path: 'catalog/pipelines/create', component: PipelineCreateComponent, canActivate: [authGuard] },

  // Ops shell — Activity / Ingestion sub-tabs. The shell component restores the
  // last-active sub-tab from localStorage when the user lands at bare `/ops`,
  // so no static default redirect is needed here.
  {
    path: 'ops',
    component: OpsShellComponent,
    canActivate: [authGuard],
    children: [
      { path: 'activity', component: ActivityComponent },
      { path: 'ingestion', component: PipelineStatusComponent }
    ]
  },

  // Search — promoted to top-level (was previously /data/search)
  { path: 'search', component: SearchComponent, canActivate: [authGuard] },

  // Detail routes — URLs unchanged, reached from inside Catalog via deep links.
  { path: 'pipelines/:name/edit', component: PipelineCreateComponent, canActivate: [authGuard] },
  { path: 'pipelines/:name', component: PipelineViewComponent, canActivate: [authGuard] },
  { path: 'taps/:name/edit', component: TapCreateComponent, canActivate: [authGuard] },
  { path: 'taps/:name/run', component: TapRunComponent, canActivate: [authGuard] },
  { path: 'pipeline/:pipelineToken/:pipeline', component: PipelineDetailComponent, canActivate: [authGuard] },

  { path: 'configuration', component: ConfigurationComponent, canActivate: [authGuard] },

  // Pop-out window targets — render a single component full-bleed, no app
  // shell. AppComponent.showChrome() returns false on /popout/* paths.
  { path: 'popout/activity', component: AgentMonitorComponent, canActivate: [authGuard] },

  // Secrets moved into Configuration as a sub-tab (v1.6.16+). Preserve the
  // legacy /secrets URL for bookmarks and external docs. Use a
  // UrlTree-returning function so the ?tab=secrets query param survives the
  // redirect (Angular's string redirectTo URL-encodes the '?').
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
