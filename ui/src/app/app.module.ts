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
import { AppRoutingModule } from './app-routing.module';
import { provideHttpClient } from '@angular/common/http';
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
