///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.h2database:h2:2.3.232
//DEPS info.picocli:picocli:4.7.5
//DEPS org.jfree:jfreechart:1.5.4

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
        // how much, so it's the first thing the reader sees.
        List<String> dotnetRts = runtimes.stream().filter(r -> r.startsWith("dotnet")).toList();
        List<String> jvmFamilyRts = runtimes.stream().filter(r -> !r.startsWith("dotnet")).toList();
        if (!dotnetRts.isEmpty() && !jvmFamilyRts.isEmpty()) {
            sb.append("<h2>dotnet vs Quarkus comparison</h2>\n");
            sb.append("<p class=\"hint\">For each dotnet variant, every metric is shown alongside the same metric from each Quarkus variant. " +
                      "The ratio in parentheses is <code>quarkus / dotnet</code>: values above 1× mean Quarkus produced a higher number for that metric, below 1× means dotnet did. " +
                      "<span class=\"win-key\">Green</span> marks the winning side per metric. " +
                      "<span class=\"over-key\">Red</span> marks RSS values that exceed the runtime's configured memory budget.</p>\n");
            for (String dotnetRt : dotnetRts) {
                renderDotnetVsJvmTable(sb, dotnetRt, jvmFamilyRts, stats, budgetMib);
            }
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
        for (MetricDef md : METRICS) {
            sb.append("<th title=\"").append(esc(md.titleAttr)).append("\">")
              .append(esc(md.label)).append("<br><span class=\"unit\">")
              .append(esc(md.unit)).append("</span></th>");
        }
        sb.append("</tr></thead>\n<tbody>\n");
        for (String r : runtimes) {
            sb.append("<tr><th class=\"rt\">").append(esc(r)).append("</th>");
            Double budget = budgetMib.get(r);
            for (MetricDef md : METRICS) {
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
        for (String metric : CHART_METRICS) {
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
            for (MetricDef md : METRICS) {
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
     * Renders one comparison table: rows are metrics, columns are
     * (dotnet baseline, then every JVM-family runtime with its raw value
     * and the quarkus/dotnet ratio).
     */
    void renderDotnetVsJvmTable(StringBuilder sb, String dotnetRt, List<String> jvmRts,
                                Map<String, Map<String, MetricStats>> stats,
                                Map<String, Double> budgetMib) {
        sb.append("<h3>").append(esc(dotnetRt)).append(" vs Quarkus variants</h3>\n");
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

        for (MetricDef md : METRICS) {
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

    /** Pretty-print other.mean / baseline.mean with a × suffix. */
    static String formatRatio(MetricStats baseline, MetricStats other) {
        if (baseline == null || other == null) return "<span class=\"na\">—</span>";
        if (baseline.n == 0 || other.n == 0) return "<span class=\"na\">—</span>";
        if (Double.isNaN(baseline.mean) || Double.isNaN(other.mean)) return "<span class=\"na\">—</span>";
        if (baseline.mean == 0) return "<span class=\"na\">—</span>";
        double ratio = other.mean / baseline.mean;
        // 3 decimals when sub-unit, 2 when modest, 1 when large.
        int d = ratio < 1 ? 3 : (ratio < 10 ? 2 : 1);
        return fmtDouble(ratio, d) + "×";
    }

    void appendMeta(StringBuilder sb, String label, Object value) {
        if (value == null) return;
        String s = value.toString();
        if (s.isEmpty()) return;
        sb.append("<tr><th>").append(esc(label)).append("</th><td>").append(esc(s)).append("</td></tr>\n");
    }

    /**
     * @param dominantN the run-wide iteration count surfaced in the table
     *                  caption; if a cell has a different n we annotate it
     *                  inline so the anomaly is visible.
     */
    static String formatStats(MetricStats s, int decimals, int dominantN) {
        if (s == null || s.n == 0 || Double.isNaN(s.mean)) return "<span class=\"na\">—</span>";
        StringBuilder sb = new StringBuilder();
        sb.append(fmtDouble(s.mean, decimals));
        if (s.stddev != null && s.n >= 2) {
            sb.append(" <span class=\"sd\">± ").append(fmtDouble(s.stddev, decimals)).append("</span>");
        }
        if (dominantN <= 0 || s.n != dominantN) {
            sb.append(" <span class=\"n\">(n=").append(s.n).append(")</span>");
        }
        return sb.toString();
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
          table.summary, table.raw, table.compare { border-collapse: collapse; margin: 0.5rem 0; font-size: 0.92em; }
          table.summary th, table.summary td, table.raw th, table.raw td,
          table.compare th, table.compare td {
              padding: 0.35rem 0.6rem; text-align: right; border-bottom: 1px solid #8884;
          }
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
