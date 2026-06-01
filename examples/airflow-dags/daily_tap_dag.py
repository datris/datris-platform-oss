"""Example Airflow DAG: run a Datris tap daily and wait for the pipeline.

Requires the Datris Airflow provider:

    pip install airflow-provider-datris

Setup:
  1. Add a connection of type "Datris" in the Airflow UI with `conn_id`
     "datris_default". Set Host to your Datris base URL. Set Password to an
     API key holding the `tap:run` capability (only required when the Datris
     install enforces API keys).
  2. In Datris, leave the tap's cron empty (manual-only). If the tap has a
     Datris cron, the operator refuses to trigger it — Datris already schedules
     it, and the operator won't double-fire.

This DAG triggers the tap once per day, forwards the run's logical date as a
`since`/`until` window via per-run params, waits for the resulting pipeline to
reach a terminal state, and surfaces the run tokens/metrics as XComs.
"""

from __future__ import annotations

from datetime import datetime

from airflow import DAG
from datris_provider.operators import DatrisRunTapOperator

with DAG(
    dag_id="datris_daily_tap",
    start_date=datetime(2026, 1, 1),
    schedule="@daily",
    catchup=False,
    tags=["datris"],
) as dag:
    ingest = DatrisRunTapOperator(
        task_id="ingest_orders",
        tap_name="orders_api_to_postgres",
        datris_conn_id="datris_default",
        wait_for_completion=True,
        poll_interval=15,
        # Per-run window — exposed to the tap script as env vars.
        tap_params={"since": "{{ ds }}", "until": "{{ next_ds }}"},
    )
