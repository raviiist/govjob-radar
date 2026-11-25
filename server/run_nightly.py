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
    a = ap.parse_args()

    cfg = yaml.safe_load((HERE / "config" / "sources.yaml").read_text())
    ids = [s["id"] for s in cfg["sources"]]
    dup = {i for i in ids if ids.count(i) > 1}
    if dup:
        sys.exit(f"duplicate source ids in sources.yaml: {dup}")

    store = MemoryStore(a.state_file) if a.dry_run else FirestoreStore()
    gem = None if a.no_ai else Gemini(max_calls=int(os.environ.get("AI_MAX_CALLS", "300")))
    stats = Nightly(store, cfg, gem, run_url=run_url()).run(only=a.only)
    print({k: v for k, v in stats.__dict__.items() if k != "errors"})


if __name__ == "__main__":
    main()
