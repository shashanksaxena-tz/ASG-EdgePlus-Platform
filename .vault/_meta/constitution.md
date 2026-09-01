---
id: constitution
type: meta
status: active
date: 2026-06-05
authors: [[shashank]]
amend-policy: PR + PO sign-off + tech-lead sign-off
---

# ASG Edge+ Project Constitution

> This is law. Engineers (human or AI) work under it. Conflicts with anything else in this repo are resolved in this document's favor. To change it, see §10.

---

## 1. Purpose

ASG Edge+ is a multi-tenant SaaS for **lease accounting (ASC 842 / IFRS 16) and contract management**. It is being built as a Java 21 / Spring Boot 3.4 / PostgreSQL / Kafka backend with a Vite + React 19 + TypeScript frontend, organized as two self-contained subprojects in one monorepo. This constitution governs how every contributor — human teammate or AI agent — proposes, designs, builds, reviews, and ships work on the platform. It exists because the cost of a mistake in lease accounting compounds for the seven-year audit retention window, and because AI agents producing accurate-looking but subtly wrong work over many days is the single highest-probability failure mode for this codebase.

---

## 2. Source-of-Truth Hierarchy

When two documents disagree, the one higher on this list wins. Always.

1. **The signed BRD `.docx`** under `KnowledgeFolder/Epics/ASG-BRD<NN>-<TITLE>.docx`. The Product Owner owns this. Extract verbatim with `pandoc -t plain` — do not paraphrase from memory.
2. **The GitHub EPIC issue** (label: `epic`). Linked from the BRD. Carries acceptance criteria, scope, open questions, and the comment thread of clarifications.
3. **The EPIC spec** in `specs/<EPIC-ID>-<slug>-spec.md`. Engineering's interpretation of (1) + (2) — the contract between PO and engineering. **EPICs are the source of truth for engineering implementation.**
4. **The user-story specs** branched off the EPIC, in `specs/<EPIC-ID>.<STORY-ID>-<slug>-spec.md`. Slice of the EPIC for one BRD user story. ACs are 1:1 with the BRD.
5. **Code.** The implementation. If code disagrees with (4) and there is no drift note, **the code is wrong**.

**Conflicts flow upward, not downward.** A disagreement between code and spec is fixed by changing code OR by filing a drift note + spec PR (see §9). A disagreement between spec and EPIC is escalated to the EPIC owner. A disagreement between EPIC and BRD is escalated to the PO. **Conflicts are never resolved silently in code.**

---

## 3. Architecture Rules That Don't Bend

These are the active ADRs in `backend/.vault/decisions/`. Each one is binding. Cite by ID in any spec or PR that touches the area. Superseded ADRs are listed for historical clarity; do not follow them.

| ID           | Status     | 1-line invariant                                                                                                                                                                                      |
| ------------ | ---------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **ADR-0003** | active     | Platform stack is Java + React/TypeScript + PostgreSQL — *historical; superseded in practice by the Java 21 + Spring Boot 3.4 + Postgres + Kafka stack now in use. Re-confirm with PO before citing.* |
| **ADR-0004** | active     | Multi-tenant isolation is enforced as **four-layer defense in depth** — JWT claim, gateway filter, JPA tenant filter, row-level checks. Never trust one layer alone.                                  |
| **ADR-0005** | active     | All state changes are captured in an **immutable, append-only audit ledger** with hash-chained entries. No updates, no deletes.                                                                       |
| **ADR-0006** | active     | Workflows are split into **orchestrator + definition + execution engines**, all driven by explicit state machines. No ad-hoc branching in services.                                                   |
| **ADR-0007** | active     | Audit data has a **7-year retention** SLA, served from hot/warm/cold storage tiers. Retention is policy-as-code, not a DBA convention.                                                                |
| **ADR-0008** | active     | **Contract Management is the single critical-path blocker**; the contract schema must be frozen by Week 12 of the build. Downstream services depend on it.                                            |
| **ADR-0009** | active     | ASC 842 recalculation is driven by an **explicit, enumerated list of trigger events**. No implicit recalculation.                                                                                     |
| **ADR-0010** | active     | ASC 842 lives in a **dedicated bounded-context service**, not a generic accounting engine.                                                                                                            |
| **ADR-0011** | superseded | In-house IAM stack — **superseded by ADR-0015**. Do not follow.                                                                                                                                       |
| **ADR-0012** | active     | Domain services communicate via **events on Kafka with the transactional outbox**. Synchronous calls between domains require an explicit waiver.                                                      |
| **ADR-0013** | active     | Cross-cutting concerns are centralized in **eight shared SDKs** (auth, authz, multi-tenant ctx, audit, doc-storage, notifications, search, workflow). Services do not re-implement these.             |
| **ADR-0014** | active     | The product is delivered in **five sequenced phases with five parallel tracks**. Out-of-phase work needs PO approval.                                                                                 |
| **ADR-0015** | active     | **AWS Cognito is the sole IdP** — one User Pool per tenant + one platform pool. Supersedes ADR-0011.                                                                                                  |

