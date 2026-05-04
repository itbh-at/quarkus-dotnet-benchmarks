///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.h2database:h2:2.3.232
//DEPS info.picocli:picocli:4.7.5
//DEPS org.apache.commons:commons-math3:3.6.1
//SOURCES StatsHelper.java

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Generates an HTML comparison report between two benchmark runs in the same
 * H2 database. Rows = metrics (one section per runtime), columns = base run /
 * target run / delta / winner. Designed for quick "what did this knob change?"
 * inspection — not a substitute for the per-run report.
 *
 * Usage:
 *   jbang analysis-scripts/CompareRuns.java --base 9 --target 10
 *   → reports/compare-run-9-vs-10.html
 */
@Command(name = "CompareRuns", mixinStandardHelpOptions = true,
         description = "Generate an HTML comparison report between two runs")
public class CompareRuns implements Callable<Integer> {

    @Option(names = "--base", required = true, description = "Baseline run_id")
    long baseRunId;

    @Option(names = "--target", required = true, description = "Target run_id")
    long targetRunId;

    @Option(names = "--db", description = "H2 database path (without extension)",
            defaultValue = "database/benchmarks")
    String dbPath;

    @Option(names = "--output",
            description = "Output HTML file (default: reports/compare-run-{base}-vs-{target}.html)")
    Path output;

