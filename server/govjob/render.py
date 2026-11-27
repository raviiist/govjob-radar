"""Headless Chromium for websites that build their pages with JavaScript (SSC, MPSC, ...).

Used only when the normal reader finds no notices on a site. It identifies itself with the same
GovJobRadarBot user agent as the normal reader — it is for reading JavaScript pages, not for
getting around sites that refuse automated access.
"""
from __future__ import annotations

import time

from .http import UA


class Renderer:
    def __init__(self, enabled: bool = True):
        self.enabled = enabled
        self._pw = None
        self._browser = None
        self.error: str | None = None
        self.pages = 0
        self.failures = 0

    @property
    def available(self) -> bool:
        if not self.enabled or self.error:
            return False
        if self._browser is None:
            try:
                from playwright.sync_api import sync_playwright
                self._pw = sync_playwright().start()
                self._browser = self._pw.chromium.launch(
                    args=["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu"])
            except Exception as e:  # not installed, or system libraries missing
                self.error = f"{type(e).__name__}: {str(e)[:300]}"
                return False
        return True

    def html(self, url: str, timeout_ms: int = 45000) -> tuple[str, str] | None:
        """Returns (final_url, html) or None."""
        if not self.available:
            return None
        ctx = page = None
        try:
            ctx = self._browser.new_context(user_agent=UA, ignore_https_errors=True, java_script_enabled=True,
                                            locale="en-IN")
            page = ctx.new_page()
            # Skip images, fonts and media: faster and lighter on the small server.
            page.route("**/*", lambda r: r.abort() if r.request.resource_type in ("image", "media", "font")
                       else r.continue_())
            page.goto(url, wait_until="domcontentloaded", timeout=timeout_ms)
            try:
                page.wait_for_load_state("networkidle", timeout=15000)
            except Exception:
                pass
            time.sleep(1.5)
            self.pages += 1
            return page.url, page.content()
        except Exception as e:
            self.failures += 1
            print(f"  render failed for {url}: {type(e).__name__}: {str(e)[:160]}", flush=True)
            # A crashed browser is restarted once for the next page.
            if self._browser is not None and not self._browser.is_connected():
                self._browser = None
            return None
        finally:
            try:
                if ctx:
                    ctx.close()
            except Exception:
                pass

    def close(self):
        try:
            if self._browser:
                self._browser.close()
            if self._pw:
                self._pw.stop()
        except Exception:
            pass
