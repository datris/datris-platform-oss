#!/bin/bash

set -xe

if [ -d /config ] && [ "$(ls -A /config 2>/dev/null)" ]; then
  cp /config/* /usr/src/datrisserver/config/
fi

java ${JAVA_OPTS} -Djava.net.preferIPv4Stack=true -Dspring.config.additional-location=file:/usr/src/datrisserver/config/ -jar /usr/src/datrisserver/datrisserver.jar
