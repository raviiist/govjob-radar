import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
import pytest  # noqa: E402

from govjob.sources import SourcesError, load  # noqa: E402


def test_real_file_is_valid():
    cfg = load(Path(__file__).parents[1] / "config" / "sources.yaml")
    assert len(cfg["sources"]) > 50


def test_minimal_entry_gets_defaults():
    cfg = load(None, 'sources:\n  - {id: ka_police, name: "KSP", level: state, state: "Karnataka", home: "https://ksp.karnataka.gov.in"}\n')
    s = cfg["sources"][0]
    assert s["sector"] == "other" and s["kind"] == "mixed"


def test_clear_errors():
    with pytest.raises(SourcesError) as e:
        load(None, 'sources:\n  - {id: "Bad Id", name: x, level: state, home: "www.x.gov.in"}\n')
    msg = str(e.value)
    assert "small letters" in msg and "need  state" in msg and "https://" in msg
