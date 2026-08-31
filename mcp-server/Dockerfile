# Pinned by digest (multi-arch manifest list); Dependabot docker sends digest-bump PRs.
FROM python:3.12-slim@sha256:09f7da3bc104798d0afb40bc08d23ab2da20a76130cec1f2ef170848f5d85217

LABEL io.modelcontextprotocol.server.name="io.github.datris/datris"

WORKDIR /app

# The Debian packages in the slim image lag behind trixie-security fixes
# (openssl CVEs flagged by the weekly Trivy image scan).
RUN apt-get update && apt-get upgrade -y && rm -rf /var/lib/apt/lists/*

# The pip bundled with the slim image lags behind its own security fixes.
RUN pip install --no-cache-dir --upgrade pip

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

RUN groupadd -r mcp && useradd -r -g mcp -d /app mcp && chown -R mcp:mcp /app
USER mcp

EXPOSE 3000

# TCP-connect liveness: the SSE endpoint streams, so an HTTP GET would hang.
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD python -c "import socket; socket.create_connection(('127.0.0.1', 3000), timeout=3)" || exit 1

CMD ["python", "server.py", "--sse", "--port", "3000"]
