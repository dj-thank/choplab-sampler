# ChopLab execution plans

An ExecPlan is a living, self-contained implementation document for work that spans multiple files, layers, or validation stages. A fresh agent with only the repository and the ExecPlan must be able to continue the task.

## When required

Create an ExecPlan for:

- native engine introduction or replacement;
- cross-layer data-model migrations;
- project persistence/schema changes;
- stereo pipeline changes;
- Song, MIDI, effects, or stem subsystems;
- substantial UI navigation/state changes;
- multi-hour debugging or release work.

Save active plans under `plans/active/<name>.md`. Move completed plans to `plans/completed/`.

## Rules

- Read the full plan before implementing or resuming.
- Do not leave key decisions only in chat; write them into the plan.
- Keep progress, discoveries, decisions, validation evidence and remaining work current.
- Resolve routine ambiguity using repository constraints and documented product goals.
- Keep the project buildable at milestone boundaries.
- Use proof-of-concept milestones for risky native/DSP or lifecycle assumptions.
- Never mark a milestone complete based only on code presence.

## Required structure

```markdown
# <Outcome-oriented title>

## Purpose and user-visible outcome

Explain what a user can do when this plan is complete.

## Current state

Describe the actual implementation, relevant files, and verified baseline commands.

## Constraints and invariants

List Android versions, real-time rules, data bounds, legal boundaries, compatibility, and no-go decisions.

## Architecture and interfaces

Describe state ownership, module boundaries, thread model, JNI/data contracts, persistence schema, and migration path.

## Milestones

### Milestone 1: <name>
- Scope
- Files/interfaces expected to change
- Implementation steps
- Tests/checks
- Acceptance evidence

## Progress

- [ ] Timestamp — concrete task and status

## Discoveries

Record unexpected behavior, exact errors, measurements, or source references.

## Decision log

- Timestamp — decision, alternatives, and rationale

## Validation log

- Command
- Date/environment
- Result
- Important output or artifact path

## Risks and rollback

Describe likely failure modes and how to return to a known-good state.

## Remaining device validation

List physical-device-only tests separately.
```

## Quality bar

The plan must name exact repository paths and commands. It must distinguish intended behavior from observed behavior. It must include acceptance criteria that a person can verify without relying on the agent's assertion.
