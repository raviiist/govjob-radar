"""The nightly run: crawl every source, read new notices, apply corrigenda/extensions,
then run the lifecycle (expiry with extension check, link health)."""
from __future__ import annotations

import re
import traceback
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta

from . import ai as ai_mod
from .extract import (CORRIGENDUM_WORDS, Link, candidate_notices, html_to_text, is_pdf_url,
                      links_of, listing_link_candidates, pdf_to_text, JOB_WORDS)
from .http import Fetcher
from .util import IST, date_mentioned, dates_in, domain_allowed, iso, now_utc, parse_date
from .verify import build_job, check, match_corrigendum

SOURCE_FAIL_NIGHTS = 3          # flag a source after this many failed nights in a row
EXTENSION_GRACE_NIGHTS = 3      # keep checking for an extension this many nights after the last date
LINK_FAIL_NIGHTS = 2            # hide a job after its official link fails this many nights in a row
REVIEW_STALE_DAYS = 3


@dataclass
class RunStats:
    sources_total: int = 0
    sources_ok: int = 0
    sources_failed: int = 0
    notices_seen: int = 0
    notices_read: int = 0
    published: int = 0
    sent_to_review: int = 0
    dropped: int = 0
    extensions: int = 0
    closed: int = 0
    links_broken: int = 0
    deferred_for_quota: int = 0
    errors: list = field(default_factory=list)


