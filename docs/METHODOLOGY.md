# Methodology — how this application was built

This project was built by an orchestrated team of AI agents directed by a single architect role,
using **Claude Opus 5 in Claude Code**. This document describes the method, the reasoning behind it,
the evidence that it worked, and — with equal weight — where it failed.

The short version: **parallel agents produce code quickly but drift silently, so the method is built
around verification rather than generation.** Roughly a third of the effort went into writing code
and two thirds into proving it was actually correct. That ratio is the point.

---

## 1. The problem the method has to solve

Handing a large full-stack brief to many agents at once creates three specific failure modes:

| Failure mode | What it looks like | How the method counters it |
|---|---|---|
| **Hallucinated APIs** | Confident code against framework methods that do not exist | Verify the real API surface *before* writing the spec |
| **Silent divergence** | Two halves that each work alone and break together | One authoritative written contract; strict file ownership |
| **Convincing but false reports** | An agent reports "all tests pass" when they did not | Never trust a report; re-verify independently |

Everything below follows from those three.

---

## 2. Core principles

**Verify the ground before designing on it.** Spring Boot 4.1.1 and Spring AI 2.0.1 both post-date
the model's training cutoff. Any spec written from memory would have been fiction.

**Write the contract once, centrally.** Parallel agents cannot negotiate. Anything two agents must
agree on is decided up front by one author.

**Separate generation from verification.** The agent that writes code is the worst judge of it. Every
wave ends with a *different* agent whose only job is to compile, run and fix.

**Make the reviewer's job refutation, not confirmation.** An agent asked "are there bugs?" finds
bugs, real or not. An agent asked "prove this bug is not real" produces a usable signal.

**The architect verifies personally.** Not as ceremony — three real defects were found this way that
no agent could have found, because agents shared the blind spots of the environment they ran in.

**Record failures.** A methodology document that only lists successes is marketing.

---

## 3. The pipeline

### Phase 0 — Environment reconnaissance *(before any design)*

The most valuable phase, and the one most easily skipped.

- The machine had **no JDK, Maven, Docker or Postgres** — only Node and Homebrew. Established
  before promising anything.
- `brew install --cask temurin@21` **silently no-opped** (casks need `sudo`; exit code was `0`
  because a later command in the chain succeeded). Caught by checking for the artifact, not the exit
  status. Switched to the sudo-free formula.
- Spring Initializr emitted a parent version `4.1.1.RELEASE` that **does not exist** in Maven
  Central. Caught on the first real compile.
- The real Spring AI 2.0.1 API was read out of the jars with `javap` and
  `spring-configuration-metadata.json`. This revealed that **`internalToolExecutionEnabled` no longer
  exists** — tool execution moved to a `ChatClient` advisor, so a direct `ChatModel.call()` returns
  tool calls *unexecuted*. That single fact determined the entire agent architecture.
- A **spike** then proved the ReAct loop against the live model. It immediately failed with a
  `ClassCastException`: `OpenAiChatModel` rejects the generic `ToolCallingChatOptions.builder()` and
  requires `OpenAiChatOptions`. Fixed in the spike, minutes of work.

> Each of these would have cost a full implementation wave if discovered later, and one — the tool
> execution semantics — would have produced a plausible-looking agent that never actually looped.

### Phase 1 — The authoritative spec

`docs/BUILD_SPEC.md`: verified environment facts, the **working ReAct code from the spike**, the
exact DB schema, the rule DSL, the API surface, and the coverage guarantee. Every agent was required
to read it first and told plainly: *"These are NEWER than your training data. Do not assume any API.
Verify with `javap`."*

### Phase 2 — Implementation in verified waves

Backend (10 agents) and frontend (6 agents) ran as two concurrent swarms, each internally sequenced:

```
Foundation (1 agent, must compile and pass)
      ↓
Parallel features (3–4 agents, disjoint file ownership, NO builds)
      ↓
Integrator (1 agent: compile, fix, run, prove)
      ↓
Parallel features (3 agents)  →  Integrator
```

Three mechanics made the parallelism safe:

- **Strict file ownership.** Every agent got an explicit list and *"do NOT create files outside it."*
  Shared foundations (`pom.xml`, `application.yml`, migrations, design tokens) were written once by
  one owner beforehand.
- **Serialised builds.** Feature agents were forbidden from running `mvn`/`tsc` — concurrent Maven
  runs corrupt `target/`. Only integrators build.
- **Reports as input.** Each integrator received the preceding agents' reports verbatim, so it knew
  what was *intended*, not just what compiled.

### Phase 3 — Independent architect verification

Agent reports were treated as claims. Re-verifying personally found **three defects the swarm could
not have found**:

1. **Schema ownership.** Every agent ran `psql` as a superuser, so none ever saw that
   `DROP SCHEMA public CASCADE; CREATE SCHEMA public` leaves `public` owned by the superuser — the
   app role then cannot create in it and Flyway dies with `no schema has been selected to create in`.
   That is the *first thing a reviewer hits on a fresh machine*. Agents were blind to it because
   they shared a privileged environment.
