package eu.inqudium.config.validation.rules;

import eu.inqudium.config.snapshot.BulkheadSnapshot;
import eu.inqudium.config.validation.ConsistencyRule;
import eu.inqudium.config.validation.Severity;
import eu.inqudium.config.validation.ValidationFinding;

import java.time.Duration;
import java.util.Optional;

/**
 * Class-3 rule: a bulkhead derived from the {@code protective} preset paired with a non-trivial
 * {@code maxWaitDuration} contradicts the preset's intent.
 *
 * <p>The {@code protective} preset is the fail-fast baseline ({@code maxWaitDuration =
 * Duration.ZERO}). If a user starts from {@code .protective()} but then adjusts
 * {@code maxWaitDuration} to a meaningful wait window, they are almost certainly mismatched —
 * either the preset choice or the wait override is wrong. The rule warns rather than errors
 * because each individual value is valid; only the combination is suspect.
 *
 * <p>The threshold for "non-trivial" is {@code 100&nbsp;ms} — anything up to that point still
 * fits a fail-fast story (it is roughly the human reaction time and the upper bound of
 * acceptable per-call latency for most interactive workloads). Beyond it, the user has clearly
 * traded fail-fast for queueing.
 */
public final class BulkheadProtectiveWithLongWaitRule
        implements ConsistencyRule<BulkheadSnapshot> {

    /**
     * Wait durations strictly above this threshold trigger the warning. Picked so that the
     * common "tens of milliseconds" tolerance does not generate noise.
     */
    static final Duration LONG_WAIT_THRESHOLD = Duration.ofMillis(100);

    /** Stable identifier — used in {@link ValidationFinding#ruleId()} and per-rule overrides. */
    public static final String ID = "BULKHEAD_PROTECTIVE_WITH_LONG_WAIT";

    @Override
    public String ruleId() {
        return ID;
    }

    @Override
    public Class<BulkheadSnapshot> appliesTo() {
        return BulkheadSnapshot.class;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARNING;
    }

    @Override
    public Optional<ValidationFinding> check(BulkheadSnapshot s) {
        if (!"protective".equals(s.derivedFromPreset())) {
            return Optional.empty();
        }
        if (s.maxWaitDuration().compareTo(LONG_WAIT_THRESHOLD) <= 0) {
            return Optional.empty();
        }
        return Optional.of(new ValidationFinding(
                ruleId(),
                defaultSeverity(),
                s.name(),
                "Bulkhead '" + s.name() + "' uses 'protective' preset (intent: fail fast) "
                        + "but maxWaitDuration is " + s.maxWaitDuration() + ". "
                        + "Consider 'balanced' for non-zero wait durations, or set "
                        + "maxWaitDuration to Duration.ZERO to honour the protective intent."));
    }
}
