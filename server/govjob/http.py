"""HTTP fetching with retries, size limits, politeness and clear error reasons."""
from __future__ import annotations

import time
import warnings
from dataclasses import dataclass, field
from urllib.parse import urlparse

import requests
import urllib3

from .util import host

UA = ("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/126.0 Mobile Safari/537.36 GovJobRadarBot/1.0 (+https://github.com/raviiist/govjob-radar)")
MAX_BYTES = 15 * 1024 * 1024


@dataclass
class Fetched:
    url: str
    final_url: str = ""
    status: int = 0
    content_type: str = ""
    content: bytes = b""
    error: str = ""          # empty = ok
    error_code: str = ""     # TIMEOUT, DNS, TLS, HTTP_404, BLOCKED, TOO_LARGE ...
    tls_fallback: bool = False
    elapsed: float = 0.0

    @property
    def ok(self) -> bool:
        return not self.error and 200 <= self.status < 400

    @property
    def is_pdf(self) -> bool:
        return "pdf" in self.content_type.lower() or self.content[:5] == b"%PDF-"

    def text(self) -> str:
        for enc in ("utf-8", "cp1252", "latin-1"):
            try:
                return self.content.decode(enc)
            except UnicodeDecodeError:
                continue
        return self.content.decode("utf-8", "ignore")


@dataclass
class Fetcher:
    timeout: float = 30.0
    per_host_delay: float = 1.0
    retries: int = 2
    _last: dict = field(default_factory=dict)
    _insecure_hosts: set = field(default_factory=set)
    stats: dict = field(default_factory=lambda: {"requests": 0, "errors": 0, "tls_fallbacks": 0})

    def __post_init__(self):
        self.s = requests.Session()
        self.s.headers.update({"User-Agent": UA, "Accept-Language": "en-IN,en;q=0.9,hi;q=0.8"})

    def _polite(self, url: str):
        h = host(url)
        wait = self.per_host_delay - (time.time() - self._last.get(h, 0))
        if wait > 0:
            time.sleep(wait)
        self._last[h] = time.time()

    def get(self, url: str, head_only: bool = False) -> Fetched:
        out = Fetched(url=url)
        if urlparse(url).scheme not in ("http", "https"):
            out.error, out.error_code = "not an http(s) link", "BAD_URL"
            return out
        verify = host(url) not in self._insecure_hosts
        for attempt in range(self.retries + 1):
            self._polite(url)
            self.stats["requests"] += 1
            t0 = time.time()
            try:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", urllib3.exceptions.InsecureRequestWarning)
                    if head_only:
                        r = self.s.head(url, timeout=self.timeout, allow_redirects=True, verify=verify)
                        if r.status_code in (403, 405, 501) or r.status_code >= 500:
                            # many government servers reject HEAD; fall back to a small GET
                            r = self.s.get(url, timeout=self.timeout, allow_redirects=True,
                                           verify=verify, stream=True)
                            r.close()
                    else:
                        r = self.s.get(url, timeout=self.timeout, allow_redirects=True,
                                       verify=verify, stream=True)
                        chunks, size = [], 0
                        for chunk in r.iter_content(65536):
                            size += len(chunk)
                            if size > MAX_BYTES:
                                r.close()
                                out.error, out.error_code = "file larger than 15 MB", "TOO_LARGE"
                                return out
                            chunks.append(chunk)
                        out.content = b"".join(chunks)
                out.elapsed = time.time() - t0
                out.status = r.status_code
                out.final_url = r.url
                out.content_type = r.headers.get("Content-Type", "")
                out.tls_fallback = not verify
                if r.status_code >= 400:
                    out.error = f"HTTP {r.status_code}"
                    out.error_code = "BLOCKED" if r.status_code in (401, 403, 451) else f"HTTP_{r.status_code}"
                    if r.status_code >= 500 and attempt < self.retries:
                        time.sleep(3 * (attempt + 1))
                        continue
                    self.stats["errors"] += 1
                else:
                    out.error = out.error_code = ""
                return out
            except requests.exceptions.SSLError as e:
                if verify:
                    # Many .gov.in servers send an incomplete certificate chain. These are
                    # public notices, so read them anyway but remember the problem.
                    verify = False
                    self._insecure_hosts.add(host(url))
                    self.stats["tls_fallbacks"] += 1
                    continue
                out.error, out.error_code = f"TLS error: {e}"[:300], "TLS"
            except requests.exceptions.Timeout:
                out.error, out.error_code = "timed out", "TIMEOUT"
            except requests.exceptions.ConnectionError as e:
                msg = str(e)
                code = "DNS" if ("NameResolution" in msg or "getaddrinfo" in msg) else "CONNECT"
                out.error, out.error_code = f"connection failed: {msg}"[:300], code
            except requests.exceptions.RequestException as e:
                out.error, out.error_code = f"request failed: {e}"[:300], "REQUEST"
            if attempt < self.retries:
                time.sleep(3 * (attempt + 1))
        self.stats["errors"] += 1
        return out