2. **A crashing screen.** Driving the real UI in Playwright surfaced
   `Cannot read properties of undefined (reading 'length')` on the customer profile — invisible to
   358 green tests.
3. **Frozen progress.** Watching a real 8m36s run showed the polling endpoint reporting `0/12,
   steps 0` for the entire run.

**Method note:** an early `mvn ... | tail` pipeline reported success for a build that had actually
failed, because the pipeline's exit code was `tail`'s. Every later instruction carried *"report the
true exit code with `echo EXIT:$?`; never trust a log tail."*

### Phase 4 — Adversarial code review

Six reviewers, one per dimension (coverage guarantee, security, data correctness, rule DSL, RAG,
frontend). **Every finding was then given to an independent verifier instructed to refute it**
against the real code, its callers, its tests and the live API, with an explicit bias:

> *"Default to refuted=true when you are uncertain — a false finding wastes more time than a missed
> one."*

**47 raised → 37 confirmed → 10 refuted.** The verifiers did real work: one authenticated against the
running backend and diffed live JSON keys to confirm a claim, then flagged a *second* bug in the same
file the reviewer had missed. The 10 refutations are as valuable as the confirmations — they are the
noise this structure removes before it reaches a human.

This was expressed as a `pipeline()`, not a barrier: each dimension's findings began verification as
soon as *that* dimension finished, rather than waiting for the slowest reviewer.

### Phase 5 — Consolidated fix

Six parallel fix agents plus an integrator. Because the dominant defect class was naming
disagreement, the agents were given a **single arbitration rule** so six of them could not each pick
a different resolution:

> *"The backend's wire shape is the source of truth for field names. The backend is extended only
> where the UI needs data that does not exist."*

And a rule about tests, since the suites had failed to catch two crashes:

> *"When you fix a bug a test should have caught, fix the TEST too so it would now fail without your
> change. A green suite that cannot detect the bug is part of the defect."*

### Phase 6 — Independent cross-model audit

A full audit was run by a **different model** (Claude Fable 5) with no involvement in the build and
no access to the reasoning behind it, instructed to read the code, run the builds, and report — see
[`fable-audit.md`](fable-audit.md).

Using a different model matters: the agents that wrote this code share priors with the agent that
reviewed it. An auditor drawn from a different model does not inherit the same blind spots. It
independently confirmed the coverage gate, the prompt-injection defences and the failure-path
handling, and it produced findings the in-family review had not — notably that one agent tool
(`get_transaction_details`) served its payload from a live database read while the class
documentation claimed everything came from the run's frozen snapshot, and that the
"auditable from `risk_assessments` alone" claim has an edge for a rule with no in-scope transactions.

**It also produced the sharpest process lesson of the build.** The audit ran against the working
tree *while the fix workflow was still executing*, and reported four blockers — the app not booting,
both test suites red, the frontend build failing. All four were real **at 18:10** and all four were
resolved by the integration agent by 18:59. The audit was accurate and stale at the same time.

The lesson is not that the audit was wrong; it is that **an audit is a measurement of a moment**, and
a repository mid-fix is not a state worth measuring. Two changes follow:

- Audit a **committed** state, never a working tree — the audit's own M4 finding ("the entire fix
  wave exists only as uncommitted changes") was the same problem seen from the other side.
- Record the commit hash *and* the wall-clock time in the audit header, so a reader can tell staleness
  from disagreement. This one did record both, which is why the discrepancy was diagnosable in
  minutes rather than argued about.

Every blocker it raised was verified individually against the current tree before being dismissed —
`@Autowired` present on the bootstrap constructor, the test signature updated, the fixture keys
refreshed, and a clean-slate boot seeding 3 documents into 32 chunks — rather than being waved away
as "already fixed".

### Phase 7 — Documentation

Written by the architect rather than delegated, because the assumptions and design rationale live in
the decisions, not in the code.

---

## 4. Orchestration mechanics

**Deterministic control flow, not model-driven.** Phases, fan-out and gating are ordinary JavaScript
in a workflow script. The model decides *content*; the script decides *sequence*. Loops and
conditionals behave identically every run.

**Pipeline by default, barrier only when justified.** A barrier (wait for all) is only correct when a
stage genuinely needs every prior result — deduplication, an early exit, or cross-item comparison.
The review used a pipeline so verification overlapped with reviewing.

**Wave sizing.** Waves were capped at 3–4 parallel agents, not because of a concurrency limit but
because integration risk grows faster than throughput. Two waves of three with an integrator between
them beat one wave of six.

**Agent specialisation.** `implementation-agent` for building, `code-reviewer` for reviewing —
different tools and different postures.

**Structured output for machine-consumed results.** Review findings used a JSON schema, so
verification could fan out over them programmatically instead of parsing prose.

