///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.h2database:h2:2.3.232
//DEPS info.picocli:picocli:4.7.5
//DEPS org.jfree:jfreechart:1.5.4
//DEPS org.apache.commons:commons-math3:3.6.1
//SOURCES StatsHelper.java

import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.CategoryLabelPositions;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.StatisticalBarRenderer;
import org.jfree.data.statistics.DefaultStatisticalCategoryDataset;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Generates a self-contained HTML report for a single benchmark run.
 *
 * The report has four sections:
 *   1. Run metadata — host, deploy provenance, runtime versions, tuning.
 *   2. Per-runtime summary table — mean ± stddev for each captured metric.
 *   3. Bar charts with error bars for the headline metrics (TTFR,
 *      throughput, RSS at startup / under load, build time). PNGs are
 *      base64-embedded so the file is portable with no asset directory.
 *   4. Per-runtime, per-iteration raw values inside a <details> drawer.
 *
 * Usage:
 *   jbang analysis-scripts/GenerateReport.java --run-id 1
 *   jbang analysis-scripts/GenerateReport.java --run-id 1 --output reports/sweep.html
 */
@Command(name = "GenerateReport", mixinStandardHelpOptions = true,
         description = "Generate a self-contained HTML report for a single benchmark run")
public class GenerateReport implements Callable<Integer> {

    @Option(names = "--run-id", required = true,
            description = "runs.run_id of the benchmark run to report on")
    long runId;

    @Option(names = "--db", description = "H2 database path (without extension)",
            defaultValue = "database/benchmarks")
    String dbPath;

    @Option(names = "--output",
            description = "Output HTML file (default: reports/run-<id>-report.html)")
    Path output;

    @Option(names = "--show-build-time",
            description = "Include build-time metric in the report. Default: hidden — " +
                          "build cost is a one-time CI concern, not a recurring runtime cost. " +
                          "Use Quarkus Dev Mode for inner-loop development.",
            defaultValue = "false")
    boolean showBuildTime;

