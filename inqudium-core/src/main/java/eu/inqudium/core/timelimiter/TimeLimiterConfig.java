package eu.inqudium.core.timelimiter;

import eu.inqudium.core.InqConfig;
import eu.inqudium.core.compatibility.InqCompatibility;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Immutable configuration for the Time Limiter element (ADR-010).
 *
 * <p>The time limiter bounds the caller's wait time, not the operation's
 * execution time. No thread interrupts — orphaned operations continue to
 * completion and can be handled via the orphaned call handlers.
 *
 * @since 0.1.0
 */
public final class TimeLimiterConfig implements InqConfig {

  private static final TimeLimiterConfig DEFAULTS = TimeLimiterConfig.builder().build();

  private final Duration timeoutDuration;
  private final BiConsumer<OrphanedCallContext, Object> onOrphanedResult;
  private final BiConsumer<OrphanedCallContext, Throwable> onOrphanedError;
  private final InqCompatibility compatibility;

  private TimeLimiterConfig(Builder b) {
    this.timeoutDuration = b.timeoutDuration;
    this.onOrphanedResult = b.onOrphanedResult;
    this.onOrphanedError = b.onOrphanedError;
    this.compatibility = b.compatibility;
  }

  public static TimeLimiterConfig ofDefaults() {
    return DEFAULTS;
  }

  public static Builder builder() {
    return new Builder();
  }

  public Duration getTimeoutDuration() {
    return timeoutDuration;
  }

  public BiConsumer<OrphanedCallContext, Object> getOnOrphanedResult() {
    return onOrphanedResult;
  }

  public BiConsumer<OrphanedCallContext, Throwable> getOnOrphanedError() {
    return onOrphanedError;
  }

  @Override
  public InqCompatibility getCompatibility() {
    return compatibility;
  }

  /**
   * Context provided to orphaned call handlers after a timeout fires.
   *
   * @param elementName        the time limiter instance name
   * @param configuredDuration the configured timeout
   * @param actualDuration     how long the operation actually took
   * @param callId             the call's unique identifier (ADR-003)
   */
  public record OrphanedCallContext(
      String elementName,
      Duration configuredDuration,
      Duration actualDuration,
      String callId
  ) {
  }

  public static final class Builder {
    private Duration timeoutDuration = Duration.ofSeconds(5);
    private BiConsumer<OrphanedCallContext, Object> onOrphanedResult = null;
    private BiConsumer<OrphanedCallContext, Throwable> onOrphanedError = null;
    private InqCompatibility compatibility = InqCompatibility.ofDefaults();

    private Builder() {
    }

    public Builder timeoutDuration(Duration duration) {
      this.timeoutDuration = Objects.requireNonNull(duration);
      return this;
    }

    public Builder onOrphanedResult(BiConsumer<OrphanedCallContext, Object> handler) {
      this.onOrphanedResult = handler;
      return this;
    }

    public Builder onOrphanedError(BiConsumer<OrphanedCallContext, Throwable> handler) {
      this.onOrphanedError = handler;
      return this;
    }

    public Builder compatibility(InqCompatibility c) {
      this.compatibility = Objects.requireNonNull(c);
      return this;
    }

    public TimeLimiterConfig build() {
      return new TimeLimiterConfig(this);
    }
  }
}
