import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface AgentSession {
  session_id: string;
  first_seen: number;
  last_seen: number;
  call_count: number;
  api_key_hint: string;
  tenant?: string;
  key_name?: string;
  client_name?: string;
  client_version?: string;
}

export interface AgentCall {
  ts: number;
  ts_formatted?: string;
  session_id: string;
  tool: string;
  status: string;
  latency_ms: number;
  api_key_hint: string;
  tenant?: string;
  key_name?: string;
  client_name?: string;
  client_version?: string;
  args_preview?: string;
  args_full?: string;
  response_preview?: string;
  response_size?: number;
  record_count?: number | null;
  error?: string;
}

export interface AgentActivity {
  server_time: number;
  sessions: AgentSession[];
  calls: AgentCall[];
  error?: string;
}

@Injectable({ providedIn: 'root' })
export class AgentMonitorService {
  private apiUrl = '/api/v1/mcp/activity';

  constructor(private http: HttpClient) { }

  getActivity(since: number): Observable<AgentActivity> {
    return this.http.get<AgentActivity>(this.apiUrl + '?since=' + since);
  }

  clearActivity(): Observable<void> {
    return this.http.delete<void>(this.apiUrl);
  }
}
