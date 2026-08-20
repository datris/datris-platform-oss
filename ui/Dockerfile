# Stage 1: Build
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Stage 2: Serve (non-root nginx, listens on 8080)
FROM nginxinc/nginx-unprivileged:alpine
COPY --from=build /app/dist/pipeline-ui/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
# The base image already runs as the unprivileged nginx user (UID 101); the
# explicit USER makes that visible to image scanners (Trivy DS-0002).
USER nginx
EXPOSE 8080