    public static void main(String[] args) {
        System.exit(new CommandLine(new CompareRuns()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        String url = "jdbc:h2:" + Paths.get(dbPath).toAbsolutePath() + ";ACCESS_MODE_DATA=r";
        try (Connection conn = DriverManager.getConnection(url)) {
            Map<String, Object> baseMeta = loadMeta(conn, baseRunId);
            Map<String, Object> targetMeta = loadMeta(conn, targetRunId);
            if (baseMeta == null) { System.err.println("ERROR: run " + baseRunId + " not found"); return 1; }
            if (targetMeta == null) { System.err.println("ERROR: run " + targetRunId + " not found"); return 1; }

            Map<String, Map<String, Stats>> baseStats   = loadStats(conn, baseRunId);
            Map<String, Map<String, Stats>> targetStats = loadStats(conn, targetRunId);

            String html = renderHtml(baseMeta, targetMeta, baseStats, targetStats);

            Path out = output != null ? output :
                Paths.get("reports/compare-run-" + baseRunId + "-vs-" + targetRunId + ".html");
            if (out.toAbsolutePath().getParent() != null)
                Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, html);
            System.out.println("Wrote " + out.toAbsolutePath());
        }
        return 0;
    }

    // ── Domain ──────────────────────────────────────────────────────────────

    record Stats(int n, double mean, double stddev) {}

    record MetricDef(String name, String label, String unit, int decimals, boolean lowerIsBetter) {}

    /** Metrics shown in the per-runtime table. Ordered by importance. */
    static final List<MetricDef> METRICS = List.of(
        new MetricDef("load_throughput_rps",     "Throughput",     "rps",    0, false),
        new MetricDef("ttfr_ms",                 "TTFR",           "ms",     0, true),
        new MetricDef("load_throughput_density", "rps / MiB",      "rps/MiB",2, false),
        new MetricDef("rss_startup_mib",         "Startup RSS",    "MiB",    1, true),
        new MetricDef("rss_first_request_mib",   "1st-req RSS",    "MiB",    1, true),
        new MetricDef("load_rss_mib",            "Load RSS",       "MiB",    1, true),
        new MetricDef("latency_avg_ms",          "Latency avg",    "ms",     2, true),
        new MetricDef("latency_max_ms",          "Latency max",    "ms",     1, true),
        new MetricDef("build_time_s",            "Build time",     "s",      2, true)
    );

    /** Run metadata fields shown in the header table. */
    static final List<String[]> META_FIELDS = List.of(
        new String[] {"description", "Description"},
        new String[] {"jvm_memory",  "JVM heap"},
        new String[] {"jvm_args",    "JVM args"},
        new String[] {"jvm_version", "Java"},
        new String[] {"num_iterations", "Iterations"},
        new String[] {"started_at",  "Started"},
        new String[] {"deploy_short_commit", "Deploy commit"}
    );

    // ── Loading ─────────────────────────────────────────────────────────────

    Map<String, Object> loadMeta(Connection conn, long runId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT description, jvm_memory, jvm_args, jvm_version, num_iterations, " +
                "       started_at, deploy_short_commit, note " +
                "FROM runs WHERE run_id = ?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("run_id", runId);
                m.put("description",          rs.getString("description"));
                m.put("jvm_memory",           rs.getString("jvm_memory"));
                m.put("jvm_args",             rs.getString("jvm_args"));
                m.put("jvm_version",          rs.getString("jvm_version"));
                m.put("num_iterations",       rs.getObject("num_iterations"));
                m.put("started_at",           rs.getTimestamp("started_at"));
                m.put("deploy_short_commit",  rs.getString("deploy_short_commit"));
                m.put("note",                 rs.getString("note"));
                return m;
            }
        }
    }

    Map<String, Map<String, Stats>> loadStats(Connection conn, long runId) throws SQLException {
        Map<String, Map<String, Stats>> out = new LinkedHashMap<>();
        // STDDEV_SAMP gives N-1 stddev; matches the per-run report's convention.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT runtime_name, metric_name, COUNT(*) AS n, AVG(metric_value) AS mean, " +
                "       STDDEV_SAMP(metric_value) AS stddev " +
                "FROM iteration_metrics WHERE run_id = ? " +
                "GROUP BY runtime_name, metric_name " +
                "ORDER BY runtime_name, metric_name")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String runtime = rs.getString("runtime_name");
                    String metric  = rs.getString("metric_name");
                    int n          = rs.getInt("n");
                    double mean    = rs.getDouble("mean");
                    double stddev  = rs.getDouble("stddev");
                    if (rs.wasNull()) stddev = Double.NaN;
                    out.computeIfAbsent(runtime, k -> new LinkedHashMap<>())
                       .put(metric, new Stats(n, mean, stddev));
                }
            }
        }
        return out;
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    String renderHtml(Map<String, Object> baseMeta, Map<String, Object> targetMeta,
                      Map<String, Map<String, Stats>> baseStats,
                      Map<String, Map<String, Stats>> targetStats) {
        StringBuilder sb = new StringBuilder(32 * 1024);

        sb.append("<!DOCTYPE html>\n<html lang=\"en\"><head>\n<meta charset=\"utf-8\"/>\n");
        sb.append("<title>Compare run #").append(baseMeta.get("run_id"))
          .append(" vs #").append(targetMeta.get("run_id")).append("</title>\n");
        sb.append(STYLE);
        sb.append("</head><body>\n");

        sb.append("<h1>Comparison: run #").append(baseMeta.get("run_id"))
          .append(" → run #").append(targetMeta.get("run_id")).append("</h1>\n");

        // Header: metadata side-by-side, with diff-highlighted cells.
        sb.append("<h2>Configuration</h2>\n");
        sb.append("<table class=\"meta\"><thead><tr><th>Setting</th>")
          .append("<th>Run #").append(baseMeta.get("run_id")).append(" (base)</th>")
          .append("<th>Run #").append(targetMeta.get("run_id")).append(" (target)</th>")
          .append("</tr></thead><tbody>\n");
        for (String[] f : META_FIELDS) {
            String key = f[0], label = f[1];
            Object bv = baseMeta.get(key), tv = targetMeta.get(key);
            boolean differ = !Objects.equals(String.valueOf(bv), String.valueOf(tv));
            sb.append("<tr><th>").append(esc(label)).append("</th>")
              .append("<td").append(differ ? " class=\"differ\"" : "").append(">").append(esc(bv)).append("</td>")
              .append("<td").append(differ ? " class=\"differ\"" : "").append(">").append(esc(tv)).append("</td>")
              .append("</tr>\n");
        }
        sb.append("</tbody></table>\n");

        // Summary of wins/losses across all (runtime, metric) pairs.
        Set<String> allRuntimes = new LinkedHashSet<>();
        allRuntimes.addAll(baseStats.keySet());
        allRuntimes.addAll(targetStats.keySet());

        int totalCmp = 0, baseWins = 0, targetWins = 0, ties = 0;
        double biggestGainPct = 0;     String biggestGainLabel = "—";
        double biggestLossPct = 0;     String biggestLossLabel = "—";
        for (String r : allRuntimes) {
            Map<String, Stats> bs = baseStats.getOrDefault(r, Map.of());
            Map<String, Stats> ts = targetStats.getOrDefault(r, Map.of());
            for (MetricDef md : METRICS) {
                Stats b = bs.get(md.name), t = ts.get(md.name);
                if (b == null || t == null) continue;
                if (b.mean == 0 && t.mean == 0) { ties++; totalCmp++; continue; }
                totalCmp++;
                double deltaPct = (t.mean - b.mean) / Math.abs(b.mean) * 100.0;
                boolean targetBetter = md.lowerIsBetter ? t.mean < b.mean : t.mean > b.mean;
                if (b.mean == t.mean) ties++;
                else if (targetBetter) targetWins++;
                else baseWins++;
                // Track the biggest improvement and biggest regression for the
                // target side, where "improvement" respects metric direction.
                double improvementPct = md.lowerIsBetter ? -deltaPct : deltaPct;
                if (improvementPct > biggestGainPct) {
                    biggestGainPct = improvementPct;
                    biggestGainLabel = r + " · " + md.label + " (" + formatDelta(deltaPct) + ")";
                }
                if (improvementPct < biggestLossPct) {
                    biggestLossPct = improvementPct;
                    biggestLossLabel = r + " · " + md.label + " (" + formatDelta(deltaPct) + ")";
                }
            }
        }
        sb.append("<h2>Summary</h2>\n<p class=\"summary\">")
          .append("Of <strong>").append(totalCmp).append("</strong> (runtime × metric) comparisons: ")
          .append("target wins <strong>").append(targetWins).append("</strong>, ")
          .append("base wins <strong>").append(baseWins).append("</strong>")
          .append(ties > 0 ? ", ties <strong>" + ties + "</strong>" : "")
          .append(". Biggest improvement on target: <strong>").append(esc(biggestGainLabel))
          .append("</strong>. Biggest regression: <strong>").append(esc(biggestLossLabel)).append("</strong>.</p>\n");

        // Per-runtime tables.
        sb.append("<h2>Per-runtime comparison</h2>\n");
        sb.append("<p class=\"hint\">Cells show mean ± stddev with CV% (run-to-run consistency: " +
                  "<span class=\"cv-stable\">&lt;5% stable</span> · " +
                  "<span class=\"cv-moderate\">5-15% moderate</span> · " +
                  "<span class=\"cv-noisy\">&gt;15% noisy</span>). Hover a cell for the 95% CI. " +
                  "Δ% is target relative to base. d is Cohen's effect size; Welch p is the two-tailed p-value " +
                  "(<span class=\"p-very-sig\">p&lt;0.001</span>, <span class=\"p-sig\">p&lt;0.05</span>). " +
                  "<span class=\"win-key\">Green</span> marks the better mean per row; " +
                  "<span class=\"miss-key\">grey</span> marks rows where one side has no data.</p>\n");
        for (String r : allRuntimes) {
            Map<String, Stats> bs = baseStats.getOrDefault(r, Map.of());
            Map<String, Stats> ts = targetStats.getOrDefault(r, Map.of());
            sb.append("<h3>").append(esc(r)).append("</h3>\n");
            sb.append("<div class=\"scroll\"><table class=\"compare\"><thead><tr>")
              .append("<th>Metric</th>")
              .append("<th>Run #").append(baseMeta.get("run_id")).append("</th>")
              .append("<th>Run #").append(targetMeta.get("run_id")).append("</th>")
              .append("<th>Δ</th><th>Δ%</th>")
              .append("<th title=\"Cohen's d — pooled-stddev effect size, sign relative to base. ")
              .append("Magnitude: &lt;0.2 negligible, 0.2-0.5 small, 0.5-0.8 medium, ≥0.8 large.\">d</th>")
              .append("<th title=\"Welch's two-tailed t-test p-value — probability of seeing this difference if the runtimes were truly identical. ")
              .append("Bold/highlighted when p &lt; 0.05.\">Welch p</th>")
              .append("</tr></thead>\n<tbody>\n");
            for (MetricDef md : METRICS) {
                Stats b = bs.get(md.name), t = ts.get(md.name);
                String baseCls = "", targetCls = "";
                if (b != null && t != null) {
                    boolean targetBetter = md.lowerIsBetter ? t.mean < b.mean : t.mean > b.mean;
                    boolean baseBetter   = md.lowerIsBetter ? b.mean < t.mean : b.mean > t.mean;
                    if (targetBetter) targetCls = "win";
                    else if (baseBetter) baseCls = "win";
                } else if (b == null && t != null) {
                    baseCls = "miss";
                } else if (b != null && t == null) {
                    targetCls = "miss";
                } else {
                    continue; // both missing — skip the row entirely
                }
                sb.append("<tr><th class=\"metric\">").append(esc(md.label))
                  .append(" <span class=\"unit\">(").append(esc(md.unit)).append(")</span></th>")
                  .append("<td class=\"").append(baseCls).append("\">").append(formatStats(b, md.decimals)).append("</td>")
                  .append("<td class=\"").append(targetCls).append("\">").append(formatStats(t, md.decimals)).append("</td>");
                if (b != null && t != null && b.mean != 0) {
                    double abs = t.mean - b.mean;
                    double pct = abs / Math.abs(b.mean) * 100.0;
                    double d   = StatsHelper.cohensD(t.mean, t.stddev, t.n,
                                                    b.mean, b.stddev, b.n);
                    double p   = StatsHelper.welchPValue(t.mean, t.stddev, t.n,
                                                         b.mean, b.stddev, b.n);
                    sb.append("<td>").append(formatAbs(abs, md.decimals)).append("</td>")
                      .append("<td class=\"").append(deltaCls(pct, md.lowerIsBetter)).append("\">")
                      .append(formatDelta(pct)).append("</td>")
                      .append("<td class=\"").append(cohensDCls(d)).append("\">")
                      .append(formatCohensD(d)).append("</td>")
                      .append("<td class=\"").append(pValueCls(p)).append("\">")
                      .append(formatPValue(p)).append("</td>");
                } else {
                    sb.append("<td>—</td><td>—</td><td>—</td><td>—</td>");
                }
                sb.append("</tr>\n");
            }
            sb.append("</tbody></table></div>\n");
        }

        sb.append("</body></html>\n");
        return sb.toString();
    }

    static String formatStats(Stats s, int decimals) {
        if (s == null || s.n == 0 || Double.isNaN(s.mean)) return "<span class=\"na\">—</span>";
        StringBuilder out = new StringBuilder();
        // Tooltip with the 95% CI for the mean.
        double half = StatsHelper.ci95HalfWidth(s.stddev, s.n);
        boolean haveCI = !Double.isNaN(half);
        if (haveCI) {
            String ci = String.format(Locale.ROOT, "95%% CI [%s, %s]",
                fmtNum(s.mean - half, 2), fmtNum(s.mean + half, 2));
            out.append("<span title=\"").append(ci).append("\">");
        }
        out.append(String.format(Locale.ROOT, "%,." + decimals + "f", s.mean));
        if (!Double.isNaN(s.stddev)) {
            out.append(" <span class=\"sd\">±").append(String.format(Locale.ROOT, "%." + decimals + "f", s.stddev)).append("</span>");
            double cv = StatsHelper.cvPercent(s.mean, s.stddev);
            if (!Double.isNaN(cv)) {
                out.append(" <span class=\"cv ").append(cvCls(cv)).append("\">(CV ")
                   .append(formatCv(cv)).append(")</span>");
            }
        }
        if (haveCI) out.append("</span>");
        return out.toString();
    }

    static String fmtNum(double v, int decimals) {
        return String.format(Locale.ROOT, "%,." + decimals + "f", v);
    }

    static String formatCv(double cv) {
        if (cv < 1)  return String.format(Locale.ROOT, "%.2f%%", cv);
        if (cv < 10) return String.format(Locale.ROOT, "%.1f%%", cv);
        return       String.format(Locale.ROOT, "%.0f%%", cv);
    }

    /** <5% stable, 5–15% moderate, >15% noisy. */
    static String cvCls(double cv) {
        if (cv < 5)  return "cv-stable";
        if (cv < 15) return "cv-moderate";
        return "cv-noisy";
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

    static String formatPValue(double p) {
        if (Double.isNaN(p)) return "—";
        if (p < 0.0001) return "&lt;0.0001";
        return String.format(Locale.ROOT, "%.4f", p);
    }

    static String pValueCls(double p) {
        if (Double.isNaN(p)) return "";
        if (p < 0.001) return "p-very-sig";
        if (p < 0.05)  return "p-sig";
        return "p-noisy";
    }

    static String formatAbs(double v, int decimals) {
        return String.format(Locale.ROOT, "%+,." + decimals + "f", v);
    }

    static String formatDelta(double pct) {
        return String.format(Locale.ROOT, "%+.1f%%", pct);
    }

    static String deltaCls(double pct, boolean lowerIsBetter) {
        boolean improvement = lowerIsBetter ? pct < 0 : pct > 0;
        if (Math.abs(pct) < 0.5) return "delta-flat";
        return improvement ? "delta-up" : "delta-down";
    }

    static String esc(Object v) {
        if (v == null) return "<span class=\"na\">—</span>";
        String s = v.toString();
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }

    static final String STYLE = """
        <style>
          body { font: 14px/1.45 system-ui, -apple-system, sans-serif; max-width: 1200px; margin: 1.5rem auto; padding: 0 1rem; color: #222; }
          h1 { margin-top: 0; }
          h2 { margin-top: 1.6rem; border-bottom: 1px solid #ddd; padding-bottom: 0.2rem; }
          h3 { margin-top: 1rem; font-size: 1rem; color: #333; }
          .hint { color: #666; font-size: 0.92em; }
          .summary { padding: 0.6rem 0.8rem; background: #f6f7f9; border-left: 3px solid #4a6b9b; margin: 0.6rem 0; }
          table { border-collapse: collapse; width: 100%; margin: 0.4rem 0; }
          th, td { padding: 0.35rem 0.6rem; text-align: right; border-bottom: 1px solid #eee; }
          th { font-weight: 500; }
          th.metric { text-align: left; }
          table.meta th { text-align: left; width: 12rem; }
          table.meta td { text-align: left; font-family: ui-monospace, SFMono-Regular, monospace; font-size: 0.92em; }
          table.meta td.differ { background: #fff8e1; }
          .scroll { overflow-x: auto; }
          .win  { background: #B8E0BA40; }
          .miss { background: #f0f0f0; color: #999; }
          .sd   { color: #888; font-size: 0.85em; }
          .unit { color: #888; font-weight: normal; font-size: 0.85em; }
          .na   { color: #aaa; }
          .delta-up   { color: #2a8c2f; font-weight: 500; }
          .delta-down { color: #c23b22; font-weight: 500; }
          .delta-flat { color: #888; }
          .win-key  { background: #B8E0BA40; padding: 0 4px; border-radius: 3px; }
          .miss-key { background: #f0f0f0; padding: 0 4px; border-radius: 3px; }
          .cv { font-size: 0.78em; }
          .cv-stable   { color: #2a8c2f; }
          .cv-moderate { color: #b58a14; }
          .cv-noisy    { color: #c23b22; font-weight: 500; }
          .d-negligible { color: #888; }
          .d-small      { color: #555; }
          .d-medium     { color: #b58a14; font-weight: 500; }
          .d-large      { color: #2a8c2f; font-weight: 500; }
          .d-label      { color: #888; font-weight: normal; font-size: 0.85em; }
          .p-very-sig { background: #B8E0BA60; padding: 0 0.2em; border-radius: 3px; }
          .p-sig      { background: #B8E0BA30; padding: 0 0.2em; border-radius: 3px; }
          .p-noisy    { color: #999; }
        </style>
        """;
}
