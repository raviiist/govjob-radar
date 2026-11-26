"""Storage: Firestore in production, an in-memory/JSON store for tests and dry runs.

Firestore collections
  jobs/{id}          master record of every vacancy (status: live | pending_review | rejected |
                     awaiting_extension_check | closed | link_broken)
  sources/{id}       crawl state per source (listing url, failures, last ok)
  seen/{id}          notice keys already processed for a source
  errors/{id}        problems for the Admin tab (deduplicated, auto-resolved on recovery)
  app_reports/{id}   problems reported by phones (written by the app)
  status/server      nightly heartbeat     status/publish   feed heartbeat
  config/settings    admin switches (auto_publish, review_new_sources)
"""
from __future__ import annotations

import copy
import json
import os
from datetime import timedelta
from pathlib import Path

from .util import iso, now_utc, sha

# Fields the admin may change from the app (must match firebase/firestore.rules).
ADMIN_FIELDS = ["status", "title", "last_date", "total_vacancies", "sector", "level", "state", "kind",
                "summary", "review_note", "reviewed_at", "admin_updated_at", "admin_locked",
                "extended", "extension_history", "pending_extension", "review_reasons", "extension_checks"]

DEFAULT_SETTINGS = {"auto_publish": True, "review_new_sources": True, "new_source_days": 7}


class BaseStore:
    def __init__(self):
        self.jobs: dict[str, dict] = {}
        self._dirty: set[str] = set()

    # ---- jobs -------------------------------------------------------------
    def load_jobs(self):
        raise NotImplementedError

    def put_job(self, job: dict):
        job["updated_at"] = iso(now_utc())
        self.jobs[job["id"]] = job
        self._dirty.add(job["id"])

    def flush(self):
        raise NotImplementedError

    # ---- errors -----------------------------------------------------------
    def flag(self, code: str, message: str, *, severity: str = "error", origin: str = "server",
             source_id: str | None = None, job_id: str | None = None, run_url: str | None = None,
             hint: str | None = None):
        key = sha(f"{origin}|{code}|{source_id or ''}|{job_id or ''}", 20)
        print(f"[{severity.upper()}] {code}{' (' + source_id + ')' if source_id else ''}: {message[:400]}", flush=True)
        self._write_error(key, {
            "origin": origin, "code": code, "message": message[:1500], "severity": severity,
            "source_id": source_id, "job_id": job_id, "run_url": run_url, "hint": hint,
            "last_at": iso(now_utc()), "resolved": False,
        })

    def resolve(self, code: str, *, origin: str = "server", source_id: str | None = None,
                job_id: str | None = None):
        key = sha(f"{origin}|{code}|{source_id or ''}|{job_id or ''}", 20)
        self._resolve_error(key)

    def _write_error(self, key: str, data: dict):
        raise NotImplementedError

    def _resolve_error(self, key: str):
        raise NotImplementedError


class MemoryStore(BaseStore):
    """Used by tests and by `--dry-run`; optionally persisted to a JSON file."""

    def __init__(self, path: str | None = None):
        super().__init__()
        self.path = Path(path) if path else None
        self.sources: dict[str, dict] = {}
        self.seen: dict[str, list] = {}
        self.errors: dict[str, dict] = {}
        self.status: dict[str, dict] = {}
        self.settings = dict(DEFAULT_SETTINGS)
        if self.path and self.path.exists():
            d = json.loads(self.path.read_text())
            self.jobs = d.get("jobs", {})
            self.sources = d.get("sources", {})
            self.seen = d.get("seen", {})
            self.errors = d.get("errors", {})
            self.status = d.get("status", {})
            self.settings.update(d.get("settings", {}))

    def load_jobs(self):
        return self.jobs

    def cleanup(self, days: int = 90):
        return 0

    def get_settings(self):
        return dict(self.settings)

    def get_source(self, sid):
        return copy.deepcopy(self.sources.get(sid, {}))

    def set_source(self, sid, data):
        self.sources.setdefault(sid, {}).update(data)

    def get_seen(self, sid):
        return set(self.seen.get(sid, []))

    def set_seen(self, sid, keys):
        self.seen[sid] = list(keys)[-3000:]

    def set_status(self, name, data):
        self.status.setdefault(name, {}).update(data)

    def get_status(self, name):
        return dict(self.status.get(name, {}))

    def _write_error(self, key, data):
        cur = self.errors.get(key)
        data["count"] = (cur.get("count", 0) + 1) if cur and not cur.get("resolved") else 1
        data["first_at"] = cur.get("first_at") if cur and not cur.get("resolved") else data["last_at"]
        self.errors[key] = data

    def _resolve_error(self, key):
        if key in self.errors and not self.errors[key].get("resolved"):
            self.errors[key]["resolved"] = True
            self.errors[key]["resolved_at"] = iso(now_utc())
            self.errors[key]["resolved_by"] = "auto"

    def flush(self):
        self._dirty.clear()
        if self.path:
            self.path.write_text(json.dumps({
                "jobs": self.jobs, "sources": self.sources, "seen": self.seen,
                "errors": self.errors, "status": self.status, "settings": self.settings,
            }, indent=1, ensure_ascii=False))


