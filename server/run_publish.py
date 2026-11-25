#!/usr/bin/env python3
"""Build the public feed into ./_site (deployed to GitHub Pages by publish.yml).

Runs every 2 hours and right after the nightly crawl, so jobs approved in the
Admin tab reach users within about 2 hours.
"""
import argparse
import json
import sys
import urllib.request
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).parent))
from govjob import publish  # noqa: E402
from govjob.store import FirestoreStore, MemoryStore  # noqa: E402
from govjob.util import iso, now_utc  # noqa: E402
from run_nightly import run_url  # noqa: E402

HERE = Path(__file__).parent


def previous_count(feed_base: str | None):
    if not feed_base:
        return None
    try:
        with urllib.request.urlopen(feed_base.rstrip("/") + "/feed/v1/meta.json", timeout=20) as r:
            return json.load(r).get("count")
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--state-file", default=str(HERE / "local-state.json"))
    ap.add_argument("--out", default="_site")
    ap.add_argument("--feed-base", default=None, help="current public site, to compare job counts")
    ap.add_argument("--force", action="store_true", help="publish even if the feed shrank a lot")
    a = ap.parse_args()

    cfg = yaml.safe_load((HERE / "config" / "sources.yaml").read_text())
    store = MemoryStore(a.state_file) if a.dry_run else FirestoreStore()
    jobs = store.load_jobs()
    feed, meta = publish.build(jobs, cfg)

    problems = publish.validate(feed)
    if problems:
        store.flag("FEED_INVALID", "Feed not published: " + "; ".join(problems[:10]), severity="critical",
                   run_url=run_url())
        sys.exit(1)
    prev = previous_count(a.feed_base)
    if not a.force and publish.shrink_too_much(prev, meta["count"]):
        store.flag("FEED_SHRANK", f"Feed not published: jobs would drop from {prev} to {meta['count']} at once. "
                   "Users keep the previous list. If this is expected, run the Publish workflow with 'force'.",
                   severity="error", run_url=run_url())
        sys.exit(1)
    store.resolve("FEED_INVALID")
    store.resolve("FEED_SHRANK")
    store.resolve("WORKFLOW_FAILED", origin="workflow", source_id="publish")

    publish.write_site(feed, meta, Path(a.out), HERE / "site")
    store.set_status("publish", {"last_built": iso(now_utc()), "jobs_live": meta["count"],
                                 "archive": meta["archive_count"], "bytes": meta["bytes"],
                                 "run_url": run_url(), "hash": meta["hash"]})
    print(json.dumps(meta))


if __name__ == "__main__":
    main()
