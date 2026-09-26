#!/usr/bin/env python3
"""Guard for the MinIO image mirror (datrisai/minio) and the files that use it.

MinIO's own registries went private twice (2026-09-12, 2026-09-24). The stack
now pulls an unmodified mirror published by .github/workflows/mirror-image.yml
to the Datris Docker Hub org. This script checks, offline:

  - mirror-image.yml: dispatch-only, the required inputs, read-only permissions,
    the same action pins as docker-publish.yml, a plain index copy (no
    annotations), an inspect step that writes Image/Digest/Platforms to the job
    summary, and an SBOM step.
  - the inspect step really fails when the mirrored index lacks linux/amd64 or
    linux/arm64 (run against a fake `docker` on PATH).
  - all three compose files pin datrisai/minio by tag + digest on both minio and
    minio-init, and keep `user: "0:0"` with its comment.
  - docker-compose.standalone.yml regenerates without a diff.
  - the security-scan image matrix includes minio (five entries).
  - no outside-registry MinIO reference is left outside history.

The changelog / release-notes wording checks that shipped with the story were
one-time acceptance for v1.38.1 and are not repeated here: once the release
renamed the Unreleased block they could never pass again on main.

Run from anywhere: `python3 scripts/check-minio-mirror.py`. Needs PyYAML and
bash. Exits non-zero, naming every failed check.
"""
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.exit("PyYAML is required: pip install pyyaml")

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = Path(os.environ.get("MIRROR_WORKFLOW", ROOT / ".github" / "workflows" / "mirror-image.yml"))
PUBLISH = ROOT / ".github" / "workflows" / "docker-publish.yml"
SECURITY = ROOT / ".github" / "workflows" / "security-scan.yml"
COMPOSE_FILES = [
    ROOT / "docker-compose.yml",
    ROOT / "deploy" / "docker-compose.prod.yml",
    ROOT / "docker-compose.standalone.yml",
]
CHANGELOG = ROOT / "docs" / "changelog.mdx"
RELEASE_NOTES = ROOT / "release-notes.md"

# The pinned mirror is read from docker-compose.yml's minio service, so a MinIO
# bump (run mirror-image.yml, edit tag + digest in the compose files,
# regenerate standalone) needs no edit here. The other two compose files and
# both services must equal it.
IMAGE_RE = re.compile(r"^datrisai/minio:(RELEASE\.[0-9A-Za-z.:-]+)@(sha256:[0-9a-f]{64})$")


def _pinned_image():
    try:
        svcs = (yaml.safe_load(COMPOSE_FILES[0].read_text()) or {}).get("services") or {}
        return str((svcs.get("minio") or {}).get("image", ""))
    except Exception:
        return ""


PINNED_IMAGE = _pinned_image()
_m = IMAGE_RE.match(PINNED_IMAGE)
# Fallbacks only feed the fake docker; the compose check reports the bad pin.
TAG = _m.group(1) if _m else "RELEASE.unknown"
# Digest the fake docker reports for the mirrored index; any well-formed value works.
SOURCE_DIGEST = _m.group(2) if _m else "sha256:" + "0" * 64
SOURCE_RE = re.compile(r"^" + re.escape("cgr" + ".dev") + r"/chainguard/minio:latest@sha256:[0-9a-f]{64}$")

# Built from parts so this file never matches the audit grep itself.
CG_HOST = "cgr" + ".dev"
QUAY_MINIO = "quay" + ".io/minio"
AUDIT_PATTERNS = [CG_HOST, QUAY_MINIO, "minio" + "/minio", "minio" + "/mc"]

PINS = {
    "actions/checkout": "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1",
    "docker/setup-buildx-action": "docker/setup-buildx-action@37fe631027851001ddb9b187196cc803df7f5f0e",
    "docker/login-action": "docker/login-action@dbcb813823bdd20940b903addbd779551569679f",
    "anchore/sbom-action": "anchore/sbom-action@3ad7283483fc7af8ff2b4ea19663c2d5ca935e26",
}

failures = []


