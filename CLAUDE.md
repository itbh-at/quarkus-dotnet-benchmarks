# CLAUDE.md

## Project Overview

Independent performance benchmarking suite comparing Quarkus variants against
dotnet, across multiple Java and dotnet runtime versions. **Forked** from
`spring-quarkus-perf-comparison` — upstream tracking is maintained via
`upstream.yml`.

## Modules

| Module                        | Framework                                       | Notes                                                                 |
| ----------------------------- | ----------------------------------------------- | --------------------------------------------------------------------- |
| `apps/quarkus-jvm/`           | Quarkus 3.x JVM mode                            | RESTEasy, Hibernate ORM Panache, OTel; `-Pnative` builds native image |
| `apps/quarkus-virtual/`       | Quarkus 3.x virtual threads                     | JVM mode, virtual threads                                             |
| `apps/dotnet-core-aspnet-ef/` | ASP.NET Core Minimal API, Entity Framework Core | Npgsql/PostgreSQL, OTel                                               |

All modules share the same domain: `org.acme` (Java) / equivalent namespace
(dotnet) with Fruit/Store/Address entities, DTOs, mappers, and a REST
controller.

## Build

All runtimes managed via `mise`. No `.mise.toml` or `.tool-versions` — versions
are always explicit in scripts:

```sh
# Quarkus (JVM)
mise exec java@21 -- ./mvnw -Djava.version=21 -Dmaven.compiler.release=21 clean verify -pl apps/quarkus-jvm

# Quarkus (native image, built from quarkus-jvm source)
mise exec java@21 -- ./mvnw -Djava.version=21 -Dmaven.compiler.release=21 clean verify -pl apps/quarkus-jvm -Pnative

# dotnet
mise exec dotnet@10 -- dotnet build apps/dotnet-core-aspnet-ef
```

The Java build and run version is to be passed to the build process in
accordance with the selected JVM version.

## Runtime Version Management

All runtimes managed via `mise` — versions are always explicit, never implied
by a config file:

```sh
# Run Quarkus JVM with a specific Java version
mise exec java@21 -- java -jar apps/quarkus-jvm/target/quarkus-app/quarkus-run.jar
mise exec java@17 -- java -jar apps/quarkus-jvm/target/quarkus-app/quarkus-run.jar

# Run Quarkus native (no JVM needed, binary is self-contained)
apps/quarkus-jvm/target/quarkus-jvm-runner

# Run dotnet with a specific runtime version
mise exec dotnet@10 -- dotnet run --project apps/dotnet-core-aspnet-ef
mise exec dotnet@8  -- dotnet run --project apps/dotnet-core-aspnet-ef
```

The benchmark scripts iterate over configured version lists and invoke each
variant with `mise exec <runtime>@<version> --`.

### Prerequisites

- **Remote target**: `mise` is installed automatically by the qDup setup chain
  (`requirements.yml` → `ensure-*` → `mise-ensure-runtime` → `mise-install`).
- **Local machine**: the user is responsible for installing `mise` (and any
  other required tooling). Local benchmark scripts that depend on `mise` must
  begin with a precondition check that **aborts** if it is missing — they must
  never silently install:

  ```sh
  command -v mise >/dev/null 2>&1 || {
    echo "ERROR: mise is required but not installed. See https://mise.jdx.dev" >&2
    exit 1
  }
  ```

## Infrastructure

PostgreSQL on `localhost:5432`. Managed via:

```sh
cd benchmark-scripts/local
./infra.sh -s   # Start DB, create tables, seed data
./infra.sh -d   # Stop DB
```

## Benchmark Scripts

| Location                                       | Purpose                                                                |
| ---------------------------------------------- | ---------------------------------------------------------------------- |
| `benchmark-scripts/local/`                     | Scripts that run on the local machine (infra, deployment, ad-hoc)      |
| `benchmark-scripts/local/deploy-to-remote.sh`  | rsync the working tree to a remote target                              |
| `benchmark-scripts/remote/run-benchmarks.sh`   | Launches qDup locally, which orchestrates the deployed remote          |
| `benchmark-scripts/remote/main.yml + helpers/` | qDup orchestration consumed by `run-benchmarks.sh`                     |

### Deploy + run workflow

The remote never pulls from git. Local edits are pushed via rsync and qDup
orchestrates execution over SSH.