**Infrastructure supervision.** The model tunnel was watched by a background monitor that restarted
it automatically, so a dropped SSH connection could not present as a mysterious wave of agent
failures.

---

## 5. Quality gates

| Gate | Enforced by | Caught |
|---|---|---|
| API reality | `javap` + live spike | 3 framework assumptions that would have been wrong |
| Compilation | dedicated integrator per wave | integration errors before they compounded |
| Schema ↔ entity agreement | `ddl-auto: validate` against real Postgres | mapping drift, proven by deliberately mutating the live DB |
| Clean install | full schema drop + re-migrate | the schema-ownership bug |
| Runtime truth | live `curl` + `psql` assertions | coverage proven from `risk_assessments`, not self-reported |
| Real browser | Playwright, zero-page-error policy | 2 critical crashes the unit tests could not see |
| Adversarial review | refutation-biased verifiers | 37 confirmed defects, 10 false ones filtered |

The load-bearing idea: **prove behaviour from the outside.** Rule coverage is asserted with SQL over
`risk_assessments` rather than by reading the application's own `coverage_complete` flag — a bug in
the flag would otherwise hide the bug it reports on.

---

## 6. Metrics

| | |
|---|---|
| Workflows | 5 (backend, frontend, design, review, fixes) + an independent cross-model audit |
| Agents | 81 |
| Agent tokens | ~8.0M |
| Agent tool calls | ~3,200 |
| Backend | 154 Java files, ~17,000 lines |
| Frontend | 124 TS/TSX files, ~19,100 lines |
| Migrations | ~1,400 lines of SQL |
| Tests | 241 backend + 117 frontend |
| Review | 47 findings → 37 confirmed → 10 refuted |

---

## 7. What worked

- **Spiking before specifying.** Four framework assumptions were wrong; all four were caught in
  minutes rather than propagating through a wave of implementation.
- **Ownership lists.** Across ~20 parallel implementation agents there was **not one file
  collision**.
- **Integrator agents.** The first backend integration compiled green on the first attempt — three
  agents' work merged with zero API drift, because they all coded against one verified spec.
- **Adversarial verification.** It removed 10 plausible-but-false findings *and* caught bugs the
  original reviewer had missed while checking.
- **Architect-level verification.** Three real defects, including the one that breaks the reviewer's
  very first command.
- **A cross-model auditor.** A different model found what an in-family review had not, because it did
  not share the reviewers' priors.

---

## 8. What failed, and what I would change

**The spec pinned endpoint paths but not DTO field names.** This is the central failure. Both halves
invented their own names — `countries` vs `counterpartyCountries`, `transactionIds` vs
`matchedTransactionIds`, `scoreContribution` vs `score` — producing two crashes and several broken
screens.
*Change:* generate the frontend types from the backend DTOs (or an OpenAPI schema) so the contract is
mechanical rather than prose. A spec that humans read is not a contract that machines honour.

**Mocked tests validated assumptions instead of reality.** Fixtures hand-wrote keys the backend never
sends, so 358 tests passed while two central screens crashed on load. TypeScript could not help
either, because `getJson<T>()` is an unchecked cast.
*Change:* at least one contract test per endpoint asserting against a **real recorded payload**, and
treat unchecked casts at the network boundary as a code smell.

**Agents inherited privileges the end user will not have.** Superuser `psql` hid a first-run blocker.
*Change:* make agents run as the least-privileged principal the real deployment uses.

**A dead agent silently degraded downstream work.** The design workflow's `core-skin` agent died
mid-response (the machine slept) and returned `null`; three screen agents then ran with an empty
brief, and the app shell was left unbranded. It was caught only because a later review pass re-checked
the work rather than trusting the report.
*Change:* assert on a stage's output before feeding it downstream — a `null` from a required stage
should halt the pipeline, not flow into the next prompt.

**An audit was run against a moving tree.** The cross-model audit measured the repository while the
fix workflow was still writing to it, so four of its findings were obsolete before they were read.
*Change:* audits run against a commit, never a working tree.

**Reviewers were told the code worked.** Review prompts included "current verified state: all tests
green", which risks anchoring. The refutation step counteracted it, but the framing was a mistake.
*Change:* give reviewers the code and the spec, not a verdict.

---

## 9. Reproducing this

The artefacts are all in the repository:

- `docs/BUILD_SPEC.md` — the contract every agent was given
- `docs/DESIGN_SYSTEM.md` — the visual contract
- `docs/CODE_REVIEW.md` — all 37 confirmed findings with the verifier reasoning
- `docs/AI_DESIGN.md` — model choices and the full agent instruction design
- Git history — one commit per phase, each recording what was verified

The transferable core is small: **verify the environment before designing; write one authoritative
contract; let different agents generate and verify; make review adversarial; and have the architect
personally run the thing a reviewer will run.** The last one found the bug that would have made the
project fail at first launch.
