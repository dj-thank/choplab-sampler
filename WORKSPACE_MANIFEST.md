# ChopLab Codex Workspace manifest

Generated: 2026-07-16

## Purpose

This archive is a Codex-ready Android development repository. It contains a buildable MVP baseline, isolated Pro reference artifacts, project-scoped Codex configuration, persistent repository instructions, custom subagents, a reusable skill, staged prompts, environment scripts, CI configuration, validation records, and the original source archives.

## Entry points

- `README.md`: setup and operation.
- `AGENTS.md`: repository-wide instructions loaded by Codex.
- `.codex/config.toml`: project-scoped Codex settings.
- `CODEX_PROMPT.txt`: minimal prompt for an interactive session.
- `prompts/00_MASTER_PROMPT.md`: complete implementation prompt.
- `scripts/codex-start.sh`: interactive Codex launch.
- `scripts/codex-run-master.sh`: non-interactive master prompt launch.
- `scripts/doctor.sh`: local environment diagnosis.
- `scripts/bootstrap.sh`: SDK/project bootstrap.
- `scripts/verify.sh`: full Android verification gate.

## Repository layout

- `app/`: current Android MVP baseline and only production build target.
- `reference/pro-v0.2/`: incomplete Pro design/source artifacts; reference only.
- `.codex/agents/`: custom Android, DSP, build and QA subagents.
- `.agents/skills/`: reusable ChopLab development workflow.
- `plans/`: long-running ExecPlans.
- `prompts/`: master and staged Codex prompts.
- `docs/`: architecture, project state, requirements, completion criteria and validation.
- `.devcontainer/`: reproducible Android command-line development container.
- `.github/workflows/`: Android CI gate.
- `original-archives/`: preserved earlier deliverables.

## Integrity

`SHA256SUMS.txt` contains SHA-256 hashes for 110 payload files, excluding Git metadata, the manifest itself and the checksum list itself.

## Preparation status

Offline structure, TOML/JSON/YAML/XML parsing, shell syntax, pure Kotlin smoke tests and wrapper integrity checks passed. Android/Gradle assembly was not completed in the preparation container because Android SDK and external Gradle dependency resolution were unavailable. See `docs/PREPARATION_VALIDATION.md`.
