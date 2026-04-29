package eu.inqudium.core.element.trafficshaper;

import eu.inqudium.core.element.trafficshaper.strategy.SchedulingState;
import eu.inqudium.core.element.trafficshaper.strategy.SchedulingStrategy;

import java.time.Duration;
import java.time.Instant;

/**
 * Convenience facade that delegates to the {@link SchedulingStrategy}
 * obtained from the {@link TrafficShaperConfig}.
 *
 * <p>This class exists for backward compatibility and for use cases where
 * static method calls are preferred over extracting the strategy manually.
 * New code may call the strategy directly via {@code config.strategy()}.
 */
public final class TrafficShaperCore {

    private TrafficShaperCore() {
        // Utility class — not instantiable
    }

    public static <S extends SchedulingState> ThrottlePermission<S> schedule(
            S state, TrafficShaperConfig<S> config, Instant now) {
        return config.strategy().schedule(state, config, now);
    }

    public static <S extends SchedulingState> S recordExecution(
            S state, TrafficShaperConfig<S> config) {
        return config.strategy().recordExecution(state);
    }

    public static <S extends SchedulingState> S reset(
            S state, TrafficShaperConfig<S> config, Instant now) {
        return config.strategy().reset(state, config, now);
    }

    public static <S extends SchedulingState> Duration estimateWait(
            S state, TrafficShaperConfig<S> config, Instant now) {
        return config.strategy().estimateWait(state, config, now);
    }

    public static <S extends SchedulingState> int queueDepth(
            S state, TrafficShaperConfig<S> config) {
        return config.strategy().queueDepth(state);
    }

    public static <S extends SchedulingState> boolean isUnboundedQueueWarning(
            S state, TrafficShaperConfig<S> config, Instant now) {
        return config.strategy().isUnboundedQueueWarning(state, config, now);
    }

    public static double currentRatePerSecond(TrafficShaperConfig<?> config) {
        return config.ratePerSecond();
    }
}
