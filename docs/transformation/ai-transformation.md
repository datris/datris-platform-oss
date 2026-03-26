# AI Transformation (CodeGen)

AI transformations apply natural language instructions to transform data. Describe the transformation in plain English — Datris generates a Python script from your instruction and runs it locally against all data. This costs ~$0.003 per transformation regardless of file size.

## Configuration

Add an `aiTransformation` block to the `transformation` section:

```json
"transformation": {
    "aiTransformation": {
        "instruction": "convert all date values from YYYY-MM-DD format to MM/DD/YYYY format"
    }
}
```

| Field | Type | Description |
|---|---|---|
| `instruction` | string | A natural language description of the transformation to apply to every row. |

## Examples

**Date format conversion:**
```json
"aiTransformation": {
    "instruction": "convert all date values from YYYY-MM-DD format to MM/DD/YYYY format"
}
```

**Phone number standardization:**
```json
"aiTransformation": {
    "instruction": "standardize the phone_number column to +1-XXX-XXX-XXXX format"
}
```

**Data enrichment / categorization:**
```json
"aiTransformation": {
    "instruction": "add a 'sector' column at the end based on the stock symbol, categorizing each as one of: Technology, Healthcare, Finance, Energy, Consumer, Industrial, Other"
}
```

**Combined operations:**
```json
"aiTransformation": {
    "instruction": "convert dates to YYYY/MM/DD. Trim leading/trailing whitespace from all columns. Remove duplicate rows."
}
```

## How it works

1. Datris extracts column names and sample rows from the data, combines them with your instruction, and sends a single prompt to the AI model.
2. The AI generates a self-contained Python 3 script (stdlib only) that reads the input file, applies the transformation, and writes the output.
3. The script is executed locally via `python3`. Processing cost is zero after the initial API call.
4. The transformed data replaces the original and continues through the pipeline to destinations.

AI transformations run after deduplication and column trimming, and before data is written to destinations.

## Works with all file types

- **CSV/delimited files** — The script reads and writes CSV with the appropriate delimiter.
- **JSON files** — The script parses JSON, transforms records, and writes JSON.
- **XML files** — The script uses `xml.etree.ElementTree` to parse, transform, and write XML.

## CLI

```bash
datris ingest data.csv --dest postgres --ai-transform "convert dates to YYYY/MM/DD and uppercase all names"
```

## Requirements

- `ai.enabled: true` must be set in `application.yaml`
- The Vault secret for the AI provider must be configured (see [AI Configuration](../ai-configuration.md))
- `python3` must be available on the pipeline server runtime
