package eu.inqudium.config;

import eu.inqudium.config.validation.BuildReport;
import eu.inqudium.config.validation.ValidationFinding;

import java.util.Objects;

/**
 * Thrown by {@code Inqudium.configure()...build()} when validation finds errors that prevent
 * the runtime from being constructed.
 *
 * <p>The exception carries the full {@link BuildReport} so callers can inspect every finding
 * (not just the first error). The exception's message lists the first error for quick
 * recognition; production callers should always reach for {@link #report()} rather than parsing
 * the message.
 *
 * <p>"Errors" here means {@link eu.inqudium.config.validation.Severity#ERROR ERROR}-level
 * findings. With {@code strict()} mode enabled at build time, warnings are elevated to errors
 * before this check runs — a build that would have produced only warnings in lenient mode
 * therefore aborts in strict mode.
 */
public final class ConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient BuildReport report;

    /**
     * @param report the full build report; non-null. Must contain at least one error-level
     *               finding — passing a successful report through here is a programming bug.
     */
    public ConfigurationException(BuildReport report) {
        super(buildMessage(report));
        this.report = Objects.requireNonNull(report, "report");
    }

    /**
     * @return the full build report. Inspect {@link BuildReport#findings()} for the complete
     *         list, {@link BuildReport#errors()} for just the error-level findings.
     */
    public BuildReport report() {
        return report;
    }

    private static String buildMessage(BuildReport report) {
        Objects.requireNonNull(report, "report");
        ValidationFinding firstError = report.errors().findFirst().orElse(null);
        long total = report.findings().size();
        if (firstError == null) {
            return "ConfigurationException created without any error findings (programming bug); "
                    + "report contains " + total + " finding(s)";
        }
        long errorCount = report.errors().count();
        StringBuilder sb = new StringBuilder();
        sb.append("Configuration build failed with ").append(errorCount).append(" error");
        if (errorCount > 1) {
            sb.append("s");
        }
        sb.append(": [").append(firstError.ruleId()).append("] ").append(firstError.message());
        if (errorCount > 1) {
            sb.append(" (and ").append(errorCount - 1).append(" more — see report().findings())");
        }
        return sb.toString();
    }
}
