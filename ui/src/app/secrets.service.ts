import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class SecretsService {
  constructor(private http: HttpClient) { }

  listSecrets(): Observable<string[]> {
    return this.http.get<string[]>('/api/v1/secrets');
  }

  getSecret(name: string): Observable<any> {
    return this.http.get<any>('/api/v1/secrets/' + encodeURIComponent(name));
  }

  putSecret(name: string, fields: Record<string, string>): Observable<any> {
    return this.http.put<any>('/api/v1/secrets/' + encodeURIComponent(name), fields);
  }

  deleteSecret(name: string): Observable<any> {
    return this.http.delete<any>('/api/v1/secrets/' + encodeURIComponent(name));
  }
}
