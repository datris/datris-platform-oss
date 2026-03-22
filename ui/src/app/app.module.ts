import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { FormsModule } from '@angular/forms';
import { AppComponent } from './app.component';
import { DatasetsComponent } from './datasets/datasets.component';
import { DatasetCreateComponent } from './dataset-create/dataset-create.component';
import { DatasetEditComponent } from './dataset-edit/dataset-edit.component';
import { DatasetViewComponent } from './dataset-view/dataset-view.component';
import { DatasetStatusComponent } from './dataset-status/dataset-status.component';
import { DatasetDetailComponent } from './dataset-detail/dataset-detail.component';
import { SearchComponent } from './search/search.component';
import { AppRoutingModule } from './app-routing.module';
import { provideHttpClient } from '@angular/common/http';
import { MaterialModule } from './material.module';

@NgModule({
  declarations: [
    AppComponent,
    DatasetsComponent,
    DatasetCreateComponent,
    DatasetEditComponent,
    DatasetViewComponent,
    DatasetStatusComponent,
    DatasetDetailComponent,
    SearchComponent
  ],
  imports: [
    BrowserModule,
    FormsModule,
    AppRoutingModule,
    MaterialModule
  ],
  providers: [provideHttpClient()],
  bootstrap: [AppComponent]
})
export class AppModule { }
