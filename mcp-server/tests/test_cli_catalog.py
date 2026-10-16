"""`datris catalog export` / `datris catalog restore` (story: Catalog backup
and restore: CLI commands, Catalog-tab controls, docs).

Both commands go through the `mcp()` helper (stubbed here) to the MCP tools
`export_catalog` and `restore_catalog` shipped in Story 1."""
import base64
import os
import sys

from click.testing import CliRunner

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import cli  # noqa: E402

ZIP_BYTES = b"PK\x03\x04fake-catalog-archive\x00\x01\x02\xff"

EXPORT_RESULT = {
    "manifest": {
        "catalog": "sales",
        "taps": ["t_orders"],
        "pipelines": ["p_orders", "p_refunds"],
        "requiredSecrets": [{"name": "stripe", "usedBy": ["t_orders"]}],
        "warnings": [],
    },
    "archiveBase64": base64.b64encode(ZIP_BYTES).decode("ascii"),
    "sizeBytes": len(ZIP_BYTES),
}

RESTORE_OK = {
    "restored": {"taps": ["t_orders"], "pipelines": ["p_orders", "p_refunds"]},
    "skipped": [],
    "failed": [],
    "missingSecrets": [],
    "warnings": [],
    "tapsDisabled": True,
    "stateRestored": True,
}


class _Mcp:
    def __init__(self, result):
        self.calls = []
        self.result = result

    def __call__(self, name, args=None):
        self.calls.append((name, dict(args or {})))
        return self.result


def _run(argv, input=None):
    return CliRunner().invoke(cli.cli, argv, input=input, catch_exceptions=False)


def _archive(tmp_path):
    p = tmp_path / "s.zip"
    p.write_bytes(ZIP_BYTES)
    return p


# ── Acceptance bullet 1 ────────────────────────────────────────────────


def test_export_calls_export_catalog_and_writes_decoded_bytes(monkeypatch, tmp_path):
    stub = _Mcp(EXPORT_RESULT)
    monkeypatch.setattr(cli, "mcp", stub)
    out = tmp_path / "s.zip"
    res = _run(["catalog", "export", "sales", "--out", str(out)])
    assert res.exit_code == 0, res.output
    assert stub.calls == [("export_catalog", {"catalog": "sales"})]
    assert out.read_bytes() == ZIP_BYTES


def test_restore_as_calls_restore_catalog_mode_new_with_file_base64(monkeypatch, tmp_path):
    stub = _Mcp(RESTORE_OK)
    monkeypatch.setattr(cli, "mcp", stub)
    archive = _archive(tmp_path)
    res = _run(["catalog", "restore", str(archive), "--as", "sales2"])
    assert res.exit_code == 0, res.output
    assert len(stub.calls) == 1, stub.calls
    name, args = stub.calls[0]
    assert name == "restore_catalog"
    assert args.get("catalog") == "sales2"
    assert args.get("mode") == "new"
    assert args.get("archive_base64") == base64.b64encode(ZIP_BYTES).decode("ascii")
    assert args.get("archive_base64") == cli.b64_file(str(archive))


def test_restore_overwrite_without_yes_prompts_and_aborts_on_wrong_name(monkeypatch, tmp_path):
    stub = _Mcp(RESTORE_OK)
    monkeypatch.setattr(cli, "mcp", stub)
    archive = _archive(tmp_path)
    res = _run(["catalog", "restore", str(archive), "--as", "sales2", "--overwrite"], input="sales3\n")
    assert "Type the catalog name to confirm" in res.output
    assert res.exit_code != 0, res.output
    assert stub.calls == [], "a wrong confirmation must not call restore_catalog"


def test_restore_overwrite_with_correct_name_sends_mode_overwrite_and_confirm(monkeypatch, tmp_path):
    stub = _Mcp(RESTORE_OK)
    monkeypatch.setattr(cli, "mcp", stub)
    archive = _archive(tmp_path)
    res = _run(["catalog", "restore", str(archive), "--as", "sales2", "--overwrite"], input="sales2\n")
    assert res.exit_code == 0, res.output
    assert len(stub.calls) == 1, stub.calls
    name, args = stub.calls[0]
    assert name == "restore_catalog"
    assert args.get("mode") == "overwrite"
    assert args.get("confirm") == "sales2"


def test_restore_with_failed_items_exits_1_and_prints_them(monkeypatch, tmp_path):
    result = dict(RESTORE_OK)
    result["restored"] = {"taps": [], "pipelines": ["p_orders", "p_refunds"]}
    result["failed"] = [{"name": "t_orders", "error": "script write failed"}]
    monkeypatch.setattr(cli, "mcp", _Mcp(result))
    archive = _archive(tmp_path)
    res = _run(["catalog", "restore", str(archive), "--as", "sales2"])
    assert res.exit_code == 1, res.output
    assert "t_orders" in res.output
    assert "script write failed" in res.output


