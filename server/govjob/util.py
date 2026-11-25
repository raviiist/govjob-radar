"""Small shared helpers: time, hashing, text normalisation, dates, domain checks."""
from __future__ import annotations

import hashlib
import re
from datetime import date, datetime, timedelta, timezone
from urllib.parse import urlparse

from dateutil import parser as dateparser

IST = timezone(timedelta(hours=5, minutes=30))


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def today_ist() -> date:
    return datetime.now(IST).date()


def iso(dt: datetime | None) -> str | None:
    return dt.astimezone(timezone.utc).isoformat(timespec="seconds") if dt else None


def sha(text: str, n: int = 16) -> str:
    return hashlib.sha1(text.encode("utf-8", "ignore")).hexdigest()[:n]


def norm_space(s: str | None) -> str:
    return re.sub(r"\s+", " ", s or "").strip()


def norm_for_match(s: str | None) -> str:
    """Lower-case, drop punctuation and spaces: used to check that AI evidence really
    appears in the document text."""
    return re.sub(r"[^0-9a-zऀ-෿]+", "", (s or "").lower())


def norm_advt(s: str | None) -> str:
    return re.sub(r"[^0-9a-z]+", "", (s or "").lower())


def host(url: str) -> str:
    try:
        h = (urlparse(url).hostname or "").lower()
    except ValueError:
        return ""
    return h[4:] if h.startswith("www.") else h


def domain_allowed(url: str, source_home: str, extra: list[str], suffixes: list[str]) -> bool:
    """True if url is on the source's own domain, an extra domain for that source,
    or any official government suffix (.gov.in, .nic.in ...)."""
    h = host(url)
    if not h or not url.lower().startswith(("http://", "https://")):
        return False
    own = host(source_home)
    allowed = [own] + [e.lower().lstrip(".") for e in (extra or [])]
    for a in allowed:
        if a and (h == a or h.endswith("." + a)):
            return True
    return any(h.endswith(s) for s in suffixes)


_DATE_PATTERNS = [
    r"\b\d{1,2}[./-]\d{1,2}[./-]\d{2,4}\b",
    r"\b\d{1,2}(?:st|nd|rd|th)?\s*(?:of\s+)?(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?,?\s*\d{2,4}\b",
    r"\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?\s+\d{1,2}(?:st|nd|rd|th)?,?\s*\d{2,4}\b",
    r"\b\d{4}-\d{2}-\d{2}\b",
]
DATE_RE = re.compile("|".join(_DATE_PATTERNS), re.IGNORECASE)


def parse_date(s: str | None) -> date | None:
    """Parse Indian-style dates (day first). Returns None when unsure."""
    if not s:
        return None
    s = norm_space(str(s))
    if re.fullmatch(r"\d{4}-\d{2}-\d{2}", s):
        try:
            return date.fromisoformat(s)
        except ValueError:
            return None
    try:
        d = dateparser.parse(s, dayfirst=True, fuzzy=True, default=datetime(2000, 1, 1))
    except (ValueError, OverflowError, TypeError):
        return None
    if not d or d.year < 2000 or d.year > 2100:
        return None
    return d.date()


def dates_in(text: str) -> list[date]:
    out = []
    for m in DATE_RE.finditer(text or ""):
        d = parse_date(m.group(0))
        if d:
            out.append(d)
    return out


def date_mentioned(text: str, d: date) -> bool:
    """Is this calendar date written anywhere in the text (any common format)?"""
    return any(x == d for x in dates_in(text))


def jaccard(a: str, b: str) -> float:
    ta = set(re.findall(r"[a-z0-9]{3,}", (a or "").lower()))
    tb = set(re.findall(r"[a-z0-9]{3,}", (b or "").lower()))
    if not ta or not tb:
        return 0.0
    return len(ta & tb) / len(ta | tb)
