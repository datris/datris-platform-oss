FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/*
ARG JAR_FILE=datrisserver/target/scala-*/*.jar
RUN mkdir -p /usr/src/datrisserver /usr/src/datrisserver/config
COPY ${JAR_FILE} /usr/src/datrisserver/datrisserver.jar
COPY docker-init.sh /usr/src/datrisserver/docker-init.sh
RUN chmod +x /usr/src/datrisserver/docker-init.sh
ENTRYPOINT ["/usr/src/datrisserver/docker-init.sh"]
