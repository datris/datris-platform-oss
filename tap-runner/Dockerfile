# datris-tap-runner — isolated tap execution sidecar (tap-execution-isolation Phase 3).
# Holds NO platform secrets: no JVM, no Vault, no /datris/.env, no /config, no Docker socket.
FROM python:3.14-slim-bookworm

# Pre-bake the same common package set the server image carries, so taps that don't
# declare extras run with no per-run install. (Tap-declared extras install into a
# throwaway venv at runtime — see app.py.)
RUN pip install --no-cache-dir \
    requests beautifulsoup4 pandas lxml feedparser boto3 pyyaml openpyxl \
    python-dateutil pytz google-cloud-storage azure-storage-blob

# Non-root runtime user. Root FS is mounted read-only at deploy time; the only
# writable path is a tmpfs scratch (see docker-compose), used per run.
RUN useradd -r -u 10001 -m -d /home/runner runner

WORKDIR /app
COPY app.py /app/app.py
USER runner
EXPOSE 8090
ENTRYPOINT ["python3", "/app/app.py"]
