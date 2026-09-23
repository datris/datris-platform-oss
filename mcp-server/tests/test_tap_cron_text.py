"""Story: tap cron validation (plans/stories/tap-cron-validation.md), the
prose half (story Step 5).

A 5-field Unix cron (`*/1 * * * *`) is the single most common malformed
schedule an agent sends: every cron resource online shows 5 fields, Datris
schedules are Quartz and take 6 (or 7). The platform now refuses such a save
with HTTP 400 whose `error` carries the 6-field form to resend, so the
agent-facing text must (a) state the 6-field format with the leading SECONDS
field in BOTH server.py cron sections, (b) tell `create_tap` and `update_tap`
callers that a 5-field cron comes back as a 400 they can fix from the error,
and (c) say the same in docs/taps.mdx with a 6-field example.

Pure file-content checks, modelled on test_tap_memory_text.py. Sections are
cut out of server.py by anchor so a match in unrelated prose (or in server.py's
own Python code) can never satisfy a check meant for a specific section.
"""
import os
import re

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SERVER_PY = os.path.join(REPO_ROOT, "mcp-server", "server.py")
ASSISTANT_SCALA = os.path.join(
    REPO_ROOT, "datrisserver", "src", "main", "scala", "ai", "datris", "api", "AssistantAPIController.scala"
)
TAPS_MDX = os.path.join(REPO_ROOT, "docs", "taps.mdx")

# "6 fields" / "6-field", however it is written.
_SIX_FIELDS = re.compile(r"\b(6|six)[ -]?(fields?|field)\b", re.IGNORECASE)
_SECONDS = re.compile(r"\bseconds\b", re.IGNORECASE)
# The refusal: a 5-field Unix cron is rejected with HTTP 400.
_FIVE_FIELD = re.compile(r"\b(5|five)[ -]?(field|fields)\b", re.IGNORECASE)
_HTTP_400 = re.compile(r"\b400\b")


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _between(text, start_anchor, end_anchor, start_from=0):
    """The text from `start_anchor` up to the next `end_anchor` (or EOF when the
    section is the file's last one)."""
    i = text.index(start_anchor, start_from)
    j = text.find(end_anchor, i + len(start_anchor))
    return text[i:] if j < 0 else text[i:j]


def _instructions_scheduling_rule():
    """The SCHEDULING RULE paragraph inside the server instructions string."""
    text = _read(SERVER_PY)
    return _between(text, "SCHEDULING RULE (read this before suggesting", "VALIDATION RULE (read this before the first run")


def _reference_scheduling_rule():
    """The SCHEDULING RULE + CRON cookbook inside TAP_WORKFLOW_REFERENCE."""
    text = _read(SERVER_PY)
    return _between(text, "## SCHEDULING RULE (read this every time)", "## VALIDATION RULE")


def _cron_expression_descriptions():
    """Every `cron_expression` property description in the tool schemas."""
    text = _read(SERVER_PY)
    out = []
    for m in re.finditer(r'"cron_expression"\s*:\s*\{(.*?)\n\s*\}', text, re.DOTALL):
        out.append(m.group(1))
    return out


# ------------------------------------------------------------ server.py ---

def test_both_server_cron_sections_state_the_six_field_quartz_format():
    for label, section in (
        ("the instructions' SCHEDULING RULE", _instructions_scheduling_rule()),
        ("the tap-workflow-reference SCHEDULING RULE / cookbook", _reference_scheduling_rule()),
    ):
        assert _SIX_FIELDS.search(section), label + " must say a Datris cron has 6 fields"
        assert _SECONDS.search(section), label + " must name the leading seconds field"
        assert "0 0 * * * ?" in section, label + " must carry a concrete 6-field example"


def test_both_server_cron_sections_state_the_five_field_refusal():
    for label, section in (
        ("the instructions' SCHEDULING RULE", _instructions_scheduling_rule()),
        ("the tap-workflow-reference SCHEDULING RULE / cookbook", _reference_scheduling_rule()),
    ):
        assert _FIVE_FIELD.search(section), (
            label + " must warn that a 5-field Unix cron is not a Datris schedule"
        )
        assert _HTTP_400.search(section), (
            label + " must say a malformed cron is refused with HTTP 400 (not silently stored)"
        )


def test_create_tap_and_update_tap_cron_descriptions_state_the_five_field_refusal():
    descriptions = _cron_expression_descriptions()
    assert len(descriptions) >= 2, (
        "expected a cron_expression description on both create_tap and update_tap, found %d" % len(descriptions)
    )
    for desc in descriptions:
        assert _SIX_FIELDS.search(desc), "a cron_expression description must state the 6-field Quartz format: " + desc
        assert _FIVE_FIELD.search(desc), "a cron_expression description must name the 5-field Unix form: " + desc
        assert _HTTP_400.search(desc), (
            "a cron_expression description must say a 5-field cron is refused with HTTP 400 whose error carries the "
            "6-field form to resend: " + desc
        )


# --------------------------------------------------- Assistant prompt ---

def test_assistant_prompt_states_the_quartz_format_and_the_400():
    text = _read(ASSISTANT_SCALA)
    assert "Scheduled → ask, don't auto-run" in text, "the scheduled-tap bullet must stay"
    lines = text.splitlines()
    idx = next(i for i, l in enumerate(lines) if "Scheduled → ask, don't auto-run" in l)
    window = "\n".join(lines[idx:idx + 6])
    assert _SIX_FIELDS.search(window) and _HTTP_400.search(window), (
        "the Assistant prompt must say schedules are 6-field Quartz and that a 400 on save quotes the fix to resend:\n"
        + window
    )


# ------------------------------------------------------------------ docs ---

def test_taps_doc_cron_section_states_the_400_and_a_six_field_example():
    text = _read(TAPS_MDX)
    section = _between(text, "### CRON Schedule", "\n### ")
    assert _SIX_FIELDS.search(section), "docs/taps.mdx CRON Schedule must state the 6-field Quartz format"
    assert _SECONDS.search(section), "docs/taps.mdx CRON Schedule must name the leading seconds field"
    assert re.search(r"`0 [^`\n]*\?`", section), "docs/taps.mdx CRON Schedule must show a 6-field example expression"
    assert _HTTP_400.search(section), "docs/taps.mdx CRON Schedule must say a malformed cron is refused with HTTP 400"
    assert _FIVE_FIELD.search(section), "docs/taps.mdx CRON Schedule must call out the 5-field Unix form"


def test_taps_doc_wizard_schedule_step_mentions_the_malformed_cron_refusal():
    text = _read(TAPS_MDX)
    section = _between(text, "### Step 3: Schedule", "\n### Step 4")
    assert _HTTP_400.search(section), (
        "docs/taps.mdx Step 3 Schedule must say a malformed CRON expression is refused with HTTP 400"
    )
    assert _SIX_FIELDS.search(section), "docs/taps.mdx Step 3 Schedule must state the 6-field Quartz format"


def test_taps_doc_keeps_the_existing_409_gate_wording():
    # Out of scope: the test-before-cron 409 text does not move.
    text = _read(TAPS_MDX)
    assert "HTTP 409" in text, "the test-before-cron gate wording must stay"
