"""Load and check server/config/sources.yaml (the list of websites crawled every night)."""
from __future__ import annotations

import re
from pathlib import Path

import yaml

SECTORS = {"defence", "police", "space_research", "railways", "banking", "civil_services", "ssc", "psu", "teaching",
           "health", "postal", "judiciary", "engineering", "other"}


class SourcesError(Exception):
    pass


def load(path: Path | str, text: str | None = None) -> dict:
    raw = text if text is not None else Path(path).read_text(encoding="utf-8")
    try:
        cfg = yaml.safe_load(raw) or {}
    except yaml.YAMLError as e:
        raise SourcesError(f"sources.yaml is not valid YAML (check spaces and quotes): {e}")
    problems, seen = [], set()
    for i, s in enumerate(cfg.get("sources") or []):
        where = f"entry {i + 1} ({s.get('id') or s.get('name') or '?'})" if isinstance(s, dict) else f"entry {i + 1}"
        if not isinstance(s, dict):
            problems.append(f"{where}: must look like  - id: ..., name: ..., level: ..., home: ...")
            continue
        for f in ("id", "name", "level", "home"):
            if not s.get(f):
                problems.append(f"{where}: '{f}' is missing")
        sid = str(s.get("id") or "")
        if sid and not re.fullmatch(r"[a-z0-9_]+", sid):
            problems.append(f"{where}: id may only use small letters, digits and _ (e.g. ka_police)")
        if sid in seen:
            problems.append(f"{where}: id '{sid}' is used twice")
        seen.add(sid)
        if s.get("level") not in ("central", "state"):
            problems.append(f"{where}: level must be central or state")
        if s.get("level") == "state" and not s.get("state"):
            problems.append(f"{where}: state sources need  state: \"<State name>\"")
        if s.get("home") and not str(s["home"]).startswith(("http://", "https://")):
            problems.append(f"{where}: home must start with https://")
        s.setdefault("sector", "other")
        if s["sector"] not in SECTORS:
            problems.append(f"{where}: sector '{s['sector']}' unknown; use one of {sorted(SECTORS)}")
        s.setdefault("kind", "mixed")
        if s["kind"] not in ("recruitment", "deputation", "mixed"):
            problems.append(f"{where}: kind must be recruitment, deputation or mixed")
        s.setdefault("hints", [])
        s.setdefault("domains", [])
    if problems:
        raise SourcesError("Problems in server/config/sources.yaml:\n- " + "\n- ".join(problems))
    cfg.setdefault("defaults", {})
    return cfg
