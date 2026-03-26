#
# This helper will post a file to the Datris via a KafkaProducer
#

import argparse
from kafka import KafkaProducer


def publish_csv_file(filepath: str, topic: str, bootstrap_servers: str = "localhost:9092"):
    producer = KafkaProducer(
        bootstrap_servers=bootstrap_servers,
        value_serializer=lambda v: v.encode("utf-8"),
    )

    with open(filepath, encoding="utf-8") as f:
        content = f.read()

    producer.send(topic, value=content)
    producer.flush()
    producer.close()
    print(f"Published entire file '{filepath}' as a single message to topic '{topic}'")


if __name__ == "__main__":
    publish_csv_file("../../test-scripts/files/stock_price.20170102.dataset.csv",
                "idata.stock_price_object_store_databricks_stream", 
                "localhost:9092")