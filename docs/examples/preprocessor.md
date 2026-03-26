# Preprocessor

**Location:** `examples/preprocessor/`

A Flask REST API that demonstrates the pipeline's preprocessor capability. The preprocessor is called before data quality and transformation, allowing you to modify, enrich, or filter data before the pipeline processes it. Supports both synchronous and asynchronous modes.

## Endpoints

- `POST /preprocess/sync` — Process data synchronously and return the result
- `POST /preprocess/async` — Process data asynchronously and POST results back via callback

## Setup

```bash
cd examples/preprocessor
pip install flask requests
```

## Run

```bash
python app.py    # runs on port 5500
```

## Usage

Configure a pipeline's `source.preprocessor` with the endpoint URL. The pipeline sends data to your preprocessor before ingestion, allowing custom transformations.

See [Preprocessor](../preprocessor.md) for the full preprocessor protocol and configuration reference.
