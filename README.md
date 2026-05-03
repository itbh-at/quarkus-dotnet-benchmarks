# Quarkus vs .NET Benchmarks

Independent performance benchmarking suite comparing Quarkus variants against
ASP.NET Core, across multiple Java and .NET runtime versions.

## What This Project Does

This suite runs standardized workloads against multiple runtime configurations,
measuring performance across metrics including:

- **Build time** — compilation & packaging duration
- **Time to first response (TTFR)** — cold startup latency
- **Memory usage** — resident set size (RSS) at startup and under load
- **Throughput** — sustained request handling capacity (RPS)
- **Throughput density** — RPS per MB of memory

All tested applications implement identical domain logic (REST CRUD endpoints
over PostgreSQL) to ensure fair comparison. Measurements are taken in
production-like conditions with CPU pinning and isolated hardware when
available.

## How This Differs From the Ancestor

This project is a **fork** of
[`spring-quarkus-perf-comparison`](https://github.com/cdhermann/spring-quarkus-perf-comparison),
which originally benchmarked Spring Boot against Quarkus. Key changes:

| Aspect | Ancestor | Current |
|--------|----------|---------|
| **Frameworks compared** | Spring Boot vs. Quarkus | Quarkus variants vs. ASP.NET Core |
| **Spring framework** | Included as primary contender | Removed; replaced with .NET |
| **.NET support** | Optional reference implementation | Primary comparison target |
| **Focus** | Multi-framework ecosystem comparison | Java vs. managed runtime languages |
| **Runtime variants** | Single Quarkus variant | Quarkus JVM + virtual threads |

**Why the change?** The ancestor project compared Spring Boot and Quarkus, both
running on the JVM. This fork shifts to compare JVM-based applications (Quarkus)
against a fundamentally different runtime ecosystem (.NET), providing insights
into cross-platform performance trade-offs for cloud-native and serverless
workloads.

## Project Structure

```
apps/                          # Application implementations
├── quarkus-jvm/              # Quarkus 3.x JVM mode (standard threads)
├── quarkus-virtual/          # Quarkus 3.x JVM mode (virtual threads)
└── dotnet-core-aspnet-ef/    # ASP.NET Core Minimal API + EF Core

benchmark-scripts/
├── local/                    # Local deployment & infrastructure
│   ├── infra.sh              # PostgreSQL lifecycle (start/stop/seed)
│   └── deploy-to-remote.sh   # rsync working tree to remote target
├── remote/                   # qDup orchestration (runs on remote)
│   ├── run-benchmarks.sh     # Trigger benchmark run via qDup
│   ├── main.yml              # qDup playbook definition
│   └── helpers/              # qDup helper scripts
└── analysis-scripts/         # JBang-based result analysis

database/
├── benchmarks.sql            # Version-controlled results (H2 DDL + data)
└── benchmarks.mv.db          # H2 binary database (gitignored)

reports/                       # Generated HTML reports
```

## Quick Start

### Prerequisites

- **Runtime management:** `mise` (https://mise.jdx.dev) — manages explicit Java
  and .NET versions
- **Database:** Docker or local PostgreSQL server on `localhost:5432`
- **Build tools:** Maven 3.8+ (bundled with Quarkus), dotnet CLI

### Local Development

#### 1. Start PostgreSQL & seed data

```bash
cd benchmark-scripts/local
./infra.sh -s   # Start, create tables, seed data
./infra.sh -d   # Stop (when done)
```

#### 2. Build applications

**Quarkus JVM (Java 25):**
```bash
mise exec java@25 -- ./mvnw -Djava.version=25 -Dmaven.compiler.release=25 \
  clean verify -pl apps/quarkus-jvm
```

**Quarkus JVM (Java 21):**
```bash
mise exec java@21 -- ./mvnw -Djava.version=21 -Dmaven.compiler.release=21 \
  clean verify -pl apps/quarkus-jvm
```

**Quarkus native image:**
```bash
mise exec java@25 -- ./mvnw -Djava.version=25 -Dmaven.compiler.release=25 \
  clean verify -pl apps/quarkus-jvm -Pnative
```

**.NET:**
```bash
mise exec dotnet@10 -- dotnet build apps/dotnet-core-aspnet-ef
```

#### 3. Run applications

**Quarkus JVM (Java 25):**
```bash
mise exec java@25 -- java -jar apps/quarkus-jvm/target/quarkus-app/quarkus-run.jar
```

**Quarkus JVM (Java 21):**
```bash
mise exec java@21 -- java -jar apps/quarkus-jvm/target/quarkus-app/quarkus-run.jar
```

**Quarkus JVM (Java 17):**
```bash
mise exec java@17 -- java -jar apps/quarkus-jvm/target/quarkus-app/quarkus-run.jar
```

**Quarkus native (pre-built):**
```bash
apps/quarkus-jvm/target/quarkus-jvm-runner
```

**.NET (dotnet 10):**
```bash
mise exec dotnet@10 -- dotnet run --project apps/dotnet-core-aspnet-ef
```

**.NET (dotnet 8):**
```bash
mise exec dotnet@8 -- dotnet run --project apps/dotnet-core-aspnet-ef
```

### Running Benchmarks

#### Local machine only

```bash
cd benchmark-scripts/remote
bash run-benchmarks.sh \
  --host LOCAL \
  --runtimes quarkus-jvm,dotnet-aspnet-ef \
  --iterations 3
```

#### Remote machine (one-time setup)

1. **Prepare the remote machine:**
   ```bash
   benchmark-scripts/remote/remote-setup.sh --host perf-lab.example.com --user perf
   ```

   This script (run from your local machine):
   - Installs your SSH public key for passwordless login
   - Configures passwordless sudo on the remote user account
   - Verifies prerequisites: bash, curl, jq
   - Installs the C/C++ build toolchain (gcc, zlib dev headers) — required for
     native image compilation
   - Verifies non-interactive SSH connectivity

   Options:
   - `--ssh-key <PATH>` — Specify SSH key to install (auto-detected if omitted)
   - `--ssh-port <PORT>` — Custom SSH port (default: 22)
   - `--check-only` — Verify setup without making changes
   - `--skip-sudo` — Skip passwordless sudo configuration

   After this completes, the remote is ready for benchmark runs.

#### Remote machine (running benchmarks)

2. **Deploy working tree to remote:**
   ```bash
   benchmark-scripts/local/deploy-to-remote.sh perf@perf-lab.example.com
   ```

3. **Trigger benchmark run (from local machine):**
   ```bash
   cd benchmark-scripts/remote
   bash run-benchmarks.sh \
     --host perf-lab.example.com \
     --user perf \
     --runtimes quarkus-jvm,quarkus-native,dotnet-aspnet-ef \
     --iterations 3
   ```

**Iterating on scripts:** Edit locally, rerun `deploy-to-remote.sh`, rerun
`run-benchmarks.sh`. The `--delete` flag on rsync keeps the remote tree in
lockstep; transient artifacts (`logs/`, `builds/`) are preserved.

## Applications

All modules implement the same domain logic for fair comparison:

- **Entities:** Fruit, Store, Address
- **Operations:** CRUD REST endpoints with database access (Hibernate ORM /
  Entity Framework)
- **Observability:** OpenTelemetry instrumentation
- **Database:** PostgreSQL Npgsql/JDBC, connection pooling

## Benchmark Execution

The benchmark pipeline:

```
qDup run (on remote) 
  ↓
metrics.json + per-iteration logs (downloaded)
  ↓
jbang ImportBenchmark.java
  ↓
database/benchmarks.{mv.db,sql}
  ↓
jbang GenerateReport.java
  ↓
reports/<run-id>-report.html
```

### Data Collection

Per run, the benchmark captures:

**Hardware:** CPU model, architecture, cores (physical/logical), frequency, RAM

**OS:** name, version, kernel, CPU governor, transparent huge pages, load
average

**Containers:** memory limit, CPU set (if running in cgroups)

**JVM config:** heap min/max, GC type/threads, JIT mode, raw JVM flags (secrets
scrubbed)

**.NET config:** GC mode, heap limits, thread pool, tiered compilation

**Benchmark config:** load generator, threads, connections, duration, RPS target

**Database:** PostgreSQL version, pool size

### Analysis

```bash
# Import benchmark results from qDup output directory
jbang analysis-scripts/ImportBenchmark.java \
  --results /tmp/qdb-run-1 \
  --note "Optional: description of this run"

# Generate single-run report (HTML with charts & per-iteration raw data)
jbang analysis-scripts/GenerateReport.java --run-id 1
# → reports/run-1-report.html

# Compare versions across runs
jbang analysis-scripts/CompareVersions.java --app quarkus-jvm

# Check for unreviewed upstream divergences
jbang analysis-scripts/CheckUpstreamDivergence.java
```

## Database Schema

Results are stored in an H2 database with full provenance tracking. The schema
ensures every measurement is fully labeled and prevents analysis mistakes:

**`runs`** — one row per qDup invocation
- Host metadata, runtime versions (Java/dotnet), JVM/dotnet tuning, Quarkus
  build options, CPU pinning, profiler config
- Deploy provenance: commit, branch, dirty flag, timestamp (from `RUN_INFO`)
- List of tests that actually ran

**`runtime_results`** — one row per `(run_id, runtime_name)`
- Links runs to metrics; prevents orphaned metrics losing runtime identity

**`iteration_metrics`** — one row per `(run_id, runtime_name, iteration, metric_name)`
- Fully tagged: every measurement knows its run, runtime, iteration
- Columns: `metric_value` (DOUBLE), `unit` (VARCHAR)
- Metric names: `build_time_s`, `ttfr_ms`, `rss_startup_mib`,
  `rss_first_request_mib`, `load_throughput_rps`, `load_rss_mib`,
  `load_throughput_density`, `load_connection_errors`, `load_request_timeouts`

Two runs are comparable when the dimensions you want to hold constant
(`host_cpu`, `host_memory`, `jvm_memory`, `jvm_args`, `quarkus_build_config_args`,
`cpu_app`, etc.) match.

## Upstream Tracking

This project tracks its ancestor via `upstream.yml`, which records:
- The last reviewed upstream commit
- Intentional divergences (and why they were made)

Check for unreviewed divergences:

```bash
jbang analysis-scripts/CheckUpstreamDivergence.java
```

Exit code 0 = clean (all divergences documented as intentional)  
Exit code 1 = unreviewed divergences exist

## Runtime Version Management

All runtimes are managed explicitly via `mise` — no `.mise.toml` or
`.tool-versions`. Versions are always passed on the command line:

```bash
mise exec java@25 -- <command>      # Java 25
mise exec java@21 -- <command>      # Java 21
mise exec java@17 -- <command>      # Java 17
mise exec dotnet@10 -- <command>    # .NET 10
mise exec dotnet@8 -- <command>     # .NET 8
```

The benchmark scripts iterate over configured version lists and invoke each
variant with `mise exec <runtime>@<version> --`.

### Using Project Valhalla JDK

To run benchmarks with a Project Valhalla-enabled JDK (which adds value types and
primitive classes), manually install the JDK on the target machine and pass its
path via the `--java-home` option to `run-benchmarks.sh`. The `--java-home` flag
takes precedence over `--java-version`.

**Example: Benchmark with Valhalla JDK 27 EA (JEP 401)**

1. **Install Valhalla JDK on remote:**
   ```bash
   # Download or build OpenJDK 27 EA with JEP 401 (Value Classes and Objects)
   # and place at a known location, e.g.:
   /opt/jdks/jdk-valhalla-27-jep401ea3
   ```

2. **Run benchmark with Valhalla JDK:**
   ```bash
   cd benchmark-scripts/remote
   bash run-benchmarks.sh \
     --host remote-machine \
     --user perf \
     --iterations 3 \
     --java-home /opt/jdks/jdk-valhalla-27-jep401ea3 \
     --runtimes quarkus-jvm,quarkus-virtual \
     --jvm-memory '-Xms512m -Xmx512m' \
     --jvm-args '--enable-preview' \
     --description 'Valhalla JDK 27 EA (JEP 401), --enable-preview enabled'
   ```

**Key points:**
- Early Access (EA) JDK builds are not available via `mise`, so they must be
  manually installed on the target machine
- Use `--enable-preview` (or other preview-related JVM args) to enable
  Valhalla preview features like value classes
- The `--java-home` flag bypasses `mise` version lookup and uses the specified
  JDK directly for both compilation and runtime
- This approach works for any experimental or custom JDK variant, not just
  Valhalla

### Local Machine Prerequisites

Users running benchmarks locally must have `mise` installed. Local scripts that
depend on `mise` check for its presence and abort if missing:

```bash
command -v mise >/dev/null 2>&1 || {
  echo "ERROR: mise is required but not installed. See https://mise.jdx.dev" >&2
  exit 1
}
```

### Remote Machine Prerequisites

On remote targets, `mise` is installed automatically by the qDup setup chain
(`requirements.yml` → `ensure-*` → `mise-ensure-runtime` → `mise-install`).

## Security

**JVM flags:** Any flag matching `*password*`, `*secret*`, `*key*`, `*token*`,
or `*credential*` is redacted to `[REDACTED]` on import.

**.NET environment variables:** Only whitelisted `DOTNET_*` variables are
captured.

**Identity & PII:** Runs are identified by opaque UUIDs. The `RUN_INFO` file
deliberately omits developer hostname and username. Anything recorded into
committed SQL dumps, generated reports, or qDup state follows the same rule —
no personally identifiable information.

## Conventions

- **Application parity:** All modules maintain identical domain logic, endpoints,
  and behavior
- **Configuration:** YAML (`application.yml`) in all Quarkus modules
- **Benchmark logs:** Structured `key: value` sections (ENVIRONMENT, HARDWARE,
  OS, JVM CONFIG, DOTNET CONFIG, BENCHMARK CONFIG, RESULTS)
- **Version control:** `database/benchmarks.sql` is the source of truth for all
  results; `benchmarks.mv.db` is gitignored
- **Rehydrating results:** Run `jbang analysis-scripts/ImportBenchmark.java --results <dir>`
  for each run, or run `RUNSCRIPT FROM 'database/benchmarks.sql'` against a
  fresh H2 database
