///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.h2database:h2:2.2.224
//DEPS info.picocli:picocli:4.7.5

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.Callable;

/**
 * Parses benchmark log files into the H2 database, then exports to SQL.
 *
 * Usage:
 *   jbang ImportBenchmark.java --date 2026-04-29
 *   jbang ImportBenchmark.java --rebuild   (reimport all results/)
 */
@Command(name = "ImportBenchmark", mixinStandardHelpOptions = true,
         description = "Import benchmark logs into H2 database and export to SQL")
public class ImportBenchmark implements Callable<Integer> {

    @Option(names = "--date", description = "Import results for a specific date (YYYY-MM-DD)")
    String date;

    @Option(names = "--rebuild", description = "Reimport all results from scratch")
    boolean rebuild;

    @Option(names = "--db", description = "H2 database path", defaultValue = "database/benchmarks")
    String dbPath;

    public static void main(String[] args) {
        System.exit(new CommandLine(new ImportBenchmark()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:" + dbPath)) {
            initSchema(conn);

            if (rebuild) {
                reimportAll(conn);
            } else if (date != null) {
                importDate(conn, date);
            } else {
                System.err.println("Provide --date YYYY-MM-DD or --rebuild");
                return 1;
            }

            exportSql(conn);
            System.out.println("Done. Exported to database/benchmarks.sql");
        }
        return 0;
    }

    void initSchema(Connection conn) throws SQLException {
        // TODO: create tables benchmark_runs, environment, jvm_config, dotnet_config,
        //       benchmark_config, metrics, gc_events, upstream_versions, upstream_divergences
        throw new UnsupportedOperationException("Not yet implemented");
    }

    void importDate(Connection conn, String date) throws Exception {
        Path resultsDir = Paths.get("results", date);
        if (!Files.exists(resultsDir)) {
            System.err.println("No results directory: " + resultsDir);
            System.exit(1);
        }
        Files.list(resultsDir)
            .filter(Files::isDirectory)
            .forEach(runDir -> importRun(conn, runDir));
    }

    void reimportAll(Connection conn) throws Exception {
        Files.list(Paths.get("results"))
            .filter(Files::isDirectory)
            .forEach(dateDir -> {
                try { importDate(conn, dateDir.getFileName().toString()); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
    }

    void importRun(Connection conn, Path runDir) {
        // TODO: parse benchmark.log key:value sections
        // Scrub JVM flags: strip -D*password*, -D*secret*, -D*key*, -D*token*, -D*credential*
        // Insert into benchmark_runs, environment, jvm_config / dotnet_config,
        //   benchmark_config, metrics, gc_events
        throw new UnsupportedOperationException("Not yet implemented: " + runDir);
    }

    void exportSql(Connection conn) throws SQLException {
        conn.createStatement().execute("SCRIPT TO 'database/benchmarks.sql'");
    }
}
