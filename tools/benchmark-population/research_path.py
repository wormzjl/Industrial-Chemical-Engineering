"""Find a large input that left tools/ for the main checkout's research/ folder (AGENTS.md, large files).

The benchmark populations (``v1/*/*-inputs.jsonl``), the promotion's ``inputs/validation-inputs.jsonl`` and the
neural case maps are git-ignored; their canonical copies are at the same relative path under ``research/`` of
the main checkout. ``v1/manifest.json`` still pins each file's sha256, so the bytes stay traceable.
"""
from __future__ import annotations

import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def _main_checkout() -> Path | None:
    try:
        common = subprocess.run(['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'], cwd=ROOT,
                                capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None
    return Path(common).parent if common else None


def locate(path) -> Path:
    """The path itself if it exists; else the same repository-relative path under research/ (this checkout's,
    then the main checkout's); else the path unchanged, so the caller's own missing-file message names it."""
    path = Path(path)
    if path.exists():
        return path
    try:
        relative = path.resolve().relative_to(ROOT)
    except ValueError:
        return path
    for base in (ROOT, _main_checkout()):
        if base is not None and (base / 'research' / relative).exists():
            return base / 'research' / relative
    return path
