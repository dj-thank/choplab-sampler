# ADR-0007: One current progress index and bounded delivery

- Status: Accepted for the rebuild
- Date: 2026-09-25

## Context and decision

Repeated status files, plans and completion ledgers obscured which product revision and next action were current. Keep one progress and acceptance index, `docs/ROADMAP.md`; keep contracts in the other eight top-level documents and durable decisions in ADRs. CI/PR artifacts carry detailed execution evidence. Old records remain accessible from the fixed archive rather than copied into new ledgers.

Apply the Kotodama working method as request/correction → bounded single-owner work → code and meaningful verification → readback → one current truth. A correction replaces the affected plan. Record decisions, unresolved failures and the next action where a maintainer can find them, without publishing private source material, personal paths or raw conversations.

The owner authorized the complete reviewed rebuild through final polish and PR integration. That authorization supersedes the review draft's review-only and repeated per-PR approval text. Root integration may proceed inside this scope without asking the same question again; delegated writers remain bounded by their assignment. Authorization does not count as device/provider evidence or a completed human listening check.

## Consequences

Use one independently reviewable change per PR; stage1 remains 1A/1B/1C and stage2 may split further. Preserve single-writer device/provider ownership and dirty user work. Retain meaningful regression/protection tests, source/packaged security checks and evidence boundaries. Documentation-only changes do not demand an APK rebuild.

Keep reports short: behavior, checks, remaining limits, at most five human checks. Do not multiply raw logs or accept child-agent confidence as an observed pass. `LOCAL_PASS`, `DEVICE_PASS`, `PROVIDER_PASS`, `PUBLIC_PASS` and `HUMAN_GO` remain separate.

## Rollback

The historical planning scheme can be read from [the baseline](https://github.com/dj-thank/choplab-sampler/tree/2866683a5118681cf518ef47e29cac8baf882edb). If the new index omits a necessary contract, repair the appropriate document and its acceptance link; do not silently reintroduce a second current ledger.
