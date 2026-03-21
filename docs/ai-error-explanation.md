# AI Error Explanation

When a pipeline job fails, the AI automatically analyzes the error and provides a plain-English explanation of the root cause and suggested fix. This appears as a separate status entry after the error, making it easy to diagnose issues without reading stack traces.

## How it works

When any pipeline stage (data quality, transformation, destination loading) throws an exception:

1. The error is logged to status as usual
2. If AI is enabled, the error message and dataset configuration are sent to the AI model
3. The AI returns a concise explanation of what went wrong and how to fix it
4. The explanation is written as a separate status entry (`AI Explanation: ...`) and logged to the pipeline logs

If the AI call itself fails (rate limit, timeout, etc.), the explanation is silently skipped — it never causes a secondary failure.

## Example

**Error status:**
```
Process completed, error: Aborting processing this dataset, 3 error(s) were found
while performing data quality rules:
Data quality AI failure, row: 2, reason: high (7.50) is less than low (8.01)
Data quality AI failure, row: 3, reason: volume is negative (-500)
Data quality AI failure, row: 4, reason: close and adj_close exceed $1,000,000
```

**AI Explanation status (separate entry):**
```
AI Explanation: Three rows in the source data violate the data quality rules:
row 2 has a high value lower than low (likely a column swap or data entry error),
row 3 contains a negative volume, and row 4 has close and adj_close values exceeding
$1,000,000. Fix: Correct the upstream source data or add a pre-processing
transformation step to sanitize these issues before the data quality check runs.
```

## Configuration

No additional configuration is needed. AI error explanation is automatically enabled when `ai.enabled: true` is set in `application.yaml` and an AI provider is configured.

To disable AI error explanations, set `ai.enabled: false` — this also disables all other AI features (aiRule, AI transformations, schema generation).

## Requirements

- `ai.enabled: true` in `application.yaml`
- A configured AI provider (see [AI Configuration](ai-configuration.md))
