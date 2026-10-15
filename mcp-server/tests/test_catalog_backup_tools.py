"""Story: Catalog backup and restore: zip export/restore endpoints and MCP tools
(plans/stories/catalog-backup-restore.md) — the MCP-server half.

Pins: `export_catalog` and `restore_catalog` exist in the tool catalog,
`restore_catalog` is mutating (offers `reason`), `export_catalog` dispatches
GET /api/v1/catalog/<name>/export, and `restore_catalog` posts multipart to
POST /api/v1/catalog/<name>/restore with mode=new when no mode is given and
never sends mode=overwrite without `confirm`. Also the drift guard: the three
tool-catalog surfaces list both tools and scripts/check-mcp-tool-catalog.py
exits 0.

Modelled on test_catalog_tools.py. Both HTTP paths a handler might take are
captured: `server._call` and `requests.get/post` (which `_upload_content`
uses for multipart)."""
import base64
import importlib.util
import io
import json
import os
import subprocess
import sys
import zipfile
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


def _tool(name):
    for t in server._all_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _all_tools()")


def _props(tool):
    return tool.inputSchema["properties"]


def _zip_bytes():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("manifest.json", json.dumps({"formatVersion": 1, "catalog": "sales", "taps": ["t"], "pipelines": []}))
        z.writestr("taps/t.json", "{}")
        z.writestr("taps/t.py", "print(1)\n")
    return buf.getvalue()


class _Resp:
    def __init__(self, content, status_code=200, content_type="application/json"):
        self.content = content if isinstance(content, bytes) else content.encode("utf-8")
        self.text = self.content.decode("utf-8", errors="replace")
        self.status_code = status_code
        self.headers = {"Content-Type": content_type}
        self.ok = status_code < 400

    def json(self):
        return json.loads(self.text)

    def raise_for_status(self):
        pass

    def iter_content(self, chunk_size=8192):
        yield self.content


class _Captured:
    """Records every outbound request, whichever helper a handler uses."""

    def __init__(self, body, raw=None, content_type="application/json"):
        self.calls = []  # (method, path, kwargs)
        self.body = body
        self.raw = raw if raw is not None else body
        self.content_type = content_type

    def call(self, method, path, timeout=300, **kwargs):
        self.calls.append((method, path, kwargs))
        return self.body

    def requests_method(self, method):
        def _do(url, *args, **kwargs):
            path = url[len(server.DATRIS_API_URL):] if url.startswith(server.DATRIS_API_URL) else url
            files = kwargs.get("files")
            if files:
                # Read the multipart payload now: _upload_content deletes its temp file afterwards.
                snap = {}
                for k, v in (files.items() if isinstance(files, dict) else files):
                    if isinstance(v, tuple) and len(v) > 1 and hasattr(v[1], "read"):
                        snap[k] = (v[0], v[1].read())
                    else:
                        snap[k] = v
                kwargs = dict(kwargs, files=snap)
            self.calls.append((method, path, kwargs))
            return _Resp(self.raw, content_type=self.content_type)
        return _do


@pytest.fixture
def capture(monkeypatch):
    def _install(body=json.dumps({"ok": True}), raw=None, content_type="application/json"):
        c = _Captured(body, raw, content_type)
        monkeypatch.setattr(server, "_call", c.call)
        for m in ("get", "post", "put", "delete"):
            monkeypatch.setattr(server.requests, m, c.requests_method(m))
        return c
    return _install


def _path_only(path):
    return urlparse(path).path


def _modes(path, kwargs):
    """mode may travel as a query param, in the URL, or as a multipart form field."""
    out = []
    for src in (kwargs.get("params") or {}, kwargs.get("data") or {}):
        if isinstance(src, dict) and "mode" in src:
            out.append(str(src["mode"]))
    out.extend(parse_qs(urlparse(path).query).get("mode", []))
    return out


def _has_confirm(path, kwargs):
    for src in (kwargs.get("params") or {}, kwargs.get("data") or {}):
        if isinstance(src, dict) and src.get("confirm"):
            return True
    return bool(parse_qs(urlparse(path).query).get("confirm"))


def _restore_posts(calls):
    return [(m, p, k) for (m, p, k) in calls if _path_only(p).endswith("/restore")]


# ------------------------------------------------ tools present, mutating ---

def test_both_tools_present_in_all_tools():
    names = {t.name for t in server._all_tools()}
    assert "export_catalog" in names
    assert "restore_catalog" in names


def test_restore_is_mutating_with_reason():
    assert "restore_catalog" in server._MUTATING_TOOLS
    assert "reason" in _props(_tool("restore_catalog"))


def test_export_is_not_mutating():
    assert "export_catalog" not in server._MUTATING_TOOLS


def test_restore_catalog_schema():
    t = _tool("restore_catalog")
    props = _props(t)
    assert {"catalog", "archive_base64", "mode", "confirm"} <= set(props)
    assert set(t.inputSchema.get("required", [])) >= {"catalog", "archive_base64"}
    assert "mode" not in t.inputSchema.get("required", [])
    assert "confirm" not in t.inputSchema.get("required", [])


# ---------------------------------------------------------- export dispatch ---

def test_export_catalog_dispatches_get_export(capture):
    zb = _zip_bytes()
    c = capture(body=zb.decode("latin-1"), raw=zb, content_type="application/zip")
    server._dispatch("export_catalog", {"catalog": "sales"})
    assert c.calls, "export_catalog made no request"
    exports = [(m, p) for (m, p, _k) in c.calls if _path_only(p) == "/api/v1/catalog/sales/export"]
    assert exports, c.calls
    assert all(m == "get" for m, _p in exports), exports


