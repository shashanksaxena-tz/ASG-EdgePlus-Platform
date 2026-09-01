# ASG Edge+ Platform — Operating Index

Shared build configuration and cross-cutting infrastructure for every ASG Edge+ Java service.
**Contains no domain code and must never contain any.**

Open Claude Code **in this directory** so `.claude/skills/` is in scope.

## The rule that decides what may live here

> Would changing this force two services to deploy together? If yes, it cannot be a shared jar.

That is the whole admission test, and it is the reason there is no `asg-edgeplus-common`. See
`java-architecture` SKILL §7.7: one service = one bounded context = one aggregate family; share
contracts via events (schemas in a registry), never Java types. A shared `MasterValue` or a shared
`AuditEventMessage` would be a bounded context smashed flat, and the fix later is a rewrite.

Deliberately duplicated rather than shared: event payload types (producer and consumer copies drift
on purpose, and the drift is the seam), anything with a service's business rules in it, and DTOs.

## Modules

| Module | What | Why it passes the test |
| --- | --- | --- |
| `asg-edgeplus-parent` | Parent POM: CVE pins, quality gates, JDK policy, plugin management | Build config, not runtime |
| `asg-edgeplus-primitives` | `TenantId`, `Money`, `Result` | Value objects with no business rules; changing one is a semantic change nobody wants independently |
| `asg-edgeplus-starter-identity` | Auto-configured `CallerContext` from the gateway's identity headers | One correct answer to one question, and the gateway already decided it |

`asg-edgeplus-primitives` keeps the package `com.asg.edgeplus.platform.domain.primitives` — the same
package the classes had inside user-service — so adopting it is *delete four files, add one
dependency*, with zero import changes.

## Why this repo exists

The parent POM's CVE pin block is the concrete reason. Before it, the same pins were copy-pasted
into every service pom and had already drifted: `ASG-Edgeplus-Api-Gateway` was missing the
spring-security and spring-kafka pins entirely, and Documents-Service carried three of six. A new
CVE meant editing five poms by hand and hoping nobody missed one.

Likewise the gates: Api-Gateway and Documents-Service were both scaffolded with no JaCoCo, no PIT
and no ArchUnit — silently below Constitution §4.3. A service that extends this parent gets them
without opting in.

## JDK policy

Two numbers, deliberately separate, both in `asg-edgeplus-parent/pom.xml`:

- `java.version` = **21** — the bytecode target, and the runtime in every service Dockerfile.
- `java.next.version` = **24** — additionally built and tested in CI, so a runtime bump is a
  decision rather than a discovery.

`maven.compiler.release` is set (not just source/target), so the target is independent of the JDK
running Maven. Amazon Corretto everywhere: it is what the AWS base images ship. An enforcer rule
fails at `validate` on any JDK outside `[21, 24.0.1)`.

`./mvnw -Pjava24` targets 24 for one build. `./mvnw -Ptoolchain` builds through registered Corretto
toolchains (`toolchains.xml.sample`). The `jdk24-runtime` profile auto-activates on JDK 24+ and adds
the Surefire flags Mockito's inline mock maker needs there.

JDK-sensitive pins: JaCoCo **0.8.13** (0.8.12 aborts on a 24 JVM), PIT **1.19.1** (1.15.0 predates
JDK 22, and PIT runs on the JDK under test). Error Prone and NullAway have version properties but
are **not** wired into the compiler plugin — Error Prone uses javac internals and needs a matching
release plus `--add-exports` per JDK. Enabling it is its own piece of work.

## Build

```bash
./mvnw -N install && ./mvnw install -Ddocker.available=true
```

`-Ddocker.available=true` is **not optional**: the `no-docker` profile sets `skipTests=true`, so a
plain `./mvnw verify` runs zero tests and reports success. That profile is inherited from the
existing services deliberately (adopting this parent must change nothing for them) but it is a trap,
and dropping `<skipTests>` belongs in its own PR against one service.

## Releasing

Tag `vX.Y.Z`. `release.yml` runs `versions:set` from the tag and deploys to GitHub Packages.
Consumers pick it up via Renovate. **Nothing downstream can resolve this parent until the first
release exists** — `ASG-Edgeplus-Configuration-Service` CI is blocked on it.

When bumping a CVE pin: edit here, cut a release, let Renovate open the PRs. Do not pin a version in
a service pom. If you find yourself wanting to, the answer is a new parent release — or a documented
`<properties>` override in that service with a comment saying why.
