///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.h2database:h2:2.2.224
//DEPS info.picocli:picocli:4.7.5
//DEPS org.jfree:jfreechart:1.5.4

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.sql.*;
import java.util.concurrent.Callable;

/**
 * Compares benchmark results across Java or dotnet versions for a given app variant.
 * Only compares runs with the same CPU model and heap budget for fairness.
 *
 * Usage:
 *   jbang CompareVersions.java --app quarkus-jvm
 *   jbang CompareVersions.java --app dotnet-net10 --metric p99_latency_ms
 *   jbang CompareVersions.java --app quarkus-jvm --output reports/java-version-comparison.html
 */
@Command(name = "CompareVersions", mixinStandardHelpOptions = true,
         description = "Compare benchmark results across runtime versions for an app variant")
public class CompareVersions implements Callable<Integer> {

    @Option(names = "--app", required = true, description = "App variant (e.g. quarkus-jvm, dotnet-net10)")
    String app;

    @Option(names = "--metric", description = "Metric to compare", defaultValue = "throughput_rps")
    String metric;

    @Option(names = "--output", description = "Output HTML file")
    String output;

    @Option(names = "--db", description = "H2 database path", defaultValue = "database/benchmarks")
    String dbPath;

    public static void main(String[] args) {
        System.exit(new CommandLine(new CompareVersions()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:" + dbPath)) {
            // TODO: group runs by java_version / dotnet_version
            // TODO: filter to same cpu_model and effective_heap_max_mb for fair comparison
            // TODO: compute mean, stddev, confidence intervals per version
            // TODO: generate comparison chart and HTML report
            throw new UnsupportedOperationException("Not yet implemented");
        }
    }
}
