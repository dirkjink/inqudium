package eu.inqudium.config.validation.rules;

import eu.inqudium.config.runtime.InqConfigView;
import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.validation.CrossComponentRule;
import eu.inqudium.config.validation.DiagnosticFinding;
import eu.inqudium.config.validation.Severity;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Class-4 cross-component rule: warns when a runtime hosts many independently-limited bulkheads
 * but no single bulkhead acts as an aggregate cap.
 *
 * <p>Each bulkhead protects one downstream resource by capping its concurrent calls. If a
 * configuration contains many bulkheads, each generously sized, the <em>combined</em> in-flight
 * load the JVM may carry is the sum of every bulkhead's limit — even though no single bulkhead
 * alone looks dangerous. Operators sometimes notice this only when one slow downstream causes
 * threads to pile up across multiple bulkheads at once.
 *
 * <p>The rule fires when the bulkhead count exceeds {@link #MIN_BULKHEAD_COUNT_THRESHOLD} and
 * the sum of their {@code maxConcurrentCalls} exceeds {@link #AGGREGATE_PERMITS_THRESHOLD}.
 * The thresholds are deliberate canary values, not a tuned policy — see ADR-027 for the design
 * context. The single canary exists primarily to demonstrate the cross-component-rule mechanics
 * end-to-end; richer rules and operator-tunable thresholds belong in follow-up work.
 *
 * <p>The "aggregate cap" exemption is intentionally absent — there is no standard way yet to
 * mark a bulkhead as the global cap. As the use case becomes concrete the rule can grow to
 * recognise a tag like {@code "aggregate-limit"} and skip the warning when it is present.
 * <em>TODO: tag-based aggregate-limit recognition.</em>
 */
public final class MultipleBulkheadsNoAggregateLimitRule implements CrossComponentRule {

    /** Stable rule identifier. */
    public static final String ID = "MULTIPLE_BULKHEADS_NO_AGGREGATE_LIMIT";

    /**
     * Minimum bulkhead count for the rule to engage. Below this, the question of an aggregate
     * cap is moot — a small number of bulkheads is normal.
     */
    static final int MIN_BULKHEAD_COUNT_THRESHOLD = 5;

    /**
     * Aggregate {@code maxConcurrentCalls} threshold that triggers the warning. Picked as a
     * round canary value: 100 in-flight calls is a plausible upper bound for "should fit on a
     * traditional thread pool"; beyond that, virtual threads or aggregate caps deserve
     * attention.
     */
    static final int AGGREGATE_PERMITS_THRESHOLD = 100;

    @Override
    public String ruleId() {
        return ID;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARNING;
    }

    @Override
    public List<DiagnosticFinding> check(InqConfigView view) {
        long count = 0;
        long aggregatePermits = 0;
        Set<String> names = new HashSet<>();
        for (BulkheadSnapshot s : (Iterable<BulkheadSnapshot>) view.bulkheads()::iterator) {
            count++;
            aggregatePermits += s.maxConcurrentCalls();
            names.add(s.name());
        }
        if (count <= MIN_BULKHEAD_COUNT_THRESHOLD
                || aggregatePermits <= AGGREGATE_PERMITS_THRESHOLD) {
            return List.of();
        }
        return List.of(new DiagnosticFinding(
                ruleId(),
                defaultSeverity(),
                names,
                "Configuration declares " + count + " bulkheads with an aggregate "
                        + "maxConcurrentCalls of " + aggregatePermits
                        + ". No bulkhead is identified as an aggregate cap, so the JVM may "
                        + "host up to " + aggregatePermits + " concurrent in-flight calls "
                        + "across all of them simultaneously. Consider adding a higher-level "
                        + "bulkhead that wraps the others, or tightening individual limits.",
                Map.of(
                        "bulkheadCount", count,
                        "aggregatePermits", aggregatePermits)));
    }
}
