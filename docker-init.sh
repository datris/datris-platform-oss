#!/bin/bash

set -xe

if [ -d /config ] && [ "$(ls -A /config 2>/dev/null)" ]; then
  cp /config/* /usr/src/datrisserver/config/
fi

# Spark 3 on Java 17+ needs JPMS module-access opens to reach internal JDK
# classes (sun.nio.ch.DirectBuffer, etc.). Without these, the SparkSession
# fails to initialize with IllegalAccessError the first time anything tries
# to write through Spark (objectStore destinations, Postgres COPY staging).
# These are invariant for the platform — operators tune heap via JAVA_OPTS.
SPARK_JAVA17_OPENS=(
  --add-opens=java.base/java.lang=ALL-UNNAMED
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED
  --add-opens=java.base/java.io=ALL-UNNAMED
  --add-opens=java.base/java.net=ALL-UNNAMED
  --add-opens=java.base/java.nio=ALL-UNNAMED
  --add-opens=java.base/java.util=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
  --add-opens=java.base/sun.nio.cs=ALL-UNNAMED
  --add-opens=java.base/sun.security.action=ALL-UNNAMED
  --add-opens=java.base/sun.util.calendar=ALL-UNNAMED
  --add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED
)

java ${JAVA_OPTS} "${SPARK_JAVA17_OPENS[@]}" -Djava.net.preferIPv4Stack=true -Dspring.config.additional-location=file:/usr/src/datrisserver/config/ -jar /usr/src/datrisserver/datrisserver.jar
