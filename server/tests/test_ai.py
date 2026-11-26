import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
import pytest  # noqa: E402

from govjob import ai  # noqa: E402


class R:
    def __init__(self, code, body):
        self.status_code, self._b = code, body
        self.text = str(body)

    def json(self):
        return self._b


OK = {"candidates": [{"content": {"parts": [{"text": '{"doc_type": "recruitment"}'}]}}]}
BUSY = {"error": {"status": "UNAVAILABLE", "message": "overloaded"}}
DAY = {"error": {"status": "RESOURCE_EXHAUSTED", "message": "Quota exceeded for metric GenerateRequestsPerDayPerProjectPerModel"}}


def run(monkeypatch, replies):
    seen = []

    def post(url, **kw):
        seen.append(url.split("/models/")[1].split(":")[0])
        return replies.pop(0)
    monkeypatch.setattr(ai.requests, "post", post)
    monkeypatch.setattr(ai.time, "sleep", lambda s: None)
    g = ai.Gemini(api_key="k", model="a,b", min_interval=0)
    return g, seen


def test_busy_model_falls_back(monkeypatch):
    g, seen = run(monkeypatch, [R(503, BUSY), R(503, BUSY), R(200, OK)])
    assert g._call([{"text": "x"}])["doc_type"] == "recruitment"
    assert seen == ["a", "a", "b"]


def test_daily_quota_moves_to_next_then_stops(monkeypatch):
    g, seen = run(monkeypatch, [R(429, DAY), R(200, OK), R(429, DAY)])
    g._call([{"text": "x"}])
    with pytest.raises(ai.QuotaExhausted):
        g._call([{"text": "x"}])
    assert seen == ["a", "b", "b"] and g.exhausted
