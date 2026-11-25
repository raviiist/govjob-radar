"""End-to-end tests of the nightly logic with a fake web and a fake AI (no network)."""
import sys
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))

from govjob import publish  # noqa: E402
from govjob.http import Fetched  # noqa: E402
from govjob.pipeline import Nightly  # noqa: E402
from govjob.store import MemoryStore  # noqa: E402
from govjob.util import parse_date  # noqa: E402

TODAY = date(2026, 11, 26)

SRC = {"id": "isro", "name": "ISRO", "level": "central", "sector": "space_research", "kind": "mixed",
       "home": "https://www.isro.gov.in", "hints": ["https://www.isro.gov.in/OldCareers.html"]}
CFG = {"defaults": {"official_suffixes": [".gov.in", ".nic.in"], "max_new_notices_per_source": 20,
                    "max_age_days": 120}, "sources": [SRC]}

HOME = """<html><body><a href="/About.html">About</a><a href="/Careers.html">Careers</a>
<a href="/Tenders.html">Tenders</a></body></html>"""
CAREERS = """<html><body><table>
<tr><td>Recruitment of Scientist/Engineer SC – Advt No. ISRO:ICRB:01(EMC):2026</td>
<td>20/11/2026</td><td><a href="/docs/advt01.pdf">Advertisement</a></td></tr>
<tr><td>Tender for canteen</td><td><a href="/docs/tender.pdf">Tender notice</a></td></tr>
<tr><td>Result of written test 2024</td><td>01/01/2025</td><td><a href="/docs/old.pdf">Result</a></td></tr>
</table></body></html>"""
ADVT_TEXT = ("INDIAN SPACE RESEARCH ORGANISATION. Advertisement No. ISRO:ICRB:01(EMC):2026. "
             "Recruitment of Scientist/Engineer 'SC' in Level 10 of Pay Matrix. Total 63 posts. "
             "Online applications are invited. Last date for receipt of online application: 15.12.2026. "
             "Age limit 28 years as on 15.12.2026. " + "Details of posts and eligibility. " * 30)
CORR_TEXT = ("ISRO Corrigendum to Advertisement No. ISRO:ICRB:01(EMC):2026. The last date for receipt of "
             "online applications is extended from 15.12.2026 to 31.12.2026. " + "Other conditions remain. " * 30)


class FakeFetcher:
    def __init__(self, pages):
        self.pages = pages
        self.stats = {"requests": 0, "errors": 0, "tls_fallbacks": 0}

    def get(self, url, head_only=False):
        self.stats["requests"] += 1
        v = self.pages.get(url)
        if v is None:
            return Fetched(url=url, status=404, error="HTTP 404", error_code="HTTP_404")
        if isinstance(v, Fetched):
            return v
        ct = "application/pdf" if url.endswith(".pdf") else "text/html"
        return Fetched(url=url, final_url=url, status=200, content_type=ct, content=v.encode())


class FakeAI:
    available = True
    key = "x"
    model = "fake"

    def __init__(self, answers):
        self.answers = answers
        self.calls = 0

    def extract_notice(self, *, text, pdf_bytes, source, url, link_text):
        self.calls += 1
        return self.answers[url]

    def pick_listing(self, source, links):
        return None


AI_ADVT = {"doc_type": "recruitment", "title": "Scientist/Engineer 'SC' – 63 posts", "organisation": "ISRO",
           "advt_no": "ISRO:ICRB:01(EMC):2026", "level": "central", "sector": "space_research",
           "posts": [{"name": "Scientist/Engineer SC", "vacancies": 63, "pay_level": 10}],
           "min_qualification": "engineering", "age_max": 28, "last_date": "2026-12-15",
           "apply_mode": "online", "apply_url": "https://apps.ursc.gov.in/apply",
           "evidence": {"last_date_text": "Last date for receipt of online application: 15.12.2026"},
           "confidence": 0.93}
AI_CORR = {"doc_type": "corrigendum", "title": "Corrigendum", "advt_no": "ISRO:ICRB:01(EMC):2026",
           "corrigendum": {"refers_to_advt_no": "ISRO:ICRB:01(EMC):2026", "new_last_date": "2026-12-31",
                           "old_last_date": "2026-12-15"}, "confidence": 0.9}


def pages(extra=None):
    p = {"https://www.isro.gov.in": HOME, "https://www.isro.gov.in/Careers.html": CAREERS,
         "https://www.isro.gov.in/docs/advt01.pdf": ADVT_TEXT}
    p.update(extra or {})
    return p


def new_store():
    st = MemoryStore()
    st.settings["review_new_sources"] = False
    return st


def run(store, pg, ai, today=TODAY):
    n = Nightly(store, CFG, ai, fetcher=FakeFetcher(pg), today=today)
    n._read_document = lambda url, src: (url, pg[url], None, "") if url in pg else (url, "", None, "404")
    return n.run()


def test_rediscovers_listing_and_publishes_verified_job():
    st = new_store()
    s = run(st, pages(), FakeAI({"https://www.isro.gov.in/docs/advt01.pdf": AI_ADVT}))
    assert st.sources["isro"]["listing_url"] == "https://www.isro.gov.in/Careers.html"
    jobs = list(st.jobs.values())
    assert len(jobs) == 1 and jobs[0]["status"] == "live", jobs[0]["review_reasons"]
    assert jobs[0]["kind"] == "recruitment" and jobs[0]["pay_level_min"] == 10
    assert s.published == 1