Any architectural choice that contradicts an active ADR requires a new ADR superseding it, **merged before the contradicting code lands**.

---

## 4. Engineering Invariants

These are testable. A reviewer can answer "yes" or "no" for each one on any given PR.

1. **DRY ruthlessly.** Three-strikes rule: the third near-duplicate is refactored, not accepted. Reviewers flag repetition aggressively.
2. **Explicit > clever.** No "smart" one-liners. No reflection where a method call works. No string-keyed config where an enum works.
3. **Tests for every behavior.** Coverage ≥ 95% line / 90% branch on changed code; 98% on domain + application layers; PIT mutation ≥ 85%. (per `java-testing`, `java-code-quality`).
4. **No `double` / `float` in financial code.** `BigDecimal` with explicit scale + rounding mode, or domain `Money` value object. Enforced by ArchUnit. (per `asg-decimal-precision`).
5. **RFC 7807 ProblemDetail for every error.** Fields: `code`, `traceId`, `tenantId`, `errors[]`. No bespoke error shapes. (per `java-api-errors`).
6. **No skipping hooks.** `--no-verify`, `--no-gpg-sign`, `--no-edit` on rebases — all forbidden without explicit PO approval recorded in the PR.
7. **Commit-after-each-green-task.** Per `java-git-workflow` §3: each end-to-end-green coherent unit gets its own commit. No "WIP" merges to main.
8. **Conventional commits with a mandatory `Intent:` block.** Subject line follows Conventional Commits; body contains `Intent:`, `Test plan:`, `Coverage:`. Empty Intents are rejected at review.
9. **End-to-end green.** Backend (`./gradlew check`) + frontend (`tsc -b && vitest run`) + contract tests all green before any commit lands on a shared branch.
10. **Backstage catalog + runbook** for every new service. No service ships without `catalog-info.yaml` and a runbook entry per alert.
11. **OTel baggage at the gateway** carries `tenant.id` and `tenant.class`. Logs and traces inherit. (per `java-observability`).
12. **No secrets in YAML or Git.** Vault / AWS Secrets Manager / k8s Secrets only.

---

## 5. The "No Closed-Loop" Rule

Claude must never operate without a human checkpoint at the cadences defined in the `java-human-review-ritual` skill. There are **four checkpoints**, all mandatory:

| Cadence | What the human looks at | Time | Output |
| --- | --- | --- | --- |
| **Per PR** | Diff + Intent block + test plan + linked vault entries | 5–15 min | Approve / request changes; one sentence in the PR comment minimum |
| **Daily standup** (17:30) | Today's `.vault/sessions/YYYY-MM-DD.md` | 5 min | One sentence under `## Human notes` |
| **Weekly review** (Mon 09:30) | Last 7 days of sessions, new decisions, open drifts | 30 min | Paragraph in `.vault/_meta/weekly-review-YYYY-MM-DD.md` |
| **Quarterly retrospective** (last Fri of quarter) | Quarter metrics, distillation, archive | 90 min | `.vault/_meta/quarterly-retrospective-YYYY-QN.md` |