class Nightly:
    def __init__(self, store, sources_cfg: dict, gemini: ai_mod.Gemini | None, fetcher: Fetcher | None = None,
                 run_url: str | None = None, today: date | None = None):
        self.store = store
        self.cfg = sources_cfg
        self.defaults = sources_cfg.get("defaults", {})
        self.suffixes = self.defaults.get("official_suffixes", [".gov.in", ".nic.in"])
        self.ai = gemini
        self.fetch = fetcher or Fetcher()
        self.run_url = run_url
        self.today = today or datetime.now(IST).date()
        self.stats = RunStats()
        self.settings = store.get_settings()
        self.ai_stopped_reason: str | None = None

    # ------------------------------------------------------------------ helpers
    def flag(self, code, msg, **kw):
        kw.setdefault("run_url", self.run_url)
        self.store.flag(code, msg, **kw)

    def _ai_ok(self) -> bool:
        return self.ai is not None and self.ai.available and self.ai_stopped_reason is None

    def _ai_call(self, fn, *a, **kw):
        """Run an AI call; stop all AI use for the night on quota / key / model problems."""
        try:
            return fn(*a, **kw)
        except ai_mod.QuotaExhausted as e:
            self.ai_stopped_reason = "quota"
            self.flag("AI_QUOTA", f"Gemini free-tier limit reached after {self.ai.calls} calls. "
                      f"Remaining notices will be read tomorrow night. {e}", severity="warning",
                      hint="Nothing to do unless this happens every night — then lower the number of sources "
                           "or raise AI_MAX_CALLS / add billing.")
        except (ai_mod.ModelMissing, ai_mod.KeyInvalid) as e:
            self.ai_stopped_reason = e.code
            self.flag(e.code, str(e), severity="critical",
                      hint="GitHub repo > Settings > Secrets and variables > Actions: fix GEMINI_API_KEY "
                           "(secret) or GEMINI_MODEL (variable).")
        except ai_mod.AIError as e:
            self.flag("AI_ERROR", str(e), severity="warning")
        return None

    # ------------------------------------------------------------------ run
    def run(self, only: list[str] | None = None):
        started = now_utc()
        self.store.set_status("server", {"last_run_started": iso(started), "run_url": self.run_url,
                                         "state": "running"})
        self.store.load_jobs()
        sources = [s for s in self.cfg["sources"] if not only or s["id"] in only]
        self.stats.sources_total = len(sources)
        print(f"AI: key {'set (' + str(len(self.ai.key)) + ' chars)' if self.ai and self.ai.key else 'MISSING'}, "
              f"models {getattr(self.ai, 'models', '-') if self.ai else '-'}", flush=True)
        if self.ai is None or not self.ai.key:
            self.flag("AI_KEY_MISSING", "GEMINI_API_KEY is not set, new notices cannot be read.",
                      severity="critical", hint="Add the GEMINI_API_KEY secret in the GitHub repository settings.")
        else:
            self.store.resolve("AI_KEY_MISSING")

        for src in sources:
            try:
                self.crawl_source(src)
            except Exception as e:  # never let one source stop the night
                self.stats.sources_failed += 1
                self.flag("SOURCE_CRASH", f"{src['name']}: {e}\n{traceback.format_exc()[-1200:]}",
                          source_id=src["id"], severity="error")
            self.store.flush()

        self.lifecycle()
        self.store.flush()
        try:
            self.store.cleanup(90)
        except Exception as e:
            self.flag("CLEANUP_FAILED", f"Could not delete old reports: {e}", severity="info")

        if self.ai_stopped_reason is None and self.ai is not None:
            self.store.resolve("AI_QUOTA")
            self.store.resolve("AI_MODEL_NOT_FOUND")
            self.store.resolve("AI_KEY_INVALID")
        if not only:
            self.store.resolve("WORKFLOW_FAILED", origin="workflow", source_id="nightly")
        finished = now_utc()
        s = self.stats
        self.store.set_status("server", {
            "state": "ok", "last_run_finished": iso(finished), "last_success": iso(finished),
            "duration_min": round((finished - started).total_seconds() / 60, 1),
            "model": ", ".join(getattr(self.ai, "models", [self.ai.model])) if self.ai else None, "ai_calls": self.ai.calls if self.ai else 0,
            "ai_calls_by_model": dict(getattr(self.ai, "calls_by_model", {})) if self.ai else {},
            "ai_stopped": self.ai_stopped_reason,
            "stats": {k: v for k, v in s.__dict__.items() if k != "errors"},
            "fetch": dict(self.fetch.stats),
        })
        return s

    # ------------------------------------------------------------------ crawling
    def _try_listing(self, url: str):
        f = self.fetch.get(url)
        if not f.ok or f.is_pdf:
            return f, [], []
        links = links_of(f.text(), f.final_url or url)
        cands = candidate_notices(links, self.today, self.defaults.get("max_age_days", 120))
        return f, links, cands

    def crawl_source(self, src: dict):
        sid = src["id"]
        st = self.store.get_source(sid)
        if not st.get("added_at"):
            st["added_at"] = iso(now_utc())
            self.store.set_source(sid, {"added_at": st["added_at"], "name": src["name"]})
        order, seen_urls = [], set()
        for u in [st.get("listing_url")] + list(src.get("hints", [])) + [src["home"]]:
            if u and u not in seen_urls:
                order.append(u)
                seen_urls.add(u)

        used_url, cands, home_fetch, last_fetch = None, [], None, None
        for u in order:
            f, links, c = self._try_listing(u)
            last_fetch = f
            if u == src["home"]:
                home_fetch = (f, links)
            if c:
                used_url, cands = u, c
                break

        rediscovered = False
        if not cands:
            if home_fetch is None:
                f, links, _ = self._try_listing(src["home"])
                home_fetch = (f, links)
            hf, hlinks = home_fetch
            if hf.ok:
                options = [l for l in listing_link_candidates(hlinks)
                           if domain_allowed(l.url, src["home"], src.get("domains", []), self.suffixes)]
                for l in options[:6]:
                    if l.url in seen_urls:
                        continue
                    seen_urls.add(l.url)
                    f, _, c = self._try_listing(l.url)
                    if c:
                        used_url, cands, rediscovered = l.url, c, True
                        break
                # (The AI is not used here: its free daily allowance is kept for reading notices.)

        if not cands:
            fails = int(st.get("fail_count", 0)) + 1
            reason = (home_fetch[0].error if home_fetch and not home_fetch[0].ok else
                      "website opened, but no recruitment notices could be found on it")
            code = (home_fetch[0].error_code if home_fetch and not home_fetch[0].ok else "NO_NOTICES")
            self.store.set_source(sid, {"fail_count": fails, "last_error": reason, "last_error_code": code,
                                        "last_checked": iso(now_utc())})
            self.stats.sources_failed += 1
            print(f"source {sid}: FAILED ({code}) {reason[:200]}", flush=True)
            if fails >= SOURCE_FAIL_NIGHTS:
                hint = {
                    "BLOCKED": "The site refuses GitHub's servers (often blocks foreign IPs). Consider a "
                               "self-hosted runner in India (see SETUP.md) or remove the source.",
                    "DNS": "The domain no longer exists — the home URL in server/config/sources.yaml is wrong or changed.",
                    "NO_NOTICES": "The site layout may have changed or it has no current vacancies. Open the site "
                                  "and, if there is a careers page, add it to 'hints' for this source.",
                }.get(code, "Check that the site opens in a browser; if it moved, update 'home' in sources.yaml.")
                self.flag("SOURCE_FAILING", f"{src['name']}: failing for {fails} nights — {reason}",
                          source_id=sid, severity="error" if code != "NO_NOTICES" else "warning", hint=hint)
            return

        self.stats.sources_ok += 1
        print(f"source {sid}: ok, {len(cands)} notices on {used_url}", flush=True)
        self.store.set_source(sid, {"listing_url": used_url, "fail_count": 0, "last_ok": iso(now_utc()),
                                    "last_checked": iso(now_utc()), "last_candidates": len(cands),
                                    "last_error": None, "tls_problem": bool(last_fetch and last_fetch.tls_fallback)})
        self.store.resolve("SOURCE_FAILING", source_id=sid)
        if rediscovered and st.get("listing_url"):
            self.flag("SOURCE_MOVED", f"{src['name']}: recruitment page moved from {st.get('listing_url')} to "
                      f"{used_url}. Found automatically — no action needed.", source_id=sid, severity="info")

        seen = self.store.get_seen(sid)
        new = [c for c in cands if c.key not in seen]
        self.stats.notices_seen += len(new)
        limit = int(self.defaults.get("max_new_notices_per_source", 20))
        source_is_new = self._source_is_new(st)
        for link in new[:limit]:
            if not self._ai_ok():
                self.stats.deferred_for_quota += 1
                continue
            done = self.process_notice(src, link, used_url, source_is_new)
            if done:
                seen.add(link.key)
        self.store.set_seen(sid, seen)

    def _source_is_new(self, st: dict) -> bool:
        try:
            added = datetime.fromisoformat(st.get("added_at"))
        except (TypeError, ValueError):
            return True
        return (now_utc() - added).days < int(self.settings.get("new_source_days", 7))

    # ------------------------------------------------------------------ one notice
    def _read_document(self, url: str, src: dict):
        """Returns (notice_url, text, pdf_bytes_if_scanned, error)."""
        f = self.fetch.get(url)
        if not f.ok:
            return url, "", None, f.error or "could not open"
        if f.is_pdf:
            text = pdf_to_text(f.content)
            if len(text) < 200:
                return f.final_url or url, "", (f.content if len(f.content) < 8_000_000 else None), ""
            return f.final_url or url, text, None, ""
        html = f.text()
        text = html_to_text(html)
        if len(text) < 600:
            # Page is only a wrapper around the real PDF notice: follow it.
            for l in links_of(html, f.final_url or url):
                if is_pdf_url(l.url) and JOB_WORDS.search(l.text + " " + l.context) and \
                        domain_allowed(l.url, src["home"], src.get("domains", []), self.suffixes):
                    return self._read_document(l.url, src)
        return f.final_url or url, text, None, ""

    def process_notice(self, src: dict, link: Link, page_url: str, source_is_new: bool) -> bool:
        """Returns True when the notice is finished with (do not read it again)."""
        if not domain_allowed(link.url, src["home"], src.get("domains", []), self.suffixes):
            self.stats.dropped += 1
            return True
        notice_url, text, scanned_pdf, err = self._read_document(link.url, src)
        if err:
            return False   # try again tomorrow
        if not text and scanned_pdf is None:
            return True
        # Save AI calls: a notice whose every date is already in the past has expired.
        ds = [d for d in dates_in((text or "")[:20000]) if d.year >= 2000]
        if text and ds and max(ds) < self.today:
            self.stats.dropped += 1
            return True
        self.stats.notices_read += 1
        data = self._ai_call(self.ai.extract_notice, text=text or None, pdf_bytes=scanned_pdf, source=src,
                             url=notice_url, link_text=link.text)
        if data is None:
            return False
        is_corr = data.get("doc_type") == "corrigendum" or (
            CORRIGENDUM_WORDS.search(link.text) and data.get("doc_type") not in ("recruitment", "deputation"))
        if is_corr:
            self.apply_corrigendum(src, data, notice_url, text, scanned_pdf is not None, page_url, link)
            return True

        job = build_job(data, source=src, notice_url=notice_url, page_url=page_url, link_text=link.text)
        decision, reasons = check(job, data, doc_text=text, scanned=scanned_pdf is not None, source=src,
                                  suffixes=self.suffixes, today=self.today, settings=self.settings,
                                  source_is_new=source_is_new)
        if decision == "drop":
            self.stats.dropped += 1
            return True
        existing = self.store.jobs.get(job["id"])
        if existing:
            # Same advertisement listed again (e.g. on another page). Only a later date matters.
            if job["last_date"] and existing.get("last_date") and job["last_date"] > existing["last_date"] \
                    and decision == "publish":
                self._extend(existing, job["last_date"], notice_url, "re-published notice shows a later date")
            return True
        job["status"] = "live" if decision == "publish" else "pending_review"
        job["review_reasons"] = reasons
        self.store.put_job(job)
        if decision == "publish":
            self.stats.published += 1
        else:
            self.stats.sent_to_review += 1
        return True

    # ------------------------------------------------------------------ extensions
    def _extend(self, job: dict, new_date: str, notice_url: str, why: str):
        hist = list(job.get("extension_history") or [])
        hist.append({"from": job.get("last_date"), "to": new_date, "notice_url": notice_url,
                     "found_at": iso(now_utc()), "why": why})
        job.update({"last_date": new_date, "extended": True, "extension_history": hist,
                    "extension_checks": 0, "pending_extension": None})
        if job.get("status") in ("awaiting_extension_check", "closed"):
            job["status"] = "live"
            job["closed_at"] = None
        self.store.put_job(job)
        self.stats.extensions += 1

    def apply_corrigendum(self, src, data, notice_url, text, scanned, page_url, link):
        c = data.get("corrigendum") or {}
        new_date = parse_date(c.get("new_last_date") or data.get("last_date"))
        target, score = match_corrigendum(data, src["id"], self.store.jobs)
        if target and new_date:
            old = parse_date(target.get("last_date"))
            verified = (not scanned) and date_mentioned(text, new_date)
            if old and new_date > old:
                if score >= 0.9 and verified and self.settings.get("auto_publish", True) \
                        and target.get("status") != "pending_review":
                    self._extend(target, new_date.isoformat(), notice_url, "corrigendum")
                else:
                    target["pending_extension"] = {"to": new_date.isoformat(), "notice_url": notice_url,
                                                   "match_score": round(score, 2), "verified": verified}
                    rs = list(target.get("review_reasons") or [])
                    rs.append(f"Possible date extension to {new_date.isoformat()} (corrigendum, match "
                              f"{score:.0%}{'' if verified else ', date not cross-checked'})")
                    target["review_reasons"] = rs
                    self.store.put_job(target)
                    self.stats.sent_to_review += 1
            return
        # Corrigendum for something we do not have: show it to the admin as a normal notice.
        if new_date and new_date >= self.today:
            data = dict(data, doc_type="recruitment", last_date=new_date.isoformat())
            job = build_job(data, source=src, notice_url=notice_url, page_url=page_url, link_text=link.text)
            if job["id"] not in self.store.jobs:
                job["review_reasons"] = ["Corrigendum for an advertisement not in our list — check before publishing"]
                self.store.put_job(job)
                self.stats.sent_to_review += 1

    def _apply_page_shows_later_date(self, job: dict) -> str | None:
        """Look at the apply page for a 'last date' later than ours (common silent extension)."""
        url = job.get("apply_url") or ""
        if not url or is_pdf_url(url):
            return None
        src = next((s for s in self.cfg["sources"] if s["id"] == job["source_id"]), None)
        if not src or not domain_allowed(url, src["home"], src.get("domains", []), self.suffixes):
            return None
        f = self.fetch.get(url)
        if not f.ok or f.is_pdf:
            return None
        cur = parse_date(job.get("last_date"))
        best = None
        for line in html_to_text(f.text()).splitlines():
            if re.search(r"last\s*date|closing date|extended|अंतिम तिथि", line, re.I):
                for d in dates_in(line):
                    if cur and d > cur and (d - self.today).days < 120 and (best is None or d > best):
                        best = d
        return best.isoformat() if best else None

    # ------------------------------------------------------------------ lifecycle
    def lifecycle(self):
        for job in list(self.store.jobs.values()):
            status = job.get("status")
            ld = parse_date(job.get("last_date"))

            # Expired items still waiting for review: no longer useful.
            if status == "pending_review" and ld and ld < self.today:
                job["status"] = "closed"
                job["closed_at"] = iso(now_utc())
                job["review_reasons"] = (job.get("review_reasons") or []) + ["Closed: last date passed before review"]
                self.store.put_job(job)
                continue

            if status in ("live", "awaiting_extension_check") and ld and ld < self.today:
                # Before removing, check for an extension (corrigenda were already read tonight).
                later = self._apply_page_shows_later_date(job)
                if later and not job.get("pending_extension"):
                    job["pending_extension"] = {"to": later, "notice_url": job.get("apply_url"),
                                                "match_score": 1.0, "verified": False}
                    job["review_reasons"] = (job.get("review_reasons") or []) + [
                        f"Apply page shows a later last date ({later}) — confirm extension"]
                job["status"] = "awaiting_extension_check"
                job["extension_checks"] = int(job.get("extension_checks", 0)) + 1
                if job["extension_checks"] > EXTENSION_GRACE_NIGHTS and not job.get("pending_extension"):
                    job["status"] = "closed"
                    job["closed_at"] = iso(now_utc())
                    self.stats.closed += 1
                self.store.put_job(job)
                continue

            if status in ("live", "awaiting_extension_check", "link_broken"):
                self._check_link(job)

        stale = [j for j in self.store.jobs.values()
                 if (j.get("status") == "pending_review" or j.get("pending_extension"))
                 and j.get("first_seen")
                 and (now_utc() - datetime.fromisoformat(j["first_seen"])).days >= REVIEW_STALE_DAYS]
        if stale:
            self.flag("REVIEW_WAITING", f"{len(stale)} item(s) have been waiting in Review for "
                      f"{REVIEW_STALE_DAYS}+ days. Users cannot see them until approved.", severity="info")
        else:
            self.store.resolve("REVIEW_WAITING")

    def _check_link(self, job: dict):
        f = self.fetch.get(job["notice_url"], head_only=True)
        if f.ok:
            job["link_fail_count"] = 0
            job["verified_at"] = iso(now_utc())
            if job.get("status") == "link_broken":
                ld = parse_date(job.get("last_date"))
                job["status"] = "live" if not ld or ld >= self.today else "awaiting_extension_check"
                self.store.resolve("LINK_BROKEN", job_id=job["id"], source_id=job["source_id"])
            self.store.put_job(job)
            return
        job["link_fail_count"] = int(job.get("link_fail_count", 0)) + 1
        if job["link_fail_count"] >= LINK_FAIL_NIGHTS and job.get("status") != "link_broken":
            job["status"] = "link_broken"
            self.stats.links_broken += 1
            self.flag("LINK_BROKEN", f"Hidden from users: official link of '{job['title']}' "
                      f"({job['source_name']}) failed {job['link_fail_count']} nights — {f.error}. "
                      f"It comes back automatically if the link works again.",
                      job_id=job["id"], source_id=job["source_id"], severity="warning")
        self.store.put_job(job)