    public static void main(String[] args) {
        System.exit(new CommandLine(new GenerateReport()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        if (output == null) {
            output = Paths.get("reports", "run-" + runId + "-report.html");
        }

        try (Connection conn = DriverManager.getConnection(
                "jdbc:h2:" + Paths.get(dbPath).toAbsolutePath() + ";IFEXISTS=TRUE")) {

            Map<String, Object> meta = fetchRunMeta(conn, runId);
            if (meta == null) {
                System.err.println("ERROR: no run with run_id=" + runId);
                return 1;
            }

            List<String> runtimes = fetchRuntimes(conn, runId);
            if (runtimes.isEmpty()) {
                System.err.println("ERROR: run " + runId + " has no runtime_results rows");
                return 1;
            }

            // Two-level map: stats[runtime][metric] = MetricStats
            Map<String, Map<String, MetricStats>> stats = fetchStats(conn, runId, runtimes);
            // Same shape but with raw per-iteration values for the drawer
            Map<String, Map<String, double[]>> raw = fetchRaw(conn, runId, runtimes);

            String html = renderHtml(meta, runtimes, stats, raw);

            if (output.getParent() != null) Files.createDirectories(output.getParent());
            Files.writeString(output, html);
            System.out.println("Wrote " + output.toAbsolutePath());
        }
        return 0;
    }

    // ── Data access ─────────────────────────────────────────────────────────

    /** Returns column-name → value for the runs row, or null if absent. */
    Map<String, Object> fetchRunMeta(Connection conn, long runId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM runs WHERE run_id = ?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                ResultSetMetaData md = rs.getMetaData();
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    m.put(md.getColumnLabel(i).toLowerCase(), rs.getObject(i));
                }
                return m;
            }
        }
    }

    List<String> fetchRuntimes(Connection conn, long runId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT runtime_name FROM runtime_results WHERE run_id = ? ORDER BY runtime_name")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        }
    }

    /** Per-runtime, per-metric aggregate stats. Pulls everything in one query and pivots client-side. */
    Map<String, Map<String, MetricStats>> fetchStats(Connection conn, long runId, List<String> runtimes) throws SQLException {
        Map<String, Map<String, MetricStats>> out = new LinkedHashMap<>();
        for (String r : runtimes) out.put(r, new LinkedHashMap<>());

        String sql = """
            SELECT runtime_name, metric_name,
                   COUNT(metric_value)    AS n,
                   AVG(metric_value)      AS mean,
                   STDDEV_SAMP(metric_value) AS stddev,
                   MIN(metric_value)      AS minv,
                   MAX(metric_value)      AS maxv,
                   ANY_VALUE(unit)        AS unit
            FROM iteration_metrics
            WHERE run_id = ?
            GROUP BY runtime_name, metric_name
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String rtm = rs.getString("runtime_name");
                    String met = rs.getString("metric_name");
                    int n = rs.getInt("n");
                    double mean   = nullableDouble(rs, "mean");
                    Double stddev = (Double) rs.getObject("stddev"); // nullable when n<2
                    double minv = nullableDouble(rs, "minv");
                    double maxv = nullableDouble(rs, "maxv");
                    String unit = rs.getString("unit");
                    out.computeIfAbsent(rtm, k -> new LinkedHashMap<>())
                       .put(met, new MetricStats(n, mean, stddev, minv, maxv, unit));
                }
            }
        }
        return out;
    }

    /** Raw per-iteration values, indexed by runtime then metric, ordered by iteration. */
    Map<String, Map<String, double[]>> fetchRaw(Connection conn, long runId, List<String> runtimes) throws SQLException {
        Map<String, Map<String, List<Double>>> tmp = new LinkedHashMap<>();
        for (String r : runtimes) tmp.put(r, new LinkedHashMap<>());

        String sql = "SELECT runtime_name, metric_name, iteration, metric_value " +
                "FROM iteration_metrics WHERE run_id = ? ORDER BY runtime_name, metric_name, iteration";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tmp.computeIfAbsent(rs.getString("runtime_name"), k -> new LinkedHashMap<>())
                       .computeIfAbsent(rs.getString("metric_name"), k -> new ArrayList<>())
                       .add(rs.getDouble("metric_value"));
                }
            }
        }
        Map<String, Map<String, double[]>> out = new LinkedHashMap<>();
        tmp.forEach((rtm, byMetric) -> {
            Map<String, double[]> conv = new LinkedHashMap<>();
            byMetric.forEach((met, vals) -> {
                double[] arr = new double[vals.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = vals.get(i);
                conv.put(met, arr);
            });
            out.put(rtm, conv);
        });
        return out;
    }

    static double nullableDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? Double.NaN : v;
    }

    // ── Charts (PNG bytes, base64-encoded into HTML) ────────────────────────

    /** Bar chart with error bars across runtimes for one metric. Returns null if no runtime has data. */
    byte[] renderChart(String title, String yAxisLabel, String metric, String unit,
                       List<String> runtimes,
                       Map<String, Map<String, MetricStats>> stats) throws Exception {
        DefaultStatisticalCategoryDataset ds = new DefaultStatisticalCategoryDataset();
        boolean any = false;
        for (String r : runtimes) {
            MetricStats s = stats.getOrDefault(r, Map.of()).get(metric);
            if (s == null || s.n == 0 || Double.isNaN(s.mean)) continue;
            ds.add(s.mean, s.stddev == null ? 0 : s.stddev, "value", r);
            any = true;
        }
        if (!any) return null;

        JFreeChart chart = ChartFactory.createBarChart(
                title,
                /*categoryAxisLabel*/ "Runtime",
                /*valueAxisLabel*/    yAxisLabel + (unit != null && !unit.isEmpty() ? " (" + unit + ")" : ""),
                ds,
                PlotOrientation.VERTICAL,
                /*legend*/ false, /*tooltips*/ false, /*urls*/ false);

        CategoryPlot plot = chart.getCategoryPlot();
        StatisticalBarRenderer renderer = new StatisticalBarRenderer();
        // Soft blue bars; the default red was visually loud. Error bars
        // stay dark grey for contrast.
        renderer.setSeriesPaint(0, new java.awt.Color(0x7F, 0xA8, 0xC8));         // pastel blue
        renderer.setSeriesOutlinePaint(0, new java.awt.Color(0x44, 0x6B, 0x8C));  // muted edge
        renderer.setDrawBarOutline(true);
        renderer.setErrorIndicatorPaint(java.awt.Color.DARK_GRAY);
        renderer.setShadowVisible(false);
        renderer.setIncludeBaseInRange(true);
        plot.setRenderer(renderer);
        plot.setDataset(ds);
        plot.setBackgroundPaint(java.awt.Color.WHITE);
        plot.setRangeGridlinePaint(new java.awt.Color(0xE0, 0xE0, 0xE0));
        chart.setBackgroundPaint(java.awt.Color.WHITE);

        CategoryAxis xAxis = plot.getDomainAxis();
        xAxis.setCategoryLabelPositions(CategoryLabelPositions.UP_45);

        BufferedImage img = chart.createBufferedImage(800, 380);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "PNG", baos);
            return baos.toByteArray();
        }
    }

    String embedChart(byte[] png, String alt) {
        if (png == null) return "";
        String b64 = Base64.getEncoder().encodeToString(png);
        return "<img class=\"chart\" alt=\"" + esc(alt) + "\" src=\"data:image/png;base64," + b64 + "\"/>";
    }

    // ── HTML rendering ──────────────────────────────────────────────────────

    /** Metric definitions: display order is by importance for performance
     *  comparison. Throughput/TTFR/density first; memory-cost metrics next;
     *  build-time and error counters last. `lowerIsBetter` drives the
     *  winner highlight; `isMemory` flags rows that should be checked
     *  against the configured memory budget. */
    static final List<MetricDef> METRICS = List.of(
        new MetricDef("load_throughput_rps",     "Throughput",     "rps",    0, "Load test throughput",               false, false),
        new MetricDef("ttfr_ms",                 "TTFR",           "ms",     1, "Time to first request",              true,  false),
        new MetricDef("load_throughput_density", "rps / MiB",      "rps/MiB",2, "Throughput per MiB of RSS",          false, false),
        new MetricDef("rss_startup_mib",         "RSS @ startup",  "MiB",    1, "RSS at startup",                     true,  true),
        new MetricDef("rss_first_request_mib",   "RSS @ 1st req",  "MiB",    1, "RSS after first request",            true,  true),
        new MetricDef("load_rss_mib",            "RSS under load", "MiB",    1, "RSS during load test",               true,  true),
        new MetricDef("build_time_s",            "Build time",     "s",      2, "Build time",                         true,  false),
        new MetricDef("load_connection_errors",  "Conn err",       "count",  0, "Connection errors",                  true,  false),
        new MetricDef("load_request_timeouts",   "Timeouts",       "count",  0, "Request timeouts",                   true,  false)
    );

    static MetricDef metric(String name) {
        return METRICS.stream().filter(m -> m.name.equals(name)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown metric: " + name));
    }

    /** Returns the active metric list, omitting build_time_s unless --show-build-time is set. */
    List<MetricDef> activeMetrics() {
        if (showBuildTime) return METRICS;
        return METRICS.stream().filter(m -> !m.name.equals("build_time_s")).toList();
    }

    /** Returns the active chart metric list, omitting build_time_s unless --show-build-time is set. */
    List<String> activeChartMetrics() {
        if (showBuildTime) return CHART_METRICS;
        return CHART_METRICS.stream().filter(m -> !m.equals("build_time_s")).toList();
    }

    /** Alternate weighted ordering: efficiency-first. rps/MiB outweighs every
     *  later metric combined; TTFR is next; raw throughput third; RSS under
     *  load fourth. Other metrics (startup RSS, build time, errors) don't
     *  contribute to this score. */
    static final List<MetricDef> EFFICIENCY_ORDERED_METRICS = List.of(
        metric("load_throughput_density"),
        metric("ttfr_ms"),
        metric("load_throughput_rps"),
        metric("load_rss_mib")
    );

    /** Which metrics get a chart. Chosen because they're the headline numbers
     *  and have a sensible single-axis bar comparison. */
    static final List<String> CHART_METRICS = List.of(
        "ttfr_ms", "load_throughput_rps", "rss_startup_mib", "load_rss_mib", "build_time_s"
    );

    String renderHtml(Map<String, Object> meta, List<String> runtimes,
                      Map<String, Map<String, MetricStats>> stats,
                      Map<String, Map<String, double[]>> raw) throws Exception {
        StringBuilder sb = new StringBuilder(64 * 1024);

        sb.append("<!DOCTYPE html>\n<html lang=\"en\"><head>\n");
        sb.append("<meta charset=\"utf-8\"/>\n");
        sb.append("<title>Benchmark run #").append(meta.get("run_id")).append("</title>\n");
        sb.append(STYLE);
        sb.append("</head><body>\n");

        sb.append("<h1>Benchmark run #").append(meta.get("run_id")).append("</h1>\n");

        // ── Header / metadata ──
        sb.append("<section class=\"meta\">\n");
        sb.append("<table>\n");
        appendMeta(sb, "Started",      meta.get("started_at"));
        appendMeta(sb, "Stopped",      meta.get("stopped_at"));
        appendMeta(sb, "Note",         meta.get("note"));
        appendMeta(sb, "Tests run",    meta.get("tests_run"));
        appendMeta(sb, "Iterations",   meta.get("num_iterations"));
        appendMeta(sb, "Host",         joinNonNull(meta.get("host_type"), meta.get("host_cpu"), meta.get("host_memory")));
        appendMeta(sb, "OS",           joinNonNull(meta.get("host_os"), meta.get("host_kernel")));
        appendMeta(sb, "Java",         meta.get("jvm_version"));
        appendMeta(sb, "GraalVM",      meta.get("graalvm_version"));
        appendMeta(sb, "Mandrel",      meta.get("mandrel_version"));
        appendMeta(sb, "dotnet",       meta.get("dotnet_version"));
        appendMeta(sb, "JVM heap",     meta.get("jvm_memory"));
        appendMeta(sb, "JVM args",     meta.get("jvm_args"));
        appendMeta(sb, "Quarkus build args", meta.get("quarkus_build_config_args"));
        appendMeta(sb, "CPU pinning (app/db/load/otel)",
                   joinNonNull(meta.get("cpu_app"), meta.get("cpu_db"),
                               meta.get("cpu_load_generator"), meta.get("cpu_otel")));
        appendMeta(sb, "Deploy commit",
                   joinNonNull(meta.get("deploy_short_commit"), meta.get("deploy_branch"),
                               Boolean.TRUE.equals(meta.get("deploy_dirty")) ? "(dirty)" : null));
        appendMeta(sb, "Deploy run_id", meta.get("deploy_run_id"));
        appendMeta(sb, "Deployed at",  meta.get("deployed_at"));
        sb.append("</table>\n</section>\n");

        // Compute per-runtime memory budgets so RSS rows that exceed the
        // configured limit can be flagged. For JVM-family runtimes the
        // budget is -Xmx parsed from jvm_memory; for dotnet runtimes it's
        // dotnet_gc_heap_hard_limit. NaN means "no usable budget" — no
        // highlighting is applied.
        Map<String, Double> budgetMib = new HashMap<>();
        for (String r : runtimes) budgetMib.put(r, runtimeMemoryBudgetMib(r, meta));

        Integer expectedN = (Integer) meta.get("num_iterations");
        int dominantN = computeDominantN(stats, expectedN);

        // ── dotnet vs Quarkus comparison (top of report) ──
        // Headline output: identifies which side wins each metric and by
        // how much, so it's the first thing the reader sees. Split by
        // execution model — JVM-mode and native each compete against
        // dotnet on different terms (JIT vs AOT) so a single combined
        // table would muddle the story.
        List<String> dotnetRts    = runtimes.stream().filter(r -> r.startsWith("dotnet")).toList();
        List<String> jvmFamilyRts = runtimes.stream().filter(r -> !r.startsWith("dotnet")).toList();
        List<String> jvmModeRts   = jvmFamilyRts.stream().filter(r -> !r.contains("native")).toList();
        List<String> nativeRts    = jvmFamilyRts.stream().filter(r ->  r.contains("native")).toList();

        if (!dotnetRts.isEmpty() && !jvmFamilyRts.isEmpty()) {
            sb.append("<h2>dotnet vs Quarkus comparison</h2>\n");
            sb.append("<p class=\"hint\">Per metric, the ratio in parentheses is <code>quarkus / dotnet</code>: values above 1× mean Quarkus produced a higher number, below 1× means dotnet did. " +
                      "<span class=\"win-key\">Green</span> marks the per-row winner within each category. " +
                      "<span class=\"over-key\">Red</span> marks RSS values that exceed the runtime's configured memory budget. " +
                      "Two importance-weighted scores are reported per category: the <em>Overall winner</em> uses the table-row order (Throughput → TTFR → rps/MiB → memory rows → build time → errors), " +
                      "while the <em>Efficiency-weighted winner</em> reorders to rps/MiB → TTFR → throughput → RSS-under-load. " +
                      "In both, the leading metric outweighs every later metric combined, so ranking is effectively lexicographic in the chosen order.</p>\n");

            for (String dotnetRt : dotnetRts) {
                if (!jvmModeRts.isEmpty()) {
                    renderCategorySection(sb, dotnetRt, "JVM-mode", jvmModeRts, stats, budgetMib);
                }
                if (!nativeRts.isEmpty()) {
                    renderCategorySection(sb, dotnetRt, "Native", nativeRts, stats, budgetMib);
                }
            }
        }

        // ── Statistical comparisons (Java/Quarkus vs dotnet) ──
        // Per-metric pairwise tables exposing Cohen's d and Welch's
        // two-tailed t-test p-value as visible cells (the per-category
        // tables above carry the same numbers as tooltips on the ratio
        // cell — this section makes them legible for serialised reading
        // and matches the markdown-style results docs.
        if (!dotnetRts.isEmpty() && !jvmFamilyRts.isEmpty()) {
            renderStatisticalComparisons(sb, dotnetRts, jvmFamilyRts, stats);
        }

        // ── Workload simulation ──
        // Closed-form models that translate per-runtime metrics into
        // synthetic workload outcomes (serverless cold-start tax,
        // long-running SLO-compliant throughput density). The goal is
        // to surface which runtime fits which deployment archetype,
        // rather than just ranking individual metrics.
        renderSimulationSection(sb, runtimes, stats);

        // ── Native closed-world analysis ──
        // Statistics emitted by GraalVM/Mandrel native-image during
        // build-time reachability analysis. Reflects the closed-world
        // assumption: anything not reached at build time isn't in the
        // binary, and dynamic features (reflection, JNI, resources)
        // must be explicitly registered.
        if (!nativeRts.isEmpty()) {
            renderNativeClosedWorldSection(sb, nativeRts, stats);
        }

        // ── Per-runtime summary ──
        sb.append("<h2>Per-runtime summary</h2>\n");
        sb.append("<p class=\"hint\">Cells show mean ± sample standard deviation across iterations.</p>\n");
        sb.append("<div class=\"scroll\"><table class=\"summary\">\n");
        if (dominantN > 0) {
            sb.append("<caption>n = ").append(dominantN)
              .append(" iteration").append(dominantN == 1 ? "" : "s")
              .append(" per cell</caption>\n");
        }
        sb.append("<thead><tr><th>Runtime</th>");
        for (MetricDef md : activeMetrics()) {
            sb.append("<th title=\"").append(esc(md.titleAttr)).append("\">")
              .append(esc(md.label)).append("<br><span class=\"unit\">")
              .append(esc(md.unit)).append("</span></th>");
        }
        sb.append("</tr></thead>\n<tbody>\n");
        for (String r : runtimes) {
            sb.append("<tr><th class=\"rt\">").append(esc(r)).append("</th>");
            Double budget = budgetMib.get(r);
            for (MetricDef md : activeMetrics()) {
                MetricStats s = stats.getOrDefault(r, Map.of()).get(md.name);
                boolean over = md.isMemory && exceedsBudget(s, budget);
                sb.append("<td").append(over ? " class=\"over\"" : "").append(">")
                  .append(formatStats(s, md.decimals, dominantN)).append("</td>");
            }
            sb.append("</tr>\n");
        }
        sb.append("</tbody></table></div>\n");

        // ── Charts ──
        sb.append("<h2>Charts</h2>\n");
        sb.append("<p class=\"hint\">Bar = mean across iterations. Error bar = sample standard deviation. " +
                  "Runtimes that didn't produce a metric are omitted from that chart.</p>\n");
        sb.append("<div class=\"charts\">\n");
        for (String metric : activeChartMetrics()) {
            MetricDef md = METRICS.stream().filter(m -> m.name.equals(metric)).findFirst().orElseThrow();
            byte[] png = renderChart(md.titleAttr, md.label, metric, md.unit, runtimes, stats);
            if (png != null) {
                sb.append("<figure>").append(embedChart(png, md.titleAttr)).append("</figure>\n");
            }
        }
        sb.append("</div>\n");

        // ── Per-iteration raw values ──
        sb.append("<details><summary>Per-iteration raw values</summary>\n");
        sb.append("<div class=\"scroll\"><table class=\"raw\">\n<thead><tr><th>Runtime</th><th>Metric</th>");
        int maxIter = 0;
        for (var byMetric : raw.values())
            for (var arr : byMetric.values())
                if (arr.length > maxIter) maxIter = arr.length;
        for (int i = 0; i < maxIter; i++) sb.append("<th>iter ").append(i).append("</th>");
        sb.append("</tr></thead>\n<tbody>\n");
        for (String r : runtimes) {
            for (MetricDef md : activeMetrics()) {
                double[] vals = raw.getOrDefault(r, Map.of()).get(md.name);
                if (vals == null) continue;
                sb.append("<tr><th class=\"rt\">").append(esc(r)).append("</th>")
                  .append("<td class=\"metric\">").append(esc(md.label)).append("</td>");
                for (int i = 0; i < maxIter; i++) {
                    if (i < vals.length)
                        sb.append("<td>").append(fmtDouble(vals[i], md.decimals)).append("</td>");
                    else
                        sb.append("<td class=\"na\">—</td>");
                }
                sb.append("</tr>\n");
            }
        }
        sb.append("</tbody></table></div></details>\n");

        sb.append("<footer>Generated by analysis-scripts/GenerateReport.java from <code>")
          .append(esc(Paths.get(dbPath).toAbsolutePath().toString())).append("</code></footer>\n");
        sb.append("</body></html>\n");
        return sb.toString();
    }

    /**
     * Renders one category subsection: a banner with the overall winner
     * and the existing pair-wise comparison table for that category's
     * Quarkus variants.
     */
    void renderCategorySection(StringBuilder sb, String dotnetRt, String categoryLabel,
                               List<String> jvmRts,
                               Map<String, Map<String, MetricStats>> stats,
                               Map<String, Double> budgetMib) {
        CategoryResult cat            = computeCategoryWinner(dotnetRt, jvmRts, stats, activeMetrics());
        CategoryResult catEfficiency  = computeCategoryWinner(dotnetRt, jvmRts, stats, EFFICIENCY_ORDERED_METRICS);

        sb.append("<h3>").append(esc(dotnetRt)).append(" vs ").append(esc(categoryLabel))
          .append(" Quarkus variants</h3>\n");

        // Two banners: the canonical importance ordering (table-row order),
        // and an efficiency-first alternate ordering (rps/MiB → TTFR →
        // throughput → RSS-under-load). The side label "Quarkus (JVM-mode)"
        // or "Quarkus (Native)" reflects the category, not a specific runtime.
        String otherSideLabel = "Quarkus (" + categoryLabel + ")";
        renderVerdictBanner(sb, "Overall winner",            cat,           dotnetRt, otherSideLabel);
        renderVerdictBanner(sb, "Efficiency-weighted winner (rps/MiB → TTFR → throughput → RSS load)",
                            catEfficiency, dotnetRt, otherSideLabel);

        renderCompareTable(sb, dotnetRt, jvmRts, stats, budgetMib);
    }

    private static void renderVerdictBanner(StringBuilder sb, String label, CategoryResult cat,
                                            String dotnetRt, String otherSideLabel) {
        sb.append("<p class=\"verdict ");
        if (cat.winnerSide == WinnerSide.DOTNET) sb.append("verdict-dotnet");
        else if (cat.winnerSide == WinnerSide.OTHER) sb.append("verdict-other");
        else sb.append("verdict-tie");
        sb.append("\">").append(esc(label)).append(": <strong>");
        sb.append(switch (cat.winnerSide) {
            case DOTNET -> esc(dotnetRt);
            case OTHER  -> esc(otherSideLabel);
            case TIE    -> "tied";
        });
        sb.append("</strong>");
        sb.append(" <span class=\"verdict-detail\">(metric wins: ")
          .append(esc(dotnetRt)).append("=").append(cat.dotnetWins)
          .append(", ").append(esc(otherSideLabel)).append("=").append(cat.otherWins);
        if (cat.ties > 0) sb.append(", ties=").append(cat.ties);
        sb.append("; tiebreaker: ");
        sb.append(cat.tiebreakerMetric == null ? "none — fully tied" : esc(cat.tiebreakerMetric));
        sb.append(")</span></p>\n");
    }

    /**
     * The actual rows-of-metrics × columns-of-runtimes table.
     * Pulled out of renderCategorySection so the table layout is shared.
     */
    void renderCompareTable(StringBuilder sb, String dotnetRt, List<String> jvmRts,
                            Map<String, Map<String, MetricStats>> stats,
                            Map<String, Double> budgetMib) {
        sb.append("<div class=\"scroll\"><table class=\"compare\">\n");
        sb.append("<thead><tr><th rowspan=\"2\">Metric</th><th rowspan=\"2\" class=\"baseline\">")
          .append(esc(dotnetRt)).append("</th>");
        for (String jvmRt : jvmRts) {
            sb.append("<th colspan=\"2\" class=\"pair-start\">").append(esc(jvmRt)).append("</th>");
        }
        sb.append("</tr><tr>");
        for (int j = 0; j < jvmRts.size(); j++) {
            sb.append("<th class=\"pair-start\">value</th><th>ratio</th>");
        }
        sb.append("</tr></thead>\n<tbody>\n");

        for (MetricDef md : activeMetrics()) {
            MetricStats baseline = stats.getOrDefault(dotnetRt, Map.of()).get(md.name);

            // Pick a single winner across all participants in this row
            // (the dotnet baseline + every JVM-family variant). Ties at the
            // top yield no winner — we never paint multiple cells green.
            String winner = pickWinner(md, dotnetRt, baseline, jvmRts, stats);

            sb.append("<tr><th class=\"metric\">").append(esc(md.label))
              .append(" <span class=\"unit\">(").append(esc(md.unit)).append(")</span></th>");

            String dotnetCellCls = "baseline";
            if (dotnetRt.equals(winner)) dotnetCellCls += " win";
            if (md.isMemory && exceedsBudget(baseline, budgetMib.get(dotnetRt))) dotnetCellCls += " over";
            sb.append("<td class=\"").append(dotnetCellCls).append("\">")
              .append(formatBaseline(baseline, md.decimals)).append("</td>");

            for (String jvmRt : jvmRts) {
                MetricStats other = stats.getOrDefault(jvmRt, Map.of()).get(md.name);
                String cls = "pair-start";
                if (jvmRt.equals(winner)) cls += " win";
                if (md.isMemory && exceedsBudget(other, budgetMib.get(jvmRt))) cls += " over";
                sb.append("<td class=\"").append(cls).append("\">")
                  .append(formatBaseline(other, md.decimals)).append("</td>");
                sb.append("<td>").append(formatRatio(baseline, other)).append("</td>");
            }
            sb.append("</tr>\n");
        }
        sb.append("</tbody></table></div>\n");
    }

    /**
     * Renders the closed-world analysis output from GraalVM/Mandrel
     * native-image: binary size, reachable types/fields/methods, the
     * subset of those registered for reflection (with percentage of
     * total), and the build-time peak RSS.
     */
    void renderNativeClosedWorldSection(StringBuilder sb, List<String> nativeRts,
                                        Map<String, Map<String, MetricStats>> stats) {
        sb.append("<h2>Native closed-world analysis</h2>\n");
        sb.append("<p class=\"hint\">Output of GraalVM/Mandrel native-image's build-time reachability analysis. " +
                  "Under the closed-world assumption only types/fields/methods reached at build time end up in the binary; " +
                  "anything dynamic (reflection, JNI, resources) must be explicitly registered. " +
                  "The reflection columns show registered count and as a percentage of total reachable.</p>\n");
        sb.append("<div class=\"scroll\"><table class=\"native-stats\">\n");
        sb.append("<thead><tr>")
          .append("<th>Runtime</th>")
          .append("<th>Binary size<br><span class=\"unit\">MiB</span></th>")
          .append("<th>Build RSS<br><span class=\"unit\">GiB</span></th>")
          .append("<th>Classes reachable</th>")
          .append("<th>Fields reachable</th>")
          .append("<th>Methods reachable</th>")
          .append("<th>Classes registered<br>for reflection</th>")
          .append("<th>Fields registered<br>for reflection</th>")
          .append("<th>Methods registered<br>for reflection</th>")
          .append("</tr></thead>\n<tbody>\n");

        for (String r : nativeRts) {
            Map<String, MetricStats> m = stats.getOrDefault(r, Map.of());
            MetricStats binSize    = m.get("native_binary_size_mib");
            MetricStats buildRss   = m.get("native_build_rss_gib");
            MetricStats classes    = m.get("native_classes_reachable");
            MetricStats fields     = m.get("native_fields_reachable");
            MetricStats methods    = m.get("native_methods_reachable");
            MetricStats refClasses = m.get("native_reflection_classes");
            MetricStats refFields  = m.get("native_reflection_fields");
            MetricStats refMethods = m.get("native_reflection_methods");

            sb.append("<tr><th class=\"rt\">").append(esc(r)).append("</th>")
              .append("<td>").append(formatBaseline(binSize, 1)).append("</td>")
              .append("<td>").append(formatBaseline(buildRss, 2)).append("</td>")
              .append("<td>").append(formatCount(classes)).append("</td>")
              .append("<td>").append(formatCount(fields)).append("</td>")
              .append("<td>").append(formatCount(methods)).append("</td>")
              .append("<td>").append(formatCountPct(refClasses, classes)).append("</td>")
              .append("<td>").append(formatCountPct(refFields, fields)).append("</td>")
              .append("<td>").append(formatCountPct(refMethods, methods)).append("</td>")
              .append("</tr>\n");
        }
        sb.append("</tbody></table></div>\n");
    }

    // ── Statistical comparisons (Java/Quarkus vs dotnet) ──────────────────

    /**
     * Per-metric pairwise tables. For each metric, one row per (dotnet, jvm)
     * pair showing the two means, Cohen's d (Quarkus - dotnet pooled-stddev
     * effect size), and the two-tailed Welch p-value with significance
     * highlighting. Skips metrics that are mostly zero (error counters)
     * because d/p there are uninformative.
     */
    void renderStatisticalComparisons(StringBuilder sb,
                                      List<String> dotnetRts, List<String> jvmFamilyRts,
                                      Map<String, Map<String, MetricStats>> stats) {
        sb.append("<h2>Statistical comparisons (Java/Quarkus vs dotnet)</h2>\n");
        sb.append("<p class=\"hint\">Per-metric pairwise comparisons. " +
                  "Cohen's d sign reflects (Quarkus − dotnet): positive = Quarkus higher, negative = dotnet higher. " +
                  "Welch's two-tailed t-test does not assume equal variances. " +
                  "<span class=\"p-very-sig\">p&nbsp;&lt;&nbsp;0.001</span> · " +
                  "<span class=\"p-sig\">p&nbsp;&lt;&nbsp;0.05</span> · " +
                  "<span class=\"p-noisy\">not significant</span>.</p>\n");

        for (MetricDef md : activeMetrics()) {
            // Skip mostly-zero error counters where Cohen's d / p-value are degenerate.
            if (md.name.equals("load_connection_errors")
                    || md.name.equals("load_request_timeouts"))
                continue;

            // Collect rows for pairs that have data on both sides.
            List<Object[]> rows = new ArrayList<>();
            for (String dotnet : dotnetRts) {
                for (String jvm : jvmFamilyRts) {
                    MetricStats d = stats.getOrDefault(dotnet, Map.of()).get(md.name);
                    MetricStats q = stats.getOrDefault(jvm,    Map.of()).get(md.name);
                    if (d == null || q == null || d.stddev == null || q.stddev == null) continue;
                    if (d.n < 2 || q.n < 2) continue;
                    double cd = StatsHelper.cohensD(q.mean, q.stddev, q.n,
                                                   d.mean, d.stddev, d.n);
                    double p  = StatsHelper.welchPValue(q.mean, q.stddev, q.n,
                                                        d.mean, d.stddev, d.n);
                    rows.add(new Object[] { dotnet, jvm, q, d, cd, p });
                }
            }
            if (rows.isEmpty()) continue;

            sb.append("<h3>").append(esc(md.label))
              .append(" <span class=\"unit\">(").append(esc(md.unit)).append(")</span>")
              .append(" <span class=\"dir\">— ").append(md.lowerIsBetter ? "lower" : "higher")
              .append(" is better</span></h3>\n");
            sb.append("<div class=\"scroll\"><table class=\"compare\">\n");
            sb.append("<thead><tr>")
              .append("<th>Comparison</th>")
              .append("<th>dotnet mean</th>")
              .append("<th>Quarkus mean</th>")
              .append("<th>Cohen's d</th>")
              .append("<th>Welch p</th>")
              .append("</tr></thead>\n<tbody>\n");

            for (Object[] r : rows) {
                String dotnet = (String) r[0];
                String jvm    = (String) r[1];
                MetricStats q = (MetricStats) r[2];
                MetricStats d = (MetricStats) r[3];
                double cd     = (Double) r[4];
                double p      = (Double) r[5];
                sb.append("<tr>")
                  .append("<td>").append(esc(dotnet)).append(" vs ").append(esc(jvm)).append("</td>")
                  .append("<td>").append(fmtDouble(d.mean, md.decimals)).append("</td>")
                  .append("<td>").append(fmtDouble(q.mean, md.decimals)).append("</td>")
                  .append("<td class=\"").append(cohensDCls(cd)).append("\">").append(formatCohensD(cd)).append("</td>")
                  .append("<td class=\"").append(pValueCls(p)).append("\">").append(formatPValueWithMarker(p)).append("</td>")
                  .append("</tr>\n");
            }
            sb.append("</tbody></table></div>\n");
        }
    }

    static String formatCohensD(double d) {
        if (Double.isNaN(d)) return "—";
        String label = StatsHelper.cohensDLabel(d);
        String num = String.format(Locale.ROOT, "%.2f", d);
        return label.isEmpty() ? num : num + " <span class=\"d-label\">" + label + "</span>";
    }

    static String cohensDCls(double d) {
        if (Double.isNaN(d)) return "";
        double a = Math.abs(d);
        if (a < 0.2) return "d-negligible";
        if (a < 0.5) return "d-small";
        if (a < 0.8) return "d-medium";
        return "d-large";
    }

    static String formatPValueWithMarker(double p) {
        if (Double.isNaN(p)) return "—";
        String marker = p < 0.05 ? " ✓" : " ✗";
        if (p < 0.0001) return "&lt;0.0001" + marker;
        return String.format(Locale.ROOT, "%.4f%s", p, marker);
    }

    static String pValueCls(double p) {
        if (Double.isNaN(p)) return "";
        if (p < 0.001) return "p-very-sig";
        if (p < 0.05)  return "p-sig";
        return "p-noisy";
    }

    // ── Workload simulation ────────────────────────────────────────────────
    // Closed-form models. The serverless model assumes a stream of N
    // invocations with cold-start probability p_cold; cost is the
    // expected memory-time integral per invocation. The long-running
    // model assumes a fixed target rps over T hours, with each instance
    // running at some utilization fraction of its measured capacity;
    // SLO compliance is degraded linearly past the target latency,
    // reaching zero at 2× the target. Both produce a single comparable
    // score that's robust to small noise but explainable from the
    // inputs printed alongside.

    /** Default workload params — keep in sync with the description text. */
    record ServerlessParams(long invocations, double pCold) {
        static final ServerlessParams DEFAULT = new ServerlessParams(1_000_000L, 0.20);
    }
    record LongRunningParams(double targetRps, double durationHours,
                             double sloMaxMs, double utilization) {
        static final LongRunningParams DEFAULT =
            new LongRunningParams(10_000.0, 24.0, 100.0, 0.80);
    }

    record ServerlessResult(String runtime, double warmCostMibMs, double coldTaxMibMs,
                            double totalGbSeconds) {}

    record LongRunningResult(String runtime, double sloCompliance, double effectiveRps,
                             int instances, double totalMemoryMib, double rpsPerGbHour) {}

    static ServerlessResult simServerless(String runtime,
                                          MetricStats ttfr, MetricStats rss,
                                          MetricStats latencyAvg, ServerlessParams p) {
        if (missing(ttfr) || missing(rss) || missing(latencyAvg)) return null;
        double warmCost  = latencyAvg.mean * rss.mean;             // MiB·ms / invocation
        double coldExtra = ttfr.mean * rss.mean;                   // MiB·ms extra / cold
        double expected  = warmCost + p.pCold * coldExtra;          // MiB·ms / invocation
        double totalGbSeconds = (expected * p.invocations) / 1024.0 / 1000.0;
        return new ServerlessResult(runtime, warmCost, p.pCold * coldExtra, totalGbSeconds);
    }

    static LongRunningResult simLongRunning(String runtime,
                                            MetricStats throughput, MetricStats rssLoad,
                                            MetricStats latencyMax, LongRunningParams p) {
        if (missing(throughput) || missing(rssLoad)) return null;
        double slo = 1.0;
        if (!missing(latencyMax)) {
            double overshoot = Math.max(0.0, latencyMax.mean - p.sloMaxMs);
            slo = Math.max(0.0, 1.0 - overshoot / p.sloMaxMs); // 1 at SLO, 0 at 2×SLO
        }
        double effectiveRps = throughput.mean * slo;
        int instances = (int) Math.ceil(p.targetRps / (throughput.mean * p.utilization));
        if (instances < 1) instances = 1;
        double totalMemoryMib = instances * rssLoad.mean;
        double gbHours = (totalMemoryMib / 1024.0) * p.durationHours;
        double totalRequests = effectiveRps * 3600.0 * p.durationHours;
        double rpsPerGbHour = gbHours == 0 ? Double.NaN : totalRequests / gbHours;
        return new LongRunningResult(runtime, slo, effectiveRps, instances,
                                     totalMemoryMib, rpsPerGbHour);
    }

    static boolean missing(MetricStats s) {
        return s == null || s.n == 0 || Double.isNaN(s.mean);
    }

    void renderSimulationSection(StringBuilder sb, List<String> runtimes,
                                 Map<String, Map<String, MetricStats>> stats) {
        ServerlessParams sp = ServerlessParams.DEFAULT;
        LongRunningParams lp = LongRunningParams.DEFAULT;

        sb.append("<h2>Workload simulation</h2>\n");
        sb.append("<p class=\"hint\">Closed-form models that translate per-runtime metrics into deployment-archetype outcomes. " +
                  "Inputs come from the measured columns in the per-runtime summary; the math is shown alongside the score so readers can sanity-check it.</p>\n");

        // Serverless
        sb.append("<h3>Serverless function</h3>\n");
        sb.append("<p class=\"hint\">").append(String.format(Locale.ROOT,
            "Simulated workload: <strong>%,d invocations</strong>, cold-start probability <strong>%.0f%%</strong>. " +
            "Per-invocation cost = avg latency × RSS-after-first-request (warm) + p<sub>cold</sub> × TTFR × RSS (cold tax). " +
            "Result is total billable GB·seconds; <strong>lower is better</strong>.",
            sp.invocations, sp.pCold * 100.0)).append("</p>\n");

        List<ServerlessResult> srvResults = new ArrayList<>();
        for (String r : runtimes) {
            Map<String, MetricStats> m = stats.getOrDefault(r, Map.of());
            ServerlessResult res = simServerless(r,
                m.get("ttfr_ms"), m.get("rss_first_request_mib"), m.get("latency_avg_ms"), sp);
            if (res != null) srvResults.add(res);
        }
        srvResults.sort((a, b) -> Double.compare(a.totalGbSeconds, b.totalGbSeconds));
        renderServerlessTable(sb, srvResults);

        // Long-running
        sb.append("<h3>Long-running service</h3>\n");
        sb.append("<p class=\"hint\">").append(String.format(Locale.ROOT,
            "Simulated workload: <strong>%,.0f rps for %.0f hours</strong>, instances pinned to <strong>%.0f%% utilization</strong>, " +
            "Service Level Objective (SLO) target latency <strong>%.0f ms</strong> — i.e. the maximum request latency the service commits to. " +
            "The simulator uses the measured max latency as a proxy: full credit at the SLO, zero credit at 2× the SLO, linear in between. " +
            "Score = SLO-compliant requests served per GB·hour of cluster memory; <strong>higher is better</strong>.",
            lp.targetRps, lp.durationHours, lp.utilization * 100.0, lp.sloMaxMs)).append("</p>\n");

        List<LongRunningResult> lrResults = new ArrayList<>();
        for (String r : runtimes) {
            Map<String, MetricStats> m = stats.getOrDefault(r, Map.of());
            LongRunningResult res = simLongRunning(r,
                m.get("load_throughput_rps"), m.get("load_rss_mib"), m.get("latency_max_ms"), lp);
            if (res != null) lrResults.add(res);
        }
        lrResults.sort((a, b) -> Double.compare(b.rpsPerGbHour, a.rpsPerGbHour));
        renderLongRunningTable(sb, lrResults);
    }

    void renderServerlessTable(StringBuilder sb, List<ServerlessResult> rs) {
        sb.append("<div class=\"scroll\"><table class=\"compare\">\n");
        sb.append("<thead><tr><th>Rank</th><th>Runtime</th>")
          .append("<th>Warm cost<br><span class=\"unit\">MiB·ms / req</span></th>")
          .append("<th>Cold tax<br><span class=\"unit\">MiB·ms / req</span></th>")
          .append("<th>Total<br><span class=\"unit\">GB·seconds</span></th>")
          .append("<th>Ratio vs winner</th>")
          .append("</tr></thead>\n<tbody>\n");
        double best = rs.isEmpty() ? Double.NaN : rs.get(0).totalGbSeconds;
        for (int i = 0; i < rs.size(); i++) {
            ServerlessResult r = rs.get(i);
            String cls = (i == 0) ? "win" : "";
            sb.append("<tr><td class=\"").append(cls).append("\">").append(i + 1).append("</td>")
              .append("<th class=\"rt\">").append(esc(r.runtime)).append("</th>")
              .append("<td>").append(String.format(Locale.ROOT, "%,.0f", r.warmCostMibMs)).append("</td>")
              .append("<td>").append(String.format(Locale.ROOT, "%,.0f", r.coldTaxMibMs)).append("</td>")
              .append("<td class=\"").append(cls).append("\">")
                .append(String.format(Locale.ROOT, "%,.0f", r.totalGbSeconds)).append("</td>")
              .append("<td>").append(i == 0 ? "1.00×" :
                  String.format(Locale.ROOT, "%.2f×", r.totalGbSeconds / best)).append("</td>")
              .append("</tr>\n");
        }
        sb.append("</tbody></table></div>\n");
    }

    void renderLongRunningTable(StringBuilder sb, List<LongRunningResult> rs) {
        sb.append("<div class=\"scroll\"><table class=\"compare\">\n");
        sb.append("<thead><tr><th>Rank</th><th>Runtime</th>")
          .append("<th>SLO compliance</th>")
          .append("<th>Effective<br><span class=\"unit\">rps</span></th>")
          .append("<th>Instances<br>required</th>")
          .append("<th>Cluster RSS<br><span class=\"unit\">MiB</span></th>")
          .append("<th>Score<br><span class=\"unit\">SLO-rps / GB·hour</span></th>")
          .append("<th>Ratio vs winner</th>")
          .append("</tr></thead>\n<tbody>\n");
        double best = rs.isEmpty() ? Double.NaN : rs.get(0).rpsPerGbHour;
        for (int i = 0; i < rs.size(); i++) {
            LongRunningResult r = rs.get(i);
            String cls = (i == 0) ? "win" : "";
            sb.append("<tr><td class=\"").append(cls).append("\">").append(i + 1).append("</td>")
              .append("<th class=\"rt\">").append(esc(r.runtime)).append("</th>")
              .append("<td>").append(String.format(Locale.ROOT, "%.0f%%", r.sloCompliance * 100)).append("</td>")
              .append("<td>").append(String.format(Locale.ROOT, "%,.0f", r.effectiveRps)).append("</td>")
              .append("<td>").append(r.instances).append("</td>")
              .append("<td>").append(String.format(Locale.ROOT, "%,.0f", r.totalMemoryMib)).append("</td>")
              .append("<td class=\"").append(cls).append("\">")
                .append(Double.isNaN(r.rpsPerGbHour) ? "—" :
                        String.format(Locale.ROOT, "%,.0f", r.rpsPerGbHour)).append("</td>")
              .append("<td>").append(Double.isNaN(r.rpsPerGbHour) || i == 0 ? "1.00×" :
                  String.format(Locale.ROOT, "%.2f×", r.rpsPerGbHour / best)).append("</td>")
              .append("</tr>\n");
        }
        sb.append("</tbody></table></div>\n");
    }

    static String formatCount(MetricStats s) {
        if (s == null || s.n == 0 || Double.isNaN(s.mean)) return "<span class=\"na\">—</span>";
        return String.format(Locale.ROOT, "%,d", (long) s.mean);
    }

    static String formatCountPct(MetricStats subset, MetricStats total) {
        if (subset == null || subset.n == 0 || Double.isNaN(subset.mean))
            return "<span class=\"na\">—</span>";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%,d", (long) subset.mean));
        if (total != null && total.n > 0 && !Double.isNaN(total.mean) && total.mean > 0) {
            double pct = 100.0 * subset.mean / total.mean;
            sb.append(" <span class=\"pct\">(").append(String.format(Locale.ROOT, "%.1f", pct)).append("%)</span>");
        }
        return sb.toString();
    }

    /** What the per-row determination resolves to within a category. */
    enum WinnerSide { DOTNET, OTHER, TIE }

    record CategoryResult(WinnerSide winnerSide, int dotnetWins, int otherWins, int ties,
                          String tiebreakerMetric) {}

    /**
     * Computes the overall winner for one (dotnet, otherSide) category.
     *
     * For each metric, the better side wins (per `lowerIsBetter`); the side
     * is "dotnet" if its mean beats the best-of-other-side, "other" if any
     * member of the other side beats dotnet, "tie" if equal. Wins are
     * importance-weighted with weight 2^(N-1-i) where i is the metric's
     * position in METRICS (top metric is biggest); the resulting score
     * outweighs every later metric combined, so the ranking is effectively
     * lexicographic in the order of importance.
     *
     * `tiebreakerMetric` is the label of the first metric whose winner side
     * matches the overall winner — i.e. the metric that broke the tie.
     */
    static CategoryResult computeCategoryWinner(String dotnetRt, List<String> otherRts,
                                                Map<String, Map<String, MetricStats>> stats,
                                                List<MetricDef> orderedMetrics) {
        int dotnetWins = 0, otherWins = 0, ties = 0;
        long dotnetScore = 0L, otherScore = 0L;
        String firstMetricDotnetWon = null;
        String firstMetricOtherWon  = null;

        for (int i = 0; i < orderedMetrics.size(); i++) {
            MetricDef md = orderedMetrics.get(i);
            long weight = 1L << (orderedMetrics.size() - 1 - i); // 2^(N-1-i)

            MetricStats baseline = stats.getOrDefault(dotnetRt, Map.of()).get(md.name);
            // Best mean among the other side for this metric.
            Double otherBest = null;
            for (String r : otherRts) {
                MetricStats s = stats.getOrDefault(r, Map.of()).get(md.name);
                if (s == null || s.n == 0 || Double.isNaN(s.mean)) continue;
                if (otherBest == null) otherBest = s.mean;
                else otherBest = md.lowerIsBetter ? Math.min(otherBest, s.mean) : Math.max(otherBest, s.mean);
            }
            boolean dotnetHas = baseline != null && baseline.n > 0 && !Double.isNaN(baseline.mean);
            if (!dotnetHas && otherBest == null) continue; // metric has no data anywhere
            if (!dotnetHas) { otherWins++; otherScore += weight; if (firstMetricOtherWon == null) firstMetricOtherWon = md.label; continue; }
            if (otherBest == null) { dotnetWins++; dotnetScore += weight; if (firstMetricDotnetWon == null) firstMetricDotnetWon = md.label; continue; }
            if (baseline.mean == otherBest) { ties++; continue; }
            boolean dotnetBetter = md.lowerIsBetter ? baseline.mean < otherBest : baseline.mean > otherBest;
            if (dotnetBetter) {
                dotnetWins++; dotnetScore += weight;
                if (firstMetricDotnetWon == null) firstMetricDotnetWon = md.label;
            } else {
                otherWins++; otherScore += weight;
                if (firstMetricOtherWon == null) firstMetricOtherWon = md.label;
            }
        }

        WinnerSide winner;
        String tiebreaker;
        if (dotnetScore > otherScore) {
            winner = WinnerSide.DOTNET;
            tiebreaker = firstMetricDotnetWon;
        } else if (otherScore > dotnetScore) {
            winner = WinnerSide.OTHER;
            tiebreaker = firstMetricOtherWon;
        } else {
            winner = WinnerSide.TIE;
            tiebreaker = null;
        }
        return new CategoryResult(winner, dotnetWins, otherWins, ties, tiebreaker);
    }

    /**
     * Returns the runtime name with the strictly best value for this metric
     * across the dotnet baseline + every JVM-family variant. Returns null
     * if there's a tie at the top (so no cell is highlighted) or if no
     * runtime has usable data.
     */
    static String pickWinner(MetricDef md, String dotnetRt, MetricStats baseline,
                             List<String> jvmRts, Map<String, Map<String, MetricStats>> stats) {
        String bestName = null;
        double bestValue = md.lowerIsBetter ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        boolean tied = false;

        if (baseline != null && baseline.n > 0 && !Double.isNaN(baseline.mean)) {
            bestName = dotnetRt;
            bestValue = baseline.mean;
        }
        for (String jvmRt : jvmRts) {
            MetricStats other = stats.getOrDefault(jvmRt, Map.of()).get(md.name);
            if (other == null || other.n == 0 || Double.isNaN(other.mean)) continue;
            double v = other.mean;
            if (bestName == null) { bestName = jvmRt; bestValue = v; tied = false; continue; }
            if (v == bestValue) { tied = true; continue; }
            boolean betterThanBest = md.lowerIsBetter ? v < bestValue : v > bestValue;
            if (betterThanBest) { bestName = jvmRt; bestValue = v; tied = false; }
        }
        return tied ? null : bestName;
    }

    /** True iff stats has data and its mean exceeds the budget. */
    static boolean exceedsBudget(MetricStats s, Double budgetMib) {
        if (s == null || s.n == 0 || Double.isNaN(s.mean)) return false;
        if (budgetMib == null || budgetMib.isNaN() || budgetMib <= 0) return false;
        return s.mean > budgetMib;
    }

    /**
     * Returns the configured max memory in MiB for a runtime, or NaN if
     * unknown. JVM-family runtimes use the parsed -Xmx from jvm_memory;
     * dotnet runtimes use dotnet_gc_heap_hard_limit.
     */
    static Double runtimeMemoryBudgetMib(String runtime, Map<String, Object> meta) {
        if (runtime.startsWith("dotnet")) {
            return parseDotnetLimitMib(asString(meta.get("dotnet_gc_heap_hard_limit")));
        }
        return parseXmxMib(asString(meta.get("jvm_memory")));
    }

    /** Extracts the -Xmx value from a JVM args string (e.g. "-Xms512m -Xmx1g")
     *  and converts to MiB. Returns NaN if no -Xmx is present. */
    static Double parseXmxMib(String jvmMemory) {
        if (jvmMemory == null) return Double.NaN;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "-Xmx(\\d+)([kKmMgGtT]?)").matcher(jvmMemory);
        if (!m.find()) return Double.NaN;
        long n = Long.parseLong(m.group(1));
        char unit = m.group(2).isEmpty() ? 'b' : Character.toLowerCase(m.group(2).charAt(0));
        return switch (unit) {
            case 'k' -> n / 1024.0;
            case 'm' -> (double) n;
            case 'g' -> n * 1024.0;
            case 't' -> n * 1024.0 * 1024.0;
            default  -> n / (1024.0 * 1024.0); // raw bytes
        };
    }

    /** Parses dotnet_gc_heap_hard_limit (hex bytes string like "0x20000000")
     *  to MiB. Returns NaN if missing or unparseable. */
    static Double parseDotnetLimitMib(String s) {
        if (s == null || s.isEmpty()) return Double.NaN;
        try {
            long bytes = s.startsWith("0x") || s.startsWith("0X")
                ? Long.parseUnsignedLong(s.substring(2), 16)
                : Long.parseLong(s);
            return bytes / (1024.0 * 1024.0);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static String asString(Object o) { return o == null ? null : o.toString(); }

    /** Mean rounded to the metric's decimals; em-dash for missing data. */
    static String formatBaseline(MetricStats s, int decimals) {
        if (s == null || s.n == 0 || Double.isNaN(s.mean)) return "<span class=\"na\">—</span>";
        return fmtDouble(s.mean, decimals);
    }

    /**
     * Pretty-print other.mean / baseline.mean with a × suffix. The cell carries
     * a tooltip with Cohen's d (effect size) and the two-tailed Welch p-value
     * for hover-on-demand statistical context. The dedicated "Statistical
     * comparisons" section below visualises significance per-metric — keeping
     * a colour cue on the ratio cell here would compete with the row-winner
     * green and fire on nearly every cell, since n=7 with tight CV% makes
     * almost every cross-runtime comparison statistically significant.
     */
    static String formatRatio(MetricStats baseline, MetricStats other) {
        if (baseline == null || other == null) return "<span class=\"na\">—</span>";
        if (baseline.n == 0 || other.n == 0) return "<span class=\"na\">—</span>";
        if (Double.isNaN(baseline.mean) || Double.isNaN(other.mean)) return "<span class=\"na\">—</span>";
        if (baseline.mean == 0) return "<span class=\"na\">—</span>";
        double ratio = other.mean / baseline.mean;
        int d = ratio < 1 ? 3 : (ratio < 10 ? 2 : 1);
        String tooltip = ratioTooltip(baseline, other);
        StringBuilder out = new StringBuilder();
        if (!tooltip.isEmpty()) out.append("<span title=\"").append(esc(tooltip)).append("\">");
        out.append(fmtDouble(ratio, d)).append("×");
        if (!tooltip.isEmpty()) out.append("</span>");
        return out.toString();
    }

    /** Tooltip text: "d=0.84 (large), Welch p=0.0012". */
    private static String ratioTooltip(MetricStats a, MetricStats b) {
        if (a == null || b == null || a.stddev == null || b.stddev == null) return "";
        double d = StatsHelper.cohensD(b.mean, b.stddev, b.n, a.mean, a.stddev, a.n);
        double p = StatsHelper.welchPValue(b.mean, b.stddev, b.n, a.mean, a.stddev, a.n);
        StringBuilder s = new StringBuilder();
        if (!Double.isNaN(d)) {
            s.append("Cohen's d = ").append(String.format(Locale.ROOT, "%.2f", d));
            String label = StatsHelper.cohensDLabel(d);
            if (!label.isEmpty()) s.append(" (").append(label).append(")");
        }
        if (!Double.isNaN(p)) {
            if (s.length() > 0) s.append(", ");
            s.append("Welch p = ").append(formatPValue(p));
        }
        return s.toString();
    }

    static String formatPValue(double p) {
        if (Double.isNaN(p)) return "—";
        if (p < 0.0001) return "<0.0001";
        return String.format(Locale.ROOT, "%.4f", p);
    }

    void appendMeta(StringBuilder sb, String label, Object value) {
        if (value == null) return;
        String s = value.toString();
        if (s.isEmpty()) return;
        sb.append("<tr><th>").append(esc(label)).append("</th><td>").append(esc(s)).append("</td></tr>\n");
    }

    /**
     * Cell content: <code>mean ± stddev (CV X%)</code>, with a tooltip
     * carrying the 95% confidence interval. n is annotated inline only when
     * it differs from the run-wide dominant count (so the anomaly is visible).
     *
     * @param dominantN run-wide iteration count surfaced in the table caption.
     */
    static String formatStats(MetricStats s, int decimals, int dominantN) {
        if (s == null || s.n == 0 || Double.isNaN(s.mean)) return "<span class=\"na\">—</span>";
        StringBuilder sb = new StringBuilder();
        String tip = ciTooltip(s);
        if (!tip.isEmpty()) sb.append("<span title=\"").append(esc(tip)).append("\">");
        sb.append(fmtDouble(s.mean, decimals));
        if (s.stddev != null && s.n >= 2) {
            sb.append(" <span class=\"sd\">± ").append(fmtDouble(s.stddev, decimals)).append("</span>");
            double cv = StatsHelper.cvPercent(s.mean, s.stddev);
            if (!Double.isNaN(cv)) {
                sb.append(" <span class=\"cv ").append(cvClass(cv)).append("\">(CV ")
                  .append(fmtCv(cv)).append(")</span>");
            }
        }
        if (dominantN <= 0 || s.n != dominantN) {
            sb.append(" <span class=\"n\">(n=").append(s.n).append(")</span>");
        }
        if (!tip.isEmpty()) sb.append("</span>");
        return sb.toString();
    }

    /** Tooltip "95% CI [lo, hi]" — width derived from t(n-1) × stddev/√n. */
    private static String ciTooltip(MetricStats s) {
        if (s == null || s.stddev == null || s.n < 2 || Double.isNaN(s.mean)) return "";
        double half = StatsHelper.ci95HalfWidth(s.stddev, s.n);
        if (Double.isNaN(half)) return "";
        return String.format(Locale.ROOT, "95%% CI [%s, %s]",
                fmtDouble(s.mean - half, 2), fmtDouble(s.mean + half, 2));
    }

    /** CV-percent magnitude class: <5% stable, 5–15% moderate, >15% noisy. */
    private static String cvClass(double cv) {
        if (cv < 5)  return "cv-stable";
        if (cv < 15) return "cv-moderate";
        return "cv-noisy";
    }

    private static String fmtCv(double cv) {
        if (cv < 1)  return String.format(Locale.ROOT, "%.2f%%", cv);
        if (cv < 10) return String.format(Locale.ROOT, "%.1f%%", cv);
        return       String.format(Locale.ROOT, "%.0f%%", cv);
    }

    /**
     * If every populated metric cell has the same iteration count, return
     * that value (so we can advertise it once in the table caption). Returns
     * 0 to signal "mixed" — fall back to per-cell labelling.
     */
    static int computeDominantN(Map<String, Map<String, MetricStats>> stats, Integer expectedFromMeta) {
        Set<Integer> ns = new HashSet<>();
        for (var byMetric : stats.values()) {
            for (var s : byMetric.values()) {
                if (s != null && s.n > 0) ns.add(s.n);
            }
        }
        if (ns.size() == 1) return ns.iterator().next();
        if (expectedFromMeta != null && expectedFromMeta > 0 && ns.size() == 0) {
            return expectedFromMeta;
        }
        return 0;
    }

    static String fmtDouble(double v, int decimals) {
        if (Double.isNaN(v)) return "—";
        return String.format(Locale.ROOT, "%,." + decimals + "f", v);
    }

    static String joinNonNull(Object... parts) {
        StringJoiner sj = new StringJoiner(" · ");
        for (Object p : parts) {
            if (p != null && !p.toString().isEmpty()) sj.add(p.toString());
        }
        return sj.length() == 0 ? null : sj.toString();
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ── Domain ──────────────────────────────────────────────────────────────

    record MetricStats(int n, double mean, Double stddev, double min, double max, String unit) {}

    record MetricDef(String name, String label, String unit, int decimals,
                     String titleAttr, boolean lowerIsBetter, boolean isMemory) {}

    // ── Style ───────────────────────────────────────────────────────────────

    static final String STYLE = """
        <style>
          :root { color-scheme: light dark; }
          body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif;
                 margin: 2rem auto; max-width: 1200px; padding: 0 1rem; line-height: 1.45; }
          h1 { margin-bottom: 0.2rem; }
          h2 { margin-top: 2.5rem; border-bottom: 1px solid #8884; padding-bottom: 0.25rem; }
          .hint { color: #888; font-size: 0.92em; margin-top: 0.2rem; }
          section.meta table { border-collapse: collapse; margin-top: 1rem; }
          section.meta th { text-align: left; padding-right: 1rem; vertical-align: top;
                            color: #888; font-weight: 500; white-space: nowrap; }
          section.meta td { padding: 0.1rem 0; font-family: ui-monospace, "SF Mono", Menlo, monospace; font-size: 0.92em; }
          .scroll { overflow-x: auto; }
          table.summary, table.raw, table.compare, table.native-stats {
              border-collapse: collapse; margin: 0.5rem 0; font-size: 0.92em;
          }
          table.summary th, table.summary td, table.raw th, table.raw td,
          table.compare th, table.compare td,
          table.native-stats th, table.native-stats td {
              padding: 0.35rem 0.6rem; text-align: right; border-bottom: 1px solid #8884;
          }
          table.native-stats th.rt { text-align: left; font-family: ui-monospace, monospace; }
          table.native-stats thead th { text-align: center; vertical-align: bottom; }
          table.native-stats .pct { color: #888; font-size: 0.85em; }
          table.compare thead tr:first-child th { border-bottom: none; padding-bottom: 0.1rem; }
          table.compare thead tr:nth-child(2) th { font-weight: 400; color: #888; font-size: 0.85em; padding-top: 0; }
          table.compare th.metric { text-align: left; font-weight: 500; white-space: nowrap; }
          table.compare th.metric .unit { color: #888; }
          table.compare th.baseline, table.compare td.baseline {
              background: #7fa8c81a;
              border-right: 2px solid #8886;
          }
          /* Vertical separator between each Quarkus variant's column-pair. */
          table.compare th.pair-start, table.compare td.pair-start {
              border-left: 1px solid #8884;
          }
          /* Winner cell — the variant that beats its pairing on this metric. */
          table.compare td.win, table.summary td.win {
              background: #B8E0BA;
              color: #1a4d1f;
              font-weight: 600;
          }
          /* Over-budget cell — RSS exceeds the runtime's configured memory budget
             (-Xmx for JVM-family, GCHeapHardLimit for dotnet). The .win + .over
             combination keeps the win greenness via gradient. */
          table.compare td.over, table.summary td.over {
              background: #F7C8C8;
              color: #6b1f1f;
          }
          table.compare td.over.win, table.summary td.over.win {
              background: linear-gradient(135deg, #B8E0BA 50%, #F7C8C8 50%);
              color: #2a3a2a;
          }
          .win-key { background: #B8E0BA; color: #1a4d1f; padding: 0 0.3em; border-radius: 3px; }
          .over-key { background: #F7C8C8; color: #6b1f1f; padding: 0 0.3em; border-radius: 3px; }
          .verdict { margin: 0.4rem 0 0.6rem 0; padding: 0.5rem 0.8rem; border-radius: 4px; }
          .verdict-dotnet { background: #B8E0BA40; border-left: 3px solid #4a9b50; }
          .verdict-other  { background: #C8D8F040; border-left: 3px solid #4a6b9b; }
          .verdict-tie    { background: #8884; border-left: 3px solid #888; }
          .verdict-detail { color: #888; font-size: 0.88em; font-weight: normal; }
          h3 { margin-top: 1.5rem; margin-bottom: 0.4rem; font-size: 1.05em; color: #555; }
          table.summary caption { caption-side: top; text-align: left;
              padding: 0.25rem 0; color: #888; font-size: 0.9em; font-style: italic; }
          table.summary thead th { text-align: center; vertical-align: bottom; }
          table.summary th.rt, table.raw th.rt { text-align: left; font-family: ui-monospace, monospace; }
          table.raw td.metric { text-align: left; color: #888; }
          .unit { font-size: 0.78em; color: #888; font-weight: normal; }
          .sd { color: #888; }
          .n { color: #aaa; font-size: 0.8em; }
          .na { color: #aaa; }
          .cv { font-size: 0.78em; }
          .cv-stable   { color: #2a8c2f; }
          .cv-moderate { color: #b58a14; }
          .cv-noisy    { color: #c23b22; font-weight: 500; }
          .d-negligible { color: #888; }
          .d-small      { color: #555; }
          .d-medium     { color: #b58a14; font-weight: 500; }
          .d-large      { color: #2a8c2f; font-weight: 500; }
          .d-label      { color: #888; font-weight: normal; font-size: 0.85em; }
          .dir          { color: #666; font-weight: normal; font-size: 0.85em; }
          .p-very-sig { background: #B8E0BA60; padding: 0 0.2em; border-radius: 3px; }
          .p-sig      { background: #B8E0BA30; padding: 0 0.2em; border-radius: 3px; }
          .p-noisy    { color: #999; }
          .charts { display: flex; flex-wrap: wrap; gap: 1.5rem; }
          .charts figure { margin: 0; }
          .chart { max-width: 100%; height: auto; border: 1px solid #8884; border-radius: 4px; background: white; }
          details { margin-top: 1rem; }
          summary { cursor: pointer; font-weight: 600; }
          footer { margin-top: 3rem; color: #888; font-size: 0.85em; border-top: 1px solid #8884; padding-top: 1rem; }
          code { background: #8881; padding: 0.05rem 0.3rem; border-radius: 3px; font-size: 0.9em; }
        </style>
        """;
}