def test_restore_error_exits_1_with_the_message(monkeypatch, tmp_path):
    monkeypatch.setattr(cli, "mcp", _Mcp({"error": "archive is not a catalog export"}))
    archive = _archive(tmp_path)
    res = _run(["catalog", "restore", str(archive), "--as", "sales2"])
    assert res.exit_code == 1, res.output
    assert "archive is not a catalog export" in res.output


def test_export_error_exits_1_with_the_message(monkeypatch, tmp_path):
    monkeypatch.setattr(cli, "mcp", _Mcp({"error": "Catalog 'nope' not found"}))
    out = tmp_path / "nope.zip"
    res = _run(["catalog", "export", "nope", "--out", str(out)])
    assert res.exit_code == 1, res.output
    assert "Catalog 'nope' not found" in res.output
    assert not out.exists(), "no archive may be written on error"


def test_restore_409_clashes_exit_1_and_print_each(monkeypatch, tmp_path):
    monkeypatch.setattr(cli, "mcp", _Mcp({
        "error": "catalog 'sales2' already exists",
        "clashes": ["tap 't_orders' already exists", "pipeline 'p_orders' already exists"],
    }))
    archive = _archive(tmp_path)
    res = _run(["catalog", "restore", str(archive), "--as", "sales2"])
    assert res.exit_code == 1, res.output
    assert "tap 't_orders' already exists" in res.output
    assert "pipeline 'p_orders' already exists" in res.output


# ── Acceptance bullet 2 ────────────────────────────────────────────────


def test_restore_no_state_passes_restore_state_false(monkeypatch, tmp_path):
    stub = _Mcp(RESTORE_OK)
    monkeypatch.setattr(cli, "mcp", stub)
    archive = _archive(tmp_path)
    res = _run(["catalog", "restore", str(archive), "--as", "sales2", "--no-state"])
    assert res.exit_code == 0, res.output
    assert len(stub.calls) == 1, stub.calls
    name, args = stub.calls[0]
    assert name == "restore_catalog"
    assert "restore_state" in args and args["restore_state"] is False


def test_restore_without_no_state_omits_restore_state_or_sends_true(monkeypatch, tmp_path):
    stub = _Mcp(RESTORE_OK)
    monkeypatch.setattr(cli, "mcp", stub)
    archive = _archive(tmp_path)
    res = _run(["catalog", "restore", str(archive), "--as", "sales2"])
    assert res.exit_code == 0, res.output
    assert len(stub.calls) == 1, stub.calls
    _, args = stub.calls[0]
    assert args.get("restore_state", True) is True


# ── Restore honours archived CodeGen scripts (multi-env story 3) ────────


def test_restore_regenerate_scripts_passes_regenerate_scripts_true(monkeypatch, tmp_path):
    stub = _Mcp(RESTORE_OK)
    monkeypatch.setattr(cli, "mcp", stub)
    archive = _archive(tmp_path)
    res = _run(["catalog", "restore", str(archive), "--as", "sales2", "--regenerate-scripts"])
    assert res.exit_code == 0, res.output
    assert len(stub.calls) == 1, stub.calls
    name, args = stub.calls[0]
    assert name == "restore_catalog"
    assert args.get("regenerate_scripts") is True, args


def test_restore_without_regenerate_scripts_omits_the_key(monkeypatch, tmp_path):
    stub = _Mcp(RESTORE_OK)
    monkeypatch.setattr(cli, "mcp", stub)
    archive = _archive(tmp_path)
    res = _run(["catalog", "restore", str(archive), "--as", "sales2"])
    assert res.exit_code == 0, res.output
    _, args = stub.calls[0]
    assert "regenerate_scripts" not in args, args


def test_restore_prints_imported_and_regenerated_ai_script_counts(monkeypatch, tmp_path):
    result = dict(RESTORE_OK)
    result["scriptsImported"] = ["p_orders/dataQuality", "p_orders/transformation"]
    result["scriptsRegenerated"] = [
        {"item": "p_refunds/dataQuality", "reason": "no script record in the archive; re-export to carry it"}
    ]
    stub = _Mcp(result)
    monkeypatch.setattr(cli, "mcp", stub)
    archive = _archive(tmp_path)
    res = _run(["catalog", "restore", str(archive), "--as", "sales2"])
    assert res.exit_code == 0, res.output
    assert "Imported 2 AI scripts" in res.output, res.output
    assert "Regenerated 1 AI script" in res.output, res.output
    assert "no script record in the archive; re-export to carry it" in res.output, res.output
