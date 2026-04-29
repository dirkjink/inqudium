package eu.inqudium.config.dsl;

import eu.inqudium.config.snapshot.CoDelStrategyConfig;

import java.time.Duration;
import java.util.Objects;

/**
 * Fluent sub-builder that produces a {@link CoDelStrategyConfig}.
 *
 * <p>Defaults match the {@code balanced} preset of the deprecated phase-1
 * {@code CoDelBulkheadStrategy}: {@code targetDelay = 50 ms}, {@code interval = 500 ms}. Both
 * setters validate at the call site so a misconfiguration surfaces at the offending DSL line.
 */
public final class CoDelConfigBuilder {

    private Duration targetDelay = Duration.ofMillis(50);
    private Duration interval = Duration.ofMillis(500);

    CoDelConfigBuilder() {
        // Package-private — instantiated only by BulkheadBuilderBase when the user calls
        // .codel(...).
    }

    /**
     * @param value the latency budget above which queued calls become drop candidates;
     *              non-null and strictly positive.
     * @return this builder, for chaining.
     */
    public CoDelConfigBuilder targetDelay(Duration value) {
        Objects.requireNonNull(value, "targetDelay");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "targetDelay must be strictly positive, got: " + value);
        }
        this.targetDelay = value;
        return this;
    }

    /**
     * @param value the consecutive-overshoot window required before a drop decision is taken;
     *              non-null and strictly positive.
     * @return this builder, for chaining.
     */
    public CoDelConfigBuilder interval(Duration value) {
        Objects.requireNonNull(value, "interval");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "interval must be strictly positive, got: " + value);
        }
        this.interval = value;
        return this;
    }

    /**
     * Materialize the strategy config from the current builder state. Called by
     * {@link BulkheadBuilderBase} when the user's {@code .codel(...)} call returns; user code
     * does not call this method directly.
     */
    CoDelStrategyConfig build() {
        return new CoDelStrategyConfig(targetDelay, interval);
    }
}
