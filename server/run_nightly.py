#!/usr/bin/env python3
"""Nightly crawl. Run by .github/workflows/nightly.yml at about 2 AM IST.

Local test without Firebase / AI:   python server/run_nightly.py --dry-run --no-ai --only upsc
"""
import argparse
import os
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).parent))
from govjob.ai import Gemini  # noqa: E402
from govjob.pipeline import Nightly  # noqa: E402
from govjob.store import FirestoreStore, MemoryStore  # noqa: E402

HERE = Path(__file__).parent


def run_url():
    if os.environ.get("GITHUB_RUN_ID"):
        return f"{os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/{os.environ['GITHUB_REPOSITORY']}" \
               f"/actions/runs/{os.environ['GITHUB_RUN_ID']}"
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="use a local JSON file instead of Firestore")
    ap.add_argument("--state-file", default=str(HERE / "local-state.json"))
    ap.add_argument("--no-ai", action="store_true")
    ap.add_argument("--only", nargs="*", help="source ids to crawl")
    ap.add_argument("--no-render", action="store_true", help="do not use the headless browser")
    a = ap.parse_args()

    from govjob.sources import load as load_sources
    cfg = load_sources(HERE / "config" / "sources.yaml")

    store = MemoryStore(a.state_file) if a.dry_run else FirestoreStore()
    # GitHub's own timer is a late backup for the India server's schedule: skip if the crawl already ran.
    if os.environ.get("RUN_EVENT") == "schedule" and not a.only:
        from datetime import datetime, timezone
        last = store.get_status("server").get("last_success")
        try:
            hours = (datetime.now(timezone.utc) - datetime.fromisoformat(last)).total_seconds() / 3600
        except (TypeError, ValueError):
            hours = 999
        if hours < 14:
            print(f"Crawl already done {hours:.1f} h ago (started by the India server) - skipping this late GitHub run.")
            return
    gem = None if a.no_ai else Gemini(max_calls=int(os.environ.get("AI_MAX_CALLS", "300")))
    from govjob.render import Renderer
    renderer = Renderer(enabled=not a.no_render)
    try:
        stats = Nightly(store, cfg, gem, run_url=run_url(), renderer=renderer).run(only=a.only)
    finally:
        renderer.close()
    print(f"pages rendered with the headless browser: {renderer.pages}" +
          (f" (browser unavailable: {renderer.error})" if renderer.error else ""))
    print({k: v for k, v in stats.__dict__.items() if k != "errors"})


if __name__ == "__main__":
    main()
