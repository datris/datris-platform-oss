import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class SecretsService {
  constructor(private http: HttpClient) { }

  listSecrets(type?: string): Observable<string[]> {
    const url = type ? '/api/v1/secrets?type=' + encodeURIComponent(type) : '/api/v1/secrets';
    return this.http.get<string[]>(url);
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
