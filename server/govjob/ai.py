"""Gemini API (free tier) client used to read notices and to re-find listing pages.

Only public government notices are sent. Model name is configurable (GEMINI_MODEL)
because Google renames models; a wrong name shows up in Admin > Errors.
"""
from __future__ import annotations

import base64
import json
import os
import re
import time

import requests

API = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
DEFAULT_MODEL = "gemini-flash-lite-latest"
# Tried in order; each model has its own free daily allowance.
FALLBACK_MODELS = ["gemini-flash-lite-latest", "gemini-flash-latest", "gemini-2.5-flash-lite"]

SECTORS = ["defence", "police", "space_research", "railways", "banking", "civil_services", "ssc",
           "psu", "teaching", "health", "postal", "judiciary", "engineering", "other"]
QUALS = ["any", "8th", "10th", "12th", "iti", "diploma", "graduate", "engineering", "medical",
         "postgraduate", "phd", "unknown"]


class AIError(Exception):
    code = "AI_ERROR"


class QuotaExhausted(AIError):
    code = "AI_QUOTA"


class ModelMissing(AIError):
    code = "AI_MODEL_NOT_FOUND"


class KeyInvalid(AIError):
    code = "AI_KEY_INVALID"


EXTRACT_PROMPT = """You read Indian government job notices. Return ONLY one JSON object, no prose.

Source: {source_name} ({level}{state_part}). Default sector: {sector}. Notice link: {url}
Link text on the source page: "{link_text}"

Decide what this document is and extract facts EXACTLY as written. Never guess: use null when a
value is not clearly stated. Dates as YYYY-MM-DD (Indian documents write day first).

JSON keys:
"doc_type": "recruitment" | "deputation" | "corrigendum" | "result" | "admit_card" | "other"
   - "deputation": posts to be filled on deputation / absorption / short-term contract from serving
     government employees ("through proper channel", "officers holding analogous posts").
   - "corrigendum": a change to an earlier advertisement (date extension, re-opening, change in posts).
"title": short clear title, e.g. "Scientist/Engineer 'SC' – 63 posts"
"organisation": recruiting organisation
"advt_no": advertisement number exactly as written, or null
"level": "central" | "state"
"state": state name if a state government post, else null
"sector": one of {sectors}
"posts": [{{"name": str, "vacancies": int|null, "pay_level": int|null (7th CPC level 1-18), "pay_text": str|null, "qualification": str|null}}]
"total_vacancies": int|null
"min_qualification": one of {quals}
"qualification_summary": one short line
"age_min": int|null, "age_max": int|null (general category), "age_as_on": "YYYY-MM-DD"|null, "age_note": str|null
"fee_text": str|null
"apply_mode": "online" | "offline" | "both" | null
"start_date": "YYYY-MM-DD"|null
"last_date": "YYYY-MM-DD"|null  (last date to apply / receive application)
"apply_url": str|null  (only if written in the document)
"location": str|null
"how_to_apply": one or two short sentences|null
"summary": two short plain-English sentences for a job seeker
"deputation_tenure": str|null, "eligible_cadre": str|null  (deputation only)
"corrigendum": {{"refers_to_advt_no": str|null, "refers_to_title": str|null, "new_last_date": "YYYY-MM-DD"|null, "old_last_date": "YYYY-MM-DD"|null, "what_changed": str|null}} | null
"evidence": {{"last_date_text": verbatim text from the document that states the last date, or null,
              "advt_no_text": verbatim text containing the advertisement number, or null}}
"confidence": number 0..1 — how sure you are that ALL the above facts are correct

Document text:
<<<
{text}
>>>"""

PICK_PROMPT = """Below are links found on the official website of "{source_name}".
Which ONE link most likely opens the page that lists current recruitment / vacancy / careers
notices{dep}? Return ONLY JSON: {{"url": "<one of the urls below or null>", "why": "<few words>"}}

{links}"""


