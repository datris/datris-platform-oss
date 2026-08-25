# Pinned by digest (multi-arch manifest list); Dependabot docker sends digest-bump PRs.
FROM eclipse-temurin:17-jre@sha256:13cc28a6cc72a38ce1f00c906be3580c1a3e604b8984d694f369a96742abc93b
RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends python3 python3-pip python3-venv && rm -rf /var/lib/apt/lists/*
# pebble is a loose (non-dpkg) service-manager binary shipped in the Ubuntu base;
# nothing here uses it and its bundled Go stdlib trips Trivy, so drop it.
RUN rm -f /usr/bin/pebble
RUN pip3 install --break-system-packages requests beautifulsoup4 pandas lxml feedparser boto3 pyyaml openpyxl python-dateutil pytz google-cloud-storage azure-storage-blob
ARG JAR_FILE=datrisserver/target/scala-*/*.jar
RUN mkdir -p /usr/src/datrisserver /usr/src/datrisserver/config
COPY ${JAR_FILE} /usr/src/datrisserver/datrisserver.jar
COPY docker-init.sh /usr/src/datrisserver/docker-init.sh
RUN chmod +x /usr/src/datrisserver/docker-init.sh
RUN groupadd -r datris && useradd -r -g datris -d /usr/src/datrisserver datris \
    && chown -R datris:datris /usr/src/datrisserver
USER datris
# Liveness only (any HTTP response counts): /api/v1/health/services probes every
# backend and is too slow/heavy for a container healthcheck.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD curl -s -o /dev/null http://127.0.0.1:8080/ || exit 1
ENTRYPOINT ["/usr/src/datrisserver/docker-init.sh"]
