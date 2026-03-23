# Kafka Destination

The Kafka destination writes messages to a Kafka topic. It supports keyed messages for partition control, structured and raw payload formats, and SASL/PLAIN authentication.

## Message Format

The payload format depends on the source schema:

- **Structured data** -- When the schema contains regular typed columns, each row is serialized as a JSON object and sent as the message value.
- **JSON or XML** -- When the schema contains a single `_json` or `_xml` field, the raw string value is sent directly as the message value without additional wrapping.

## Message Key

Set `keyField` to the name of a column whose value should be used as the Kafka message key. Messages with the same key are routed to the same partition, preserving ordering for that key. If `keyField` is omitted, messages are distributed across partitions using the default partitioner.

## Authentication

The destination authenticates to the Kafka cluster using SASL/PLAIN. Credentials are retrieved from Vault using the secret name specified in `kafkaProducerSecretName`. The secret must contain:

| Key                | Description                    |
|--------------------|--------------------------------|
| `bootstrapServers` | Kafka bootstrap server list    |
| `username`         | SASL username                  |
| `password`         | SASL password                  |

## Bootstrap Server Override

To override the bootstrap servers from the Vault secret on a per-pipeline basis, set `overrideBootstrapServers` in the destination configuration.

## Configuration Example

```json
{
  "name": "stock_price_kafka",
  "source": { "..." : "..." },
  "destination": {
    "kafka": {
      "topic": "order-events",
      "keyField": "order_id",
      "overrideBootstrapServers": "broker1:9092,broker2:9092",
      "timeoutMs": 10000
    }
  }
}
```

Kafka credentials (`bootstrapServers`, `username`, `password`) are configured globally in `application.yaml` via `secrets.kafkaProducerSecretName`. See [Configuration Reference](../configuration-reference.md) for Vault secret format.

### Field Reference

| Field                      | Required | Default | Description                                      |
|----------------------------|----------|---------|--------------------------------------------------|
| `topic`                    | yes      |         | Target Kafka topic                               |
| `keyField`                 | no       |         | Column name to use as the message key            |
| `overrideBootstrapServers` | no       |         | Override bootstrap servers from the Vault secret |
| `timeoutMs`                | no       | `10000` | Producer send timeout in milliseconds            |