**Skipping the daily for 3 consecutive days, or the weekly for 3 consecutive weeks, is a stop-the-line event.** Auto-commit pauses until the human catches up. Claude refuses to merge in the affected area until the ritual is restored.

Stop-the-line is also triggered by: first-time error pattern, drift on a load-bearing decision, coverage drop, > 3 high-priority adversarial-drift findings, validator failure, or repeated debugging in the same module (third occurrence = architectural problem, not a coding problem). On trigger, Claude writes `.vault/_meta/escalations/YYYY-MM-DD-<symptom>.md` and opens a GitHub issue labeled `framework:escalation`.

---

## 6. Definition of Done

A feature is **not done** until all of the following are true:

1. Every BRD / EPIC AC is met — verified by the spec's AC table, ticked one-by-one in the PR description.
2. Tests pass: backend `./gradlew :<module>:check`; frontend `npm run lint && npm run test:run && tsc -b`.
3. ArchUnit + Spotless + Checkstyle + NullAway + dep-check + gitleaks + ESLint clean.
4. PR reviewed by a human who is **not the author** and not the same human who reviewed the last PR (rotate weekly).
5. Documentation updated where the change touches a load-bearing claim: ADR, EPIC spec, user-story spec, component note, runbook, OpenAPI.
6. Audit + observability hooks present: audit-log entry for every state change, log/metric/trace span for every public method, MDC carries `tenant_id` + `trace_id`. (per `java-observability`, `java-data-governance`).
7. Vault updated in the same commit: session note appended, component/decision/finding/debugging/drift notes created as applicable.

A PR that fails any one of these is rejected — not "approved with a note."

---

## 7. Definition of Spec

A spec is **not ready** until it contains all of:

- Numbered acceptance criteria, each **traceable to a specific BRD section** (quote the BRD line).
- A finite-state machine diagram (mermaid or PlantUML) if the feature involves any state.
- An **error matrix**: every error path → ProblemDetail `code` + HTTP status + user-facing message + recovery action.
- Data model deltas: new tables / columns / indexes / migrations.
- Non-functional requirements with **measurable targets**: p95 latency, throughput, RPO/RTO tier, cost ceiling.
- An **open-questions list** (zero-or-more). A spec with secret open questions is rejected.
- Links to: upstream BRD section, EPIC issue URL, parent EPIC spec (for user-story specs), Figma node (for FE), ADRs cited.

The template lives at `backend/.vault/_meta/templates/epic-spec-template.md` and `…/user-story-spec-template.md`. The full authoring loop lives in `backend/.vault/_meta/spec-process.md`.

---

## 8. AI Agent Rules

When the contributor is Claude (or any AI agent), additional rules apply on top of §§ 1–7:

a. **Check `<claude-mem-context>` first.** At every session start, read the auto-loaded memory context. If a prior session decided something, cite it; do not redo the analysis.
b. **Use the `code-review-graph` MCP before grep/glob/read.** Faster, cheaper, and gives caller/dependent/test-coverage structure. Fall back to file-scanning only when the graph does not cover the question.
c. **Consult `backend/.vault/decisions/` before any architectural choice.** A new public API, a new service boundary, a new persistence pattern, a new event topic — read the ADRs first, cite the relevant ones in the PR.
d. **Commit-after-task** per `java-git-workflow`. Each green, coherent unit is a commit. No multi-task mega-commits.
e. **Never delete BRD-mandated behavior to make tests pass.** If a test requires removing a BRD-mandated rule, the test is wrong OR the BRD is wrong — escalate, do not delete.
f. **Flag BRD-vs-implementation drift in `.vault/drift/`** at the moment of discovery. Do not "remember to flag it later." File the note in the same session that surfaced the drift.

