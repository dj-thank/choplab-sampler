# Changelog

## Unreleased — rebuild

- Consolidate product contracts into nine documents, with ROADMAP as the only current progress and acceptance index.
- Preserve ADR1–5 and research; record Android/Windows focus and concise development governance in ADR6–7.
- Retire superseded planning/status documents through the immutable 0.18.0 archive. This documentation change does not implement the planned engine, five-screen UI, AI or schema10.
- Add a Mac NEXT preview build (`mac-preview.yml`): an ad-hoc signed, not notarized Apple Silicon app for CI artifact review. It is not a public release.
- Spotify import browses by artist and album and adds only the tracks chosen; connecting an account no longer downloads the whole library.
- Move the strict project JSON codec (schema 15) into `core` common so JVM and iPadOS hosts share one reader and writer; JVM output bytes are unchanged.

## 0.18.0 baseline

[PR101](https://github.com/dj-thank/choplab-sampler/pull/101) integrated the Android/Windows production line, including shared Spotify library import/search and local drum separation. The rebuild baseline is merge `2866683a5118681cf518ef47e29cac8baf882edb`, preserved by `archive/pre-rebuild-v0.18.0`.

The writer is schema7 and the reader accepts schemas1–7. Later local schema8/9 work is outside this baseline. Historical validation does not establish a fresh device, provider or human pass for the rebuild.

Older release notes are available in the [archived release directory](https://github.com/dj-thank/choplab-sampler/tree/2866683a5118681cf518ef47e29cac8baf882edb/docs/releases). Current work and evidence are indexed only in [ROADMAP](docs/ROADMAP.md).
