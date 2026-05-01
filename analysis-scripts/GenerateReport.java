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
        renderer.setErrorIndicatorPaint(java.awt.Color.DARK_GRAY);
        renderer.setIncludeBaseInRange(true);
        plot.setRenderer(renderer);
        plot.setDataset(ds);

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

    /** Metric definitions for the summary table (display order, axis labels, decimals). */
    static final List<MetricDef> METRICS = List.of(
        new MetricDef("build_time_s",            "Build time",     "s",      2, "Build time"),
        new MetricDef("ttfr_ms",                 "TTFR",           "ms",     1, "Time to first request"),
        new MetricDef("rss_startup_mib",         "RSS @ startup",  "MiB",    1, "RSS at startup"),
        new MetricDef("rss_first_request_mib",   "RSS @ 1st req",  "MiB",    1, "RSS after first request"),
        new MetricDef("load_throughput_rps",     "Throughput",     "rps",    0, "Load test throughput"),
        new MetricDef("load_rss_mib",            "RSS under load", "MiB",    1, "RSS during load test"),
        new MetricDef("load_throughput_density", "rps / MiB",      "rps/MiB",2, "Throughput per MiB of RSS"),
        new MetricDef("load_connection_errors",  "Conn err",       "count",  0, "Connection errors"),
        new MetricDef("load_request_timeouts",   "Timeouts",       "count",  0, "Request timeouts")
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
        appendMeta(sb, "Scenario",     meta.get("scenario_name"));
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

        // ── Summary table ──
        sb.append("<h2>Per-runtime summary</h2>\n");
        sb.append("<p class=\"hint\">Cells show mean ± sample standard deviation across iterations. " +
                  "<code>n</code> is the number of iterations contributing.</p>\n");
        sb.append("<div class=\"scroll\"><table class=\"summary\">\n<thead><tr><th>Runtime</th>");
        for (MetricDef md : METRICS) {
            sb.append("<th title=\"").append(esc(md.titleAttr)).append("\">")
              .append(esc(md.label)).append("<br><span class=\"unit\">")
              .append(esc(md.unit)).append("</span></th>");
        }
        sb.append("</tr></thead>\n<tbody>\n");
        for (String r : runtimes) {
            sb.append("<tr><th class=\"rt\">").append(esc(r)).append("</th>");
            for (MetricDef md : METRICS) {
                MetricStats s = stats.getOrDefault(r, Map.of()).get(md.name);
                sb.append("<td>").append(formatStats(s, md.decimals)).append("</td>");
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

    void appendMeta(StringBuilder sb, String label, Object value) {
        if (value == null) return;
        String s = value.toString();
        if (s.isEmpty()) return;
        sb.append("<tr><th>").append(esc(label)).append("</th><td>").append(esc(s)).append("</td></tr>\n");
    }

    static String formatStats(MetricStats s, int decimals) {
        if (s == null || s.n == 0 || Double.isNaN(s.mean)) return "<span class=\"na\">—</span>";
        StringBuilder sb = new StringBuilder();
        sb.append(fmtDouble(s.mean, decimals));
        if (s.stddev != null && s.n >= 2) {
            sb.append(" <span class=\"sd\">± ").append(fmtDouble(s.stddev, decimals)).append("</span>");
        }
        sb.append(" <span class=\"n\">(n=").append(s.n).append(")</span>");
        return sb.toString();
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

    record MetricDef(String name, String label, String unit, int decimals, String titleAttr) {}

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
          table.summary, table.raw { border-collapse: collapse; margin: 0.5rem 0; font-size: 0.92em; }
          table.summary th, table.summary td, table.raw th, table.raw td {
              padding: 0.35rem 0.6rem; text-align: right; border-bottom: 1px solid #8884;
          }
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
