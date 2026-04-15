"""
main.py

FastAPI application.  Two SSE streams power the browser UI:

  GET  /stream/state   — broadcasts pipeline + activity updates
                         (one persistent connection per browser tab)
  POST /chat           — accepts a message, runs the agentic loop,
                         streams events back via SSE

  GET  /               — serves the single-page browser UI
  GET  /health         — liveness check

Run:
    uvicorn main:app --reload --port 8001

The browser then opens http://localhost:8001
"""

import json
import os
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from sse_starlette.sse import EventSourceResponse

load_dotenv()

from agent.config import SUGGESTIONS, STATUS_COLORS, ACTIVITY_COLORS
from agent.loop import run as agent_run
from agent.pipeline_store import store


# ── Session store (in-memory; swap for Redis in production) ──────────────────
_sessions: dict[str, list[dict]] = {}


# ── App ───────────────────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(_app: FastAPI):
    from agent.mcp_client import connect, disconnect, is_connected
    from agent.scheduler import start_scheduler, stop_scheduler

    mcp_url = os.environ.get("MCP_SERVER_URL", "http://localhost:3000/sse")
    port = os.environ.get("PORT", "8001")

    print(
        f"[datris] API key: {'✓ loaded' if os.environ.get('ANTHROPIC_API_KEY') else '✗ missing'}\n"
        f"[datris] MCP server: {mcp_url}"
    )

    try:
        await connect(mcp_url)
        print(f"[datris] MCP: ✓ connected")
    except Exception as e:
        print(f"[datris] MCP: ✗ failed to connect — {e}")
        print("[datris] Agent will run without MCP (tools unavailable)")

    if is_connected():
        start_scheduler()

    print(f"[datris] Open http://localhost:{port} in your browser")

    yield

    stop_scheduler()
    await disconnect()


app = FastAPI(title="Datris Market Intelligence Agent", lifespan=lifespan)

# Serve anything in /static (CSS, favicons, etc.) if needed
app.mount("/static", StaticFiles(directory="static"), name="static")


# ── Health ────────────────────────────────────────────────────────────────────

@app.get("/health")
async def health():
    from agent.mcp_client import is_connected
    return {
        "status": "ok",
        "model": os.environ.get("MODEL", "claude-sonnet-4-20250514"),
        "mcp_connected": is_connected(),
    }


# ── Pipeline state SSE ────────────────────────────────────────────────────────
# The browser opens this once and receives all pipeline / activity deltas.

@app.get("/stream/state")
async def stream_state(request: Request):
    async def generator():
        # Send initial snapshot so the browser can paint immediately
        snap = await store.snapshot()
        yield {"event": "snapshot", "data": json.dumps(snap)}

        q = store.subscribe()
        try:
            while True:
                if await request.is_disconnected():
                    break
                msg = await q.get()
                yield {"event": msg["event"], "data": json.dumps(msg["data"])}
        finally:
            store.unsubscribe(q)

    return EventSourceResponse(generator())


# ── Chat SSE ──────────────────────────────────────────────────────────────────

@app.post("/chat")
async def chat(request: Request):
    body = await request.json()
    session_id: str = body.get("session_id", "default")
    user_text:  str = body.get("message", "").strip()
    if not user_text:
        return JSONResponse({"error": "message required"}, status_code=400)

    history = _sessions.get(session_id, [])

    async def generator():
        nonlocal history
        async for event in agent_run(user_text, history):
            if event["event"] == "history":
                # Persist updated history server-side; don't send to browser
                history = event["data"]["history"]
                _sessions[session_id] = history
                continue
            yield {"event": event["event"], "data": json.dumps(event["data"])}

    return EventSourceResponse(generator())


# ── Browser UI ────────────────────────────────────────────────────────────────

@app.get("/", response_class=HTMLResponse)
async def index():
    suggestions_json = json.dumps(SUGGESTIONS)
    status_colors_json = json.dumps(STATUS_COLORS)
    activity_colors_json = json.dumps(ACTIVITY_COLORS)
    return HTMLResponse(_build_ui(suggestions_json, status_colors_json, activity_colors_json))


