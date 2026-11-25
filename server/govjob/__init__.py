"""GovJob Radar server pipeline.

Nightly: crawl official sources -> find notices -> AI extraction (Gemini free tier)
-> verification -> Firestore (master DB) -> lifecycle (extension check, link health).
Publish: Firestore live jobs -> static JSON feed on GitHub Pages.
"""

FEED_SCHEMA_VERSION = 1
