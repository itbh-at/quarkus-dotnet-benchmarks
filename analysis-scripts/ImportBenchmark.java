///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.h2database:h2:2.3.232
//DEPS info.picocli:picocli:4.7.5
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Imports a qDup benchmark output directory (the value passed to
 * run-benchmarks.sh --output-dir) into the H2 database and exports the result
 * to SQL.
 *
 * Schema is laid out so every measurement carries its runtime and iteration
 * label. Any aggregating query MUST `GROUP BY runtime_name` — you can't
 * accidentally average across variants. This is by design: it's the same
 * mistake-class that catches you when reading the live qDup stream where
 * BUILD_TIME events don't carry the runtime context.
 *
 * Source of truth: ${results}/target-host/metrics.json — qDup writes the full
 * RUN.output state there at end of each run (see download-metrics in main.yml).
 *
 * Usage:
 *   jbang analysis-scripts/ImportBenchmark.java --results /tmp/qdb-defaults
 */
@Command(name = "ImportBenchmark", mixinStandardHelpOptions = true,
         description = "Import a qDup benchmark output directory into H2 and export to SQL")
public class ImportBenchmark implements Callable<Integer> {

    @Option(names = "--results", required = true,
            description = "Path to the qDup --output-dir (must contain target-host/metrics.json)")
    Path results;

    @Option(names = "--db", description = "H2 database path (without extension)",
            defaultValue = "database/benchmarks")
    String dbPath;

    @Option(names = "--sql-export", description = "Path to write the SQL dump",
            defaultValue = "database/benchmarks.sql")
    Path sqlExport;

    @Option(names = "--note", description = "Optional human-readable note to attach to the run")
    String note;

