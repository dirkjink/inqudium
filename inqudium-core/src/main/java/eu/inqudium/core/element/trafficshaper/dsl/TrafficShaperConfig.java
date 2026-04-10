package eu.inqudium.core.element.trafficshaper.dsl;

import java.time.Duration;

/**
 * The immutable configuration for a Traffic Shaper instance.
 */
public record TrafficShaperConfig(
        String name,
        int permittedCalls,
        Duration evaluationPeriod,
        Duration maxWaitDuration
) {
}
