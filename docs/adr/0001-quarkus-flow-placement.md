# ADR-0001: Where Quarkus Flow Lives in the Architecture

**Date:** 2026-04-07  
**Status:** Accepted — revisit when real SC2 use cases emerge  
**Deciders:** Mark Proctor

---

## Context

Quarkus Flow (CNCF Serverless Workflow) is an R&D integration target for this
project. Before implementing, we must decide where it sits relative to CaseHub
and Drools. Three options were evaluated.

The library research (§4.4) notes: "CaseHub handles real-time in-game reactive
decisions. Quarkus Flow could handle the match lifecycle." However, the
economics task has natural multi-phase workflow shape that Flow is well-suited
to model, making the boundary non-obvious.

An additional forward-compatibility concern: Drools Fusion CEP (planned) will
add event sequence pattern detection across game ticks. The chosen approach
must leave a clean boundary between Drools as detection engine and Flow as
orchestration engine.

---

## Options Considered

### Option A — Stateful multi-tick Flow within the plugin seam

`FlowEconomicsTask implements EconomicsTask`. The `execute()` method emits a
`GameStateTick` event on a SmallRye in-memory channel. A long-lived
`EconomicsFlow` instance runs per game, looping on tick events and managing
economics phase state (saturation → gas transition → expansion → multi-base)
in the workflow context. IntentQueue is injected directly into decision
functions.

**Pros:**
- Idiomatic use of Flow — stateful, event-driven, long-running
- Economics phases are explicit and visible (Dev UI Mermaid diagrams)
- Built-in Micrometer monitoring per workflow instance
- Drools/Flow boundary is clean: Drools writes signals, Flow reads them
- Flow context carries phase state without shared mutable fields

**Cons:**
- One-tick lag: decisions from tick N fire in tick N+1's dispatch
- Requires SmallRye Messaging bridge setup
- CDI lifecycle events (`GameStarted`, `GameStopped`) must be added to `AgentOrchestrator`
- More complex test setup than plain JUnit

---

### Option B — Per-tick Flow pipeline (synchronous)

`FlowEconomicsTask.execute()` starts a fresh Flow instance each tick, blocks
on `.get(timeout)`, reads decisions from the output context.

**Pros:** No event bridge; predictable timing; simple testing

**Cons:**
- Not idiomatic — Drools does this better with less overhead
- No stateful phase management — each tick starts fresh
- Blocking on a workflow instance inside a CDI bean risks Vert.x thread issues
- Provides no R&D value beyond Drools already delivers

---

### Option C — Flow at match lifecycle; economics stays CaseHub-driven

A `FlowMatchLifecycle` wraps the game (lobby → running → victory → post-game
analysis). CaseHub runs inside the "running" state. `EconomicsTask` is not
replaced — a basic implementation continues inside CaseHub.

**Pros:**
- Architecturally cleanest per library research §4.4
- Flow handles what it was explicitly designed for (lifecycle, not per-tick)
- No one-tick lag concern

**Cons:**
- Does not exercise Flow for economics reasoning — the interesting R&D question
  is deferred, not answered
- Does not replace `BasicEconomicsTask` as intended
- Drools CEP / Flow boundary cannot be explored in this configuration

---

## Decision

**Option A** — stateful multi-tick Flow within the `EconomicsTask` plugin seam.

The one-tick lag is accepted: at 500ms per tick it is invisible in play, and
it is an explicit design decision documented here rather than an accidental
oversight.

The Drools/Flow boundary is: Drools detects patterns and writes signals to the
CaseFile; Flow reads those signals and orchestrates the economic response. This
boundary is designed into the `GameStateTick` record from the start — signal
fields are populated by simple boolean checks now and by Drools CEP rules when
that integration lands, with no change to the flow itself.

---

## Consequences

- `BasicEconomicsTask` is demoted to a plain (non-CDI) class (reference + tests)
- `AgentOrchestrator` gains `GameStarted` / `GameStopped` CDI event firing
- New CaseFile signal keys (`SIGNAL_GAS_READY`, `SIGNAL_SATURATED`) added when
  Drools CEP is introduced — reserved but not yet written
- Option C (match lifecycle) remains the long-term aspiration for Flow's primary
  role; Option A coexists with it (the flow manages economics within the game,
  a future lifecycle flow manages the game itself)

---

## Revisit Triggers

This decision should be revisited when:

1. **Real SC2 play begins** — live game timing may reveal the one-tick lag as
   a problem, or confirm it is invisible
2. **Drools CEP lands** — once event sequence detection is in, the Drools/Flow
   boundary becomes empirically testable. The right split may shift.
3. **Match lifecycle work begins** — Option C implementation will clarify how
   Flow and CaseHub coexist at the outer lifecycle level, which may prompt
   rethinking Option A
4. **Performance profiling** — if the SmallRye bridge introduces measurable
   latency at high tick rates, Option B or a direct-call variant may become
   preferable
