import { Component, OnDestroy, OnInit } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { Subscription, filter } from 'rxjs';

@Component({
  selector: 'app-ops-shell',
  templateUrl: './ops-shell.component.html',
  styleUrls: ['./ops-shell.component.css']
})
export class OpsShellComponent implements OnInit, OnDestroy {
  private static readonly KEY = 'ops.lastChild';
  private static readonly CHILDREN = ['activity', 'ingestion'];
  private static readonly DEFAULT = 'activity';
  private sub?: Subscription;

  constructor(private router: Router) {}

  ngOnInit(): void {
    const bare = this.router.url.split('?')[0].split('#')[0];
    if (bare === '/ops' || bare === '/ops/') {
      const last = localStorage.getItem(OpsShellComponent.KEY) || OpsShellComponent.DEFAULT;
      const safe = OpsShellComponent.CHILDREN.includes(last) ? last : OpsShellComponent.DEFAULT;
      this.router.navigate(['/ops', safe], { replaceUrl: true });
    }

    this.sub = this.router.events
      .pipe(filter(e => e instanceof NavigationEnd))
      .subscribe((e: any) => {
        const m = /^\/ops\/([^\/?#]+)/.exec(e.urlAfterRedirects);
        if (m && OpsShellComponent.CHILDREN.includes(m[1])) {
          localStorage.setItem(OpsShellComponent.KEY, m[1]);
        }
      });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }
}