def check(name):
    def wrap(fn):
        try:
            problems = fn() or []
        except Exception as e:  # a crash in a check is a failure of that check
            problems = [f"{type(e).__name__}: {e}"]
        if problems:
            failures.append(name)
            print(f"FAIL {name}")
            for p in problems:
                print(f"     - {p}")
        else:
            print(f"ok   {name}")
        return fn
    return wrap


def load_workflow():
    if not WORKFLOW.exists():
        raise FileNotFoundError(f"{WORKFLOW} does not exist")
    return yaml.safe_load(WORKFLOW.read_text())


def wf_on(wf):
    # PyYAML (YAML 1.1) parses a bare `on:` key as boolean True.
    return wf.get("on", wf.get(True))


def all_steps(wf):
    return [s for job in (wf.get("jobs") or {}).values() for s in (job.get("steps") or [])]


def run_text(wf):
    return "\n".join(s.get("run", "") for s in all_steps(wf))


# ---------------------------------------------------------------- workflow shape

@check("mirror-image.yml: name, dispatch-only trigger, inputs")
def _():
    wf = load_workflow()
    p = []
    if wf.get("name") != "Mirror third-party image":
        p.append(f"name is {wf.get('name')!r}, want 'Mirror third-party image'")
    on = wf_on(wf)
    keys = set(on) if isinstance(on, dict) else ({on} if isinstance(on, str) else set(on or []))
    if keys != {"workflow_dispatch"}:
        p.append(f"triggers are {sorted(map(str, keys))}, want only workflow_dispatch (no schedule/push)")
    inputs = ((on or {}).get("workflow_dispatch") or {}).get("inputs") or {} if isinstance(on, dict) else {}
    src = inputs.get("source") or {}
    if src.get("type") != "string" or not SOURCE_RE.match(str(src.get("default", ""))):
        p.append(f"input source: want string defaulting to the pinned Chainguard index, got {src}")
    repo = inputs.get("repository") or {}
    if repo.get("type") != "string" or repo.get("default") != "minio":
        p.append(f"input repository: want string default 'minio', got {repo}")
    tag = inputs.get("tag") or {}
    if tag.get("type") != "string" or tag.get("required") is not True:
        p.append(f"input tag: want required string, got {tag}")
    al = inputs.get("also_latest") or {}
    if al.get("type") != "boolean" or al.get("default") is not True:
        p.append(f"input also_latest: want boolean default true, got {al}")
    return p


@check("mirror-image.yml: permissions and env")
def _():
    wf = load_workflow()
    p = []
    if wf.get("permissions") != {"contents": "read"}:
        p.append(f"permissions are {wf.get('permissions')}, want {{contents: read}}")
    env = wf.get("env") or {}
    if env.get("DOCKERHUB_ORG") != "datrisai":
        p.append("env.DOCKERHUB_ORG must be datrisai")
    if env.get("FORCE_JAVASCRIPT_ACTIONS_TO_NODE24") not in (True, "true"):
        p.append("env.FORCE_JAVASCRIPT_ACTIONS_TO_NODE24 must be true")
    return p


@check("mirror-image.yml: action pins match docker-publish.yml")
def _():
    wf = load_workflow()
    publish = PUBLISH.read_text()
    uses = [s.get("uses", "") for s in all_steps(wf) if s.get("uses")]
    p = []
    for action, pin in PINS.items():
        if pin not in publish:
            p.append(f"test fixture stale: {pin} not in docker-publish.yml")
        mine = [u for u in uses if u.split("@")[0] == action]
        if not mine:
            p.append(f"no step uses {action}")
        elif any(u != pin for u in mine):
            p.append(f"{action} pinned as {mine}, want {pin}")
    for s in all_steps(wf):
        if (s.get("uses") or "").startswith("docker/login-action"):
            w = s.get("with") or {}
            if "DOCKERHUB_USERNAME" not in str(w.get("username")) or "DOCKERHUB_TOKEN" not in str(w.get("password")):
                p.append(f"login step must use DOCKERHUB_USERNAME/DOCKERHUB_TOKEN secrets, got {w}")
    return p


