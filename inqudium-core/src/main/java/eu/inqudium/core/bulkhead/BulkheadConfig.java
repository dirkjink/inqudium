package eu.inqudium.core.bulkhead;

import eu.inqudium.core.InqConfig;
import eu.inqudium.core.compatibility.InqCompatibility;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for the Bulkhead element (ADR-020).
 *
 * @since 0.1.0
 */
public final class BulkheadConfig implements InqConfig {

    private static final BulkheadConfig DEFAULTS = BulkheadConfig.builder().build();

    private final int maxConcurrentCalls;
    private final Duration maxWaitDuration;
    private final InqCompatibility compatibility;

    private BulkheadConfig(Builder b) {
        this.maxConcurrentCalls = b.maxConcurrentCalls;
        this.maxWaitDuration = b.maxWaitDuration;
        this.compatibility = b.compatibility;
    }

    public static BulkheadConfig ofDefaults() { return DEFAULTS; }
    public static Builder builder() { return new Builder(); }

    public int getMaxConcurrentCalls() { return maxConcurrentCalls; }
    public Duration getMaxWaitDuration() { return maxWaitDuration; }
    @Override public InqCompatibility getCompatibility() { return compatibility; }

    public static final class Builder {
        private int maxConcurrentCalls = 25;
        private Duration maxWaitDuration = Duration.ZERO;
        private InqCompatibility compatibility = InqCompatibility.ofDefaults();

        private Builder() {}

        public Builder maxConcurrentCalls(int max) { this.maxConcurrentCalls = max; return this; }
        public Builder maxWaitDuration(Duration duration) { this.maxWaitDuration = Objects.requireNonNull(duration); return this; }
        public Builder compatibility(InqCompatibility c) { this.compatibility = Objects.requireNonNull(c); return this; }

        public BulkheadConfig build() { return new BulkheadConfig(this); }
    }
}
