# CodeGen Rule Evaluation Mode

## Overview

A new evaluation strategy for `DataQuality.scala` that uses an LLM to generate a Python validation script from a plain-English rule, then executes it locally via `ProcessBuilder`. This avoids embedded interpreter dependencies (Jep, GraalVM) while keeping per-row cost at zero.

## Architecture

```
┌─────────────────────┐      ┌──────────────┐      ┌─────────────────────┐
│  Rule (plain text)  │─────▶│ Anthropic API │─────▶│ Generated .py script│
│  + CSV column names │      │  (one call)   │      │  (written to /tmp)  │
└─────────────────────┘      └──────────────┘      └──────┬──────────────┘
                                                          │
                              ┌──────────────┐            │
                              │ CSV data file │────────────┤
                              └──────────────┘            │
                                                          ▼
                                                   ┌──────────────┐
                                                   │ ProcessBuilder│
                                                   │ python3 exec  │
                                                   └──────┬───────┘
                                                          │
                                                          ▼
                                                   ┌──────────────┐
                                                   │ JSON result   │
                                                   │ (failures)    │
                                                   └──────────────┘
```

## Flow

1. **Extract metadata** — Read the CSV header row to get column names and types.
2. **Build the prompt** — Combine the rule text with the column metadata into an API prompt that instructs the model to generate a self-contained Python validation script.
3. **Call Anthropic API** — Single request, ~500 tokens in, ~1,000 tokens out. Use the existing REST client infrastructure already in the pipeline server.
4. **Write script to temp file** — Save the generated Python to a temp directory with a unique filename.
5. **Execute via ProcessBuilder** — `Seq("python3", scriptPath, csvPath).!!` — captures stdout as the JSON result.
6. **Parse failures** — Deserialize the JSON array of `{index, reason}` objects back into Scala domain objects.
7. **Cleanup** — Delete the temp script file.

## Integration Point in DataQuality.scala

The existing rule evaluation modes are: `JavaScript` (Nashorn), `Python` (Jep), and `RestEndpoint`. This adds a fourth: `CodeGenerated`.

```scala
// In the rule evaluation dispatch
rule.evaluationType match {
  case "javascript"     => runJavaScriptRowRule(rule, rows)
  case "python"         => runPythonRowRule(rule, rows)
  case "rest"           => runRestEndpointRule(rule, rows)
  case "codegen"        => runCodeGenRule(rule, rows)    // NEW
}
```

## Key Components

### CodeGenRuleEvaluator.scala

Responsible for orchestrating the generate-then-execute flow.

```scala
package net.idata.pipeline.util

import scala.sys.process._
import java.nio.file.{Files, Path}

class CodeGenRuleEvaluator(apiClient: AnthropicApiClient) {

  /**
   * Generate a Python validation script from a plain-English rule.
   *
   * @param rule       The rule text, e.g. "Make sure all dates are formatted as YYYY-MM-DD"
   * @param columns    Column names from the CSV header
   * @param sampleRows Optional sample rows (3-5) to help the LLM understand data shape
   * @return           The generated Python script as a String
   */
  def generateScript(rule: String, columns: Seq[String], sampleRows: Seq[String] = Seq.empty): String = {
    // TODO: Build prompt, call API, extract script from response
    ???
  }

  /**
   * Write the script to a temp file and execute it against the CSV.
   *
   * @param script   The Python script content
   * @param csvPath  Path to the CSV file to validate
   * @return         JSON string of validation failures
   */
  def executeScript(script: String, csvPath: String): String = {
    val tempScript: Path = Files.createTempFile("dq_codegen_", ".py")
    try {
      Files.write(tempScript, script.getBytes("UTF-8"))
      val result = Seq("python3", tempScript.toString, csvPath).!!
      result.trim
    } finally {
      Files.deleteIfExists(tempScript)
    }
  }

  /**
   * Full pipeline: generate, execute, parse.
   *
   * @param rule     Plain-English validation rule
   * @param columns  CSV column names
   * @param csvPath  Path to the CSV data file
   * @return         List of ValidationFailure objects
   */
  def evaluate(rule: String, columns: Seq[String], csvPath: String): List[ValidationFailure] = {
    val script = generateScript(rule, columns)
    val json = executeScript(script, csvPath)
    // TODO: Parse JSON into List[ValidationFailure]
    ???
  }
}

case class ValidationFailure(index: Int, reason: String)
```

### AnthropicApiClient.scala

Thin wrapper around the Anthropic Messages API. May already exist or can extend the existing REST client.

```scala
package net.idata.pipeline.util

class AnthropicApiClient(apiKey: String, model: String = "claude-sonnet-4-20250514") {

  private val endpoint = "https://api.anthropic.com/v1/messages"

  /**
   * Send a prompt and return the text content of the response.
   *
   * @param systemPrompt  System-level instructions
   * @param userMessage   The user message
   * @param maxTokens     Max output tokens (default 2048)
   * @return              Response text
   */
  def complete(systemPrompt: String, userMessage: String, maxTokens: Int = 2048): String = {
    // TODO: HTTP POST to endpoint using existing Spring RestTemplate or OkHttp
    // Headers: x-api-key, anthropic-version, content-type
    // Parse response JSON, extract content[0].text
    ???
  }
}
```

