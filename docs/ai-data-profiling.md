# AI Data Profiling

Upload any data file and receive an AI-generated profile — summary statistics, data quality issues, and recommendations for validation rules and transformations. Use profiling to understand your data before setting up a dataset configuration.

## Endpoint

`POST /api/v1/dataset/profile`

## Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `file` | multipart file | (required) | The data file to profile (CSV, JSON, or XML) |
| `delimiter` | string | `,` | CSV delimiter character |
| `header` | boolean | `true` | Whether the CSV file has a header row |
| `sampleSize` | int | `200` | Number of rows to sample for large files |

## Example

```bash
curl -s -X POST http://localhost:8080/api/v1/dataset/profile \
  -F "file=@stock_prices.csv" \
  -F "delimiter=," \
  -F "header=true" \
  -F "sampleSize=200" | python3 -m json.tool
```

## Response

The endpoint returns a JSON object with three sections:

```json
{
  "summary": {
    "rowCount": 200,
    "columnCount": 8,
    "columns": [
      {
        "name": "symbol",
        "inferredType": "string",
        "nullCount": 0,
        "uniqueCount": 195,
        "sampleValues": ["FAX", "IAF", "FCO"]
      },
      {
        "name": "date",
        "inferredType": "date",
        "nullCount": 0,
        "uniqueCount": 1,
        "sampleValues": ["2016-12-30"]
      },
      {
        "name": "open",
        "inferredType": "float",
        "nullCount": 1,
        "uniqueCount": 198,
        "sampleValues": ["4.65", "5.44", "7.91"]
      }
    ]
  },
  "qualityIssues": [
    "Column 'open' has 1 null/empty value",
    "Column 'volume' contains a negative value (-500)",
    "Column 'close' has a value exceeding $1,000,000"
  ],
  "recommendations": [
    "Add a regex column rule for 'symbol': ^[A-Z]{1,5}$",
    "Consider an aiRule to validate that prices are realistic for US equities",
    "Add a column rule to ensure 'volume' is non-negative"
  ],
  "suggestedDataQuality": {
    "aiRule": {
      "instruction": "all price columns (open, high, low, close, adj_close) must be positive and not exceed $1,000,000, volume must be positive, and high must be greater than or equal to low",
      "onFailureIsError": false
    },
    "columnRules": [
      {
        "columnName": "symbol",
        "function": "regex",
        "parameter": "^[A-Z]{1,5}$",
        "onFailureIsError": true,
        "description": "Stock ticker must be 1-5 uppercase letters"
      }
    ]
  }
}
```

### Response fields

| Section | Description |
|---------|-------------|
| `summary` | Row count, column count, and per-column statistics (inferred type, null count, unique count, sample values) |
| `qualityIssues` | Data quality problems detected in the sample — missing values, outliers, inconsistent formats, suspicious patterns |
| `recommendations` | Human-readable suggestions for validation rules and transformations |
| `suggestedDataQuality` | Ready-to-use `dataQuality` JSON block that can be copied directly into a dataset configuration. Includes regex `columnRules` for structural patterns and an `aiRule` for domain-specific checks that require reasoning |

### Suggested data quality rules

The `suggestedDataQuality` section provides a complete, copy-paste-ready `dataQuality` configuration based on what the AI observed in the data:

- **`columnRules`** — regex rules for columns with clear structural patterns (email formats, zip codes, ticker symbols, ID codes). The AI only suggests regex for columns where a pattern is detectable — not for free-text or numeric columns.
- **`aiRule`** — a single natural language instruction covering domain-specific checks that cannot be expressed as regex: value ranges, cross-column relationships (e.g., high >= low), and business logic. If no AI rule is appropriate, this field is omitted.

This follows the [choosing the right rule type](data-quality/column-rules.md) guidance: regex for format checks, AI for reasoning.

## How it works

1. You upload a file — no dataset registration needed
2. For large CSV files, the pipeline randomly samples `sampleSize` rows (keeping the header)
3. The sampled content is sent to the AI model with a profiling prompt
4. The AI analyzes the data and returns a structured JSON profile

Profiling is a standalone operation — it does not require a registered dataset, data quality rules, or any pipeline configuration. It is designed to be the first step when working with a new data source, before setting up validation or ingestion.

## Use cases

- **Explore new data** — understand the structure, types, and quality of an unfamiliar file before writing a dataset configuration
- **Discover quality issues** — find missing values, outliers, format inconsistencies, and suspicious patterns
- **Generate rule ideas** — the AI suggests specific regex patterns, aiRule instructions, and transformations based on what it observes
- **Validate assumptions** — confirm that a file matches expected schema and data quality before loading

## Sampling

For files larger than `sampleSize` rows, the profiling endpoint automatically samples a random subset. The header row is always included. This keeps profiling fast and within AI context window limits regardless of file size.

The default sample of 200 rows is typically sufficient to detect patterns, types, and quality issues. Increase `sampleSize` for more thorough profiling at the cost of slower response times.

## Requirements

- `ai.enabled: true` must be set in `application.yaml`
- A configured AI provider (see [AI Configuration](ai-configuration.md))
- Cloud providers (Anthropic, OpenAI) recommended for best results
