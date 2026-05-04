import org.apache.commons.math3.distribution.TDistribution;

/**
 * Statistical helpers shared by GenerateReport.java and CompareRuns.java.
 * Loaded into both scripts via jbang's //SOURCES directive.
 *
 * Conventions:
 *   - All inputs are sample stats (mean, sample stddev, n).
 *   - Returns NaN where the formula is undefined (n<2, zero variance,
 *     zero mean for ratios, etc.) so callers can render an em-dash
 *     instead of crashing.
 *   - Welch's t-test does NOT assume equal variances and uses the
 *     Welch-Satterthwaite degrees-of-freedom approximation.
 */
public final class StatsHelper {
    private StatsHelper() {}

    /** Coefficient of variation (relative dispersion), as a percentage. */
    public static double cvPercent(double mean, double stddev) {
        if (Double.isNaN(mean) || Double.isNaN(stddev) || mean == 0.0) return Double.NaN;
        return Math.abs(stddev / mean) * 100.0;
    }

    /**
     * Half-width of the two-tailed 95% confidence interval for the mean,
     * using the t-distribution with n-1 degrees of freedom.
     */
    public static double ci95HalfWidth(double stddev, int n) {
        if (n < 2 || Double.isNaN(stddev)) return Double.NaN;
        TDistribution td = new TDistribution(n - 1);
        return td.inverseCumulativeProbability(0.975) * stddev / Math.sqrt(n);
    }

    /**
     * Cohen's d using pooled sample standard deviation. Sign reflects
     * (m1 - m2): positive when the first sample is larger. Magnitude
     * convention: |d| < 0.2 negligible · 0.2-0.5 small · 0.5-0.8 medium
     * · ≥0.8 large.
     */
    public static double cohensD(double m1, double sd1, int n1,
                                  double m2, double sd2, int n2) {
        if (n1 < 2 || n2 < 2) return Double.NaN;
        double pooledVar = ((n1 - 1) * sd1 * sd1 + (n2 - 1) * sd2 * sd2)
                            / (double) (n1 + n2 - 2);
        if (pooledVar <= 0.0 || Double.isNaN(pooledVar)) return Double.NaN;
        return (m1 - m2) / Math.sqrt(pooledVar);
    }

    /**
     * Two-tailed Welch's t-test p-value (no equal-variance assumption).
     * Returns NaN if either sample has fewer than 2 observations or if
     * both samples have zero variance and equal means.
     */
    public static double welchPValue(double m1, double sd1, int n1,
                                      double m2, double sd2, int n2) {
        if (n1 < 2 || n2 < 2) return Double.NaN;
        double s1Sq = sd1 * sd1, s2Sq = sd2 * sd2;
        double seSq = s1Sq / n1 + s2Sq / n2;
        if (seSq <= 0.0) {
            return m1 == m2 ? 1.0 : 0.0;
        }
        double t = (m1 - m2) / Math.sqrt(seSq);
        // Welch-Satterthwaite degrees of freedom.
        double v1Term = (s1Sq / n1); v1Term = (v1Term * v1Term) / (n1 - 1);
        double v2Term = (s2Sq / n2); v2Term = (v2Term * v2Term) / (n2 - 1);
        double df = (seSq * seSq) / (v1Term + v2Term);
        if (Double.isNaN(df) || df <= 0.0) return Double.NaN;
        TDistribution td = new TDistribution(df);
        return 2.0 * (1.0 - td.cumulativeProbability(Math.abs(t)));
    }

    /** Magnitude label for Cohen's d per the standard convention. */
    public static String cohensDLabel(double d) {
        if (Double.isNaN(d)) return "";
        double a = Math.abs(d);
        if (a < 0.2) return "negligible";
        if (a < 0.5) return "small";
        if (a < 0.8) return "medium";
        return "large";
    }
}
