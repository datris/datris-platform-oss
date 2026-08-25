# Pinned by digest (multi-arch manifest list); Dependabot docker sends digest-bump PRs.
FROM python:3.12-slim@sha256:7a8b475003c4fe15a2cd4e55e5cfc2f3560bdc9333d624f24cdd6d4340fd7a17

LABEL io.modelcontextprotocol.server.name="io.github.datris/datris"

WORKDIR /app

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
