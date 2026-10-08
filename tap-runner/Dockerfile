# datris-tap-runner — isolated tap execution sidecar (tap-execution-isolation Phase 3).
# Holds NO platform secrets: no JVM, no Vault, no /datris/.env, no /config, no Docker socket.
# Pinned by digest (multi-arch manifest list); Dependabot docker sends digest-bump PRs.
FROM python:3.12-slim-bookworm@sha256:34386ef0cb081344d7ec1c103ba398e6e9f64e9ab3a1509accc92a4e24a07258

# The Debian packages in the slim image lag behind bookworm-security fixes
# (libpcre2 CVEs flagged by the weekly Trivy image scan).
RUN apt-get update && apt-get upgrade -y && rm -rf /var/lib/apt/lists/*

# The pip/setuptools bundled with the bookworm image lag behind their own security fixes.
RUN pip install --no-cache-dir --upgrade pip setuptools

# Pre-bake the same common package set the server image carries, so taps that don't
# declare extras run with no per-run install. (Tap-declared extras install into a
# throwaway venv at runtime — see app.py.)
RUN pip install --no-cache-dir \
    "urllib3>=2.8.0" requests beautifulsoup4 pandas lxml feedparser boto3 pyyaml openpyxl \
    python-dateutil pytz google-cloud-storage azure-storage-blob

# Non-root runtime user. Root FS is mounted read-only at deploy time; the only
# writable path is a tmpfs scratch (see docker-compose), used per run.
RUN useradd -r -u 10001 -m -d /home/runner runner

# /scratch: mount point of the codegen-scratch named volume when this image runs as
# datris-codegen-runner (CODEGEN_SCRATCH_DIR=/scratch). Created here, owned by runner
# with mode 0700, so Docker initialises the empty named volume with that ownership on
# first mount. The tap runner service does not mount it.
RUN mkdir -p /scratch && chown runner:runner /scratch && chmod 0700 /scratch
# Both take effect only where CODEGEN_SCRATCH_DIR is set (datris-codegen-runner):
# the scratch volume is dedicated, so everything under it is removed after each run and
# at start (not only cg_* run dirs), and the /tmp tmpfs is emptied after each run, so a
# script cannot leave files for the next one. The tap runner sets neither and is unaffected.
ENV CODEGEN_SCRATCH_DEDICATED=1 CODEGEN_TMP_DIR=/tmp

WORKDIR /app
COPY app.py /app/app.py
USER runner
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/health', timeout=3)" || exit 1
ENTRYPOINT ["python3", "/app/app.py"]
