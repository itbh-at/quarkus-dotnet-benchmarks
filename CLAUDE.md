# CLAUDE.md

## Project Overview

Independent performance benchmarking suite comparing Quarkus variants against
dotnet, across multiple Java and dotnet runtime versions. **Forked** from
`spring-quarkus-perf-comparison` — upstream tracking is maintained via
`upstream.yml`.

## Modules

| Module                  | Framework                   | Notes                                  |
| ----------------------- | --------------------------- | -------------------------------------- |
| `apps/quarkus-jvm/`     | Quarkus 3.x JVM mode        | RESTEasy, Hibernate ORM Panache        |
| `apps/quarkus-native/`  | Quarkus 3.x native image    | GraalVM/Mandrel                        |
| `apps/quarkus-virtual/` | Quarkus 3.x virtual threads | JVM mode, virtual threads              |
| `apps/dotnet/`          | dotnet version              | Equivalent domain: Fruit/Store/Address |

All modules share the same domain: `org.acme` (Java) / equivalent namespace
(dotnet) with Fruit/Store/Address entities, DTOs, mappers, and a REST
controller.

## Build

All runtimes managed via `mise`. No `.mise.toml` or `.tool-versions` — versions
are always explicit in scripts:

```sh
# Quarkus (JVM)
mise exec java@21 -- ./mvnw -Djava.version=21 -Dmaven.compiler.release=21 clean verify -pl apps/quarkus-jvm

# Quarkus (native)
mise exec java@21 -- ./mvnw -Djava.version=21 -Dmaven.compiler.release=21 clean verify -pl apps/quarkus-native -Pnative

# dotnet
mise exec dotnet@10 -- dotnet build apps/dotnet-net10
```

The Java build and run version is to be passed to the build process in
accordance with the selected JVM version.

## Runtime Version Management

All runtimes managed via `mise`. No `.mise.toml` or `.tool-versions` — versions
are always explicit in scripts:

```sh
mise exec java@21 -- java -jar apps/quarkus-jvm/target/*.jar
mise exec dotnet@10 -- dotnet run --project apps/dotnet-net10
```

## Infrastructure

PostgreSQL on `localhost:5432`. Managed via:

```sh
cd benchmark-scripts/local
./infra.sh -s   # Start DB, create tables, seed data
./infra.sh -d   # Stop DB
```

## Benchmark Scripts

| Location                    | Purpose                             |
| --------------------------- | ----------------------------------- |
| `benchmark-scripts/local/`  | Run locally against localhost       |
| `benchmark-scripts/remote/` | Rsync'd to remote target, run there |

Deploy to remote:

```sh
rsync -avz benchmark-scripts/remote/ user@remote-target:/opt/benchmarks/scripts/
```

Run on remote:

```sh
bash /opt/benchmarks/scripts/run-full-benchmark.sh
```

## Data Flow

```txt
Remote run → benchmark.log (structured key:value)
  → rsync back locally into results/YYYY-MM-DD/{variant}-{runtime}-run{N}/
  → jbang analysis-scripts/ImportBenchmark.java --date YYYY-MM-DD
  → database/benchmarks.h2.db + database/benchmarks.sql (committed)
  → jbang analysis-scripts/GenerateReport.java --date YYYY-MM-DD
  → reports/YYYY-MM-DD-report.html
```

## Analysis Scripts (JBang)

```sh
jbang analysis-scripts/ImportBenchmark.java --date YYYY-MM-DD
jbang analysis-scripts/GenerateReport.java --date YYYY-MM-DD
jbang analysis-scripts/CompareVersions.java --app quarkus-jvm
jbang analysis-scripts/CheckUpstreamDivergence.java
```

## Upstream Tracking

`upstream.yml` records the upstream commit this codebase was last reviewed
against and documents intentional divergences. Run divergence check with:

```sh
jbang analysis-scripts/CheckUpstreamDivergence.java
```

Exit code 1 = unreviewed divergences found. Exit code 0 = clean or all
divergences are intentional.

## What Gets Recorded Per Run

- **Hardware**: CPU model, architecture, physical/logical cores, frequency, total RAM
- **OS**: name, version, kernel, CPU governor, transparent huge pages, load average
- **Cgroup**: memory limit, CPU set (if containerized)
- **JVM**: heap min/max, GC type/threads, JIT mode, raw JVM flags (secrets scrubbed)
- **dotnet**: GC mode, heap limits (configured + effective), thread pool, tiered compilation
- **Benchmark config**: load generator, threads, connections, duration, RPS target
- **Database**: PostgreSQL version, pool size

## Security

JVM flags are scrubbed on import — any flag matching `*password*`, `*secret*`,
`*key*`, `*token*`, `*credential*` patterns is replaced with `[REDACTED]`. Only
whitelisted `DOTNET_*` environment variables are captured.

## Conventions

- Application code must maintain parity across all modules (same domain, same
  endpoints, same behavior)
- Config files use YAML (`application.yml`) in all Quarkus modules
- Benchmark log format: structured `key: value` sections (ENVIRONMENT, HARDWARE,
  OS, JVM CONFIG, DOTNET CONFIG, BENCHMARK CONFIG, RESULTS)
- `database/benchmarks.sql` is the version-controlled source of truth for all
  results
- `database/benchmarks.h2.db` is gitignored (binary); regenerate with `jbang
  analysis-scripts/ImportBenchmark.java --rebuild`
