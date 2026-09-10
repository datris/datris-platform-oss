# Stage 1: Build
# Pinned by digest (multi-arch manifest list); Dependabot docker sends digest-bump PRs.
FROM node:20-alpine@sha256:fb4cd12c85ee03686f6af5362a0b0d56d50c58a04632e6c0fb8363f609372293 AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build
# Version stamp read by the Doctor panel and `datris doctor` (version.skew).
# The release workflow passes the tag; a from-source build without it reports
# "unknown", which doctor flags as a warning rather than an error. Written
# here (build stage) because the nginx stage runs unprivileged.
ARG APP_VERSION=unknown
RUN printf '{"version":"%s"}\n' "$APP_VERSION" > /app/dist/pipeline-ui/browser/version.json

# Stage 2: Serve (non-root nginx, listens on 8080)
FROM nginxinc/nginx-unprivileged:alpine@sha256:2ddec616f1cb58bcac057aa388f28cb81e35137641ef4226d321714499329bd1
# The Alpine packages in the pinned base lag behind alpine-security fixes
# (libuuid/util-linux, libexpat, curl CVEs flagged by the weekly Trivy image
# scan). Upgrade as root, then drop back to the unprivileged nginx user.
USER root
RUN apk upgrade --no-cache
USER nginx
COPY --from=build /app/dist/pipeline-ui/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
# The base image already runs as the unprivileged nginx user (UID 101); the
# explicit USER makes that visible to image scanners (Trivy DS-0002).
USER nginx
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:8080/ || exit 1
