package eu.inqudium.core.element.bulkhead.dsl;

import java.time.Duration;

class DefaultBulkheadProtection implements BulkheadProtection {

  private int maxConcurrentCalls = 25; // Fallback
  private Duration maxWaitDuration = Duration.ofSeconds(0); // Fallback: Fail fast

  @Override
  public BulkheadProtection limitingConcurrentCallsTo(int maxCalls) {
    this.maxConcurrentCalls = maxCalls;
    return this;
  }

  @Override
  public BulkheadProtection waitingAtMostFor(Duration maxWait) {
    this.maxWaitDuration = maxWait;
    return this;
  }

  @Override
  public BulkheadConfig applyStrictProfile() {
    // Strict: Very low concurrency, absolute fail-fast (no waiting)
    return new BulkheadConfig(10, Duration.ZERO);
  }

  @Override
  public BulkheadConfig applyBalancedProfile() {
    // Balanced: Good concurrency, short queueing allowed
    return new BulkheadConfig(50, Duration.ofMillis(500));
  }

  @Override
  public BulkheadConfig applyPermissiveProfile() {
    // Permissive: High concurrency, long waiting allowed
    return new BulkheadConfig(200, Duration.ofSeconds(5));
  }

  @Override
  public BulkheadConfig apply() {
    return new BulkheadConfig(maxConcurrentCalls, maxWaitDuration);
  }
}