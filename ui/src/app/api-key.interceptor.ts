import { HttpInterceptorFn } from '@angular/common/http';

export const apiKeyInterceptor: HttpInterceptorFn = (req, next) => {
  const apiKey = localStorage.getItem('datris-api-key');
  if (apiKey && req.url.startsWith('/api/')) {
    req = req.clone({ setHeaders: { 'x-api-key': apiKey } });
  }
  return next(req);
};