class Gemini:
    """Uses the first model in the list; when it is busy (503) or its free daily limit is used up
    (429), moves on to the next model, which has its own free allowance."""

    def __init__(self, api_key: str | None = None, model: str | None = None, max_calls: int = 300,
                 min_interval: float = 6.5):
        self.key = api_key or os.environ.get("GEMINI_API_KEY", "")
        names = model or os.environ.get("GEMINI_MODEL") or ",".join(FALLBACK_MODELS)
        self.models = [m.strip() for m in names.split(",") if m.strip()]
        self.idx = 0
        self.max_calls = max_calls
        self.min_interval = min_interval   # stay under free-tier requests-per-minute
        self.calls = 0
        self.calls_by_model: dict[str, int] = {}
        self._last = 0.0
        self.exhausted = False

    @property
    def model(self) -> str:
        return self.models[min(self.idx, len(self.models) - 1)]

    @property
    def available(self) -> bool:
        return bool(self.key) and not self.exhausted and self.calls < self.max_calls

    def _next_model(self) -> bool:
        if self.idx + 1 < len(self.models):
            self.idx += 1
            return True
        return False

    @staticmethod
    def _err(r) -> str:
        try:
            e = r.json().get("error", {})
            return f"{e.get('status', '')}: {e.get('message', '')}".replace("\n", " ")[:300]
        except ValueError:
            return r.text.replace("\n", " ")[:300]

    def _call(self, parts: list[dict]) -> dict:
        if not self.key:
            raise KeyInvalid("GEMINI_API_KEY is not set")
        if self.exhausted or self.calls >= self.max_calls:
            raise QuotaExhausted("nightly AI call budget used up")
        body = {"contents": [{"role": "user", "parts": parts}],
                "generationConfig": {"temperature": 0.1, "responseMimeType": "application/json"}}
        busy = 0
        missing = []
        for _ in range(3 * len(self.models) + 2):
            wait = self.min_interval - (time.time() - self._last)
            if wait > 0:
                time.sleep(wait)
            self._last = time.time()
            self.calls += 1
            self.calls_by_model[self.model] = self.calls_by_model.get(self.model, 0) + 1
            try:
                r = requests.post(API.format(model=self.model), params={"key": self.key},
                                  json=body, timeout=90)
            except requests.RequestException as e:
                busy += 1
                if busy >= 2 and not self._next_model():
                    raise AIError(f"Gemini request failed: {type(e).__name__}")
                continue
            if r.status_code == 200:
                return self._parse(r.json())
            msg = self._err(r)
            if r.status_code == 404:
                missing.append(self.model)
                if self._next_model():
                    continue
                raise ModelMissing(f"model(s) {missing} not found — set the GEMINI_MODEL repository variable "
                                   f"to current model names (comma-separated). {msg}")
            if r.status_code in (400, 401, 403) and ("API key" in msg or "API_KEY" in msg or "PERMISSION" in msg):
                raise KeyInvalid(f"Gemini rejected the API key: {msg}")
            if r.status_code == 429:
                if "PerDay" in msg or "per day" in msg.lower() or "PerDay" in r.text:
                    if self._next_model():
                        busy = 0
                        continue
                    self.exhausted = True
                    raise QuotaExhausted(f"free daily limit reached on all models {self.models}: {msg}")
                time.sleep(40)   # per-minute limit: wait and retry same model
                continue
            if r.status_code >= 500:
                busy += 1
                if busy >= 2:
                    busy = 0
                    if not self._next_model():
                        self.idx = 0       # all busy: start again from the first after a pause
                        time.sleep(30)
                else:
                    time.sleep(10)
                continue
            raise AIError(f"Gemini HTTP {r.status_code}: {msg}")
        raise AIError(f"Gemini busy on all models {self.models}, gave up on this notice")

    @staticmethod
    def _parse(resp: dict) -> dict:
        try:
            text = "".join(p.get("text", "") for p in resp["candidates"][0]["content"]["parts"])
        except (KeyError, IndexError, TypeError):
            raise AIError(f"unexpected Gemini response: {json.dumps(resp)[:300]}")
        text = text.strip()
        text = re.sub(r"^```(?:json)?|```$", "", text).strip()
        try:
            data = json.loads(text)
        except json.JSONDecodeError:
            m = re.search(r"\{.*\}", text, re.S)
            if not m:
                raise AIError(f"AI did not return JSON: {text[:200]}")
            data = json.loads(m.group(0))
        if isinstance(data, list):
            data = data[0] if data else {}
        return data

    def extract_notice(self, *, text: str | None, pdf_bytes: bytes | None, source: dict,
                       url: str, link_text: str) -> dict:
        prompt = EXTRACT_PROMPT.format(
            source_name=source["name"], level=source["level"],
            state_part=f", {source['state']}" if source.get("state") else "",
            sector=source.get("sector", "other"), url=url, link_text=link_text[:200],
            sectors=SECTORS, quals=QUALS,
            text=(text or "(the document is attached as a PDF)")[:30000])
        parts = [{"text": prompt}]
        if pdf_bytes and not text:
            parts.append({"inline_data": {"mime_type": "application/pdf",
                                          "data": base64.b64encode(pdf_bytes).decode()}})
        return self._call(parts)

    def pick_listing(self, source: dict, links: list) -> str | None:
        listing = "\n".join(f"- {l.text[:80]} -> {l.url}" for l in links[:40])
        dep = " (or deputation vacancies)" if source.get("kind") in ("deputation", "mixed") else ""
        data = self._call([{"text": PICK_PROMPT.format(source_name=source["name"], dep=dep, links=listing)}])
        url = data.get("url")
        valid = {l.url for l in links}
        return url if url in valid else None
