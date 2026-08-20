import { Component, OnDestroy, OnInit } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { Subscription, filter } from 'rxjs';

@Component({
    selector: 'app-mcp-shell',
    templateUrl: './mcp-shell.component.html',
    styleUrls: ['./mcp-shell.component.css'],
    standalone: false
})
export class McpShellComponent implements OnInit, OnDestroy {
  private static readonly KEY = 'mcp.lastChild';
  private static readonly CHILDREN = ['activity', 'connect'];
  private static readonly DEFAULT = 'activity';
  private sub?: Subscription;

  constructor(private router: Router) {}

  ngOnInit(): void {
    const bare = this.router.url.split('?')[0].split('#')[0];
    if (bare === '/mcp' || bare === '/mcp/') {
      const last = localStorage.getItem(McpShellComponent.KEY) || McpShellComponent.DEFAULT;
      const safe = McpShellComponent.CHILDREN.includes(last) ? last : McpShellComponent.DEFAULT;
      this.router.navigate(['/mcp', safe], { replaceUrl: true });
    }

    this.sub = this.router.events
      .pipe(filter(e => e instanceof NavigationEnd))
      .subscribe((e: any) => {
        const m = /^\/mcp\/([^\/?#]+)/.exec(e.urlAfterRedirects);
        if (m && McpShellComponent.CHILDREN.includes(m[1])) {
          localStorage.setItem(McpShellComponent.KEY, m[1]);
        }
      });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }
}
