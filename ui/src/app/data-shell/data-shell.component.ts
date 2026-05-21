import { Component, OnDestroy, OnInit } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { Subscription, filter } from 'rxjs';

@Component({
  selector: 'app-data-shell',
  templateUrl: './data-shell.component.html',
  styleUrls: ['./data-shell.component.css']
})
export class DataShellComponent implements OnInit, OnDestroy {
  private static readonly KEY = 'data.lastChild';
  private static readonly CHILDREN = ['ingestion', 'search'];
  private static readonly DEFAULT = 'ingestion';
  private sub?: Subscription;

  constructor(private router: Router) {}

  ngOnInit(): void {
    const bare = this.router.url.split('?')[0].split('#')[0];
    if (bare === '/data' || bare === '/data/') {
      const last = localStorage.getItem(DataShellComponent.KEY) || DataShellComponent.DEFAULT;
      const safe = DataShellComponent.CHILDREN.includes(last) ? last : DataShellComponent.DEFAULT;
      this.router.navigate(['/data', safe], { replaceUrl: true });
    }

    this.sub = this.router.events
      .pipe(filter(e => e instanceof NavigationEnd))
      .subscribe((e: any) => {
        const m = /^\/data\/([^\/?#]+)/.exec(e.urlAfterRedirects);
        if (m && DataShellComponent.CHILDREN.includes(m[1])) {
          localStorage.setItem(DataShellComponent.KEY, m[1]);
        }
      });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }
}