@check("mirror-image.yml: plain index copy with imagetools create")
def _():
    wf = load_workflow()
    runs = run_text(wf)
    p = []
    if "imagetools create" not in runs:
        p.append("no `docker buildx imagetools create` in any run step")
    if re.search(r"--annotation|--annotations|\s-a\s", runs):
        p.append("imagetools create must not add annotations (byte-for-byte index copy)")
    if "latest" not in runs or "also_latest" not in (runs + json.dumps(wf, default=str)):
        p.append("run step must add :latest when also_latest is true")
    return p


@check("mirror-image.yml: SBOM step")
def _():
    wf = load_workflow()
    sboms = [s for s in all_steps(wf) if (s.get("uses") or "").startswith("anchore/sbom-action")]
    if not sboms:
        return ["no anchore/sbom-action step"]
    w = sboms[0].get("with") or {}
    p = []
    if w.get("format") != "cyclonedx-json":
        p.append(f"format is {w.get('format')!r}, want cyclonedx-json")
    for key in ("output-file", "artifact-name"):
        v = str(w.get(key, ""))
        if not (v.startswith("sbom-") and v.endswith(".cdx.json") and "repository" in v and "tag" in v):
            p.append(f"{key} is {v!r}, want sbom-<repository>-<tag>.cdx.json")
    img = str(w.get("image", ""))
    if not ("DOCKERHUB_ORG" in img and "repository" in img and "tag" in img):
        p.append(f"image is {img!r}, want ${{DOCKERHUB_ORG}}/<repository>:<tag>")
    return p


# ------------------------------------------------ inspect step: platform guard

FAKE_DOCKER = r'''#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
plats = [p for p in os.environ["FAKE_PLATFORMS"].split(",") if p]
digest = os.environ["FAKE_DIGEST"]
ms = [{"mediaType": "application/vnd.oci.image.manifest.v1+json",
       "digest": "sha256:" + ("%064x" % (i + 1)), "size": 1000,
       "platform": {"os": p.split("/")[0], "architecture": p.split("/")[1]}}
      for i, p in enumerate(plats)]
index = {"schemaVersion": 2, "mediaType": "application/vnd.oci.image.index.v1+json", "manifests": ms}
if "imagetools" in args and "inspect" in args or args[:2] == ["manifest", "inspect"]:
    if "--raw" in args or args[:1] == ["manifest"]:
        print(json.dumps(index, indent=2)); sys.exit(0)
    if "--format" in args:
        t = args[args.index("--format") + 1]
        if ".Manifest.Digest" in t:
            print(json.dumps(digest) if "json" in t else digest); sys.exit(0)
        if ".Manifest" in t:
            m = dict(index, digest=digest, manifests=[dict(x, platform=dict(x["platform"])) for x in ms])
            print(json.dumps(m) if "json" in t else m); sys.exit(0)
        if ".Image" in t:
            print(json.dumps({p: {"architecture": p.split("/")[1], "os": "linux"} for p in plats})); sys.exit(0)
        print(json.dumps(dict(index, digest=digest))); sys.exit(0)
    ref = next((a for a in args if "/" in a and not a.startswith("-")), "img")
    print("Name:      " + ref)
    print("MediaType: application/vnd.oci.image.index.v1+json")
    print("Digest:    " + digest)
    print()
    print("Manifests:")
    for x, p in zip(ms, plats):
        print("  Name:        " + ref.split(":")[0] + "@" + x["digest"])
        print("  MediaType:   " + x["mediaType"])
        print("  Platform:    " + p)
        print()
    sys.exit(0)
sys.exit(0)  # create, login, anything else: succeed silently
'''


