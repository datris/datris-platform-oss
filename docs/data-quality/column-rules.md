# Column Validation

Column rules validate individual column values for every row in the pipeline using `"regex"` pattern matching.

For AI-powered validation that can check multiple columns, cross-column logic, and natural language rules, see the `aiRule` section below.

## Configuration

Define column rules in the `columnRules` array within the `dataQuality` block of the pipeline configuration. Each rule has the following fields:

| Field | Type | Description |
|---|---|---|
| `columnName` | string | The name of the column to validate. |
| `function` | string | The validation function: `"regex"`. |
| `parameter` | string | The regex pattern to match against. |
| `onFailureIsError` | boolean | If `true`, a validation failure aborts processing. If `false`, failures are logged as warnings. |
| `description` | string | A human-readable description of what the rule checks. |

---

## Regex Column Rules

Use `"function": "regex"` to validate column values against a regular expression pattern.

```json
{
  "columnName": "email",
  "function": "regex",
  "parameter": "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$",
  "onFailureIsError": true,
  "description": "Email must be a valid email address format"
}
```

### Example

```json
{
  "dataQuality": {
    "columnRules": [
      {
        "columnName": "email",
        "function": "regex",
        "parameter": "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$",
        "onFailureIsError": true,
        "description": "Email must be a valid email address format"
      },
      {
        "columnName": "phone_number",
        "function": "regex",
        "parameter": "^\\+?[0-9]{7,15}$",
        "onFailureIsError": false,
        "description": "Phone number should contain 7-15 digits with optional leading +"
      },
      {
        "columnName": "country_code",
        "function": "regex",
        "parameter": "^[A-Z]{2}$",
        "onFailureIsError": true,
        "description": "Country code must be exactly two uppercase letters (ISO 3166-1 alpha-2)"
      }
    ]
  }
}
```

---

## AI Rule

Use the `aiRule` field to validate the entire file with a single natural language instruction. The AI model receives the full file content (header and all rows) in one call, giving it complete context to evaluate complex rules that span columns or require domain knowledge.

```json
{
  "dataQuality": {
    "aiRule": {
      "instruction": "prices should be realistic for US equities (not negative, not exceeding $1,000,000)",
      "onFailureIsError": false
    }
  }
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `instruction` | string | | A natural language description of all validation criteria. Multiple checks can be combined in one instruction. |
| `onFailureIsError` | boolean | | If `true`, failures abort processing. If `false`, failures are logged as warnings. |
| `sample` | boolean | `false` | If `true`, randomly samples rows instead of sending the entire file. Recommended for large files. |
| `sampleSize` | int | `200` | Number of rows to sample when `sample` is `true`. |

### How it works

The pipeline sends the file content (with header) to the AI model in a single call. The model evaluates every row against the instruction and returns only the rows that fail validation, along with a reason for each failure.

### Sampling mode

For large files, enable sampling to validate a random subset of rows instead of the entire file. This is **recommended for files over a few thousand rows** — it dramatically reduces processing time from minutes to seconds while still catching systematic data quality issues.

```json
{
  "dataQuality": {
    "aiRule": {
      "instruction": "prices should be realistic for US equities (not negative, not exceeding $1,000,000)",
      "onFailureIsError": false,
      "sample": true,
      "sampleSize": 200
    }
  }
}
```

Data quality issues like invalid formats, missing values, out-of-range numbers, and broken cross-column relationships tend to be consistent across a pipeline. A random sample of 200 rows is typically sufficient to detect these patterns. If the data passes a 200-row sample, it is very likely clean.

**Why sampling is recommended for large files:**

- **Speed** — 200 rows validates in seconds; 6,000+ rows can take 10-30 minutes or more depending on the model
- **Cost** — fewer input tokens means lower API costs for cloud providers
- **Context window limits** — large files may exceed the model's context window, causing errors. Sampling always fits.
- **Accuracy** — AI models can lose focus and miss violations when processing very large inputs. Smaller inputs produce more reliable results.

Sampling is especially important when using local models via Ollama, which have smaller context windows and slower inference. For cloud providers with large context windows (Anthropic, OpenAI), full-file mode works well for small-to-medium files (up to ~10,000 rows).

### Choosing the right rule type

The pipeline provides three levels of data quality validation. Each has its strengths — use the simplest tool that gets the job done.

**Column rules (`regex`)** are the best choice for format and pattern validation. They execute instantly, produce deterministic results, cost nothing, and never miss a violation. If a check can be expressed as a regular expression — email formats, phone numbers, zip codes, ticker symbols, date patterns — always use a column rule. There is no benefit to using AI for checks that have a clear structural definition.

```json
{
  "columnName": "symbol",
  "function": "regex",
  "parameter": "^[A-Z]{1,5}$",
  "onFailureIsError": true,
  "description": "Stock ticker must be 1-5 uppercase letters"
}
```

**Row rules (`javascript`, `restEndpoint`)** handle validation logic that spans multiple columns but can be expressed programmatically. For example, checking that `high >= low` or that `end_date > start_date` is straightforward in code and does not benefit from AI interpretation. These rules are fast, reliable, and free.

**AI rules (`aiRule`)** are designed for validation that requires **judgment, domain knowledge, or nuance** — things that are difficult or impossible to express as code. Reserve AI rules for checks like:

- "prices should be realistic for US equities (not negative, not exceeding $1,000,000)"
- "date must be a valid trading day (not a weekend or US market holiday)"
- "description must be professional and free of profanity"
- "address must be a plausible US mailing address"
- "the combination of product category and price tier should be consistent with market norms"

AI rules incur API costs (for cloud providers), take longer to execute, and may occasionally miss violations depending on the model. They are powerful when the validation logic is too complex or subjective for code — but should not be used as a substitute for checks that regex or row rules can handle reliably.

All three rule types can be used together on the same pipeline. Column rules and row rules run first, then the AI rule.

### Accuracy and model selection

The accuracy of `aiRule` depends directly on the AI model used. Larger models catch more violations:

| Model Type | Example | Accuracy |
|------------|---------|----------|
| Local 7-14B | `qwen2.5:14b-instruct` | Catches obvious violations; may miss subtle cross-column issues |
| Local 70B+ | `llama3.3:70b` | Near cloud quality; catches most violations |
| Cloud | Claude Sonnet, GPT-4o | Highest accuracy; catches subtle and cross-column violations |

For production workloads where data quality accuracy is critical, use a cloud provider or a 70B+ local model. For development and testing, smaller local models provide fast iteration at acceptable accuracy. See [AI Configuration](../ai-configuration.md) for model recommendations and hardware requirements.

### Requirements

- `ai.enabled: true` must be set in `application.yaml`
- The Vault secret for the AI provider must be configured (see [AI Configuration](../ai-configuration.md))
- Ollama must be running locally if using the `ollama` provider

---

## Behavior

- `regex` rules are evaluated per-row inline; `aiRule` sends the full file to the model in one call
- Rules with `onFailureIsError: true` are counted as errors; rules with `onFailureIsError: false` are counted as warnings
- Processing aborts immediately if the error count exceeds **100** (to prevent runaway logging)
- After all rows are evaluated, if **any errors** exist, processing is aborted
- Warnings are summarized in a count at the end of validation
- For `columnRules`, the `columnName` must exist in the source schema — validation fails if the column is not found
