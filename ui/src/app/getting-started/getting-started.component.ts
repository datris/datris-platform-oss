import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'app-getting-started',
  templateUrl: './getting-started.component.html',
  styleUrls: ['./getting-started.component.css']
})
export class GettingStartedComponent implements OnInit {
  isTrial = false;

  constructor(private http: HttpClient) { }

  ngOnInit(): void {
    this.http.get<any>('/api/v1/version').subscribe({
      next: (data) => {
        this.isTrial = data.multiTenant === 'true';
      }
    });
  }
}
