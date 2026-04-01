package eu.inqudium.core.trafficshaper.strategy;

import eu.inqudium.core.trafficshaper.ThrottleMode;
import eu.inqudium.core.trafficshaper.ThrottlePermission;
import eu.inqudium.core.trafficshaper.TrafficShaperConfig;

import java.time.Duration;
import java.time.Instant;

/**
 * Token bucket shaping strategy — allows controlled bursts then shapes excess.
 *
 * <p>Maintains a bucket of tokens that refills at the configured rate.
 * Requests consume one token each:
 * <ul>
 *   <li>Tokens available → request proceeds immediately (burst)</li>
 *   <li>Bucket empty → request is delayed until the next token refills</li>
 * </ul>
 *
 * <p>This differs from the {@link LeakyBucketStrategy} which never allows bursts:
 * <pre>
 *   Leaky Bucket:   ──|──|──|──|──|──|──►  (always even)
 *   Token Bucket:   ──|||||──|──|──|──|──►  (burst then even)
 * </pre>
 *
 * @param burstCapacity maximum tokens in the bucket (controls burst size)
 */
public record TokenBucketShapingStrategy(int burstCapacity) implements SchedulingStrategy<TokenBucketShapingState> {

  public TokenBucketShapingStrategy {
    if (burstCapacity < 1) {
      throw new IllegalArgumentException("burstCapacity must be >= 1, got " + burstCapacity);
    }
  }

  @Override
  public TokenBucketShapingState initial(TrafficShaperConfig<TokenBucketShapingState> config, Instant now) {
    return TokenBucketShapingState.initial(burstCapacity, now);
  }

  @Override
  public ThrottlePermission<TokenBucketShapingState> schedule(
      TokenBucketShapingState state,
      TrafficShaperConfig<TokenBucketShapingState> config,
      Instant now) {

    // Refill tokens based on elapsed time
    TokenBucketShapingState refilled = refill(state, config, now);

    if (refilled.availableTokens() >= 1.0) {
      // Burst path: consume a token, admit immediately
      TokenBucketShapingState consumed = refilled
          .withTokensConsumed(refilled.availableTokens() - 1.0)
          .withScheduledImmediate(now.plus(config.interval()));
      return ThrottlePermission.immediate(consumed, now);
    }

    // No tokens — compute delay until next token arrives
    double deficit = 1.0 - refilled.availableTokens();
    long waitNanos = (long) (deficit * config.interval().toNanos());
    Duration waitDuration = Duration.ofNanos(Math.max(waitNanos, 1));

    // Check overflow
    if (!config.isQueuingAllowed()
        && config.throttleMode() == ThrottleMode.SHAPE_AND_REJECT_OVERFLOW) {
      return ThrottlePermission.rejected(refilled.withRequestRejected(), waitDuration);
    }

    if (config.throttleMode() == ThrottleMode.SHAPE_AND_REJECT_OVERFLOW
        && shouldReject(refilled, config, waitDuration)) {
      return ThrottlePermission.rejected(refilled.withRequestRejected(), waitDuration);
    }

    // Admit with delay: consume the token (goes negative/zero) and schedule
    Instant slot = now.plusNanos(waitNanos);
    TokenBucketShapingState delayed = refilled
        .withTokensConsumed(refilled.availableTokens() - 1.0)
        .withScheduledDelayed(slot.plus(config.interval()));
    return ThrottlePermission.delayed(delayed, waitDuration, slot);
  }

  @Override
  public TokenBucketShapingState recordExecution(TokenBucketShapingState state) {
    return state.withRequestDequeued();
  }

  @Override
  public TokenBucketShapingState reset(
      TokenBucketShapingState state,
      TrafficShaperConfig<TokenBucketShapingState> config,
      Instant now) {
    return state.withNextEpoch(now);
  }

  @Override
  public Duration estimateWait(
      TokenBucketShapingState state,
      TrafficShaperConfig<TokenBucketShapingState> config,
      Instant now) {
    TokenBucketShapingState refilled = refill(state, config, now);
    if (refilled.availableTokens() >= 1.0) return Duration.ZERO;
    double deficit = 1.0 - refilled.availableTokens();
    return Duration.ofNanos((long) (deficit * config.interval().toNanos()));
  }

  @Override
  public int queueDepth(TokenBucketShapingState state) {
    return state.queueDepth();
  }

  @Override
  public boolean isUnboundedQueueWarning(
      TokenBucketShapingState state,
      TrafficShaperConfig<TokenBucketShapingState> config,
      Instant now) {
    return checkUnboundedWarning(state, config, now);
  }

  private TokenBucketShapingState refill(
      TokenBucketShapingState state,
      TrafficShaperConfig<TokenBucketShapingState> config,
      Instant now) {
    long elapsedNanos = Duration.between(state.lastRefillTime(), now).toNanos();
    if (elapsedNanos <= 0) return state;

    double tokensToAdd = (double) elapsedNanos / config.interval().toNanos();
    double newTokens = Math.min(state.availableTokens() + tokensToAdd, burstCapacity);
    return state.withRefill(newTokens, now);
  }
}
