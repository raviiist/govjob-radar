"""Turn AI output into a job record and decide: publish, send to review, or drop.

A job is auto-published only when every check passes; otherwise it waits in
Admin > Review with the exact reasons listed.
"""
from __future__ import annotations

from datetime import date

from .ai import QUALS, SECTORS
from .util import date_mentioned, domain_allowed, iso, norm_advt, norm_for_match, norm_space, now_utc, \
    parse_date, sha

MIN_CONFIDENCE = 0.75


def _int(v):
    try:
        if v is None or v == "":
            return None
        return int(str(v).replace(",", "").strip())
    except (ValueError, TypeError):
        return None


def _date(v) -> str | None:
    d = parse_date(v) if v else None
    return d.isoformat() if d else None


def job_id(source_id: str, advt_no: str | None, notice_url: str, title: str) -> str:
    key = norm_advt(advt_no) if advt_no and len(norm_advt(advt_no)) >= 3 else notice_url
    return sha(f"{source_id}|{key}|{'' if advt_no else title.lower()}", 16)


def build_job(ai: dict, *, source: dict, notice_url: str, page_url: str, link_text: str) -> dict:
    posts = []
    for p in ai.get("posts") or []:
        if not isinstance(p, dict) or not p.get("name"):
            continue
        posts.append({
            "name": norm_space(p.get("name"))[:200],
            "vacancies": _int(p.get("vacancies")),
            "pay_level": _int(p.get("pay_level")) if _int(p.get("pay_level")) and 1 <= _int(p.get("pay_level")) <= 18 else None,
            "pay_text": norm_space(p.get("pay_text"))[:160] or None,
            "qualification": norm_space(p.get("qualification"))[:300] or None,
        })
    levels = [p["pay_level"] for p in posts if p["pay_level"]]
    total = _int(ai.get("total_vacancies"))
    if total is None and posts and all(p["vacancies"] is not None for p in posts):
        total = sum(p["vacancies"] for p in posts)
    doc_type = ai.get("doc_type") or "other"
    kind = "deputation" if doc_type == "deputation" or source.get("kind") == "deputation" and doc_type != "recruitment" \
        else "recruitment"
    sector = ai.get("sector") if ai.get("sector") in SECTORS else source.get("sector", "other")
    level = ai.get("level") if ai.get("level") in ("central", "state") else source["level"]
    state = ai.get("state") or source.get("state") if level == "state" else None
    title = norm_space(ai.get("title") or link_text)[:200]
    last_date = _date(ai.get("last_date"))
    now = iso(now_utc())
    return {
        "id": job_id(source["id"], ai.get("advt_no"), notice_url, title),
        "title": title,
        "organisation": norm_space(ai.get("organisation") or source["name"])[:160],
        "advt_no": norm_space(ai.get("advt_no"))[:120] or None,
        "source_id": source["id"], "source_name": source["name"],
        "level": level, "state": state, "sector": sector, "kind": kind,
        "posts": posts[:40], "total_vacancies": total,
        "pay_level_min": min(levels) if levels else None, "pay_level_max": max(levels) if levels else None,
        "min_qualification": ai.get("min_qualification") if ai.get("min_qualification") in QUALS else "unknown",
        "qualification_summary": norm_space(ai.get("qualification_summary"))[:300] or None,
        "age_min": _int(ai.get("age_min")), "age_max": _int(ai.get("age_max")),
        "age_as_on": _date(ai.get("age_as_on")), "age_note": norm_space(ai.get("age_note"))[:300] or None,
        "fee_text": norm_space(ai.get("fee_text"))[:300] or None,
        "apply_mode": ai.get("apply_mode") if ai.get("apply_mode") in ("online", "offline", "both") else None,
        "start_date": _date(ai.get("start_date")),
        "last_date": last_date, "original_last_date": last_date,
        "extended": False, "extension_history": [],
        "notice_url": notice_url, "apply_url": ai.get("apply_url") or None, "page_url": page_url,
        "location": norm_space(ai.get("location"))[:160] or None,
        "how_to_apply": norm_space(ai.get("how_to_apply"))[:500] or None,
        "summary": norm_space(ai.get("summary"))[:500] or None,
        "deputation_tenure": norm_space(ai.get("deputation_tenure"))[:200] or None,
        "eligible_cadre": norm_space(ai.get("eligible_cadre"))[:300] or None,
        "confidence": float(ai.get("confidence") or 0),
        "first_seen": now, "verified_at": now, "link_fail_count": 0, "extension_checks": 0,
        "status": "pending_review", "review_reasons": [],
    }


def check(job: dict, ai: dict, *, doc_text: str, scanned: bool, source: dict, suffixes: list[str],
          today: date, settings: dict, source_is_new: bool) -> tuple[str, list[str]]:
    """Returns (decision, reasons). decision: publish | review | drop."""
    doc_type = ai.get("doc_type")
    if doc_type not in ("recruitment", "deputation"):
        return "drop", [f"not a vacancy notice ({doc_type})"]
    if not domain_allowed(job["notice_url"], source["home"], source.get("domains", []), suffixes):
        return "drop", ["notice is not on an official domain"]

    reasons = []
    if not job["last_date"]:
        reasons.append("Last date not found in the notice")
    else:
        ld = date.fromisoformat(job["last_date"])
        if ld < today:
            return "drop", ["last date already passed"]
        if (ld - today).days > 400:
            reasons.append("Last date is more than a year away — please confirm")
        ev = (ai.get("evidence") or {}).get("last_date_text")
        if scanned:
            reasons.append("Scanned PDF: AI read it from the image, text could not be cross-checked")
        elif not ev or norm_for_match(ev)[:60] not in norm_for_match(doc_text):
            reasons.append("Last date wording not found verbatim in the document")
        elif not date_mentioned(ev, ld) and not date_mentioned(doc_text, ld):
            reasons.append("Last date does not match the date written in the document")
    if job["advt_no"] and not scanned:
        if norm_for_match(job["advt_no"]) not in norm_for_match(doc_text):
            reasons.append("Advertisement number not found in the document")
    if not job["title"] or not job["organisation"]:
        reasons.append("Title or organisation missing")
    if job["confidence"] < MIN_CONFIDENCE:
        reasons.append(f"AI confidence low ({job['confidence']:.2f})")
    if job["apply_url"] and not domain_allowed(job["apply_url"], source["home"], source.get("domains", []), suffixes):
        reasons.append(f"Apply link is not on a government domain: {job['apply_url']}")
    if source_is_new and settings.get("review_new_sources", True):
        reasons.append("New source — first week is always reviewed")
    if not settings.get("auto_publish", True):
        reasons.append("Auto-publish is switched off")
    return ("review" if reasons else "publish"), reasons


def match_corrigendum(ai: dict, source_id: str, jobs: dict[str, dict]) -> tuple[dict | None, float]:
    """Find the earlier job a corrigendum refers to. Returns (job, score 0..1)."""
    from .util import jaccard
    c = ai.get("corrigendum") or {}
    ref_no = norm_advt(c.get("refers_to_advt_no") or ai.get("advt_no"))
    ref_title = c.get("refers_to_title") or ai.get("title") or ""
    best, best_s = None, 0.0
    for j in jobs.values():
        if j.get("source_id") != source_id or j.get("status") in ("rejected",):
            continue
        s = 0.0
        if ref_no and len(ref_no) >= 3 and norm_advt(j.get("advt_no")) == ref_no:
            s = 1.0
        else:
            s = jaccard(ref_title, j.get("title", "")) * 0.8
        if s > best_s:
            best, best_s = j, s
    return best, best_s
