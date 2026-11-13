# asg-edgeplus-starter-tenancy

Database-per-tenant routing for a service (KB ADR-004), with the per-service schema model of
ADR-0058: each firm has one spoke database (`asg_spoke_*`); inside it **each service owns its own
schema and its own database login and runs only its own Liquibase changelog**.

No domain types. A service supplies a schema name and a changelog; the starter knows no table.

## What a request does

```
gateway ──X-Tenant-Id──▶ GatewayIdentityFilter ──▶ TenantScopeFilter ──▶ your code ──▶ DataSource
                           (identity starter)       binds TenantScope        │            (TenantRoutingDataSource)
                                                                             ▼
                                         first use of the firm: CREATE SCHEMA + Liquibase (once, locked)
                                                                             ▼
                                                  small lazy Hikari pool on asg_spoke_<firm>, schema=<service>
```

- Caller has a tenant: **firm scope**.
- `X-Tenant-Scope: platform` and the caller holds one of `platform-scope-roles`: **platform scope**
  (served only by a bean named `platformDataSource`; otherwise refused). Any other value of the
  header is 400; lacking the role is 403.
- Neither: **nothing is bound** and any DataSource use throws `MissingTenantScopeException`.
  Platform scope is never inferred from a missing tenant (ADR-0059), and there is no hub default.
- Jobs and consumers with no request enter a scope themselves: `TenantScopes.runIn(TenantScope.firm(id), ...)`.

## Properties (`asg.platform.tenancy.*`)

| Property | Required | Meaning |
|---|---|---|
| `jdbc-url-template` | yes | `jdbc:postgresql://host:5432/{db}` |
| `database-name-template` | unless you declare a `SpokeLocator` bean | e.g. `asg_spoke_{tenant}` (`{tenant}` = firm id, 32 hex digits) |
| `schema` | yes | this service's schema, e.g. `lease` |
| `username` / `password` | yes | this service's login. No defaults |
| `changelog` | yes | classpath path of the service's master changelog |
| `admin-username` / `admin-password` | no | provisioner login used only to `CREATE SCHEMA ... AUTHORIZATION <username>`. Without it the service login creates its own schema and needs `CREATE` on the database |
| `migration-username` / `migration-password` | no (both or neither) | a second login that **owns** the schema and runs Liquibase. Request pools still connect as `username`, which then holds only what the service's changesets grant it (the changelog receives it as `${runtimeLogin}`) plus `USAGE` on the schema. Must differ from `username`. Use it when a grant has to be a real limit, e.g. an INSERT-only table: the owner of a table can always drop its triggers |
| `pool-max-size` | no (3) | connections per firm |
| `pool-idle-timeout` | no (`PT5M`) | |
| `platform-scope-roles` | no (empty) | roles allowed to request platform scope |
| `enabled` | no (true) | |

The service must also set `spring.liquibase.enabled=false` (this starter migrates per firm) and, with
JPA, `spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false` plus an explicit
`hibernate.dialect`, because nothing is routable at startup.

## Isolation guarantees (all covered by `TenancyPostgresTest`)

- Two firms never see each other's rows: separate databases.
- Each service gets its own `DATABASECHANGELOG`/`DATABASECHANGELOGLOCK` in its own schema.
- The schema is created owned by the service login with no grants to anyone else, so another
  service's login is refused with SQLSTATE `42501` on `SELECT`, `INSERT` and `CREATE`.
- Concurrent first requests for one firm migrate once; two instances racing both succeed and each
  changeset is applied once (Postgres advisory lock for schema creation, Liquibase lock for
  changesets). Different firms migrate one at a time per JVM, because Liquibase's scope stack is
  process-wide.

## Two logins (covered by `TwoLoginTenancyPostgresTest`)

With `migration-username` set, the schema, its tables and its changelog history are owned by the
migration login; requests run as `username` with only the granted privileges, so `ALTER`, `DROP`,
`CREATE` and any ungranted DML fail with SQLSTATE `42501`. A changeset grants table privileges like:

```yaml
- sql:
    sql: GRANT SELECT, INSERT ON ledger_row TO "${runtimeLogin}"
```

Before every migration the starter **verifies** the barrier and refuses the firm (503 to the
caller) unless the migration login owns the schema and the runtime login is neither a superuser nor
a member of the owner role. Login names must match `^[a-z][a-z0-9_]{0,62}$`.

**The runtime login name is fixed per environment.** Liquibase computes a changeset's checksum after
substituting `${runtimeLogin}`, so renaming the login (e.g. alternating-user secret rotation) would
fail checksum validation on every firm. Rotate the password, not the name.

## Not here

Creating the spoke **database**, the firm registry, provisioning jobs, seeding and the
`REVOKE ... FROM PUBLIC` lock-down stay in Configuration-Service (they need `CREATEDB`). A spoke
database must exist before a firm's first request.
