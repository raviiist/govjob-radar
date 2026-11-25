#!/usr/bin/env python3
"""Called by a workflow step when the job itself failed (install error, crash, timeout).

Writes a WORKFLOW_FAILED item to Admin > Errors. If even this cannot run (for example
the Python packages failed to install), the Admin > Health tab still turns red because
the nightly heartbeat goes stale.
"""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))


def main():
    name = sys.argv[1] if len(sys.argv) > 1 else "workflow"
    url = (f"{os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/{os.environ.get('GITHUB_REPOSITORY')}"
           f"/actions/runs/{os.environ.get('GITHUB_RUN_ID')}")
    try:
        from govjob.store import FirestoreStore
        from govjob.util import iso, now_utc
        store = FirestoreStore()
        store.flag("WORKFLOW_FAILED", f"The '{name}' GitHub job failed. Open the run to see the log.",
                   severity="critical", origin="workflow", source_id=name, run_url=url,
                   hint="Usually a package/version problem or an expired secret. The log's last red step shows why.")
        if name == "nightly":
            store.set_status("server", {"state": "failed", "last_failed": iso(now_utc()), "run_url": url})
    except Exception as e:
        print(f"could not report failure: {e}")


if __name__ == "__main__":
    main()
