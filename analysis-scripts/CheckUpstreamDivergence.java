///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.yaml:snakeyaml:2.2
//DEPS com.h2database:h2:2.3.232
//DEPS info.picocli:picocli:4.7.5

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Fetches upstream repo and compares tracked files against our versions.
 * Records results in H2. Exits non-zero if unreviewed divergences are found.
 *
 * Usage:
 *   jbang CheckUpstreamDivergence.java
 *   jbang CheckUpstreamDivergence.java --upstream-dir /path/to/local/upstream-clone
 */
@Command(name = "CheckUpstreamDivergence", mixinStandardHelpOptions = true,
         description = "Detect drift between our Quarkus code and the upstream repository")
public class CheckUpstreamDivergence implements Callable<Integer> {

    @Option(names = "--upstream-dir",
            description = "Path to local upstream repo clone (skips git fetch if provided)")
    String upstreamDir;

    @Option(names = "--config", description = "upstream.yml path", defaultValue = "upstream.yml")
    String configPath;

    @Option(names = "--db", description = "H2 database path", defaultValue = "database/benchmarks")
    String dbPath;

    public static void main(String[] args) {
        System.exit(new CommandLine(new CheckUpstreamDivergence()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        // TODO: load upstream.yml (repository URL, tracked_files, path_mapping, intentional_divergences)
        // TODO: if --upstream-dir not given, clone upstream repo to temp dir
        // TODO: for each tracked_files entry, map path and compare files
        // TODO: classify each diff as "intentional" (matches upstream.yml) or "unreviewed"
        // TODO: print report to stdout
        // TODO: record check result in upstream_versions and upstream_divergences H2 tables
        // TODO: return 1 if any unreviewed divergences, 0 otherwise
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
