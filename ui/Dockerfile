# Stage 1: Build
# Pinned by digest (multi-arch manifest list); Dependabot docker sends digest-bump PRs.
FROM node:20-alpine@sha256:fb4cd12c85ee03686f6af5362a0b0d56d50c58a04632e6c0fb8363f609372293 AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Stage 2: Serve (non-root nginx, listens on 8080)
FROM nginxinc/nginx-unprivileged:alpine@sha256:901e944d1f4fc2bd077e8f5568b98c1f6f8cdacf6b97a87747c43134a339b9a7
COPY --from=build /app/dist/pipeline-ui/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
# The base image already runs as the unprivileged nginx user (UID 101); the
# explicit USER makes that visible to image scanners (Trivy DS-0002).
USER nginx
EXPOSE 8080
