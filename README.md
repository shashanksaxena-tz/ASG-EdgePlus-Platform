# ASG Edge+ Platform

Shared build configuration and cross-cutting infrastructure for ASG Edge+ services.

**This repository contains no domain code, and that is a rule rather than a coincidence.**

## The rule that decides what may live here

> **Would changing this force two services to deploy together?**
> If yes, it cannot be a shared jar. It is either an event contract — copied per service and evolved
> additively — or it does not belong in a library at all.

`java-architecture` SKILL §7.7 is explicit and correct: *"Do not extract a `shared-domain` library
across services; that's a bounded context smashed flat. Share contracts via events, not Java
types."* Nothing here contradicts that. What lives here is the layer §7.7 does not speak to: build
configuration and technical infrastructure that every service must implement **identically**.

| Belongs here | Does **not** belong here |
|---|---|
| Dependency versions and CVE pins | Aggregates, entities, repositories |
| Quality gates (JaCoCo, PIT, ArchUnit, Spotless) | Business rules of any kind |
| Reading the gateway's identity headers | A shared `User` or `Firm` type |
| The RFC 7807 error *shape* | A service's `GlobalExceptionHandler` (923 lines of domain mapping in user-service, 48 in audit-service — they are not the same thing) |
| Logging/MDC/OTel plumbing | Event message types — copy those per §7.7 |
| `TenantId`, `Money`, `Result` | Anything whose meaning differs between contexts |

## Why it exists

The polyrepo split left the same build configuration copy-pasted into every service pom, and it had
already drifted:

| | tomcat | netty | spring-framework | spring-security | spring-kafka |
|---|---|---|---|---|---|
| User-Service | 10.1.55 | 4.1.136 | 6.2.19 | pinned | 3.3.16 |
| Audit-Service | 10.1.55 | 4.1.136 | 6.2.19 | pinned | 3.3.16 |
| **Api-Gateway** | n/a | 4.1.136 | 6.2.19 | **missing** | **missing** |
| **Documents-Service** | **3 of 6 pins** | | | | |

A new CVE meant editing five poms by hand and hoping nobody missed one. That is a security posture,
not a tidiness problem.

Separately, the two newest repos were scaffolded with **no JaCoCo, no PIT and no ArchUnit** — silently
below Constitution §4.3's line ≥ 95 % / branch ≥ 90 % / mutation ≥ 85 %. Inheriting this parent makes
those gates the default rather than something each service remembers to add.

## Modules

| Module | What it is |
|---|---|
| `asg-edgeplus-parent` | The POM every service sets as its `<parent>`. Versions, CVE pins, quality gates, plugin configuration. No code. |
| `asg-edgeplus-primitives` | `TenantId`, `Money`, `Result`. Pure Java, no Spring. Lifted verbatim from user-service, same package, so adopting it is a delete-plus-dependency with zero import changes. |
| `asg-edgeplus-starter-identity` | Reads the gateway's `X-User-Id` / `X-Tenant-Id` / `X-Roles` into a `CallerContext`. Auto-configured. |

## Using it

```xml
<parent>
  <groupId>com.asg.edgeplus.platform</groupId>
  <artifactId>asg-edgeplus-parent</artifactId>
  <version>1.0.0</version>
  <relativePath/>
</parent>

<properties>
  <!-- so the parent's PIT config targets your packages -->
  <pitest.targetPackage>com.asg.edgeplus.configurationservice</pitest.targetPackage>
</properties>

<dependencies>
  <dependency>
    <groupId>com.asg.edgeplus.platform</groupId>
    <artifactId>asg-edgeplus-starter-identity</artifactId>
    <!-- no version: the parent manages it -->
  </dependency>
</dependencies>
```

Declare dependencies without versions. If you find yourself wanting to pin one in a service pom, the
answer is a new parent release — or a documented `<properties>` override with a comment saying why.

## Java: two JDKs, one bytecode target

Amazon Corretto is the only JDK distribution here — it is what the AWS base images ship, so the
JDK that compiles is the JDK family that runs. Two versions matter, and they are not the same
knob:

| | property | value | what it means |
|---|---|---|---|
| Baseline | `java.version` | **21** | the bytecode target, and the runtime in every service Dockerfile |
| Forward-compat | `java.next.version` | **24** | additionally built and tested in CI, so a runtime bump is a decision, not a discovery |

`maven.compiler.release` is set (not just `source`/`target`), so the target is independent of the
JDK running Maven: the same sources produce 21 bytecode on Corretto 21 *and* on Corretto 24.
That separation is the point — `asg-edgeplus-primitives` and `asg-edgeplus-starter-identity` are
consumed by services on a Java 21 JRE, and a class file compiled for 24 will not load there at
all.

### What CI does

- **`verify` (Corretto 21)** — the merge gate.
- **`forward-compat` (Corretto 24, advisory)** — the same build on 24 with the target still 21,
  then a second pass with `-Pjava24` to show what a target move would cost. Advisory until it has
  been green for a release cycle; then flip `continue-on-error` off.

### Locally

```bash
./mvnw verify                       # uses JAVA_HOME; enforcer rejects anything outside [21, 24]
./mvnw -Pjava24 verify              # compile and test to Java 24 bytecode (needs a JDK 24)
./mvnw -Ptoolchain verify           # use registered Corretto toolchains instead of JAVA_HOME
./mvnw -Ptoolchain -Pjava24 verify  # ...targeting 24
```