### Prompt Template

The prompt sent to the API should produce a deterministic, self-contained Python script.

```
SYSTEM:
You are a code generator. Output ONLY a valid Python 3 script with no explanation,
no markdown fences, and no commentary. The script must:
- Accept a CSV file path as sys.argv[1]
- Read the CSV using the csv module (stdlib only — no pandas, no numpy)
- Validate every row against the rule provided
- Print a JSON array to stdout: [{"index": <row_number>, "reason": "..."}]
- If all rows pass, print: []
- Use 0-based row indexing (first data row = 0)
- Handle edge cases: empty values, whitespace, encoding

USER:
Columns: {comma-separated column names}
Sample rows:
{3-5 sample rows from the CSV}

Rule: "{the plain-English rule text}"
```

### Configuration (application.conf / environment)

```hocon
idata.pipeline.codegen {
  enabled = true
  anthropic-api-key = ${?ANTHROPIC_API_KEY}       # or pull from Vault/Secrets Manager
  model = "claude-sonnet-4-20250514"               # Sonnet for speed/cost balance
  max-tokens = 2048
  python-path = "python3"                          # override if needed
  script-timeout-seconds = 300                     # kill runaway scripts
  max-csv-rows-for-sample = 5                      # rows sent to the LLM for context
}
```

## Dataset Config Schema Addition

Add `codegen` as a valid `evaluationType` in the dataset configuration JSON:

```json
{
  "dataQualityRules": [
    {
      "name": "date_format_check",
      "evaluationType": "codegen",
      "rule": "Make sure all dates in the 'trade_date' column are formatted as YYYY-MM-DD",
      "severity": "error",
      "columns": ["trade_date"]
    }
  ]
}
```

## Error Handling

| Scenario | Handling |
|---|---|
| API call fails (network, auth, rate limit) | Retry with exponential backoff (3 attempts), then fail the rule with a clear error message |
| Generated script has syntax errors | Catch non-zero exit code from ProcessBuilder, log stderr, fail the rule |
| Script times out | Use `Process` with a `Future` + timeout; destroy the process if exceeded |
| Script produces invalid JSON | Catch parse exception, log raw stdout, fail the rule |
| Python not installed | Detect at startup, disable codegen mode, log warning |

```scala
// Timeout pattern
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

def executeWithTimeout(script: String, csvPath: String, timeoutSec: Int): String = {
  val process = Process(Seq("python3", scriptPath, csvPath))
  val future = Future { process.!! }
  try {
    Await.result(future, timeoutSec.seconds)
  } catch {
    case _: java.util.concurrent.TimeoutException =>
      process.destroy()
      throw new PipelineException("CodeGen script execution timed out")
  }
}
```

## Security Considerations

- **Script sandboxing** — The generated script runs with the same permissions as the pipeline process. For production, consider running inside a Docker container or with restricted filesystem access.
- **API key management** — Store the Anthropic API key in HashiCorp Vault (already integrated) or AWS Secrets Manager. Never hardcode or log.
- **Script review (optional)** — For sensitive environments, add a flag to log or cache generated scripts for audit before execution.
- **Stdlib only** — The prompt explicitly constrains the generated script to Python stdlib. No `pip install` at runtime.

## Cost Estimate

| Component | Per-Rule Cost | Notes |
|---|---|---|
| API call (Sonnet) | ~$0.003 | ~500 input tokens, ~1,000 output tokens |
| Python execution | ~$0.00 | Local CPU, sub-second for most datasets |
| **Total per rule** | **~$0.003** | vs. $25–$40 if sending all rows to the API |

## Testing Plan

1. **Unit tests** — Mock the API client, verify prompt construction and JSON parsing.
2. **Integration tests** — Use a small CSV fixture, call the real API, validate the generated script runs and produces correct failures.
3. **Rule coverage tests** — Build a test suite of common rule types:
   - Date format validation (`YYYY-MM-DD`, `MM/DD/YYYY`, etc.)
   - Numeric range checks (`column X must be between 0 and 100`)
   - Non-null / non-empty checks
   - Regex pattern matching (`email format`, `phone number format`)
   - Cross-column consistency (`if status is 'closed', close_date must not be null`)
   - Categorical checks (`column must be one of: A, B, C`)
4. **Regression** — Cache known-good generated scripts and compare outputs when the model or prompt changes.

## Dependencies

- **Existing** — Spring Boot `RestTemplate` or `WebClient` for HTTP calls, Jackson for JSON parsing, HashiCorp Vault or AWS Secrets Manager for API key storage.
- **New** — None. `ProcessBuilder` and `sys.process` are in the Scala/Java stdlib. Python 3 is assumed present on the runtime environment.

## Future Enhancements

- **Script caching** — Hash the (rule + columns) tuple and cache generated scripts. Same rule against same schema reuses the cached script without an API call.
- **Batch mode** — Generate scripts that operate on partitions in parallel for very large files.
- **Hybrid mode** — Use codegen for deterministic rules, fall back to REST endpoint evaluation for fuzzy/semantic rules that need LLM judgment per row.
- **Multi-language** — Extend to generate JavaScript (Nashorn-compatible) scripts for environments without Python.
