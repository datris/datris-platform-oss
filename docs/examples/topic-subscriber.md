# Topic Subscriber

**Location:** `examples/topic-subscriber/`

An ActiveMQ/STOMP consumer that subscribes to pipeline notification events. Use this to trigger downstream workflows when pipelines complete processing.

## Features

- Durable subscription with automatic reconnection
- Supports Virtual Topic and Durable Topic subscription styles
- Graceful shutdown on SIGINT/SIGTERM
- Logs to both stdout and file (`notification_consumer.log`)

## Configuration (`.env`)

```bash
ACTIVEMQ_HOST=localhost
ACTIVEMQ_PORT=61613
ACTIVEMQ_USER=admin
ACTIVEMQ_PASSWORD=admin
TOPIC_NAME=oss-pipeline-notification
SUBSCRIPTION_STYLE=virtual_topic    # virtual_topic or durable_topic
CLIENT_ID=my-subscriber
```

## Setup

```bash
cd examples/topic-subscriber
pip install stomp.py python-dotenv
```

## Run

```bash
python app.py
```

The subscriber prints pipeline processing notifications as they arrive, including pipeline name, destination, and pipeline token.

See [Notifications](../notifications.md) for how pipeline notifications work.
