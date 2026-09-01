# ADR-0044 — Where shared platform code lives, and what is not allowed in it

- **Status:** Accepted
- **Date:** 2026-09-01
- **Supersedes:** nothing
- **Related:** ADR-0043 (configuration-service own repo), `java-architecture` SKILL §7.7

## Context

The move from monorepo to one-repo-per-service left cross-cutting code with no home, and the
symptoms were already visible:

- The CVE pin block had been copy-pasted into five service poms and had drifted.
  `ASG-Edgeplus-Api-Gateway` was missing the spring-security and spring-kafka pins entirely;
  Documents-Service carried three of six. A new CVE meant five hand edits.
- `ASG-Edgeplus-Api-Gateway` and `ASG-Edgeplus-Documents-Service` were scaffolded with no JaCoCo,
  no PIT and no ArchUnit — silently below Constitution §4.3.
- `TenantId`, `Money` and `Result` existed only inside user-service, so a new service either
  re-typed them or reached across a repo boundary.
- Reading the gateway's identity headers was about to be implemented a second time, differently.

The question asked was the right one: *what happens when somebody updates it, or a CVE forces an
update and everything breaks?* Copy-by-hand does not survive that.

The opposite failure is equally real and much harder to undo. A repo called `common` accumulates
domain types, and once two services share a `MasterValue` they must be released together — a
distributed monolith with none of a monolith's advantages.

## Decision

A single repo, `ASG-EdgePlus-Platform`, holds shared build configuration and cross-cutting
infrastructure. Admission is decided by one question:

> **Would changing this force two services to deploy together? If yes, it cannot be a shared jar.**

Three modules, each of which passes that test:

1. `asg-edgeplus-parent` — parent POM. Build configuration is not runtime coupling: a service picks
   up a new parent version when it chooses to.
2. `asg-edgeplus-primitives` — `TenantId`, `Money`, `Result`. Value objects carrying no business
   rules. Changing one is a semantic change nobody wants independently. The package is kept as
   `com.asg.edgeplus.platform.domain.primitives` — the same package the classes had inside
   user-service — so adoption is *delete four files, add one dependency*, zero import changes.
3. `asg-edgeplus-starter-identity` — Spring Boot auto-configuration turning the gateway's
   `X-User-Id` / `X-Tenant-Id` / `X-Roles` into an injectable `CallerContext`, failing closed. One
   correct answer to one question the gateway has already decided.

Explicitly **not** admitted, and to be duplicated instead:

- Anything with a service's business rules in it. Per SKILL §7.7 there is no `shared-domain`.
- Event payload types. Producer and consumer copies drift on purpose; contracts are shared as
  schemas in a registry (Avro/Protobuf), not as Java classes. This was reconsidered for
  `AuditEventMessage` — the producer/consumer diff is javadoc-only, which looks like a perfect
  extraction candidate — and rejected: the identical-today diff is what makes the coupling invisible
  until the day one side needs a field.
- DTOs and API contracts.

Propagation is Renovate, not hand-editing: bump a pin here, cut a release, let Renovate open the PRs
in each consumer. A version pinned in a service pom is a defect; the remedy is a new parent release,
or a documented `<properties>` override in that service with a comment explaining why.

## Consequences

**Good.** One edit per CVE. Every service inherits the §4.3 gates without opting in. The JDK policy
(Corretto 21 target, 24 verified in CI) is decided once. Identity cannot be re-implemented
divergently. Adoption is nearly free because packages were preserved.

**Cost.** A release cycle now stands between a platform change and a service consuming it — which is
the intended friction, not a defect. The parent must be published to GitHub Packages before any
consumer's CI can go green; `ASG-Edgeplus-Configuration-Service` is blocked on exactly that today.
And this repo needs a gatekeeper: the admission test above is easy to state and easy to erode one
"just this one class" at a time. It is repeated in the README and in `CLAUDE.md` for that reason.

**Rejected alternatives.** A git submodule of shared sources (compiles into each service, so no
version boundary at all, and no CVE pin consolidation). A `common` jar with no admission rule (the
distributed monolith). Copy-and-paste with a linter to detect divergence (detects the symptom after
the fact; does nothing about the five-poms-per-CVE problem).
