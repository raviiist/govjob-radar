"""Build the public feed (static JSON on GitHub Pages) from the master job list."""
from __future__ import annotations

import json
import shutil
from datetime import datetime, timedelta
from pathlib import Path

from . import FEED_SCHEMA_VERSION
from .util import iso, now_utc, sha

PUBLIC_FIELDS = [
    "id", "title", "organisation", "advt_no", "source_id", "source_name", "level", "state", "sector", "kind",
    "posts", "total_vacancies", "pay_level_min", "pay_level_max", "min_qualification", "qualification_summary",
    "age_min", "age_max", "age_as_on", "age_note", "fee_text", "apply_mode", "start_date", "last_date",
    "original_last_date", "extended", "extension_history", "notice_url", "apply_url", "page_url", "location",
    "how_to_apply", "summary", "deputation_tenure", "eligible_cadre", "first_seen", "verified_at", "status",
    "closed_at",
]
SHOWN = ("live", "awaiting_extension_check")
ARCHIVE_DAYS = 30
SHRINK_LIMIT = 0.5   # refuse to publish if the feed would lose more than half its jobs at once


def public(job: dict) -> dict:
    out = {k: job.get(k) for k in PUBLIC_FIELDS}
    for h in out.get("extension_history") or []:
        h.pop("why", None)
    return out


def build(jobs: dict[str, dict], sources_cfg: dict) -> tuple[dict, dict]:
    now = now_utc()
    live = [public(j) for j in jobs.values() if j.get("status") in SHOWN]
    live.sort(key=lambda j: (j.get("last_date") or "9999", j["title"]))
    archive = []
    for j in jobs.values():
        if j.get("status") == "closed" and j.get("closed_at") and j.get("first_seen"):
            try:
                if now - datetime.fromisoformat(j["closed_at"]) <= timedelta(days=ARCHIVE_DAYS):
                    archive.append(public(j))
            except ValueError:
                pass
    sources = [{"id": s["id"], "name": s["name"], "level": s["level"], "state": s.get("state"),
                "home": s["home"]} for s in sources_cfg["sources"]]
    feed = {"schema": FEED_SCHEMA_VERSION, "generated_at": iso(now), "jobs": live, "archive": archive,
            "sources": sources}
    body = json.dumps(feed, ensure_ascii=False, separators=(",", ":"))
    meta = {"schema": FEED_SCHEMA_VERSION, "generated_at": feed["generated_at"], "count": len(live),
            "archive_count": len(archive), "hash": sha(json.dumps(live, sort_keys=True), 20),
            "bytes": len(body.encode())}
    return feed, meta


def write_site(feed: dict, meta: dict, out_dir: Path, static_dir: Path):
    out_dir.mkdir(parents=True, exist_ok=True)
    if static_dir.exists():
        for p in static_dir.iterdir():
            if p.is_file():
                shutil.copy(p, out_dir / p.name)
    fdir = out_dir / "feed" / "v1"
    fdir.mkdir(parents=True, exist_ok=True)
    (fdir / "jobs.json").write_text(json.dumps(feed, ensure_ascii=False, separators=(",", ":")))
    (fdir / "meta.json").write_text(json.dumps(meta))
    (out_dir / ".nojekyll").write_text("")


def validate(feed: dict) -> list[str]:
    problems = []
    ids = set()
    for j in feed["jobs"]:
        if j["id"] in ids:
            problems.append(f"duplicate id {j['id']}")
        ids.add(j["id"])
        if not j.get("title") or not j.get("notice_url"):
            problems.append(f"{j['id']}: missing title/link")
        if not str(j.get("notice_url", "")).startswith("http"):
            problems.append(f"{j['id']}: bad link")
    return problems


def shrink_too_much(prev_count: int | None, new_count: int) -> bool:
    return bool(prev_count and prev_count >= 20 and new_count < prev_count * (1 - SHRINK_LIMIT))