class FirestoreStore(BaseStore):
    def __init__(self, credentials_json: str | None = None):
        super().__init__()
        import firebase_admin
        from firebase_admin import credentials, firestore
        raw = credentials_json or os.environ.get("FIREBASE_SERVICE_ACCOUNT", "")
        if not raw:
            raise RuntimeError("FIREBASE_SERVICE_ACCOUNT secret is not set")
        info = json.loads(raw)
        if not firebase_admin._apps:
            firebase_admin.initialize_app(credentials.Certificate(info))
        self.db = firestore.client()
        self._fs = firestore

    def load_jobs(self):
        self.jobs = {d.id: d.to_dict() for d in self.db.collection("jobs").stream()}
        return self.jobs

    def get_settings(self):
        d = self.db.collection("config").document("settings").get()
        s = dict(DEFAULT_SETTINGS)
        if d.exists:
            s.update(d.to_dict() or {})
        else:
            self.db.collection("config").document("settings").set(s)
        return s

    def get_source(self, sid):
        d = self.db.collection("sources").document(sid).get()
        return d.to_dict() if d.exists else {}

    def set_source(self, sid, data):
        self.db.collection("sources").document(sid).set(data, merge=True)

    def get_seen(self, sid):
        d = self.db.collection("seen").document(sid).get()
        return set((d.to_dict() or {}).get("keys", [])) if d.exists else set()

    def set_seen(self, sid, keys):
        self.db.collection("seen").document(sid).set({"keys": list(keys)[-3000:]})

    def set_status(self, name, data):
        self.db.collection("status").document(name).set(data, merge=True)

    def get_status(self, name):
        d = self.db.collection("status").document(name).get()
        return d.to_dict() if d.exists else {}

    def _write_error(self, key, data):
        ref = self.db.collection("errors").document(key)
        cur = ref.get()
        cur = cur.to_dict() if cur.exists else None
        if cur and not cur.get("resolved"):
            data["count"] = cur.get("count", 1) + 1
            data["first_at"] = cur.get("first_at", data["last_at"])
        else:
            data["count"] = 1
            data["first_at"] = data["last_at"]
        ref.set(data)

    def _resolve_error(self, key):
        ref = self.db.collection("errors").document(key)
        cur = ref.get()
        if cur.exists and not (cur.to_dict() or {}).get("resolved"):
            ref.update({"resolved": True, "resolved_at": iso(now_utc()), "resolved_by": "auto"})

    def cleanup(self, days: int = 90):
        """Delete phone reports and resolved errors older than `days` (privacy policy promise)."""
        cutoff = iso(now_utc() - timedelta(days=days))
        n = 0
        for d in self.db.collection("app_reports").where("created_at", "<", cutoff).limit(400).stream():
            d.reference.delete()
            n += 1
        for d in self.db.collection("errors").where("last_at", "<", cutoff).limit(400).stream():
            if (d.to_dict() or {}).get("resolved"):
                d.reference.delete()
                n += 1
        return n

    def flush(self):
        # If the admin edited a job in the app while this run was working, keep the admin's edits.
        refs = [self.db.collection("jobs").document(j) for j in self._dirty]
        remote = {d.id: d.to_dict() for d in self.db.get_all(refs) if d.exists} if refs else {}
        batch, n = self.db.batch(), 0
        for jid in list(self._dirty):
            job = self.jobs[jid]
            r = remote.get(jid)
            if r and r.get("admin_updated_at") and r.get("admin_updated_at") != job.get("admin_updated_at"):
                for f in ADMIN_FIELDS:
                    if f in r:
                        job[f] = r[f]
            batch.set(self.db.collection("jobs").document(jid), job)
            n += 1
            if n % 400 == 0:
                batch.commit()
                batch = self.db.batch()
        batch.commit()
        self._dirty.clear()
