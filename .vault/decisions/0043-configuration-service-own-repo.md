---
id: dec-0043
type: decision
tags: [#decision, #configuration-service, #masters, #page-layouts, #repo-topology]
date: 2026-08-31
authors: ["[[shashank]]", "[[claude]]"]
supersedes: ["[[0041-issue-909-page-layouts-lightweight-backend-poc]]"]
status: active
linked-commits: []
linked-tickets: ["[[909]]", "[[916]]"]
linked-components: ["[[user-service]]"]
---

# Masters + Page/List Layout get their own repo: `ASG-Edgeplus-Configuration-Service`

## Context

`specs/MDM-01/spec.md` (Master Data Management) and `specs/PAGE-LAYOUTS-01/spec.md` (Page and List
Custom Layout) were both written assuming a `backend/services/configuration-service` module inside
this monorepo. Since then the platform has begun splitting into one repo per deployable, which makes
"which Maven module" the wrong question — it is now "which repository".

### Observed state of the split, 2026-08-31

All five service repos were pushed within 7–11 hours of each other; the monorepo is still active in
parallel (486 open issues, 8 open PRs). This is mid-migration, not a completed one.

| Repo | Commits | First commit | Java files | Maturity |
|---|---|---|---|---|
| `ASG-Edgeplus-User-Service` | 723 | 2026-06-03 | 679 | **Migrated with full history.** Own `pom.xml` on `spring-boot-starter-parent`, `catalog-info.yaml`, `runbooks/`, 71 migrations |
| `ASG-Edgeplus-Audit-Service` | 104 | 2026-06-08 | 54 | **Migrated with full history.** `catalog-info.yaml`, `retention.yml`, 7 migrations |
| `ASG-Edgeplus-Api-Gateway` | 16 | 2026-08-24 | 17 | **New and real** — see below. Carries `specs/EPIC-881/spec.md` |
| `ASG-Edgeplus-Documents-Service` | 11 | 2026-08-26 | 1 | **Scaffold** — one `Application` class; the current new-service skeleton |
| `ASG-Edgeplus-UI` | 519 | 2026-06-03 | — (315 ts/tsx) | Migrated with full history; own `.vault/`, `designs/`, `locators/` |

Three findings from that survey change decisions rather than merely describe them.

**1. The API gateway exists and enforces authentication.** Previous verification recorded "no API
gateway — standing gap", and both services' `SecurityConfig` javadoc has always *asserted* one
("the gateway enforces JWT presence at the edge in any non-local profile") while none existed. That
is no longer true. `ASG-Edgeplus-Api-Gateway` runs Spring Cloud Gateway with:

- `NimbusReactiveJwtDecoder.withJwkSetUri(issuerUri + "/.well-known/jwks.json")` plus
  `JwtValidators.createDefaultWithIssuer`, and `anyExchange().authenticated()`.
- `InboundHeaderScrubFilter` — strips `X-User-Id`, `X-Tenant-Id`, `X-Roles`, `X-Internal` from every
  inbound request so a client cannot forge them.
- `IdentityHeaderFilter` (`HIGHEST_PRECEDENCE + 1`) — sets, from the validated JWT,
  `X-User-Id` = `jwt.getSubject()`, `X-Tenant-Id` = claim `tid`, `X-Roles` = comma-joined `roles`
  claim, **and removes the `Authorization` header**. Its javadoc is explicit: *"the token itself is
  never forwarded past this point — internal services trust the gateway-set headers, not a
  re-presented user token."*
- `ProblemDetailWriter` emitting RFC 7807 with `code`, `traceId`, `tenantId` (Constitution §4.5).

**2. There is no shared platform library, and the primitives have been duplicated.** No new repo
depends on a published `domain-primitives` artifact. `ASG-Edgeplus-User-Service` instead contains
`com.asg.edgeplus.platform.domain.primitives.{TenantId, Money, Result}` as source files — the same
package as the old shared module, now a copy. Audit-service and the gateway carry none. ADR-0013's
"eight shared SDKs" is now definitively dead rather than merely unbuilt.

**3. Quality gates did not survive into the new-born repos.** The migrated services kept them; the
scaffolds did not.

| Repo | JaCoCo | PIT | ArchUnit |
|---|---|---|---|
| User-Service | 0.95 line / 0.90 branch | `mutationThreshold` 85 | yes, 1 arch test |
| Audit-Service | 0.95 line / 0.90 branch | present | yes, 1 arch test |
| Api-Gateway | **none** | **none** | **none** |
| Documents-Service | **none** | **none** | **none** |

CI is `./mvnw -B verify` on a self-hosted runner (`taazaa.shared.runner.01`) inside
`vars.SONAR_JAVA_IMAGE`. CD is per-repo and replaces the monorepo's `.github/services.json` matrix
with a `.github/environments.json` carrying a dev → qa → uat → production promotion chain
(`source_env`, `src_tag_regex`, `namespace`, `host`, `registry_prefix`), plus two composite actions,
`helm-gitops-commit` and `smoke-test`. Only `dev` has `exists: true` today.

**4. The Page Layouts POC never made the jump.** `grep -ci pagelayout` across all eight branches of
`ASG-Edgeplus-User-Service` returns 0. The 6,287-line `pagelayouts` sub-module from dec-0041 exists
only on the monorepo branch `feature/909-page-layouts-backend-poc`, which is now stranded on a
repository being dismantled.

## Decision

**Masters and Page/List Layout ship as one new repository, `ASG-Edgeplus-Configuration-Service`.**
Not a module in `ASG-Edgeplus-User-Service`, and not two separate repos.

### Why its own repo, not user-service

1. **The convention is one repo per deployable** — five for five. A bounded context gets a
   repository, not a folder inside another service.
2. **Wrong bounded context.** `user-service` is identity and auth. Masters is read at runtime by
   every other service — every dropdown in the product resolves through it. The Preamble names
   *"shared notification, **configuration**, and identity services"* as three separate shared
   services in one sentence; P6 requires a reusable service to be operationally independent of any
   specific UI module.
3. **The argument for folding in has evaporated.** In the monorepo, a standalone service had to
   justify a `services.json` entry, an ECR repository, a Sonar project and an edit in an external
   Helm repo. In polyrepo every service pays exactly that cost by definition, so the marginal cost
   of a new repo is now the *standard* cost rather than an exception.
4. **Blast radius.** Masters brings CSV import, a scheduled effective-date sweep, and a
   cross-tenant bulk write path (MST-031 Cross-Client Scope). None of that belongs in the
   security-critical auth service.

This supersedes dec-0041's second scope answer ("new standalone service vs extend `user-service`.
User chose **extend user-service**"). That was a defensible choice for an explicitly lightweight POC
and dec-0041 said so itself: *"Any move from POC to production should revisit all of this
explicitly, likely via a real `/spec` pass."* Both specs now exist; this is that revisit.

### Why one repo for both modules, not two

`MDM-01` and `PAGE-LAYOUTS-01` are two epics and two BRDs, so two repos is the tidier reading of
bounded contexts. It is rejected on the hot path: a dropdown-typed `FieldDefinition` resolves its
options through a `MasterType` (D-03), and the layout render endpoint is the most-requested endpoint
in the product with a 200 ms p95 target (MDM-01 SC-001). Splitting them puts a network call on that
path per dropdown-typed field — a Contract page with twenty dropdowns becomes twenty calls, or a
batch call plus a cache plus a new failure mode.

Keep them together now. Splitting a repository later is cheap; un-splitting a hot path is not. Revisit
if Masters grows its own team, or if the import/sweep workload needs to scale independently of the
layout read path.

### How the repo is scaffolded

- **Structure from `ASG-Edgeplus-Documents-Service`** — its 17 files are the current new-service
  skeleton: five workflows (`ci-gate`, `cd-dev`, `cd-hotfix`, `cd-repoint`, `repo-hygiene`), the two
  composite actions, `.github/environments.json`, `Dockerfile`, `mvnw`, and a minimal `pom.xml` on
  `spring-boot-starter-parent` with `groupId com.asg.edgeplus`, Java 21, web + actuator + test.
- **Quality block from `ASG-Edgeplus-User-Service`'s `pom.xml`, not from the scaffold.** The scaffold
  omits JaCoCo, PIT and ArchUnit; Constitution §4.3 still mandates line ≥ 95 %, branch ≥ 90 %,
  domain + application ≥ 98 %, PIT ≥ 85 %, and both specs' SC-008 / SC-010 depend on them. Copy
  user-service's plugin configuration verbatim so the new service starts gated rather than
  retro-fitted.
- **`catalog-info.yaml` and `runbooks/`** from user-service (Constitution §4.10 — no service ships
  without them).
- `image_base: configuration`, `sonar_key: ASG_EdgePlus_ConfigurationService`, and an ECR repository
  under the existing `257526644541.dkr.ecr.us-east-2.amazonaws.com/asg-edgeplus/` registry.
- **The specs move with the service.** `ASG-Edgeplus-Api-Gateway` carries `specs/EPIC-881/spec.md`,
  so the convention travelled. `specs/MDM-01/spec.md` and `specs/PAGE-LAYOUTS-01/spec.md` relocate
  from the monorepo into this repo.

### Identity: consume the gateway's headers, do not re-validate a token

This is now a settled contract rather than an open question. The service:

- Reads the **actor** from `X-User-Id` (audit `changed_by`, MST-032).
- Reads the **active firm context** from `X-Tenant-Id` — this is `firmId`, and it is what MST-030's
  "user's currently active firm context" and MST-031's cross-firm 403 are enforced against.
- Reads **roles** from `X-Roles` (comma-separated) and maps them onto MST-029's four permissions
  (View Masters / Edit Values / Manage Schema / Approve Changes) behind `AuthorizationPort`.
- **Must not** attempt JWT validation or read `Authorization` — the gateway strips it. A
  `JwtDecoderConfig` in this service would be dead code.
- **Must** reject a request arriving without those headers rather than defaulting, since their
  absence means the request bypassed the gateway. `InboundHeaderScrubFilter` guarantees a client
  cannot forge them *through* the gateway; it guarantees nothing about traffic that never went
  through one.

## Consequences

- **The largest risk in both specs is materially reduced.** MDM-01 §17 Q-007 and §18 Risk 1, and
  PAGE-LAYOUTS-01 §17 Q-004 and Risk 3, were all written around "authorisation does not exist and
  the Preamble forbids building it locally". Authentication and identity propagation now exist. What
  remains genuinely absent is *authorisation* — role → permission evaluation as a centralised
  platform service (Preamble P1). `X-Roles` gives this service the caller's roles; nothing yet tells
  it which permissions a role holds. So `AuthorizationPort` still ships behind an adapter, but the
  adapter now reads real roles instead of a stub token.
- **ADR-0004 goes from one layer to three.** JWT claim validation (gateway) ✓, gateway filter ✓,
  row-level `firm_id` checks ✓ (this service). Only the JPA tenant filter is still missing. Update
  MDM-01 drift D-1 / Q-006 and PAGE-LAYOUTS-01 drift D-1 accordingly.
- **MDM-01 §11's event contract survives unchanged** — both migrated services still publish
  `${SPRING_PROFILES_ACTIVE:local}.governance.audit-log.captured.v1`, so the audit topic and its
  `entityType` discriminator plan hold.
- **The primitives problem is now this service's problem too.** With no published artifact, this repo
  either copies `TenantId`/`Money`/`Result` (following user-service, accepting the drift) or someone
  publishes `domain-primitives` to CodeArtifact / GitHub Packages first. Copying is the pragmatic
  choice for one more service; it is the wrong choice at five, and it is already at two. **Raise this
  as a platform decision rather than settling it inside this service.**
- **PAGE-LAYOUTS-01 Phase 0 changes shape.** T101 was "migrate the `pagelayouts` sub-module out of
  `user-service`". Since the POC never reached the new user-service repo, there is nothing to extract
  from it. The work becomes "port the POC from the stranded monorepo branch
  `feature/909-page-layouts-backend-poc` into the new repo, or consciously abandon it" — and that
  decision now has a deadline, because the monorepo is being dismantled around it.
- **`environments.json` replaces the `services.json` cost list.** Both specs' §4 "cost of admission"
  paragraphs are stale: no `services.json` entry is needed, but a full repo scaffold, an ECR
  repository, a Sonar project and a Helm GitOps entry are. Net effort is similar, differently shaped.
- The monorepo's `README.md` §9 "Eventual split" describes a two-way backend/frontend split via
  `git filter-repo --subdirectory-filter`. Reality went per-service. §9 is stale and should be
  corrected or removed.

## References

- `specs/MDM-01/spec.md`, `specs/PAGE-LAYOUTS-01/spec.md` — the two specs this placement serves.
- `docs/plans/masters-page-layout/OPEN-DECISIONS.md` — D-01…D-20, and A-02 (branch disposition),
  which this ADR partially answers.
- `docs/plans/masters-page-layout/MASTERS_PAGELAYOUT_IMPLEMENTATION_PLAN_v2.md` §4, §4.1 — the
  monorepo-era placement argument this ADR supersedes.
- `ASG-Edgeplus-Api-Gateway`: `src/main/java/.../filter/IdentityHeaderFilter.java`,
  `filter/InboundHeaderScrubFilter.java`, `config/SecurityConfig.java`, `specs/EPIC-881/spec.md`.
- `ASG-Edgeplus-Documents-Service`: the 17-file new-service scaffold.
- `ASG-Edgeplus-User-Service`: `pom.xml` (quality plugin block to copy),
  `src/main/java/com/asg/edgeplus/platform/domain/primitives/` (the duplicated primitives).
- [[0041-issue-909-page-layouts-lightweight-backend-poc]] — superseded on placement.
- [[0042-issue-916-page-layouts-build-layout-backend]] — its `Field` catalog and `LayoutCanvasItem`
  designs remain useful input to PAGE-LAYOUTS-01 §9; only the placement changes.
- Constitution §3 (superseding ADRs), §4.3 (coverage gates), §4.10 (catalog + runbook).
