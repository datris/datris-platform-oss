# AI Transformation

AI transformations apply natural language instructions to transform data using an AI model. Instead of writing JavaScript, describe the transformation in plain English and the AI model applies it to every row.

## Configuration

Add an `aiTransformation` block to the `transformation` section of the pipeline configuration:

```json
"transformation": {
    "aiTransformation": {
        "instruction": "convert all date values from YYYY-MM-DD format to MM/DD/YYYY format"
    }
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `instruction` | string | | A natural language description of the transformation to apply to every row. |
| `sample` | boolean | `false` | If `true`, only transforms a random sample of rows. The rest are passed through unchanged. |
| `sampleSize` | int | `200` | Number of rows to transform when `sample` is `true`. |

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

**Unit conversion:**
```json
"aiTransformation": {
    "instruction": "convert the temperature column from Fahrenheit to Celsius, rounded to 1 decimal place"
}
```

## How it works

The pipeline sends the file content (header and all rows) to the AI model in a single call with the transformation instruction. The model returns the transformed rows in the same CSV format with the same delimiter. The transformed rows replace the original data and continue through the pipeline to the configured destinations.

AI transformations run after deduplication and JavaScript row functions, and before data is written to destinations.

## Sampling mode

For large files, enable sampling to transform only a subset of rows. Sampled rows are transformed by the AI; the remaining rows are passed through unchanged.

```json
"aiTransformation": {
    "instruction": "categorize each product into one of: Electronics, Clothing, Food, Other",
    "sample": true,
    "sampleSize": 500
}
```

This is useful for testing transformations on a subset before applying to the full pipeline, or when only a sample needs enrichment.

## Choosing the right transformation type

| Type | Best For | Speed | Cost |
|------|----------|-------|------|
| **Column trimming** | Removing whitespace | Instant | Free |
| **Deduplication** | Removing duplicate rows | Instant | Free |
| **JavaScript row functions** | Deterministic logic: math, string ops, conditionals | Instant | Free |
| **AI transformation** | Fuzzy/subjective tasks: categorization, format standardization, entity extraction, enrichment | Seconds | API cost |

Use AI transformations for tasks that are difficult to express in code — categorization, natural language processing, entity extraction, or format conversions across many possible input formats. For simple, deterministic transformations (math, string concatenation, conditionals), JavaScript row functions are faster, free, and more reliable.

## Requirements

- `ai.enabled: true` must be set in `application.yaml`
- The Vault secret for the AI provider must be configured (see [AI Configuration](../ai-configuration.md))
- Cloud providers (Anthropic, OpenAI) are recommended for accuracy. Local models via Ollama work for simple transformations.
