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
from .http import Fetched, Fetcher
from .util import IST, date_mentioned, dates_in, domain_allowed, iso, now_utc, parse_date
from .verify import build_job, check, match_corrigendum, reasons_block_extension

RELATIVE_DEADLINE = re.compile(r"within\s+(\d+|thirty|forty[- ]five|sixty|fifteen|twenty[- ]one)\s+days|"
                               r"(\d+|thirty|sixty)\s+days\s+(from|of|after)\s+the\s+date", re.I)
NO_DATE_CLOSE_DAYS = 75         # a job with no last date at all is closed this long after we first saw it


@dataclass
class Bundle:
    notice_url: str
    detail_url: str | None = None
    docs: list = field(default_factory=list)
    parts: list = field(default_factory=list)
    scanned: list = field(default_factory=list)
    error: str = ""

    @property
    def text(self) -> str:
        return "\n\n".join(self.parts)


SOURCE_FAIL_NIGHTS = 3
BLOCKED_AFTER = 7               # nights of refusal before a site is only tried weekly
REFUSING_CODES = ("BLOCKED", "TIMEOUT", "CONNECT", "TLS")          # flag a source after this many failed nights in a row
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
                 run_url: str | None = None, today: date | None = None, renderer=None):
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
        self.renderer = renderer          # headless browser for JavaScript sites (optional)

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
                                         "state": "running", "sources_configured": len(self.cfg["sources"])})
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
            "state": "ok", "last_run_finished": iso(finished), **({} if only else {"last_success": iso(finished)}),
            "duration_min": round((finished - started).total_seconds() / 60, 1),
            "model": ", ".join(getattr(self.ai, "models", [self.ai.model])) if self.ai else None, "ai_calls": self.ai.calls if self.ai else 0,
            "ai_calls_by_model": dict(getattr(self.ai, "calls_by_model", {})) if self.ai else {},
            "ai_stopped": self.ai_stopped_reason,
            "stats": {k: v for k, v in s.__dict__.items() if k != "errors"},
            "fetch": dict(self.fetch.stats),
        })
        return s

    # ------------------------------------------------------------------ crawling
    def _try_listing(self, url: str, render: bool = False):
        if render:
            r = self.renderer.html(url) if self.renderer else None
            if r is None:
                return Fetched(url=url, error="could not render", error_code="RENDER"), [], []
            final, html = r
            f = Fetched(url=url, final_url=final, status=200, content_type="text/html", content=html.encode())
        else:
            f = self.fetch.get(url)
        if not f.ok or f.is_pdf:
            return f, [], []
        links = links_of(f.text(), f.final_url or url)
        cands = candidate_notices(links, self.today, self.defaults.get("max_age_days", 120))
        return f, links, cands

    def crawl_source(self, src: dict):
        sid = src["id"]
        st = self.store.get_source(sid)
        # A site that has refused us for a week is tried only once a week (it is not going to change daily).
        fails = int(st.get("fail_count", 0))
        if fails >= BLOCKED_AFTER and st.get("last_error_code") in REFUSING_CODES and \
                (self.today.toordinal() % 7) != (sum(map(ord, sid)) % 7):
            self.stats.sources_failed += 1
            print(f"source {sid}: skipped tonight (refuses our server; retried weekly)", flush=True)
            return
        if not st.get("added_at"):
            st["added_at"] = iso(now_utc())
            self.store.set_source(sid, {"added_at": st["added_at"], "name": src["name"]})
        order, seen_urls = [], set()
        for u in (list(st.get("listing_urls") or [st.get("listing_url")]) + list(src.get("hints", []))
                  + list(st.get("extra_pages") or []) + [src["home"]]):
            if u and u not in seen_urls:
                order.append(u)
                seen_urls.add(u)

        # Collect notices from several pages: saved listing pages, hints, the home page, and pages the
        # home page links to (careers / recruitment / vacancies / deputation / circulars ...).
        cands, pages_ok, home_fetch, last_fetch, by_key = [], [], None, None, {}
        max_pages = int(self.defaults.get("max_listing_pages", 6))

        def take(url, render=False):
            nonlocal last_fetch
            f, links, c = self._try_listing(url, render)
            last_fetch = f
            if c:
                pages_ok.append(url)
                for l in c:
                    if l.key not in by_key:
                        by_key[l.key] = (l, url)
            return f, links

        for u in order:
            if len(pages_ok) >= max_pages:
                break
            f, links = take(u)
            if u == src["home"]:
                home_fetch = (f, links)
        if home_fetch is None:
            f, links = take(src["home"])
            home_fetch = (f, links)
        hf, hlinks = home_fetch
        rediscovered = False
        if hf.ok:
            options = [l for l in listing_link_candidates(hlinks)
                       if domain_allowed(l.url, src["home"], src.get("domains", []), self.suffixes)]
            for l in options[:8]:
                if len(pages_ok) >= max_pages:
                    break
                if l.url in seen_urls:
                    continue
                seen_urls.add(l.url)
                before = len(pages_ok)
                take(l.url)
                if len(pages_ok) > before and l.url not in (st.get("listing_urls") or []):
                    rediscovered = True
        # Site opened but nothing found: it probably builds its pages with JavaScript. Read it with the
        # headless browser (also straight away on later nights once we know the site needs it).
        used_js = False
        if (not by_key and hf.ok) or st.get("needs_js"):
            if self.renderer and self.renderer.available:
                used_js = True
                rendered = 0
                for u in order:
                    if rendered >= 4 or len(pages_ok) >= max_pages:
                        break
                    rendered += 1
                    f, links = take(u, render=True)
                    if u == src["home"] and links:
                        for l in listing_link_candidates(links)[:6]:
                            if rendered >= 6 or len(pages_ok) >= max_pages:
                                break
                            if l.url in seen_urls or not domain_allowed(l.url, src["home"], src.get("domains", []),
                                                                         self.suffixes):
                                continue
                            seen_urls.add(l.url)
                            rendered += 1
                            take(l.url, render=True)
            elif self.renderer is not None and not st.get("needs_js"):
                self.flag("RENDER_UNAVAILABLE", f"Headless browser not available, JavaScript sites cannot be read: "
                          f"{self.renderer.error}", severity="warning",
                          hint="Run the server setup script again (installs the browser's system libraries).")
        cands = [v[0] for v in by_key.values()]
        page_of = {k: v[1] for k, v in by_key.items()}
        used_url = pages_ok[0] if pages_ok else None
        if used_js and cands:
            self.store.set_source(sid, {"needs_js": True})

        if not cands:
            fails = int(st.get("fail_count", 0)) + 1
            reason = (home_fetch[0].error if home_fetch and not home_fetch[0].ok else
                      "website opened, but no recruitment notices could be found on it")
            code = (home_fetch[0].error_code if home_fetch and not home_fetch[0].ok else "NO_NOTICES")
            self.store.set_source(sid, {"fail_count": fails, "last_error": reason, "last_error_code": code,
                                        "last_checked": iso(now_utc())})
            self.stats.sources_failed += 1
            print(f"source {sid}: FAILED ({code}) {reason[:200]}", flush=True)
            if fails >= SOURCE_FAIL_NIGHTS and code in REFUSING_CODES:
                self.flag("SOURCE_REFUSES", f"{src['name']}: the website refuses automated access ({reason}). "
                          f"Its vacancies usually also appear in Employment News / DoPT / ministry circular pages, "
                          f"which are crawled. It is retried once a week.", source_id=sid, severity="info",
                          hint="Nothing to do. If you never want it retried, delete it from server/config/sources.yaml.")
                self.store.resolve("SOURCE_FAILING", source_id=sid)
            elif fails >= SOURCE_FAIL_NIGHTS:
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
        print(f"source {sid}: ok, {len(cands)} notices on {len(pages_ok)} page(s): {pages_ok}", flush=True)
        self.store.set_source(sid, {"listing_url": used_url, "listing_urls": pages_ok, "fail_count": 0, "last_ok": iso(now_utc()),
                                    "last_checked": iso(now_utc()), "last_candidates": len(cands),
                                    "last_error": None, "tls_problem": bool(last_fetch and last_fetch.tls_fallback)})
        self.store.resolve("SOURCE_FAILING", source_id=sid)
        self.store.resolve("SOURCE_REFUSES", source_id=sid)
        if rediscovered and st.get("listing_urls"):
            self.flag("SOURCE_MOVED", f"{src['name']}: found a new vacancies page on the site: {pages_ok}. "
                      f"Picked up automatically — no action needed.", source_id=sid, severity="info")

        seen = self.store.get_seen(sid)
        new = [c for c in cands if c.key not in seen]
        # Read the most important first when the free AI allowance is tight: deputation, extensions, newest.
        new.sort(key=lambda l: (-bool(re.search(r"deputation|प्रतिनियुक्ति", f"{l.text} {l.context}", re.I))
                                - bool(CORRIGENDUM_WORDS.search(l.text)),
                                -max([d.toordinal() for d in dates_in(l.context)] or [0])))
        self.stats.notices_seen += len(new)
        limit = int(self.defaults.get("max_new_notices_per_source", 20))
        source_is_new = self._source_is_new(st)
        for link in new[:limit]:
            if not self._ai_ok():
                self.stats.deferred_for_quota += 1
                continue
            done = self.process_notice(src, link, page_of.get(link.key, used_url), source_is_new)
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
    def _read_document(self, url: str, src: dict) -> Bundle:
        """Read a notice. If it is an HTML page, also read the PDFs on it (advertisement, extension,
        corrigendum ...) so the AI sees the whole story, e.g. a later extension circular."""
        b = Bundle(notice_url=url)
        f = self.fetch.get(url)
        if not f.ok:
            b.error = f.error or "could not open"
            return b
        if f.is_pdf:
            b.notice_url = f.final_url or url
            b.docs = [b.notice_url]
            self._add_pdf(b, "Notice", b.notice_url, f.content)
            return b
        page_url = f.final_url or url
        html = f.text()
        page_text = html_to_text(html)
        if len(page_text) < 300 and self.renderer and self.renderer.available:
            r = self.renderer.html(page_url)
            if r:
                page_url, html = r
                page_text = html_to_text(html)
        pdfs = []
        for l in links_of(html, page_url):
            if not is_pdf_url(l.url) or l.url in [p.url for p in pdfs]:
                continue
            if not domain_allowed(l.url, src["home"], src.get("domains", []), self.suffixes):
                continue
            pdfs.append(l)
        # A detail page has few PDFs - read them all; on a busy page keep only vacancy-related ones.
        if len(pdfs) > 4:
            pdfs = [l for l in pdfs if JOB_WORDS.search(f"{l.text} {l.context} {l.url}")]
        pdfs = pdfs[:4]
        b.parts.append(f"=== Notice page: {page_url} ===\n{page_text[:4000]}")
        b.detail_url = page_url if pdfs else None
        b.docs = [p.url for p in pdfs]
        main = next((p for p in pdfs if re.search(r"advert|advt|notification|vacancy circular|bilingual|recruitment",
                                                   f"{p.text} {p.url}", re.I)
                     and not CORRIGENDUM_WORDS.search(p.text)), pdfs[0] if pdfs else None)
        b.notice_url = main.url if main else page_url
        for p in pdfs:
            pf = self.fetch.get(p.url)
            if pf.ok:
                self._add_pdf(b, p.text or "Document", p.url, pf.content)
        return b

    @staticmethod
    def _add_pdf(b: "Bundle", label: str, url: str, content: bytes):
        text = pdf_to_text(content)
        if len(text) >= 200:
            b.parts.append(f"=== {label[:80]} ({url}) ===\n{text[:14000]}")
        elif len(content) < 8_000_000 and len(b.scanned) < 2:
            b.scanned.append(content)
            b.parts.append(f"=== {label[:80]} ({url}) === (scanned PDF attached)")

    def process_notice(self, src: dict, link: Link, page_url: str, source_is_new: bool) -> bool:
        """Returns True when the notice is finished with (do not read it again)."""
        if not domain_allowed(link.url, src["home"], src.get("domains", []), self.suffixes):
            self.stats.dropped += 1
            return True
        b = self._read_document(link.url, src)
        if b.error:
            return False   # try again tomorrow
        if not b.parts:
            return True
        text = b.text
        notice_url = b.notice_url
        scanned_pdf = b.scanned or None
        # Save AI calls: skip a notice whose every date is in the past - unless its deadline is relative
        # ("within 30 days of issue") or it mentions an extension.
        ds = [d for d in dates_in(text[:40000]) if d.year >= 2000]
        if not b.scanned and ds and max(ds) < self.today and not RELATIVE_DEADLINE.search(text) \
                and not CORRIGENDUM_WORDS.search(text):
            self.stats.dropped += 1
            return True
        self.stats.notices_read += 1
        data = self._ai_call(self.ai.extract_notice, text=text, pdf_bytes=b.scanned, source=src,
                             url=notice_url, link_text=link.text)
        if data is None:
            return False
        is_corr = data.get("doc_type") == "corrigendum" or (
            CORRIGENDUM_WORDS.search(link.text) and data.get("doc_type") not in ("recruitment", "deputation"))
        if is_corr:
            self.apply_corrigendum(src, data, notice_url, text, bool(b.scanned), page_url, link)
            return True

        job = build_job(data, source=src, notice_url=notice_url, page_url=page_url, link_text=link.text)
        job["detail_url"] = b.detail_url
        job["docs"] = b.docs
        decision, reasons = check(job, data, doc_text=text, scanned=bool(b.scanned), source=src,
                                  suffixes=self.suffixes, today=self.today, settings=self.settings,
                                  source_is_new=source_is_new)
        if decision == "drop":
            self.stats.dropped += 1
            print(f"  skipped: {job['title'][:90]} | {'; '.join(reasons)}", flush=True)
            self._maybe_listing_page(src, b, link)
            return True
        existing = self.store.jobs.get(job["id"])
        if existing:
            # Same advertisement listed again (e.g. "extension" entry linking to the same page).
            if job["last_date"] and (not existing.get("last_date") or job["last_date"] > existing["last_date"]):
                if decision == "publish" or (existing.get("last_date") and not reasons_block_extension(reasons)):
                    self._extend(existing, job["last_date"], job.get("extension_doc") or notice_url,
                                 "extension found on the notice page")
                else:
                    self._pending_extension(existing, job["last_date"], job.get("extension_doc") or notice_url,
                                            1.0, False, "listed again with a later last date")
            if b.detail_url and not existing.get("detail_url"):
                existing.update({"detail_url": b.detail_url, "docs": b.docs})
                self.store.put_job(existing)
            return True
        job["status"] = "live" if decision == "publish" else "pending_review"
        job["review_reasons"] = reasons
        print(f"  {'LIVE' if decision == 'publish' else 'REVIEW'}: {job['title'][:90]} | last date {job['last_date']}"
              f"{' (extended)' if job.get('extended') else ''}{' | ' + '; '.join(reasons)[:200] if reasons else ''}",
              flush=True)
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

    def _maybe_listing_page(self, src: dict, b: "Bundle", link: Link):
        """A 'notice' that is really a page listing several notices (e.g. "CEN Notifications List"):
        remember it as an extra listing page so its notices are read from now on."""
        if not b.detail_url or not re.search(r"list|notices|notifications|advertisements|portal|vacancies|recruitments",
                                             link.text, re.I):
            return
        st = self.store.get_source(src["id"])
        extra = list(st.get("extra_pages") or [])
        if b.detail_url not in extra:
            extra = (extra + [b.detail_url])[-8:]
            self.store.set_source(src["id"], {"extra_pages": extra})
            print(f"  -> will also read notices listed on {b.detail_url}", flush=True)

    def _pending_extension(self, target: dict, new_date: str, notice_url: str, score: float, verified: bool,
                           why: str):
        target["pending_extension"] = {"to": new_date, "notice_url": notice_url,
                                       "match_score": round(score, 2), "verified": verified}
        rs = [r for r in (target.get("review_reasons") or []) if not r.startswith("Possible date extension")]
        rs.append(f"Possible date extension to {new_date} ({why}, match {score:.0%}"
                  f"{'' if verified else ', date not cross-checked'})")
        target["review_reasons"] = rs
        self.store.put_job(target)
        self.stats.sent_to_review += 1

    def apply_corrigendum(self, src, data, notice_url, text, scanned, page_url, link, target=None, score=None):
        c = data.get("corrigendum") or {}
        new_date = parse_date(c.get("new_last_date") or data.get("last_date"))
        if target is None:
            target, score = match_corrigendum(data, src["id"], self.store.jobs)
        if target and new_date:
            old = parse_date(target.get("last_date"))
            verified = (not scanned) and date_mentioned(text, new_date)
            if not old or new_date > old:
                if score >= 0.9 and verified and self.settings.get("auto_publish", True) \
                        and target.get("status") != "pending_review":
                    self._extend(target, new_date.isoformat(), notice_url, "corrigendum")
                else:
                    self._pending_extension(target, new_date.isoformat(), notice_url, score, verified, "corrigendum")
            return
        # Corrigendum for something we do not have: show it to the admin as a normal notice.
        if new_date and new_date >= self.today:
            data = dict(data, doc_type="recruitment", last_date=new_date.isoformat())
            job = build_job(data, source=src, notice_url=notice_url, page_url=page_url, link_text=link.text)
            if job["id"] not in self.store.jobs:
                job["review_reasons"] = ["Corrigendum for an advertisement not in our list — check before publishing"]
                self.store.put_job(job)
                self.stats.sent_to_review += 1

    def _watch_detail_page(self, job: dict):
        """Re-open a job's own notice page; if a new PDF (extension / corrigendum) appeared, read it."""
        url = job.get("detail_url")
        if not url or not self._ai_ok():
            return
        src = next((s for s in self.cfg["sources"] if s["id"] == job.get("source_id")), None)
        if not src:
            return
        f = self.fetch.get(url)
        if not f.ok or f.is_pdf:
            return
        pdfs = [l.url for l in links_of(f.text(), f.final_url or url) if is_pdf_url(l.url)
                and domain_allowed(l.url, src["home"], src.get("domains", []), self.suffixes)]
        known = set(job.get("docs") or [])
        if not [u for u in pdfs if u not in known]:
            return
        b = self._read_document(url, src)
        if b.error:
            return
        self.stats.notices_read += 1
        data = self._ai_call(self.ai.extract_notice, text=b.text, pdf_bytes=b.scanned, source=src,
                             url=b.notice_url, link_text=job.get("title", ""))
        if data is None:
            return
        job["docs"] = b.docs
        self.store.put_job(job)
        new_date = data.get("last_date") or (data.get("corrigendum") or {}).get("new_last_date")
        if new_date:
            ext_doc = data.get("extension_doc_url") or next((u for u in b.docs if u not in known), b.notice_url)
            self.apply_corrigendum(src, dict(data, last_date=new_date), ext_doc, b.text, bool(b.scanned),
                                   job.get("page_url"), Link(url, job.get("title", ""), ""), target=job, score=1.0)

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
            # Re-check the source of every open job against the current trusted-domain rule.
            if status in ("live", "awaiting_extension_check", "pending_review"):
                src = next((x for x in self.cfg["sources"] if x["id"] == job.get("source_id")), None)
                if src and not domain_allowed(job.get("notice_url", ""), src["home"], src.get("domains", []),
                                              self.suffixes):
                    job["status"] = "pending_review"
                    job["review_reasons"] = ["Notice is not on a government-only domain — check that the website "
                                             f"is the real official one: {job.get('notice_url')}"]
                    self.store.put_job(job)
                    continue
            # New extension / corrigendum PDFs on the job's own page (checked before expiry decisions).
            if status in ("live", "awaiting_extension_check", "pending_review") and job.get("detail_url"):
                try:
                    self._watch_detail_page(job)
                except Exception as e:
                    self.flag("WATCH_FAILED", f"{job.get('title')}: {e}", job_id=job["id"],
                              source_id=job.get("source_id"), severity="info")
                status = job.get("status")
            ld = parse_date(job.get("last_date"))

            # No last date at all (e.g. "30 days from publication"): close after a fixed time.
            if not ld and status in ("live", "pending_review") and job.get("first_seen"):
                try:
                    age = (now_utc() - datetime.fromisoformat(job["first_seen"])).days
                except ValueError:
                    age = 0
                if age > NO_DATE_CLOSE_DAYS:
                    job["status"] = "closed"
                    job["closed_at"] = iso(now_utc())
                    self.store.put_job(job)
                    continue

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
        if job.get("status") == "link_broken" and f.error_code not in ("HTTP_404", "HTTP_410", "DNS"):
            f.error, f.status = "", 200     # hidden earlier only because the site refused our server
        if f.ok:
            job["link_fail_count"] = 0
            job["verified_at"] = iso(now_utc())
            if job.get("status") == "link_broken":
                ld = parse_date(job.get("last_date"))
                job["status"] = "live" if not ld or ld >= self.today else "awaiting_extension_check"
                self.store.resolve("LINK_BROKEN", job_id=job["id"], source_id=job["source_id"])
            self.store.put_job(job)
            return
        if f.error_code not in ("HTTP_404", "HTTP_410", "DNS"):
            # Refused / timed out / server error: the site may only be blocking our server. Not proof the
            # link is dead, so keep the job visible.
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