def run_inspect_steps(platforms):
    """Run the workflow's inspect step(s) with a fake docker; return (rc, summary, log)."""
    wf = load_workflow()
    inputs = {"source": f"{CG_HOST}/chainguard/minio:latest@{SOURCE_DIGEST}",
              "repository": "minio", "tag": TAG, "also_latest": "true"}
    wf_env = {k: str(v) for k, v in (wf.get("env") or {}).items()}

    def subst(text, env):
        text = re.sub(r"\$\{\{\s*(?:github\.event\.)?inputs\.(\w+)\s*\}\}", lambda m: inputs.get(m.group(1), ""), text)
        text = re.sub(r"\$\{\{\s*env\.(\w+)\s*\}\}", lambda m: env.get(m.group(1), ""), text)
        return text

    job = next(iter(wf["jobs"].values()))
    job_env = {k: subst(str(v), wf_env) for k, v in (job.get("env") or {}).items()}
    steps = [s for s in job.get("steps") or [] if "imagetools inspect" in (s.get("run") or "")]
    if not steps:
        raise AssertionError("no run step calls `docker buildx imagetools inspect`")
    with tempfile.TemporaryDirectory() as d:
        dp = Path(d)
        fake = dp / "docker"
        fake.write_text(FAKE_DOCKER)
        fake.chmod(fake.stat().st_mode | stat.S_IEXEC)
        summary = dp / "summary.md"
        summary.write_text("")
        log = ""
        for s in steps:
            env = dict(os.environ, **wf_env, **job_env)
            env.update({k: subst(str(v), env) for k, v in (s.get("env") or {}).items()})
            env.update({"PATH": f"{d}:{os.environ['PATH']}", "GITHUB_STEP_SUMMARY": str(summary),
                        "GITHUB_OUTPUT": str(dp / "out"), "GITHUB_ENV": str(dp / "env"),
                        "FAKE_PLATFORMS": ",".join(platforms), "FAKE_DIGEST": SOURCE_DIGEST})
            script = subst(s["run"], env)
            r = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", script], env=env,
                               capture_output=True, text=True, timeout=60)
            log += r.stdout + r.stderr
            if r.returncode != 0:
                return r.returncode, summary.read_text(), log
        return 0, summary.read_text(), log


@check("inspect step: both platforms present -> succeeds and writes Image/Digest/Platforms summary")
def _():
    rc, summary, log = run_inspect_steps(["linux/amd64", "linux/arm64"])
    p = []
    if rc != 0:
        p.append(f"exited {rc} with both platforms present; log:\n{log}")
    for needle in ("Image", "Digest", "Platforms", SOURCE_DIGEST, "linux/amd64", "linux/arm64", "|"):
        if needle not in summary:
            p.append(f"job summary lacks {needle!r}")
    for needle in (SOURCE_DIGEST, "linux/amd64", "linux/arm64"):
        if needle not in log:
            p.append(f"log does not echo {needle!r}")
    return p


@check("inspect step: index missing linux/arm64 -> exits non-zero")
def _():
    rc, _, log = run_inspect_steps(["linux/amd64"])
    return [] if rc != 0 else [f"exited 0 without linux/arm64; log:\n{log}"]


@check("inspect step: index missing linux/amd64 -> exits non-zero")
def _():
    rc, _, log = run_inspect_steps(["linux/arm64"])
    return [] if rc != 0 else [f"exited 0 without linux/amd64; log:\n{log}"]


# ---------------------------------------------------------------- compose files

@check("compose files: minio and minio-init pin datrisai/minio by tag + digest")
def _():
    p = []
    for f in COMPOSE_FILES:
        svcs = (yaml.safe_load(f.read_text()) or {}).get("services") or {}
        imgs = {}
        for name in ("minio", "minio-init"):
            img = (svcs.get(name) or {}).get("image", "")
            imgs[name] = img
            if not IMAGE_RE.match(img or ""):
                p.append(f"{f.relative_to(ROOT)} {name}: image {img!r}, want datrisai/minio:RELEASE.<date>@sha256:<digest>")
            elif img != PINNED_IMAGE:
                p.append(f"{f.relative_to(ROOT)} {name}: image {img!r} differs from docker-compose.yml minio {PINNED_IMAGE!r}")
        if len(set(imgs.values())) != 1:
            p.append(f"{f.relative_to(ROOT)}: minio and minio-init use different images {imgs}")
    return p


