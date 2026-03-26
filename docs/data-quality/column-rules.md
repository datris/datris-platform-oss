# Data Quality — CodeGen AI Rule

The `aiRule` validates data using a plain-English instruction. Datris generates a Python validation script from your instruction via an LLM (single API call), then runs the script locally against all data. This approach costs ~$0.003 per rule regardless of file size, compared to the previous approach of sending all data to the LLM.

## Configuration

Define the AI rule in the `aiRule` field within the `dataQuality` block of the pipeline configuration.

| Field | Type | Description |
|---|---|---|
| `instruction` | string | A natural language description of all validation criteria. Multiple checks can be combined in one instruction. |
| `onFailureIsError` | boolean | If `true`, failures abort processing. If `false`, failures are logged as warnings. |

---

## How it works

1. **Prompt generation** — Datris extracts column names and a few sample rows from the data, combines them with your instruction, and sends a single prompt to the configured AI model.
2. **Script generation** — The AI model generates a self-contained Python 3 validation script (stdlib only, no pip packages).
3. **Local execution** — The generated script is written to a temp file and executed via `python3` against the full data file. Processing cost is zero after the initial API call.
4. **Result parsing** — The script outputs a JSON array of failures: `[{"index": <row_number>, "reason": "..."}]`. Datris parses this and maps failures back to the original data.
5. **Cleanup** — Temp files are deleted after execution.

### Example

```json
{
  "dataQuality": {
    "aiRule": {
      "instruction": "Validate that all email addresses are properly formatted, all phone numbers contain 7-15 digits, all dates are in YYYY-MM-DD format, and prices are positive and not exceeding $1,000,000",
      "onFailureIsError": true
    }
  }
}
```

This single instruction replaces what previously required separate regex column rules for each column. The generated Python script handles all checks in one pass.

---

## Works with all file types

The CodeGen approach works for:

- **CSV/delimited files** — The script reads the CSV with the appropriate delimiter and validates each row.
- **JSON files** — The script parses the JSON array and validates each record.
- **XML files** — The script uses `xml.etree.ElementTree` to parse and validate each element.

For all formats, the instruction is the same plain-English description. The LLM generates the appropriate parser for the file type.

---

## Behavior

- The CodeGen AI rule generates the Python script once per rule evaluation (single LLM API call ~$0.003)
- The generated script runs locally via `python3` with a 5-minute timeout
- Scripts are constrained to Python standard library only (no external packages)
- Rules with `onFailureIsError: true` are counted as errors; `onFailureIsError: false` are warnings
- Processing aborts immediately if the error count exceeds **100**
- After all rows are evaluated, if **any errors** exist, processing is aborted
- Warnings are summarized at the end of validation

## Requirements

- `ai.enabled: true` must be set in `application.yaml`
- The Vault secret for the AI provider must be configured (see [AI Configuration](../ai-configuration.md))
- `python3` must be available on the pipeline server runtime