def test_unverifiable_date_goes_to_review():
    st = new_store()
    bad = dict(AI_ADVT, last_date="2026-12-20",
               evidence={"last_date_text": "Last date for receipt of online application: 20.12.2026"})
    run(st, pages(), FakeAI({"https://www.isro.gov.in/docs/advt01.pdf": bad}))
    j = next(iter(st.jobs.values()))
    assert j["status"] == "pending_review"
    assert any("verbatim" in r or "match" in r for r in j["review_reasons"])


def test_new_source_reviewed_first_week():
    st = MemoryStore()  # review_new_sources default True
    run(st, pages(), FakeAI({"https://www.isro.gov.in/docs/advt01.pdf": AI_ADVT}))
    assert next(iter(st.jobs.values()))["status"] == "pending_review"


def test_corrigendum_extends_and_expiry_respects_extension():
    st = new_store()
    ai = FakeAI({"https://www.isro.gov.in/docs/advt01.pdf": AI_ADVT,
                 "https://www.isro.gov.in/docs/corr01.pdf": AI_CORR})
    run(st, pages(), ai)
    # Night after the original last date: a corrigendum appears on the page.
    careers2 = CAREERS.replace("</table>", '<tr><td>Corrigendum: extension of last date Advt No. '
                               'ISRO:ICRB:01(EMC):2026</td><td>14/12/2026</td>'
                               '<td><a href="/docs/corr01.pdf">Corrigendum</a></td></tr></table>')
    pg = pages({"https://www.isro.gov.in/Careers.html": careers2, "https://www.isro.gov.in/docs/corr01.pdf": CORR_TEXT})
    s = run(st, pg, ai, today=date(2026, 12, 16))
    j = next(iter(st.jobs.values()))
    assert j["last_date"] == "2026-12-31" and j["extended"] and j["status"] == "live"
    assert j["extension_history"][0]["from"] == "2026-12-15"
    assert s.extensions == 1


def test_expired_job_waits_then_closes():
    st = new_store()
    ai = FakeAI({"https://www.isro.gov.in/docs/advt01.pdf": AI_ADVT})
    run(st, pages(), ai)
    for i in range(1, 5):
        run(st, pages(), ai, today=date(2026, 12, 15) + timedelta(days=i))
        j = next(iter(st.jobs.values()))
        if i <= 3:
            assert j["status"] == "awaiting_extension_check", (i, j["status"])
    assert j["status"] == "closed"


def test_broken_link_hidden_after_two_nights_and_recovers():
    st = new_store()
    ai = FakeAI({"https://www.isro.gov.in/docs/advt01.pdf": AI_ADVT})
    run(st, pages(), ai)
    broken = pages({"https://www.isro.gov.in/docs/advt01.pdf": None})
    broken = {k: v for k, v in broken.items() if v is not None}
    run(st, broken, ai, today=TODAY + timedelta(days=1))
    run(st, broken, ai, today=TODAY + timedelta(days=2))
    j = next(iter(st.jobs.values()))
    assert j["status"] == "link_broken"
    assert any(e["code"] == "LINK_BROKEN" and not e["resolved"] for e in st.errors.values())
    run(st, pages(), ai, today=TODAY + timedelta(days=3))
    assert next(iter(st.jobs.values()))["status"] == "live"
    assert all(e["resolved"] for e in st.errors.values() if e["code"] == "LINK_BROKEN")


def test_failing_source_flagged_after_three_nights():
    st = new_store()
    for i in range(3):
        run(st, {}, FakeAI({}), today=TODAY + timedelta(days=i))
    errs = [e for e in st.errors.values() if e["code"] == "SOURCE_FAILING"]
    assert errs and not errs[0]["resolved"]
    run(st, pages(), FakeAI({"https://www.isro.gov.in/docs/advt01.pdf": AI_ADVT}), today=TODAY + timedelta(days=3))
    assert all(e["resolved"] for e in st.errors.values() if e["code"] == "SOURCE_FAILING")


def test_quota_stops_ai_and_retries_tomorrow():
    from govjob.ai import QuotaExhausted

    class QuotaAI(FakeAI):
        def extract_notice(self, **kw):
            raise QuotaExhausted("429 per day")
    st = new_store()
    run(st, pages(), QuotaAI({}))
    assert not st.jobs
    assert any(e["code"] == "AI_QUOTA" for e in st.errors.values())
    assert not st.seen.get("isro")   # not marked as seen -> read next night
    run(st, pages(), FakeAI({"https://www.isro.gov.in/docs/advt01.pdf": AI_ADVT}), today=TODAY + timedelta(days=1))
    assert len(st.jobs) == 1


def test_publish_feed_and_shrink_guard():
    st = new_store()
    run(st, pages(), FakeAI({"https://www.isro.gov.in/docs/advt01.pdf": AI_ADVT}))
    feed, meta = publish.build(st.jobs, CFG)
    assert meta["count"] == 1 and not publish.validate(feed)
    assert "review_reasons" not in feed["jobs"][0] and "confidence" not in feed["jobs"][0]
    assert publish.shrink_too_much(100, 30) and not publish.shrink_too_much(100, 80)


def test_off_domain_notice_dropped():
    st = new_store()
    careers = CAREERS.replace('/docs/advt01.pdf', 'https://fake-jobs.example.com/advt01.pdf')
    run(st, pages({"https://www.isro.gov.in/Careers.html": careers}), FakeAI({}))
    assert not st.jobs


def test_indian_dates():
    assert parse_date("15.12.2026") == date(2026, 12, 15)
    assert parse_date("05/01/2027") == date(2027, 1, 5)
    assert parse_date("31st December, 2026") == date(2026, 12, 31)