Always `./mvnw`, never a system `mvn`. The wrapper (`only-script`, no jar in the repo) pins Maven
**3.9.16** in `.mvn/wrapper/maven-wrapper.properties`, so a laptop, a CI runner and a Docker build
all run the same Maven — which is the one thing that makes "works on my machine" reproducible. CI
calls `./mvnw` too, on purpose: a CI that used its own `mvn` would defeat the pin. The wrapper needs
no local Maven install, only a JDK. (The estate is split today: Api-Gateway is on 3.9.16,
User-Service, Documents-Service and Audit-Service still on 3.9.14 — worth aligning in their own PR.)

Copy `toolchains.xml.sample` to `~/.m2/toolchains.xml` and fix the two `jdkHome` paths to use
`-Ptoolchain`. Install both JDKs with `sdk list java | grep amzn` then `sdk install java <ver>-amzn`,
or from <https://aws.amazon.com/corretto/>.

A `maven-enforcer-plugin` rule fails the build at `validate` on anything outside `[21, 24.0.1)`
with a message telling you what to install. That is deliberate: several ASG dev machines still
default to JDK 11, and without the rule the build dies deep inside an unrelated plugin.

### Moving the baseline forward

1. `forward-compat` green (both passes) for a full release cycle.
2. Bump `java.version` to 24 here; cut a parent release.
3. Only then update the service Dockerfiles. Never the other way round — a service whose image
   runs 24 while the platform jars target 21 works; the reverse does not.

Three tools are JDK-version-sensitive and are pinned accordingly: **JaCoCo 0.8.13** (0.8.12 aborts
on a 24 JVM), **PIT 1.19.1** (1.15.0 predates JDK 22 and PIT has to run on the JDK under test), and
the `jdk24-runtime` profile, which activates on any JDK ≥ 24 and adds
`-XX:+EnableDynamicAgentLoading -Dnet.bytebuddy.experimental=true` to Surefire so Mockito's inline
mock maker keeps working. Error Prone and NullAway have version properties but are **not** wired
into the compiler plugin — Error Prone reaches into javac internals and needs a matching release
plus `--add-exports` for each new JDK, so turning it on is its own piece of work.

## Identity: what a service actually gets

The gateway (`ASG-Edgeplus-Api-Gateway`) validates the Cognito JWT, then sets three headers and
**strips `Authorization`**. Its `IdentityHeaderFilter` javadoc is explicit: *"the token itself is
never forwarded past this point — internal services trust the gateway-set headers, not a
re-presented user token."* So a service must never try to validate a JWT; there isn't one.

```java
@GetMapping("/v1/master-types")
public MasterTypePage list(CallerContext caller) {
  TenantId firm = caller.requireTenant();      // X-Tenant-Id, from the JWT's tid claim
  UUID actor    = caller.userId();             // X-User-Id — the audit actor
  boolean admin = caller.hasRole("SYSADMIN");  // X-Roles
  ...
}
```

Two deliberate design points:

- **`tenantId` is nullable.** A platform-level caller acting outside any single firm legitimately has
  no active tenant, so each endpoint decides explicitly via `hasTenant()` / `requireTenant()` rather
  than inheriting an invented default.
- **It fails closed.** On a non-permit-all path, absent or malformed headers mean the request did not
  come through the gateway — so it is rejected, not served with a guessed identity. Keep
  `asg.platform.identity.permit-all-paths` aligned with the gateway's own
  `asg.gateway.security.permit-all-paths`.

## What happens when this is updated

That question is the whole point of the repo, so it has a definite answer per artifact type.

**The parent POM and the starters** are versioned artifacts published to GitHub Packages. Consumers
pin an exact version; Renovate opens a PR per repo; each service's own CI gates it; each team merges
on its own schedule. **No lockstep, ever** — these are compile-time only, so a bump never requires a
coordinated deploy.

> Renovate currently exists only in the old monorepo and reached none of the new repos. Until it is
> added per repo, nothing propagates automatically and this repo's value is halved. That is the first
> follow-up, and it is one file per repo.

**Versioning** is semver on the parent. Additive changes are minor; removing or renaming anything is
major, and majors should be rare. Deprecate for at least one minor before removing.

**Event contracts are not here on purpose.** Per §7.7 each service keeps its own copy and evolves it
additively (`java-messaging` §3). That is safer than sharing a jar, not merely more orthodox: with a
shared type, renaming a field compiles cleanly and breaks at runtime across every service; with
copies, each side's schema change is an explicit, reviewed act. The remaining gap is that the copies
are made by hand — the standard fix is schema-first (Avro or Protobuf in a schemas repo, each service
**generating** its own classes, with a registry rejecting incompatible changes in CI). Worth doing;
out of scope for this repo.

## Adopting it in an existing service

1. Point `<parent>` at `asg-edgeplus-parent` and delete every `<properties>` version pin, the whole
   `<dependencyManagement>` block for managed artifacts, and the `<pluginManagement>` block.
2. Set `<pitest.targetPackage>`.
3. For user-service specifically: delete
   `src/main/java/com/asg/edgeplus/platform/domain/primitives/` and add the `asg-edgeplus-primitives`
   dependency. The package is identical, so no import changes.
4. Run `./mvnw -B verify` and compare the dependency tree before and after
   (`./mvnw dependency:tree`) — the pins should be unchanged for user-service and audit-service, and
   should *gain* the missing spring-security and spring-kafka pins for the gateway.