def _build_ui(suggestions_json: str, status_colors_json: str, activity_colors_json: str) -> str:
    """
    Returns the complete single-page HTML application.
    Keeping the UI in Python means zero build tooling — open the URL and go.

    For a larger project, move this to static/index.html and serve it directly.
    """
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<title>Datris — Market Intelligence</title>
<style>
  *, *::before, *::after {{ box-sizing: border-box; margin: 0; padding: 0; }}
  html, body {{ height: 100%; background: #07090c; overflow: hidden; }}

  :root {{
    --font: 'SF Mono','Fira Code','Cascadia Code',Consolas,monospace;
    --bg:   #07090c;
    --bg2:  #0a0c10;
    --bg3:  #0e1016;
    --border: #1e2530;
    --border2: #161c24;
    --text:   #edeae2;
    --text2:  #dcd8d0;
    --muted:  #908a7e;
    --dim:    #6a7080;
    --dim2:   #555a68;
    --amber:  #f5a623;
    --green:  #2ed99a;
    --blue:   #809af8;
  }}

  @keyframes pulse  {{ 0%,100%{{opacity:1}} 50%{{opacity:.35}} }}
  @keyframes blink  {{ 0%,100%{{opacity:1}} 50%{{opacity:.15}} }}
  @keyframes fadein {{ from{{opacity:0;transform:translateY(4px)}} to{{opacity:1;transform:none}} }}

  body {{ font-family: var(--font); font-size: 12.5px; color: var(--text); display: flex; flex-direction: column; height: 100vh; }}

  /* ── Header ── */
  #header {{
    background: var(--bg2); border-bottom: 1px solid var(--border);
    padding: 9px 18px; display: flex; align-items: center; gap: 20px; flex-shrink: 0;
  }}
  .brand-primary   {{ color: var(--amber); font-weight: 700; letter-spacing: .13em; font-size: 11px; }}
  .brand-slash     {{ color: #2a2d35; font-size: 11px; }}
  .brand-secondary {{ color: var(--dim); font-size: 11px; letter-spacing: .06em; }}
  .status-dot      {{ width: 7px; height: 7px; border-radius: 50%; background: var(--green); box-shadow: 0 0 5px var(--green); }}
  #header-right    {{ display: flex; gap: 18px; margin-left: auto; font-size: 11px; align-items: center; }}
  .stat            {{ color: var(--muted); }}
  .stat span       {{ font-weight: 500; color: var(--text2); }}
  .badge           {{ font-size: 10px; letter-spacing: .08em; color: var(--amber); animation: pulse 1s infinite; }}

  /* ── Main ── */
  #main {{ display: flex; flex: 1; overflow: hidden; }}

  /* ── Chat panel ── */
  #chat-panel {{
    width: 46%; border-right: 1px solid var(--border);
    display: flex; flex-direction: column; overflow: hidden;
  }}
  .panel-label {{
    padding: 6px 14px; border-bottom: 1px solid var(--border2);
    font-size: 9.5px; color: var(--dim2); letter-spacing: .1em;
    display: flex; justify-content: space-between; flex-shrink: 0;
  }}
  #chat-scroll {{
    flex: 1; overflow-y: auto; padding: 12px 14px;
    display: flex; flex-direction: column; gap: 10px;
  }}
  .msg-row {{ animation: fadein .2s ease; display: flex; flex-direction: column; gap: 3px; }}
  .msg-row.user  {{ align-items: flex-end; }}
  .msg-row.agent {{ align-items: flex-start; }}
  .msg-label {{ font-size: 9.5px; color: var(--muted); letter-spacing: .08em; }}
  .bubble {{
    padding: 9px 12px; line-height: 1.65; font-size: 12.5px;
    white-space: pre-wrap; word-break: break-word; border-radius: 8px;
    max-width: 95%;
  }}
  .bubble.user  {{ background: #0e1525; border: 1px solid #1e2d42; border-radius: 8px 8px 2px 8px; max-width: 80%; }}
  .bubble.agent {{ background: var(--bg3); border: 1px solid var(--border); border-radius: 8px 8px 8px 2px; color: var(--text2); }}
  .bubble.partial {{ color: #9a9890; }}
  .bubble.error   {{ border-color: #2a1010; color: #e05252; }}
  .tool-chip {{
    background: #0c0e06; border: 1px solid #1e2a10; border-radius: 6px;
    padding: 7px 11px; display: flex; align-items: center; gap: 8px; animation: fadein .15s ease;
  }}
  .tool-dot  {{ width: 6px; height: 6px; border-radius: 50%; background: var(--amber); flex-shrink: 0; animation: blink .7s infinite; }}
  .tool-name {{ color: var(--amber); font-size: 11px; }}
  .tool-args {{ color: var(--muted); font-size: 10.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }}
  .thinking  {{ display: flex; gap: 5px; padding: 6px 2px; align-items: center; }}
  .dot       {{ width: 5px; height: 5px; border-radius: 50%; background: var(--amber); }}

  /* ── Suggestions + input ── */
  #suggestions {{
    padding: 6px 14px 0; flex-shrink: 0;
  }}
  #suggestion-select {{
    width: 100%; background: var(--bg3); border: 1px solid #2a2e38; border-radius: 4px;
    padding: 6px 10px; color: var(--muted); font-family: var(--font); font-size: 11px;
    cursor: pointer; appearance: none;
    background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6'%3E%3Cpath d='M0 0l5 6 5-6z' fill='%236a7080'/%3E%3C/svg%3E");
    background-repeat: no-repeat; background-position: right 10px center;
  }}
  #suggestion-select:hover {{ border-color: var(--amber); color: var(--text2); }}
  #suggestion-select:focus {{ outline: none; border-color: var(--amber); }}
  #suggestion-select option {{ background: var(--bg2); color: var(--text2); }}
  #input-row {{ padding: 10px 14px; border-top: 1px solid var(--border); display: flex; gap: 7px; }}
  #msg-input {{
    flex: 1; background: var(--bg3); border: 1px solid #2a2e38; border-radius: 4px;
    padding: 7px 11px; color: var(--text); font-family: var(--font); font-size: 12.5px;
  }}
  #msg-input:focus {{ outline: none; border-color: #3a4558; }}
  #send-btn {{
    border: none; border-radius: 4px; padding: 7px 14px;
    font-family: var(--font); font-size: 10.5px; font-weight: 700;
    letter-spacing: .1em; transition: background .15s; cursor: pointer;
    background: var(--amber); color: var(--bg);
  }}
  #send-btn:disabled {{ background: #1a1e26; color: var(--dim); cursor: not-allowed; }}
  /* ── Status panel ── */
  #status-panel {{ flex: 1; display: flex; flex-direction: column; overflow: hidden; }}
  #pipeline-grid {{
    display: grid; grid-template-columns: 1fr 1fr;
    gap: 7px; padding: 10px 14px; border-bottom: 1px solid var(--border);
  }}
  .tile {{
    background: var(--bg3); border: 1px solid var(--border);
    border-radius: 6px; padding: 9px 11px; transition: border-color .4s;
  }}
  .tile.ingesting {{ border-color: #2a1f08; }}
  .tile-header {{ display: flex; justify-content: space-between; align-items: center; margin-bottom: 3px; }}
  .tile-name   {{ color: #e0dcd4; font-size: 11.5px; font-weight: 500; }}
  .tile-status {{ display: flex; align-items: center; gap: 4px; font-size: 9.5px; letter-spacing: .07em; }}
  .tile-dot    {{ width: 5.5px; height: 5.5px; border-radius: 50%; flex-shrink: 0; }}
  .tile-dot.ingesting {{ animation: pulse .9s infinite; }}
  .tile-meta   {{ display: flex; justify-content: space-between; font-size: 10.5px; color: var(--dim); }}
  .tile-source {{ color: #7a8090; }}
  .tile-rows   {{ color: var(--muted); }}
  .tile-time   {{ font-size: 10px; color: var(--dim2); margin-top: 1px; }}

  #activity-scroll {{
    flex: 1; overflow-y: auto; padding: 8px 14px;
    display: flex; flex-direction: column; gap: 2px;
  }}
  .act-row {{ display: flex; gap: 10px; align-items: baseline; font-size: 11.5px; animation: fadein .15s ease; }}
  .act-time {{ color: var(--dim); flex-shrink: 0; font-size: 10px; font-variant-numeric: tabular-nums; }}
  .act-type {{ flex-shrink: 0; font-size: 9.5px; letter-spacing: .07em; text-transform: uppercase; min-width: 46px; }}
  .act-msg  {{ color: var(--muted); line-height: 1.45; }}

  #metrics-footer {{
    border-top: 1px solid var(--border); padding: 7px 14px;
    display: flex; gap: 18px; font-size: 10.5px; align-items: center; flex-shrink: 0;
  }}
  .engine-label {{ margin-left: auto; color: var(--dim); font-size: 10px; letter-spacing: .04em; }}


  ::-webkit-scrollbar       {{ width: 3px; height: 3px; }}
  ::-webkit-scrollbar-track {{ background: transparent; }}
  ::-webkit-scrollbar-thumb {{ background: #2e3240; border-radius: 2px; }}
</style>
</head>
<body>

<!-- ── Header ── -->
<div id="header">
  <div style="display:flex;align-items:center;gap:8px">
    <div class="status-dot"></div>
    <span class="brand-primary">DATRIS</span>
    <span class="brand-slash">/</span>
    <span class="brand-secondary">MARKET INTELLIGENCE</span>
  </div>
  <div id="header-right">
    <span class="stat">PIPELINES <span id="h-pipelines" style="color:#444">0/4</span></span>
    <span class="stat">ROWS <span id="h-rows" style="color:#dbd8d0">0</span></span>
    <span class="stat">API <span id="h-api" style="color:#dbd8d0">0 calls</span></span>
    <span class="badge" id="h-badge" style="display:none"></span>
  </div>
</div>

<!-- ── Main ── -->
<div id="main">

  <!-- ── Chat panel ── -->
  <div id="chat-panel">
    <div class="panel-label">AGENT CHAT</div>
    <div id="chat-scroll">
      <div class="msg-row agent">
        <span class="msg-label">DATRIS AGENT</span>
        <div class="bubble agent">Market Intelligence Agent online. I manage FRED macro feeds, equity ETFs, crypto prices, and SEC filings.

Ask me anything about current conditions — I'll check pipeline health, ingest fresh data if needed, and ground my answer in actual numbers.</div>
      </div>
    </div>

    <div id="suggestions">
      <select id="suggestion-select">
        <option value="">Suggested questions...</option>
      </select>
    </div>

    <div id="input-row">
      <input id="msg-input" type="text" placeholder="Ask about market conditions..." autocomplete="off"/>
      <button id="send-btn">SEND</button>
    </div>
  </div>

  <!-- ── Status panel ── -->
  <div id="status-panel">
    <div class="panel-label" id="pl-label">PIPELINE STATUS <span></span></div>
    <div id="pipeline-grid"></div>

    <div class="panel-label" id="act-label">LIVE ACTIVITY <span></span></div>
    <div id="activity-scroll"></div>

    <div id="metrics-footer">
      <span class="stat">TOTAL ROWS <span id="m-rows" style="color:#dbd8d0">0</span></span>
      <span class="stat">ACTIVE <span id="m-active" style="color:#22c98b">0 pipelines</span></span>
      <span class="stat">PENDING <span id="m-pending" style="color:#2e3240">0 jobs</span></span>
      <span class="engine-label">DATRIS PIPELINE ENGINE v0.9</span>
    </div>
  </div>

</div>

<script>
// ── Config from server ────────────────────────────────────────────────────────
const SUGGESTIONS    = {suggestions_json};
const STATUS_COLORS  = {status_colors_json};
const ACT_COLORS     = {activity_colors_json};

// ── State ─────────────────────────────────────────────────────────────────────
let pipelines  = {{}};
let totalRows  = 0;
let apiCalls   = 0;
let actCount   = 0;
let loading    = false;
let activeTools = {{}};   // id → chip element

// ── DOM refs ──────────────────────────────────────────────────────────────────
const chatScroll   = document.getElementById('chat-scroll');
const inputEl      = document.getElementById('msg-input');
const sendBtn      = document.getElementById('send-btn');
const pipelineGrid = document.getElementById('pipeline-grid');
const actScroll    = document.getElementById('activity-scroll');

// ── Suggestion dropdown ──────────────────────────────────────────────────────
const suggSelect = document.getElementById('suggestion-select');
SUGGESTIONS.forEach(q => {{
  const opt = document.createElement('option');
  opt.value = q; opt.textContent = q;
  suggSelect.appendChild(opt);
}});
suggSelect.addEventListener('change', () => {{
  if (suggSelect.value) {{
    inputEl.value = suggSelect.value;
    suggSelect.value = '';
    inputEl.focus();
  }}
}});

// ── Pipeline tile rendering ───────────────────────────────────────────────────
function renderPipelines() {{
  const entries = Object.entries(pipelines);
  pipelineGrid.innerHTML = '';
  let active = 0, ingesting = 0;

  entries.forEach(([, p]) => {{
    if (p.status === 'ready')     active++;
    if (p.status === 'ingesting') ingesting++;

    const dot   = STATUS_COLORS[p.status] || '#444';
    const label = p.status.toUpperCase();
    const tile  = document.createElement('div');
    tile.className = 'tile' + (p.status === 'ingesting' ? ' ingesting' : '');
    tile.innerHTML = `
      <div class="tile-header">
        <span class="tile-name">${{p.name}}</span>
        <span class="tile-status" style="color:${{dot}}">
          <span class="tile-dot${{p.status==='ingesting'?' ingesting':''}}"
                style="background:${{dot}};${{p.status==='ingesting'?'box-shadow:0 0 5px '+dot:''}}"></span>
          ${{label}}
        </span>
      </div>
      <div class="tile-meta">
        <span class="tile-source">${{p.source}}</span>
        <span class="tile-rows">${{p.rows > 0 ? p.rows.toLocaleString() + ' rows' : '—'}}</span>
      </div>
      ${{p.last_run ? `<div class="tile-time">last run ${{p.last_run}}</div>` : ''}}
    `;
    pipelineGrid.appendChild(tile);
  }});

  // Header stats
  const hPl = document.getElementById('h-pipelines');
  hPl.textContent = `${{active}}/${{entries.length}}`;
  hPl.style.color = active > 0 ? '#22c98b' : '#444';

  document.getElementById('m-active').textContent  = `${{active}} pipelines`;
  const pendEl = document.getElementById('m-pending');
  pendEl.textContent  = `${{ingesting}} jobs`;
  pendEl.style.color  = ingesting > 0 ? '#f5a623' : '#2e3240';

  const badge = document.getElementById('h-badge');
  if (ingesting > 0) {{ badge.style.display = ''; badge.textContent = '● INGESTING'; }}
  else if (loading)  {{ badge.style.display = ''; badge.textContent = '● PROCESSING'; }}
  else               {{ badge.style.display = 'none'; }}

  document.getElementById('pl-label').innerHTML =
    `PIPELINE STATUS <span style="color:#2e3240">${{active}} ready · ${{ingesting}} active</span>`;
}}

// ── Activity feed ─────────────────────────────────────────────────────────────
function addActivity(evt) {{
  actCount++;
  const row = document.createElement('div');
  row.className = 'act-row';
  const t = evt.time ? new Date(evt.time).toLocaleTimeString('en-US',{{hour12:false}}) : new Date().toLocaleTimeString('en-US',{{hour12:false}});
  const c = ACT_COLORS[evt.type] || '#444';
  row.innerHTML = `
    <span class="act-time">${{t}}</span>
    <span class="act-type" style="color:${{c}}">${{evt.type}}</span>
    <span class="act-msg">${{evt.msg}}</span>
  `;
  actScroll.appendChild(row);
  if (actScroll.children.length > 80) actScroll.removeChild(actScroll.firstChild);
  actScroll.scrollTop = actScroll.scrollHeight;
  document.getElementById('act-label').innerHTML =
    `LIVE ACTIVITY <span style="color:#2e3240">${{actCount}} events</span>`;
}}

// ── Chat bubbles ──────────────────────────────────────────────────────────────
function addBubble(role, text, extra='') {{
  console.log('[ui] addBubble', role, text.slice(0,60), extra);
  const row = document.createElement('div');
  row.className = `msg-row ${{role}}`;
  row.innerHTML = `
    <span class="msg-label">${{role === 'user' ? 'YOU' : 'DATRIS AGENT'}}</span>
    <div class="bubble ${{role}} ${{extra}}">${{text.replace(/</g,'&lt;').replace(/>/g,'&gt;')}}</div>
  `;
  chatScroll.appendChild(row);
  chatScroll.scrollTop = chatScroll.scrollHeight;
  return row.querySelector('.bubble');
}}

function addToolChip(id, name, input_) {{
  const chip = document.createElement('div');
  chip.className = 'tool-chip';
  chip.id = 'chip-' + id;
  chip.innerHTML = `
    <div class="tool-dot"></div>
    <span class="tool-name">${{name}}</span>
    <span class="tool-args">${{JSON.stringify(input_).slice(0,55)}}</span>
  `;
  chatScroll.appendChild(chip);
  chatScroll.scrollTop = chatScroll.scrollHeight;
  activeTools[id] = chip;
}}

function removeToolChip(id) {{
  const chip = activeTools[id];
  if (chip) {{ chip.remove(); delete activeTools[id]; }}
}}

function showThinking() {{
  const el = document.createElement('div');
  el.className = 'thinking'; el.id = 'thinking';
  [0,1,2].forEach(i => {{
    const d = document.createElement('div');
    d.className = 'dot';
    d.style.animation = `pulse 1.2s ${{i*0.22}}s infinite`;
    el.appendChild(d);
  }});
  chatScroll.appendChild(el);
  chatScroll.scrollTop = chatScroll.scrollHeight;
}}
function hideThinking() {{ document.getElementById('thinking')?.remove(); }}

// ── State SSE (pipeline + activity deltas) ────────────────────────────────────
const stateSource = new EventSource('/stream/state');

stateSource.addEventListener('snapshot', e => {{
  const snap = JSON.parse(e.data);
  pipelines  = snap.pipelines;
  totalRows  = snap.total_rows;
  apiCalls   = snap.api_calls;
  renderPipelines();
  snap.activity.forEach(a => addActivity(a));
  document.getElementById('h-rows').textContent = totalRows.toLocaleString();
  document.getElementById('m-rows').textContent = totalRows.toLocaleString();
  document.getElementById('h-api').textContent  = apiCalls + ' calls';
}});

stateSource.addEventListener('pipeline', e => {{
  const d = JSON.parse(e.data);
  pipelines[d.key] = d.pipeline;
  totalRows = d.total_rows;
  document.getElementById('h-rows').textContent = totalRows.toLocaleString();
  document.getElementById('m-rows').textContent = totalRows.toLocaleString();
  renderPipelines();
}});

stateSource.addEventListener('activity', e => {{
  addActivity(JSON.parse(e.data));
}});

stateSource.addEventListener('api_calls', e => {{
  apiCalls = JSON.parse(e.data).api_calls;
  document.getElementById('h-api').textContent = apiCalls + ' calls';
}});

// ── Chat SSE ──────────────────────────────────────────────────────────────────
async function sendMessage() {{
  const text = inputEl.value.trim();
  if (!text || loading) return;
  inputEl.value = '';
  setLoading(true);

  addBubble('user', text);
  showThinking();

  const sessionId = 'session-' + (window._sid = window._sid || Date.now());

  const evtSource = new EventSource(
    '/chat?' + new URLSearchParams({{ message: text, session_id: sessionId }})
  );

  // POST instead so body is available; re-open as SSE via fetch + ReadableStream
  evtSource.close();   // close the GET-based attempt

  const res = await fetch('/chat', {{
    method: 'POST',
    headers: {{ 'Content-Type': 'application/json' }},
    body: JSON.stringify({{ message: text, session_id: sessionId }}),
  }});

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  let answerBubble = null;

  hideThinking();

  function handleEvent(evtType, evtData) {{
    try {{
      console.log('[sse]', evtType, evtData.slice(0, 100));
      const d = JSON.parse(evtData);
      if (evtType === 'tool_start') {{
        addToolChip(d.id, d.name, d.input);
      }} else if (evtType === 'tool_end') {{
        removeToolChip(d.id);
      }} else if (evtType === 'partial_text') {{
        if (!answerBubble) answerBubble = addBubble('agent', d.text, 'partial');
        else {{ answerBubble.textContent = d.text; chatScroll.scrollTop = chatScroll.scrollHeight; }}
      }} else if (evtType === 'answer') {{
        if (answerBubble) {{ answerBubble.classList.remove('partial'); answerBubble.textContent = d.text; }}
        else addBubble('agent', d.text);
        chatScroll.scrollTop = chatScroll.scrollHeight;
      }} else if (evtType === 'error') {{
        addBubble('agent', 'Error: ' + d.message, 'error');
      }}
    }} catch(e) {{ console.error('SSE parse error:', e, evtData); }}
  }}

  while (true) {{
    const {{ done, value }} = await reader.read();
    if (done) break;
    buf += decoder.decode(value, {{ stream: true }});

    // Parse SSE events from buffer
    // Split on both \\r\\n and \\n to handle all SSE line endings
    const lines = buf.split(/\\r?\\n/);
    buf = lines.pop();  // keep incomplete last line

    let evtType = null;
    let dataLines = [];
    for (const line of lines) {{
      const trimmed = line.trim();
      if (trimmed.startsWith('event:')) {{
        evtType = trimmed.slice(6).trim();
      }} else if (trimmed.startsWith('data:')) {{
        dataLines.push(trimmed.slice(5));
      }} else if (trimmed === '' && evtType && dataLines.length) {{
        handleEvent(evtType, dataLines.join('\\n'));
        evtType = null;
        dataLines = [];
      }}
    }}
  }}

  // Flush any remaining event in buffer
  if (buf.trim()) {{
    const lines = buf.split(/\\r?\\n/);
    let evtType = null;
    let dataLines = [];
    for (const line of lines) {{
      const trimmed = line.trim();
      if (trimmed.startsWith('event:')) evtType = trimmed.slice(6).trim();
      else if (trimmed.startsWith('data:')) dataLines.push(trimmed.slice(5));
      else if (trimmed === '' && evtType && dataLines.length) {{
        handleEvent(evtType, dataLines.join('\\n'));
        evtType = null;
        dataLines = [];
      }}
    }}
    if (evtType && dataLines.length) handleEvent(evtType, dataLines.join('\\n'));
  }}
  console.log('[sse] stream ended, buf remaining:', buf.length);

  setLoading(false);
}}

function setLoading(val) {{
  loading = val;
  sendBtn.disabled = val;
  renderPipelines();   // refreshes badge
}}

sendBtn.addEventListener('click', sendMessage);
inputEl.addEventListener('keydown', e => {{ if (e.key === 'Enter' && !e.shiftKey) sendMessage(); }});
</script>
</body>
</html>"""
