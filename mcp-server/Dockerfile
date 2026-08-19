FROM python:3.14-slim

LABEL io.modelcontextprotocol.server.name="io.github.datris/datris"

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

RUN groupadd -r mcp && useradd -r -g mcp -d /app mcp && chown -R mcp:mcp /app
USER mcp

EXPOSE 3000

CMD ["python", "server.py", "--sse", "--port", "3000"]