Violations of (a)–(f) are reviewer-callable: a reviewer who spots one rejects the PR with a one-line reason.

---

## 9. Drift and Escalation

Reality drifts from documents. When an engineer (human or AI) notices that code, the EPIC, the BRD, or an ADR disagree:

1. Create `backend/.vault/drift/YYYY-MM-DD-<topic>.md` (template in `_meta/templates/drift.md`). Capture: what disagrees with what, evidence (file:line, BRD section, ADR ID), and a proposed resolution (code fix OR doc update).
2. Open a PR proposing the resolution. Label `drift`. Reference the drift note in the PR body.
3. **Never silently fix one side.** A drift note that says only "code now matches doc" with no PR is invalid; a code change with no drift note is invalid.
4. Drift items with `status: open` block merge on any PR that touches the affected component.
5. Drift older than 14 days without resolution escalates automatically to the weekly review.

The drift folder is the project's honesty surface. Empty drift folder + active development = the engineers stopped looking.

---

## 10. How to Amend This Constitution

Changes to this file require:

1. A PR that touches **only** `constitution.md` (no code, no other doc churn — keep the diff readable).
2. **Sign-off from the Product Owner** AND **sign-off from the tech lead** in the PR review.
3. A `## Changelog` entry appended at the bottom of this file: date, author, one-line summary of what changed and why.
4. Any skill or ADR that references the changed section is updated in a follow-up PR within 7 days.

No emergency exceptions. If the constitution needs to change to ship a release, the release waits.

---

## 11. Spec Process Reference

This project uses [spec-kit](https://github.com/github/spec-kit) (GitHub's spec-driven-development methodology) as its specification process. Spec-kit is the methodology; `_meta/spec-process.md` is the BRD-adapted manual; the manual overrides spec-kit only where the BRD-driven entry point requires it (notably `/asg-spec.specify`, which extracts from a signed BRD `.docx` rather than interviewing the user).

**Four core artifacts per feature** (per spec-kit):

| Artifact | Lives at | Authored by |
| --- | --- | --- |
| `constitution.md` | `backend/.vault/_meta/constitution.md` (this file) | `/asg-spec.constitution` |
| `spec.md` | `specs/<EPIC-ID>/spec.md` | `/asg-spec.specify` |
| `plan.md` | `specs/<EPIC-ID>/plan.md` | `/asg-spec.plan` |
| `tasks.md` | `specs/<EPIC-ID>/tasks.md` | `/asg-spec.tasks` |

Optional sidecars (per spec-kit): `clarifications.md`, `checklists/<domain>.md`, `research.md`, `data-model.md`, `quickstart.md`, `contracts/`, `analysis.md`.

**Directory layout:** one folder per EPIC under `specs/<EPIC-ID>/`. The previous flat-file layout (`AUTH-01.S07-change-email.md`, etc.) is deprecated — see `_meta/spec-process.md` §3.

**Nine `/asg-spec.*` commands** mirror spec-kit's slash commands: `constitution`, `specify`, `clarify`, `plan`, `tasks`, `checklist`, `analyze`, `taskstoissues`, `implement`. The canonical command prompts are cached at `/tmp/speckit-templates/commands/`; ASG uses them verbatim with one insertion — the BRD preamble at the top of `/asg-spec.specify`, documented in `_meta/spec-process.md` §5.3.

**ADR gate on /asg-spec.plan** (ASG-added on top of spec-kit's Constitution Check): every plan.md lists every ADR the work touches and affirms it OR proposes a superseding ADR; the superseding ADR PR must merge before the plan is approved. See `_meta/spec-process.md` §7.

---

## Changelog

- 2026-06-05 — shashank — Initial ratification.
- 2026-06-05 — shashank — Added §11 Spec Process Reference adopting spec-kit (https://github.com/github/spec-kit) methodology. §§1–10 unchanged.