    public static void main(String[] args) {
        System.exit(new CommandLine(new ImportBenchmark()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        Path metricsFile = results.resolve("target-host/metrics.json");
        if (!Files.exists(metricsFile)) {
            System.err.println("ERROR: metrics.json not found at " + metricsFile);
            return 1;
        }

        JsonNode json = new ObjectMapper().readTree(metricsFile.toFile());
        Path dbDir = Paths.get(dbPath).toAbsolutePath().getParent();
        if (dbDir != null) Files.createDirectories(dbDir);

        try (Connection conn = DriverManager.getConnection("jdbc:h2:" + Paths.get(dbPath).toAbsolutePath())) {
            initSchema(conn);

            long runId = insertRun(conn, json);
            int runtimeCount = insertRuntimeResults(conn, runId, json);
            int metricCount = insertIterationMetrics(conn, runId, json);

            System.out.printf(
                "Imported run_id=%d  runtimes=%d  metric_rows=%d  source=%s%n",
                runId, runtimeCount, metricCount, metricsFile);

            exportSql(conn);
        }
        return 0;
    }

    // ── Schema ──────────────────────────────────────────────────────────────

    void initSchema(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS runs (
                  run_id                       BIGINT       PRIMARY KEY AUTO_INCREMENT,
                  started_at                   TIMESTAMP,
                  stopped_at                   TIMESTAMP,
                  imported_at                  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                  note                         VARCHAR(2048),

                  -- host metadata (env.host)
                  host_user                    VARCHAR(64),
                  host_name                    VARCHAR(255),
                  host_target                  VARCHAR(255),
                  host_os                      VARCHAR(128),
                  host_type                    VARCHAR(255),
                  host_kernel                  VARCHAR(128),
                  host_cpu                     VARCHAR(255),
                  host_gpu                     VARCHAR(255),
                  host_memory                  VARCHAR(64),

                  -- run-level config (config.run / config.repo)
                  scenario                     VARCHAR(32),
                  scenario_name                VARCHAR(64),
                  num_iterations               INT,
                  tests_run                    VARCHAR(512),
                  description                  VARCHAR(1024),
                  run_identifier               VARCHAR(255),
                  drop_os_filesystem_caches    BOOLEAN,
                  use_container_host_network   BOOLEAN,

                  -- deploy provenance (config.repo.* — populated from RUN_INFO
                  -- generated by deploy-to-remote.sh). deploy_run_id is an
                  -- opaque UUID generated per-deploy; we deliberately do NOT
                  -- store the developer's hostname or username, since that's
                  -- PII with no analytical value.
                  deploy_run_id                VARCHAR(48),
                  deploy_commit                VARCHAR(64),
                  deploy_short_commit          VARCHAR(16),
                  deploy_branch                VARCHAR(128),
                  deploy_dirty                 BOOLEAN,
                  deployed_at                  TIMESTAMP,

                  -- runtime versions (config.jvm / config.dotnet)
                  jvm_version                  VARCHAR(128),
                  jvm_home                     VARCHAR(255),
                  graalvm_version              VARCHAR(128),
                  graalvm_home                 VARCHAR(255),
                  mandrel_version              VARCHAR(128),
                  mandrel_home                 VARCHAR(255),
                  dotnet_version               VARCHAR(64),
                  dotnet_home                  VARCHAR(255),
                  dotnet_gc_heap_hard_limit    VARCHAR(64),

                  -- JVM tuning
                  jvm_memory                   VARCHAR(255),
                  jvm_args                     VARCHAR(2048),

                  -- Quarkus build/run options
                  quarkus_version              VARCHAR(64),
                  quarkus_build_config_args    VARCHAR(2048),
                  quarkus_native_build_options VARCHAR(2048),

                  -- CPU pinning (config.resources)
                  cpu_app                      VARCHAR(64),
                  cpu_db                       VARCHAR(64),
                  cpu_load_generator           VARCHAR(64),
                  cpu_first_request            VARCHAR(64),
                  cpu_monitor                  VARCHAR(64),
                  cpu_otel                     VARCHAR(64),
                  app_cpus                     INT,

                  -- profiling
                  profiler_name                VARCHAR(32),
                  profiler_events              VARCHAR(64)
                )
            """);

            // One row per (run, runtime). The composite key here is what every
            // metric row references — the FK is the guardrail that prevents an
            // orphaned metric losing its runtime label.
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS runtime_results (
                  run_id        BIGINT       NOT NULL,
                  runtime_name  VARCHAR(64)  NOT NULL,
                  PRIMARY KEY (run_id, runtime_name),
                  FOREIGN KEY (run_id) REFERENCES runs(run_id) ON DELETE CASCADE
                )
            """);

            // The grain of this table is one measurement. Every row is fully
            // labelled with runtime + iteration; aggregation queries MUST
            // GROUP BY runtime_name to be meaningful.
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS iteration_metrics (
                  id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
                  run_id        BIGINT       NOT NULL,
                  runtime_name  VARCHAR(64)  NOT NULL,
                  iteration     INT          NOT NULL,
                  metric_name   VARCHAR(64)  NOT NULL,
                  metric_value  DOUBLE,
                  unit          VARCHAR(16),
                  FOREIGN KEY (run_id, runtime_name)
                    REFERENCES runtime_results(run_id, runtime_name) ON DELETE CASCADE
                )
            """);
            s.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_metrics_lookup " +
                "ON iteration_metrics(run_id, runtime_name, metric_name)");
            s.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_metrics_metric_name " +
                "ON iteration_metrics(metric_name)");

            // Forward-compat ALTERs for DBs created against an older schema.
            // H2 ignores these when the column already exists. If a DB has
            // an old `deployed_from` column from a pre-PII-cleanup import,
            // drop it so its data doesn't linger in the SQL export.
            for (String alter : new String[] {
                "ALTER TABLE runs ADD COLUMN IF NOT EXISTS tests_run VARCHAR(512)",
                "ALTER TABLE runs ADD COLUMN IF NOT EXISTS deploy_run_id VARCHAR(48)",
                "ALTER TABLE runs ADD COLUMN IF NOT EXISTS deploy_commit VARCHAR(64)",
                "ALTER TABLE runs ADD COLUMN IF NOT EXISTS deploy_short_commit VARCHAR(16)",
                "ALTER TABLE runs ADD COLUMN IF NOT EXISTS deploy_branch VARCHAR(128)",
                "ALTER TABLE runs ADD COLUMN IF NOT EXISTS deploy_dirty BOOLEAN",
                "ALTER TABLE runs ADD COLUMN IF NOT EXISTS deployed_at TIMESTAMP",
                "ALTER TABLE runs DROP COLUMN IF EXISTS deployed_from"
            }) {
                s.executeUpdate(alter);
            }
        }
    }

    // ── runs row ────────────────────────────────────────────────────────────

    long insertRun(Connection conn, JsonNode json) throws SQLException {
        JsonNode timing  = json.path("timing");
        JsonNode config  = json.path("config");
        JsonNode env     = json.path("env");
        JsonNode host    = env.path("host");
        JsonNode runHost = env.path("run").path("host");
        JsonNode jvm     = config.path("jvm");
        JsonNode dotnet  = config.path("dotnet");
        JsonNode quarkus = config.path("quarkus");
        JsonNode res     = config.path("resources");
        JsonNode cpu     = res.path("cpu");
        JsonNode prof    = config.path("profiler");
        JsonNode runCfg  = config.path("run");
        JsonNode repo    = config.path("repo");

        String sql = """
            INSERT INTO runs (
              started_at, stopped_at, note,
              host_user, host_name, host_target,
              host_os, host_type, host_kernel, host_cpu, host_gpu, host_memory,
              scenario, scenario_name, num_iterations, tests_run, description, run_identifier,
              drop_os_filesystem_caches, use_container_host_network,
              deploy_run_id, deploy_commit, deploy_short_commit, deploy_branch,
              deploy_dirty, deployed_at,
              jvm_version, jvm_home, graalvm_version, graalvm_home,
              mandrel_version, mandrel_home,
              dotnet_version, dotnet_home, dotnet_gc_heap_hard_limit,
              jvm_memory, jvm_args,
              quarkus_version, quarkus_build_config_args, quarkus_native_build_options,
              cpu_app, cpu_db, cpu_load_generator, cpu_first_request,
              cpu_monitor, cpu_otel, app_cpus,
              profiler_name, profiler_events
            ) VALUES (?,?,?, ?,?,?, ?,?,?,?,?,?, ?,?,?,?,?,?, ?,?,
                      ?,?,?,?, ?,?,
                      ?,?,?,?, ?,?, ?,?,?, ?,?, ?,?,?, ?,?,?,?, ?,?,?, ?,?)
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            ps.setTimestamp(i++, parseTs(timing.path("start").asText(null)));
            ps.setTimestamp(i++, parseTs(timing.path("stop").asText(null)));
            ps.setString(i++, note);

            ps.setString(i++, str(runHost.path("user")));
            ps.setString(i++, str(runHost.path("name")));
            ps.setString(i++, str(runHost.path("target")));

            ps.setString(i++, str(host.path("os")));
            ps.setString(i++, str(host.path("type")));
            ps.setString(i++, str(host.path("kernel")));
            ps.setString(i++, str(host.path("cpu")));
            ps.setString(i++, str(host.path("gpu")));
            ps.setString(i++, str(host.path("memory")));

            ps.setString(i++, str(repo.path("scenario")));
            ps.setString(i++, str(repo.path("scenarioName")));
            setNullableInt(ps, i++, config.path("num_iterations"));
            ps.setString(i++, joinTests(config.path("tests")));
            ps.setString(i++, str(runCfg.path("description")));
            ps.setString(i++, str(runCfg.path("identifier")));
            setNullableBool(ps, i++, runCfg.path("dropOsFilesystemCaches"));
            setNullableBool(ps, i++, runCfg.path("useContainerHostNetwork"));

            ps.setString(i++, str(repo.path("run_id")));
            ps.setString(i++, str(repo.path("commit")));
            ps.setString(i++, str(repo.path("short_commit")));
            ps.setString(i++, str(repo.path("branch")));
            setNullableBool(ps, i++, repo.path("dirty"));
            ps.setTimestamp(i++, parseTs(str(repo.path("deployed_at"))));

            ps.setString(i++, str(jvm.path("version")));
            ps.setString(i++, str(jvm.path("home")));
            ps.setString(i++, str(jvm.path("graalvm").path("version")));
            ps.setString(i++, str(jvm.path("graalvm").path("home")));
            ps.setString(i++, str(jvm.path("mandrel").path("version")));
            ps.setString(i++, str(jvm.path("mandrel").path("home")));
            ps.setString(i++, str(dotnet.path("version")));
            ps.setString(i++, str(dotnet.path("home")));
            ps.setString(i++, str(dotnet.path("gcHeapHardLimit")));

            ps.setString(i++, str(jvm.path("memory")));
            ps.setString(i++, scrubFlags(str(jvm.path("args"))));

            ps.setString(i++, str(quarkus.path("version")));
            ps.setString(i++, scrubFlags(str(quarkus.path("build_config_args"))));
            ps.setString(i++, scrubFlags(str(quarkus.path("native_build_options"))));

            ps.setString(i++, str(cpu.path("app")));
            ps.setString(i++, str(cpu.path("db")));
            ps.setString(i++, str(cpu.path("load_generator")));
            ps.setString(i++, str(cpu.path("1st_request")));
            ps.setString(i++, str(cpu.path("monitor")));
            ps.setString(i++, str(cpu.path("otel")));
            setNullableInt(ps, i++, res.path("app_cpus"));

            ps.setString(i++, str(prof.path("name")));
            ps.setString(i++, str(prof.path("events")));

            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) throw new SQLException("No generated key for runs row");
                return rs.getLong(1);
            }
        }
    }

    // ── runtime_results rows ────────────────────────────────────────────────

    int insertRuntimeResults(Connection conn, long runId, JsonNode json) throws SQLException {
        JsonNode results = json.path("results");
        if (!results.isObject()) return 0;

        int n = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO runtime_results (run_id, runtime_name) VALUES (?, ?)")) {
            for (Iterator<String> it = results.fieldNames(); it.hasNext(); ) {
                ps.setLong(1, runId);
                ps.setString(2, it.next());
                ps.executeUpdate();
                n++;
            }
        }
        return n;
    }

    // ── iteration_metrics rows ──────────────────────────────────────────────

    /**
     * Maps qDup's metrics.json structure into per-iteration rows. For every
     * runtime, every iteration of every test contributes one row per metric.
     * The runtime_name is set on every row — that's the whole point.
     */
    int insertIterationMetrics(Connection conn, long runId, JsonNode json) throws SQLException {
        JsonNode results = json.path("results");
        if (!results.isObject()) return 0;

        int total = 0;
        String sql = "INSERT INTO iteration_metrics " +
                "(run_id, runtime_name, iteration, metric_name, metric_value, unit) " +
                "VALUES (?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Iterator<Map.Entry<String, JsonNode>> it = results.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String runtime = e.getKey();
                JsonNode r = e.getValue();

                total += addArray(ps, runId, runtime, r.path("build").path("timings"),
                        "build_time_s", "s");

                total += addArray(ps, runId, runtime, r.path("startup").path("timings"),
                        "ttfr_ms", "ms");

                total += addArray(ps, runId, runtime, r.path("rss").path("startup"),
                        "rss_startup_mib", "MiB");
                total += addArray(ps, runId, runtime, r.path("rss").path("firstRequest"),
                        "rss_first_request_mib", "MiB");

                total += addArray(ps, runId, runtime, r.path("load").path("throughput"),
                        "load_throughput_rps", "rps");
                total += addArray(ps, runId, runtime, r.path("load").path("rss"),
                        "load_rss_mib", "MiB");
                total += addArray(ps, runId, runtime, r.path("load").path("throughputDensity"),
                        "load_throughput_density", "rps/MiB");
                total += addArray(ps, runId, runtime, r.path("load").path("connectionErrors"),
                        "load_connection_errors", "count");
                total += addArray(ps, runId, runtime, r.path("load").path("requestTimeouts"),
                        "load_request_timeouts", "count");
            }
        }
        return total;
    }

    /** Insert one row per array element using the array index as iteration. */
    int addArray(PreparedStatement ps, long runId, String runtime, JsonNode arr,
                 String metric, String unit) throws SQLException {
        if (!arr.isArray()) return 0;
        int n = 0;
        for (int i = 0; i < arr.size(); i++) {
            JsonNode v = arr.get(i);
            if (v == null || v.isNull()) continue;
            ps.setLong(1, runId);
            ps.setString(2, runtime);
            ps.setInt(3, i);
            ps.setString(4, metric);
            ps.setDouble(5, v.asDouble());
            ps.setString(6, unit);
            ps.executeUpdate();
            n++;
        }
        return n;
    }

    // ── SQL export ──────────────────────────────────────────────────────────

    void exportSql(Connection conn) throws Exception {
        Path target = sqlExport.toAbsolutePath();
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        try (Statement s = conn.createStatement()) {
            // H2's SCRIPT writes the full DDL+DATA dump. Plain text — diff-able.
            s.execute("SCRIPT TO '" + target + "'");
        }
        System.out.println("SQL export: " + target);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    static Timestamp parseTs(String iso) {
        if (iso == null || iso.isEmpty()) return null;
        return Timestamp.from(Instant.parse(iso));
    }

    /** Treat empty / null / missing JSON values as SQL NULL. */
    static String str(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText();
        return s.isEmpty() ? null : s;
    }

    /** Render config.tests (array or string) as a comma-joined list, or null. */
    static String joinTests(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isArray()) {
            if (n.size() == 0) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(n.get(i).asText());
            }
            return sb.toString();
        }
        String s = n.asText();
        return s.isEmpty() ? null : s;
    }

    static void setNullableInt(PreparedStatement ps, int idx, JsonNode n) throws SQLException {
        if (n == null || n.isMissingNode() || n.isNull() || n.asText().isEmpty()) {
            ps.setNull(idx, Types.INTEGER);
        } else {
            ps.setInt(idx, n.asInt());
        }
    }

    static void setNullableBool(PreparedStatement ps, int idx, JsonNode n) throws SQLException {
        if (n == null || n.isMissingNode() || n.isNull()) {
            ps.setNull(idx, Types.BOOLEAN);
            return;
        }
        String s = n.asText();
        if (s.equalsIgnoreCase("true"))       ps.setBoolean(idx, true);
        else if (s.equalsIgnoreCase("false")) ps.setBoolean(idx, false);
        else                                  ps.setNull(idx, Types.BOOLEAN);
    }

    /**
     * Scrub anything that looks like a credential out of captured JVM-style flags.
     * Mirrors the security convention documented in CLAUDE.md.
     */
    static String scrubFlags(String s) {
        if (s == null) return null;
        return s.replaceAll(
            "-D[\\w.]*(?i:password|secret|key|token|credential|auth)[\\w.]*=[^\\s]+",
            "-D[REDACTED]"
        );
    }
}
