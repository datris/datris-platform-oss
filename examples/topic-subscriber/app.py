"""
Datris Pipeline Notification Consumer
Subscribes to the pipeline notification topic via a durable queue
using the STOMP protocol.

Install dependencies:
    pip install stomp.py python-dotenv

Usage:
    python app.py

Environment variables (or .env file):
    ACTIVEMQ_HOST       - ActiveMQ broker host (default: localhost)
    ACTIVEMQ_PORT       - STOMP port (default: 61613)
    ACTIVEMQ_USER       - Broker username (default: admin)
    ACTIVEMQ_PASSWORD   - Broker password (default: admin)
    ACTIVEMQ_CLIENT_ID  - Durable subscriber client ID
    ACTIVEMQ_TOPIC      - Topic name (default: oss-pipeline-notification)
"""

import json
import logging
import os
import signal
import sys
import time
from datetime import datetime
from typing import Optional

import stomp
from dotenv import load_dotenv

load_dotenv()

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
    handlers=[
        logging.StreamHandler(sys.stdout),
        logging.FileHandler("notification_consumer.log"),
    ],
)
logger = logging.getLogger("datris.notification.consumer")

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
ACTIVEMQ_HOST       = os.getenv("ACTIVEMQ_HOST", "localhost")
ACTIVEMQ_PORT       = int(os.getenv("ACTIVEMQ_PORT", "61613"))
ACTIVEMQ_USER       = os.getenv("ACTIVEMQ_USER", "admin")
ACTIVEMQ_PASSWORD   = os.getenv("ACTIVEMQ_PASSWORD", "admin")
ACTIVEMQ_CLIENT_ID  = os.getenv("ACTIVEMQ_CLIENT_ID", "datris-python-notification-consumer")
TOPIC_NAME          = os.getenv("ACTIVEMQ_TOPIC", "oss-pipeline-notification")

# For Virtual Topic queue semantics (each consumer gets its own copy):
#   SUBSCRIPTION_DEST = "/queue/Consumer.python-app.VirtualTopic.oss-pipeline-notification"
# For direct durable topic subscription:
#   SUBSCRIPTION_DEST = "/topic/oss-pipeline-notification"
USE_VIRTUAL_TOPIC   = os.getenv("USE_VIRTUAL_TOPIC", "true").lower() == "true"

if USE_VIRTUAL_TOPIC:
    SUBSCRIPTION_DEST = f"/queue/Consumer.{ACTIVEMQ_CLIENT_ID}.VirtualTopic.{TOPIC_NAME}"
else:
    SUBSCRIPTION_DEST = f"/topic/{TOPIC_NAME}"

RECONNECT_DELAY_SECS    = 5
MAX_RECONNECT_ATTEMPTS  = 10


# ---------------------------------------------------------------------------
# Message handler
# ---------------------------------------------------------------------------
def handle_notification(message_id: str, headers: dict, body: str) -> None:
    """
    Process a single pipeline notification message.
    Customize this function with your pipeline logic.
    """
    logger.info(f"Received message | id={message_id}")
    logger.debug(f"Headers: {headers}")

    try:
        payload = json.loads(body)
        logger.info(f"Dataset notification: {json.dumps(payload, indent=2)}")

        # --- Your pipeline logic here ---
        pipeline_name   = payload.get("pipelineName")
        pipeline_token  = payload.get("pipelineToken")
        event_type      = payload.get("eventType")
        timestamp       = payload.get("timestamp", datetime.utcnow().isoformat())

        logger.info(
            f"Processing | pipeline={pipeline_name} | "
            f"event={event_type} | token={pipeline_token} | ts={timestamp}"
        )

        # Example: trigger downstream action
        # pipeline_client.trigger(pipeline_name, pipeline_token)

    except json.JSONDecodeError:
        # Non-JSON payload — log raw body
        logger.warning(f"Non-JSON message body: {body}")


# ---------------------------------------------------------------------------
# STOMP listener
# ---------------------------------------------------------------------------
class NotificationListener(stomp.ConnectionListener):

    def __init__(self, conn: stomp.Connection):
        self.conn = conn
        self.message_count = 0

    def on_connected(self, frame):
        logger.info(f"Connected to ActiveMQ broker at {ACTIVEMQ_HOST}:{ACTIVEMQ_PORT}")
        self._subscribe()

    def on_disconnected(self):
        logger.warning("Disconnected from ActiveMQ broker")

    def on_error(self, frame):
        logger.error(f"STOMP error: {frame.body}")

    def on_message(self, frame):
        message_id  = frame.headers.get("message-id", "unknown")
        logger.info(f"*** MESSAGE RECEIVED *** id={message_id}")
        try:
            handle_notification(message_id, frame.headers, frame.body)
            self.message_count += 1
        except Exception as e:
            logger.error(f"Error processing message {message_id}: {e}", exc_info=True)

    def _subscribe(self):
        self.conn.subscribe(
            destination=SUBSCRIPTION_DEST,
            id="notification-sub-1",
            ack="auto",
        )
        logger.info(f"Subscribed to: {SUBSCRIPTION_DEST}")
        logger.info(
            f"Mode: {'Virtual Topic Queue' if USE_VIRTUAL_TOPIC else 'Durable Topic'}"
        )


# ---------------------------------------------------------------------------
# Connection + retry loop
# ---------------------------------------------------------------------------
def create_connection() -> stomp.Connection:
    conn = stomp.Connection(
        host_and_ports=[(ACTIVEMQ_HOST, ACTIVEMQ_PORT)],
        heartbeats=(10000, 10000),  # 10s heartbeat
    )
    return conn


def connect(conn: stomp.Connection, listener: NotificationListener) -> bool:
    try:
        conn.set_listener("notification-listener", listener)
        conn.connect(
            ACTIVEMQ_USER,
            ACTIVEMQ_PASSWORD,
            wait=True,
        )
        return True
    except Exception as e:
        logger.error(f"Connection failed: {e}")
        return False


def run_consumer():
    conn        = create_connection()
    listener    = NotificationListener(conn)
    attempts    = 0

    # Graceful shutdown on SIGINT / SIGTERM
    def shutdown(sig, frame):
        logger.info("Shutdown signal received — disconnecting...")
        try:
            conn.disconnect()
        except Exception:
            pass
        logger.info(f"Total messages processed: {listener.message_count}")
        sys.exit(0)

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)

    # Connect with retry
    while attempts < MAX_RECONNECT_ATTEMPTS:
        if connect(conn, listener):
            attempts = 0
            logger.info("Waiting for messages... (Ctrl+C to stop)")
            # Keep alive — reconnect if dropped
            while conn.is_connected():
                time.sleep(1)
            logger.warning("Connection lost — reconnecting...")
        else:
            attempts += 1
            logger.warning(
                f"Reconnect attempt {attempts}/{MAX_RECONNECT_ATTEMPTS} "
                f"in {RECONNECT_DELAY_SECS}s..."
            )
            time.sleep(RECONNECT_DELAY_SECS)
            conn = create_connection()
            listener = NotificationListener(conn)

    logger.error("Max reconnect attempts reached. Exiting.")
    sys.exit(1)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    logger.info("=" * 60)
    logger.info("Datris Pipeline Notification Consumer Starting")
    logger.info(f"  Broker   : {ACTIVEMQ_HOST}:{ACTIVEMQ_PORT}")
    logger.info(f"  Topic    : {TOPIC_NAME}")
    logger.info(f"  Dest     : {SUBSCRIPTION_DEST}")
    logger.info(f"  ClientID : {ACTIVEMQ_CLIENT_ID}")
    logger.info("=" * 60)
    run_consumer()