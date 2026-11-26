"""Read pages: list links, pick recruitment notices, turn HTML/PDF into text."""
from __future__ import annotations

import io
import re
from dataclasses import dataclass
from datetime import date, timedelta
from urllib.parse import urljoin, urldefrag

from bs4 import BeautifulSoup

from .util import dates_in, norm_space, sha

JOB_WORDS = re.compile(
    r"recruit|vacanc|advertis|\badvt\b|notification|employment|deputation|post of|posts of|"
    r"engagement|apprentice|walk[- ]?in|careers?|jobs?\b|selection|appointment|filling up|"
    r"corrigendum|addendum|extension|extended|re-?open|"
    r"भर्ती|रिक्ति|विज्ञापन|प्रतिनियुक्ति|नियुक्ति", re.IGNORECASE)
SKIP_WORDS = re.compile(
    r"\btender|\brti\b|annual report|\bresult|answer key|admit card|e-?admit|call letter|"
    r"syllabus|marks|cut[- ]?off|interview schedule|press release|photo gallery|"
    r"auction|quotation|\beoi\b|expression of interest|newsletter|holiday", re.IGNORECASE)
CORRIGENDUM_WORDS = re.compile(r"corrigendum|addendum|extension|extended|extend|revised|re-?open|शुद्धिपत्र",
                               re.IGNORECASE)
LISTING_WORDS = re.compile(
    r"recruit|career|vacanc|employment|jobs?\b|opportunit|advertisement|deputation|circulars?\b|what.?s new|notices?\b|"
    r"भर्ती|रिक्ति", re.IGNORECASE)


@dataclass
class Link:
    url: str
    text: str
    context: str   # text of the surrounding row / list item

    @property
    def key(self) -> str:
        return sha("v2|" + self.url + "|" + norm_space(self.text).lower())   # v2: re-read everything once


def soup_of(html: str) -> BeautifulSoup:
    try:
        return BeautifulSoup(html, "lxml")
    except Exception:  # lxml not installed
        return BeautifulSoup(html, "html.parser")


def links_of(html: str, base_url: str) -> list[Link]:
    soup = soup_of(html)
    out, seen = [], set()
    for a in soup.find_all("a", href=True):
        href = a["href"].strip()
        if href.startswith(("javascript:", "mailto:", "tel:", "#")):
            continue
        url = urldefrag(urljoin(base_url, href))[0]
        text = norm_space(a.get_text(" ") or a.get("title") or "")
        row = a.find_parent(["tr", "li", "p", "div"])
        ctx = norm_space(row.get_text(" "))[:600] if row else text
        if (url, text) in seen:
            continue
        seen.add((url, text))
        out.append(Link(url=url, text=text, context=ctx))
    return out


def is_pdf_url(url: str) -> bool:
    return bool(re.search(r"\.pdf($|\?)", url, re.IGNORECASE))


def candidate_notices(links: list[Link], today: date, max_age_days: int) -> list[Link]:
    """Links that look like recruitment / deputation notices or their corrigenda."""
    out = []
    oldest = today - timedelta(days=max_age_days)
    for l in links:
        blob = f"{l.text} {l.context}"
        if not JOB_WORDS.search(blob):
            continue
        if SKIP_WORDS.search(l.text) and not CORRIGENDUM_WORDS.search(l.text):
            continue
        ds = [d for d in dates_in(l.context) if d <= today + timedelta(days=400)]
        # Menu links like "Careers" / "Recruitment" are pages, not notices.
        if not is_pdf_url(l.url) and len(l.text) < 25 and not ds:
            continue
        if len(l.text) < 3 and not is_pdf_url(l.url):
            continue
        # If the row shows dates and all of them are old, skip (old archive entries).
        if ds and max(ds) < oldest:
            continue
        out.append(l)
    return out


def listing_link_candidates(links: list[Link]) -> list[Link]:
    """Links on a home page that may lead to the recruitment / careers page."""
    scored = []
    for l in links:
        s = 0
        if LISTING_WORDS.search(l.text):
            s += 3
        if LISTING_WORDS.search(l.url):
            s += 2
        if is_pdf_url(l.url):
            s -= 2
        if s > 0:
            scored.append((s, l))
    scored.sort(key=lambda x: -x[0])
    return [l for _, l in scored[:40]]


def html_to_text(html: str) -> str:
    soup = soup_of(html)
    for t in soup(["script", "style", "noscript", "header", "footer", "nav"]):
        t.decompose()
    text = soup.get_text("\n")
    lines = [norm_space(x) for x in text.splitlines()]
    return "\n".join(x for x in lines if x)


def pdf_to_text(data: bytes, max_pages: int = 12) -> str:
    try:
        from pypdf import PdfReader
        reader = PdfReader(io.BytesIO(data))
        parts = []
        for page in reader.pages[:max_pages]:
            try:
                parts.append(page.extract_text() or "")
            except Exception:
                continue
        return "\n".join(parts).strip()
    except Exception:
        return ""
