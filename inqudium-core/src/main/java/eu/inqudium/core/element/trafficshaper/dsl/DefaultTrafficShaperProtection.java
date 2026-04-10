package eu.inqudium.core.element.trafficshaper.dsl;

import java.time.Duration;

class DefaultTrafficShaperProtection implements TrafficShaperProtection {

    private int permittedCalls = 50; // Fallback
    private Duration evaluationPeriod = Duration.ofSeconds(1); // Fallback: 50 calls/sec
    private Duration maxWaitDuration = Duration.ofMillis(500); // Fallback: Short queueing

    @Override
    public TrafficShaperProtection permittingCalls(int limit) {
        this.permittedCalls = limit;
        return this;
    }

    @Override
    public TrafficShaperProtection withinPeriod(Duration period) {
        this.evaluationPeriod = period;
        return this;
    }

    @Override
    public TrafficShaperProtection queueingForAtMost(Duration maxWait) {
        this.maxWaitDuration = maxWait;
        return this;
    }

    @Override
    public TrafficShaperConfig applyStrictProfile() {
        // Strict: Very tight limit, no queueing allowed (drop immediately)
        return new TrafficShaperConfig(10, Duration.ofSeconds(1), Duration.ZERO);
    }

    @Override
    public TrafficShaperConfig applyBalancedProfile() {
        // Balanced: Standard rate, moderate queueing to smooth out bursts
        return new TrafficShaperConfig(100, Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Override
    public TrafficShaperConfig applyPermissiveProfile() {
        // Permissive: High limits, long queueing for heavy background loads
        return new TrafficShaperConfig(500, Duration.ofSeconds(1), Duration.ofSeconds(10));
    }

    @Override
    public TrafficShaperConfig apply() {
        return new TrafficShaperConfig(permittedCalls, evaluationPeriod, maxWaitDuration);
    }
}