```sh
# 1. Deploy the working tree (apps + scripts + RUN_INFO).
#    Target defaults to ~/quarkus-dotnet-benchmarks if no path specified.
benchmark-scripts/local/deploy-to-remote.sh perf@perf-lab.example.com

# 2. Trigger a benchmark run. qDup runs locally, drives the remote via SSH.
cd benchmark-scripts/remote
bash run-benchmarks.sh \
  --host perf-lab.example.com \
  --user perf \
  --runtimes quarkus-jvm,quarkus-native,dotnet-aspnet-ef \
  --iterations 3
```

**Iterating on yml/script changes:** edit locally, rerun
`deploy-to-remote.sh`, rerun `run-benchmarks.sh`. The `--delete` flag on rsync
keeps the remote tree in lockstep with local; transient artifacts (`logs/`,
`builds/`) on the remote are preserved across redeploys.

**HOST=LOCAL:** to run everything on the local machine (qDup self-targets via
SSH on `127.0.0.1`), pass `--host LOCAL`. No deploy needed in that case.

## Data Flow

```txt
qDup run on remote → metrics.json + per-iteration logs downloaded to <output-dir>
  → jbang analysis-scripts/ImportBenchmark.java --results <output-dir>
  → database/benchmarks.mv.db + database/benchmarks.sql (committed)
  → jbang analysis-scripts/GenerateReport.java --run-id <id>
  → reports/<id>-report.html
```

## H2 Schema

Three tables, designed so every measurement is fully labelled and aggregation
queries **must** `GROUP BY runtime_name`:

- **`runs`** — one row per qDup invocation. Holds host metadata, runtime
  versions (Java/GraalVM/Mandrel/dotnet), JVM tuning, Quarkus build options,
  CPU pinning, profiler config, and timing. Includes deploy provenance
  (`deploy_run_id` UUID, `deploy_commit`, `deploy_branch`, `deploy_dirty`,
  `deployed_at`) read from the `RUN_INFO` file generated by
  `deploy-to-remote.sh`, and the list of tests that actually ran
  (`tests_run`). Two runs are comparable when the dimensions you want to
  hold constant (host_cpu, host_memory, jvm_memory, scenario, ...) match.
- **`runtime_results`** — one row per `(run_id, runtime_name)`. Joins runs to
  metric rows and acts as the foreign-key target that prevents an orphaned
  metric losing its runtime label.
- **`iteration_metrics`** — one row per `(run_id, runtime_name, iteration,
  metric_name)`. Columns: `metric_value` (DOUBLE), `unit` (VARCHAR). Metric
  names imported from `metrics.json`: `build_time_s`, `ttfr_ms`,
  `rss_startup_mib`, `rss_first_request_mib`, `load_throughput_rps`,
  `load_rss_mib`, `load_throughput_density`, `load_connection_errors`,
  `load_request_timeouts`.

The grain of `iteration_metrics` is **one measurement, fully tagged**. There
is no "metric without a runtime" or "iteration without an index" — the schema
makes the most error-prone analysis mistake (confusing iterations across
variants) syntactically impossible.

## Analysis Scripts (JBang)

```sh
# Import a qDup output directory (default: ./database/benchmarks.{mv.db,sql})
jbang analysis-scripts/ImportBenchmark.java --results /tmp/qdb-defaults \
  --note "Defaults sweep, 7 runtimes x 3 iter x 3 tests"

jbang analysis-scripts/GenerateReport.java --run-id 1
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

`RUN_INFO` (generated by `deploy-to-remote.sh`) deliberately omits the
developer's hostname and username. Runs are identified by an opaque
`run_id` (UUID). Anything captured into committed SQL dumps, generated
reports, or the qDup state must follow the same rule — no PII.

## Conventions

- Application code must maintain parity across all modules (same domain, same
  endpoints, same behavior)
- Config files use YAML (`application.yml`) in all Quarkus modules
- Benchmark log format: structured `key: value` sections (ENVIRONMENT, HARDWARE,
  OS, JVM CONFIG, DOTNET CONFIG, BENCHMARK CONFIG, RESULTS)
- `database/benchmarks.sql` is the version-controlled source of truth for all
  results
- `database/benchmarks.mv.db` is gitignored (H2 binary); rehydrate by
  re-running `jbang analysis-scripts/ImportBenchmark.java --results <dir>`
  for each run, or by `RUNSCRIPT FROM 'database/benchmarks.sql'` against a
  fresh H2 database