@check("compose files: minio keeps user \"0:0\" and its comment; comments updated")
def _():
    p = []
    for f in COMPOSE_FILES:
        rel = f.relative_to(ROOT)
        text = f.read_text()
        svcs = (yaml.safe_load(text) or {}).get("services") or {}
        if (svcs.get("minio") or {}).get("user") != "0:0":
            p.append(f"{rel} minio: user must stay \"0:0\"")
        if not re.search(r"# The image runs as uid 65532; existing installs' data dir is root-owned\n"
                         r"\s*# \(the previous image ran as root\), so keep root to stay writable\.\n"
                         r'\s*user: "0:0"', text):
            p.append(f"{rel} minio: the uid 65532 / root-owned comment must stay directly above user: \"0:0\"")
        if "Same image as the minio service: it bundles mc and bash" not in text:
            p.append(f"{rel} minio-init: keep the 'Same image as the minio service' comment")
        if "MinIO withdrew its public images" in text:
            p.append(f"{rel}: old 'MinIO withdrew its public images' comment still present")
        if "mirror-image workflow" not in text:
            p.append(f"{rel} minio: comment must explain the bump path (run the mirror-image workflow)")
    return p


@check("docker-compose.standalone.yml regenerates without a diff")
def _():
    r = subprocess.run([sys.executable, str(ROOT / "scripts" / "build-standalone-compose.py")],
                       cwd=ROOT, capture_output=True, text=True)
    if r.returncode != 0:
        return [f"build-standalone-compose.py failed: {r.stderr}"]
    d = subprocess.run(["git", "diff", "--exit-code", "--stat", "docker-compose.standalone.yml"],
                       cwd=ROOT, capture_output=True, text=True)
    return [] if d.returncode == 0 else [f"regenerated file differs from the committed one:\n{d.stdout}"]


# ---------------------------------------------------------------- security scan

@check("security-scan.yml: image-scan matrix has five entries including minio")
def _():
    wf = yaml.safe_load(SECURITY.read_text())
    job = (wf.get("jobs") or {}).get("image-scan") or {}
    matrix = ((job.get("strategy") or {}).get("matrix") or {}).get("image") or []
    p = []
    if len(matrix) != 5:
        p.append(f"matrix has {len(matrix)} entries {matrix}, want 5")
    if "minio" not in matrix:
        p.append("matrix lacks minio")
    for s in job.get("steps") or []:
        ref = ((s.get("with") or {}).get("image-ref"))
        if ref is not None and ref != "datrisai/${{ matrix.image }}:latest":
            p.append(f"step {s.get('name')!r} scans {ref!r}, want datrisai/${{{{ matrix.image }}}}:latest")
    return p


# ---------------------------------------------------------------- audit grep

def vnext_section(text):
    m = re.search(r"^## vNEXT.*?(?=^## )", text, re.S | re.M)
    return m.group(0) if m else None


@check("audit grep: outside-registry MinIO references only in history")
def _():
    # The story's grep, over files git would commit (tracked + untracked,
    # not ignored), so local build output under target/ or .bloop/ is skipped.
    r = subprocess.run(["git", "grep", "-n", "-I", "--untracked", r"\|".join(re.escape(x) for x in AUDIT_PATTERNS),
                        "--", ".", ":!plans", ":!**/node_modules/**"],
                       cwd=ROOT, capture_output=True, text=True)
    notes = RELEASE_NOTES.read_text().splitlines()
    vnext = vnext_section(RELEASE_NOTES.read_text()) or ""
    p = []
    for line in r.stdout.splitlines():
        path, lineno, content = line.split(":", 2)
        path = path[2:] if path.startswith("./") else path
        if path == "docs/changelog.mdx" or path.startswith("release-notes/"):
            continue
        if path == "release-notes.md" and content not in vnext:
            continue  # the v1.37.1 section until the releaser archives it
        # The workflow's `source` input default is required by the story to be
        # the Chainguard index; that single default line is the mirror's input.
        if path == ".github/workflows/mirror-image.yml" and "default:" in content:
            continue
        p.append(f"{path}:{lineno}: {content.strip()[:120]}")
    return p


if __name__ == "__main__":
    if failures:
        print(f"\n{len(failures)} check(s) failed")
        sys.exit(1)
    print("\nall MinIO mirror checks passed")
