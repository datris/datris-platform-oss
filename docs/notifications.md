# Dataset Notifications

The pipeline publishes event notifications after each destination loader completes. Notifications are sent to an ActiveMQ **Virtual Topic** and can be consumed by any number of independent subscribers.

## How It Works

The pipeline uses ActiveMQ's [Virtual Destinations](https://activemq.apache.org/virtual-destinations) pattern:

1. Each destination loader (PostgreSQL, MongoDB, Kafka, ActiveMQ, Object Store, REST) publishes a notification to `VirtualTopic.{environment}-dataset-notification`
2. Consumers subscribe by creating a queue named `Consumer.{name}.VirtualTopic.{environment}-dataset-notification`
3. ActiveMQ automatically copies topic messages to all matching consumer queues
4. Each consumer gets its own independent queue with its own message cursor

No subscription API is needed — consumers simply connect to their named queue.

## Consuming Notifications

### Queue Naming Convention

```
Consumer.{consumer-name}.VirtualTopic.{environment}-dataset-notification
```

For example, with environment `oss`:
- `Consumer.analytics.VirtualTopic.oss-dataset-notification`
- `Consumer.audit-log.VirtualTopic.oss-dataset-notification`
- `Consumer.dashboard.VirtualTopic.oss-dataset-notification`

Each consumer queue receives a copy of every notification independently.

### Quick Start: Python Consumer (Minimal)

```python
import stomp
import json

class NotificationListener(stomp.ConnectionListener):
    def on_message(self, frame):
        notification = json.loads(frame.body)
        print(f"Dataset: {notification['dataset']}, Destination: {notification['destination']}")

conn = stomp.Connection([('localhost', 61613)])
conn.set_listener('', NotificationListener())
conn.connect('admin', 'admin', wait=True)
conn.subscribe(
    destination='/queue/Consumer.myapp.VirtualTopic.oss-dataset-notification',
    id=1,
    ack='auto'
)
```

> **Note:** This is a minimal snippet. For a production-ready consumer with automatic reconnect, heartbeats, graceful shutdown, and configurable virtual topic support, see [`helpers/topic-subscriber/`](../helpers/topic-subscriber/).

### Example: Java/JMS Consumer

```java
ConnectionFactory factory = new ActiveMQConnectionFactory("tcp://localhost:61616");
Connection connection = factory.createConnection("admin", "admin");
connection.start();
Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
Queue queue = session.createQueue("Consumer.myapp.VirtualTopic.oss-dataset-notification");
MessageConsumer consumer = session.createConsumer(queue);
consumer.setMessageListener(message -> {
    TextMessage text = (TextMessage) message;
    System.out.println("Notification: " + text.getText());
});
```

## Message Filtering

Notifications include JMS message properties that can be used with **JMS selectors** for filtering:

| Property | Description |
|----------|-------------|
| `dataset` | Dataset name |
| `destination` | Destination type (`postgres`, `objectStore`, `kafka`, `mongodb`, `activemq`) |
| `schema` | Database schema (database destinations) |
| `database` | Database name (database destinations) |
| `table` | Table name (database destinations) |
| `topic` | Kafka topic (Kafka destinations) |
| `queueName` | Queue name (ActiveMQ destinations) |
| `prefixKey` | Object store prefix (object store destinations) |

To filter, use a JMS selector when creating the consumer:

```java
MessageConsumer consumer = session.createConsumer(queue, "dataset = 'sales_data' AND destination = 'postgres'");
```

## Notification Payload

```json
{
  "dataset": "sales_data",
  "publisherToken": "publisher-123",
  "pipelineToken": "pt-abc12345-...",
  "destination": "postgres",
  "prefixKey": null,
  "objectStoreUrl": null,
  "objectStoreTemporaryUrl": null,
  "schema": "public",
  "database": "analytics",
  "table": "sales",
  "topic": null,
  "queueName": null
}
```

Fields are populated based on the destination type. Unused fields are `null`.

## Enabling Notifications

Notifications are enabled by default in `application.yaml`:

```yaml
sendDatasetNotifications: "true"
```

Set to `"false"` to disable all notifications.
