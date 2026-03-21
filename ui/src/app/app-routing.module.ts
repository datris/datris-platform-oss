import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { DatasetStatusComponent } from './dataset-status/dataset-status.component';
import { DatasetDetailComponent } from './dataset-detail/dataset-detail.component';

const routes: Routes = [
  { path: '', component: DatasetStatusComponent },
  { path: 'dataset/:pipelineToken/:dataset', component: DatasetDetailComponent }
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule { }
