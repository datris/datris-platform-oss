import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { DatasetsComponent } from './datasets/datasets.component';
import { DatasetCreateComponent } from './dataset-create/dataset-create.component';
import { DatasetEditComponent } from './dataset-edit/dataset-edit.component';
import { DatasetViewComponent } from './dataset-view/dataset-view.component';
import { DatasetStatusComponent } from './dataset-status/dataset-status.component';
import { DatasetDetailComponent } from './dataset-detail/dataset-detail.component';
import { SearchComponent } from './search/search.component';

const routes: Routes = [
  { path: '', redirectTo: 'datasets', pathMatch: 'full' },
  { path: 'datasets', component: DatasetsComponent },
  { path: 'datasets/create', component: DatasetCreateComponent },
  { path: 'datasets/:name/edit', component: DatasetCreateComponent },
  { path: 'datasets/:name', component: DatasetViewComponent },
  { path: 'ingestion', component: DatasetStatusComponent },
  { path: 'dataset/:pipelineToken/:dataset', component: DatasetDetailComponent },
  { path: 'search', component: SearchComponent }
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule { }
