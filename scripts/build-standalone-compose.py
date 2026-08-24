#!/usr/bin/env python3
"""Generate docker-compose.standalone.yml — a single, self-contained Compose file.

The canonical docker-compose.yml bind-mounts three files from the repo tree
(the two init scripts + the config override), which is why a plain download of
that file alone won't run without a checkout. This script inlines those files
into a top-level `configs:` block using Compose's inline `content:` (Compose
>= 2.23), so the resulting docker-compose.standalone.yml needs nothing but
itself:

    curl -O https://get.datris.ai/docker-compose.standalone.yml
    ANTHROPIC_API_KEY=sk-ant-... docker compose -f docker-compose.standalone.yml up -d

Source of truth stays docker-compose.yml + docker/*. Re-run this after editing
any of them. The transform is plain text (not a YAML round-trip) so every
comment in the source compose file is preserved verbatim.
"""
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent

# (config-name, source-path-relative-to-root)
INLINE = [
    ("vault-hcl", "docker/vault.hcl"),
    ("vault-bootstrap-sh", "docker/vault-bootstrap.sh"),
    ("vault-init-sh", "docker/vault-init.sh"),
    ("minio-init-sh", "docker/minio-init.sh"),
    ("datris-app-yaml", "docker/config/application.yaml"),
]

# Each entry rewrites a service's host bind mount into a `configs:` reference.
# Exact-string replacements keep us honest: if the source compose file changes
# shape, the matching fails loudly instead of producing a broken standalone.
REPLACEMENTS = [
    (
        # vault: inline vault.hcl as a config, keep the vault-data named volume
        # (it persists Vault storage and the init file across restarts).
        "    volumes:\n"
        "      - vault-data:/vault/file\n"
        "      - ./docker/vault.hcl:/vault/config/vault.hcl:ro\n",
        "    configs:\n"
        "      - source: vault-hcl\n"
        "        target: /vault/config/vault.hcl\n"
        "        mode: 0644\n"
        "    volumes:\n"
        "      - vault-data:/vault/file\n",
    ),
    (
        # vault-init: inline both scripts as configs, keep the shared vault-data
        # named volume (bootstrap reads/writes the init file there).
        "    volumes:\n"
        "      # Shares vault-data so bootstrap can read/write the init file (unseal key\n"
        "      # + root token) and unseal the same storage the vault service uses.\n"
        "      - vault-data:/vault/file\n"
        "      # Writes the server's random token here for the datris service to read.\n"
        "      - datris-vault-token:/vault-token\n"
        "      # TAP_RUNNER_TOKEN file minted on first boot so compose up works without a .env token.\n"
        "      - tap-runner-token:/tap-runner-token\n"
        "      - ./docker/vault-bootstrap.sh:/vault-bootstrap.sh\n"
        "      - ./docker/vault-init.sh:/vault-init.sh\n",
        "    configs:\n"
        "      - source: vault-bootstrap-sh\n"
        "        target: /vault-bootstrap.sh\n"
        "        mode: 0755\n"
        "      - source: vault-init-sh\n"
        "        target: /vault-init.sh\n"
        "        mode: 0755\n"
        "    volumes:\n"
        "      - vault-data:/vault/file\n"
        "      - datris-vault-token:/vault-token\n"
        "      - tap-runner-token:/tap-runner-token\n",
    ),
    (
        "    volumes:\n"
        "      - ./docker/minio-init.sh:/minio-init.sh\n",
        "    configs:\n"
        "      - source: minio-init-sh\n"
        "        target: /minio-init.sh\n"
        "        mode: 0755\n",
    ),
    (
        # datris: inline the config override, keep the pip-cache named volume.
        "    volumes:\n"
        "      - ./docker/config:/config\n"
        "      - pip-cache:/root/.cache/pip\n"
        "      # Read-only: the server reads its Vault token (written by vault-init) but\n"
        "      # can't modify it. Dedicated volume so the server never sees the Vault\n"
        "      # root token / unseal key that live on the vault-data volume.\n"
        "      - datris-vault-token:/vault-token:ro\n"
        "      - tap-runner-token:/tap-runner-token:ro\n",
        "    configs:\n"
        "      - source: datris-app-yaml\n"
        "        target: /config/application.yaml\n"
        "    volumes:\n"
        "      - pip-cache:/root/.cache/pip\n"
        "      - datris-vault-token:/vault-token:ro\n"
        "      - tap-runner-token:/tap-runner-token:ro\n",
    ),
]

HEADER = (
    "# ============================================================\n"
    "# GENERATED FILE — DO NOT EDIT BY HAND.\n"
    "# Produced by scripts/build-standalone-compose.py from:\n"
    "#   docker-compose.yml + docker/vault.hcl + docker/vault-bootstrap.sh\n"
    "#   + docker/vault-init.sh + docker/minio-init.sh\n"
    "#   + docker/config/application.yaml\n"
    "#\n"
    "# A single self-contained Compose file: no repo checkout, no bind\n"
    "# mounts. The init scripts and config are inlined below under the\n"
    "# top-level `configs:` block. Requires Docker Compose >= 2.23.\n"
    "#\n"
    "# Run:\n"
    "#   ANTHROPIC_API_KEY=sk-ant-... \\\n"
    "#     docker compose -f docker-compose.standalone.yml up -d\n"
    "# (or place a .env next to this file — Compose reads it automatically)\n"
    "# ============================================================\n\n"
)


def indent(text, n):
    pad = " " * n
    return "\n".join((pad + line) if line.strip() else "" for line in text.split("\n"))


def main():
    compose = (ROOT / "docker-compose.yml").read_text()

    for old, new in REPLACEMENTS:
        if old not in compose:
            sys.exit(
                "error: expected bind-mount block not found in docker-compose.yml — "
                "it may have changed shape. Update REPLACEMENTS in this script.\n"
                "Missing block:\n" + old
            )
        compose = compose.replace(old, new, 1)

    blocks = ["configs:"]
    for name, path in INLINE:
        content = (ROOT / path).read_text().rstrip("\n")
        # Escape `$` as `$$` so Compose does NOT interpolate inline config
        # content at file-load time. Without this, shell variables in the
        # inlined scripts (e.g. `$INIT_FILE`, `$NF`, `$(...)` command
        # substitutions) get replaced with blank strings, silently corrupting
        # the materialized scripts. The container shell / app expands these at
        # runtime instead, using its own `environment:` block — identical to
        # how the bind-mounted docker-compose.yml reads the scripts from disk.
        content = content.replace("$", "$$")
        blocks.append(f"  {name}:")
        blocks.append("    content: |")
        blocks.append(indent(content, 6))
    configs_block = "\n".join(blocks) + "\n"

    out = HEADER + compose.rstrip("\n") + "\n\n" + configs_block
    target = ROOT / "docker-compose.standalone.yml"
    target.write_text(out)
    print(f"wrote {target.relative_to(ROOT)} ({len(out)} bytes)")


if __name__ == "__main__":
    main()