def test_export_catalog_quotes_the_name(capture):
    zb = _zip_bytes()
    c = capture(body=zb.decode("latin-1"), raw=zb, content_type="application/zip")
    server._dispatch("export_catalog", {"catalog": "a/b"})
    paths = [_path_only(p) for (_m, p, _k) in c.calls]
    assert "/api/v1/catalog/a%2Fb/export" in paths, paths


# --------------------------------------------------------- restore dispatch ---

def test_restore_catalog_posts_multipart_with_mode_new_by_default(capture):
    c = capture(body=json.dumps({"restored": {"taps": ["t"], "pipelines": []}}))
    archive = base64.b64encode(_zip_bytes()).decode("ascii")
    server._dispatch("restore_catalog", {"catalog": "sales_copy", "archive_base64": archive})
    posts = _restore_posts(c.calls)
    assert len(posts) == 1, c.calls
    method, path, kwargs = posts[0]
    assert method == "post"
    assert _path_only(path) == "/api/v1/catalog/sales_copy/restore"
    files = kwargs.get("files") or {}
    assert "file" in files, f"restore must be multipart with a `file` part: {kwargs}"
    part = files["file"]
    sent = part[1] if isinstance(part, tuple) else part
    if hasattr(sent, "read"):
        sent = sent.read()
    assert sent == _zip_bytes(), "archive bytes must reach the server unchanged"
    assert _modes(path, kwargs) == ["new"], kwargs


def test_restore_catalog_never_sends_overwrite_without_confirm(capture):
    c = capture(body=json.dumps({"ok": True}))
    archive = base64.b64encode(_zip_bytes()).decode("ascii")
    out = server._dispatch("restore_catalog", {"catalog": "sales", "archive_base64": archive, "mode": "overwrite"})
    for _m, path, kwargs in _restore_posts(c.calls):
        assert "overwrite" not in _modes(path, kwargs), f"overwrite sent without confirm: {path} {kwargs}"
    if not _restore_posts(c.calls):
        assert "confirm" in str(out).lower(), out


def test_restore_catalog_overwrite_with_confirm_is_sent(capture):
    c = capture(body=json.dumps({"ok": True}))
    archive = base64.b64encode(_zip_bytes()).decode("ascii")
    server._dispatch("restore_catalog", {"catalog": "sales", "archive_base64": archive, "mode": "overwrite", "confirm": "sales"})
    posts = _restore_posts(c.calls)
    assert len(posts) == 1, c.calls
    _m, path, kwargs = posts[0]
    assert _modes(path, kwargs) == ["overwrite"]
    assert _has_confirm(path, kwargs)


def test_restore_catalog_returns_server_body_verbatim_including_207(capture):
    body = json.dumps({"restored": {"taps": ["t"], "pipelines": []}, "failed": [{"name": "p", "error": "boom"}]})
    capture(body=body)
    archive = base64.b64encode(_zip_bytes()).decode("ascii")
    assert server._dispatch("restore_catalog", {"catalog": "sales_copy", "archive_base64": archive}) == body


# ------------------------------------------------------------- drift check ---

def _load_drift_script():
    spec = importlib.util.spec_from_file_location("check_mcp_tool_catalog", ROOT / "scripts" / "check-mcp-tool-catalog.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_tool_catalog_surfaces_list_both_tools_and_drift_check_passes():
    mod = _load_drift_script()
    for surface in (mod.server_tools(), mod.routes_tools(), mod.ui_tools()):
        assert {"export_catalog", "restore_catalog"} <= surface
    r = subprocess.run([sys.executable, str(ROOT / "scripts" / "check-mcp-tool-catalog.py")], capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr


# ------------------------------------- environment echo (multi-env story 2b) ---
# plans/stories/multi-env-2b-environment-label-mcp.md: restore_catalog is a
# mutating tool whose JSON response names the environment it changed.

def _call_tool_with(monkeypatch, name, body, label):
    import asyncio
    monkeypatch.setattr(server, "_environment_label", lambda: label, raising=True)
    monkeypatch.setattr(server, "_dispatch", lambda n, a: body)
    monkeypatch.setattr(server, "_activity_record", lambda *a, **k: None)
    archive = base64.b64encode(_zip_bytes()).decode("ascii")
    return asyncio.run(server.call_tool(name, {"catalog": "sales_copy", "archive_base64": archive}))


def test_restore_catalog_response_echoes_environment(monkeypatch):
    payload = {"restored": {"taps": ["t"], "pipelines": []},
               "sourceEnvironment": "dev", "targetEnvironment": "qa",
               "warnings": ["archive was exported from `dev`; this install is `qa`"]}
    res = _call_tool_with(monkeypatch, "restore_catalog", json.dumps(payload), "qa")
    assert res.isError is False
    assert json.loads(res.content[0].text) == dict(payload, environment="qa")
    # Unset label: response unchanged.
    body = json.dumps(payload)
    res = _call_tool_with(monkeypatch, "restore_catalog", body, None)
    assert res.content[0].text == body
    # Tool description names the environment fields from story 2a.
    desc = _tool("restore_catalog").description
    assert "sourceEnvironment" in desc and "targetEnvironment" in desc


def test_restore_catalog_409_body_is_not_decorated(monkeypatch):
    body = json.dumps({"error": "catalog sales_copy already exists", "clashes": ["t"]})
    res = _call_tool_with(monkeypatch, "restore_catalog", body, "qa")
    assert res.isError is True
    assert res.content[0].text == body
